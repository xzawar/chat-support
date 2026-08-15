# Releases and in-app updates

How a new version reaches installed apps, and what to do to publish one.

The app is not distributed through Play, so nothing tells users a fix exists unless the app asks.
On startup it fetches a small JSON manifest, compares one number against its own `versionCode`,
and offers the download if the server's number is higher.

---

## The moving parts

| Piece | Where | What it does |
| --- | --- | --- |
| `latest.json` | Your domain, e.g. `https://keykraftt.com/app/latest.json` | Says which version is current and where the APK is |
| `app-release.apk` | GitHub Release asset | The build itself |
| `UPDATE_MANIFEST_URL` | `app/build.gradle.kts` | The manifest URL, compiled into the app |
| `.github/workflows/release.yml` | CI | Builds, signs, generates the manifest, attaches both to the Release |
| `UpdateChecker` / `UpdateInstaller` / `UpdateHost` | `app/.../update/` | Checks, downloads, prompts, installs |

### Two URLs you must change before the first release

1. `UPDATE_MANIFEST_URL` in `app/build.gradle.kts` — currently `https://keykraftt.com/app/latest.json`
2. `DOWNLOAD_URL` in `.github/workflows/release.yml` — currently `https://keykraftt.com/app/download`

Both point at **your** domain, not at `github.com`, even though the files live in a GitHub Release.
The manifest URL is compiled into every installed copy and can never be changed retroactively, so it
has to be an address you will control forever. A redirect can be repointed at a new repo, a new
account or a different host whenever you like, and every already-installed app follows it.
Hardcoding `github.com/<owner>/<repo>` makes renaming your repository a breaking change for users.

---

## One-time setup

### 1. Create a signing keystore, then never lose it

Android installs an update over an existing app **only when both are signed by the same key**. A
differently-signed APK fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way out is
uninstalling first, which erases the user's local data. There is no recovery from a lost keystore
other than telling everyone to uninstall and reinstall.

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias supportchat
```

Back `release.jks` up somewhere that is not the repository, along with the two passwords and the
alias.

### 2. Add five repository secrets

**Settings → Secrets and variables → Actions → New repository secret**

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | The keystore, base64-encoded (see below) |
| `KEYSTORE_PASSWORD` | The store password |
| `KEY_ALIAS` | e.g. `supportchat` |
| `KEY_PASSWORD` | The key password, which is the store password unless you set a different one |
| `GOOGLE_SERVICES_JSON` | The entire contents of `app/google-services.json` |

All five are required. `KEY_PASSWORD` is read separately from `KEYSTORE_PASSWORD` with no fallback,
so it must be set even when the two are identical.

`GOOGLE_SERVICES_JSON` exists because `app/google-services.json` is gitignored and therefore absent
from the checkout, and the Google Services Gradle plugin refuses to build without it. Both workflows
write it back before compiling. Paste the file's contents verbatim, including the braces.

Encoding the keystore:

```bash
base64 -w 0 release.jks          # Linux
base64 -i release.jks            # macOS
```

```powershell
# Windows. Do not use certutil -encode: it adds header lines that corrupt the secret.
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
```

### 3. Point your domain at GitHub

Two paths on your domain, both pointing into the release. GitHub keeps
`/releases/latest/download/<asset-name>` resolving to the newest release forever, so these never
need touching again.

- `/app/download` → `https://github.com/<owner>/<repo>/releases/latest/download/app-release.apk`
- `/app/latest.json` → `https://github.com/<owner>/<repo>/releases/latest/download/latest.json`

A redirect is enough — `DownloadManager` follows https→https redirects, which is one of the reasons
the app uses it instead of a hand-written download.

**Cloudflare** (Rules → Redirect Rules, or a Worker):

```
When incoming requests match:  URI Path equals /app/download
Then:  Dynamic redirect, 302, concat("https://github.com/<owner>/<repo>/releases/latest/download/app-release.apk")
```

**Vercel** (`vercel.json`):

```json
{
  "redirects": [
    { "source": "/app/download", "destination": "https://github.com/<owner>/<repo>/releases/latest/download/app-release.apk" },
    { "source": "/app/latest.json", "destination": "https://github.com/<owner>/<repo>/releases/latest/download/latest.json" }
  ]
}
```

**Netlify** (`_redirects`):

```
/app/download      https://github.com/<owner>/<repo>/releases/latest/download/app-release.apk      302
/app/latest.json   https://github.com/<owner>/<repo>/releases/latest/download/latest.json          302
```

**Apache / cPanel** (`.htaccess`):

```apache
Redirect 302 /app/download    https://github.com/<owner>/<repo>/releases/latest/download/app-release.apk
Redirect 302 /app/latest.json https://github.com/<owner>/<repo>/releases/latest/download/latest.json
```

**Nginx**:

```nginx
location = /app/download    { return 302 https://github.com/<owner>/<repo>/releases/latest/download/app-release.apk; }
location = /app/latest.json { return 302 https://github.com/<owner>/<repo>/releases/latest/download/latest.json; }
```

> A "Download for Android" button on your site is then just
> `<a href="/app/download">Download</a>`. Your domain in the markup, GitHub serving the bytes.

If you would rather your domain stay in the address bar, proxy instead of redirecting (Cloudflare
Worker, or `proxy_pass` behind a small cache). It works identically for the app; it just costs you
the bandwidth that GitHub was absorbing.

---

## Publishing a release

**Bump both version fields in `app/build.gradle.kts`.** This is the step that matters:

```kotlin
versionCode = 43        // MUST increase. This is the only value the update check compares.
versionName = "7.0.3"   // Shown to the user. Never parsed.
```

`versionCode` is a plain integer that only ever goes up. `versionName` is a label — as a string,
`"7.0.10"` sorts *below* `"7.0.9"`, which is exactly the bug `versionCode` exists to avoid. Tag a
release without bumping `versionCode` and the APK uploads perfectly while no installed app ever
offers it; CI prints a warning if the tag and `versionName` disagree.

Then tag and push:

```bash
git add -A
git commit -m "Release 7.0.3"
git tag -a v7.0.3 -m "Fixes expired chats not being deleted."   # the message becomes the changelog
git push origin main --tags
```

That is the whole process. The `Release` workflow then:

1. builds and signs `app-release.apk`,
2. writes `latest.json` with the real `versionCode` from the build file and the APK's actual size,
3. attaches both to a GitHub Release for that tag.

Watch it under the **Actions** tab. On success, open
`https://keykraftt.com/app/latest.json` in a browser — if that shows the new `versionCode`, every
app will offer the update on its next launch.

### Retrying a failed publish

**Actions → Release → Run workflow**, and give it the existing tag. No new version number needed.

### Doing it by hand

```bash
export KEYSTORE_FILE=$PWD/release.jks KEYSTORE_PASSWORD=... KEY_ALIAS=supportchat KEY_PASSWORD=...
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Then create the Release in the GitHub UI and attach `app-release.apk` plus a `latest.json` you write
yourself. Keep the asset filenames exactly as they are — the redirects resolve by name.

---

## The manifest format

```json
{
  "versionCode": 43,
  "versionName": "7.0.3",
  "apkUrl": "https://keykraftt.com/app/download",
  "changelog": "Fixes expired chats not being deleted.",
  "mandatory": false,
  "sizeBytes": 24117248
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `versionCode` | yes | Integer. Offered only when greater than the installed one. |
| `versionName` | no | Defaults to the version code. Display only. |
| `apkUrl` | yes | **Must be `https://`.** Plain http is rejected. |
| `changelog` | no | Shown in the prompt. |
| `mandatory` | no | `true` removes the Later button and ignores an earlier dismissal. |
| `sizeBytes` | no | Shown next to the version. |

`mandatory` is for builds where the old version is actively broken — it takes away the user's choice,
so treat it as rare.

---

## Behaviour worth knowing

- **Every failure is silent.** No network, captive wifi, a half-deployed manifest, malformed JSON:
  the check simply concludes "up to date" and tries again next launch. An update check must never put
  an error in front of an app that otherwise works.
- **The check runs two seconds after the first frame**, not during startup, so it never competes with
  Firebase restoring the session.
- **"Later" is remembered per version.** The declined `versionCode` is stored, so the next release
  prompts again on its own without anything needing to be reset. Rotating the screen does not
  re-prompt.
- **Install permission is requested before the download.** From Android 8 the user must allow this
  specific app to install packages, which is a Settings screen rather than a dialog. Asking after a
  25 MB download would risk wasting it.
- **The APK downloads to the app's own external files directory**, so no storage permission is
  involved on any API level and the file disappears if the app is uninstalled.
- **Users can turn it off** — Settings → "Check for updates on startup". On by default, because
  nothing else would ever tell them.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| No prompt appears | `versionCode` in `latest.json` is not greater than the installed build; or the user dismissed this exact version; or the toggle is off. |
| Prompt appears, install fails | The new APK is signed with a different key than the installed one. |
| Download starts, never finishes | `apkUrl` redirects to a page rather than a file, or is plain http. |
| Manifest opens fine in a browser, app never sees it | The URL compiled into the installed APK differs from the one you are testing — check `UPDATE_MANIFEST_URL` for the build they actually have. |
| Workflow fails at the keystore step | `KEYSTORE_BASE64` is unset or was encoded with line wrapping; use `base64 -w 0`. |
