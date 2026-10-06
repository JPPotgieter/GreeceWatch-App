// HTTP endpoint the watch calls. Authenticated with a shared bearer token.

import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { dedupeKey, formatAlert, shouldSendPin, validateAlert } from './messages.js';

const MAX_BODY_BYTES = 4096;
const DEDUPE_TTL_MS = 6 * 60 * 60 * 1000;

function tokenMatches(header, token) {
  const given = Buffer.from(String(header || '').replace(/^Bearer\s+/i, ''));
  const expected = Buffer.from(token);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('body too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(Object.assign(new Error('invalid JSON'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function reply(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}

// `sender` needs `send(text, pin) -> 'sent' | 'queued'`, `connected` and `listGroups()`.
export function createServer({ token, sender, log = console.log }) {
  const seen = new Map();

  function alreadyHandled(key) {
    const now = Date.now();
    for (const [k, t] of seen) if (now - t > DEDUPE_TTL_MS) seen.delete(k);
    return seen.has(key);
  }

  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://relay');

      if (req.method === 'GET' && url.pathname === '/health') {
        return reply(res, 200, { ok: true, whatsapp: sender.connected });
      }

      if (!tokenMatches(req.headers.authorization, token)) {
        return reply(res, 401, { error: 'unauthorized' });
      }

      if (req.method === 'GET' && url.pathname === '/groups') {
        if (!sender.connected) return reply(res, 503, { error: 'whatsapp not connected' });
        return reply(res, 200, { groups: await sender.listGroups() });
      }

      if (req.method === 'POST' && url.pathname === '/alert') {
        const alert = await readJson(req);
        const problem = validateAlert(alert);
        if (problem) return reply(res, 400, { error: problem });

        const key = dedupeKey(alert);
        if (alreadyHandled(key)) return reply(res, 200, { status: 'duplicate' });

        const pin = shouldSendPin(alert) ? { lat: alert.lat, lon: alert.lon } : null;
        const status = await sender.send(formatAlert(alert), pin);
        seen.set(key, Date.now());
        log(`${alert.type} from ${alert.name} (${key}): ${status}`);
        return reply(res, status === 'sent' ? 200 : 202, { status });
      }

      return reply(res, 404, { error: 'not found' });
    } catch (e) {
      log(`request failed: ${e.message}`);
      return reply(res, e.status || 500, { error: e.message });
    }
  });
}
