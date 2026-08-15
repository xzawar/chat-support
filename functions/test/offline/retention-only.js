/**
 * Runs the retention suite on its own: node test/offline/retention-only.js
 *
 * The full suite (run.js) also evaluates firestore.rules and database.rules.json, and those two
 * files parse as JSON5-with-comments rather than strict JSON, so that part of the suite fails on
 * this checkout for reasons that have nothing to do with retention. This entry point exists so the
 * sweep can be verified without that noise.
 */

const { summary } = require("./harness")

async function main() {
	console.log("Support Chat \u2014 retention verification")
	await require("./retention.test")()
	process.exit(summary() ? 0 : 1)
}

main().catch((error) => {
	console.error("Suite crashed:", error)
	process.exit(1)
})
