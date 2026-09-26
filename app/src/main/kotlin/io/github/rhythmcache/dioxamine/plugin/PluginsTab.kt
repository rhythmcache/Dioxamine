package io.github.rhythmcache.dioxamine.plugin

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import io.github.rhythmcache.dioxamine.core.formatFileSize
import kotlinx.coroutines.launch
import java.io.File

enum class PluginTopTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    INSTALLED(R.string.plugins_tab_installed, Icons.Filled.Extension),
    BROWSE(R.string.plugins_tab_browse, Icons.Filled.Search),
}

@Composable
fun PluginsTab(
    repo: PluginRepository,
    onOpenPlugin: (pluginId: String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val installedPlugins by repo.installedPlugins.collectAsState()
    val availableUpdates by repo.availableUpdates.collectAsState()
    val updatingPluginIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(installedPlugins) {
        repo.checkForUpdates()
    }

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

    var selectedTopTab by rememberSaveable { mutableStateOf(PluginTopTab.INSTALLED) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.tab_plugins),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PluginTopTab.entries.forEach { tab ->
                    val isSelected = selectedTopTab == tab
                    val backgroundColor by animateColorAsState(
                        targetValue =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        label = "tab_background",
                    )
                    val contentColor by animateColorAsState(
                        targetValue =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        label = "tab_content",
                    )
                    Surface(
                        onClick = { selectedTopTab = tab },
                        shape = RoundedCornerShape(8.dp),
                        color = backgroundColor,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTopTab) {
                PluginTopTab.INSTALLED -> {
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
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenPlugin(manifest.id) }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
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
                                                .size(50.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(50.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.Extension,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(14.dp))

                                // Center: Title + Version & Author + Description
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = manifest.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                        ) {
                                            Text(
                                                text = "v${manifest.version}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                        if (!manifest.author.isNullOrBlank()) {
                                            Text(
                                                text = stringResource(R.string.plugin_info_author, manifest.author),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }

                                    if (manifest.description.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
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

                            val updateInfo = availableUpdates[manifest.id]
                            if (updateInfo != null && updateInfo.versionCode > manifest.versionCode) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                                )
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.SystemUpdate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.plugin_update_available, updateInfo.version),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        val changelogUrl = updateInfo.changelog?.trim()
                                        val isHttpChangelog = !changelogUrl.isNullOrBlank() &&
                                            (changelogUrl.startsWith("http://", ignoreCase = true) || changelogUrl.startsWith("https://", ignoreCase = true))
                                        if (isHttpChangelog) {
                                            TextButton(
                                                onClick = {
                                                    runCatching {
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(changelogUrl))
                                                        context.startActivity(intent)
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp),
                                                shape = RoundedCornerShape(8.dp),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.plugin_btn_changelog),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }

                                        val isUpdating = updatingPluginIds.contains(manifest.id)
                                        Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                updatingPluginIds.add(manifest.id)
                                                val result = repo.downloadAndInstallUpdate(manifest, updateInfo)
                                                updatingPluginIds.remove(manifest.id)
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
                                        },
                                        enabled = !isUpdating,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        if (isUpdating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.plugin_btn_updating),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.plugin_btn_update),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    }
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
                }

                PluginTopTab.BROWSE -> {
                    PluginBrowsePlaceholder()
                }
            }
        }
    }

    infoDialogManifest?.let { manifest ->
        val dialogIconBitmap = remember(manifest.id, manifest.icon) {
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

        val pluginDirSize = remember(manifest.id) {
            val dir = File(context.filesDir, "plugins/${manifest.id}")
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                0L
            }
        }

        AlertDialog(
            onDismissRequest = { infoDialogManifest = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (dialogIconBitmap != null) {
                        Image(
                            bitmap = dialogIconBitmap,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(44.dp),
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
                    Column {
                        Text(
                            text = manifest.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                text = "v${manifest.version} (${manifest.versionCode})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (manifest.description.isNotBlank()) {
                        Text(
                            text = manifest.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }

                    Text(
                        text = stringResource(R.string.plugin_info_id, manifest.id),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Text(
                        text = stringResource(R.string.plugin_info_version, "${manifest.version} (${manifest.versionCode})"),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    val author = manifest.author
                    if (!author.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.plugin_info_author, author),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (pluginDirSize > 0L) {
                        Text(
                            text = stringResource(R.string.plugin_info_size, formatFileSize(pluginDirSize)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    val homepage = manifest.homepage
                    if (!homepage.isNullOrBlank()) {
                        val homepageText = stringResource(R.string.plugin_info_homepage, homepage)
                        val prefix = homepageText.substringBefore(homepage)
                        val homepageAnnotated =
                            remember(homepage, prefix, linkColor) {
                                buildAnnotatedString {
                                    append(prefix)
                                    withLink(
                                        LinkAnnotation.Url(
                                            url = homepage,
                                            styles =
                                                TextLinkStyles(
                                                    style =
                                                        SpanStyle(
                                                            color = linkColor,
                                                            textDecoration = TextDecoration.Underline,
                                                            fontWeight = FontWeight.Medium,
                                                        ),
                                                ),
                                        ),
                                    ) {
                                        append(homepage)
                                    }
                                }
                            }
                        Text(
                            text = homepageAnnotated,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (manifest.minAppVersionCode > 1) {
                        Text(
                            text = stringResource(R.string.plugin_info_min_app_version, manifest.minAppVersionCode),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (manifest.permissions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text =
                                stringResource(
                                    R.string.plugin_info_permissions,
                                    manifest.permissions.allList().joinToString(", "),
                                ),
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

@Composable
private fun PluginBrowsePlaceholder() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.plugins_browse_placeholder_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.plugins_browse_placeholder_desc),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = stringResource(R.string.plugins_browse_coming_soon),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

