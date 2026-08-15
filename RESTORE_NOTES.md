# Support Chat 3.1 — what changed in this pass

Built on top of your existing app. Firebase Auth, the Realtime Database layer, the conversation
and message models, and the `status` / `assignedAgentUid` fields were treated as load-bearing and
kept. Nothing was migrated to Firestore.

## Phase 1 — Diagnosis

**Backend in use: Firebase Realtime Database** (`chat-support-1-default-rtdb`, asia-southeast1),
not Firestore. Auth is Firebase Auth email/password, plus Google via Credential Manager added in
the previous pass.

**Message receiving:** a real `ChildEventListener` / `ValueEventListener` pair on
`tenants/{tenantId}/messages/{conversationId}` — a live listener, not a one-time `get()`. It stays
attached for as long as the repository scope is alive.

**The persistence bug was cause #1: writes were not reliably reaching the database.**
It was not stale seed data and not a cached-snapshot race. The assign / close / delete actions
called `.updateChildren()` / `.removeValue()` but the returned `Task` was never awaited and its
failure was never surfaced, while the UI list was updated locally straight away. A rejected write
(the usual cause: `/agents/{uid}/tenantId` missing, so the security rules deny the path) therefore
looked exactly like a success until the next cold start re-read the untouched remote value.

Every mutation now goes through `awaitWrite(task)` in `SupportRepository`, returns an error string
instead of swallowing it, and the UI shows it in an error banner. Room is the durable guard around
this, not the fix itself — the fix is awaiting and reporting the write.

**Assigned / Closed filters** are restored as chips in the inbox header (All / Assigned /
Unassigned / Closed) reading the backend's existing `status` and `assignedAgentUid` fields. No new
field was introduced.

## 3.1.1 — compile fixes

Three errors from your first real Gradle run, all mine:

- `SupportDatabase.kt` — `fallbackToDestructiveMigration(dropAllTables = true)` needs Room 2.7;
  this project is on 2.6.1, where the method takes no arguments and already drops the tables.
  Now `fallbackToDestructiveMigration()`. The validator checks the Room version against this call.
- `ComingSoonScreen.kt` — `padding(horizontal =, bottom =)` is not a real overload; the axis form
  and the per-side form cannot be mixed. Now `padding(start =, end =, bottom =)`. The validator
  scans every `padding(...)` call for this mix.
- `AccountScreen.kt` — "Accepting chats" rendered a confident "No" while the read was still in
  flight or had failed. Now three-way, so unknown looks unknown.

Also: `CallingAgentScreen.kt`, `EmailAutomationScreen.kt` and `SocialMediaScreen.kt` are **not**
part of this tree. They are 2.0 leftovers in your local folder, referencing a `StatRow` composable
that was deleted when the mock dashboards were replaced with Coming Soon. Delete the folder and
unzip fresh rather than unzipping over it — unzipping merges, so stale files survive.


## Pass 3.1 — the account model from your SupportAccount.kt

Your file was added and the rest of the app was migrated onto it. **The account is now the
tenant.** There is no tenant id, no website id, and no hand-made `/agents/{uid}/tenantId` record
to create in the Firebase console. Signing in provisions `owners/{uid}` itself.

### Added

- `data/SupportAccount.kt` — your file, with three deliberate changes, each marked in the source:
  1. **`FirebaseDatabase.getInstance(DATABASE_URL)` instead of `getInstance()`.** Your version
     resolves the project default instance; this project's database is in **asia-southeast1**, so
     the bare call points at an empty US instance and every read silently returns nothing. This
     one would have cost you an afternoon.
  2. The Google credential fetch delegates to the existing `util/GoogleAuthClient.kt` rather than
     building a second Credential Manager request. Two sign-in paths that can drift apart is worse
     than one. `GoogleAuthClient` also sets `setFilterByAuthorizedAccounts(false)`, so the sheet is
     not empty on a fresh install.
  3. `ensureOwnerRecord` is public and is called after **email** sign-in too. Otherwise an email
     account signs in successfully and then cannot read anything, which is exactly the
     "permission denied" you reported.
- `ui/screens/LinkWebsiteScreen.kt` — generate / copy / revoke a pairing code, with a live
  countdown. Reached from Settings → "Link your website" and from the Account page.
- `Routes.LINK_WEBSITE`.

### Migrated

| Before | After |
| --- | --- |
| `tenants/{tenantId}/conversations/{id}` | `owners/{ownerUid}/conversations/{id}` |
| `tenants/{tenantId}/messages/{id}` | `owners/{ownerUid}/messages/{id}` |
| `agents/{uid}/devices/{deviceId}` | `owners/{uid}/devices/{deviceId}` |
| `agents/{uid}` profile record | `owners/{uid}/public` + `owners/{uid}/profile` |
| `SupportRepository(root, tenantId, ...)` | `SupportRepository(root, ownerUid, ...)` |
| `repository.agentProfile(uid)` | `repository.ownerProfile(uid)` |
| `const val TENANT_ID = "demo"` | deleted; the repository is keyed to `auth.uid` |

The Account page's "tenant mismatch" card is gone with it — the condition it detected can no
longer occur. It is replaced by owner details plus a card that fires only if `owners/{uid}` is
still empty after sign-in, which now means the rules rejected the bootstrap write.

`database.rules.json` and `functions/index.js` were rewritten for the new paths. The push function
now reads recipients from `owners/{uid}/devices` plus `owners/{uid}/team`, and still narrows to the
assignee alone when a conversation is assigned.

### Read this before you deploy

- **Existing data does not move.** Anything under `tenants/demo/…` stays there and will not appear
  in the app. Nothing was deleted, but nothing was copied either — a migration script would have to
  guess which uid owns the old demo tenant, and guessing wrong would hand your conversations to the
  wrong account.
- **Your website / WordPress widget still writes to the old path.** It must be repointed to
  `owners/{your uid}/…`. That is what the pairing code screen is for: the plugin reads
  `/pairings/{code}`, checks the email matches, and stores the owner uid.
- **Team accounts have a gap I did not paper over.** Your design grants a team member access via
  `owners/{ownerUid}/team/{uid}`, and the rules honour it, but a team member's device has no way to
  discover *which* owner it belongs to — there is no reverse index in the design. The app therefore
  always opens the signed-in user's own owner node. `addTeamMember` is wired and works server side;
  making a second person actually see someone else's inbox needs either a `memberOf/{uid}/{ownerUid}`
  index or the owner uid handed over at invite time. I did not invent one silently.
- The pairing rule caps `expiresAt` at one hour and requires `ownerUid === auth.uid`, as your
  comments specified. Codes are revoked with the button on the screen; leaving the screen does not
  auto-revoke, because a coroutine started in a composable is cancelled when that composable goes
  away and the write would only land some of the time.


## Phase 3 — Data layer

`Firebase → SupportRepository → Room → Flow → ViewModel StateFlow → Compose`.

- Room (`support-chat.db`, `SupportDao`) is the single source of truth. No screen reads Firebase.
- The repository's listeners write into Room; DAO `Flow`s push to the UI automatically.
- Sends, assigns, closes and deletes write remotely first, then let the sync path update Room.
- ViewModels use `stateIn(scope, SharingStarted.WhileSubscribed(5000), initial)`; screens use
  `collectAsStateWithLifecycle()` throughout.
- Every lazy list has `key = { it.id }`; animation specs come from `ui/theme/Motion.kt`.

## Phases 4–7

- **Navbar:** floating pill, 28dp icons, 66dp targets, index-based, clears navigation-bar insets.
- **Tab swipe:** the four tab roots are pages of one `HorizontalPager` inside a single nav
  destination. Row-level swipe-to-delete is a child pointer handler, so it consumes horizontal
  drags before the pager sees them. The conversation screen is a separate destination.
- **Wallpaper:** five bundled backgrounds plus a gallery pick (with a persistable URI permission),
  stored in DataStore, drawn behind the message bubbles only.
- **Auth flow:** launch checks `FirebaseAuth.getInstance().currentUser`; signed in goes straight to
  the tabs, Login is popped off the back stack. Sign-out clears the main graph. Both Google and
  "Sign in with Email" are on the login screen — neither replaced the other.
- **Push:** `SupportMessagingService`, channel `support_messages`, token stored at
  `agents/{uid}/devices/{deviceId}`, cleared on sign-out, `POST_NOTIFICATIONS` requested after
  login, notification deep-links to the conversation.

## Removed at your request

The Email Automation, Calling Agent and Social Media mock dashboards from the previous pass, plus
`data/MockDashboards.kt` and the `Stat` / `StatTile` / `StatRow` components that only they used.
All three tabs now show the supplied "Nothing to show yet" Coming Soon design.

## Still needs you

1. **The Cloud Function does not exist in your Firebase project.** `functions/index.js` is written
   but undeployed, and deploying it requires the Blaze plan. Until then, notifications only appear
   while the app is in the foreground.
2. `/agents/{YOUR_AUTH_UID}/tenantId = "demo"` must exist, including a separate record for the
   different UID that Google sign-in creates.
3. Google sign-in still needs the console steps; `google_web_client_id` is a placeholder and
   `oauth_client` is empty in `google-services.json`.
4. Login hero art and the Coming Soon illustration are placeholders — swap points are commented in
   `LoginScreen.kt` and `res/drawable/il_coming_soon.xml`.
5. **This was never compiled.** No Android SDK or network access here, so treat first build
   warnings as expected, and do the profiling pass yourself.

## 3.2 - login screen replica

- ui/screens/LoginScreen.kt rebuilt against the supplied reference: full-bleed illustration panel
  over 58% of the screen, "Support Chat" wordmark, cream sheet (#F5F1EA), white Google pill with
  a hairline border, black pill below it.
- Colours on this screen are hard-coded, not themed. It is a branded first-run screen and the
  reference is one fixed scheme, so it looks identical in light and dark mode. Text field colours
  are pinned for the same reason: themed fields would render light-on-cream in dark mode.
- res/drawable-nodpi/login_hero.png is cropped straight out of the reference JPG (552x526). It is
  below screen resolution and will be upscaled about 2x on a 1080p phone, so it will look soft.
  Replace it with a vector or a full-resolution export when one is available.
- The wordmark uses FontFamily.SansSerif at FontWeight.Black. The reference typeface could not be
  identified or downloaded. Drop a .ttf into res/font and change WordmarkFont in LoginScreen.kt.
  Poppins ExtraBold and Nunito Black are close free stand-ins.
- res/drawable/ic_google_g.xml is a redrawn G. Google's brand terms require their own artwork:
  swap it before shipping (developers.google.com/identity/branding-guidelines).
- The reference has an Apple button in the lower slot. Apple sign-in is not added, so that slot
  keeps "Sign in with Email".
- Removed the stale footer note on the login screen that still referenced /agents/<uid>/tenantId.
- LinkWebsiteScreen: generating a new code now revokes the previous one first, the expiry line
  shows a wall-clock time as well as the countdown, "Revoke code" is now "Cancel this code", and
  the footer no longer claims that leaving the screen revokes the code (it never did).

## 3.3 - theme, icons, avatar, back gesture

- ui/theme/Color.kt rebuilt from the login palette: cream paper (#F5F1EA), ink (#111111), the
  illustration's pale sky blue as the single accent. The dark scheme is built from the same idea
  rather than a grey inversion: warm charcoal (#14120F) with warm neutral text, because a simply
  darkened cream turns muddy.
- Nav icons redrawn as vectors in the heavy rounded style of the navbar reference: chat bubble,
  envelope, handset, two people, each with a filled variant for the active tab. These are my
  redraws from the reference image, not exports of your originals.
- New res/drawable/ic_menu.xml, and the inbox top bar now shows the three-line menu in place of
  the gear. It still opens Settings.
- Predictive back turned off in AndroidManifest.xml. That preview - the previous screen sliding
  in while the gesture is held - is the Android 14+ behaviour you were seeing; with it off the
  screen only changes when you release.
- Profile picture: InitialsAvatar now takes an optional photoUrl and AuthUiState exposes the
  Google account photo. Nothing is uploaded and no Storage bucket is needed. Email-only accounts
  have no photo and keep their initials, which the Account page states directly.
- res/drawable-nodpi/il_hello_bubble.png and il_blob_orange.png are cut out of the login
  illustration with the pale background made transparent, and the Coming Soon screen now uses
  them. Same caveat as the hero: they come from the mockup JPG, so they are low resolution.

## 3.4 - Google sign-in wired up

- app/google-services.json replaced with the file you sent, under the exact name the plugin
  reads. It now carries an oauth_client entry, which the previous one did not.
- res/values/strings.xml: google_web_client_id is the web client (client_type 3),
  671335404750-q3c57d7ba6obihtegcoo81m7mhcm7s4q.apps.googleusercontent.com. Marked
  translatable="false" so no localisation pass can touch it.
- Still no SHA-1 registered: the file has no client_type 1 entry with a certificate_hash. Debug
  sign-in works without it, but a release build will not, and a new machine or a regenerated
  debug keystore will break it. Add the fingerprint and re-download when convenient.

## 3.4.1 - resource linking fix

- Removed android:tint="?attr/colorControlNormal" from the nine icons I generated in 3.3.
  colorControlNormal without the android: prefix is an AppCompat attribute, and this app has no
  AppCompat theme, so aapt could not resolve it and processDebugResources failed. My mistake, in
  the icon generator.
- The icons do not need a tint attribute at all: every one of them is drawn through Compose's
  Icon composable, which applies its own tint over the painter. FloatingTabBar passes iconTint
  and the inbox top bar inherits from the theme, so light and dark still work.
- il_coming_soon.xml uses ?android:attr/colorControlNormal, with the android: prefix. That one is
  a framework attribute, it always resolves, and it was not part of the failure. Left alone.
- Added a validator rule that rejects any ?attr/ reference in res/drawable, so this class of
  error cannot reach you again.

## 3.5 - illustrations, swipe fix, offline cache

- Your 14 sphere SVGs converted to real Android vector drawables (res/drawable/il_sphere_*.xml).
  Circles, ellipses, rects and lines were rewritten as path data and the radial gradients were
  re-expressed in viewport units, because <vector> supports none of those SVG elements directly.
- App icon rebuilt from 07-red-happy on a cream background, scaled into the adaptive-icon safe zone.
- Coming Soon screens now show a three-sphere cluster chosen from the screen title.
- Swipe to delete no longer shows the red Delete panel at rest. The background slot is only
  composed while a drag is actually in progress, and it fades in with the finger.
- Firebase disk persistence enabled in a new SupportChatApplication, plus keepSynced on the
  conversations node, so a cold start replays the local copy instead of re-downloading everything.

Still required from you, and none of it is code I can do from here:
- firebase deploy --only functions (needs the Blaze plan). Without it there are no notifications
  when the app is closed - the database write notifies nothing by itself.
- firebase deploy --only database, to publish the corrected rules that let devices/ be written.
- Repoint the WordPress plugin at owners/<your uid>/conversations and owners/<your uid>/messages.

## 3.6 - swipe redesign, lime logo, animated spheres, sign-in diagnostics

- Swipe to delete rebuilt to match the reference: a solid red block uncovered as the row slides
  left, with a white trash icon + Delete pinned to the trailing edge, fading and sliding in with
  the finger. The panel still only exists while a drag is actually happening, and the row itself
  is now opaque so nothing shows through it.
- **[Corrected in 4.2 — this is no longer true.]** App icon replaced with the new lime wink logo
  (face vector + lime background, inside the adaptive safe zone), and lime was the app's accent:
  the active tab dot was lime, and
  secondaryContainer/tertiary carry it through the rest of the theme.
- The sphere illustrations on the Coming Soon screens now bob gently, each on its own clock.
- The filled email tab icon no longer collapses into a square: the flap is cut out of the body
  with evenOdd instead of being a white stroke, so Compose's icon tint can't erase it.
- Google sign-in: the cancelled-sheet case now says what it almost certainly means (the missing
  SHA-1), the owners-record write reports rules failures as rules failures, and GoogleAuthClient
  logs each step (filter Logcat by GoogleAuthClient).
- firebase.json now declares the functions directory, so firebase deploy --only functions works
  from the project root.

The two deploys in NOTIFICATIONS.md are still required from you; nothing about them can be done
from this side.

## 3.7 - visitor faces, high refresh rate, cold-start deferral

- Website visitors now get an illustrated sphere face instead of initials, in the inbox, the
  conversation header and the push notification. Every visitor is literally named "Website
  Visitor", so initials were the same two letters for everyone. The face is chosen from the
  conversation id, so it is stable across devices and reinstalls, and nothing is stored.
- Phase 3.5: the window now asks for the display's fastest mode on API 30+, so 90/120Hz panels
  are no longer left at the 60Hz default. Every animation already used Compose's
  Choreographer-backed APIs, and a grep confirmed there is no hand-rolled postDelayed(16) loop
  anywhere, which would have capped the app at 60fps regardless.
- Phase 3.6: FCM token registration is deferred past the first frame. It is a network round
  trip that was firing in the same instant as the auth check, the database listeners and the
  Room reads; none of it is on the main thread, but together they starve the same small pool.
  Firebase disk persistence (added in 3.5) already lets the inbox render from cache instead of
  waiting on the network.
- The navbar was already inverted against the theme (dark pill in light mode, light pill in
  dark mode) via inverseSurface; verified rather than changed.

## 4.2 - exact logo colours, Phase 8 UI, Phase 9 density, junk removal

The zip that came in was already 4.1.1, with Phases 1-7 done. The data layer had also already
been moved to Phase 8 - `ConversationStatus.PENDING`, `keepChat`, `startChat()`, pagination, and
an `InboxViewModel` with assign/close/reopen stripped out were all present. What had not been
done was the UI on top of it, which meant `ChatScreen` was still calling `viewModel::assignToMe`
on a ViewModel that no longer declared it. **The project as received did not compile.** That is
fixed here.

### Colour - exact logo values only

First, a correction to 3.6 above: the app is **not** lime. A later pass reverted to blue and the
notes were never updated. The launcher icon is the blue one, and that is what the palette is now
sampled from. `keykraft_logo.png` is green, but it appears only on the Help page as a partner
mark, and it is left alone.

Every brand value in `Color.kt` is now a colour that literally appears in
`mipmap-xxxhdpi/ic_launcher.png`, read out with an actual histogram rather than eyeballed:

| Token | Value | Where it comes from |
| --- | --- | --- |
| `SkyLight` | `#51C9FD` | icon gradient top |
| `SkyDeep` | `#4AA7F7` | icon gradient bottom |
| `SkyMid` | `#3C92CD` | icon shading |
| `SkyShadow` | `#2F6F92` | icon deep shadow |
| `InkBlack` | `#07090A` | the eyes and mouth |
| `PureWhite` | `#FFFFFF` | the face |

`SkyTint`, `SkyInk` and `KeykraftGreen` are deleted. Those were the soft, lightened derivatives -
tints mixed toward white to make backgrounds feel gentle - and they are exactly what was asked to
go. Light mode is now true `#FFFFFF` and dark mode true `#07090A`, with pure-neutral greys in
between so nothing is a washed-out blue pretending to be grey.

One deliberate consequence worth stating plainly, because it looks like a mistake otherwise:
**`onPrimary` is `InkBlack`, not white.** White text on `#4AA7F7` measures about 2.4:1, which
fails WCAG AA and is genuinely hard to read. The icon's own black on that same blue is 8.9:1. The
choices were to darken the brand blue - which breaks "exact logo colours" - or to use the black
that is already in the logo. The second one keeps both promises, so agent message bubbles and
filled buttons now carry black text on brand blue, which is also what the icon itself looks like.

### Phase 8 - the UI that was missing

- **`VisitorProfileScreen`** is new. Name, email, page URL, country, device, first seen, last
  activity, chat started. It hosts **Keep Chat** and **Close / Reopen**. Pushed as its own
  destination on top of the conversation, so back goes profile -> chat -> inbox.
- **`ChatScreen`** header is emptied out. Assign and Close/Reopen are gone - that is what was
  breaking the build - and the only action left is opening the visitor profile. Tapping the
  visitor's name does the same.
- **Start Chat gate.** A pending conversation shows a Start Chat panel *instead of* the composer,
  not above it. Reading a pending thread does not connect the visitor; only the button does, and
  that one action assigns the chat, flips it to open, and writes the "you're now connected"
  system message in a single atomic update.
- **System messages** render centred, as a chip rather than a bubble on either side.
- **`ConversationRow`** loses the whole Assign / Close action strip. Status is now one small pill,
  shown only for Pending and Closed - "Unassigned" on every row was noise. Assigned-to-you is a
  6dp dot.
- **Pagination** is wired to the UI: scrolling near the oldest loaded message widens the window.

### Phase 9 - density

Row vertical padding 12dp -> 8dp and avatar 52dp -> 42dp; combined with the deleted action strip
that takes a row from roughly 100dp to roughly 62dp. Composer padding 12/8 -> 10/5 with a
single-line-until-needed field. Send button gets a real 1dp border. Hamburger 22dp -> 26dp.
Search bar inset to 28dp horizontal with a smaller placeholder.

Separation is by **tonal shading**, not rules. The inbox header and the search/filter band sit on
`surfaceContainerLow` while the list sits on `surface`; the chat header and composer do the same.
The divider between inbox rows is gone entirely. A new `TonalSection` helper does this for
grouped content. `ThinDivider` survives for splitting rows *inside* one section, where a shade
step would just produce stripes.

### Backend - both new functions are required, neither is deployed

- `notifyAgentsOnSupportRequest` watches `status` and fires **only** on the transition into
  `pending`. Previously every visitor message pushed, so four lines of typing meant four alerts
  for one request for help.
- `notifyAgentsOnVisitorMessage` now refuses to send unless `status === "open"`, and ignores
  `sender: "system"`.
- `purgeExpiredConversations` is a new hourly `pubsub.schedule` job. It deletes the conversation
  **and its message subtree** in one multi-path update - deleting the conversation alone would
  orphan the messages forever. `keepChat === true` is exempt. A row with no usable timestamp is
  skipped, otherwise the first run after deploy would wipe every legacy conversation.

This needs Blaze, and the schedule additionally enables Cloud Scheduler. Until
`firebase deploy --only functions` runs, there are no notifications when the app is closed **and
there is no auto-delete at all**. No client code can substitute for either - a phone that is off
cannot delete anything.

### Junk removed

- Drawables: `brand_launcher.png`, `brand_launcher_medium.png`, `brand_launcher_small.png`,
  `ic_brand_mark.xml`, `ic_brand_mark_white.xml`, `il_blob_orange.png`, `il_hello_bubble.png`,
  `il_coming_soon.xml`, `ic_assign.xml`.
- `SupportDao.conversationCount()` and `messageCount()` - written for a seed-demo-data path that
  no longer exists. Room generates code for every abstract query whether called or not.
- `Conversation.initials` - returned "WV" for every visitor, unreferenced since the switch to
  illustrated avatars.
- `Common.kt` `internal val Transparent` - an alias for `Color.Transparent`.
- Colour tokens `SkyTint`, `SkyInk`, `KeykraftGreen`.
- The inbox error hint pointing at `/agents/{uid}/tenantId` and the `"demo"` tenant. Neither
  exists any more, so the advice was actively misleading.
- Two indexes in `database.rules.json`: `searchText`, which nothing has ever written, and
  `expiresAt`, which is computed client-side and which the purge job scans for rather than
  queries.

### Not verifiable here

There is no Android SDK, no device and no network in the environment this was written in, so
nothing was compiled and none of the runtime acceptance criteria - GPU rendering bars, refresh
rate above 60, cold-start trace, FCM in all three app states - could be measured. The code was
checked by reading it. Expect to fix an import or two on first build.

Phase 10 assets were never attached; sensible defaults remain in place.
