@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.data.api.ApiException
import com.codexce.supportchat.data.api.SupportApi
import com.codexce.supportchat.data.api.Website
import com.codexce.supportchat.data.api.WebsiteSecret
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.debounced
import com.codexce.supportchat.ui.components.EmptyState
import com.codexce.supportchat.ui.components.ErrorBanner
import com.codexce.supportchat.ui.components.CardListSkeleton
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.StatusPill
import com.codexce.supportchat.ui.components.SupportCard
import kotlinx.coroutines.launch
import com.codexce.supportchat.ui.theme.supportButtonColors

/**
 * Website registration.
 *
 * Pairing codes are gone. A website is now a row the backend owns, and the only secret is the
 * API key it mints when the site is registered. That key is returned exactly once, in the
 * response to the create call, and is never stored in a readable form afterwards - the backend
 * keeps a hash. So the copy button below is genuinely the only chance to take the key, and the
 * screen says so rather than implying it can be looked up again later.
 */
@Composable
fun LinkWebsiteScreen(
    signedInEmail: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var websites by remember { mutableStateOf<List<Website>?>(null) }
    var domain by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The freshly created site, held in memory only, for as long as this screen lives. Only its
    // short link code is ever put on screen; the raw key inside it is never rendered.
    var revealed by remember { mutableStateOf<WebsiteSecret?>(null) }

    suspend fun reload() {
        runCatching { SupportApi.websites() }
            .onSuccess { websites = it }
            .onFailure { failure ->
                websites = emptyList()
                error = (failure as? ApiException)?.message
                    ?: failure.localizedMessage
                    ?: "Could not load your websites"
            }
    }

    /*
     * One active domain at a time. The backend already refuses a second one - createWebsite
     * throws website_limit_reached inside the transaction - but letting someone fill in a form
     * and press Register only to be told no is a poor way to communicate a hard rule. The form
     * is closed while a live site exists and opens again the moment Remove deactivates it.
     */
    val hasActiveSite = websites?.any { it.active } == true

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("Link your website", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState()),
        ) {
            error?.let { message ->
                ErrorBanner(message = message, onDismiss = { error = null })
            }

            GroupGap()
            Column(
                Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SupportCard {
                    Text(
                        text = if (hasActiveSite) {
                            "You already have an active domain. Remove it below before " +
                                "registering a different one."
                        } else {
                            "Register the one domain the widget will run on. An account can " +
                                "link a single website, and requests from any other origin are " +
                                "refused, so the key alone is not enough to use it somewhere else."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("Domain") },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        enabled = !working && !hasActiveSite,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                    Button(
                        colors = supportButtonColors(),
                        onClick = debounced {
                            if (working) return@debounced
                            working = true
                            error = null
                            scope.launch {
                                runCatching { SupportApi.createWebsite(domain.trim()) }
                                    .onSuccess { created ->
                                        revealed = created
                                        domain = ""
                                        reload()
                                    }
                                    .onFailure { failure ->
                                        error = (failure as? ApiException)?.message
                                            ?: failure.localizedMessage
                                            ?: "Could not register that domain"
                                    }
                                working = false
                            }
                        },
                        enabled = !working && domain.isNotBlank() && !hasActiveSite,
                    ) {
                        if (working) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Register")
                        }
                    }
                }

                /*
                 * The verification code, and nothing else.
                 *
                 * This card used to print the raw sk_live_ key, the site id and a warning about
                 * key handling. None of that is the owner's problem: the plugin never asks for
                 * the key, it asks for this short code and trades it for the key itself over the
                 * API. Showing the key here only created something worth leaking, and the site
                 * id is an internal identifier with no use outside a support ticket.
                 *
                 * The key still exists, and the summary row below still proves it exists - as a
                 * fingerprint, never in plaintext.
                 */
                revealed?.let { created ->
                    val code = created.linkCode
                    SupportCard {
                        Text(
                            text = "Your verification code",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.padding(top = 8.dp))
                        Text(
                            // A word rather than a dash. This slot is empty only while the code
                            // is still being generated, and "Generating" says that; a dash just
                            // looks like the code came back blank.
                            text = code ?: "Generating",
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.padding(top = 8.dp))
                        Text(
                            text = "Enter this code in the widget on " +
                                (created.domain ?: "your site") +
                                " to finish linking it. It is valid for a limited time; if it " +
                                "expires, rotate the key below to get a new one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(top = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                colors = supportButtonColors(),
                                onClick = debounced { code?.let { copyToClipboard(context, it) } },
                                enabled = code != null,
                            ) { Text("Copy code") }
                            TextButton(onClick = debounced { revealed = null }) { Text("Done") }
                        }
                    }
                }
            }

            GroupGap()
            val current = websites
            when {
                current == null -> CardListSkeleton(cards = 2, cardHeight = 88.dp)

                current.isEmpty() -> EmptyState(
                    icon = AppIcons.Cloud,
                    title = "No sites yet",
                    message = "Register a domain above to get the key the widget needs.",
                )

                else -> Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (site in current) {
                        WebsiteCard(
                            site = site,
                            busy = working,
                            onRotate = {
                                working = true
                                error = null
                                scope.launch {
                                    runCatching { SupportApi.rotateWebsiteKey(site.id) }
                                        .onSuccess { rotated ->
                                            revealed = rotated
                                            reload()
                                        }
                                        .onFailure { failure ->
                                            error = (failure as? ApiException)?.message
                                                ?: "Could not rotate that key"
                                        }
                                    working = false
                                }
                            },
                            onDelete = {
                                working = true
                                error = null
                                scope.launch {
                                    runCatching { SupportApi.deleteWebsite(site.id) }
                                        .onFailure { failure ->
                                            error = (failure as? ApiException)?.message
                                                ?: "Could not remove that site"
                                        }
                                    reload()
                                    working = false
                                }
                            },
                        )
                    }
                }
            }

            Text(
                text = "Signed in as " + (signedInEmail ?: "an unknown account") +
                    ". The widget calls the handshake endpoint with this key, and the reply " +
                    "carries a short-lived token for that one conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun WebsiteCard(
    site: Website,
    busy: Boolean,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
) {
    SupportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = site.domain,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusPill(text = if (site.active) "Active" else "Disabled", emphasised = site.active)
        }
        Spacer(Modifier.padding(top = 4.dp))
        Text(
            // Masked by default. The fingerprint is enough to tell two keys apart and to confirm
            // one was issued, without ever putting the key itself on screen.
            text = site.keyFingerprint?.let { "Key \u2022\u2022\u2022\u2022 \u2022\u2022\u2022\u2022 " + it }
                ?: "No key issued yet",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = debounced(onRotate), enabled = !busy) { Text("Rotate key") }
            TextButton(onClick = debounced(onDelete), enabled = !busy) { Text("Remove") }
        }
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("API key", value))
}
