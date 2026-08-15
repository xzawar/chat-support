/**
 * Tiny test harness: a require hook that swaps in the fakes, plus assertion helpers.
 *
 * The hook is what lets the real route modules load without a Firebase project, a network, or an
 * emulator. Only three module ids are intercepted; everything else (crypto, the src/ tree itself)
 * resolves normally, so what is under test is the shipped code.
 */

const Module = require("module")
const path = require("path")
const { express, createAdmin, createJwt } = require("./fakes")

const state = { admin: null, jwt: null }

function install() {
  if (install.done) return
  const originalLoad = Module._load
  Module._load = function patched(request, parent, isMain) {
    if (request === "express") return express
    if (request === "firebase-admin") return state.admin
    if (request === "jsonwebtoken") return state.jwt
    return originalLoad.call(this, request, parent, isMain)
  }
  install.done = true
}

/**
 * Fresh fakes and a fresh module registry for every test file, so state cannot leak between
 * cases through the src/firebase.js singleton.
 */
function reset() {
  state.admin = createAdmin()
  state.jwt = createJwt()
  install()

  const root = path.resolve(__dirname, "../..")
  for (const key of Object.keys(require.cache)) {
    if (key.startsWith(root) && !key.includes(`${path.sep}test${path.sep}`)) {
      delete require.cache[key]
    }
  }

  process.env.WIDGET_JWT_SECRET = "test-widget-secret-value-0123456789"
  process.env.BILLING_CALLBACK_SECRET = "test-billing-secret-value-0123456789"
  delete process.env.BOOTSTRAP_ADMIN

  return state.admin
}

/** Builds an app with the given routers mounted, wired to the real error handler. */
function buildApp(mounts) {
  const { errorHandler } = require("../../src/http")
  const app = express()
  for (const [mountPath, router] of mounts) app.use(mountPath, router)
  app.use(errorHandler)
  return app
}

// --------------------------------------------------------------------------- assertions

const results = { passed: 0, failed: 0, failures: [] }
let currentSuite = ""

function suite(name) {
  currentSuite = name
  console.log(`\n${name}`)
}

async function test(name, fn) {
  try {
    await fn()
    results.passed += 1
    console.log(`  PASS  ${name}`)
  } catch (error) {
    results.failed += 1
    results.failures.push(`${currentSuite} > ${name}: ${error.message}`)
    console.log(`  FAIL  ${name}`)
    console.log(`        ${error.message}`)
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message || "assertion failed")
}

function assertEqual(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(`${message || "values differ"}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`)
  }
}

function summary() {
  console.log(`\n${results.passed} passed, ${results.failed} failed`)
  if (results.failed > 0) {
    console.log("\nFailures:")
    for (const failure of results.failures) console.log(`  - ${failure}`)
  }
  return results.failed === 0
}

module.exports = { reset, buildApp, suite, test, assert, assertEqual, summary, results }
