/**
 * Route-level tests: ownership guard, the one-website limit, exact feature matching, and the
 * key-rotation invalidation window.
 *
 * These load the real route modules (src/routes/websites.js, src/routes/widget.js,
 * src/middleware/auth.js) against in-memory fakes, so a regression in the shipped code fails here
 * rather than in production.
 */

const { reset, buildApp, suite, test, assert, assertEqual } = require("./harness")
const { sha256 } = require("../../src/lib/crypto")

const DAY = 24 * 60 * 60 * 1000

/** A provisioned tenant, its owner, and an active website with a known key. */
function seedWorkspace(admin, options = {}) {
  const tenantId = options.tenantId || "tnt_a"
  const ownerUid = options.ownerUid || "uid_owner"
  const store = admin.__store

  store.seed(`tenants/${tenantId}`, {
    name: "Acme",
    ownerUid,
    ownerEmail: "owner@acme.test",
    plan: "plan_1",
    features: options.features || ["chat"],
    status: options.status || "active",
    currentPeriodEnd: Date.now() + 30 * DAY,
  })

  const ownerToken = admin.__auth.issueIdToken(ownerUid, {
    email: "owner@acme.test",
    email_verified: true,
    tenantId,
  })

  return { tenantId, ownerUid, ownerToken }
}

function authHeaders(token) {
  return { Authorization: `Bearer ${token}` }
}

module.exports = async function run() {
  // ------------------------------------------------------------------ ownership guard

  suite("Route guards (owner only, no role claim)")

  await test("an owner of the tenant is allowed through", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const response = await app.__handle({
      method: "GET",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
    })
    assertEqual(response.status, 200, "status")
  })

  await test("a signed-in user who is not the tenant's owner gets 403 owner_only", async () => {
    const admin = reset()
    const { tenantId } = seedWorkspace(admin)
    // Same tenantId claim, different uid: exactly the case a stolen or stale claim would produce.
    const strangerToken = admin.__auth.issueIdToken("uid_stranger", {
      email: "someone@else.test",
      tenantId,
    })
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const response = await app.__handle({
      method: "GET",
      path: "/v1/websites/",
      headers: authHeaders(strangerToken),
    })
    assertEqual(response.status, 403, "status")
    assertEqual(response.body.error.code, "owner_only", "code")
  })

  await test("a token with no tenant claim gets tenant_not_provisioned", async () => {
    const admin = reset()
    seedWorkspace(admin)
    const freshToken = admin.__auth.issueIdToken("uid_new", { email: "new@acme.test" })
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const response = await app.__handle({
      method: "GET",
      path: "/v1/websites/",
      headers: authHeaders(freshToken),
    })
    assertEqual(response.status, 401, "status")
    assertEqual(response.body.error.code, "tenant_not_provisioned", "code")
  })

  await test("a role claim, even one saying owner, grants nothing on its own", async () => {
    const admin = reset()
    const { tenantId } = seedWorkspace(admin)
    const forgedToken = admin.__auth.issueIdToken("uid_forged", {
      email: "forged@acme.test",
      tenantId,
      role: "owner",
    })
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const response = await app.__handle({
      method: "GET",
      path: "/v1/websites/",
      headers: authHeaders(forgedToken),
    })
    assertEqual(response.status, 403, "status")
    assertEqual(response.body.error.code, "owner_only", "code")
  })

  await test("bootstrap sets tenantId as the only custom claim", async () => {
    const admin = reset()
    admin.__store.seed("plans/plan_1", {
      name: "Starter",
      tier: 1,
      features: ["chat"],
      priceCents: 0,
      currency: "USD",
    })
    const token = admin.__auth.issueIdToken("uid_first", {
      email: "first@acme.test",
      email_verified: true,
    })
    const app = buildApp([["/v1/bootstrap", require("../../src/routes/bootstrap")]])

    const response = await app.__handle({
      method: "POST",
      path: "/v1/bootstrap/",
      headers: authHeaders(token),
      body: { workspaceName: "Acme" },
    })
    assertEqual(response.status, 201, "status")

    const claims = admin.__auth.claims.get("uid_first")
    assertEqual(Object.keys(claims).join(","), "tenantId", "claim keys")
    assert(!("role" in claims), "a role claim was minted")

    const tenant = admin.__store.peek(`tenants/${response.body.tenantId}`)
    assertEqual(tenant.ownerUid, "uid_first", "ownerUid")
    assert(!("role" in tenant), "tenant carries a role field")

    // No membership document anywhere: ownership is the tenant field and nothing else.
    const membershipPaths = [...admin.__store.docs.keys()].filter((key) =>
      key.includes("/agents/"),
    )
    assertEqual(membershipPaths.length, 0, `membership docs written: ${membershipPaths.join(", ")}`)
  })

  // ------------------------------------------------------------------ one website per account

  suite("One website per owner account")

  await test("the first website is created and returns the raw key once", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const response = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "https://www.Acme.test/contact" },
    })
    assertEqual(response.status, 201, "status")
    assertEqual(response.body.domain, "acme.test", "domain is normalised")
    assert(/^sk_live_[0-9a-f]{48}$/.test(response.body.apiKey), "key shape")

    const stored = admin.__store.peek(`tenants/tnt_a/websites/${response.body.id}`)
    assertEqual(stored.apiKeyHash, sha256(response.body.apiKey), "only the hash is stored")
    assert(!("apiKey" in stored), "raw key persisted")
  })

  await test("a second website is rejected with 409 website_limit_reached", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const first = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })
    assertEqual(first.status, 201, "first create")

    const second = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "other.test" },
    })
    assertEqual(second.status, 409, "status")
    assertEqual(second.body.error.code, "website_limit_reached", "code")
    assert(second.body.error.message.includes("acme.test"), "message names the linked site")

    const websites = [...admin.__store.docs.keys()].filter((key) =>
      key.startsWith("tenants/tnt_a/websites/"),
    )
    assertEqual(websites.length, 1, "no second row was written")
  })

  await test("two simultaneous creates cannot both win", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const results = await Promise.all([
      app.__handle({
        method: "POST",
        path: "/v1/websites/",
        headers: authHeaders(ownerToken),
        body: { domain: "acme.test" },
      }),
      app.__handle({
        method: "POST",
        path: "/v1/websites/",
        headers: authHeaders(ownerToken),
        body: { domain: "acme-two.test" },
      }),
    ])

    const created = results.filter((entry) => entry.status === 201)
    const rejected = results.filter((entry) => entry.status === 409)
    assertEqual(created.length, 1, "exactly one create succeeded")
    assertEqual(rejected.length, 1, "the other was rejected")
    assertEqual(rejected[0].body.error.code, "website_limit_reached", "code")

    const websites = [...admin.__store.docs.keys()].filter((key) =>
      key.startsWith("tenants/tnt_a/websites/"),
    )
    assertEqual(websites.length, 1, "one row exists")
  })

  await test("removing the site frees the slot, and the listing says so", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const first = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })

    const listedBefore = await app.__handle({
      method: "GET",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
    })
    assertEqual(listedBefore.body.canAddWebsite, false, "slot taken")
    assertEqual(listedBefore.body.limit, 1, "limit reported")

    const removed = await app.__handle({
      method: "DELETE",
      path: `/v1/websites/${first.body.id}`,
      headers: authHeaders(ownerToken),
    })
    assertEqual(removed.status, 200, "delete status")

    const listedAfter = await app.__handle({
      method: "GET",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
    })
    assertEqual(listedAfter.body.canAddWebsite, true, "slot freed")

    const replacement = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme-new.test" },
    })
    assertEqual(replacement.status, 201, "replacement created")
  })

  await test("one tenant's limit does not affect another tenant", async () => {
    const admin = reset()
    const a = seedWorkspace(admin, { tenantId: "tnt_a", ownerUid: "uid_a" })
    const b = seedWorkspace(admin, { tenantId: "tnt_b", ownerUid: "uid_b" })
    const app = buildApp([["/v1/websites", require("../../src/routes/websites")]])

    const first = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(a.ownerToken),
      body: { domain: "a.test" },
    })
    const second = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(b.ownerToken),
      body: { domain: "b.test" },
    })
    assertEqual(first.status, 201, "tenant A")
    assertEqual(second.status, 201, "tenant B")
  })

  // ------------------------------------------------------------------ feature matching

  suite("hasFeature: exact match only")

  await test("a blank string in features[] unlocks nothing", async () => {
    reset()
    const { hasFeature } = require("../../src/lib/entitlements")
    const tenant = {
      status: "active",
      features: ["", "   "],
      currentPeriodEnd: Date.now() + DAY,
    }
    for (const feature of ["chat", "email_automation", "social_media", "", "   "]) {
      assertEqual(hasFeature(tenant, feature), false, `hasFeature(${JSON.stringify(feature)})`)
    }
  })

  await test("a wildcard entry is not special either", async () => {
    reset()
    const { hasFeature } = require("../../src/lib/entitlements")
    const tenant = { status: "active", features: ["*"], currentPeriodEnd: Date.now() + DAY }
    assertEqual(hasFeature(tenant, "chat"), false, "chat via wildcard")
    assertEqual(hasFeature(tenant, "email_automation"), false, "email via wildcard")
    // The literal string is still matchable, so nothing silently disappears.
    assertEqual(hasFeature(tenant, "*"), true, "literal match")
  })

  await test("named features match exactly and nothing else", async () => {
    reset()
    const { hasFeature } = require("../../src/lib/entitlements")
    const tenant = {
      status: "active",
      features: ["chat", " email_automation "],
      currentPeriodEnd: Date.now() + DAY,
    }
    assertEqual(hasFeature(tenant, "chat"), true, "chat")
    assertEqual(hasFeature(tenant, "email_automation"), true, "padded entry still matches")
    assertEqual(hasFeature(tenant, "social_media"), false, "unlisted feature")
    assertEqual(hasFeature(tenant, "cha"), false, "prefix")
    assertEqual(hasFeature(tenant, "chatty"), false, "superstring")
    assertEqual(hasFeature(tenant, "CHAT"), false, "case")
    assertEqual(hasFeature(tenant, undefined), false, "undefined")
    assertEqual(hasFeature(tenant, null), false, "null")
  })

  await test("a lapsed subscription fails every check regardless of features[]", async () => {
    reset()
    const { hasFeature } = require("../../src/lib/entitlements")
    const canceled = {
      status: "canceled",
      features: ["chat"],
      currentPeriodEnd: Date.now() - DAY,
    }
    assertEqual(hasFeature(canceled, "chat"), false, "canceled")

    const pastDueInGrace = {
      status: "past_due",
      features: ["chat"],
      currentPeriodEnd: Date.now() - DAY,
    }
    assertEqual(hasFeature(pastDueInGrace, "chat"), true, "past_due inside grace")

    const pastDueExpired = {
      status: "past_due",
      features: ["chat"],
      currentPeriodEnd: Date.now() - 30 * DAY,
    }
    assertEqual(hasFeature(pastDueExpired, "chat"), false, "past_due past grace")
  })

  await test("a blank features entry does not open the widget handshake", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin, { features: [""] })
    const app = buildApp([
      ["/v1/websites", require("../../src/routes/websites")],
      ["/v1/widget", require("../../src/routes/widget")],
    ])

    const created = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })

    const handshake = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      headers: { Origin: "https://acme.test" },
      body: { apiKey: created.body.apiKey },
    })
    assertEqual(handshake.status, 402, "status")
    assertEqual(handshake.body.error.code, "subscription_inactive", "code")
  })

  suite("The API key may arrive in the body or as a Bearer header")

  await test("a Bearer Authorization header is accepted", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([
      ["/v1/websites", require("../../src/routes/websites")],
      ["/v1/widget", require("../../src/routes/widget")],
    ])

    const created = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })

    // No apiKey in the body at all: this is the shape the WordPress proxy sends.
    const handshake = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      headers: { Origin: "https://acme.test", Authorization: `Bearer ${created.body.apiKey}` },
      body: {},
    })
    assertEqual(handshake.status, 200, "status")
    assert(Boolean(handshake.body.widgetToken), "widget token issued")
  })

  await test("a Bearer header carrying a rotated-away key is still rejected", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([
      ["/v1/websites", require("../../src/routes/websites")],
      ["/v1/widget", require("../../src/routes/widget")],
    ])

    const created = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })
    await app.__handle({
      method: "POST",
      path: `/v1/websites/${created.body.id}/rotate-key`,
      headers: authHeaders(ownerToken),
    })

    // The alternative transport must not become an alternative trust path.
    const handshake = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      headers: { Origin: "https://acme.test", Authorization: `Bearer ${created.body.apiKey}` },
      body: {},
    })
    assertEqual(handshake.status, 401, "status")
    assertEqual(handshake.body.error.code, "invalid_api_key", "code")
  })

  // ------------------------------------------------------------------ key rotation

  suite("API key rotation invalidates the old key immediately")

  await test("the old key is rejected on the very next handshake", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([
      ["/v1/websites", require("../../src/routes/websites")],
      ["/v1/widget", require("../../src/routes/widget")],
    ])

    const created = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })
    const oldKey = created.body.apiKey

    // Baseline: the original key works before rotation, so a later rejection means something.
    const before = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      headers: { Origin: "https://acme.test" },
      body: { apiKey: oldKey },
    })
    assertEqual(before.status, 200, "handshake before rotation")
    assert(Boolean(before.body.widgetToken), "widget token issued")

    const rotated = await app.__handle({
      method: "POST",
      path: `/v1/websites/${created.body.id}/rotate-key`,
      headers: authHeaders(ownerToken),
    })
    assertEqual(rotated.status, 200, "rotate status")
    const newKey = rotated.body.apiKey
    assert(newKey !== oldKey, "a different key was issued")

    // No delay, no cache flush, no re-deploy: the next call in the same process.
    const after = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      headers: { Origin: "https://acme.test" },
      body: { apiKey: oldKey },
    })
    assertEqual(after.status, 401, "old key status")
    assertEqual(after.body.error.code, "invalid_api_key", "old key code")

    const withNew = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      headers: { Origin: "https://acme.test" },
      body: { apiKey: newKey },
    })
    assertEqual(withNew.status, 200, "new key works")
  })

  await test("rotation is single-valued: only the newest key resolves", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([
      ["/v1/websites", require("../../src/routes/websites")],
      ["/v1/widget", require("../../src/routes/widget")],
    ])

    const created = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })

    const keys = [created.body.apiKey]
    for (let i = 0; i < 3; i += 1) {
      const rotated = await app.__handle({
        method: "POST",
        path: `/v1/websites/${created.body.id}/rotate-key`,
        headers: authHeaders(ownerToken),
      })
      keys.push(rotated.body.apiKey)
    }

    const stored = admin.__store.peek(`tenants/tnt_a/websites/${created.body.id}`)
    assertEqual(stored.apiKeyHash, sha256(keys[keys.length - 1]), "hash is the newest key")

    for (const superseded of keys.slice(0, -1)) {
      const response = await app.__handle({
        method: "POST",
        path: "/v1/widget/handshake",
        headers: { Origin: "https://acme.test" },
        body: { apiKey: superseded },
      })
      assertEqual(response.body.error.code, "invalid_api_key", "superseded key rejected")
    }
  })

  await test("a deactivated site's key stops working too", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([
      ["/v1/websites", require("../../src/routes/websites")],
      ["/v1/widget", require("../../src/routes/widget")],
    ])

    const created = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })

    await app.__handle({
      method: "DELETE",
      path: `/v1/websites/${created.body.id}`,
      headers: authHeaders(ownerToken),
    })

    const handshake = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      headers: { Origin: "https://acme.test" },
      body: { apiKey: created.body.apiKey },
    })
    assertEqual(handshake.status, 403, "status")
    assertEqual(handshake.body.error.code, "website_inactive", "code")
  })

  await test("the key is bound to its registered domain", async () => {
    const admin = reset()
    const { ownerToken } = seedWorkspace(admin)
    const app = buildApp([
      ["/v1/websites", require("../../src/routes/websites")],
      ["/v1/widget", require("../../src/routes/widget")],
    ])

    const created = await app.__handle({
      method: "POST",
      path: "/v1/websites/",
      headers: authHeaders(ownerToken),
      body: { domain: "acme.test" },
    })

    const wrongOrigin = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      headers: { Origin: "https://attacker.test" },
      body: { apiKey: created.body.apiKey },
    })
    assertEqual(wrongOrigin.body.error.code, "origin_not_allowed", "wrong origin")

    const noOrigin = await app.__handle({
      method: "POST",
      path: "/v1/widget/handshake",
      body: { apiKey: created.body.apiKey },
    })
    assertEqual(noOrigin.body.error.code, "origin_not_allowed", "missing origin")
  })
}
