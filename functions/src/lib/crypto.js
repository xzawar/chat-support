/**
 * Hashing and token helpers.
 *
 * Two rules encoded here: raw API keys are never stored (only their SHA-256), and lead document
 * ids are derived from the email rather than random, so "upsert this visitor" is a single write
 * with no read-then-write race.
 */

const crypto = require("crypto")

/** Lowercase hex SHA-256. Used for API key storage and lead ids. */
function sha256(value) {
  return crypto.createHash("sha256").update(String(value), "utf8").digest("hex")
}

/** Normalises an email the same way everywhere, so one person is one lead. */
function normalizeEmail(email) {
  return String(email || "").trim().toLowerCase()
}

/** websiteId_sha256(lowercase email) — stable, so re-identifying updates rather than duplicates. */
function leadId(websiteId, email) {
  return `${websiteId}_${sha256(normalizeEmail(email))}`
}

/** A fresh API key. Shown to the owner exactly once; only the hash is persisted. */
function generateApiKey() {
  return `sk_live_${crypto.randomBytes(24).toString("hex")}`
}

function randomId(prefix) {
  return `${prefix}_${crypto.randomBytes(12).toString("hex")}`
}

/**
 * Constant-time string compare. Used for the billing callback secret so a timing side channel
 * cannot be used to guess it byte by byte.
 */
function safeEqual(a, b) {
  const left = Buffer.from(String(a || ""), "utf8")
  const right = Buffer.from(String(b || ""), "utf8")
  if (left.length !== right.length) return false
  return crypto.timingSafeEqual(left, right)
}

/**
 * Strips a hostname out of an Origin header. Returns null for anything unparseable, which the
 * handshake treats as a failed allowlist check rather than a pass.
 */
function originHost(origin) {
  if (!origin || typeof origin !== "string") return null
  try {
    return new URL(origin).hostname.toLowerCase()
  } catch (err) {
    return null
  }
}

/** example.com matches example.com and www.example.com, and nothing else. */
function domainMatches(allowedDomain, host) {
  if (!allowedDomain || !host) return false
  const allowed = String(allowedDomain).trim().toLowerCase().replace(/^www\./, "")
  const candidate = String(host).trim().toLowerCase().replace(/^www\./, "")
  return allowed === candidate
}

module.exports = {
  sha256,
  normalizeEmail,
  leadId,
  generateApiKey,
  randomId,
  safeEqual,
  originHost,
  domainMatches,
}
