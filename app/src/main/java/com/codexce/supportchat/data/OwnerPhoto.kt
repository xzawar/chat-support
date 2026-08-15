package com.codexce.supportchat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The owner's profile picture.
 *
 * Where this is stored, and why it moved.
 *
 * It used to live in the Realtime Database at chats/{tenantId}/ownerPhoto, on the reasoning
 * that the app already writes successfully elsewhere in that subtree. That reasoning was
 * wrong. The RTDB rules whitelist the keys allowed under a tenant node, ownerPhoto is not one
 * of them, and every write was rejected. Worse, the failure handler assumed a rejection meant
 * the account was not the owner - so the owner was told the photo could only be set by the
 * owner, which is why this looked like a permissions bug rather than a schema one.
 *
 * It now lives on the Firestore tenant document as an ownerPhoto field. That document is one
 * the owner demonstrably writes: it is the same document the company name and subscription are
 * written to. No rule change is needed to make this work.
 *
 * Sizing, and why the old numbers were wrong.
 *
 * This used to be 96px on the long edge at a fixed quality of 55, chosen on the claim that an
 * avatar is never drawn above 96dp. That claim does not survive contact with the devices this
 * runs on. 96dp on a 3x screen is 288 physical pixels, so a 96px source was being upscaled
 * threefold before it ever reached the glass - and quality 55 puts visible JPEG blocking into
 * the picture before that upscale even starts. The result was the reported blur. The same
 * value is also now read by the web console, which draws it larger again.
 *
 * The budget is a byte budget, not a pixel one. 20 KB is the ceiling, measured on the JPEG
 * itself rather than on the base64 string, and the encoder walks a quality ladder until it
 * fits. In practice a 256px portrait lands in the 12-18 KB band at quality 84 or better, which
 * is roughly four times the pixels and a far higher quality floor than before, for a value
 * that is still 2% of Firestore's 1 MB document ceiling.
 */
object OwnerPhoto {

    /**
     * Long edge in pixels.
     *
     * 256 rather than 96. This has to survive a 3x density screen at the largest size the
     * picture is drawn at, and it is now also the source the web console renders, where there
     * is no density cap at all.
     */
    private const val MAX_EDGE = 256

    /**
     * The fallback edge, used only if a picture cannot be squeezed under the byte budget at
     * [MAX_EDGE] even at the bottom of the quality ladder.
     *
     * Dropping resolution is the better trade at that point: a 192px image at quality 74 looks
     * cleaner than a 256px one at quality 40, because blocking artefacts are far more visible
     * than a slightly softer scale. This is rare - it takes a very noisy photograph.
     */
    private const val FALLBACK_EDGE = 192

    /**
     * Quality ladder, walked high to low until the result fits [MAX_BYTES].
     *
     * Starts at 92 because most avatars are faces against a plain background and compress far
     * better than the worst case, so there is no reason to spend the whole budget by default.
     * Stops at 62: below roughly 60 the blocking is visible at avatar sizes, which is the exact
     * problem this change exists to fix, and going lower to save bytes would defeat it.
     */
    private val QUALITY_LADDER = intArrayOf(92, 88, 84, 80, 76, 70, 66, 62)

    /**
     * Hard ceiling on the stored JPEG, in bytes, before base64.
     *
     * 20 KB of JPEG is about 26.7 KB once base64 inflates it by a third. That is 2.7% of the
     * Firestore 1 MB document limit, and this document is re-read on every tenant snapshot, so
     * the ceiling is about sync bandwidth rather than about the limit.
     */
    private const val MAX_BYTES = 20_000

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val _photo = MutableStateFlow<String?>(null)
    /** Base64 JPEG, or null when the owner has not set one and the Google photo should win. */
    val photo: StateFlow<String?> = _photo.asStateFlow()

    private var listener: ListenerRegistration? = null
    private var watchedTenant: String? = null

    private fun doc(tenantId: String) = db.collection("tenants").document(tenantId)

    /**
     * Starts mirroring the stored photo into [photo].
     *
     * Idempotent per tenant: calling it again for the same tenant is a no-op, so screens can
     * call it from a LaunchedEffect without stacking listeners. Failures are swallowed - if
     * the rules refuse the read, the right outcome is the Google photo, not an error banner.
     */
    fun observe(tenantId: String) {
        if (tenantId.isBlank() || watchedTenant == tenantId) return
        stop()
        watchedTenant = tenantId
        listener = doc(tenantId).addSnapshotListener { snapshot, error ->
            _photo.value = if (error != null) {
                null
            } else {
                snapshot?.getString("ownerPhoto")?.takeIf { it.isNotBlank() }
            }
        }
    }

    fun stop() {
        listener?.remove()
        listener = null
        watchedTenant = null
    }

    /**
     * Reads [uri], shrinks it, and stores it.
     *
     * @return null on success, or a message to show the owner.
     */
    suspend fun upload(context: Context, tenantId: String, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            if (tenantId.isBlank()) return@withContext "No workspace is loaded yet."

            val encoded = runCatching { encode(context, uri) }
                .getOrElse { return@withContext "That image could not be read." }
                ?: return@withContext "That image could not be read."

            // merge, not set: this document also holds the workspace name, plan and status,
            // and a plain set would delete all of it.
            runCatching {
                doc(tenantId).set(mapOf("ownerPhoto" to encoded), SetOptions.merge()).await()
            }.fold(
                onSuccess = { null },
                onFailure = { "Could not save the photo. Try again." },
            )
        }

    /** Removes the override so the Google photo shows again. */
    suspend fun clear(tenantId: String): String? = withContext(Dispatchers.IO) {
        if (tenantId.isBlank()) return@withContext null
        runCatching {
            doc(tenantId).update("ownerPhoto", FieldValue.delete()).await()
        }.fold(
            onSuccess = { null },
            onFailure = { "Could not remove the photo. Try again." },
        )
    }

    /**
     * Decodes to a bitmap for display. Returns null rather than throwing on anything
     * malformed, because a corrupt value should degrade to the Google photo and not crash the
     * account page.
     *
     * ARGB_8888 is requested explicitly. BitmapFactory is entitled to hand back RGB_565 for an
     * opaque JPEG, and 565 quantises to 5 and 6 bits per channel - which puts visible banding
     * across exactly the smooth skin-tone gradients an avatar is mostly made of. That banding
     * reads as "blurry and cheap" just as much as the old compression did, and it is free to
     * avoid at this size.
     */
    fun decode(data: String?): Bitmap? {
        if (data.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    /**
     * Two-pass decode, then a quality search.
     *
     * inSampleSize first so a 12 megapixel camera shot is never fully decoded into memory just
     * to be thrown away - that is the step that OOMs on low-end devices. Only then is the
     * already-small bitmap scaled to the exact edge.
     *
     * The sample factor deliberately stops one power of two ABOVE the target rather than at
     * it. inSampleSize is a fast box decimation with no filtering; letting it get all the way
     * down to the target edge and doing no filtered scale afterwards is a large part of why
     * the old output looked soft. Landing at 2x and finishing with a filtered
     * createScaledBitmap gives a properly resampled result.
     */
    private fun encode(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null

        var sample = 1
        while (longEdge / sample > MAX_EDGE * 2) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // Full edge first. Only if nothing on the ladder fits is resolution given up.
        val bytes = compressWithin(decoded, MAX_EDGE)
            ?: compressWithin(decoded, FALLBACK_EDGE)

        decoded.recycle()

        return bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    /**
     * Scales [source] to [edge] and returns the highest-quality JPEG that fits [MAX_BYTES],
     * or null if even the bottom of the ladder is too large at this size.
     *
     * The scale is done once and the ladder re-encodes the same bitmap, so this costs one
     * resample and a handful of compressions of an image that is at most 256px on its long
     * edge. That is a few milliseconds, and it happens on Dispatchers.IO behind a picker the
     * user has just returned from, never on a frame that is being drawn.
     */
    private fun compressWithin(source: Bitmap, edge: Int): ByteArray? {
        val scale = edge.toFloat() / maxOf(source.width, source.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                // Bilinear filtering. Without this the downscale is a nearest-neighbour
                // point sample, which aliases hard edges and is the other half of the
                // softness-plus-crunchiness the old pipeline produced.
                true,
            )
        } else {
            source
        }

        try {
            for (quality in QUALITY_LADDER) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                if (bytes.size <= MAX_BYTES) return bytes
            }
            return null
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
