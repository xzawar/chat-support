/**
 * The one place that decides whether a tenant may do something.
 *
 * Two functions, deliberately. Every gate in the API is `hasFeature(tenant, feature)`; nothing
 * anywhere else reads tenant.status or compares dates, because the moment two places implement
 * "is this subscription alive" they drift and one of them starts letting people through.
 *
 * features[] is copied onto the tenant from the plan doc on every billing event rather than
 * joined at read time. A gate check must not depend on a second document being fetchable.
 */

const { GRACE_MILLIS } = require("../config")

/** Millis from a Firestore Timestamp, a number, or a date string. Null when absent. */
function toMillis(value) {
  if (value === null || value === undefined) return null
  if (typeof value === "number") return value
  if (typeof value === "string") {
    const parsed = Date.parse(value)
    return Number.isNaN(parsed) ? null : parsed
  }
  if (typeof value.toMillis === "function") return value.toMillis()
  if (value instanceof Date) return value.getTime()
  return null
}

/**
 * active and trialing are alive. past_due is alive only inside the 7-day grace window measured
 * from currentPeriodEnd — a card that failed on renewal day should not lock the inbox that same
 * afternoon. Anything else (canceled, unpaid, missing) is dead.
 */
function subscriptionActive(tenant, now) {
  if (!tenant) return false
  const at = typeof now === "number" ? now : Date.now()
  const status = tenant.status || null

  if (status === "active" || status === "trialing") return true

  if (status === "past_due") {
    const periodEnd = toMillis(tenant.currentPeriodEnd)
    if (periodEnd === null) return false
    return at <= periodEnd + GRACE_MILLIS
  }

  return false
}

/**
 * The gate. Exact-match only.
 *
 * There is no wildcard and no grant-all escape hatch. A blank or whitespace-only entry that finds
 * its way into features[] — a bad seed, a trailing comma in a console edit, an empty string from a
 * form — unlocks nothing, and a blank feature name asked for by a caller is never satisfied.
 * Anything that is not a non-empty exact string match is a miss.
 */
function hasFeature(tenant, feature) {
  if (typeof feature !== "string") return false
  const wanted = feature.trim()
  if (wanted.length === 0) return false
  if (!subscriptionActive(tenant)) return false

  const features = Array.isArray(tenant.features) ? tenant.features : []
  return features.some((entry) => typeof entry === "string" && entry.trim() === wanted)
}

module.exports = { hasFeature, subscriptionActive, toMillis }
