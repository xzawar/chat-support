package com.codexce.supportchat.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder

/**
 * A live map for one address.
 *
 * Google's keyless embed endpoint in a WebView, not the Maps SDK. The SDK would mean a Play
 * Services dependency, a second API key in the manifest, and a key that is visible in the APK
 * and billable if anyone extracts it. output=embed needs none of that and renders the same
 * tiles. The cost is that it is a web view: it needs the network and shows nothing offline.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AddressMap(address: String, modifier: Modifier = Modifier) {
    val query = remember(address) { URLEncoder.encode(address, "UTF-8") }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp)),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Without a client, any tap that resolves to a navigation hands the URL to
                // the system browser mid-scroll.
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The dialog owns the gesture, not the map.
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setOnTouchListener { _, _ -> true }
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                loadUrl("https://maps.google.com/maps?q=$query&z=16&output=embed")
            }
        },
    )
}

/**
 * The detail box for an office address.
 *
 * Offices used to be full cards printed into the Help page, which made that screen a list with
 * two posters stapled to the bottom of it. They are ordinary rows now, and the address and its
 * map appear here when a row is tapped. The hand-off to Maps is something chosen after seeing
 * where the place is, not a surprise.
 */
@Composable
fun AddressDialog(
    title: String,
    address: String,
    onDismiss: () -> Unit,
    /*
     * A share link to the exact pin, when there is one. "Defence Road, Lahore" is not unique
     * enough for a text search to be trusted, so Open in Maps prefers this.
     *
     * The embedded preview below still searches by address: the keyless embed endpoint only
     * accepts a query, and a short link cannot be resolved without a network call.
     */
    mapsUrl: String? = null,
) {
    val context = LocalContext.current
    val query = remember(address) { URLEncoder.encode(address, "UTF-8") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                AddressMap(address = address, modifier = Modifier.padding(bottom = 2.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    mapsUrl
                                        ?: "https://www.google.com/maps/search/?api=1&query=$query",
                                ),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    onDismiss()
                },
            ) { Text("Open in Maps") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
