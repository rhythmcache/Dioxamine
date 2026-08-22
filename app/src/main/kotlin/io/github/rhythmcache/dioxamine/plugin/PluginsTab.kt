package io.github.rhythmcache.dioxamine.plugin

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.BuildConfig
import io.github.rhythmcache.dioxamine.R
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PluginsTab(
    repo: PluginRepository,
    onOpenPlugin: (pluginId: String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val installedPlugins by repo.installedPlugins.collectAsState()

    var infoDialogManifest by remember { mutableStateOf<PluginManifest?>(null) }
    var uninstallConfirmManifest by remember { mutableStateOf<PluginManifest?>(null) }

    val linkColor = MaterialTheme.colorScheme.primary
    val docsPrompt = stringResource(R.string.plugins_docs_prompt)
    val docsLink = stringResource(R.string.plugins_docs_link)

    val docsAnnotatedText = remember(docsPrompt, docsLink, linkColor) {
        buildAnnotatedString {
            append(docsPrompt)
            if (!docsPrompt.endsWith(" ")) {
                append(" ")
            }
            withLink(
                LinkAnnotation.Url(
                    url = BuildConfig.PLUGIN_DOCS_URL,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ),
                ),
            ) {
                append(docsLink)
            }
            append(".")
        }
    }

    val pickZipLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val result = repo.install(uri)
                val message =
                    when (result) {
                        is PluginInstallResult.Installed ->
                            context.getString(R.string.plugins_msg_installed, result.manifest.name)

                        is PluginInstallResult.Updated ->
                            context.getString(R.string.plugins_msg_updated, result.new.name, result.new.version)

                        is PluginInstallResult.UpdateRejected ->
                            context.getString(R.string.plugins_msg_rejected)

                        is PluginInstallResult.Error ->
                            context.getString(R.string.plugins_msg_error, result.message)
                    }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        if (installedPlugins.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.plugins_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.plugins_description),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = docsAnnotatedText,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.plugins_no_plugins),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = docsAnnotatedText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(installedPlugins, key = { it.id }) { manifest ->
                    var menuExpanded by remember { mutableStateOf(false) }

                    val iconBitmap = remember(manifest.id, manifest.icon) {
                        val pluginDir = File(context.filesDir, "plugins/${manifest.id}")
                        val iconFileName = manifest.icon ?: "icon.png"
                        val iconFile = File(pluginDir, iconFileName)
                        if (iconFile.exists() && iconFile.isFile) {
                            try {
                                BitmapFactory.decodeFile(iconFile.absolutePath)?.asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    }

                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPlugin(manifest.id) },
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Left: Plugin Icon / Placeholder
                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap,
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Extension,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            // Center: Title + Version + Description
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = manifest.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = "v${manifest.version}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                if (manifest.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = manifest.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            // Right: 3-dots Menu Button (Vertically centered)
                            Box(
                                modifier = Modifier.padding(start = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.cd_more_options),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.plugin_menu_info)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Info,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            infoDialogManifest = manifest
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.plugin_menu_uninstall),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            uninstallConfirmManifest = manifest
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { pickZipLauncher.launch(arrayOf("application/zip")) },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.plugins_cd_install),
            )
        }
    }

    infoDialogManifest?.let { manifest ->
        AlertDialog(
            onDismissRequest = { infoDialogManifest = null },
            title = {
                Text(
                    text = manifest.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.plugin_info_id, manifest.id),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.plugin_info_version, manifest.version),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val author = manifest.author
                    if (!author.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.plugin_info_author, author),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = stringResource(R.string.plugin_info_entry, manifest.entry),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (manifest.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = manifest.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (manifest.permissions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.plugin_info_permissions, manifest.permissions.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { infoDialogManifest = null }) {
                    Text(text = stringResource(R.string.cd_close))
                }
            },
        )
    }

    uninstallConfirmManifest?.let { manifest ->
        AlertDialog(
            onDismissRequest = { uninstallConfirmManifest = null },
            title = {
                Text(
                    text = stringResource(R.string.plugin_uninstall_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.plugin_uninstall_confirm, manifest.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = manifest
                        uninstallConfirmManifest = null
                        coroutineScope.launch {
                            val success = repo.uninstall(target.id)
                            if (success) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.plugin_msg_uninstalled, target.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.plugin_menu_uninstall),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { uninstallConfirmManifest = null }) {
                    Text(text = stringResource(R.string.btn_deny))
                }
            },
        )
    }
}
