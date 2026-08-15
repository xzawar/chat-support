/**
 * BillingGateway — the port, plus the sandbox adapter.
 *
 * No Stripe SDK, no card data, and no PII ever reaches this layer. A gateway is handed an
 * amount, a currency and an opaque reference; it is never handed an email, a name, or anything
 * that could be logged into a payment provider's dashboard by accident.
 *
 * Adding a real provider means writing one more object with the same three methods and adding
 * a case to createGateway(). No route changes.
 */

const { randomId } = require("./crypto")

/**
 * @typedef {Object} CheckoutRequest
 * @property {string} tenantId    opaque to the gateway
 * @property {string} planId
 * @property {number} amountCents amount actually due, after any coupon
 * @property {string} currency
 * @property {string} reference   idempotency handle we generate and store
 *
 * @typedef {Object} CheckoutResult
 * @property {string} paymentId
 * @property {"approved"|"pending"|"declined"} status
 * @property {string|null} redirectUrl  null when no redirect is needed (sandbox)
 */

/**
 * Auto-approves everything. This is what makes the whole billing path testable without a
 * merchant account: checkout returns approved synchronously and the caller runs the exact same
 * activation write a real callback would have run.
 */
const sandboxGateway = {
  name: "sandbox",

  /** @param {CheckoutRequest} request @returns {Promise<CheckoutResult>} */
  async createCheckout(request) {
    return {
      paymentId: randomId("pay_sbx"),
      status: "approved",
      redirectUrl: null,
      amountCents: request.amountCents,
      currency: request.currency,
    }
  },

  /**
   * A real adapter verifies a provider signature here. The sandbox has no signature of its own;
   * /v1/billing/callback still checks the shared BILLING_CALLBACK_SECRET before this is reached,
   * so an unauthenticated caller cannot activate a plan.
   */
  verifyCallback() {
    return true
  },

  /** Normalises a provider payload into the shape the callback route acts on. */
  parseCallback(body) {
    return {
      paymentId: body && body.paymentId ? String(body.paymentId) : null,
      reference: body && body.reference ? String(body.reference) : null,
      status: body && body.status ? String(body.status) : "declined",
    }
  },
}

function createGateway(provider) {
  switch (provider) {
    case "sandbox":
      return sandboxGateway
    default:
      throw new Error(
        `Unknown BILLING_PROVIDER "${provider}". Write an adapter in lib/billingGateway.js.`,
      )
  }
}

module.exports = { createGateway, sandboxGateway }
