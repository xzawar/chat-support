# Chat retention on the free (Spark) plan

## Why chats were not being deleted

Retention was written as a scheduled Cloud Function (`purgeExpiredConversations`, every 15 minutes).
Cloud Functions cannot be deployed at all on the Spark plan, so that job has never run once. Neither
has the rest of `functions/` - the `/v1` REST API and the `notifyOwnerOn*` triggers.

The app still works because it does not depend on any of it:

- it reads and writes the Realtime Database directly (`SupportApi` uses `rtdb.updateChildren(...)`),
- `MessageWatchService`, a foreground service with its own database listener, stands in for the
  push notifications the triggers would have sent.

Retention was the one feature with no client-side equivalent, so it simply never happened.

There was also a genuine bug in the server job, now fixed: a chat pinned with Keep is stored as
`expiresAt = 0`, and `0 <= now` is true, so the job would have deleted pinned chats first. It would
have done the wrong thing the moment it was ever enabled.

## What now happens instead

Deletion moved into the owner's app, which is the only party that is both permitted to delete
(`database.rules.json` already grants the owner write access to `conversations` and `messages`) and
guaranteed to run.

Every time the inbox snapshot arrives, `SupportRepository.remoteConversations()`:

1. filters expired conversations out of the list before it reaches the UI, so they disappear
   immediately - even offline, or if the delete is refused;
2. deletes each one from the database, conversation and message thread in a single update, batched
   200 at a time;
3. prunes the local Room cache and outbox, but only after the server has accepted the delete.

A failed sweep is silent and is retried on the next snapshot. No database rules change and no Room
migration were needed.

The rule lives in `app/src/main/java/com/codexce/supportchat/data/Retention.kt` and is deliberately
identical to the server's, so enabling the function later cannot make the two disagree. A chat is
deleted only when it carries a real deadline that has passed. All of `0`, `null`, a negative value,
and a missing field mean "keep forever", because those are the different ways the app, the API and
older rows record a pin.

There is intentionally no rule that infers an expiry from how old the last message is. Firebase
cannot distinguish an absent `expiresAt` from one explicitly set to `null`, and `null` is how the API
records a pin - so such a rule would quietly delete pinned chats after 24 quiet hours. Rows with no
deadline therefore persist until deleted by hand.

## The limitation, stated plainly

Deletion only happens while the owner app has the inbox open. `MessageWatchService` uses its own
listener and does not go through `SupportRepository`, so it was left untouched. A chat can outlive
its 24 hours until someone next opens the app, at which point it is removed within a second and
never appears in the list.

If that matters, the fix is the Blaze plan, not more client code. Blaze includes a free monthly
allowance that a 15-minute purge over a small database stays inside, so the practical cost is a card
on file rather than a bill. Everything needed is already written and deploys unchanged:
`firebase deploy --only functions,database`. There is also `POST /v1/admin/purge` (owner only) for
running a sweep by hand, dormant for the same reason.
