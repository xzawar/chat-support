package com.codexce.supportchat.update

import org.json.JSONObject

/**
 * What the server publishes about the newest build.
 *
 * The app is sideloaded, so there is no Play Store to ask. The manifest is the substitute: one
 * small JSON document, fetched on startup, describing the build that is currently released.
 *
 * versionCode is the only thing compared. versionName is for humans and is never parsed — comparing
 * "7.0.10" against "7.0.9" as strings puts them in the wrong order, and doing it properly means
 * writing a semver parser to answer a question versionCode already answers exactly.
 *
 * Example manifest:
 * ```json
 * {
 *   "versionCode": 43,
 *   "versionName": "7.0.3",
 *   "apkUrl": "https://keykraftt.com/app/download",
 *   "changelog": "Expired chats are now removed on time.",
 *   "mandatory": false,
 *   "sizeBytes": 24117248
 * }
 * ```
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val changelog: String?,
    /** When true the prompt cannot be dismissed with Later. Reserve it for genuinely broken builds. */
    val mandatory: Boolean,
    /** Optional, for showing a download size before the user commits to it. */
    val sizeBytes: Long?,
) {
    companion object {
        /**
         * Parses and validates. Throws IllegalArgumentException on anything malformed, which the
         * checker turns into a silent failure — a broken manifest must never break app startup.
         */
        fun parse(raw: String): UpdateManifest {
            val json = JSONObject(raw)

            val versionCode = json.optLong("versionCode", -1L)
            require(versionCode > 0L) { "manifest has no usable versionCode" }

            val apkUrl = json.optString("apkUrl").trim()
            /*
             * https only, and it is not negotiable.
             *
             * This URL ends up installing a package. Over http, anyone between the phone and the
             * server chooses which APK that is, and the install prompt would name our app while
             * handing over theirs. Cleartext is also blocked by the platform's default network
             * security config, so an http URL would fail at download time anyway — but failing
             * here, on a check that states the reason, is better than a download error that does
             * not explain itself.
             */
            require(apkUrl.startsWith("https://")) { "apkUrl must be an https URL" }

            val versionName = json.optString("versionName").trim().ifEmpty { versionCode.toString() }
            val changelog = json.optString("changelog").trim().ifEmpty { null }
            val sizeBytes = json.optLong("sizeBytes", 0L).takeIf { it > 0L }

            return UpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl,
                changelog = changelog,
                mandatory = json.optBoolean("mandatory", false),
                sizeBytes = sizeBytes,
            )
        }
    }
}
