# Firebase setup — chat-support-1

Everything below is done once, in order. There is no server and no Cloud Function in this
architecture, so all of it fits on the Spark (free) plan.

Project id: `chat-support-1` (already set in `.firebaserc`)

---

## Step 1 — Authentication

Console -> **Authentication** -> Get started -> **Sign-in method**. Enable exactly three:

| Provider | Used by | Breaks if missing |
|---|---|---|
| Email/Password | `AuthViewModel.signIn` | Owner cannot sign in with email |
| Google | `SupportAccount.signInWithGoogle` | Google button fails |
| **Anonymous** | Website visitors | Chat never opens on the website |

Then:

1. **SHA-1 fingerprint** — Project settings -> Your apps -> Android -> Add fingerprint.
   Get it with `./gradlew signingReport`, or:
   `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`
   Without it the Google sheet opens, you pick an account, and it closes silently.
2. **Authorized domains** — Authentication -> Settings -> Authorized domains.
   **Nothing to do here.** This list only gates OAuth popup/redirect sign-in and email action
   links. It does not gate `signInAnonymously()`, which is the only auth call the widget
   makes, so every customer domain works without ever being listed. See "Authorized domains"
   near the bottom of this file for why this is not automatable and why it no longer matters.
3. The web client id is already in `app/src/main/res/values/strings.xml` as
   `google_web_client_id`. Only change it if you recreate the OAuth client.

---

## Step 2 — Firestore

Console -> **Firestore Database** -> Create database -> **Production mode** -> choose a location.

**The location is permanent.** Pick the one nearest your users. `asia-southeast1` matches the
Realtime Database region this app is hard-coded to.

---

## Step 3 — Realtime Database

Console -> **Realtime Database** -> Create database -> **region: Singapore (asia-southeast1)** ->
Locked mode.

This one is not a free choice. The app has this URL compiled in:

```
https://chat-support-1-default-rtdb.asia-southeast1.firebasedatabase.app
```

Create the database in another region and you get a different URL, and the app talks to nothing.
If you already made one elsewhere, either delete it or change `DATABASE_URL` in
`app/src/main/java/com/codexce/supportchat/data/` and in `firebase-config.json` to match.

---

## Step 4 — Deploy the rules

From the project folder:

```bash
npm install -g firebase-tools
firebase login
firebase use chat-support-1
firebase deploy --only firestore:rules,firestore:indexes,database
```

Rules and index deploys are free on Spark.

**Do not run a bare `firebase deploy`.** It will try to push `functions/` and fail on the Blaze
requirement. The two notification triggers in `functions/` are dead code until you upgrade.

Until this step is done, every read and write in the app is denied and sign-in appears to hang.

---

## Step 5 — Seed the two collections nothing creates for you

### `plans/{planId}`

Read by `SupportApi.plans()`, ordered by `tier`. An empty collection means an empty Subscription
screen. Create one document per plan; the document id is the plan id.

`plans/free`

| Field | Type | Value |
|---|---|---|
| name | string | Free |
| tier | number | 0 |
| priceCents | number | 0 |
| currency | string | USD |
| features | array of strings | chat |
| description | string | One website, unlimited chats |

`plans/pro`

| Field | Type | Value |
|---|---|---|
| name | string | Pro |
| tier | number | 1 |
| priceCents | number | 1900 |
| currency | string | USD |
| features | array of strings | chat, email_automation |
| description | string | Chat plus email automation |

The feature strings must match exactly. `hasFeature` is an exact match now — a blank string in
`features` unlocks nothing. The names the app checks are `chat`, `email_automation`,
`social_media`.

### `coupons/{CODE}`

The document id IS the code, uppercase. Only 100%-off activation works; there is no payment
gateway.

| Field | Type | Value |
|---|---|---|
| planId | string | pro |
| used | boolean | false |

Rules let `used` move `false -> true` exactly once, in the same transaction that changes the
tenant's plan, so a code cannot be redeemed twice even from two devices.

**Do not pre-create anything else.** `tenants`, `owners`, `apiKeys`, `websites`, `leads`,
`linkCodes`, `publicTenants` and the whole `chats/` RTDB tree are written by the app.

---

## Step 6 — Verify, in this order

1. **Sign in on the phone.** Firestore should now contain `tenants/tnt_…` with your uid in
   `ownerUid`, and `owners/{uid}` holding that tenant id. If `owners/{uid}` exists but the tenant
   does not, the transaction was refused — recheck Step 4.
2. **Open Subscription.** Your seeded plans should list. Empty means `plans/` is empty or rules
   were not deployed.
3. **Add your website** on the Link Website screen. In one transaction you should see:
   `tenants/{t}/websites/web_…`, `apiKeys/sk_live_…`, `linkCodes/{8 CHARS}` with `used: false`,
   and `tenant.websiteCount` at 1. A second attempt must fail with "already has a website".
4. **RTDB.** After the inbox opens once, `chats/{tenantId}/ownerUid` should hold your uid. RTDB
   rules depend on that node, because they cannot read Firestore.
5. **Link WordPress.** Paste `firebase-config.json` into the plugin, then type the code. The
   plugin fetches `linkCodes/{code}` over the unauthenticated REST API, stores the key, and flips
   `used` to true. Codes expire after 10 minutes.

---

## Authorized domains

Decision taken: **the widget does not use Google sign-in.** Visitors type a name and an email,
the lead is stored with `source: "manual"` and `emailVerified: false`, and the widget's only
auth call is `signInAnonymously()`.

That makes onboarding genuinely zero-touch: a customer installs the plugin, types the link
code, and chat works. No console step, no per-domain allow-list, no waiting on you.

### Why it could not be automated

The authorized-domain list lives in the project's Identity Platform config. The only
programmatic way to change it is a PATCH to
`identitytoolkit.googleapis.com/admin/v2/projects/chat-support-1/config` signed with a service
account key. That key is full admin over the project; shipping it inside an APK or a WordPress
plugin would hand anyone who extracted it the ability to delete the whole database. There is no
Cloud Function to hide it in, because that needs Blaze. So it was never a build-it-better
problem — it was a choose-a-trade-off problem, and the trade chosen is below.

### What this costs

**No lead is ever verified.** `emailVerified` stays false for every lead, for ever, because the
only thing that could have set it true was the Google provider claim. A visitor can type
anyone's address. Treat captured emails as claimed, not confirmed, and do not use them for
anything that assumes ownership of the address.

The rules do not need changing for this: `leads` already refuses `emailVerified: true` unless
the signed-in provider vouched for that exact address, which now never happens. `'google'` is
still an accepted value for `source` — harmless, and it leaves the door open if you ever add
Google back for a domain you control.

**Owner sign-in is unaffected.** Google sign-in on the Android app uses Credential Manager, not
a browser popup, so it is not domain-gated and stays exactly as it is.

## What is still not wired

- **The widget JS still calls the old proxy**, so the plugin will load a chat box that cannot
  talk to Firebase until that rewrite lands. Steps 1-6 are still worth doing: the Android app is
  fully functional without it.
- **Push notifications need Blaze**, because they need Cloud Functions. `functions/` is kept for
  that day and deploys nothing today.
- **No Origin/Referer check is possible.** Rules cannot read HTTP headers. Anyone holding your
  API key can open chats against your tenant. See `README-FIREBASE-ONLY.md`.
