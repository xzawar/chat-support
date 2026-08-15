/**
 * RTDB rules, evaluated against the real database.rules.json.
 *
 * There is no emulator in this environment, so this reads the shipped rules file, walks to the
 * requested path, and evaluates the rule expressions the way RTDB does:
 *
 *   - Access cascades downward. A `.read` granted at an ancestor grants everything beneath it, so
 *     the check collects the nearest defined `.read`/`.write` from the node and its ancestors.
 *   - A rule that is `false`, or absent everywhere on the path, denies.
 *   - $wildcards bind path segments and are substituted into the expression.
 *
 * The expressions in this file are plain boolean JavaScript (===, !==, &&), which is why they can
 * be evaluated directly rather than approximated. If a future rule uses an RTDB-only builtin
 * (root.child(), data.exists(), newData) this evaluator refuses to guess and fails loudly.
 */

const fs = require("fs")
const path = require("path")
const { suite, test, assertEqual, assert } = require("./harness")

const RULES_PATH = path.resolve(__dirname, "../../../database.rules.json")
const rules = JSON.parse(fs.readFileSync(RULES_PATH, "utf8")).rules

const UNSUPPORTED = /\b(root|data|newData|now|query)\b/

/** Collects the rules that apply to a path, outermost first, binding $wildcards as it descends. */
function walk(segments) {
  let node = rules
  const applicable = [{ node, bindings: {} }]
  let bindings = {}

  for (const segment of segments) {
    if (!node || typeof node !== "object") return { applicable, resolvedToLeaf: false }
    let next = null
    if (Object.prototype.hasOwnProperty.call(node, segment)) {
      next = node[segment]
    } else {
      const wildcard = Object.keys(node).find((key) => key.startsWith("$"))
      if (wildcard) {
        next = node[wildcard]
        bindings = { ...bindings, [wildcard]: segment }
      }
    }
    if (next === null || next === undefined) return { applicable, resolvedToLeaf: false }
    node = next
    applicable.push({ node, bindings: { ...bindings } })
  }
  return { applicable, resolvedToLeaf: true }
}

function evaluate(expression, bindings, auth) {
  if (typeof expression === "boolean") return expression
  if (expression === "true") return true
  if (expression === "false") return false
  if (UNSUPPORTED.test(expression)) {
    throw new Error(`Rule uses an RTDB builtin this evaluator cannot check: ${expression}`)
  }
  let source = expression
  for (const [name, value] of Object.entries(bindings)) {
    source = source.split(name).join(JSON.stringify(value))
  }
  // eslint-disable-next-line no-new-func
  const fn = new Function("auth", `"use strict"; return (${source});`)
  return fn(auth) === true
}

/** True when RTDB would allow this operation. */
function allowed(operation, pathString, auth) {
  const segments = pathString.split("/").filter(Boolean)
  const { applicable } = walk(segments)
  const key = operation === "read" ? ".read" : ".write"
  for (const { node, bindings } of applicable) {
    if (node && typeof node === "object" && key in node) {
      if (evaluate(node[key], bindings, auth)) return true
    }
  }
  return false
}

const ownerA = { uid: "uid_a", token: { tenantId: "tnt_a" } }
const ownerB = { uid: "uid_b", token: { tenantId: "tnt_b" } }
const widgetA = {
  uid: "widget_conv_1",
  token: { tenantId: "tnt_a", conversationId: "conv_1", widget: true },
}

const READ_PATHS = [
  "/",
  "chats",
  "chats/tnt_a",
  "chats/tnt_a/conversations",
  "chats/tnt_a/conversations/conv_1",
  "chats/tnt_a/messages/conv_1",
  "chats/tnt_a/messages/conv_1/msg_1",
  "chats/tnt_b/conversations",
  "chats/tnt_b/conversations/conv_9",
  "chats/tnt_b/messages/conv_9",
]

module.exports = async function run() {
  suite("RTDB rules (database.rules.json)")

  await test("unauthenticated client can read nothing", () => {
    for (const target of READ_PATHS) {
      assertEqual(allowed("read", target, null), false, `anonymous read of ${target}`)
    }
  })

  await test("owner reads their own tenant's inbox and threads", () => {
    assertEqual(allowed("read", "chats/tnt_a/conversations", ownerA), true, "inbox list")
    assertEqual(allowed("read", "chats/tnt_a/conversations/conv_1", ownerA), true, "thread row")
    assertEqual(allowed("read", "chats/tnt_a/messages/conv_1", ownerA), true, "messages")
    assertEqual(allowed("read", "chats/tnt_a/messages/conv_1/msg_1", ownerA), true, "one message")
  })

  await test("cross-tenant reads are denied for an owner token", () => {
    assertEqual(allowed("read", "chats/tnt_b/conversations", ownerA), false, "other inbox")
    assertEqual(allowed("read", "chats/tnt_b/conversations/conv_9", ownerA), false, "other thread")
    assertEqual(allowed("read", "chats/tnt_b/messages/conv_9", ownerA), false, "other messages")
    assertEqual(allowed("read", "chats/tnt_a/conversations", ownerB), false, "reverse direction")
  })

  await test("tenant root and the chats root are not readable by anyone", () => {
    for (const auth of [ownerA, ownerB, widgetA]) {
      assertEqual(allowed("read", "chats", auth), false, "chats root")
      assertEqual(allowed("read", "chats/tnt_a", auth), false, "tenant root")
      assertEqual(allowed("read", "/", auth), false, "db root")
    }
  })

  await test("widget token cannot enumerate the inbox", () => {
    assertEqual(allowed("read", "chats/tnt_a/conversations", widgetA), false, "inbox list")
  })

  await test("widget token reads only its own conversation", () => {
    assertEqual(allowed("read", "chats/tnt_a/conversations/conv_1", widgetA), true, "own thread")
    assertEqual(allowed("read", "chats/tnt_a/messages/conv_1", widgetA), true, "own messages")
    assertEqual(allowed("read", "chats/tnt_a/conversations/conv_2", widgetA), false, "other thread")
    assertEqual(allowed("read", "chats/tnt_a/messages/conv_2", widgetA), false, "other messages")
    assertEqual(
      allowed("read", "chats/tnt_b/conversations/conv_1", widgetA),
      false,
      "same conversationId under another tenant",
    )
  })

  await test("no client-side write is allowed anywhere in RTDB", () => {
    const writePaths = [
      ...READ_PATHS,
      "owners/uid_a",
      "owners/uid_a/devices/dev_1",
      "chats/tnt_a/conversations/conv_1/status",
      "chats/tnt_a/conversations/conv_1/assignedAgentUid",
      "chats/tnt_a/messages/conv_1/msg_2",
      "chats/tnt_a/messages/conv_1/msg_2/text",
      "anything/else/entirely",
    ]
    for (const auth of [null, ownerA, ownerB, widgetA]) {
      for (const target of writePaths) {
        assertEqual(
          allowed("write", target, auth),
          false,
          `write to ${target} as ${auth ? auth.uid : "anonymous"}`,
        )
      }
    }
  })

  await test("no rule anywhere in the file grants a write", () => {
    const grants = []
    const visit = (node, at) => {
      if (!node || typeof node !== "object") return
      for (const [key, value] of Object.entries(node)) {
        if (key === ".write" && value !== false && value !== "false") grants.push(`${at}/.write`)
        if (key.startsWith(".") || key === "//") continue
        visit(value, `${at}/${key}`)
      }
    }
    visit(rules, "")
    assertEqual(grants.length, 0, `write grants found at ${grants.join(", ")}`)
  })

  await test("no rule mentions a role claim", () => {
    const source = fs.readFileSync(RULES_PATH, "utf8")
    const expressions = source.match(/"\.(read|write|validate)":\s*"[^"]*"/g) || []
    for (const expression of expressions) {
      assert(!/token\.role/.test(expression), `role claim used in ${expression}`)
    }
  })
}
