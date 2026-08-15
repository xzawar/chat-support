/**
 * GET /v1/leads — cursor-paginated lead list, gated on email_automation.
 *
 * Cursor is the lastSeenAt millis plus the document id, not an offset. Offsets re-scan everything
 * before the page and drift when a lead is touched mid-scroll; a keyset cursor is stable.
 */

const express = require("express")
const { firestore } = require("../firebase")
const { asyncHandler, badRequest } = require("../http")
const { requireAuth, requireTenant, attachTenant, requireOwner, requireFeature } = require("../middleware/auth")
const { FEATURE_EMAIL } = require("../config")
const { toMillis } = require("../lib/entitlements")

const router = express.Router()

router.use(requireAuth, requireTenant, attachTenant, requireOwner, requireFeature(FEATURE_EMAIL))

const MAX_LIMIT = 100
const DEFAULT_LIMIT = 50

function encodeCursor(doc) {
  const data = doc.data()
  const seen = toMillis(data.lastSeenAt) || 0
  return Buffer.from(`${seen}:${doc.id}`, "utf8").toString("base64url")
}

function decodeCursor(cursor) {
  try {
    const raw = Buffer.from(String(cursor), "base64url").toString("utf8")
    const separator = raw.indexOf(":")
    if (separator < 0) return null
    return { lastSeenAt: Number(raw.slice(0, separator)), id: raw.slice(separator + 1) }
  } catch (err) {
    return null
  }
}

function serialize(doc) {
  const data = doc.data()
  return {
    id: doc.id,
    websiteId: data.websiteId || null,
    websiteDomain: data.websiteDomain || null,
    email: data.email || null,
    name: data.name || null,
    source: data.source || "manual",
    emailVerified: data.emailVerified === true,
    marketingConsent: data.marketingConsent === true,
    conversationCount: data.conversationCount || 0,
    firstSeenAt: toMillis(data.firstSeenAt),
    lastSeenAt: toMillis(data.lastSeenAt),
  }
}

router.get(
  "/",
  asyncHandler(async (req, res) => {
    const tenantId = req.auth.tenantId
    const limit = Math.min(
      MAX_LIMIT,
      Math.max(1, parseInt(req.query.limit, 10) || DEFAULT_LIMIT),
    )

    let query = firestore()
      .collection("tenants")
      .doc(tenantId)
      .collection("leads")
      .orderBy("lastSeenAt", "desc")
      .orderBy("__name__", "desc")

    // Optional filter for the grouped-by-site view, so the app can lazy-load one group at a time
    // instead of pulling every lead to render a header count.
    if (req.query.websiteId) {
      query = firestore()
        .collection("tenants")
        .doc(tenantId)
        .collection("leads")
        .where("websiteId", "==", String(req.query.websiteId))
        .orderBy("lastSeenAt", "desc")
        .orderBy("__name__", "desc")
    }

    if (req.query.cursor) {
      const cursor = decodeCursor(req.query.cursor)
      if (!cursor) throw badRequest("Invalid cursor.", "invalid_cursor")
      query = query.startAfter(new Date(cursor.lastSeenAt), cursor.id)
    }

    // Fetch one extra to detect a next page without a second count query.
    const snap = await query.limit(limit + 1).get()
    const docs = snap.docs.slice(0, limit)
    const hasMore = snap.docs.length > limit

    res.json({
      leads: docs.map(serialize),
      nextCursor: hasMore && docs.length > 0 ? encodeCursor(docs[docs.length - 1]) : null,
      hasMore,
    })
  }),
)

/**
 * GET /v1/leads/groups — per-website counts for the Emails screen headers.
 *
 * Uses an aggregation count() per website rather than reading every lead, so a tenant with
 * 50,000 leads still renders headers in one round trip each.
 */
router.get(
  "/groups",
  asyncHandler(async (req, res) => {
    const tenantId = req.auth.tenantId
    const db = firestore()

    const websitesSnap = await db
      .collection("tenants")
      .doc(tenantId)
      .collection("websites")
      .get()

    const groups = await Promise.all(
      websitesSnap.docs.map(async (websiteDoc) => {
        const countSnap = await db
          .collection("tenants")
          .doc(tenantId)
          .collection("leads")
          .where("websiteId", "==", websiteDoc.id)
          .count()
          .get()

        return {
          websiteId: websiteDoc.id,
          websiteDomain: websiteDoc.data().domain || null,
          leadCount: countSnap.data().count,
        }
      }),
    )

    res.json({ groups: groups.sort((a, b) => b.leadCount - a.leadCount) })
  }),
)

module.exports = router
