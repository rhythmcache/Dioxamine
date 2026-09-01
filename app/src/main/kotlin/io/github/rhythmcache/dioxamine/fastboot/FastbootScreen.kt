package io.github.rhythmcache.dioxamine.fastboot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R

private enum class FastbootTopTab(val labelRes: Int) {
    ACTIONS(R.string.fastboot_tab_actions),
    SHELL(R.string.fastboot_tab_shell),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastbootScreen(vm: FastbootViewModel) {
    var topTab by remember { mutableStateOf(FastbootTopTab.ACTIONS) }

    Column(modifier = Modifier.fillMaxSize()) {
        FastbootDeviceConnectorCard(vm)

        PrimaryTabRow(selectedTabIndex = topTab.ordinal) {
            FastbootTopTab.entries.forEach { tab ->
                Tab(
                    selected = topTab == tab,
                    onClick = { topTab = tab },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (topTab) {
                FastbootTopTab.ACTIONS -> FastbootActionsTab(vm)
                FastbootTopTab.SHELL -> FastbootShellTab(vm)
            }
        }
    }
}

@Composable
private fun FastbootDeviceConnectorCard(vm: FastbootViewModel) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (vm.devices.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Usb,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.fastboot_no_device_status),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        vm.devices.values.forEach { device ->
                            val isSelected = device.id == vm.connectedDeviceId && vm.isConnected
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (!isSelected) {
                                        vm.connect(device.id)
                                    }
                                },
                                label = {
                                    Text(
                                        device.label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Usb,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.cd_expand_collapse)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    if (vm.devices.isEmpty()) {
                        Text(
                            stringResource(R.string.fastboot_no_device_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        vm.devices.values.forEach { device ->
                            FastbootDeviceRow(device, vm)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FastbootDeviceRow(device: FastbootDevice, vm: FastbootViewModel) {
    val isCurrent = device.id == vm.connectedDeviceId
    val isConnected = isCurrent && vm.isConnected

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                Icons.Filled.Usb,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    device.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = if (isConnected) {
                        stringResource(R.string.fastboot_connected)
                    } else {
                        stringResource(R.string.fastboot_detected_not_connected)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isConnected) {
                TextButton(onClick = { vm.closeSession() }) {
                    Text(stringResource(R.string.fastboot_disconnect))
                }
            } else {
                TextButton(onClick = { vm.connect(device.id) }) {
                    Text(stringResource(R.string.btn_connect))
                }
            }
        }
    }
}

private data class ActionTileSpec(
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int,
    val destination: FastbootSubScreen,
)

private val tileSpecs = listOf(
    ActionTileSpec(Icons.Filled.RestartAlt, R.string.fastboot_tile_reboot_title, R.string.fastboot_tile_reboot_desc, FastbootSubScreen.Reboot),
    ActionTileSpec(Icons.Filled.FileUpload, R.string.fastboot_tile_flash_title, R.string.fastboot_tile_flash_desc, FastbootSubScreen.FlashImage),
    ActionTileSpec(Icons.Filled.PlayCircle, R.string.fastboot_tile_boot_title, R.string.fastboot_tile_boot_desc, FastbootSubScreen.BootImage),
    ActionTileSpec(Icons.Filled.Lock, R.string.fastboot_tile_lock_title, R.string.fastboot_tile_lock_desc, FastbootSubScreen.LockState),
    ActionTileSpec(Icons.Filled.Info, R.string.fastboot_tile_vars_title, R.string.fastboot_tile_vars_desc, FastbootSubScreen.Variables),
)

@Composable
fun FastbootActionsTab(vm: FastbootViewModel) {
    var activeSubScreen by remember { mutableStateOf<FastbootSubScreen>(FastbootSubScreen.TilesList) }
    val isConnected = vm.isConnected

    when (val screen = activeSubScreen) {
        FastbootSubScreen.TilesList -> {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                if (!isConnected) {
                    Text(
                        text = stringResource(R.string.fastboot_no_device_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                OperationStatusCard(
                    status = vm.lastOperationStatus,
                    onDismiss = { vm.clearOperationStatus() },
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(tileSpecs) { spec ->
                        FastbootActionTile(
                            icon = spec.icon,
                            title = stringResource(spec.titleRes),
                            description = stringResource(spec.descriptionRes),
                            enabled = isConnected,
                            onClick = {
                                vm.clearOperationStatus()
                                activeSubScreen = spec.destination
                            },
                        )
                    }
                }
            }
        }
        else -> {
            val onBack: () -> Unit = { activeSubScreen = FastbootSubScreen.TilesList }
            Column(modifier = Modifier.fillMaxSize()) {
                when (screen) {
                    FastbootSubScreen.Reboot -> RebootScreen(vm, onBack)
                    FastbootSubScreen.FlashImage -> FlashImageScreen(vm, onBack)
                    FastbootSubScreen.BootImage -> BootImageScreen(vm, onBack)
                    FastbootSubScreen.LockState -> LockStateScreen(vm, onBack)
                    FastbootSubScreen.Variables -> VariablesScreen(vm, onBack)
                    FastbootSubScreen.TilesList -> {}
                }
            }
        }
    }
}

@Composable
fun FastbootShellTab(vm: FastbootViewModel) {
    val listState = rememberLazyListState()

    LaunchedEffect(vm.logs.size) {
        if (vm.logs.isNotEmpty()) listState.animateScrollToItem(vm.logs.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.fastboot_log_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.clearLogs() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.fastboot_clear_log))
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (vm.logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.fastboot_shell_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(vm.logs) { entry -> LogLine(entry) }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }

        if (vm.isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            ShellInputBar(
                vm = vm,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    val (prefix, color) = when (entry.level) {
        LogLevel.COMMAND -> "\$ " to MaterialTheme.colorScheme.primary
        LogLevel.INFO -> "  " to MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.RESULT -> "  " to MaterialTheme.colorScheme.onSurface
        LogLevel.ERROR -> "! " to MaterialTheme.colorScheme.error
        LogLevel.SYSTEM -> "# " to MaterialTheme.colorScheme.tertiary
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = entry.formattedTime(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = prefix + entry.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}
