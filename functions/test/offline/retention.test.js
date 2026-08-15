/**
 * Retention sweep, against the real src/jobs/retention.js.
 *
 * The shared RTDB fake in fakes.js is a flat key/value map with get/set/update, which is all the
 * routes under test need. The retention job is different: it walks snapshots — forEach over tenant
 * children, child("conversations"), .key, .val() — so it needs a stub shaped like a DataSnapshot.
 * That stub lives here rather than in fakes.js because nothing else uses it, and because keeping
 * it next to the assertions makes it obvious what the job is actually being handed.
 *
 * src/firebase.js is replaced in the require cache instead of being loaded, so importing the job
 * never boots the Admin SDK or looks for credentials.
 */

const { suite, test, assert, assertEqual } = require("./harness")

const HOUR = 60 * 60 * 1000

// --------------------------------------------------------------------------- snapshot stub

/**
 * A DataSnapshot-shaped view over a plain object.
 *
 * forEach follows the real contract in the one way this job depends on: a callback returning true
 * cancels enumeration. The batch cut-off is implemented with exactly that, so a stub that ignored
 * the return value would make the PURGE_BATCH test pass for the wrong reason.
 */
function snapshot(key, value) {
	return {
		key,
		exists: () => value !== undefined && value !== null,
		val: () => (value === undefined ? null : value),
		child(path) {
			let current = value
			for (const segment of String(path).split("/")) {
				if (!segment) continue
				current = current && typeof current === "object" ? current[segment] : undefined
			}
			return snapshot(String(path).split("/").pop(), current)
		},
		forEach(callback) {
			if (!value || typeof value !== "object") return false
			for (const childKey of Object.keys(value)) {
				if (callback(snapshot(childKey, value[childKey])) === true) return true
			}
			return false
		},
	}
}

/** Applies a multi-path update to a plain tree, where null deletes. Mirrors RTDB semantics. */
function applyUpdates(tree, updates) {
	for (const [path, value] of Object.entries(updates)) {
		const segments = path.split("/").filter(Boolean)
		const last = segments.pop()
		let node = tree
		for (const segment of segments) {
			if (!node[segment] || typeof node[segment] !== "object") node[segment] = {}
			node = node[segment]
		}
		if (value === null) delete node[last]
		else node[last] = value
	}
}

/**
 * Loads the job with a stub database. Returns the job plus the tree it is operating on, so
 * assertions can be made about what survived rather than only about the returned counts.
 */
function loadJob(tree) {
	const reads = []
	const db = {
		ref(path) {
			const key = String(path === undefined ? "" : path).replace(/^\/+|\/+$/g, "")
			return {
				path: key,
				async get() {
					reads.push(key)
					let current = tree
					for (const segment of key.split("/").filter(Boolean)) {
						current = current && typeof current === "object" ? current[segment] : undefined
					}
					return snapshot(key.split("/").pop() || null, current)
				},
				async update(updates) {
					applyUpdates(tree, updates)
				},
			}
		},
	}

	const firebasePath = require.resolve("../../src/firebase")
	const jobPath = require.resolve("../../src/jobs/retention")
	delete require.cache[jobPath]
	require.cache[firebasePath] = {
		id: firebasePath,
		filename: firebasePath,
		loaded: true,
		exports: { rtdb: () => db },
	}

	const job = require("../../src/jobs/retention")
	delete require.cache[firebasePath]
	delete require.cache[jobPath]
	return { job, tree, reads }
}

/** One tenant, one conversation per supplied expiresAt, each with a message attached. */
function treeWith(expiryByConversation, tenantId = "t1") {
	const conversations = {}
	const messages = {}
	for (const [id, expiresAt] of Object.entries(expiryByConversation)) {
		conversations[id] = { status: "open", lastMessage: { text: "hi", at: 1 } }
		// undefined means "the field was never written", which is a distinct case from null.
		if (expiresAt !== undefined) conversations[id].expiresAt = expiresAt
		messages[id] = { m1: { sender: "visitor", text: "hi" } }
	}
	return { chats: { [tenantId]: { ownerUid: "owner", conversations, messages } } }
}

// --------------------------------------------------------------------------- tests

module.exports = async function retentionTests() {
	suite("Retention sweep")

	const now = 1_700_000_000_000

	await test("deletes a conversation whose expiresAt has passed, with its messages", async () => {
		const { job, tree } = loadJob(treeWith({ c1: now - HOUR }))
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 1, "deleted count")
		assertEqual(result.scanned, 1, "scanned count")
		assertEqual(tree.chats.t1.conversations.c1, undefined, "conversation removed")
		assertEqual(tree.chats.t1.messages.c1, undefined, "messages removed with it")
	})

	await test("keeps a conversation whose expiresAt is still in the future", async () => {
		const { job, tree } = loadJob(treeWith({ c1: now + HOUR }))
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 0, "deleted count")
		assert(tree.chats.t1.conversations.c1, "conversation survives")
		assert(tree.chats.t1.messages.c1, "messages survive")
	})

	/*
	 * The regression this file was written for.
	 *
	 * The app writes expiresAt = 0 when the owner turns Keep on; the API writes null for the same
	 * action. Read literally, 0 is 1970, so the sweep deleted exactly the chats that had been
	 * pinned — and did it on the very next run, within fifteen minutes of the owner pinning them.
	 */
	await test("treats expiresAt 0 as pinned, not as overdue since 1970", async () => {
		const { job, tree } = loadJob(treeWith({ c1: 0 }))
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 0, "deleted count")
		assert(tree.chats.t1.conversations.c1, "pinned conversation survives")
		assert(tree.chats.t1.messages.c1, "pinned messages survive")
	})

	await test("treats null expiresAt as pinned", async () => {
		const { job, tree } = loadJob(treeWith({ c1: null }))
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 0, "deleted count")
		assert(tree.chats.t1.conversations.c1, "conversation survives")
	})

	await test("leaves records that never had an expiresAt alone", async () => {
		const { job, tree } = loadJob(treeWith({ c1: undefined }))
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 0, "deleted count")
		assert(tree.chats.t1.conversations.c1, "conversation survives")
	})

	await test("ignores a negative expiresAt rather than treating it as ancient", async () => {
		const { job, tree } = loadJob(treeWith({ c1: -5 }))
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 0, "deleted count")
		assert(tree.chats.t1.conversations.c1, "conversation survives")
	})

	await test("expiresAt exactly equal to now is expired", async () => {
		const { job, tree } = loadJob(treeWith({ c1: now }))
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 1, "deleted count")
		assertEqual(tree.chats.t1.conversations.c1, undefined, "conversation removed")
	})

	await test("mixed tenants: only expired rows go, and pinned rows are untouched", async () => {
		const tree = {
			chats: {
				t1: {
					conversations: { a: { expiresAt: now - 1 }, b: { expiresAt: 0 } },
					messages: { a: { m: 1 }, b: { m: 1 } },
				},
				t2: {
					conversations: { c: { expiresAt: now + HOUR }, d: { expiresAt: now - HOUR } },
					messages: { c: { m: 1 }, d: { m: 1 } },
				},
			},
		}
		const { job } = loadJob(tree)
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.tenants, 2, "both tenants visited")
		assertEqual(result.deleted, 2, "one expired row per tenant")
		assertEqual(tree.chats.t1.conversations.a, undefined, "t1 expired row removed")
		assert(tree.chats.t1.conversations.b, "t1 pinned row survives")
		assert(tree.chats.t2.conversations.c, "t2 future row survives")
		assertEqual(tree.chats.t2.conversations.d, undefined, "t2 expired row removed")
		assertEqual(tree.chats.t2.messages.d, undefined, "t2 expired messages removed")
	})

	await test("a tenant with no conversations node does not throw", async () => {
		const { job } = loadJob({ chats: { t1: { ownerUid: "owner" } } })
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 0, "deleted count")
		assertEqual(result.tenants, 1, "tenant still counted")
	})

	await test("an empty chats tree is a no-op", async () => {
		const { job } = loadJob({})
		const result = await job.purgeExpiredConversations(now)

		assertEqual(result.deleted, 0, "deleted count")
		assertEqual(result.tenants, 0, "no tenants")
	})

	await test("stops at PURGE_BATCH and leaves the rest for the next run", async () => {
		const { PURGE_BATCH } = require("../../src/config")
		const expiry = {}
		for (let i = 0; i < PURGE_BATCH + 25; i += 1) expiry[`c${i}`] = now - HOUR

		const { job, tree } = loadJob(treeWith(expiry))
		const first = await job.purgeExpiredConversations(now)
		assertEqual(first.deleted, PURGE_BATCH, "first run fills the batch exactly")
		assertEqual(
			Object.keys(tree.chats.t1.conversations).length,
			25,
			"survivors left for the next run",
		)

		// Idempotence: the follow-up run finds the remainder by the same test and finishes the job.
		const second = await job.purgeExpiredConversations(now)
		assertEqual(second.deleted, 25, "second run clears the remainder")
		assertEqual(Object.keys(tree.chats.t1.conversations).length, 0, "nothing left")
	})

	await test("scoped run reads one tenant and cannot touch another", async () => {
		const tree = {
			chats: {
				t1: { conversations: { a: { expiresAt: now - 1 } }, messages: { a: { m: 1 } } },
				t2: { conversations: { b: { expiresAt: now - 1 } }, messages: { b: { m: 1 } } },
			},
		}
		const { job, reads } = loadJob(tree)
		const result = await job.purgeExpiredConversations(now, { tenantId: "t1" })

		assertEqual(result.deleted, 1, "only the scoped tenant's row is deleted")
		assertEqual(result.tenants, 1, "one tenant visited")
		assertEqual(tree.chats.t1.conversations.a, undefined, "scoped tenant purged")
		assert(tree.chats.t2.conversations.b, "other tenant untouched")
		assertEqual(reads[0], "chats/t1", "read is scoped to the tenant node, not the whole tree")
	})

	await test("scoped run on a tenant that does not exist is a no-op", async () => {
		const { job } = loadJob(treeWith({ c1: now - HOUR }))
		const result = await job.purgeExpiredConversations(now, { tenantId: "nope" })

		assertEqual(result.deleted, 0, "deleted count")
		assertEqual(result.tenants, 0, "no tenants visited")
	})

	await test("isPinned classifies every encoding the way the sweep relies on", async () => {
		const { job } = loadJob({})
		const { isPinned } = job

		assertEqual(isPinned(null), true, "null is pinned")
		assertEqual(isPinned(undefined), true, "absent is pinned")
		assertEqual(isPinned(0), true, "zero is pinned")
		assertEqual(isPinned(-1), true, "negative is pinned")
		assertEqual(isPinned(NaN), true, "NaN is pinned")
		assertEqual(isPinned(Infinity), true, "Infinity is pinned")
		assertEqual(isPinned("0"), true, "a string is not a deadline")
		assertEqual(isPinned(1), false, "a positive number is a deadline")
	})
}
