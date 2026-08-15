/**
 * Websites: the thing a widget is embedded on, and the only source of valid API keys.
 *
 * One website per tenant, enforced here. That limit is the tenant-isolation boundary: a key
 * belongs to exactly one website, a website belongs to exactly one tenant, and a tenant can never
 * accumulate a list of domains to be reasoned about. The exact-domain check at handshake stays as
 * a second, cheaper line of defence, but the boundary no longer depends on an open-ended
 * allowlist being correct.
 *
 * The raw key is returned exactly once, at creation. Only its SHA-256 is stored, so a leaked
 * Firestore export cannot be replayed against the handshake.
 */

const express = require("express")
const { firestore, FieldValue } = require("../firebase")
const { asyncHandler, requiredString, notFound, conflict } = require("../http")
const { requireAuth, requireTenant, attachTenant, requireOwner } = require("../middleware/auth")
const { generateApiKey, sha256, randomId } = require("../lib/crypto")

const router = express.Router()

// Every route here is "an authenticated owner of this tenant", and nothing else.
router.use(requireAuth, requireTenant, attachTenant, requireOwner)

function websitesRef(tenantId) {
  return firestore().collection("tenants").doc(tenantId).collection("websites")
}

/** Normalises "https://www.Example.com/path" and "Example.com" to the same stored domain. */
function normalizeDomain(input) {
  let value = String(input).trim().toLowerCase()
  value = value.replace(/^https?:\/\//, "")
  value = value.split("/")[0]
  value = value.replace(/^www\./, "")
  return value
}

function present(doc) {
  const data = doc.data()
  return {
    id: doc.id,
    domain: data.domain,
    active: data.active !== false,
    // Never the hash, never the key. A fingerprint is enough to tell two keys apart in a UI.
    keyFingerprint: data.apiKeyHash ? data.apiKeyHash.slice(0, 8) : null,
    createdAt: data.createdAt && data.createdAt.toMillis ? data.createdAt.toMillis() : null,
  }
}

router.get(
  "/",
  asyncHandler(async (req, res) => {
    const snap = await websitesRef(req.auth.tenantId).orderBy("createdAt", "desc").get()
    const websites = snap.docs.map(present)
    res.json({
      websites,
      // The app hides its "Register" form on this rather than guessing the rule.
      limit: 1,
      canAddWebsite: websites.every((site) => site.active === false),
    })
  }),
)

/**
 * POST /v1/websites { domain } — returns the raw apiKey once and never again.
 *
 * 409 website_limit_reached when this tenant already has an active website. The check and the
 * write share a transaction, so two taps on Register a millisecond apart cannot both win.
 * Removing a site deactivates it (leads reference websiteId), and a deactivated site does not
 * hold the slot, so an owner can move to a new domain without support intervention.
 */
router.post(
  "/",
  asyncHandler(async (req, res) => {
    const domain = normalizeDomain(requiredString(req.body, "domain", 255))
    const collection = websitesRef(req.auth.tenantId)
    const websiteId = randomId("web")
    const apiKey = generateApiKey()

    await firestore().runTransaction(async (tx) => {
      const existing = await tx.get(collection.where("active", "==", true).limit(1))
      if (!existing.empty) {
        const current = existing.docs[0].data()
        throw conflict(
          `This account is already linked to ${current.domain}. An account can have one ` +
            "website. Remove that site first if you need to move the widget somewhere else.",
          "website_limit_reached",
        )
      }

      tx.set(collection.doc(websiteId), {
        domain,
        tenantId: req.auth.tenantId,
        apiKeyHash: sha256(apiKey),
        active: true,
        createdAt: FieldValue.serverTimestamp(),
      })
    })

    res.status(201).json({
      id: websiteId,
      domain,
      apiKey,
      warning: "Copy this key now. It is hashed on save and cannot be shown again.",
    })
  }),
)

/**
 * POST /v1/websites/:id/rotate-key — same one-time-display contract.
 *
 * Invalidation is immediate rather than eventual, and that is a property of the storage shape
 * rather than of anything this handler remembers to do: the document holds exactly one
 * apiKeyHash, the handshake resolves a key by looking that hash up, and this update overwrites
 * it. The moment this write commits the old key resolves to no website at all.
 */
router.post(
  "/:id/rotate-key",
  asyncHandler(async (req, res) => {
    const ref = websitesRef(req.auth.tenantId).doc(req.params.id)
    const snap = await ref.get()
    if (!snap.exists) throw notFound("Website not found.", "website_not_found")

    const apiKey = generateApiKey()
    await ref.update({ apiKeyHash: sha256(apiKey), rotatedAt: FieldValue.serverTimestamp() })

    res.json({
      id: ref.id,
      apiKey,
      warning: "The previous key stopped working immediately. Update the widget snippet.",
    })
  }),
)

router.delete(
  "/:id",
  asyncHandler(async (req, res) => {
    const ref = websitesRef(req.auth.tenantId).doc(req.params.id)
    const snap = await ref.get()
    if (!snap.exists) throw notFound("Website not found.", "website_not_found")
    // Deactivated, not deleted: leads reference websiteId and orphaning them would break the
    // grouped Emails screen for data the owner never asked to lose. It frees the one-website slot.
    await ref.update({ active: false, deactivatedAt: FieldValue.serverTimestamp() })
    res.json({ ok: true, id: ref.id, active: false })
  }),
)

module.exports = router
