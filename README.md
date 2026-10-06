# MOB Watch: man-overboard alerts from a Pixel Watch to WhatsApp

You arm the app on your Pixel Watch. If you go into the water, the watch's
barometer sees the pressure jump, the watch buzzes, and the crew's WhatsApp
group gets:

```
🚨🚨 MAN OVERBOARD 🚨🚨
*JP* has gone into the water!
🕒 14:32:07
📍 Position: 37.44612, 24.94120 (±6 m)
🗺️ https://maps.google.com/?q=37.44612,24.94120

1. Shout "MAN OVERBOARD" and keep pointing at them
2. Throw the lifebuoy / dan buoy
3. Press MOB on the chartplotter
4. Turn the boat around
```

It also sends a map pin. After that it posts your live GPS position every
30 seconds for an hour, until you tap **I'M SAFE**.

> ⚠️ **This is an extra, not your safety gear.** Wear a lifejacket, clip on at
> night and in heavy weather, and if you can, carry a proper AIS MOB beacon or
> PLB. Radio signals do not travel through water, so the watch can only send
> once your wrist is back above the surface. Read [Limitations](#limitations).

## How it works

```
Pixel Watch  ──HTTPS──▶  Relay server  ──WhatsApp Web──▶  "Greece 2026 ⛵" group
(barometer + GPS)       (Node.js, spare                    (all 8 of you)
                         WhatsApp number)
```

WhatsApp has no official API that lets an app post into a normal group chat.
So the relay is a small server logged into WhatsApp as a **spare phone number**
that you add to the group, the same way WhatsApp Web works. The watch
calls the relay, and the relay posts the message.

**Detection:** water adds about 1 hPa of pressure per centimetre of depth.
Weather only moves air pressure a few hPa per *hour*. When the pressure stays
at least 20 hPa above normal for 0.4 s, which means your wrist is about 20 cm
under water, the watch fires. Splashes, waves, rain, going below deck and
weather changes don't come close. See `watch/app/src/main/java/com/greecewatch/mob/SubmersionDetector.kt`.

---

## 1. Set up the relay server (once, before the trip)

You need:

- **A spare WhatsApp number**, such as a cheap prepaid SIM in an old phone. Don't
  use your own number: the relay uses an unofficial WhatsApp client, and
  WhatsApp can ban numbers that use one. Add this number to the boat group.
- **Somewhere to run it 24/7 with HTTPS.** The watch only talks HTTPS. Either:
  - a small VPS (Hetzner, DigitalOcean, about €4/month) behind Caddy or nginx for HTTPS, or
  - a Raspberry Pi at home with a free [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/),
    which gives you an `https://…` address with no port forwarding.

Install and start it (Node.js 20 or newer):

```bash
cd relay
npm install
export RELAY_TOKEN=$(openssl rand -hex 24)   # save this, the watch needs it too
npm start
```

1. A QR code appears in the terminal. On the **spare phone**, go to WhatsApp → Settings →
   Linked devices → Link a device, and scan it.
2. The log then lists every group that number is in:
   ```
   120363012345678901@g.us  Greece 2026 ⛵
   ```
3. Stop it, and start it again with the group ID:
   ```bash
   RELAY_TOKEN=... WA_GROUP_ID=120363012345678901@g.us npm start
   ```

The login is saved in `relay/wa-auth/`, so restarts don't need the QR again.
Keep it running with systemd or pm2. Here is a minimal
systemd unit:

```ini
# /etc/systemd/system/mob-relay.service
[Service]
WorkingDirectory=/opt/GreeceWatch-App/relay
Environment=RELAY_TOKEN=your-token WA_GROUP_ID=1203...@g.us PORT=8080
ExecStart=/usr/bin/node src/index.js
Restart=always
[Install]
WantedBy=multi-user.target
```

Check it from your phone: `https://your-relay/health` should show
`{"ok":true,"whatsapp":true}`.

| Variable | Required | Meaning |
|---|---|---|
| `RELAY_TOKEN` | yes | Shared secret, 16+ characters. The watch must send the same value. |
| `WA_GROUP_ID` | yes* | Group to post to. *Leave it out on first run to list your groups. |
| `PORT` | no | Default `8080`. |
| `AUTH_DIR` | no | Where the WhatsApp login is stored. Default `./wa-auth`. |

Run the tests with `npm test`.

## 2. Put the app on your Pixel Watch

1. Install [Android Studio](https://developer.android.com/studio) and open the `watch/` folder.
2. Copy `watch/local.properties.example` to `watch/local.properties` and fill it in:
   ```properties
   relay.url=https://your-relay.example.com
   relay.token=the RELAY_TOKEN from above
   crew.name=JP
   ```
3. On the watch, turn on Developer options: Settings → System → About → tap
   *Build number* 7 times. Then go to Settings → Developer options and turn on
   **ADB debugging** and **Debug over Wi-Fi**.
4. In Android Studio, use Device Manager → *Pair devices using Wi-Fi*, pick the
   watch, and press **Run ▶**. "MOB Watch" appears in the watch's app list.
5. Open it once and allow **Location** and **Notifications**.

## 3. Test it at home. Do this before you go.

1. **Send test.** The group should get a "🧪 TEST" message within a few seconds.
   Try it once with the phone's Bluetooth off, to prove the watch works on
   its own mobile data.
2. **Bucket test.** Tell the group first. Tap **Arm**, fill a bucket or the bath, and
   push your wrist about 30 cm down. The "Water: N cm" line should climb past 20, the watch
   buzzes, and the real alert goes out. Tap **I'M SAFE** and the group gets ✅.
3. Test again at the first anchorage with a swim off the boat. Turn on
   **Wet mode / touch lock** on the watch while you're in the water.

## Daily use on board

- **Arm** when you're under way, especially on night watches, when you're alone
  on deck, or in a blow. The watch shows an "armed" notification.
- **Disarm before a planned swim**, or the group gets an alert every time someone
  jumps in. If you forget, tap **I'M SAFE**.
- **Send MOB now** sends the alert by hand. It's also useful if someone else goes
  in and you're the one wearing the watch.
- If you fall in, keep your watch arm out of the water when you can. That is
  when it can get a signal and a GPS fix.

## Limitations

- **Signal.** The watch needs internet. That means either the **LTE Pixel Watch** with
  an active eSIM plan, or Bluetooth to a phone. Bluetooth reaches about 10 m and
  water stops it, so the moment you drift away from the boat only LTE works.
  **Use an LTE model with a plan that roams in Greece.** Coastal Greek coverage is
  generally good, but there are gaps in the open sea between islands.
  Messages keep retrying until they get through.
- **Under water nothing transmits.** The alert leaves when your wrist comes up.
- **Your wrist has to go about 20 cm under.** A fall where your arm stays out of the
  water the whole time won't trigger it. Use **Send MOB now** if you can.
- **Battery.** While armed, the watch keeps its processor awake to read the
  barometer. Expect noticeably shorter battery life. Charge it every day and
  disarm in harbour.
- **Restarts.** If the watch restarts, or Android kills the app, it stays disarmed until
  you open it and tap Arm again.
- **The relay is a single point of failure.** If it is down or WhatsApp logs it out,
  no alert. Check `/health` before you cast off.
- **The relay uses unofficial WhatsApp access** (the [Baileys](https://github.com/WhiskeySockets/Baileys)
  library), which WhatsApp doesn't support. That's why it uses a spare number.

## Project layout

```
relay/   Node.js server: HTTP /alert endpoint → WhatsApp group
watch/   Wear OS app (Kotlin, Jetpack Compose for Wear)
```
