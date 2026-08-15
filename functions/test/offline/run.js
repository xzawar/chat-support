/**
 * Offline suite runner: node test/offline/run.js
 *
 * Runs without an emulator, without network access, and without installed dependencies, so the
 * security properties can be re-checked on any machine that has Node. The emulator suite in
 * ../rules.emulator.test.js covers the same rules through the real rules engine; run both when a
 * network is available.
 */

const { summary } = require("./harness")

async function main() {
  console.log("Support Chat \u2014 offline verification suite")
  await require("./firestore-rules.test")()
  await require("./rtdb-rules.test")()
  await require("./api.test")()
  await require("./no-agent-role.test")()
  await require("./retention.test")()

  const ok = summary()
  process.exit(ok ? 0 : 1)
}

main().catch((error) => {
  console.error("Suite crashed:", error)
  process.exit(1)
})
