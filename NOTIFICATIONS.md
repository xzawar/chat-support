# Notifications: the full checklist, in order

The app code for notifications is complete and has been since 3.0. If pushes are not arriving,
it is because one of these four pieces of YOUR Firebase project is not done yet. None of them
can be done from the app code.

## 1. Deploy the database rules (lets the phone save its push token)

Without this, every attempt to write owners/<you>/devices/<id> is rejected, silently.

    firebase deploy --only database

Check it worked: Firebase console > Realtime Database > Rules should match database.rules.json
and contain a "devices" block.

## 2. Deploy the Cloud Function (turns a message into a push)

Writing a message to the database notifies NOTHING on its own. functions/index.js is the piece
that watches for new visitor messages and sends the FCM push. It requires the Blaze
(pay-as-you-go) plan; Spark cannot deploy functions.

    npm install --prefix functions
    firebase deploy --only functions

Check it worked: Firebase console > Functions lists notifyAgentsOnVisitorMessage.

## 3. Confirm your phone's token is actually saved

After installing 3.6 and signing in, open Firebase console > Realtime Database >
owners/<your uid>/devices. You should see one entry per device with a token. If it is empty,
either step 1 was not deployed or the app was installed before it was. Reinstall, sign in, and
look again.

## 4. Point the website widget at the owner paths

The plugin must write to:

    owners/<your uid>/conversations
    owners/<your uid>/messages/<conversationId>

If it still writes to the old tenants/... or top-level conversations/ layout, the app (and the
function) will never see those messages.

## If Google sign-in closes right after you pick an account

That is the missing SHA-1 fingerprint. Get it:

    keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android

Copy the SHA-1 line, then: Firebase console > Project settings > Your apps > your Android app >
SHA certificate fingerprints > Add fingerprint. Re-download google-services.json into app/ and
reinstall.

## Watching it work end-to-end

    firebase functions:log

Send a test message from the website widget. The log should show
"Sent 1/1 pushes for <conversationId>". If it says "No device tokens", step 3 is the problem.
If there is no log line at all, the function (step 2) is not deployed, or the widget (step 4)
is writing somewhere else.
