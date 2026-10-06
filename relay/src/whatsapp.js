// WhatsApp connection via Baileys (WhatsApp Web protocol). The relay's phone
// number is linked once by scanning a QR code, then posts into the group.

import makeWASocket, {
  Browsers,
  DisconnectReason,
  fetchLatestBaileysVersion,
  useMultiFileAuthState,
} from 'baileys';
import pino from 'pino';
import qrcode from 'qrcode-terminal';

export class WhatsAppClient {
  constructor({ authDir, groupId, log }) {
    this.authDir = authDir;
    this.groupId = groupId;
    this.log = log;
    this.sock = null;
    this.connected = false;
    this.queue = [];
  }

  async start() {
    const { state, saveCreds } = await useMultiFileAuthState(this.authDir);
    const { version } = await fetchLatestBaileysVersion();

    this.sock = makeWASocket({
      version,
      auth: state,
      browser: Browsers.ubuntu('GreeceWatch Relay'),
      logger: pino({ level: 'warn' }),
      markOnlineOnConnect: false,
      syncFullHistory: false,
    });

    this.sock.ev.on('creds.update', saveCreds);
    this.sock.ev.on('connection.update', (update) => this.onConnectionUpdate(update));
  }

  async onConnectionUpdate({ connection, lastDisconnect, qr }) {
    if (qr) {
      this.log('Scan this QR code with the relay phone: WhatsApp > Settings > Linked devices > Link a device');
      qrcode.generate(qr, { small: true });
    }

    if (connection === 'open') {
      this.connected = true;
      this.log('WhatsApp connected');
      await this.logGroups();
      await this.flushQueue();
    }

    if (connection === 'close') {
      this.connected = false;
      const code = lastDisconnect?.error?.output?.statusCode;
      if (code === DisconnectReason.loggedOut) {
        this.log(`WhatsApp logged out. Delete ${this.authDir} and restart to link again.`);
        return;
      }
      this.log(`WhatsApp connection closed (code ${code ?? 'unknown'}), reconnecting in 3s`);
      setTimeout(() => this.start().catch((e) => this.log(`reconnect failed: ${e.message}`)), 3000);
    }
  }

  async listGroups() {
    const groups = await this.sock.groupFetchAllParticipating();
    return Object.values(groups).map((g) => ({ id: g.id, name: g.subject }));
  }

  async logGroups() {
    try {
      const groups = await this.listGroups();
      if (!this.groupId) {
        this.log('WA_GROUP_ID is not set. Groups this number is in:');
      }
      for (const g of groups) {
        const marker = g.id === this.groupId ? '  <== alerts go here' : '';
        this.log(`  ${g.id}  ${g.name}${marker}`);
      }
      if (this.groupId && !groups.some((g) => g.id === this.groupId)) {
        this.log(`WARNING: WA_GROUP_ID ${this.groupId} is not one of the groups above`);
      }
    } catch (e) {
      this.log(`could not list groups: ${e.message}`);
    }
  }

  // Sends now if connected, otherwise holds the message until the link comes back.
  async send(text, pin) {
    if (!this.groupId) throw new Error('WA_GROUP_ID is not configured');
    if (!this.connected) {
      this.queue.push({ text, pin });
      return 'queued';
    }
    await this.deliver(text, pin);
    return 'sent';
  }

  async deliver(text, pin) {
    await this.sock.sendMessage(this.groupId, { text });
    if (pin) {
      await this.sock.sendMessage(this.groupId, {
        location: { degreesLatitude: pin.lat, degreesLongitude: pin.lon },
      });
    }
  }

  async flushQueue() {
    while (this.queue.length && this.connected) {
      const { text, pin } = this.queue[0];
      try {
        await this.deliver(text, pin);
        this.queue.shift();
      } catch (e) {
        this.log(`queued message failed, will retry on next connect: ${e.message}`);
        return;
      }
    }
  }
}
