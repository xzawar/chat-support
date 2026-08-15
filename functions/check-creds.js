#!/usr/bin/env node
/**
 * Answers one question: can this machine authenticate to Firebase, and if not, why not.
 *
 * Run it from the functions folder:
 *   node check-creds.js
 *
 * It touches nothing and writes nothing. Every check prints a verdict, so the output is a
 * complete picture rather than the first failure. Delete it once seeding works.
 */

const fs = require("fs")
const path = require("path")

const line = () => console.log("-".repeat(72))
const ok = (msg) => console.log("  [ OK ]   " + msg)
const bad = (msg) => console.log("  [ FAIL ] " + msg)
const info = (msg) => console.log("           " + msg)

line()
console.log("Firebase Admin credential check")
line()

/*
 * 1. Where am I?
 *
 * The single most common cause of "the file is definitely there" is running node from the project
 * root instead of from functions/, so a relative lookup resolves somewhere else entirely.
 */
console.log("\n1. Working directory")
console.log("   node was launched from : " + process.cwd())
console.log("   this script lives in   : " + __dirname)
if (process.cwd() !== __dirname) {
  bad("You are NOT in the functions folder.")
  info("cd \"" + __dirname + "\"  and run again.")
} else {
  ok("Running from the functions folder.")
}

/*
 * 2. The key file, checked by exact name.
 *
 * Windows hides known extensions by default, so a file displayed as "service-account.json" can
 * genuinely be "service-account.json.json" or "service-account.json.txt" on disk. Explorer will
 * insist it looks right. Listing the directory is the only way to see the truth, so if the exact
 * name is missing every nearby candidate is printed rather than just reporting absence.
 */
console.log("\n2. service-account.json")
const keyPath = path.join(__dirname, "service-account.json")
console.log("   looking for : " + keyPath)

let key = null
if (fs.existsSync(keyPath)) {
  ok("File exists.")
  const bytes = fs.statSync(keyPath).size
  info("Size: " + bytes + " bytes")
  if (bytes < 500) {
    bad("That is too small for a real key (expect roughly 2000-2500 bytes).")
  }
  try {
    key = JSON.parse(fs.readFileSync(keyPath, "utf8"))
    ok("Valid JSON.")
  } catch (err) {
    bad("Not valid JSON: " + err.message)
    info("Re-download it; do not open and re-save it in an editor.")
  }
} else {
  bad("NOT FOUND at that exact path.")
  const entries = fs.readdirSync(__dirname)
  const near = entries.filter(
    (f) => f.toLowerCase().includes("service") || f.toLowerCase().includes(".json"),
  )
  if (near.length) {
    info("Files here with similar names (note the REAL extensions):")
    near.forEach((f) => info("    " + f))
    info("If you see service-account.json.json or .json.txt, that is the problem.")
    info("Windows hides known extensions. Rename it to exactly: service-account.json")
  } else {
    info("No .json files in this folder at all - the key has not been downloaded yet.")
    info("Firebase Console > Project settings > Service accounts > Generate new private key")
  }
}

/* 3. Is it actually a service account key, and for the right project? */
if (key) {
  console.log("\n3. Key contents")
  const required = ["type", "project_id", "private_key", "client_email"]
  const missing = required.filter((f) => !key[f])
  if (missing.length) {
    bad("Missing required fields: " + missing.join(", "))
    info("This looks like the wrong file. A web app config is NOT a service account key.")
    info("The right file has \"type\": \"service_account\" and a private_key.")
  } else {
    ok("Looks like a real service account key.")
    info("type        : " + key.type)
    info("project_id  : " + key.project_id)
    info("client_email: " + key.client_email)
    if (key.project_id !== "chat-support-1") {
      bad("This key is for \"" + key.project_id + "\", not chat-support-1.")
      info("You generated it from the wrong Firebase project.")
    } else {
      ok("Correct project.")
    }
  }
}

/*
 * 4. The environment variable, which silently wins over the file.
 *
 * firebase.js skips the local file entirely when this is set, so a stale value left over from an
 * earlier attempt will keep the file from ever being read - and the error will look identical.
 */
console.log("\n4. GOOGLE_APPLICATION_CREDENTIALS")
const envVar = process.env.GOOGLE_APPLICATION_CREDENTIALS
if (!envVar) {
  ok("Not set. The local file will be used, which is what we want.")
} else {
  info("Set to: " + envVar)
  if (fs.existsSync(envVar)) {
    ok("That path exists and will be used INSTEAD of service-account.json.")
  } else {
    bad("That path does NOT exist. This overrides the local file and breaks it.")
    info("Clear it for this session:  $env:GOOGLE_APPLICATION_CREDENTIALS=\"\"")
  }
}

/* 5. Confirm firebase.js is the updated one. */
console.log("\n5. src/firebase.js version")
const fbPath = path.join(__dirname, "src", "firebase.js")
if (!fs.existsSync(fbPath)) {
  bad("src/firebase.js not found.")
} else {
  const src = fs.readFileSync(fbPath, "utf8")
  if (src.includes("localServiceAccount")) {
    ok("Updated version is in place.")
  } else {
    bad("This is the OLD firebase.js - the replacement did not land in src/.")
    info("It belongs at: " + fbPath)
  }
}

/* 6. The real thing: initialise and perform one authenticated read. */
console.log("\n6. Live connection test")
;(async () => {
  try {
    const { firestore } = require("./src/firebase")
    await firestore().collection("plans").limit(1).get()
    ok("Authenticated and reached Firestore.")
    console.log("\nCredentials are working. Run:  node seed-cli.js")
  } catch (err) {
    bad("Could not reach Firestore.")
    info(err.message.split("\n")[0])
    console.log("\nFix whatever is marked FAIL above, then run this again.")
  }
  line()
})()
