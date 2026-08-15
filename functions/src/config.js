/**
 * Every tunable the backend has, in one place.
 *
 * Nothing here reads request input. Region and database instance must match the Firebase
 * project the app already points at (asia-southeast1), or triggers silently never fire.
 */

const REGION = "asia-southeast1"

/**
 * The Firebase project, stated outright.
 *
 * Inside Cloud Functions this is injected by the runtime, so nothing ever needed to name it. On a
 * laptop nothing injects it, and the Admin SDK's "Unable to detect a Project Id in the current
 * environment" is what that absence looks like. Hard-coding it is correct here rather than lazy:
 * this repo serves exactly one project, and DATABASE_INSTANCE below is already derived from the
 * same name.
 */
const PROJECT_ID = process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT || "chat-support-1"

const DATABASE_INSTANCE = "chat-support-1-default-rtdb"
const DATABASE_URL = "https://" + DATABASE_INSTANCE + ".asia-southeast1.firebasedatabase.app"

/**
 * The database URL handed to the browser widget, which is not always the one this process uses.
 *
 * Behind a tunnel to a local emulator, the widget cannot reach 127.0.0.1:9000 - it needs the public
 * tunnel URL, and the RTDB emulator additionally requires the namespace as a query parameter:
 *   PUBLIC_DATABASE_URL=https://rtdb-xyz.trycloudflare.com/?ns=chat-support-1-default-rtdb
 * Unset in production, where the real database URL is already public.
 */
const publicDatabaseUrl = () => process.env.PUBLIC_DATABASE_URL || DATABASE_URL

/**
 * Where the browser should reach the Auth emulator, when one is in play.
 *
 * The widget signs in with a custom token. A token minted by the Auth emulator is unsigned, so real
 * Google Auth rejects it outright - the browser has to be pointed at the same emulator that minted
 * it. Returning this in the handshake keeps that decision on the server: the widget never guesses.
 * Unset in production, and it must stay unset there, or visitors would authenticate nowhere.
 */
const widgetAuthEmulatorUrl = () => process.env.PUBLIC_AUTH_EMULATOR_URL || null

/** How long a conversation lives in RTDB after its last message. Matches the Android app. */
const RETENTION_MILLIS = 24 * 60 * 60 * 1000

/** Conversations purged per scheduled run, so one run cannot blow the function's memory. */
const PURGE_BATCH = 400

/** past_due tenants keep working this long past currentPeriodEnd before the gate closes. */
const GRACE_MILLIS = 7 * 24 * 60 * 60 * 1000

/** A paid period is 30 days. Coupon activation and sandbox checkout both use this. */
const PERIOD_MILLIS = 30 * 24 * 60 * 60 * 1000

/** Widget tokens are short-lived on purpose; the widget re-handshakes when one expires. */
const WIDGET_TOKEN_TTL_SECONDS = 24 * 60 * 60

const FEATURE_CHAT = "chat"
const FEATURE_EMAIL = "email_automation"
const FEATURE_SOCIAL = "social_media"

/**
 * Secrets come from the environment. There is no in-repo default: a fallback secret that works
 * is a fallback secret that ships to production, so an unset value fails loudly at first use
 * rather than quietly signing forgeable tokens.
 */
function requireSecret(name) {
  const value = process.env[name]
  if (!value || value.length < 16) {
    throw new Error(
      `${name} is not set (or is shorter than 16 chars). Set it with: firebase functions:secrets:set ${name}`,
    )
  }
  return value
}

/** Signs and verifies widgetToken. */
const widgetJwtSecret = () => requireSecret("WIDGET_JWT_SECRET")

/** Shared secret the payment gateway presents on /v1/billing/callback. */
const billingCallbackSecret = () => requireSecret("BILLING_CALLBACK_SECRET")

/**
 * Which BillingGateway adapter to construct. "sandbox" auto-approves; a real provider is added
 * by writing one more adapter, not by touching any route.
 */
const billingProvider = () => process.env.BILLING_PROVIDER || "sandbox"

/*
 * The demo coupon, in one place.
 *
 * It used to be a literal "DEMO100" written into the seeder, which meant changing it was a code
 * edit in every place that mentioned it. It lives here now, and every one of these is overridable
 * by environment variable, so swapping the code or the discount for a different demo is a config
 * change and a re-seed rather than a patch.
 *
 * DEMO_COUPON_MAX_REDEMPTIONS is null by default on purpose: the point of a demo code is that it
 * can be applied over and over while testing. Set it to a number to cap it.
 */
const DEMO_COUPON_CODE = (process.env.DEMO_COUPON_CODE || "DEMO100").toUpperCase()
const DEMO_COUPON_PERCENT = Number(process.env.DEMO_COUPON_PERCENT || 100)
const DEMO_COUPON_MAX_REDEMPTIONS = process.env.DEMO_COUPON_MAX_REDEMPTIONS
  ? Number(process.env.DEMO_COUPON_MAX_REDEMPTIONS)
  : null
const DEMO_COUPON_ACTIVE = process.env.DEMO_COUPON_ACTIVE !== "false"

/**
 * The uid or email allowed to run the one-time bootstrap. Optional: when unset, the first
 * authenticated caller wins, which is fine for a single-installation deployment because the
 * bootstrap doc locks immediately afterwards.
 */
const bootstrapAdmin = () => process.env.BOOTSTRAP_ADMIN || null

module.exports = {
  REGION,
  PROJECT_ID,
  DATABASE_INSTANCE,
  DATABASE_URL,
  publicDatabaseUrl,
  widgetAuthEmulatorUrl,
  RETENTION_MILLIS,
  PURGE_BATCH,
  GRACE_MILLIS,
  PERIOD_MILLIS,
  WIDGET_TOKEN_TTL_SECONDS,
  FEATURE_CHAT,
  FEATURE_EMAIL,
  FEATURE_SOCIAL,
  DEMO_COUPON_CODE,
  DEMO_COUPON_PERCENT,
  DEMO_COUPON_MAX_REDEMPTIONS,
  DEMO_COUPON_ACTIVE,
  widgetJwtSecret,
  billingCallbackSecret,
  billingProvider,
  bootstrapAdmin,
}
