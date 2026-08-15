# Firebase-only backend: what changed, and what is weaker

The Express API on Cloud Functions is gone. The Android app and the WordPress widget now talk
directly to **Firebase Auth**, **Firestore** and the **Realtime Database**, and nothing else. That
runs on the free Spark plan, with no server to host, no cold starts and no tunnel.

Every behaviour that got weaker in the move is listed here rather than quietly dropped.

## Gone or weaker

| Before (Express) | Now (Firebase only) | Why |
| --- | --- | --- |
| Origin / Referer check on every widget call, exact-domain match | **No origin checking at all.** Anyone holding a site API key can open a conversation from any page or any script. | Security rules cannot read HTTP headers. Faking it in the browser would be theatre: the client controls what it sends. |
| API keys stored as a SHA-256 hash (`apiKeyHash`) | **Keys are stored in plaintext**, as the `apiKeys/{key}` document id, and the owner's website document keeps a copy so rotation can delete the old one. | A rule cannot hash an incoming value and then look a document up by the result. Document-id lookup is the only way to resolve a key in one `get()` without allowing a query. Entropy is still 192 bits, so a key is not guessable. |
| Real payment flow behind a gateway adapter | **Nothing is ever charged.** Every plan is selectable and a switch is confirmed immediately. Only 100%-off coupon activation is genuinely enforced (single-use, transactional). | Trustworthy payment needs a server-side webhook: a client can always lie about having paid. Marked `TODO` in `SupportApi.checkout`. |
| Server-side plan enforcement before a write | **Plan and feature checks are advisory** on the client; the rules enforce ownership and tenant scoping, not billing state. | Rules can read the tenant document, but an owner can also write it, so plan state is not a boundary the owner can be held to. Cross-tenant isolation is unaffected. |
| Scheduled `purgeExpiredConversations` function | **The app prunes expired chats** when the inbox loads, in one multi-path delete. Expired chats are hidden immediately whether or not the delete has landed yet. | Scheduled functions need Blaze. Expiry is data hygiene, not access control: the owner is already authorised to read every chat in their own tenant. |
| Rate limiting on the handshake proxy | Still none, and now there is no proxy to add it to. | Was already out of scope; noted so it is not mistaken for a regression. |
| Server-minted custom tokens for visitors, 24h TTL | **Anonymous Auth.** The visitor's uid is their identity and does not expire, so a chat cannot drop mid-conversation on a token refresh. | No server to mint tokens. Rules scope each anonymous uid to the single conversation carrying its `visitorUid`. |
| Tenant carried in a `tenantId` custom claim | **No custom claims.** The app resolves its tenant by querying `tenants` where `ownerUid == auth.uid`, and every rule re-reads `tenants/{id}.ownerUid`. | Only a server can set claims. This is strictly stronger: there is no token to go stale, so the old "role demotion token delay" problem cannot exist. |
| Push notifications from Firestore/RTDB triggers | **Foreground service only** (`MessageWatchService`). The trigger code is kept, unwired, for a later Blaze deploy. | Cloud Functions needs Blaze. |

## One new, small trade-off

Realtime Database rules cannot read Firestore, so ownership is mirrored to
`chats/{tenantId}/ownerUid`. The node is **create-once and then immutable** — only the uid already
in it can rewrite it — and the app writes it during bootstrap, in the same flow that creates the
tenant. The residual risk is a tenant whose mirror is somehow missing: the first authenticated
account to write it would own that chat namespace. It cannot read anything in Firestore, and the
real owner would notice immediately because their own writes would start failing.

## What did not get weaker

- **Cross-tenant isolation.** A visitor can read and write exactly one conversation, in one
  tenant. An owner can only touch the tenant whose `ownerUid` is their own uid. Nothing else in
  either database is readable or writable, by default deny.
- **One website per owner.** Enforced twice: a Firestore transaction that reads and increments
  `tenant.websiteCount`, and a rule that refuses the website document unless the count is still
  zero. Two simultaneous taps cannot both commit.
- **One tenant per owner.** `owners/{uid}` is create-once and never updated.
- **Key rotation is immediate.** The old `apiKeys` document is deleted and the new one created in
  the same transaction, so there is no window where both keys work.
- **Blank feature names unlock nothing.** Exact-name match only, in the app and in the widget.
- **No duplicated messages.** `clientMessageId` is still the database child key, so a replayed
  send overwrites rather than duplicating, offline queue included.
- **No `agent` role anywhere.** One role, owner, derived from `ownerUid`.

## Testing the rules

The rules are written to be exercised with the emulator, which does not need Blaze:

```
firebase emulators:start --only auth,firestore,database
```

The cases worth asserting are cross-tenant reads, a visitor writing someone else's conversation,
a second website creation, a second coupon redemption, and a plain client write anywhere outside
`chats/{tenantId}`. None of those were run in the environment that produced this change.
