package com.codexce.supportchat.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads the APK and hands it to the package installer.
 *
 * DownloadManager rather than a manual stream copy, for three reasons that all matter here: it
 * survives the app being backgrounded mid-download, it resumes across connectivity changes, and it
 * already knows how to follow the redirect chain a GitHub release asset produces (your domain ->
 * github.com -> objects.githubusercontent.com).
 *
 * The file lands in the app's own external files directory. Nothing outside the app can read it
 * there, no storage permission is involved on any API level, and the system removes it when the app
 * is uninstalled instead of leaving stale APKs in the user's Downloads folder forever.
 */
object UpdateInstaller {

    /** Matches the authority declared for FileProvider in AndroidManifest.xml. */
    private const val AUTHORITY_SUFFIX = ".updates"

    private const val APK_MIME = "application/vnd.android.package-archive"

    private const val FILE_NAME = "update.apk"

    private const val POLL_INTERVAL_MS = 400L

    sealed interface Outcome {
        data class Ready(val file: File) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Android 8+ requires the user to allow this specific app to install packages, and it is a
     * Settings screen rather than a runtime dialog. Checked before downloading, so the permission
     * is asked for while the user still has the prompt in mind rather than after a transfer they
     * then cannot use.
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the "install unknown apps" screen for this app. No-op below API 26. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Enqueues the download and suspends until it finishes, reporting progress as 0f..1f.
     *
     * Progress is polled rather than observed. DownloadManager's only push notification is a
     * broadcast on completion; byte counts are exclusively available by querying its cursor, so the
     * poll is not a shortcut, it is the API. 400ms is cheap and still looks smooth.
     *
     * A null progress value means the server sent no Content-Length - common behind a redirecting
     * proxy - and the caller should show an indeterminate bar rather than invent a percentage.
     */
    suspend fun download(
        context: Context,
        manifest: UpdateManifest,
        onProgress: (Float?) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: return@withContext Outcome.Failed("Downloads are unavailable on this device.")

        // A previous attempt's file must never be installed by mistake if this one fails early.
        val target = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            FILE_NAME,
        )
        runCatching { if (target.exists()) target.delete() }

        val request = DownloadManager.Request(Uri.parse(manifest.apkUrl))
            .setTitle("Support Chat ${manifest.versionName}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            // Metered networks are allowed: the whole point is that the user asked for this now.
            // Roaming is not, because a 25MB APK on roaming data is a bill, not a feature.
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val id = runCatching { manager.enqueue(request) }.getOrElse { error ->
            return@withContext Outcome.Failed(error.message ?: "Could not start the download.")
        }

        try {
            while (true) {
                val status = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (!cursor.moveToFirst()) null else readStatus(cursor)
                } ?: return@withContext Outcome.Failed("The download disappeared.")

                when (status.state) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onProgress(1f)
                        return@withContext if (target.exists() && target.length() > 0L) {
                            Outcome.Ready(target)
                        } else {
                            Outcome.Failed("The downloaded file is missing.")
                        }
                    }

                    DownloadManager.STATUS_FAILED ->
                        return@withContext Outcome.Failed(reasonText(status.reason))

                    else -> {
                        onProgress(status.fraction)
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Outcome.Failed("Unreachable.")
        } finally {
            // Removes the row from DownloadManager's list without deleting the file, so cancelled
            // or crashed attempts do not accumulate entries in the system download UI.
            runCatching { manager.remove(id) }
        }
    }

    private data class Status(val state: Int, val reason: Int, val fraction: Float?)

    private fun readStatus(cursor: Cursor): Status {
        val state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        val total = cursor.getLong(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
        )
        val soFar = cursor.getLong(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
        )
        // total is -1 until the response headers arrive, and stays -1 when there is no
        // Content-Length at all. Either way there is no honest percentage to report.
        val fraction = if (total > 0L) (soFar.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
        return Status(state, reason, fraction)
    }

    private fun reasonText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space for the update."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage is unavailable."
        DownloadManager.ERROR_CANNOT_RESUME -> "The download could not be resumed."
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "The download was interrupted."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "The download link redirects too many times."
        else -> "The download failed. Try again."
    }

    /**
     * Launches the system installer.
     *
     * A content:// URI from FileProvider, never a file:// one. Passing a file URI across an activity
     * boundary has thrown FileUriExposedException since API 24, which is this app's minSdk, so there
     * is no legacy path worth keeping. The read grant is what lets the installer - a different
     * process - open a file inside our private directory.
     */
    fun install(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
