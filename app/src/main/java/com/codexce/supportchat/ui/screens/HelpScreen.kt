@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codexce.supportchat.R
import com.codexce.supportchat.ui.components.AddressDialog
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.GroupDivider
import com.codexce.supportchat.ui.components.GroupGap
import com.codexce.supportchat.ui.components.InfoBox
import com.codexce.supportchat.ui.components.SettingsGroup
import com.codexce.supportchat.ui.components.SettingsRow

private val TintCall = Color(0xFF1D6FE0)
private val TintGreen = Color(0xFF25D366)
private val TintOrange = Color(0xFFFF9F0A)
private val TintPlum = Color(0xFF7C3AED)
private val TintBronze = Color(0xFFE0A126)

private const val HEAD_OFFICE = "Shop #2 Musa Market, Defence Road, Lahore"
private const val SITE_OFFICE = "5900 Balcones Drive STE 100, Austin, TX 78731"

// Share links to the exact pins, used for the Open in Maps hand-off.
private const val HEAD_OFFICE_MAP = "https://maps.app.goo.gl/HZsrEmrbqLoirb3Q6"
private const val SITE_OFFICE_MAP = "https://maps.app.goo.gl/TFrkFaVoqoMyFcBG7"

/**
 * Help and contact.
 *
 * Everything is a row now. The offices used to be full cards with a map baked into the page,
 * which made this screen two different things stacked on each other: a list, and then some
 * posters. They are rows like everything else, and the address and its map appear in a box
 * when the row is tapped.
 *
 * No subtitles. A contact row carries its value on the right, as a value, not as a caption
 * underneath the title.
 *
 * The three contact rows hand off directly, because there is nothing to read first: tapping
 * Phone means you want to dial. Each hand-off stays wrapped, because a device with no dialler,
 * no mail client or no WhatsApp would otherwise throw ActivityNotFoundException and take the
 * screen down with it.
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var office by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun open(uri: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    office?.let { picked ->
        AddressDialog(
            title = picked.first,
            address = picked.second,
            onDismiss = { office = null },
            mapsUrl = if (picked.second == HEAD_OFFICE) HEAD_OFFICE_MAP else SITE_OFFICE_MAP,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("Help", style = MaterialTheme.typography.titleLarge) },
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
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.keykraft_logo),
                    contentDescription = "Keykraft",
                    modifier = Modifier.size(84.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Keykraft",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            SettingsGroup {
                /*
                 * The dialling number, back where it was. It was lost when the office cards
                 * became rows: WhatsApp carries the same digits, but tapping it opens a chat,
                 * and someone who wants to phone should not have to copy a number out of a
                 * messaging app.
                 */
                SettingsRow(
                    icon = AppIcons.Phone,
                    title = "Phone",
                    tint = TintCall,
                    onClick = { open("tel:+923025008869") },
                    trailing = {
                        Text(
                            text = "+92 302 5008869",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.WhatsApp,
                    title = "WhatsApp",
                    tint = TintGreen,
                    onClick = { open("https://wa.me/923201848137") },
                    trailing = {
                        Text(
                            text = "+92 320 1848137",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.MailOutline,
                    title = "Email",
                    tint = TintOrange,
                    onClick = { open("mailto:info@keykraftt.com") },
                    trailing = {
                        Text(
                            text = "info@keykraftt.com",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            GroupGap()
            SettingsGroup {
                SettingsRow(
                    icon = AppIcons.Globe,
                    title = "Head office",
                    tint = TintPlum,
                    onClick = { office = "Head office" to HEAD_OFFICE },
                )
                GroupDivider()
                SettingsRow(
                    icon = AppIcons.Globe,
                    title = "Site office",
                    tint = TintBronze,
                    onClick = { office = "Site office" to SITE_OFFICE },
                )
            }

            GroupGap()
            InfoBox(
                text = "WhatsApp and email are answered around the clock. There is no ticket " +
                    "number to quote - the account you are signed in with identifies your " +
                    "workspace.",
            )

            GroupGap()
            InfoBox(
                text = "Support Chat is built by Keykraft. Conversations older than 24 hours " +
                    "are purged automatically unless you mark them as kept.",
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Support Chat by Keykraft",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
        }
    }
}
