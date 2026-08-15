package com.codexce.supportchat.data

import com.codexce.supportchat.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Points the app at the local Firebase emulator suite instead of the real project.
 *
 * Debug builds only: USE_EMULATORS is a BuildConfig field that is false for release, so a shipped
 * APK cannot be talked into pointing at a development machine.
 *
 * There is no functions emulator here any more. The backend is Auth + Firestore + Realtime
 * Database, and all three run on the free Spark plan, so the emulator is now only useful for
 * testing rules changes before deploying them.
 *
 * The host is 10.0.2.2 by default, which is how the Android emulator reaches the host machine's
 * localhost. On a physical device that address means nothing — override EMULATOR_HOST in
 * app/build.gradle.kts with your machine's LAN address.
 */
object Emulators {

    /** Debug builds only. */
    val enabled: Boolean get() = BuildConfig.USE_EMULATORS

    /** Host running `firebase emulators:start`, as seen from this device. */
    val host: String get() = BuildConfig.EMULATOR_HOST

    private const val AUTH_PORT = 9099
    private const val DATABASE_PORT = 9000
    private const val FIRESTORE_PORT = 8080

    /**
     * Call once, before anything touches Auth, Firestore or the database. All three SDKs throw if
     * they are redirected after first use, which is why this runs from Application.onCreate and
     * nowhere else.
     */
    fun connect() {
        if (!enabled) return

        FirebaseAuth.getInstance().useEmulator(host, AUTH_PORT)
        FirebaseDatabase.getInstance(DATABASE_URL).useEmulator(host, DATABASE_PORT)
        FirebaseFirestore.getInstance().useEmulator(host, FIRESTORE_PORT)
    }
}
