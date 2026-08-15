/**
 * Widget authentication.
 *
 * The widget never holds a Firebase user. It holds a widgetToken: a short-lived JWT this backend
 * signed at handshake, carrying tenantId, websiteId and conversationId. Those three values are
 * read from the signed token and never from the request body — otherwise any visitor could post
 * into any tenant's conversation by editing one field.
 */

const jwt = require("jsonwebtoken")
const { widgetJwtSecret, WIDGET_TOKEN_TTL_SECONDS } = require("../config")
const { unauthorized } = require("../http")

/**
 * Signs the token the widget presents on every later call. The conversationId claim is what the
 * RTDB rules match against, so one visitor's token can only ever read one thread.
 */
function signWidgetToken({ tenantId, websiteId, conversationId }) {
  return jwt.sign(
    { tenantId, websiteId, conversationId, scope: "widget" },
    widgetJwtSecret(),
    { expiresIn: WIDGET_TOKEN_TTL_SECONDS, subject: conversationId },
  )
}

function verifyWidgetToken(token) {
  return jwt.verify(token, widgetJwtSecret())
}

function bearerToken(req) {
  const header = req.get("Authorization") || req.get("authorization") || ""
  if (header.startsWith("Bearer ")) {
    const token = header.slice("Bearer ".length).trim()
    if (token.length > 0) return token
  }
  return null
}

/** Express guard for widget-only routes. */
function requireWidget(req, res, next) {
  const token = bearerToken(req)
  if (!token) {
    return next(unauthorized("Chat session missing. Reload the page.", "missing_widget_token"))
  }

  let claims
  try {
    claims = verifyWidgetToken(token)
  } catch (err) {
    return next(
      unauthorized("Chat session expired. Reload the page to continue.", "widget_token_invalid"),
    )
  }

  if (claims.scope !== "widget" || !claims.tenantId || !claims.conversationId) {
    return next(unauthorized("Chat session is not valid.", "widget_token_invalid"))
  }

  req.widget = {
    tenantId: claims.tenantId,
    websiteId: claims.websiteId || null,
    conversationId: claims.conversationId,
  }
  next()
}

module.exports = { signWidgetToken, verifyWidgetToken, requireWidget }
