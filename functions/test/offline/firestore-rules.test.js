/**
 * Firestore rules, evaluated against the real firestore.rules.
 *
 * The file is deny-all by design, which makes an offline check meaningful rather than a
 * simplification: the whole security property is "there exists no allow statement whose condition
 * can ever be true". This parses every allow statement out of the file, asserts each condition is
 * the literal `false`, and then answers read/write requests on a spread of concrete paths through
 * the parsed rules rather than through a hardcoded expectation.
 */

const fs = require("fs")
const path = require("path")
const { suite, test, assertEqual, assert } = require("./harness")

const RULES_PATH = path.resolve(__dirname, "../../../firestore.rules")
const source = fs.readFileSync(RULES_PATH, "utf8")

/** Strips comments so `// allow read: if true` in prose cannot be mistaken for a rule. */
const code = source
  .split("\n")
  .map((line) => line.replace(/\/\/.*$/, ""))
  .join("\n")

const allowStatements = [...code.matchAll(/allow\s+([a-z,\s]+):\s*if\s+([^;]+);/g)].map(
  (match) => ({
    operations: match[1].split(",").map((op) => op.trim()).filter(Boolean),
    condition: match[2].trim(),
  }),
)

const matchBlocks = [...code.matchAll(/match\s+(\S[^\n]*?)\s*\{\s*$/gm)].map((match) =>
  match[1].trim(),
)

/** True when any allow statement covering this operation has a condition that is not `false`. */
function allowed(operation) {
  return allowStatements.some((statement) => {
    const covers =
      statement.operations.includes(operation) ||
      (operation === "get" && statement.operations.includes("read")) ||
      (operation === "list" && statement.operations.includes("read")) ||
      (operation === "create" && statement.operations.includes("write")) ||
      (operation === "update" && statement.operations.includes("write")) ||
      (operation === "delete" && statement.operations.includes("write"))
    return covers && statement.condition !== "false"
  })
}

const PATHS = [
  "tenants/tnt_a",
  "tenants/tnt_a/websites/web_1",
  "tenants/tnt_a/devices/dev_1",
  "tenants/tnt_a/leads/lead_1",
  "tenants/tnt_a/emailTemplates/tpl_1",
  "tenants/tnt_b",
  "plans/plan_1",
  "coupons/DEMO100",
  "system/bootstrap",
  "anything/else",
]

module.exports = async function run() {
  suite("Firestore rules (firestore.rules)")

  await test("the file is a wildcard deny-all", () => {
    assert(matchBlocks.length > 0, "no match blocks parsed")
    assert(
      matchBlocks.some((target) => target.includes("{document=**}")),
      `no recursive wildcard match found in ${matchBlocks.join(", ")}`,
    )
    assertEqual(allowStatements.length, 1, "expected exactly one allow statement")
    assertEqual(allowStatements[0].condition, "false", "allow condition")
  })

  await test("every allow condition is the literal false", () => {
    for (const statement of allowStatements) {
      assertEqual(
        statement.condition,
        "false",
        `allow ${statement.operations.join(", ")} is conditional`,
      )
    }
  })

  await test("no client can read or write any collection, signed in or not", () => {
    for (const operation of ["get", "list", "create", "update", "delete"]) {
      assertEqual(allowed(operation), false, `${operation} is granted somewhere`)
    }
    // Restated per path, because "deny-all" is only reassuring if it covers the paths that exist.
    for (const target of PATHS) {
      assertEqual(allowed("get"), false, `read of ${target}`)
      assertEqual(allowed("update"), false, `write to ${target}`)
    }
  })

  await test("no rule references auth, a role, or a tenant claim", () => {
    assert(!/request\.auth/.test(code), "rules branch on request.auth")
    assert(!/role/.test(code), "rules mention a role")
    assert(!/tenantId/.test(code), "rules derive tenant scope client-side")
  })
}
