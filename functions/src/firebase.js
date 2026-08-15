/**
 * Single Admin SDK initialisation for the whole backend.
 *
 * Everything that touches data imports from here. Two accessors instead of two globals so a
 * module can be required in a test without booting Firebase as a side effect of the import.
 *
 * ---------------------------------------------------------------------------------------------
 * Why this file grew credential handling
 *
 * initializeApp() used to be called with nothing but a databaseURL. Inside Cloud Functions that
 * is exactly right: the runtime injects both the project id and a service account, so naming
 * either one would be redundant and would drift the day the project is renamed.
 *
 * On a laptop nothing is injected, and the SDK fails with:
 *
 *   Error: Unable to detect a Project Id in the current environment.
 *
 * which is a confusing way of saying "I have no credentials and no project". It surfaces at the
 * first write rather than at startup, which is why the stack pointed at seedPlans() and looked
 * like a bug in the seeder.
 *
 * Three sources are tried in order, most explicit first. All three end up at the same place, so
 * nothing downstream needs to know which one won.
 */

const fs = require("fs")
const path = require("path")
const admin = require("firebase-admin")
const { DATABASE_URL, PROJECT_ID } = require("./config")

/**
 * A service-account.json sitting next to package.json.
 *
 * This is the path that makes the seeder runnable on Windows. Setting GOOGLE_APPLICATION_CREDENTIALS
 * inline the Unix way does not work in PowerShell or cmd, which is the single most common reason
 * a first seed attempt fails. Dropping the key file in the functions folder needs no environment
 * variable at all.
 *
 * The file is deliberately not read when GOOGLE_APPLICATION_CREDENTIALS is already set, so an
 * explicit choice always beats a file someone forgot to delete.
 */
function localServiceAccount() {
  if (process.env.GOOGLE_APPLICATION_CREDENTIALS) return null
  const file = path.join(__dirname, "..", "service-account.json")
  if (!fs.existsSync(file)) return null
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"))
  } catch (err) {
    throw new Error(
      "functions/service-account.json exists but is not valid JSON. Re-download the key from " +
        "Firebase Console > Project settings > Service accounts > Generate new private key. " +
        "Underlying error: " +
        err.message,
    )
  }
}

/**
 * Emulator runs need no credentials at all - the emulators accept anything - but they still need
 * a project id to namespace the data under, or writes land somewhere the UI is not looking.
 */
const usingEmulator = Boolean(
  process.env.FIRESTORE_EMULATOR_HOST || process.env.FIREBASE_DATABASE_EMULATOR_HOST,
)

if (admin.apps.length === 0) {
  const options = { databaseURL: DATABASE_URL, projectId: PROJECT_ID }

  const key = localServiceAccount()
  if (key) {
    options.credential = admin.credential.cert(key)
    // The key names its own project. Trust it over the constant, so pointing at a second
    // project is a matter of swapping one file.
    if (key.project_id) options.projectId = key.project_id
  } else if (!usingEmulator) {
    /*
     * applicationDefault() covers the two remaining cases: GOOGLE_APPLICATION_CREDENTIALS
     * pointing at a key, and `gcloud auth application-default login` having been run. It throws
     * if neither is present - which is the point. Failing here, at startup, with a message that
     * says what to do is strictly better than failing inside a batch commit six frames deep.
     */
    try {
      options.credential = admin.credential.applicationDefault()
    } catch (err) {
      throw new Error(
        [
          "No Firebase Admin credentials found.",
          "",
          "Pick one:",
          "  1. Firebase Console > Project settings > Service accounts > Generate new private key,",
          "     then save it as functions/service-account.json. Nothing else to configure.",
          "  2. gcloud auth application-default login",
          "  3. Set GOOGLE_APPLICATION_CREDENTIALS to the full path of a key file.",
          "",
          "Do not commit the key file. It grants full admin access to " + PROJECT_ID + ".",
          "",
          "Underlying error: " + err.message,
        ].join("\n"),
      )
    }
  }

  admin.initializeApp(options)
}

/** Firestore holds business data. Client access is denied by rules; this is the only door. */
const firestore = () => admin.firestore()

/** RTDB holds chat and nothing else. */
const rtdb = () => admin.database()

const auth = () => admin.auth()

const FieldValue = admin.firestore.FieldValue
const Timestamp = admin.firestore.Timestamp

module.exports = { admin, firestore, rtdb, auth, FieldValue, Timestamp }
