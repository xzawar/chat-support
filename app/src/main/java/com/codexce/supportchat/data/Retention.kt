package com.codexce.supportchat.data

/*
 * When a conversation is due to be deleted.
 *
 * This exists because the deletion has to happen on the CLIENT. The scheduled Cloud Function that
 * was supposed to do it (functions/src/jobs/retention.js) requires the Blaze plan, so on the free
 * plan nothing on the server ever runs and expired chats accumulate forever. The owner's app is the
 * only party that is both authorised to delete (database.rules.json gives the owner write access to
 * conversations and messages) and guaranteed to run.
 *
 * The rule is deliberately identical to the server's isPinned check, so that deploying the function
 * later cannot produce two components disagreeing about which rows are rubbish.
 */

/**
 * The three ways a row says "never delete me", which is the detail the original sweep got wrong.
 *
 * A pinned chat is stored as `null` by the API, as `0` by this app (SupportApi writes 0L when the
 * user taps Keep), and is absent entirely on rows written before the field existed. Only the first
 * is obviously "no expiry". `0` is the dangerous one: `0 <= now` is true, so a naive comparison
 * deletes precisely the chats the user asked to keep - the bug this fix exists for. Treating any
 * non-positive or missing value as pinned covers all three encodings.
 */
fun isPinnedExpiry(storedExpiresAt: Long?): Boolean = storedExpiresAt == null || storedExpiresAt <= 0L

/**
 * Whether this conversation's retention window has closed.
 *
 * A row is deleted only when it carries a real deadline that has passed. There is deliberately no
 * fallback that infers an expiry from how old the last message is, even though that would clear
 * legacy rows which have no `expiresAt` at all.
 *
 * The reason is that this code cannot tell those legacy rows apart from pinned ones. Firebase's
 * `getValue(Long::class.java)` returns null both for a field that is absent and for one explicitly
 * set to null - and null is exactly how the API records "keep this forever". An age-based fallback
 * would therefore delete chats the user pinned through the API after 24 quiet hours, which is the
 * same category of bug as the `0` case above and worse, because it destroys data silently.
 *
 * Rows with no deadline are consequently immortal until deleted by hand. That is the correct trade:
 * every write path in the app and the widget stamps `expiresAt`, so such rows are effectively
 * historical, and keeping a stale chat too long is recoverable while deleting a pinned one is not.
 *
 * `now >= due` rather than `>` matches the server, so a row whose deadline is exactly now is
 * treated as expired by both and neither leaves it for the other.
 */
fun isRetentionExpired(keepChat: Boolean, storedExpiresAt: Long?, now: Long): Boolean {
    if (keepChat) return false
    if (isPinnedExpiry(storedExpiresAt)) return false
    return now >= storedExpiresAt!!
}
