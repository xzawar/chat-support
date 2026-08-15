/**
 * Owner-triggered maintenance.
 *
 * This exists because the retention sweep was invisible. It ran — or did not run — inside Cloud
 * Scheduler, and the only way to find out which was to read function logs in the console. When a
 * chat outlives its 24 hours the first question is "did the sweep run at all?", and that question
 * deserves an answer the app can ask.
 *
 * POST /v1/admin/purge runs exactly the same code as the scheduled job, scoped to the caller's
 * own tenant, and returns what it did. It is not a replacement for the schedule: it is a way to
 * verify the schedule's logic against real data, and a manual escape hatch when Cloud Scheduler
 * is unavailable (a Spark-plan project, or a deploy where only `--only functions:api` landed).
 *
 * tenantId comes from the verified claim, never from the body, so this cannot be used to reach
 * into another tenant's chat. Owner-only, for the same reason every other write here is.
 */

const express = require("express")
const { asyncHandler } = require("../http")
const { requireAuth, requireTenant, attachTenant, requireOwner } = require("../middleware/auth")
const { purgeExpiredConversations } = require("../jobs/retention")

const router = express.Router()

// Deliberately not behind requireActiveChat. Deleting data the owner was promised would be
// deleted must keep working after a subscription lapses; retention is a commitment, not a
// feature. Gating cleanup behind billing would mean a lapsed tenant's chat lives forever.
router.use(requireAuth, requireTenant, attachTenant, requireOwner)

/**
 * POST /v1/admin/purge -> { ok, scanned, deleted, tenants, at }
 *
 * Idempotent. Running it twice in a row is harmless: the second call finds nothing, because the
 * first call's multi-path delete already committed.
 */
router.post(
	"/purge",
	asyncHandler(async (req, res) => {
		const at = Date.now()
		const result = await purgeExpiredConversations(at, { tenantId: req.auth.tenantId })
		res.json({ ok: true, at, ...result })
	}),
)

module.exports = router
