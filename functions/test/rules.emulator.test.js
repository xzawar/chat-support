/**
 * Rules tests through the real rules engine, using @firebase/rules-unit-testing.
 *
 * Run with:
 *   cd functions && npm install
 *   firebase emulators:exec --only firestore,database "npm run test:rules"
 *
 * The emulator is authoritative: it runs the same rules engine production runs. The offline suite
 * (npm run test:offline) checks the same properties by evaluating the rules files directly, so the
 * invariants can still be re-verified on a machine with no emulator and no network. Neither
 * replaces the other; this file is the one to trust when they disagree.
 *
 * Cases mirror the acceptance checklist:
 *   - No client can read or write any Firestore collection (deny-all holds).
 *   - An owner token reads chats/{tenantId}/... for its own tenantId only.
 *   - A visitor (widget) token reads exactly one conversation and cannot list the inbox.
 *   - No path anywhere in RTDB accepts a client write.
 */

const fs = require("fs")
const path = require("path")
const assert = require("assert")
const { describe, it, before, after } = require("node:test")
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing")
const {
  doc,
  getDoc,
  setDoc,
  collection,
  getDocs,
  deleteDoc,
} = require("firebase/firestore")
const { ref, get, set, update, remove } = require("firebase/database")

const ROOT = path.resolve(__dirname, "..", "..")
const TENANT_A = "tnt_a"
const TENANT_B = "tnt_b"
const CONVERSATION = "conv_1"

let testEnv

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "support-chat-rules-test",
    firestore: {
      rules: fs.readFileSync(path.join(ROOT, "firestore.rules"), "utf8"),
      host: "127.0.0.1",
      port: 8080,
    },
    database: {
      rules: fs.readFileSync(path.join(ROOT, "database.rules.json"), "utf8"),
      host: "127.0.0.1",
      port: 9000,
    },
  })

  // Seed through the admin context, which bypasses rules exactly as the Admin SDK does in
  // production. Everything the tests then attempt is a *client* attempt.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore()
    await setDoc(doc(db, "tenants", TENANT_A), { name: "Acme", ownerUid: "uid_a" })
    await setDoc(doc(db, "tenants", TENANT_A, "websites", "web_1"), { domain: "acme.test" })
    await setDoc(doc(db, "plans", "plan_1"), { name: "Starter", tier: 1 })

    const rtdb = context.database()
    await set(ref(rtdb, `chats/${TENANT_A}/conversations/${CONVERSATION}`), {
      id: CONVERSATION,
      status: "pending",
    })
    await set(ref(rtdb, `chats/${TENANT_A}/conversations/conv_2`), { id: "conv_2" })
    await set(ref(rtdb, `chats/${TENANT_A}/messages/${CONVERSATION}/m1`), { text: "hi" })
    await set(ref(rtdb, `chats/${TENANT_B}/conversations/conv_9`), { id: "conv_9" })
    await set(ref(rtdb, `chats/${TENANT_B}/messages/conv_9/m1`), { text: "other tenant" })
  })
})

after(async () => {
  if (testEnv) await testEnv.cleanup()
})

// The owner's ID token carries tenantId and nothing else. There is no role claim to test, which is
// the point: authorisation lives in the API against tenants/{id}.ownerUid.
const ownerA = () => testEnv.authenticatedContext("uid_a", { tenantId: TENANT_A })
const ownerB = () => testEnv.authenticatedContext("uid_b", { tenantId: TENANT_B })
const widgetA = () =>
  testEnv.authenticatedContext(`widget_${CONVERSATION}`, {
    tenantId: TENANT_A,
    conversationId: CONVERSATION,
    widget: true,
  })

describe("Firestore: deny-all holds for every client", () => {
  const targets = [
    ["tenants", TENANT_A],
    ["plans", "plan_1"],
    ["coupons", "DEMO100"],
    ["system", "bootstrap"],
  ]

  it("an owner cannot read their own tenant document", async () => {
    const db = ownerA().firestore()
    for (const [collectionId, docId] of targets) {
      await assertFails(getDoc(doc(db, collectionId, docId)))
    }
  })

  it("an owner cannot read subcollections of their own tenant", async () => {
    const db = ownerA().firestore()
    await assertFails(getDoc(doc(db, "tenants", TENANT_A, "websites", "web_1")))
    await assertFails(getDocs(collection(db, "tenants", TENANT_A, "websites")))
    await assertFails(getDocs(collection(db, "tenants", TENANT_A, "leads")))
    await assertFails(getDocs(collection(db, "tenants", TENANT_A, "devices")))
  })

  it("an unauthenticated client cannot read anything", async () => {
    const db = testEnv.unauthenticatedContext().firestore()
    for (const [collectionId, docId] of targets) {
      await assertFails(getDoc(doc(db, collectionId, docId)))
    }
  })

  it("no client can write, create, or delete anywhere", async () => {
    for (const context of [ownerA(), widgetA(), testEnv.unauthenticatedContext()]) {
      const db = context.firestore()
      await assertFails(setDoc(doc(db, "tenants", TENANT_A), { name: "hacked" }))
      await assertFails(setDoc(doc(db, "tenants", TENANT_A, "websites", "web_2"), { d: 1 }))
      await assertFails(setDoc(doc(db, "anything", "else"), { x: 1 }))
      await assertFails(deleteDoc(doc(db, "tenants", TENANT_A)))
    }
  })
})

describe("RTDB: chat reads are scoped to one tenant", () => {
  it("an owner reads their own inbox and threads", async () => {
    const db = ownerA().database()
    await assertSucceeds(get(ref(db, `chats/${TENANT_A}/conversations`)))
    await assertSucceeds(get(ref(db, `chats/${TENANT_A}/conversations/${CONVERSATION}`)))
    await assertSucceeds(get(ref(db, `chats/${TENANT_A}/messages/${CONVERSATION}`)))
  })

  it("an owner cannot read another tenant's chats", async () => {
    const db = ownerA().database()
    await assertFails(get(ref(db, `chats/${TENANT_B}/conversations`)))
    await assertFails(get(ref(db, `chats/${TENANT_B}/conversations/conv_9`)))
    await assertFails(get(ref(db, `chats/${TENANT_B}/messages/conv_9`)))
  })

  it("the reverse direction is denied too", async () => {
    const db = ownerB().database()
    await assertFails(get(ref(db, `chats/${TENANT_A}/conversations`)))
    await assertFails(get(ref(db, `chats/${TENANT_A}/messages/${CONVERSATION}`)))
  })

  it("nobody can read above the per-tenant node", async () => {
    const db = ownerA().database()
    await assertFails(get(ref(db, "/")))
    await assertFails(get(ref(db, "chats")))
    await assertFails(get(ref(db, `chats/${TENANT_A}`)))
  })

  it("an unauthenticated client reads nothing", async () => {
    const db = testEnv.unauthenticatedContext().database()
    await assertFails(get(ref(db, `chats/${TENANT_A}/conversations`)))
    await assertFails(get(ref(db, `chats/${TENANT_A}/messages/${CONVERSATION}`)))
  })
})

describe("RTDB: a visitor token sees exactly one conversation", () => {
  it("cannot list the inbox", async () => {
    const db = widgetA().database()
    await assertFails(get(ref(db, `chats/${TENANT_A}/conversations`)))
  })

  it("reads its own thread and messages", async () => {
    const db = widgetA().database()
    await assertSucceeds(get(ref(db, `chats/${TENANT_A}/conversations/${CONVERSATION}`)))
    await assertSucceeds(get(ref(db, `chats/${TENANT_A}/messages/${CONVERSATION}`)))
  })

  it("cannot read another visitor's thread", async () => {
    const db = widgetA().database()
    await assertFails(get(ref(db, `chats/${TENANT_A}/conversations/conv_2`)))
    await assertFails(get(ref(db, `chats/${TENANT_A}/messages/conv_2`)))
  })

  it("cannot reach the same conversationId under another tenant", async () => {
    const db = widgetA().database()
    await assertFails(get(ref(db, `chats/${TENANT_B}/conversations/${CONVERSATION}`)))
  })
})

describe("RTDB: no client write anywhere", () => {
  const paths = [
    "/",
    "chats",
    `chats/${TENANT_A}`,
    `chats/${TENANT_A}/conversations`,
    `chats/${TENANT_A}/conversations/${CONVERSATION}`,
    `chats/${TENANT_A}/conversations/${CONVERSATION}/status`,
    `chats/${TENANT_A}/messages/${CONVERSATION}`,
    `chats/${TENANT_A}/messages/${CONVERSATION}/m2`,
    `chats/${TENANT_B}/conversations/conv_9`,
    "owners/uid_a",
    "owners/uid_a/devices/dev_1",
    "anything/else/entirely",
  ]

  it("set is denied for owners, visitors, and anonymous clients", async () => {
    for (const context of [ownerA(), widgetA(), testEnv.unauthenticatedContext()]) {
      const db = context.database()
      for (const target of paths) {
        await assertFails(set(ref(db, target), { hacked: true }))
      }
    }
  })

  it("update and remove are denied as well", async () => {
    const db = ownerA().database()
    await assertFails(
      update(ref(db, `chats/${TENANT_A}/conversations/${CONVERSATION}`), { status: "closed" }),
    )
    await assertFails(remove(ref(db, `chats/${TENANT_A}/conversations/${CONVERSATION}`)))
  })

  it("a visitor cannot post a message directly (that is what POST /v1/widget/messages is for)", async () => {
    const db = widgetA().database()
    await assertFails(
      set(ref(db, `chats/${TENANT_A}/messages/${CONVERSATION}/injected`), { text: "x" }),
    )
  })
})

describe("Rules carry no role logic", () => {
  it("neither rules file mentions a role claim", () => {
    const firestoreRules = fs.readFileSync(path.join(ROOT, "firestore.rules"), "utf8")
    const databaseRules = fs.readFileSync(path.join(ROOT, "database.rules.json"), "utf8")
    assert.ok(!/token\.role|\brole\b\s*==/.test(firestoreRules), "firestore.rules uses a role")
    assert.ok(!/token\.role/.test(databaseRules), "database.rules.json uses a role")
  })
})
