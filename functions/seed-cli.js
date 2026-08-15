#!/usr/bin/env node
/**
 * Seeds the global catalogue (plans + DEMO100) from a terminal.
 *
 * Bootstrap already calls seedCatalog(), so this is for the case where you want the plans present
 * before anyone signs in — or where you edited a price in src/seed.js and want it live without
 * redeploying. Safe to run repeatedly: every write is a merge on a fixed document id, and the
 * coupon is created only when missing so a re-run can never reset redeemedCount.
 *
 *   GOOGLE_APPLICATION_CREDENTIALS=./service-account.json node seed-cli.js
 */

const { seedCatalog } = require("./src/seed")

seedCatalog()
  .then((result) => {
    console.log("Plans seeded:", result.plans.join(", "))
    console.log(
      result.coupon.created
        ? "Coupon DEMO100 created."
        : "Coupon DEMO100 already existed; left untouched so redeemedCount is preserved.",
    )
    process.exit(0)
  })
  .catch((err) => {
    console.error("Seed failed:", err)
    process.exit(1)
  })
