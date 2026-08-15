/**
 * Authentication and authorisation for the owner API.
 *
 * Two invariants this file exists to protect:
 *
 *   1. tenantId comes from verified custom claims and from nowhere else. No route reads
 *      req.body.tenantId, no route reads a query param, no route trusts a header.
 *   2. There is exactly one kind of user on a tenant: its owner. There is no role claim and no
 *      role branch anywhere. "May this caller act on this tenant?" is answered by comparing the
 *      verified uid against tenants/{tenantId}.ownerUid, which is server-owned data.
 *
 * Because authorisation is now derived from a Firestore document rather than from a token claim,
 * the old "role demotion is invisible until the ID token refreshes" problem is gone: there is no
 * role to demote, and ownership is re-read on every request.
 */

const { auth, firestore } = require("../firebase")
const { unauthorized, forbidden, paymentRequired, notFound, asyncHandler } = require("../http")
const { hasFeature } = require("../lib/entitlements")

function bearerToken(req) {
  const header = req.get("Authorization") || req.get("authorization") || ""
  if (!header.startsWith("Bearer ")) return null
  const token = header.slice("Bearer ".length).trim()
  return token.length > 0 ? token : null
}

/**
 * Verifies the Firebase ID token. Sets req.auth but does NOT require a tenant claim — the
 * bootstrap route needs an authenticated caller who has no tenant yet.
 */
const requireAuth = asyncHandler(async (req, res, next) => {
  const token = bearerToken(req)
  if (!token) throw unauthorized("Sign in to continue.", "missing_token")

  let decoded
  try {
    decoded = await auth().verifyIdToken(token, true)
  } catch (err) {
    throw unauthorized("Your session has expired. Sign in again.", "invalid_token")
  }

  // No role is read off the token. tenantId is the only claim this backend sets or trusts.
  req.auth = {
    uid: decoded.uid,
    email: decoded.email || null,
    emailVerified: decoded.email_verified === true,
    tenantId: decoded.tenantId || null,
  }
  next()
})

/**
 * Requires a tenant claim. The distinct code lets the app tell "you are signed in but this
 * installation was never set up" apart from "your token is bad", and route the user to bootstrap
 * instead of back to the login screen.
 */
const requireTenant = (req, res, next) => {
  if (!req.auth) return next(unauthorized("Sign in to continue.", "missing_token"))
  if (!req.auth.tenantId) {
    return next(
      unauthorized(
        "This account is not attached to a workspace yet. Run setup, then sign in again.",
        "tenant_not_provisioned",
      ),
    )
  }
  next()
}

/**
 * Loads tenants/{tenantId} once per request and caches it on req. Several gates run on the same
 * request (ownership + feature) and each one re-reading the tenant doc would triple the read cost
 * of every call for no benefit.
 */
async function loadTenant(req) {
  if (req.tenant) return req.tenant
  const snap = await firestore().collection("tenants").doc(req.auth.tenantId).get()
  if (!snap.exists) {
    throw notFound("Workspace not found.", "tenant_not_found")
  }
  req.tenant = { id: snap.id, ...snap.data() }
  return req.tenant
}

const attachTenant = asyncHandler(async (req, res, next) => {
  await loadTenant(req)
  next()
})

/**
 * The only authorisation gate in the system: is this authenticated caller the owner of the tenant
 * their claim points at?
 *
 * Deliberately reads ownerUid from Firestore instead of a claim. A claim can be stale for up to an
 * hour after it changes; the tenant document cannot.
 */
const requireOwner = asyncHandler(async (req, res, next) => {
  if (!req.auth) throw unauthorized("Sign in to continue.", "missing_token")
  const tenant = await loadTenant(req)
  if (!tenant.ownerUid || tenant.ownerUid !== req.auth.uid) {
    throw forbidden("Only the workspace owner can do this.", "owner_only")
  }
  next()
})

/**
 * Feature gate for owner routes. 403 feature_not_in_plan, per the spec — 402 is reserved for the
 * widget handshake, where the distinction is "your subscription lapsed" rather than "your plan
 * never included this".
 */
function requireFeature(feature) {
  return asyncHandler(async (req, res, next) => {
    const tenant = await loadTenant(req)
    if (!hasFeature(tenant, feature)) {
      throw forbidden(
        "Your plan does not include this feature. Upgrade to unlock it.",
        "feature_not_in_plan",
      )
    }
    next()
  })
}

/** Same check, but for the chat path, where an inactive subscription means 402. */
function requireActiveChat() {
  return asyncHandler(async (req, res, next) => {
    const tenant = await loadTenant(req)
    if (!hasFeature(tenant, "chat")) {
      throw paymentRequired(
        "This workspace's subscription is not active.",
        "subscription_inactive",
      )
    }
    next()
  })
}

module.exports = {
  requireAuth,
  requireTenant,
  requireOwner,
  attachTenant,
  requireFeature,
  requireActiveChat,
  loadTenant,
}
