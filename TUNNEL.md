# Putting the local emulator on a public URL (demo only)

This makes a real WordPress site talk to the emulator running on your machine. It is a demo, not a
deployment: your laptop is the server, and the emulators have no authentication at all, so anyone
with the tunnel URL has admin access to that database for as long as it is open. Use it to show the
widget working, then close it.

For anything lasting, see the self-host or Blaze options in `BACKEND.md`.

---

## Why two tunnels, not one

The obvious move is to tunnel port 5001 and be done. That gets you a successful handshake and a
widget that then sits on "Connecting" forever, because the chat needs three things and only one of
them is the API:

| The widget needs | Runs on | Reachable from a public site? |
|---|---|---|
| The API (handshake, identify, send) | 5001 | Needs a tunnel |
| Realtime Database (the live thread) | 9000 | Needs a tunnel |
| Auth (sign in with the custom token) | 9099 | Needs a tunnel |

The Auth one is the trap. The handshake mints a custom token from the **emulator**, and an emulator
token is unsigned — real Google Auth rejects it. So the browser must be pointed at the same Auth
emulator that issued it, which is what `PUBLIC_AUTH_EMULATOR_URL` below does.

---

## 1. Install a tunnel

```bash
# macOS
brew install cloudflared
# Windows
winget install --id Cloudflare.cloudflared
# Linux
# https://developers.cloudflare.com/cloudflare-one/connections/downloads/
```

Cloudflare quick tunnels need no account and no card. `ngrok` works identically, but its free tier
allows one tunnel at a time and you need three.

---

## 2. Start the emulators, then three tunnels

Terminal 1:

```bash
cd functions && npm run emulators
```

Terminals 2, 3, 4 — each prints a `https://<random>.trycloudflare.com` URL. Write all three down:

```bash
cloudflared tunnel --url http://localhost:5001   # API
cloudflared tunnel --url http://localhost:9000   # Realtime Database
cloudflared tunnel --url http://localhost:9099   # Auth
```

The URLs change every restart. That is the main tax of this approach.

---

## 3. Tell the backend its public addresses

The backend cannot guess them, and the widget must never guess either — so both come from the
handshake. Add to `functions/.env`, using your own tunnel URLs:

```
# Note the ?ns= parameter. The RTDB emulator serves every namespace on one port and needs to be
# told which one; without it the widget connects and sees an empty database.
PUBLIC_DATABASE_URL=https://rtdb-xyz.trycloudflare.com/?ns=chat-support-1-default-rtdb

PUBLIC_AUTH_EMULATOR_URL=https://auth-xyz.trycloudflare.com
```

Both **must stay unset in production.** `PUBLIC_AUTH_EMULATOR_URL` in a live deployment would send
real visitors to authenticate against nothing.

Restart the emulators so the functions emulator re-reads `.env`.

---

## 4. Re-seed with your real domain

The handshake checks the request `Origin` against the website's registered domain, exactly. The
default seed registers `localhost`, so a public site is refused with 403 `origin_not_allowed`.
Re-seed with the site's domain:

```bash
cd functions
node dev/seed-emulator.js --emulators=localhost --domain=example.com
```

Bare host only — no `https://`, no trailing slash, no path. The check accepts `example.com` and
`www.example.com`, nothing else.

---

## 5. Configure the plugin

In **WordPress → Support Chat**:

- **Backend URL:** `https://api-xyz.trycloudflare.com/chat-support-1/asia-southeast1/api`
- **API key:** the `sk_test_dev_…` key the seed printed
- **Website ID:** `web_dev`

That path segment after the hostname is the functions emulator's routing prefix. A deployed backend
does not have it — dropping it is the most common cause of a 404 here.

Use the **Test connection** button. It runs the handshake from PHP and prints exactly what the
backend answered, which is a far shorter loop than guessing from the browser console.

The plugin in this bundle is patched for this: it accepts the current handshake field names
(`firebaseConfig`, `rtdbToken`), sends the API key in both the body and the `Authorization` header,
and connects to the Auth emulator when the handshake tells it to. The unpatched 4.0.0 plugin will
**not** work against this backend even without tunnelling — the field names had drifted.

---

## 6. What still will not work

- **Push notifications.** FCM has no emulator. A visitor message won't wake the Android app; the
  in-app `MessageWatchService` covers it while the app is open.
- **Nothing survives a laptop sleep.** Closing the lid or dropping Wi-Fi kills the site's chat.
- **Tunnel URLs rotate**, so steps 3 and 5 repeat on every restart.
- **Data is disposable.** Real visitor conversations would land in a local emulator and vanish.

---

## 7. Shutting it down

Stop the three `cloudflared` processes, then remove `PUBLIC_DATABASE_URL` and
`PUBLIC_AUTH_EMULATOR_URL` from `.env` and clear the plugin's Backend URL. Leaving stale tunnel URLs
configured means the site's widget spends its retry ladder calling a hostname that no longer exists.

The security point, stated once more plainly: while those tunnels are open, the Firestore and RTDB
emulators are on the public internet with no credentials required. Do not leave them running
overnight, and do not put anything real in them.
