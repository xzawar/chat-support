/**
 * Idempotent seeds.
 *
 * Every write here is a merge on a known document id, so running this a hundred times leaves the
 * same four plans, the same coupon and the same three templates. That matters because bootstrap
 * calls it, the CLI calls it, and a redeploy may call it again — a seeder that duplicates rows on
 * the second run is a seeder nobody dares to run.
 *
 * Note what is NOT merged: the demo coupon's redeemedCount. Re-seeding must never reset a
 * redemption counter, so that one field is written only on first create.
 */

const { firestore, FieldValue } = require("./firebase")
const {
  FEATURE_CHAT,
  FEATURE_EMAIL,
  FEATURE_SOCIAL,
  DEMO_COUPON_CODE,
  DEMO_COUPON_PERCENT,
  DEMO_COUPON_MAX_REDEMPTIONS,
  DEMO_COUPON_ACTIVE,
} = require("./config")

const PLANS = [
  {
    id: "plan_1",
    name: "Starter",
    tier: 1,
    features: [FEATURE_CHAT],
    priceCents: 0,
    currency: "USD",
    active: true,
    description: "Live chat for one website.",
  },
  {
    id: "plan_2",
    name: "Growth",
    tier: 2,
    features: [FEATURE_CHAT, FEATURE_EMAIL],
    priceCents: 2900,
    currency: "USD",
    active: true,
    description: "Live chat plus lead capture and email automation.",
  },
  {
    id: "plan_3",
    name: "Scale",
    tier: 3,
    features: [FEATURE_CHAT, FEATURE_EMAIL, FEATURE_SOCIAL],
    priceCents: 7900,
    currency: "USD",
    active: true,
    description: "Everything in Growth plus social media inboxes.",
  },
  {
    id: "plan_4",
    name: "Ultimate",
    tier: 4,
    /*
     * The concrete features are listed alongside the wildcard rather than relying on "*" alone.
     * hasFeature() on the client is a membership test over this array, so a plan that carries
     * only the wildcard would fail every specific check and the app would tell an Ultimate
     * customer they are not subscribed to anything. Listing both means the wildcard is what the
     * UI prints ("Everything") while the explicit entries are what the gates actually match, and
     * a feature added later is covered by "*" server-side.
     */
    features: [FEATURE_CHAT, FEATURE_EMAIL, FEATURE_SOCIAL, "*"],
    priceCents: 14900,
    currency: "USD",
    active: true,
    description: "Every channel, plus everything added later, with priority support.",
  },
]

const DEFAULT_TEMPLATES = [
  {
    id: "welcome",
    name: "Welcome",
    subject: "Thanks for getting in touch",
    body:
      "Hi {{name}},\n\nThanks for reaching out on {{website}}. We have your details and someone " +
      "from the team will reply shortly.\n\n— Support",
  },
  {
    id: "follow_up",
    name: "Follow-up",
    subject: "Did we answer your question?",
    body:
      "Hi {{name}},\n\nJust checking in on the conversation you started with us. If anything is " +
      "still unresolved, reply here and we will pick it straight back up.\n\n— Support",
  },
  {
    id: "promo",
    name: "Promo",
    subject: "A little something for you",
    body:
      "Hi {{name}},\n\nBecause you have chatted with us before, here is early access to what we " +
      "are working on next.\n\n— Support",
  },
]

/** plans/{id} — merged, so editing a price in code and re-seeding updates it. */
async function seedPlans() {
  const db = firestore()
  const batch = db.batch()
  PLANS.forEach((plan) => {
    batch.set(
      db.collection("plans").doc(plan.id),
      { ...plan, updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    )
  })
  await batch.commit()
  return PLANS.map((plan) => plan.id)
}

/**
 * coupons/{DEMO_COUPON_CODE} — the demo code, defined in config rather than here.
 *
 * Split into two writes on purpose. The terms (percent, cap, active) are merged every run, so
 * changing DEMO_COUPON_PERCENT in config and re-seeding actually updates the live coupon instead
 * of being ignored because the document already exists. redeemedCount is written only on create,
 * because merging it would reset the counter on every deploy and quietly uncap a capped coupon.
 *
 * With the default config the cap is null, meaning the demo code can be applied repeatedly - which
 * is the whole point of a demo code. Point DEMO_COUPON_CODE at a different string to swap it; the
 * old document is left alone rather than deleted, so anything mid-checkout against it still works.
 */
async function seedCoupons() {
  const db = firestore()
  const ref = db.collection("coupons").doc(DEMO_COUPON_CODE)
  const snap = await ref.get()

  const terms = {
    code: DEMO_COUPON_CODE,
    percentOff: DEMO_COUPON_PERCENT,
    active: DEMO_COUPON_ACTIVE,
    maxRedemptions: DEMO_COUPON_MAX_REDEMPTIONS,
    expiresAt: null,
    updatedAt: FieldValue.serverTimestamp(),
  }

  if (!snap.exists) {
    await ref.set({ ...terms, redeemedCount: 0, createdAt: FieldValue.serverTimestamp() })
    return { created: true, code: DEMO_COUPON_CODE }
  }

  await ref.set(terms, { merge: true })
  return { created: false, code: DEMO_COUPON_CODE }
}

/** tenants/{id}/emailTemplates — the three starters, merged on fixed ids. */
async function seedEmailTemplates(tenantId) {
  const db = firestore()
  const batch = db.batch()
  const collection = db.collection("tenants").doc(tenantId).collection("emailTemplates")
  DEFAULT_TEMPLATES.forEach((template) => {
    batch.set(
      collection.doc(template.id),
      {
        ...template,
        seeded: true,
        updatedAt: FieldValue.serverTimestamp(),
        createdAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    )
  })
  await batch.commit()
  return DEFAULT_TEMPLATES.map((template) => template.id)
}

/** Global catalogue only. Tenant-scoped seeds run at bootstrap, when a tenant exists. */
async function seedCatalog() {
  const plans = await seedPlans()
  const coupon = await seedCoupons()
  return { plans, coupon }
}

module.exports = { seedCatalog, seedPlans, seedCoupons, seedEmailTemplates, PLANS, DEFAULT_TEMPLATES }
