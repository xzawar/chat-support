/**
 * Retention purge — deletes expired conversations out of chats/{tenantId}/.
 *
 * Why this is a scheduled server job and not client code: a phone that is switched off cannot
 * delete anything, so a client-side purge means the 24-hour promise quietly depends on somebody
 * opening the app. It runs every 15 minutes, deletes in batches so one run cannot exhaust memory,
 * and is idempotent — a run that dies halfway leaves the survivors for the next run, which finds
 * them again by the same expiresAt test.
 *
 * Leads live in Firestore and are never touched here. That is the point of the split: chat is
 * ephemeral, the business record of who talked to you is not.
 *
 * Reminder, because it is the usual reason "nothing is being deleted": this module only runs when
 * the scheduled function is actually deployed. `firebase deploy --only functions` on the Blaze
 * plan creates the Cloud Scheduler job; without it, expiresAt values simply accumulate in the
 * past and nothing ever collects them. POST /v1/admin/purge exists so the same sweep can be run
 * on demand and observed, rather than being taken on faith.
 */

const { rtdb } = require("../firebase")
const { PURGE_BATCH } = require("../config")

/**
 * A conversation is pinned — permanently exempt from retention — when expiresAt is not a positive
 * number. Three encodings all mean "keep this", and all three occur in live data:
 *
 *   null     the API writes this when keepChat is turned on (routes/messages.js).
 *   0        the Android app writes this for the same action (SupportApi.kt patchConversation).
 *            Read literally, 0 is a deadline in 1970, so the previous version of this job saw
 *            every chat the owner had pinned as maximally overdue and deleted it on the next
 *            run. That is the opposite of what Keep means, hence this guard.
 *   absent   records written before retention existed. Inventing a deadline for them is worse
 *            than leaving them alone.
 *
 * NaN and Infinity are rejected for the same reason: neither is a deadline, and `NaN > at` is
 * false, which would otherwise class a corrupt value as expired.
 */
function isPinned(expiresAt) {
	return typeof expiresAt !== "number" || !Number.isFinite(expiresAt) || expiresAt <= 0
}

/**
 * Sweeps expired conversations.
 *
 * @param {number} [now] Millis to evaluate against. Injected by tests; defaults to Date.now().
 * @param {{ tenantId?: string }} [options] Scopes the sweep to one tenant. The scheduled run
 *   passes nothing and sweeps every tenant; the owner-triggered route passes its own verified
 *   tenantId, so one owner can never purge another tenant's chat by calling the API.
 * @returns {Promise<{ scanned: number, deleted: number, tenants: number }>}
 */
async function purgeExpiredConversations(now, options) {
	const at = typeof now === "number" ? now : Date.now()
	const onlyTenantId = (options && options.tenantId) || null

	// Scoped runs read one tenant node instead of the whole chats tree. Same logic either way,
	// but an owner hitting /v1/admin/purge should not pull every other tenant into memory.
	const rootRef = onlyTenantId ? rtdb().ref(`chats/${onlyTenantId}`) : rtdb().ref("chats")
	const rootSnap = await rootRef.get()

	if (!rootSnap.exists()) {
		return { scanned: 0, deleted: 0, tenants: 0 }
	}

	const updates = {}
	let scanned = 0
	let deleted = 0
	let tenants = 0

	/** Returns true when the batch is full, which tells the caller's forEach to stop iterating. */
	const visitTenant = (tenantId, tenantNode) => {
		tenants += 1
		const conversations = tenantNode.child("conversations")

		conversations.forEach((conversationNode) => {
			if (deleted >= PURGE_BATCH) return true // stop iterating; the next run picks up the rest

			scanned += 1
			const conversation = conversationNode.val() || {}
			const expiresAt = conversation.expiresAt

			if (isPinned(expiresAt) || expiresAt > at) return false

			const conversationId = conversationNode.key
			updates[`chats/${tenantId}/conversations/${conversationId}`] = null
			updates[`chats/${tenantId}/messages/${conversationId}`] = null
			deleted += 1
			return false
		})

		return deleted >= PURGE_BATCH
	}

	if (onlyTenantId) {
		visitTenant(onlyTenantId, rootSnap)
	} else {
		rootSnap.forEach((tenantNode) => visitTenant(tenantNode.key, tenantNode))
	}

	if (Object.keys(updates).length > 0) {
		// One multi-path update: either the whole batch goes or none of it does, so a partial failure
		// cannot leave messages orphaned under a conversation that no longer exists.
		await rtdb().ref().update(updates)
	}

	console.log(
		`Retention purge${onlyTenantId ? ` (tenant ${onlyTenantId})` : ""}: ${deleted} conversation(s) removed, ${scanned} scanned across ${tenants} tenant(s)`,
	)
	return { scanned, deleted, tenants }
}

module.exports = { purgeExpiredConversations, isPinned }
