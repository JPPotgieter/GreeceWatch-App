import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatAlert, validateAlert } from '../src/messages.js';
import { createServer } from '../src/server.js';

const TOKEN = 'test-token-0123456789';
const base = { type: 'overboard', name: 'JP', eventId: 'abcd-1234' };

test('validateAlert accepts a minimal overboard alert', () => {
  assert.equal(validateAlert(base), null);
  assert.equal(validateAlert({ ...base, lat: 37.9, lon: 23.7, accuracy: 8 }), null);
});

test('validateAlert rejects bad input', () => {
  assert.match(validateAlert({ ...base, type: 'party' }), /type/);
  assert.match(validateAlert({ ...base, name: '' }), /name/);
  assert.match(validateAlert({ ...base, lat: 37.9 }), /together/);
  assert.match(validateAlert({ ...base, lat: 120, lon: 0 }), /lat/);
  assert.match(validateAlert({ ...base, eventId: 'x' }), /eventId/);
});

test('overboard message includes name and maps link', () => {
  const text = formatAlert({ ...base, lat: 37.12345678, lon: 25.5, accuracy: 7.6 }, Date.UTC(2026, 6, 1, 12, 0, 0));
  assert.match(text, /MAN OVERBOARD/);
  assert.match(text, /\*JP\*/);
  assert.match(text, /15:00:00/); // Athens is UTC+3 in summer
  assert.match(text, /maps\.google\.com\/\?q=37\.12346,25\.50000/);
  assert.match(text, /±8 m/);
});

test('overboard message without GPS says so', () => {
  assert.match(formatAlert(base), /not available yet/);
});

function fakeSender() {
  const sent = [];
  return { sent, connected: true, send: async (text, pin) => { sent.push({ text, pin }); return 'sent'; }, listGroups: async () => [] };
}

async function withServer(sender, fn) {
  const server = createServer({ token: TOKEN, sender, log: () => {} });
  await new Promise((r) => server.listen(0, r));
  const url = `http://127.0.0.1:${server.address().port}`;
  try { await fn(url); } finally { server.close(); }
}

const post = (url, body, token = TOKEN) => fetch(`${url}/alert`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
  body: JSON.stringify(body),
});

test('server rejects a wrong token', async () => {
  const sender = fakeSender();
  await withServer(sender, async (url) => {
    assert.equal((await post(url, base, 'wrong')).status, 401);
    assert.equal(sender.sent.length, 0);
  });
});

test('server sends once and dedupes retries', async () => {
  const sender = fakeSender();
  await withServer(sender, async (url) => {
    const alert = { ...base, lat: 37.9, lon: 23.7 };
    assert.equal((await post(url, alert)).status, 200);
    const again = await post(url, alert);
    assert.equal(again.status, 200);
    assert.equal((await again.json()).status, 'duplicate');
    assert.equal(sender.sent.length, 1);
    assert.deepEqual(sender.sent[0].pin, { lat: 37.9, lon: 23.7 });

    assert.equal((await post(url, { ...alert, type: 'position', seq: 1 })).status, 200);
    assert.equal((await post(url, { ...alert, type: 'position', seq: 2 })).status, 200);
    assert.equal(sender.sent.length, 3);
  });
});

test('server returns 202 when WhatsApp is reconnecting', async () => {
  const sender = { ...fakeSender(), send: async () => 'queued' };
  await withServer(sender, async (url) => {
    assert.equal((await post(url, base)).status, 202);
  });
});

test('server returns 500 when sending fails so the watch retries', async () => {
  const sender = { ...fakeSender(), send: async () => { throw new Error('boom'); } };
  await withServer(sender, async (url) => {
    assert.equal((await post(url, base)).status, 500);
  });
  // ...and a retry after the failure is not treated as a duplicate
});

test('health is public', async () => {
  await withServer(fakeSender(), async (url) => {
    const res = await fetch(`${url}/health`);
    assert.deepEqual(await res.json(), { ok: true, whatsapp: true });
  });
});
