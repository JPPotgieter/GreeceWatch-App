// Pure helpers: validate what the watch sends and turn it into WhatsApp text.
// Kept free of I/O so they can be unit tested.

export const EVENT_TYPES = ['overboard', 'position', 'safe', 'test'];

const TIME_ZONE = process.env.TZ_DISPLAY || 'Europe/Athens';

export function validateAlert(body) {
  if (!body || typeof body !== 'object') return 'body must be a JSON object';
  if (!EVENT_TYPES.includes(body.type)) return `type must be one of ${EVENT_TYPES.join(', ')}`;
  if (typeof body.name !== 'string' || !body.name.trim()) return 'name is required';
  if (body.name.length > 60) return 'name is too long';
  if (typeof body.eventId !== 'string' || !/^[A-Za-z0-9-]{4,64}$/.test(body.eventId)) {
    return 'eventId must be 4-64 letters, digits or dashes';
  }
  const hasLat = body.lat !== undefined && body.lat !== null;
  const hasLon = body.lon !== undefined && body.lon !== null;
  if (hasLat !== hasLon) return 'lat and lon must be sent together';
  if (hasLat) {
    if (!Number.isFinite(body.lat) || body.lat < -90 || body.lat > 90) return 'lat out of range';
    if (!Number.isFinite(body.lon) || body.lon < -180 || body.lon > 180) return 'lon out of range';
  }
  if (body.accuracy !== undefined && body.accuracy !== null && !Number.isFinite(body.accuracy)) {
    return 'accuracy must be a number';
  }
  if (body.seq !== undefined && !Number.isInteger(body.seq)) return 'seq must be an integer';
  return null;
}

// Watches retry until they get a 2xx, so the same message can arrive more than once.
export function dedupeKey(alert) {
  return `${alert.eventId}:${alert.type}:${alert.seq ?? 0}`;
}

function formatTime(epochMs) {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: TIME_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(epochMs));
}

function positionLines(alert) {
  if (alert.lat === undefined || alert.lat === null) {
    return ['📍 Position: not available yet (GPS fix pending)'];
  }
  const lat = alert.lat.toFixed(5);
  const lon = alert.lon.toFixed(5);
  const acc = Number.isFinite(alert.accuracy) ? ` (±${Math.round(alert.accuracy)} m)` : '';
  return [
    `📍 Position: ${lat}, ${lon}${acc}`,
    `🗺️ https://maps.google.com/?q=${lat},${lon}`,
  ];
}

export function formatAlert(alert, now = Date.now()) {
  const name = alert.name.trim();
  const time = formatTime(Number.isFinite(alert.timestamp) ? alert.timestamp : now);

  switch (alert.type) {
    case 'overboard':
      return [
        '🚨🚨 MAN OVERBOARD 🚨🚨',
        `*${name}* has gone into the water!`,
        `🕒 ${time}`,
        ...positionLines(alert),
        '',
        '1. Shout "MAN OVERBOARD" and keep pointing at them',
        '2. Throw the lifebuoy / dan buoy',
        '3. Press MOB on the chartplotter',
        '4. Turn the boat around',
      ].join('\n');
    case 'position':
      return [`📡 Update: ${name}'s watch position at ${time}`, ...positionLines(alert)].join('\n');
    case 'safe':
      return `✅ ${name} pressed "I'm safe" at ${time}. Overboard alert cancelled.`;
    case 'test':
      return [`🧪 TEST from ${name}'s watch at ${time}. Ignore, nobody is in the water.`, ...positionLines(alert)].join('\n');
    default:
      throw new Error(`unknown type ${alert.type}`);
  }
}

// WhatsApp can also show a map pin, which is easier to tap than a link.
export function shouldSendPin(alert) {
  return (alert.type === 'overboard' || alert.type === 'position') && Number.isFinite(alert.lat);
}
