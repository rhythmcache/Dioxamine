package io.github.rhythmcache.dioxamine.adb.builtin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceInformationTile(
    vm: AdbViewModel,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.action_device_info_title),
        description = stringResource(R.string.action_device_info_desc),
        icon = Icons.Filled.PhoneAndroid,
        enabled = isConnected,
        onClick = onClick
    )
}

@Composable
fun DeviceInformationDetailScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val modelLabel = stringResource(R.string.action_device_model)
    val versionLabel = stringResource(R.string.action_android_version)
    val batteryLabel = stringResource(R.string.action_battery_info)
    val serialLabel = stringResource(R.string.action_serial_number)
    val cpuLabel = stringResource(R.string.action_cpu_info)
    val uptimeLabel = stringResource(R.string.action_uptime)
    val resLabel = stringResource(R.string.action_screen_resolution)
    val pkgsLabel = stringResource(R.string.action_installed_packages)

    val actions = remember(modelLabel, versionLabel, batteryLabel, serialLabel, cpuLabel, uptimeLabel, resLabel, pkgsLabel) {
        listOf(
            modelLabel to "getprop ro.product.model",
            versionLabel to "getprop ro.build.version.release",
            batteryLabel to "dumpsys battery",
            serialLabel to "getprop ro.serialno",
            cpuLabel to "cat /proc/cpuinfo",
            uptimeLabel to "cat /proc/uptime",
            resLabel to "wm size",
            pkgsLabel to "pm list packages | wc -l"
        )
    }

    val isConnected = vm.activeClient() != null
    val scope = rememberCoroutineScope()

    val triggerFetchSequential = remember(isConnected) {
        {
            if (isConnected) {
                scope.launch {
                    for ((label, command) in actions) {
                        vm.runShellSuspend(label, command)
                        delay(120)
                    }
                }
            }
        }
    }

    LaunchedEffect(isConnected) {
        triggerFetchSequential()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Header Bar with Back Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_nav_back),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.dialog_device_info_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { triggerFetchSequential() },
                enabled = isConnected
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh)
                )
            }
        }

        if (!isConnected) {
            Text(
                stringResource(R.string.connect_device_warning),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(actions) { (label, command) ->
                    val output = vm.nativeOutputs[label]
                    val isRunning = vm.runningCommands[label] == true

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (isRunning && output == null) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            when {
                                isRunning && output == null -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.status_running_command),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                output != null -> {
                                    Text(
                                        text = output,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                else -> {
                                    Text(
                                        stringResource(R.string.tap_refresh_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
