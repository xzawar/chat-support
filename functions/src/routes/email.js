/**
 * Email data: stats and templates. Both gated on email_automation, owner only.
 *
 * Nothing here sends mail. The stats are honest about that: totalRegistered is a real count of
 * leads, and the other three are zero because no message has ever been dispatched. They are
 * computed server-side rather than hardcoded in the app so that when a sender does exist, the
 * screen starts reporting real numbers without an app release.
 */

const express = require("express")
const { firestore, FieldValue } = require("../firebase")
const { asyncHandler, requiredString, optionalString, notFound } = require("../http")
const {
  requireAuth,
  requireTenant,
  attachTenant,
  requireOwner,
  requireFeature,
} = require("../middleware/auth")
const { FEATURE_EMAIL } = require("../config")
const { toMillis } = require("../lib/entitlements")

const router = express.Router()

router.use(requireAuth, requireTenant, attachTenant, requireOwner, requireFeature(FEATURE_EMAIL))

const tenantRef = (tenantId) => firestore().collection("tenants").doc(tenantId)
const templatesRef = (tenantId) => tenantRef(tenantId).collection("emailTemplates")

/**
 * GET /v1/email-stats
 *
 * count() is an aggregation query: it does not read the documents, so this stays a single cheap
 * call whether the tenant has 12 leads or 120,000.
 */
router.get(
  "/email-stats",
  asyncHandler(async (req, res) => {
    const tenantId = req.auth.tenantId
    const countSnap = await tenantRef(tenantId).collection("leads").count().get()

    // Read from usage/ when a sender eventually writes there; absent means zero, not unknown.
    const usageSnap = await tenantRef(tenantId).collection("usage").doc("email").get()
    const usage = usageSnap.exists ? usageSnap.data() : {}

    res.json({
      totalRegistered: countSnap.data().count,
      emailsSent: usage.emailsSent || 0,
      emailsFailed: usage.emailsFailed || 0,
      emailsClicked: usage.emailsClicked || 0,
    })
  }),
)

function serializeTemplate(doc) {
  const data = doc.data()
  return {
    id: doc.id,
    name: data.name || "",
    subject: data.subject || "",
    body: data.body || "",
    seeded: data.seeded === true,
    updatedAt: toMillis(data.updatedAt),
  }
}

router.get(
  "/email-templates",
  asyncHandler(async (req, res) => {
    const snap = await templatesRef(req.auth.tenantId).orderBy("name").get()
    res.json({ templates: snap.docs.map(serializeTemplate) })
  }),
)

router.post(
  "/email-templates",
  asyncHandler(async (req, res) => {
    const name = requiredString(req.body, "name", 120)
    const subject = requiredString(req.body, "subject", 200)
    const body = requiredString(req.body, "body", 20000)

    const ref = await templatesRef(req.auth.tenantId).add({
      name,
      subject,
      body,
      seeded: false,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    })

    const snap = await ref.get()
    res.status(201).json(serializeTemplate(snap))
  }),
)

router.patch(
  "/email-templates/:id",
  asyncHandler(async (req, res) => {
    const ref = templatesRef(req.auth.tenantId).doc(req.params.id)
    const snap = await ref.get()
    if (!snap.exists) throw notFound("Template not found.", "template_not_found")

    const updates = { updatedAt: FieldValue.serverTimestamp() }
    const name = optionalString(req.body, "name", 120)
    const subject = optionalString(req.body, "subject", 200)
    const body = optionalString(req.body, "body", 20000)
    if (name) updates.name = name
    if (subject) updates.subject = subject
    if (body) updates.body = body

    await ref.update(updates)
    res.json(serializeTemplate(await ref.get()))
  }),
)

router.delete(
  "/email-templates/:id",
  asyncHandler(async (req, res) => {
    const ref = templatesRef(req.auth.tenantId).doc(req.params.id)
    const snap = await ref.get()
    if (!snap.exists) throw notFound("Template not found.", "template_not_found")
    await ref.delete()
    res.json({ ok: true, id: req.params.id, deleted: true })
  }),
)

module.exports = router
