/**
 * Support Chat backend — the only writer of data in the entire system.
 *
 * Architecture, fixed:
 *   - RTDB holds chat only: chats/{tenantId}/conversations/{id} and
 *     chats/{tenantId}/messages/{conversationId}/{clientMessageId}.
 *   - Firestore holds business data only: tenants, websites, agents, usage, leads, plans,
 *     coupons, emailTemplates. Firestore rules deny all client access.
 *   - Clients never write any database. The app and the widget only LISTEN to RTDB, scoped by
 *     token claims, and call /v1/ routes for everything else.
 *   - tenantId always comes from verified custom claims, never from request input. The single
 *     exception is POST /v1/bootstrap, which exists to create the first claim and then locks.
 *
 * Deploying needs the Blaze plan; Cloud Functions cannot be deployed on Spark, and the pubsub
 * schedule additionally enables Cloud Scheduler.
 *
 *   npm install --prefix functions
 *   firebase functions:secrets:set WIDGET_JWT_SECRET
 *   firebase functions:secrets:set BILLING_CALLBACK_SECRET
 *   firebase deploy --only functions,database,firestore
 */

const functions = require("firebase-functions/v1")
const express = require("express")
const cors = require("cors")

const { REGION, DATABASE_INSTANCE } = require("./src/config")
const { errorHandler, notFound } = require("./src/http")
const { purgeExpiredConversations } = require("./src/jobs/retention")
const { collectTokens, pushToDevices, loadConversation } = require("./src/notifications")

const bootstrapRoutes = require("./src/routes/bootstrap")
const tenantRoutes = require("./src/routes/tenants")
const websiteRoutes = require("./src/routes/websites")
const billingRoutes = require("./src/routes/billing")
const widgetRoutes = require("./src/routes/widget")
const messageRoutes = require("./src/routes/messages")
const leadRoutes = require("./src/routes/leads")
const deviceRoutes = require("./src/routes/devices")
const emailRoutes = require("./src/routes/email")
const adminRoutes = require("./src/routes/admin")

// ---------------------------------------------------------------------------
// HTTP API
// ---------------------------------------------------------------------------

const app = express()

// The widget is embedded on customer domains, so cross-origin is the normal case. The handshake
// enforces the per-tenant domain allowlist itself; CORS here is a browser convenience, not the
// security boundary, and treating it as one would be a mistake.
app.use(cors({ origin: true }))
app.use(express.json({ limit: "256kb" }))
app.disable("x-powered-by")

app.get("/v1/health", (req, res) => res.json({ ok: true, ts: Date.now() }))

app.use("/v1/bootstrap", bootstrapRoutes)
app.use("/v1/tenants", tenantRoutes)
app.use("/v1/websites", websiteRoutes)
app.use("/v1/billing", billingRoutes)
app.use("/v1/widget", widgetRoutes)
app.use("/v1/conversations", messageRoutes)
app.use("/v1/leads", leadRoutes)
app.use("/v1/devices", deviceRoutes)
// Owner-triggered maintenance. /v1/admin/purge runs the retention sweep on demand, scoped to the
// caller's own tenant, so "did the sweep actually run?" is an answerable question.
app.use("/v1/admin", adminRoutes)
// email-stats and email-templates are siblings under /v1, so the router mounts at the root.
app.use("/v1", emailRoutes)

app.use((req, res, next) => next(notFound(`No route for ${req.method} ${req.path}`, "no_route")))
app.use(errorHandler)

exports.api = functions.region(REGION).https.onRequest(app)

// ---------------------------------------------------------------------------
// Scheduled retention
// ---------------------------------------------------------------------------

exports.purgeExpiredConversations = functions
  .region(REGION)
  .pubsub.schedule("every 15 minutes")
  .onRun(async () => {
    await purgeExpiredConversations(Date.now())
    return null
  })

// ---------------------------------------------------------------------------
// Push notifications
// ---------------------------------------------------------------------------

/**
 * The request is what alerts, not the message.
 *
 * Guarding on before !== pending && after === pending means a status corrected back and forth
 * does not re-alert, and an update to any other field never triggers this at all.
 */
exports.notifyOwnerOnSupportRequest = functions
  .region(REGION)
  .database.instance(DATABASE_INSTANCE)
  .ref("/chats/{tenantId}/conversations/{conversationId}/status")
  .onWrite(async (change, context) => {
    const before = change.before.val()
    const after = change.after.val()
    if (after !== "pending" || before === "pending") return null

    const { tenantId, conversationId } = context.params
    const conversation = await loadConversation(tenantId, conversationId)
    const visitor = conversation.visitor || {}
    const devices = await collectTokens(tenantId)

    return pushToDevices(tenantId, devices, {
      conversationId,
      senderName: visitor.name || "New visitor",
      text: "Requested support. Open the chat to connect them.",
      kind: "request",
    })
  })

/**
 * Ongoing messages, once a chat is actually running. A pending conversation stays silent after
 * its one request alert no matter how much the visitor types, and a closed one wakes nobody.
 */
exports.notifyOwnerOnVisitorMessage = functions
  .region(REGION)
  .database.instance(DATABASE_INSTANCE)
  .ref("/chats/{tenantId}/messages/{conversationId}/{messageId}")
  .onCreate(async (snapshot, context) => {
    const message = snapshot.val() || {}
    const { tenantId, conversationId } = context.params

    // The owner is not pushed their own replies, and the Start Chat greeting is a system message.
    // "agent" here is message authorship on the wire (visitor / agent / system), read by the
    // already-deployed widget and by the app's bubble alignment. It is not a role.
    if (message.sender === "agent" || message.sender === "system") return null

    const conversation = await loadConversation(tenantId, conversationId)
    if (conversation.status !== "open") {
      console.log(
        `Suppressed push for ${conversationId}: status is ${conversation.status || "unset"}`,
      )
      return null
    }

    const visitor = conversation.visitor || {}
    const devices = await collectTokens(tenantId)

    return pushToDevices(tenantId, devices, {
      conversationId,
      senderName: visitor.name || "Visitor",
      text: message.text || "",
      kind: "message",
    })
  })

// Exported for the seed CLI and for tests.
exports.__app = app
