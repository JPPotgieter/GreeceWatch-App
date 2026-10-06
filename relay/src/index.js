import { createServer } from './server.js';
import { WhatsAppClient } from './whatsapp.js';

const log = (msg) => console.log(`[${new Date().toISOString()}] ${msg}`);

const token = process.env.RELAY_TOKEN;
if (!token || token.length < 16) {
  console.error('Set RELAY_TOKEN to a random secret of at least 16 characters (e.g. `openssl rand -hex 24`).');
  process.exit(1);
}

const port = Number(process.env.PORT || 8080);
const whatsapp = new WhatsAppClient({
  authDir: process.env.AUTH_DIR || './wa-auth',
  groupId: process.env.WA_GROUP_ID || '',
  log,
});

await whatsapp.start();
createServer({ token, sender: whatsapp, log }).listen(port, () => {
  log(`GreeceWatch relay listening on :${port}`);
});
