package com.codexce.supportchat.update

import android.content.Context
import com.codexce.supportchat.BuildConfig
import com.codexce.supportchat.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the server whether a newer build exists.
 *
 * Deliberately built on HttpURLConnection rather than by adding a networking library. This makes
 * one GET of a document measured in hundreds of bytes, at most once per cold start; pulling in
 * OkHttp or Retrofit for that would be the largest dependency in the app serving the smallest
 * request in it. The app removed its HTTP client on purpose when it moved to the Firebase SDKs,
 * and this does not bring it back.
 *
 * Every failure path is silent by design. No network, captive portal, DNS still resolving on a
 * cold boot, a half-deployed manifest, malformed JSON — none of these are the user's problem and
 * none of them should produce an error dialog in front of an inbox that works perfectly well. The
 * result is simply UpToDate, and the next launch asks again.
 */
object UpdateChecker {

    /** Short, because this runs at startup. A stalled check must not linger behind the UI. */
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** A manifest is a small JSON document. Anything larger is not one, so stop reading. */
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    sealed interface Result {
        /** Already current, or the check could not be completed. Indistinguishable to the user. */
        data object UpToDate : Result

        data class Available(val manifest: UpdateManifest) : Result
    }

    /**
     * @param force Skips both the "check on startup" preference and the remembered Later choice.
     *   Set from an explicit "Check for updates" tap: someone who asks deserves an answer even
     *   if they dismissed this exact version earlier.
     */
    suspend fun check(context: Context, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val preferences = AppPreferences.get(context)
        if (!force && !preferences.updateOnStartupAtStartup) return@withContext Result.UpToDate

        val url = BuildConfig.UPDATE_MANIFEST_URL
        if (url.isBlank()) return@withContext Result.UpToDate

        val manifest = runCatching { UpdateManifest.parse(fetch(url)) }.getOrNull()
            ?: return@withContext Result.UpToDate

        if (manifest.versionCode <= BuildConfig.VERSION_CODE.toLong()) {
            return@withContext Result.UpToDate
        }

        /*
         * A dismissed version stays dismissed until a newer one appears.
         *
         * Without this, Later means "ask me again in thirty seconds when I next open the app",
         * which is how an update prompt becomes something people learn to tap through without
         * reading. The stored value is the version that was declined, not a boolean, so the very
         * next release prompts again on its own.
         *
         * mandatory ignores it. That flag exists for builds where the old version is actively
         * broken, and in that case the previous choice was made about a different situation.
         */
        if (!force && !manifest.mandatory && preferences.skippedUpdateVersion >= manifest.versionCode) {
            return@withContext Result.UpToDate
        }

        Result.Available(manifest)
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // The manifest changes on every release and is tiny. A cached copy is the one thing
            // that would make this feature quietly stop working after the first check.
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "application/json")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("manifest returned HTTP ${connection.responseCode}")
            }
            return connection.inputStream.use { stream ->
                val bytes = stream.readBytes(MAX_MANIFEST_BYTES)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Reads at most [limit] bytes. A truncated manifest fails JSON parsing, which is the point. */
    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = read(chunk)
            if (read == -1) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > limit) throw IllegalStateException("manifest is implausibly large")
        }
        return buffer.toByteArray()
    }
}
