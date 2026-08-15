package com.codexce.supportchat.startup

import android.content.Context
import androidx.startup.Initializer
import com.codexce.supportchat.data.AppPreferences
import com.codexce.supportchat.data.DATABASE_URL
import com.codexce.supportchat.data.Emulators
import com.google.firebase.database.FirebaseDatabase

/**
 * Everything that has to happen before the first Firebase call, moved off Application.onCreate.
 *
 * Why this exists at all.
 *
 * Firebase, Room, Coil, WorkManager and profileinstaller each ship their own ContentProvider to
 * bootstrap themselves. The system instantiates and runs every one of them, in sequence, on the
 * main thread, before Application.onCreate is even called - each one costing a class load, a
 * provider installation and a manifest lookup. That is the part of bindApplication a trace shows
 * as a wall of provider work before any of the app's own code runs.
 *
 * App Startup collapses the ones we control into a single provider
 * (androidx.startup.InitializationProvider) and runs them as ordinary objects behind it. The
 * saving is not the work itself - the same work happens - it is the per-provider overhead, and
 * it makes the ordering explicit through [dependencies] instead of implicit in manifest merge
 * order, which is how the "persistence was enabled after the first read" class of bug happens.
 *
 * This runs eagerly, and it must. setPersistenceEnabled throws if anything has already touched
 * FirebaseDatabase, so it cannot be deferred behind the first frame - being late is not a
 * performance problem here, it is a correctness one. What it buys is being early and cheap
 * rather than early and expensive.
 */
class BackendInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        // Debug builds only, and it has to happen before the first Auth or database access.
        Emulators.connect()

        try {
            // Disk persistence is deliberately skipped against the emulator. A wiped emulator
            // plus a surviving on-disk cache shows conversations that no longer exist, which
            // reads as a rules or backend bug and is neither.
            if (!Emulators.enabled) {
                FirebaseDatabase.getInstance(DATABASE_URL).setPersistenceEnabled(true)
            }
        } catch (alreadyStarted: Throwable) {
            // Only happens on a hot restart where the SDK is still initialised. Safe to ignore:
            // persistence is already on in that process.
        }
    }

    /**
     * No dependencies, and specifically not on Firebase's own initializer.
     *
     * FirebaseApp installs itself through its own provider, which the manifest merger places
     * ahead of androidx.startup because of how the Firebase SDK declares it. Naming a dependency
     * on it here would be a lie in the other direction - App Startup would try to run something
     * it does not own.
     */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * Cold-start fix: get the SharedPreferences XML off the launch path.
 *
 * MainActivity reads AppPreferences synchronously before setContent, on purpose: the saved
 * dark-mode value has to be known for the first frame or the app visibly flashes light and then
 * corrects itself. The cost of that correctness is a blocking disk read sitting directly on the
 * launch path, and SharedPreferences loads its whole XML file on first touch.
 *
 * Touching it here, on a background thread, moves that read off the critical path. The platform's
 * SharedPreferencesImpl already blocks any getter until its load finishes, so by the time the
 * activity asks for a value the file is either loaded or nearly loaded, and the activity's own
 * read returns from memory instead of from disk. No behaviour changes and the ordering guarantee
 * is unaffected - this only starts the same work earlier and elsewhere.
 *
 * Split out from [BackendInitializer] rather than folded into it because the two have opposite
 * requirements: the Firebase work must complete before anything else runs, and this must not
 * block anything at all. Keeping them in one create() invited someone to later make this
 * synchronous "for ordering", which would put the disk read straight back on the main thread.
 */
class PreferencesWarmInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val app = context.applicationContext
        Thread({ runCatching { AppPreferences.get(app) } }, "prefs-warmup").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
