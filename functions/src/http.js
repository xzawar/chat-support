/**
 * HTTP plumbing: typed errors, async route wrapping, and the single error handler.
 *
 * Routes throw ApiError and never call res.status(...).json(...) for failures themselves, so
 * every error the API emits has the same shape: { error: { code, message } }. The Android app
 * shows `message` verbatim, which is why messages are written for a human to read.
 */

class ApiError extends Error {
  constructor(status, code, message, details) {
    super(message)
    this.status = status
    this.code = code
    this.details = details || null
  }
}

const badRequest = (message, code) => new ApiError(400, code || "bad_request", message)
const unauthorized = (message, code) => new ApiError(401, code || "unauthenticated", message)
const paymentRequired = (message, code) =>
  new ApiError(402, code || "subscription_inactive", message)
const forbidden = (message, code) => new ApiError(403, code || "forbidden", message)
const notFound = (message, code) => new ApiError(404, code || "not_found", message)
const conflict = (message, code) => new ApiError(409, code || "conflict", message)

/** Wraps an async handler so a rejected promise reaches the error handler instead of hanging. */
function asyncHandler(handler) {
  return (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next)
}

/* eslint-disable no-unused-vars */
function errorHandler(err, req, res, next) {
  const status = err instanceof ApiError ? err.status : 500
  const code = err instanceof ApiError ? err.code : "internal"
  const message =
    err instanceof ApiError ? err.message : "Something went wrong. Please try again."

  // 5xx is a bug in this code; log it with the stack. 4xx is the caller's problem and logging
  // the stack for every wrong coupon code would bury the real failures.
  if (status >= 500) {
    console.error(`${req.method} ${req.originalUrl} -> 500`, err)
  } else {
    console.log(`${req.method} ${req.originalUrl} -> ${status} ${code}`)
  }

  const body = { error: { code, message } }
  if (err instanceof ApiError && err.details) body.error.details = err.details
  res.status(status).json(body)
}
/* eslint-enable no-unused-vars */

/** Reads a required string field off the body and trims it. */
function requiredString(body, field, maxLength) {
  const value = body && body[field]
  if (typeof value !== "string" || value.trim().length === 0) {
    throw badRequest(`${field} is required.`, "missing_field")
  }
  const trimmed = value.trim()
  if (maxLength && trimmed.length > maxLength) {
    throw badRequest(`${field} must be ${maxLength} characters or fewer.`, "field_too_long")
  }
  return trimmed
}

/** Same, but absent is allowed and returns null. */
function optionalString(body, field, maxLength) {
  const value = body && body[field]
  if (value === undefined || value === null || value === "") return null
  return requiredString(body, field, maxLength)
}

module.exports = {
  ApiError,
  asyncHandler,
  errorHandler,
  badRequest,
  unauthorized,
  paymentRequired,
  forbidden,
  notFound,
  conflict,
  requiredString,
  optionalString,
}
