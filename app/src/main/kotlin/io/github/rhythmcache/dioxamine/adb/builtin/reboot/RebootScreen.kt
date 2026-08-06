package io.github.rhythmcache.dioxamine.adb.builtin.reboot

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile
import io.github.rhythmcache.dioxamine.core.executeShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AdbRebootOption(
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int,
    val shellCommand: String,
)

private val rebootOptions = listOf(
    AdbRebootOption(
        icon = Icons.Filled.RestartAlt,
        titleRes = R.string.adb_reboot_system_title,
        descriptionRes = R.string.adb_reboot_system_desc,
        shellCommand = "reboot",
    ),
    AdbRebootOption(
        icon = Icons.Filled.SettingsApplications,
        titleRes = R.string.adb_reboot_bootloader_title,
        descriptionRes = R.string.adb_reboot_bootloader_desc,
        shellCommand = "reboot bootloader",
    ),
    AdbRebootOption(
        icon = Icons.Filled.Build,
        titleRes = R.string.adb_reboot_recovery_title,
        descriptionRes = R.string.adb_reboot_recovery_desc,
        shellCommand = "reboot recovery",
    ),
    AdbRebootOption(
        icon = Icons.Filled.Bolt,
        titleRes = R.string.adb_reboot_fastboot_title,
        descriptionRes = R.string.adb_reboot_fastboot_desc,
        shellCommand = "reboot fastboot",
    ),
    AdbRebootOption(
        icon = Icons.Filled.PowerSettingsNew,
        titleRes = R.string.adb_reboot_shutdown_title,
        descriptionRes = R.string.adb_reboot_shutdown_desc,
        shellCommand = "cmd power shutdown || svc power shutdown || reboot -p || setprop sys.powerctl shutdown",
    ),
)

@Composable
fun RebootTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.adb_reboot_tile_title),
        description = stringResource(R.string.adb_reboot_tile_desc),
        icon = Icons.Filled.RestartAlt,
        enabled = isConnected,
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RebootScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val client = vm.activeClient()
    val activeConn = vm.devices[vm.activeDeviceId]

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.adb_reboot_tile_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(rebootOptions) { option ->
                val titleText = stringResource(option.titleRes)
                
                fun executeReboot() {
                    val targetClient = client ?: return
                    val targetConn = activeConn ?: return
                    Toast.makeText(context, context.getString(R.string.adb_reboot_executing, titleText), Toast.LENGTH_SHORT).show()
                    coroutineScope.launch(Dispatchers.IO) {
                        runCatching {
                            targetClient.executeShell(option.shellCommand, targetConn.supportsShellV2)
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = client != null, onClick = { executeReboot() }),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = if (client != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (client != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(option.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (client != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            enabled = client != null,
                            onClick = { executeReboot() }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = titleText,
                                tint = if (client != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
