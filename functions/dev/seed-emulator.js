#!/usr/bin/env node
/**
 * One command to make a fresh emulator usable: node dev/seed-emulator.js
 *
 * The emulator starts completely empty, and the normal way in (sign up, POST /v1/bootstrap,
 * refresh the token, register a website) is four manual steps before you can look at anything.
 * This does all of it directly through the Admin SDK, which the emulator accepts without any
 * credentials at all.
 *
 * It refuses to run unless the emulator host variables are set. That guard is the whole reason
 * this file is safe to keep in the repo: without it, one stray shell would seed a demo owner and a
 * throwaway API key into the real project.
 *
 * Safe to re-run. Every write is a merge on a fixed id, so you get the same tenant, the same
 * website and the same API key each time rather than a pile of duplicates.
 */

const REQUIRED_HOSTS = [
  "FIREBASE_AUTH_EMULATOR_HOST",
  "FIRESTORE_EMULATOR_HOST",
  "FIREBASE_DATABASE_EMULATOR_HOST",
]

// --emulators=<host> fills the host variables in for you, which is what npm run seed:emulator
// passes. Being explicit on the command line is the same statement of intent as exporting them,
// and it saves every reader a shell-syntax detour on Windows.
const hostFlag = process.argv.find((arg) => arg.startsWith("--emulators="))
if (hostFlag) {
  const emulatorHost = hostFlag.split("=")[1] || "localhost"
  process.env.FIREBASE_AUTH_EMULATOR_HOST ||= `${emulatorHost}:9099`
  process.env.FIRESTORE_EMULATOR_HOST ||= `${emulatorHost}:8080`
  process.env.FIREBASE_DATABASE_EMULATOR_HOST ||= `${emulatorHost}:9000`
}

const missing = REQUIRED_HOSTS.filter((name) => !process.env[name])
if (missing.length > 0) {
  console.error("Refusing to run: this script only ever talks to emulators.")
  console.error(`Missing: ${missing.join(", ")}`)
  console.error("")
  console.error("Run it through the npm script, which sets them for you:")
  console.error("  npm run seed:emulator")
  process.exit(1)
}

process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "chat-support-1"

const { admin, firestore, rtdb, auth, FieldValue } = require("../src/firebase")
const { seedCatalog } = require("../src/seed")
const { sha256, randomId } = require("../src/lib/crypto")
const { RETENTION_MILLIS, FEATURE_CHAT, FEATURE_EMAIL } = require("../src/config")

// Fixed values so the app, the widget and this script all agree without copy-paste.
const OWNER_EMAIL = "owner@example.test"
const OWNER_PASSWORD = "password123"
const TENANT_ID = "tnt_dev"
const WEBSITE_ID = "web_dev"
// localhost so a local WordPress install passes the handshake's exact-domain check. Change this if
// you serve the widget from somewhere else; domainMatches accepts the bare host and its www. form.
const domainFlag = process.argv.find((arg) => arg.startsWith("--domain="))
const WEBSITE_DOMAIN = (domainFlag && domainFlag.split("=")[1]) || "localhost"
// Deterministic, and clearly not a real key. The production generator produces sk_live_<48 hex>.
const API_KEY = "sk_test_dev_0000000000000000000000000000000000000000000000"

async function ensureOwner() {
  let user
  try {
    user = await auth().getUserByEmail(OWNER_EMAIL)
  } catch (notFound) {
    user = await auth().createUser({
      email: OWNER_EMAIL,
      password: OWNER_PASSWORD,
      emailVerified: true,
      displayName: "Dev Owner",
    })
  }

  // tenantId is the only custom claim there is. No role: ownership is tenants/{id}.ownerUid, read
  // per request, which is why demoting somebody never depends on a token expiring.
  await auth().setCustomUserClaims(user.uid, { tenantId: TENANT_ID })
  return user
}

async function ensureTenant(ownerUid) {
  await firestore()
    .collection("tenants")
    .doc(TENANT_ID)
    .set(
      {
        name: "Dev Workspace",
        ownerUid,
        ownerEmail: OWNER_EMAIL,
        plan: "plan_3",
        // Exact names only. A blank entry here would unlock nothing, by design.
        features: [FEATURE_CHAT, FEATURE_EMAIL],
        status: "active",
        currentPeriodEnd: Date.now() + 30 * 24 * 60 * 60 * 1000,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    )
}

async function ensureWebsite() {
  await firestore()
    .collection("tenants")
    .doc(TENANT_ID)
    .collection("websites")
    .doc(WEBSITE_ID)
    .set(
      {
        domain: WEBSITE_DOMAIN,
        tenantId: TENANT_ID,
        // Only the hash is ever stored, here as in production.
        apiKeyHash: sha256(API_KEY),
        active: true,
        createdAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    )
}

/** One open conversation with two messages, so the inbox has something in it on first launch. */
async function ensureSampleChat() {
  const now = Date.now()
  const conversationId = "conv_dev_sample"

  const updates = {}
  updates[`chats/${TENANT_ID}/conversations/${conversationId}`] = {
    id: conversationId,
    tenantId: TENANT_ID,
    websiteId: WEBSITE_ID,
    websiteDomain: WEBSITE_DOMAIN,
    status: "open",
    createdAt: now - 60 * 1000,
    startedAt: now - 30 * 1000,
    expiresAt: now + RETENTION_MILLIS,
    unread: 1,
    visitor: { name: "Sample Visitor", email: "visitor@example.test" },
    lastMessage: { text: "Can you explain the starter plan?", at: now, sender: "visitor" },
  }
  updates[`chats/${TENANT_ID}/messages/${conversationId}/msg_dev_1`] = {
    id: "msg_dev_1",
    sender: "system",
    text: "Chat started.",
    createdAt: now - 60 * 1000,
  }
  // "visitor" and "agent" here are message authorship on the wire, read by the widget and by the
  // app's bubble alignment. Not roles.
  updates[`chats/${TENANT_ID}/messages/${conversationId}/msg_dev_2`] = {
    id: "msg_dev_2",
    sender: "visitor",
    text: "Can you explain the starter plan?",
    createdAt: now,
  }

  await rtdb().ref().update(updates)
  return conversationId
}

async function main() {
  console.log(`Seeding emulators for project ${process.env.GCLOUD_PROJECT}`)

  const owner = await ensureOwner()
  await ensureTenant(owner.uid)
  await ensureWebsite()
  const catalog = await seedCatalog()
  const conversationId = await ensureSampleChat()

  // The bootstrap lock, written so POST /v1/bootstrap behaves as it would on a real project that
  // has already been initialised, instead of trying to claim this tenant again.
  await firestore()
    .collection("system")
    .doc("bootstrap")
    .set(
      {
        tenantId: TENANT_ID,
        ownerUid: owner.uid,
        ownerEmail: OWNER_EMAIL,
        initializedAt: FieldValue.serverTimestamp(),
        locked: true,
      },
      { merge: true },
    )

  console.log("")
  console.log("Done. Sign in to the app with:")
  console.log(`  email     ${OWNER_EMAIL}`)
  console.log(`  password  ${OWNER_PASSWORD}`)
  console.log("")
  console.log("Widget settings for a local WordPress install:")
  console.log(`  API key   ${API_KEY}`)
  console.log(`  domain    ${WEBSITE_DOMAIN}  (the handshake checks Origin against this)`)
  console.log("")
  console.log(`Tenant ${TENANT_ID}, website ${WEBSITE_ID}, sample chat ${conversationId}`)
  console.log(`Plans seeded: ${catalog.plans.join(", ")}`)
  console.log("")
  console.log("Emulator UI: http://localhost:4000")
}

main()
  .then(() => admin.app().delete())
  .then(() => process.exit(0))
  .catch((error) => {
    console.error("Seed failed:", error)
    process.exit(1)
  })
