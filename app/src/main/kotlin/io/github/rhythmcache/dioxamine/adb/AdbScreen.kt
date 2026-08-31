package io.github.rhythmcache.dioxamine.adb

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.builtin.BuiltInActionsTab
import io.github.rhythmcache.dioxamine.adb.discovery.DiscoverySheet
import io.github.rhythmcache.dioxamine.adb.discovery.QrPairingScreen
import io.github.rhythmcache.dioxamine.adb.shell.ShellScreen
import io.github.rhythmcache.dioxamine.core.*
import io.github.rhythmcache.adb.AdbDeviceMode
import io.github.rhythmcache.dioxamine.plugin.PluginDialogGate
import io.github.rhythmcache.dioxamine.plugin.PluginPermissionGate
import io.github.rhythmcache.dioxamine.plugin.PluginRepository
import io.github.rhythmcache.dioxamine.plugin.PluginRunnerScreen
import io.github.rhythmcache.dioxamine.plugin.PluginSafBridge
import io.github.rhythmcache.dioxamine.plugin.PluginsTab

private fun parseIpAndPort(input: String): Pair<String, String?> {
    val trimmed = input.trim()
    if (trimmed.contains(":")) {
        val parts = trimmed.split(":")
        if (parts.size == 2) {
            return Pair(parts[0].trim(), parts[1].trim())
        }
    }
    return Pair(trimmed, null)
}

private fun isValidIp(ip: String): Boolean {
    if (ip.equals("localhost", ignoreCase = true)) return true
    val ipv4Regex = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
    return ipv4Regex.matches(ip)
}

private fun isValidPort(portStr: String): Boolean {
    val p = portStr.toIntOrNull() ?: return false
    return p in 1..65535
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbScreen(
    vm: AdbViewModel,
    pluginRepo: PluginRepository,
    permissionGate: PluginPermissionGate,
    dialogGate: PluginDialogGate,
    safBridge: PluginSafBridge,
    onPluginActiveChange: (Boolean) -> Unit = {},
) {
    var subTab by remember { mutableStateOf(0) }
    var activePluginId by remember { mutableStateOf<String?>(null) }
    val activeConn = vm.devices[vm.activeDeviceId]
    val mode = activeConn?.mode ?: AdbDeviceMode.UNKNOWN

    LaunchedEffect(activePluginId) {
        onPluginActiveChange(activePluginId != null)
    }

    DisposableEffect(Unit) {
        onDispose {
            onPluginActiveChange(false)
        }
    }

    // Return to Built-in sub-tab when on Shell or Plugins tab
    BackHandler(enabled = activePluginId == null && subTab != 0) {
        subTab = 0
    }

    vm.daemonDialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.dismissDaemonDialog() },
            title = { Text(stringResource(R.string.daemon_dialog_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { vm.dismissDaemonDialog() }) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        )
    }

    if (activePluginId != null) {
        PluginRunnerScreen(
            pluginId = activePluginId!!,
            vm = vm,
            repo = pluginRepo,
            permissionGate = permissionGate,
            dialogGate = dialogGate,
            safBridge = safBridge,
            onBack = { activePluginId = null }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            DeviceConnectorCard(vm)

            when (mode) {
                AdbDeviceMode.SIDELOAD -> {
                    SideloadFlashScreen(vm)
                }
                AdbDeviceMode.RESCUE -> {
                    RescueScreen(vm)
                }
                else -> {
                    PrimaryTabRow(selectedTabIndex = subTab) {
                        Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text(stringResource(R.string.adb_subtab_builtin)) })
                        Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text(stringResource(R.string.adb_subtab_adb_shell)) })
                        Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text(stringResource(R.string.adb_subtab_plugins)) })
                    }

                    when (subTab) {
                        0 -> BuiltInActionsTab(vm)
                        1 -> ShellScreen(vm)
                        2 -> PluginsTab(
                            repo = pluginRepo,
                            onOpenPlugin = { pluginId ->
                                activePluginId = pluginId
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceConnectorCard(vm: AdbViewModel) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var showDiscoverySheet by remember { mutableStateOf(false) }
    var showAddTcpDialog by remember { mutableStateOf(false) }
    var showQrPairing by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (vm.devices.isEmpty()) {
                    Row(
                        modifier = Modifier.weight(1f).clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.no_devices_connected),
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
                        vm.devices.values.forEach { conn ->
                            val isActive = conn.id == vm.activeDeviceId
                            val isConnected = conn.state is ConnectionState.Connected
                            FilterChip(
                                selected = isActive,
                                onClick = { if (isConnected) vm.setActiveDevice(conn.id) },
                                enabled = isConnected,
                                label = {
                                    val sideloadStr = stringResource(R.string.mode_sideload)
                                    val recoveryStr = stringResource(R.string.mode_recovery)
                                    val rescueStr = stringResource(R.string.mode_rescue)
                                    val labelText = when (conn.mode) {
                                        AdbDeviceMode.SIDELOAD -> "${conn.label} [$sideloadStr]"
                                        AdbDeviceMode.RECOVERY -> "${conn.label} [$recoveryStr]"
                                        AdbDeviceMode.RESCUE -> "${conn.label} [$rescueStr]"
                                        else -> conn.label
                                    }
                                    Text(
                                        labelText,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (conn.transport == DeviceTransport.USB) Icons.Filled.Usb else Icons.Filled.Wifi,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showQrPairing = true }) {
                        Icon(Icons.Filled.QrCode, contentDescription = stringResource(R.string.cd_pair_qr))
                    }
                    IconButton(onClick = { showDiscoverySheet = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_device))
                    }
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
                            stringResource(R.string.no_devices_added_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        vm.devices.values.forEach { conn ->
                            DeviceRow(conn, vm)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAddTcpDialog) {
        val isIpValid = vm.newIp.isEmpty() || isValidIp(vm.newIp)
        val isPortValid = vm.newPort.isEmpty() || isValidPort(vm.newPort)
        val isFormValid = vm.newIp.isNotBlank() && isValidIp(vm.newIp) && isValidPort(vm.newPort)

        AlertDialog(
            onDismissRequest = { showAddTcpDialog = false },
            title = { Text(stringResource(R.string.dialog_add_tcp_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.newIp,
                        onValueChange = { input ->
                            val (parsedIp, parsedPort) = parseIpAndPort(input)
                            vm.newIp = parsedIp
                            if (parsedPort != null) {
                                vm.newPort = parsedPort
                            }
                        },
                        label = { Text(stringResource(R.string.label_ip_address)) },
                        placeholder = { Text(stringResource(R.string.adb_ip_placeholder)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        isError = !isIpValid,
                        supportingText = if (!isIpValid) {
                            { Text(stringResource(R.string.err_invalid_ip_format), color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = vm.newPort,
                        onValueChange = { input ->
                            vm.newPort = input.filter { it.isDigit() }
                        },
                        label = { Text(stringResource(R.string.label_port)) },
                        placeholder = { Text("5555") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        isError = !isPortValid,
                        supportingText = if (!isPortValid) {
                            { Text(stringResource(R.string.err_invalid_port_range), color = MaterialTheme.colorScheme.error) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.connectTcp()
                        showAddTcpDialog = false
                    },
                    enabled = isFormValid
                ) { Text(stringResource(R.string.btn_connect)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddTcpDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (showDiscoverySheet) {
        DiscoverySheet(vm, onDismiss = { showDiscoverySheet = false })
    }

    if (showQrPairing) {
        QrPairingScreen(
            keyDir = context.filesDir,
            onBack = { showQrPairing = false },
            onPairedAndDiscoverable = { host, port ->
                showQrPairing = false
                vm.connectTls(host, port)
            }
        )
    }
}

@Composable
fun DeviceRow(conn: DeviceConnection, vm: AdbViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                if (conn.transport == DeviceTransport.USB) Icons.Filled.Usb else Icons.Filled.Wifi,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(conn.label, style = MaterialTheme.typography.bodySmall)
                Text(
                    when (conn.state) {
                        is ConnectionState.Connected -> {
                            when (conn.mode) {
                                AdbDeviceMode.SIDELOAD -> stringResource(R.string.status_connected_mode, stringResource(R.string.mode_sideload))
                                AdbDeviceMode.RECOVERY -> {
                                    if (conn.androidVersion != null && conn.apiLevel != null) {
                                        stringResource(R.string.status_connected_recovery_version_api, conn.androidVersion, conn.apiLevel)
                                    } else if (conn.androidVersion != null) {
                                        stringResource(R.string.status_connected_recovery_version, conn.androidVersion)
                                    } else if (conn.apiLevel != null) {
                                        stringResource(R.string.status_connected_recovery_api, conn.apiLevel)
                                    } else {
                                        stringResource(R.string.status_connected_mode, stringResource(R.string.mode_recovery))
                                    }
                                }
                                AdbDeviceMode.RESCUE -> stringResource(R.string.status_connected_mode, stringResource(R.string.mode_rescue))
                                else -> {
                                    if (conn.androidVersion != null && conn.apiLevel != null) {
                                        stringResource(R.string.status_connected_version_api, conn.androidVersion, conn.apiLevel)
                                    } else if (conn.androidVersion != null) {
                                        stringResource(R.string.status_connected_version, conn.androidVersion)
                                    } else if (conn.apiLevel != null) {
                                        stringResource(R.string.status_connected_api, conn.apiLevel)
                                    } else {
                                        stringResource(R.string.status_connected)
                                    }
                                }
                            }
                        }
                        is ConnectionState.Connecting -> stringResource(R.string.status_connecting)
                        is ConnectionState.Error -> conn.state.message
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (conn.state) {
                        is ConnectionState.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            when (conn.state) {
                is ConnectionState.Connecting -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                is ConnectionState.Connected -> {
                    var menuExpanded by remember { mutableStateOf(false) }
                    var showTcpDialog by remember { mutableStateOf(false) }
                    var tcpPortInput by remember { mutableStateOf("5555") }

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_daemon_options), modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (conn.transport == DeviceTransport.USB) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_switch_tcpip), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        showTcpDialog = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(if (conn.isRoot) R.string.action_unroot_daemon else R.string.action_root_daemon), style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = { Icon(if (conn.isRoot) Icons.Filled.LockOpen else Icons.Filled.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    menuExpanded = false
                                    vm.toggleRoot(conn.id)
                                }
                            )
                        }
                    }

                    if (showTcpDialog) {
                        AlertDialog(
                            onDismissRequest = { showTcpDialog = false },
                            title = { Text(stringResource(R.string.dialog_tcpip_title)) },
                            text = {
                                Column {
                                    Text(stringResource(R.string.dialog_tcpip_msg))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = tcpPortInput,
                                        onValueChange = { tcpPortInput = it },
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.label_port)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showTcpDialog = false
                                    val port = tcpPortInput.trim().toIntOrNull() ?: 5555
                                    vm.switchTcpip(conn.id, port)
                                }) {
                                    Text(stringResource(R.string.btn_restart))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTcpDialog = false }) {
                                    Text(stringResource(R.string.btn_cancel))
                                }
                            }
                        )
                    }

                    IconButton(onClick = { vm.disconnect(conn.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove), modifier = Modifier.size(18.dp))
                    }
                }
                else -> {
                    IconButton(onClick = { vm.disconnect(conn.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}





@Composable
fun SideloadFlashScreen(vm: AdbViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickedFileName by remember { mutableStateOf<String?>(null) }

    val pickFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        pickedUri = uri
        pickedFileName = uri?.let { resolveDisplayName(context.contentResolver, it) }
    }

    var isStartingFlash by remember { mutableStateOf(false) }

    LaunchedEffect(pickedUri, vm.flashState) {
        if (vm.flashState !is FlashUiState.Idle) {
            isStartingFlash = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.mode_sideload), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.sideload_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        when (val state = vm.flashState) {
            is FlashUiState.Idle -> {
                OutlinedButton(
                    onClick = { pickFileLauncher.launch(arrayOf("application/zip")) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text(pickedFileName ?: stringResource(R.string.btn_choose_file))
                }
                if (pickedFileName != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        pickedFileName!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (pickedUri != null && !isStartingFlash) {
                            isStartingFlash = true
                            vm.startFlash(pickedUri!!, context.contentResolver)
                        }
                    },
                    enabled = pickedUri != null && !isStartingFlash,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = if (isStartingFlash) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledContentColor = if (isStartingFlash) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    if (isStartingFlash) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_starting))
                    } else {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_sideload))
                    }
                }
            }
            is FlashUiState.Running -> {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("${state.percent.toInt()}% - ${state.bytesTransferred / 1024 / 1024}MB / ${state.totalBytes / 1024 / 1024}MB")
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { vm.cancelFlash() }) { Text(stringResource(R.string.btn_cancel)) }
            }
            is FlashUiState.Success -> {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.msg_flash_complete))
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { pickedUri = null; pickedFileName = null; vm.resetFlashState() }) { Text(stringResource(R.string.btn_done)) }
            }
            is FlashUiState.Error -> {
                Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { pickedUri = null; pickedFileName = null; vm.resetFlashState() }) { Text(stringResource(R.string.btn_try_again)) }
            }
        }
    }
}

@Composable
fun RescueScreen(vm: AdbViewModel) {
    var showWipeConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.mode_rescue), style = MaterialTheme.typography.titleMedium)

        SideloadFlashScreen(vm)

        HorizontalDivider()

        OutlinedButton(
            onClick = { showWipeConfirm = true },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_wipe_userdata))
        }

        vm.nativeOutputs["wipe"]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text(stringResource(R.string.dialog_wipe_userdata_title)) },
            text = { Text(stringResource(R.string.dialog_wipe_userdata_msg)) },
            confirmButton = {
                TextButton(onClick = { vm.rescueWipeUserdata(); showWipeConfirm = false }) { Text(stringResource(R.string.btn_wipe)) }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}

fun resolveDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? {
    return try {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (_: Exception) { null }
}
