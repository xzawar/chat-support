# Support Chat backend (Phases A–D)

The trusted server. It is the only thing in the system that writes data.

- **RTDB = chat only.** `chats/{tenantId}/conversations/{id}`, `chats/{tenantId}/messages/{conversationId}/{clientMessageId}`.
- **Firestore = business data only.** `tenants`, `websites`, `devices`, `usage`, `leads`, `plans`, `coupons`, `emailTemplates`.
- **One user type, one website.** A tenant has exactly one user, its owner (`tenants/{tenantId}.ownerUid`), and exactly one linked website. There is no role claim, no membership collection and no invite flow.
- **Clients never write anything.** Firestore rules deny all client access; RTDB rules deny all client writes and allow token-scoped listens.
- **`tenantId` comes from verified custom claims.** No route reads it from a body, query or header. The one exception is `POST /v1/bootstrap`, which exists to create the first claim and then locks itself.

---

## 1. Deploy

```bash
npm install --prefix functions

# Secrets. There is no in-repo fallback: an unset value throws at first use rather than
# quietly signing forgeable tokens.
firebase functions:secrets:set WIDGET_JWT_SECRET        # >= 16 chars
firebase functions:secrets:set BILLING_CALLBACK_SECRET  # >= 16 chars

firebase deploy --only functions,database,firestore
```

Optional environment:

| Variable | Default | Purpose |
|---|---|---|
| `BILLING_PROVIDER` | `sandbox` | Which `BillingGateway` adapter to construct |
| `BOOTSTRAP_ADMIN` | unset | uid or email allowed to run bootstrap. Unset = first caller wins |
| `FIREBASE_WEB_API_KEY` | unset | Returned to the widget in the public config |
| `FIREBASE_AUTH_DOMAIN` | unset | Same |

Base URL: `https://asia-southeast1-<project>.cloudfunctions.net/api`

---

## 2. First run

1. Sign in as the owner (Firebase Auth), grab the ID token.
2. `POST /v1/bootstrap` — creates the tenant (with `ownerUid`), seeds plans + `DEMO100` + 3 templates, and sets the `tenantId` claim. That is the only custom claim; there is no `role`.
3. **Refresh the ID token** (`getIdToken(true)`) or sign out and back in. Claims only exist in tokens minted *after* step 2. Everything 401s with `tenant_not_provisioned` until you do.
4. `POST /v1/websites { domain }` — returns the raw `apiKey` **once**. Only its SHA-256 is stored. One website per account: a second attempt is **409 `website_limit_reached`**.

---

## 3. Routes

All errors are `{ "error": { "code", "message" } }`. `message` is written to be shown to a user verbatim.

### Bootstrap
| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/v1/bootstrap/status` | any signed-in user | `{ initialized, isBootstrapOwner, claimsPresent, refreshTokenRequired }` |
| `POST` | `/v1/bootstrap` | eligible user | 201 first time, 200 idempotent re-run by the same owner, **409 `already_initialized`** for anyone else |

### Tenant
| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/v1/tenants/me` | owner | plan, status, `features[]`, `currentPeriodEnd`, `subscriptionActive`. This is what the app caches at login |

### Websites
| Method | Path | Auth |
|---|---|---|
| `GET` | `/v1/websites` | owner |
| `POST` | `/v1/websites` | owner — returns raw `apiKey` once. **409 `website_limit_reached`** if a site is already linked |
| `POST` | `/v1/websites/:id/rotate-key` | owner — the previous key stops working on the next request |
| `DELETE` | `/v1/websites/:id` | owner — deactivates, never deletes (leads reference `websiteId`). Frees the one-website slot |

### Billing — no Stripe, no card storage, no PII in logs
| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/v1/billing/plans` | owner | active plans, tier-sorted, `isCurrent` flagged |
| `POST` | `/v1/billing/apply-coupon` | owner | `{ code, planId }`. 100% off → activates immediately; below 100% → returns the discounted price only |
| `POST` | `/v1/billing/checkout` | owner | `{ planId, couponCode? }` through the `BillingGateway` port |
| `POST` | `/v1/billing/callback` | `X-Billing-Secret` header | idempotent on `paymentId` |

### Widget
| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/v1/widget/handshake` | apiKey + Origin | → `widgetToken` (JWT, 24h, `conversationId` claim), `rtdbToken` (same claim), `databaseURL`, public config |
| `POST` | `/v1/widget/identify` | widgetToken | upserts the lead, mirrors name/email onto the RTDB conversation visitor |
| `POST` | `/v1/widget/messages` | widgetToken | one multi-path update |

### Chat (agent side)
| Method | Path | Auth |
|---|---|---|
| `POST` | `/v1/conversations/:id/messages` | agent, `chat` gate |
| `PATCH` | `/v1/conversations/:id` | agent — status / assignment / keepChat / unread |
| `DELETE` | `/v1/conversations/:id` | agent |

### Leads + email — gated on `email_automation`
| Method | Path | Auth |
|---|---|---|
| `GET` | `/v1/leads?limit&cursor&websiteId` | owner — keyset cursor |
| `GET` | `/v1/leads/groups` | owner — per-domain counts via `count()` aggregation |
| `GET` | `/v1/email-stats` | owner — real lead count, zeros computed server-side |
| `GET/POST/PATCH/DELETE` | `/v1/email-templates[/:id]` | owner |

---

## 4. Entitlement

One helper, `src/lib/entitlements.js`, and nothing else anywhere reads `status` or compares dates:

```js
subscriptionActive = active | trialing | (past_due within currentPeriodEnd + 7 days)
hasFeature(tenant, f) = subscriptionActive && (features.includes("*") || features.includes(f))
```

`features[]` is **copied onto the tenant** from the plan doc on every billing event, never joined at read time — a gate check must not depend on a second document being fetchable.

| Surface | Missing feature |
|---|---|
| Widget handshake (`chat`) | **402** `subscription_inactive` |
| `/v1/leads*`, `/v1/email-*` (`email_automation`) | **403** `feature_not_in_plan` |

Plans: `plan_1` tier 1 `[chat]` · `plan_2` tier 2 `[chat, email_automation]` · `plan_3` tier 3 `[chat, email_automation, social_media]`. New tenants get `plan_1` / `trialing`.

---

## 5. Design notes worth knowing before review

- **DEMO100 increments exactly once.** Validation, activation, the `redemptions/{tenantId}` write and `redeemedCount++` are all in one Firestore transaction. A second tap re-reads the redemption doc this one wrote and returns 409 `coupon_already_redeemed`.
- **Coupons below 100% do not activate.** They quote a price. Pre-activating a discounted plan would hand out paid tiers for a 50%-off code.
- **One activation function.** Coupon, sandbox checkout and gateway callback all call `buildActivation()`. A plan activated by a coupon and one activated by a payment leave the tenant byte-identical, so the gate cannot behave differently depending on how someone paid.
- **`clientMessageId` is the idempotency key.** A retried send overwrites the same node instead of posting twice — that is what keeps the app's offline queue safe.
- **Multi-path updates.** Message + `lastMessage` + `expiresAt` + `unread` commit together, so the inbox can never show a preview that disagrees with the thread.
- **Lead ids are derived**, `websiteId_sha256(lowercase email)`, so re-identifying updates one row instead of creating three. `conversationCount` only increments for a conversation id it has not already recorded.
- **`emailVerified` is only ever true from a verified Google token.** A typed address is unverified by definition; recording otherwise poisons the list later.
- **Retention purges RTDB only.** Every 15 min, batched at 400, idempotent, `expiresAt <= now`. `expiresAt: null` means the agent pinned it. **Leads are never purged** — that is the whole point of the split.
- **Origin absent = fail.** Browsers always send `Origin` on a cross-origin POST, so its absence means the caller is not the widget.

---

## 6. Not built yet (Phases E–F)

The Android app still talks to RTDB directly at `owners/{uid}/…` and still writes. **It will stop working the moment these rules deploy** — which is expected, and is what Phase E fixes. Do not deploy `database` rules to a live install until the app is rewritten.

---

## Authorisation: one owner, no roles

There is exactly one kind of user on a tenant. Every protected route runs the same gate:

```
requireAuth → requireTenant → attachTenant → requireOwner
```

- `requireAuth` verifies the Firebase ID token and reads **only** `tenantId` off it.
- `requireOwner` compares the verified `uid` against `tenants/{tenantId}.ownerUid`, read from
  Firestore on the request.

Consequences worth stating:

- **No role claim exists.** A token carrying `role: "owner"` grants nothing; there is a test for
  exactly that case.
- **The role-demotion token delay is moot.** Permissions were never in the token, so there is no
  stale-claim window to reason about. Ownership is re-read per request, and the tenant document
  cannot be stale. (`loadTenant` caches it for the duration of a single request only.)
- **No membership documents.** `tenants/{tenantId}/agents/{uid}` is gone. FCM tokens live at
  `tenants/{tenantId}/devices/{deviceId}`, which is where `notifications.js` reads them.

`sender: "agent"` on chat messages and `assignedAgentUid` on conversations are **unchanged on
purpose**. They are wire/data field names read by the already-deployed WordPress widget, by
existing RTDB history, and by the inbox filters and index — message authorship and assignment, not
a role. `test/offline/no-agent-role.test.js` asserts both are still present so removing them has
to be a deliberate act.

---

## One website per owner account

`POST /v1/websites` refuses a second **active** website for a tenant with
**409 `website_limit_reached`**, and the message names the site already linked. The check and the
write share a `runTransaction`, so two taps a millisecond apart cannot both succeed.

`GET /v1/websites` returns `limit: 1` and `canAddWebsite`, so the app hides its Register form
instead of guessing the rule. `DELETE` deactivates the site and frees the slot, which is how an
owner moves the widget to a new domain.

The exact-domain check at handshake is retained. It is an exact match, not an open-ended
allowlist, and it costs one comparison — the one-website limit is now the isolation boundary, and
the origin check is a cheap second line behind it.

---

## Feature gating: exact match only

`hasFeature(tenant, feature)` in `src/lib/entitlements.js`:

- No wildcard. `features: ["*"]` unlocks nothing (`"*"` is matchable only as a literal name).
- Blank and whitespace-only entries in `features[]` match nothing.
- A blank or non-string `feature` argument is never satisfied.
- Comparison is exact: no prefixes, no substrings, no case folding.
- The subscription must be alive: `active`/`trialing`, or `past_due` inside the 7-day grace window.

The Android `TenantMe.hasFeature` mirrors this, and `/v1/tenants/me` filters blank entries out of
the `features[]` it returns.

---

## Running it locally without Blaze

Deploying `exports.api` needs the Blaze plan. Running the identical code does not: the Firebase
emulator suite runs Auth, Firestore, RTDB, the API, the RTDB triggers, the scheduled purge and both
rules files on a laptop, free, with no card.

```bash
npm install -g firebase-tools
cd functions && npm install
npm run emulators        # terminal 1
npm run seed:emulator    # terminal 2 - prints the owner login and a working API key
```

Debug builds of the Android app already point at it (`USE_EMULATORS` in `app/build.gradle.kts`);
release builds cannot. Two things genuinely differ: it is not reachable from the public internet, so
a live WordPress site cannot handshake with it, and FCM has no emulator, so push does not fire.

Full walkthrough, including physical devices and local WordPress: see `EMULATOR.md`. To let a real
WordPress site reach the emulator over a temporary public URL, see `TUNNEL.md` - a demo mechanism,
with the caveats stated there.

## Tests

Two suites. They check the same properties by different means; the emulator one is authoritative.

### Offline suite — no emulator, no network, no install

```bash
cd functions && npm run test:offline      # node test/offline/run.js
```

38 cases covering:

| File | What it checks |
|---|---|
| `test/offline/firestore-rules.test.js` | Parses `firestore.rules`; asserts the only `allow` condition is the literal `false`, and that no rule references `request.auth`, a role, or `tenantId` |
| `test/offline/rtdb-rules.test.js` | Loads `database.rules.json` and evaluates the real rule expressions with RTDB's downward-cascading semantics: own-tenant reads succeed, cross-tenant reads deny, a widget token is confined to its own `conversationId`, and no path anywhere grants a write |
| `test/offline/api.test.js` | Loads the real route modules against in-memory fakes: owner guard (including a forged `role: "owner"` claim), bootstrap claims, the 409 website limit (including two concurrent creates), blank-`features[]` behaviour, and old-key rejection immediately after rotation |
| `test/offline/no-agent-role.test.js` | Greps the tree for role claims, role comparisons, membership collections and invite flows; also pins the two documented wire-format exceptions |

The fakes are in `test/offline/fakes.js` and are installed with a require hook, so the code under
test is the shipped code rather than a copy. The transaction fake does a real read-version check
with retries, which is what makes the concurrent-create case meaningful.

### Emulator suite — the authoritative rules test

```bash
cd functions && npm install
firebase emulators:exec --only firestore,database "npm run test:rules"
# or: npm run test:emulator
```

`test/rules.emulator.test.js` runs the same cases through `@firebase/rules-unit-testing` and the
real rules engine: Firestore deny-all for owners, visitors and anonymous clients; RTDB
cross-tenant read denial in both directions; a visitor token limited to one conversation and
unable to list the inbox; and `set`/`update`/`remove` denied on every path.
