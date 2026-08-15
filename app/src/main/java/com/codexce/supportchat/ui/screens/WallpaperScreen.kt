@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexce.supportchat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.codexce.supportchat.data.AppPreferences
import com.codexce.supportchat.data.WallpaperOption
import com.codexce.supportchat.ui.components.AppIcons
import com.codexce.supportchat.ui.components.BackButton
import com.codexce.supportchat.ui.components.safeClickable
import com.codexce.supportchat.ui.components.debounced

/**
 * Chat wallpaper picker: five bundled backgrounds plus anything from the gallery.
 *
 * The gallery path uses OpenDocument and immediately takes a persistable read permission on the
 * returned URI. Without that the chosen image renders once and then fails after a reboot, which
 * looks exactly like the selection not persisting when in fact only the permission lapsed.
 */
@Composable
fun WallpaperScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val selection by preferences.wallpaper.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            preferences.setWallpaper(WallpaperOption.Custom, uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("Chat wallpaper", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = WallpaperOption.entries.filter { it != WallpaperOption.Custom },
                    key = { it.key },
                ) { option ->
                    WallpaperTile(
                        label = option.label,
                        selected = selection.option == option,
                        onClick = debounced { preferences.setWallpaper(option) },
                    ) {
                        val drawable = option.drawable
                        if (drawable != null) {
                            Image(
                                painter = painterResource(drawable),
                                contentDescription = option.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                        }
                    }
                }

                val customUri = selection.customUri
                if (selection.option == WallpaperOption.Custom && !customUri.isNullOrBlank()) {
                    items(items = listOf(customUri), key = { it }) { uri ->
                        WallpaperTile(
                            label = "Yours",
                            selected = true,
                            onClick = debounced { preferences.setWallpaper(WallpaperOption.Custom, uri) },
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Selected wallpaper",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                OutlinedButton(
                    onClick = debounced { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose from gallery")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "The wallpaper sits behind the message bubbles in a conversation and " +
                        "is remembered across restarts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WallpaperTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .safeClickable(onClick = onClick),
        ) {
            preview()
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(AppIcons.Check),
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
