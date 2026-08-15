# Running the whole backend locally, for free

Cloud Functions needs the Blaze plan. The emulator suite does not, and it runs *everything* this
project uses: Auth, Firestore, RTDB, the API, the RTDB push triggers, the scheduled retention job,
and both rules files. Nothing is deployed and no card is required.

The one thing the emulator cannot do is be reached from the public internet, so a live WordPress
site cannot talk to it. Everything else behaves as production does.

---

## 1. Install

```bash
npm install -g firebase-tools
firebase login

npm install --prefix functions
```

The project id is already set in `.firebaserc` (`chat-support-1`), which is what makes the
functions-emulator URL path predictable.

**Java is required** — the Firestore and RTDB emulators run on the JVM. `java -version` should
print 11 or newer. Node 20+ for the functions emulator.

---

## 2. Secrets

The backend refuses to sign a token with a default secret, in the emulator exactly as in
production. Put them in `functions/.env` (git-ignored, read automatically by the functions
emulator):

```
WIDGET_JWT_SECRET=local-dev-widget-secret-0123456789
BILLING_CALLBACK_SECRET=local-dev-billing-secret-0123456789
```

Any value of 16+ characters works. These sign local widget tokens only.

---

## 3. Start, then seed

Two terminals. First:

```bash
cd functions
npm run emulators          # first run
npm run emulators:import   # later runs, restores the last session's data
```

Then, with the emulators up:

```bash
cd functions
npm run seed:emulator
```

That prints everything you need:

| | |
|---|---|
| Owner sign-in | `owner@example.test` / `password123` |
| Widget API key | `sk_test_dev_0000…` |
| Website domain | `localhost` |
| Tenant | `tnt_dev`, one website, one open sample chat |
| Emulator UI | http://localhost:4000 |

The seed creates the owner with the `tenantId` custom claim already set, seeds plans, `DEMO100` and
the email templates, and writes the `system/bootstrap` lock — so the app opens straight into a
provisioned workspace instead of needing a bootstrap round-trip and a token refresh.

`npm run seed:emulator` **refuses to run against the real project.** It requires the emulator host
variables, and the npm script is what supplies them. That guard is the only reason a script that
mints an owner and an API key is safe to keep in the repo.

Data persists between sessions via `--export-on-exit` into `functions/dev/emulator-data`. Delete
that folder for a clean slate.

---

## 4. Point the Android app at it

Already wired, debug builds only. `app/build.gradle.kts` sets:

```kotlin
buildConfigField("boolean", "USE_EMULATORS", "true")
buildConfigField("String", "EMULATOR_HOST", "\"10.0.2.2\"")
```

`Emulators.connect()` runs from `SupportChatApplication.onCreate()` and redirects Auth and RTDB;
`SupportApi.BASE_URL` switches to the functions emulator on its own. Release builds set
`USE_EMULATORS` to `false`, so a shipped APK cannot be aimed at a laptop.

- **Android emulator (AVD):** works as-is. `10.0.2.2` is the AVD's route to your machine's
  localhost.
- **Physical device:** change `EMULATOR_HOST` to your machine's LAN address (`192.168.x.x`), be on
  the same Wi-Fi, and allow the ports through your firewall. `firebase.json` already binds the
  emulators to `0.0.0.0` so they accept connections from the network rather than localhost only.

Two consequences of the emulator that are worth knowing before they confuse you:

- **Disk persistence is off** in emulator mode, on purpose. A wiped emulator plus a surviving
  on-disk cache shows chats that no longer exist, which reads as a rules bug and is not one.
- **Push notifications do not work.** FCM has no emulator. `admin.messaging()` calls fail against a
  local project, so the trigger logs an error and the request still succeeds. Use the app's
  foreground `MessageWatchService` to observe live messages, or test push against the real project.

> Binding to `0.0.0.0` means anyone on your network can reach the emulators, and the emulators have
> no authentication at all. On an untrusted network, change the hosts in `firebase.json` back to
> `127.0.0.1` and use an AVD.

---

## 5. Point a local WordPress install at it

If the site is not local - a real domain, real visitors on it - the emulator has to be tunnelled to
a public URL, which needs three tunnels and two extra settings. That is a separate document:
`TUNNEL.md`. Read the warning at the top of it first.


The seeded website domain is `localhost`, because the handshake checks the request `Origin` against
the registered domain exactly. So serve WordPress from `http://localhost:<port>` and set, in the
plugin's settings:

- **API base:** `http://localhost:5001/chat-support-1/asia-southeast1/api`
- **API key:** the `sk_test_dev_…` key the seed printed

If you serve WordPress from `127.0.0.1` instead of `localhost`, the handshake returns 403
`origin_not_allowed` — that is the check working, not a bug. Either use `localhost`, or change
`WEBSITE_DOMAIN` in `functions/dev/seed-emulator.js` and re-seed.

---

## 6. Tests

```bash
cd functions
npm run test:offline    # 38 cases, no emulator, no network needed
npm run test:emulator   # the authoritative rules tests, through the real rules engine
```

`test:emulator` starts its own Firestore and RTDB emulators via `emulators:exec`, so it does not
need the suite from step 3 to be running — but it does need the ports free, so stop the long-
running emulators first or expect a port clash.

---

## 7. What is different from production

| | Emulator | Deployed (Blaze) |
|---|---|---|
| API URL | `http://localhost:5001/chat-support-1/asia-southeast1/api` | `https://asia-southeast1-chat-support-1.cloudfunctions.net/api` |
| Reachable from the internet | No | Yes |
| Push (FCM) | No | Yes |
| Scheduled purge | Runs on the functions emulator's own schedule; can also be invoked from the Emulator UI | Cloud Scheduler, every 15 min |
| Rules | Same files, same engine | Same files, same engine |
| Credentials | None needed | Ambient service account |
| Data | Local, disposable, exported on exit | Real |

When you do move to Blaze, nothing in the backend changes. `firebase deploy --only
functions,database,firestore`, flip `USE_EMULATORS` off by building release, and the same code runs.
