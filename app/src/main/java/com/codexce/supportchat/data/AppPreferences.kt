package com.codexce.supportchat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import com.codexce.supportchat.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/*
 * IconVariant used to live here, alongside a switcher that enabled and disabled the two
 * activity-alias entries in the manifest. All of it is gone: the screen, the setting, the
 * stored preference and the switcher. Toggling an alias force-stops the process, so choosing an
 * icon killed the app mid-tap, and on some launchers left an empty tile behind.
 *
 * The manifest aliases themselves are deliberately left in place and untouched. Exactly one is
 * enabled and it is what the launcher currently points at; removing it would uninstall the home
 * screen shortcut on upgrade. Nothing in the app writes to them any more.
 */

/** Bundled chat wallpapers. Custom carries a content URI instead of a drawable. */
enum class WallpaperOption(
    val key: String,
    val label: String,
    @DrawableRes val drawable: Int?,
) {
    None("none", "None", null),
    One("w1", "Dusk", R.drawable.wallpaper_1),
    Two("w2", "Paper", R.drawable.wallpaper_2),
    Three("w3", "Grain", R.drawable.wallpaper_3),
    Four("w4", "Bloom", R.drawable.wallpaper_4),
    Five("w5", "Dune", R.drawable.wallpaper_5),
    Custom("custom", "From gallery", null),
}

data class WallpaperSelection(
    val option: WallpaperOption = WallpaperOption.None,
    val customUri: String? = null,
) {
    val isSet: Boolean
        get() = option != WallpaperOption.None &&
            (option != WallpaperOption.Custom || !customUri.isNullOrBlank())
}

/**
 * SharedPreferences rather than DataStore, deliberately.
 *
 * The dark-mode value has to be known before the first frame composes, and the saved value is
 * read synchronously in the initialisers below. A DataStore Flow would emit its first value
 * one frame late, which is exactly how a "persisted" setting ends up looking like it reset to
 * light on every launch.
 *
 * Writes use apply() rather than commit(). commit() writes the whole XML file to disk
 * synchronously on whatever thread called it, which here is always the main thread, on a user
 * tap. apply() updates the in-memory map immediately -- so the very next read, including the
 * StateFlow emission on the line below each write, already sees the new value -- and hands the
 * disk write to a background thread. The ordering guarantee callers rely on is unchanged.
 *
 * The durability argument for commit() does not survive inspection. apply()'s queued write is
 * flushed by the platform before onPause() returns and before the process is allowed to die
 * normally, so the only case it loses to commit() is an abrupt kill in the few milliseconds
 * after a tap. Losing a dark-mode toggle in that window is not worth a blocking disk write on
 * every settings interaction.
 */
class AppPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _darkTheme = MutableStateFlow(prefs.getBoolean(KEY_DARK_THEME, false))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _wallpaper = MutableStateFlow(readWallpaper())
    val wallpaper: StateFlow<WallpaperSelection> = _wallpaper.asStateFlow()

    private val _permissionsDone = MutableStateFlow(prefs.getBoolean(KEY_PERMISSIONS_DONE, false))

    /** Whether the one-time notification setup has been walked through. */
    val permissionsDone: StateFlow<Boolean> = _permissionsDone.asStateFlow()

    private val _keepConnected = MutableStateFlow(prefs.getBoolean(KEY_KEEP_CONNECTED, true))

    /** Whether the always-connected foreground service is wanted. On by default. */
    val keepConnected: StateFlow<Boolean> = _keepConnected.asStateFlow()

    /** Readable without collecting, for the service's own start guard. */
    val keepConnectedAtStartup: Boolean get() = _keepConnected.value

    private val _updateOnStartup = MutableStateFlow(prefs.getBoolean(KEY_UPDATE_ON_STARTUP, true))

    /**
     * Whether to look for a newer build on launch. On by default.
     *
     * Default-on because this app is not installed from Play: nothing else will ever tell the user
     * a fix exists. An install that opts out silently stays on whatever version it happens to have
     * forever, so the opt-out is offered but not assumed.
     */
    val updateOnStartup: StateFlow<Boolean> = _updateOnStartup.asStateFlow()

    /** Read synchronously by the startup check, which runs before any collector exists. */
    val updateOnStartupAtStartup: Boolean get() = _updateOnStartup.value

    /**
     * The highest versionCode the user has dismissed with "Later".
     *
     * Stored as the version number rather than a boolean so a dismissal expires by itself: the
     * comparison is `skipped >= candidate`, so the next release is offered again without anything
     * needing to reset this. A boolean would have to be cleared by whoever publishes the update,
     * and forgetting once means a permanently silenced updater.
     *
     * No StateFlow: nothing observes this, it is only read inside the check.
     */
    val skippedUpdateVersion: Long get() = prefs.getLong(KEY_SKIPPED_UPDATE, 0L)

    /** Value available synchronously for the very first composition. */
    val darkThemeAtStartup: Boolean = _darkTheme.value

    /**
     * Stable per-install id used as the FCM token key, so a rotated token replaces the old entry
     * under tenants/{tenantId}/devices instead of leaving a dead token behind.
     */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString()
        .also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
        _darkTheme.value = enabled
    }

    fun setPermissionsDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_PERMISSIONS_DONE, done).apply()
        _permissionsDone.value = done
    }

    fun setKeepConnected(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_CONNECTED, enabled).apply()
        _keepConnected.value = enabled
    }

    fun setUpdateOnStartup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_ON_STARTUP, enabled).apply()
        _updateOnStartup.value = enabled
    }

    /**
     * Records a dismissed version.
     *
     * Only ever moves forward. Without the max(), a downgrade in the manifest - a release pulled
     * and replaced with a lower versionCode - would lower the bar and re-prompt for versions the
     * user already declined.
     */
    fun setSkippedUpdateVersion(versionCode: Long) {
        val highest = maxOf(versionCode, skippedUpdateVersion)
        prefs.edit().putLong(KEY_SKIPPED_UPDATE, highest).apply()
    }

    fun setWallpaper(option: WallpaperOption, customUri: String? = null) {
        prefs.edit()
            .putString(KEY_WALLPAPER, option.key)
            .putString(KEY_WALLPAPER_URI, customUri)
            .apply()
        _wallpaper.value = WallpaperSelection(option, customUri)
    }

    private fun readWallpaper(): WallpaperSelection {
        val storedKey = prefs.getString(KEY_WALLPAPER, null)
        val option = WallpaperOption.entries.firstOrNull { it.key == storedKey }
            ?: WallpaperOption.None
        return WallpaperSelection(option, prefs.getString(KEY_WALLPAPER_URI, null))
    }

    companion object {
        private const val PREFS_NAME = "support_chat_prefs"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_WALLPAPER = "chat_wallpaper"
        private const val KEY_WALLPAPER_URI = "chat_wallpaper_uri"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PERMISSIONS_DONE = "permissions_done"
        private const val KEY_KEEP_CONNECTED = "keep_connected"
        private const val KEY_UPDATE_ON_STARTUP = "update_on_startup"
        private const val KEY_SKIPPED_UPDATE = "skipped_update_version"

        @Volatile
        private var instance: AppPreferences? = null

        fun get(context: Context): AppPreferences =
            instance ?: synchronized(this) {
                instance ?: AppPreferences(context).also { instance = it }
            }
    }
}
