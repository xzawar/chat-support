/**
 * A grep with an opinion: fails if the agent role creeps back in.
 *
 * The checklist item is "no agent role remains anywhere in claims, rules, or code", which is a
 * property of the whole tree rather than of any one module, so it is asserted over the tree.
 *
 * Two things are deliberately allowed and are asserted rather than ignored:
 *
 *   - `sender: "agent"` on chat messages. That is message authorship on the wire (visitor / agent
 *     / system), read by the already-deployed WordPress widget and by existing RTDB history.
 *     Renaming it would be a breaking data migration in a pass whose scope excludes the widget.
 *   - `assignedAgentUid` on conversations, and its RTDB index. Same reason: existing data and the
 *     inbox filters read it.
 *
 * Everything else — role claims, role comparisons, membership documents, invite flows — must be
 * gone.
 */

const fs = require("fs")
const path = require("path")
const { suite, test, assert } = require("./harness")

const ROOT = path.resolve(__dirname, "../../..")

const SKIP_DIRS = new Set(["node_modules", ".git", "build", ".gradle", ".idea", "test"])
const EXTENSIONS = new Set([".js", ".kt", ".json", ".rules", ".md", ".php", ".xml"])

function walk(dir, files = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue
      walk(path.join(dir, entry.name), files)
    } else if (EXTENSIONS.has(path.extname(entry.name))) {
      files.push(path.join(dir, entry.name))
    }
  }
  return files
}

const CODE_ROOTS = [
  path.join(ROOT, "functions", "src"),
  path.join(ROOT, "functions", "index.js"),
  path.join(ROOT, "app", "src", "main", "java"),
  path.join(ROOT, "firestore.rules"),
  path.join(ROOT, "database.rules.json"),
].filter((target) => fs.existsSync(target))

function sourceFiles() {
  const files = []
  for (const target of CODE_ROOTS) {
    if (fs.statSync(target).isDirectory()) walk(target, files)
    else files.push(target)
  }
  return files
}

/** Lines matching a pattern, minus the two documented wire-format exceptions. */
function offenders(pattern) {
  const hits = []
  for (const file of sourceFiles()) {
    const lines = fs.readFileSync(file, "utf8").split("\n")
    lines.forEach((line, index) => {
      if (!pattern.test(line)) return
      if (/assignedAgentUid/.test(line)) return
      if (/sender\s*[:=]\s*"agent"|sender === "agent"|sender == "agent"/.test(line)) return
      hits.push(`${path.relative(ROOT, file)}:${index + 1}: ${line.trim()}`)
    })
  }
  return hits
}

module.exports = async function run() {
  suite("No agent role anywhere")

  await test("nothing sets or reads a role claim", () => {
    const hits = offenders(/token\.role|claims\["role"\]|claims\.role|decoded\.role|auth\.role/)
    assert(hits.length === 0, `role claim used:\n    ${hits.join("\n    ")}`)
  })

  await test("nothing compares a role against a string", () => {
    const hits = offenders(/role\s*(===|==|!==|!=)\s*["']|["']agent["']\s*(===|==)\s*/)
    assert(hits.length === 0, `role comparison found:\n    ${hits.join("\n    ")}`)
  })

  await test('nothing treats "agent" as a user type', () => {
    const hits = offenders(/role:\s*["']agent["']|"role",\s*"agent"|role\s*=\s*["']agent["']/)
    assert(hits.length === 0, `agent role literal found:\n    ${hits.join("\n    ")}`)
  })

  await test("there is no membership collection and no invite flow", () => {
    const hits = offenders(/collection\(["']agents["']\)|inviteAgent|invite_agent|\/agents\//)
    assert(hits.length === 0, `membership or invite code found:\n    ${hits.join("\n    ")}`)
  })

  await test("setCustomUserClaims only ever sets tenantId", () => {
    const source = fs.readFileSync(
      path.join(ROOT, "functions", "src", "routes", "bootstrap.js"),
      "utf8",
    )
    const calls = source.match(/setCustomUserClaims\([\s\S]*?\)\n/g) || []
    assert(calls.length > 0, "no setCustomUserClaims call found")
    for (const call of calls) {
      assert(!/role/.test(call), `role in claims: ${call.trim()}`)
      assert(/tenantId/.test(call), `no tenantId in claims: ${call.trim()}`)
    }
  })

  await test("the documented wire-format exceptions are still exactly two", () => {
    const senderHits = []
    const assignedHits = []
    for (const file of sourceFiles()) {
      const relative = path.relative(ROOT, file)
      const lines = fs.readFileSync(file, "utf8").split("\n")
      lines.forEach((line) => {
        if (/sender\s*[:=]\s*"agent"|sender === "agent"/.test(line)) senderHits.push(relative)
        if (/assignedAgentUid/.test(line)) assignedHits.push(relative)
      })
    }
    // Present on purpose. This case exists so that removing them becomes a deliberate act with a
    // failing test attached, rather than something that quietly happens.
    assert(senderHits.length > 0, 'sender: "agent" disappeared — the widget reads that value')
    assert(assignedHits.length > 0, "assignedAgentUid disappeared — existing chat data uses it")
  })
}
