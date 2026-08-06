package io.github.rhythmcache.dioxamine.adb.discovery

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel

sealed class DiscoveryStep {
    object DeviceList : DiscoveryStep()
    object ManualChooseType : DiscoveryStep()
    data class ManualTcp(val ip: String = "", val port: String = "5555") : DiscoveryStep()
    data class ManualTlsChoice(val ip: String = "", val port: String = "") : DiscoveryStep()
    data class ManualTlsConnect(val ip: String = "", val port: String = "", val isJustPaired: Boolean = false, val serviceName: String = "") : DiscoveryStep()
    data class ManualTlsPair(val ip: String = "", val port: String = "", val code: String = "", val serviceName: String = "") : DiscoveryStep()
}

typealias DiscoverySheetStep = DiscoveryStep

@Composable
fun DiscoveryDialog(
    vm: AdbViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val discoveryVm = remember { AdbDiscoveryViewModel(context) }
    var step by remember { mutableStateOf<DiscoveryStep>(DiscoveryStep.DeviceList) }

    DisposableEffect(Unit) {
        discoveryVm.startDiscovery()
        onDispose { discoveryVm.stopDiscovery() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                when (val s = step) {
                    is DiscoveryStep.DeviceList -> DeviceListStep(
                        discoveryVm = discoveryVm,
                        onDismiss = onDismiss,
                        onManualEntry = { step = DiscoveryStep.ManualChooseType },
                        onDeviceSelected = { device ->
                            when (device.type) {
                                AdbServiceType.TCP -> {
                                    vm.connectTcpDirect(device.host, device.port)
                                    onDismiss()
                                }
                                AdbServiceType.TLS_CONNECT -> {
                                    vm.clearPairingError()
                                    step = DiscoveryStep.ManualTlsConnect(device.host, device.port.toString(), serviceName = device.serviceName)
                                    vm.connectTls(device.host, device.port) { success, _ ->
                                        if (success) {
                                            onDismiss()
                                        }
                                    }
                                }
                                AdbServiceType.TLS_PAIRING -> {
                                    step = DiscoveryStep.ManualTlsPair(device.host, device.port.toString(), serviceName = device.serviceName)
                                }
                            }
                        }
                    )
                    is DiscoveryStep.ManualChooseType -> ManualChooseTypeStep(
                        onBack = { step = DiscoveryStep.DeviceList },
                        onChooseTcp = { step = DiscoveryStep.ManualTcp() },
                        onChooseTls = { step = DiscoveryStep.ManualTlsChoice() }
                    )
                    is DiscoveryStep.ManualTcp -> ManualTcpStep(
                        initial = s,
                        onBack = { step = DiscoveryStep.ManualChooseType },
                        onConnect = { ip, port ->
                            vm.connectTcpDirect(ip, port.toInt())
                            onDismiss()
                        }
                    )
                    is DiscoveryStep.ManualTlsChoice -> ManualTlsChoiceStep(
                        onBack = { step = DiscoveryStep.ManualChooseType },
                        onChooseConnect = { step = DiscoveryStep.ManualTlsConnect(s.ip, s.port) },
                        onChoosePair = { step = DiscoveryStep.ManualTlsPair(s.ip, s.port) }
                    )
                    is DiscoveryStep.ManualTlsConnect -> ManualTlsConnectStep(
                        initial = s,
                        discoveryDevices = discoveryVm.devices.values.toList(),
                        pairingError = vm.pairingError,
                        onBack = { step = DiscoveryStep.DeviceList },
                        onGoToPair = { ip, port -> step = DiscoveryStep.ManualTlsPair(ip, port) },
                        onConnect = { ip, port, callback ->
                            vm.clearPairingError()
                            vm.connectTls(ip, port.toInt(), callback)
                        },
                        onDismissAfterConnected = onDismiss
                    )
                    is DiscoveryStep.ManualTlsPair -> ManualTlsPairStep(
                        initial = s,
                        onBack = { step = DiscoveryStep.ManualTlsChoice(s.ip, s.port) },
                        onPair = { ip, port, code, callback ->
                            vm.pairTls(ip, port.toInt(), code, callback)
                        },
                        onPaired = { ip, _ ->
                            step = DiscoveryStep.ManualTlsConnect(ip, "", isJustPaired = true, serviceName = s.serviceName)
                        }
                    )
                }
            }
        }
    }
}

// Backwards-compatibility wrapper
@Composable
fun DiscoverySheet(
    vm: AdbViewModel,
    onDismiss: () -> Unit
) {
    DiscoveryDialog(vm = vm, onDismiss = onDismiss)
}

@Composable
private fun DeviceListStep(
    discoveryVm: AdbDiscoveryViewModel,
    onDismiss: () -> Unit,
    onManualEntry: () -> Unit,
    onDeviceSelected: (DiscoveredAdbDevice) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close), modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.discovery_searching),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        val devices = discoveryVm.devices.values.toList()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = 280.dp),
            contentAlignment = Alignment.Center
        ) {
            if (devices.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.discovery_no_devices_found), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.discovery_network_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(devices) { device ->
                        DiscoveredDeviceRow(device, onClick = { onDeviceSelected(device) })
                        HorizontalDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onManualEntry,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.discovery_btn_manual_entry))
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(device: DiscoveredAdbDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(device.serviceName, style = MaterialTheme.typography.bodyMedium)
            Text("${device.host}:${device.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AssistChip(
            onClick = {},
            enabled = false,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            label = {
                Text(
                    when (device.type) {
                        AdbServiceType.TLS_CONNECT -> "TLS"
                        AdbServiceType.TLS_PAIRING -> "Pairing"
                        AdbServiceType.TCP -> "TCP"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        )
    }
}

@Composable
private fun ManualChooseTypeStep(
    onBack: () -> Unit,
    onChooseTcp: () -> Unit,
    onChooseTls: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StepHeader(stringResource(R.string.discovery_conn_type), onBack)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onChooseTcp,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Cable, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.discovery_plain_tcp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onChooseTls,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.discovery_wireless_tls))
        }
    }
}

@Composable
private fun StepHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_nav_back))
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ManualTcpStep(
    initial: DiscoveryStep.ManualTcp,
    onBack: () -> Unit,
    onConnect: (String, String) -> Unit
) {
    var ip by remember { mutableStateOf(initial.ip) }
    var port by remember { mutableStateOf(initial.port) }
    val isValid = ip.isNotBlank() && port.toIntOrNull() != null

    Column(modifier = Modifier.fillMaxWidth()) {
        StepHeader(stringResource(R.string.discovery_connect_tcp), onBack)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = ip, onValueChange = { ip = it },
            label = { Text(stringResource(R.string.label_ip_address)) }, singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.label_port)) }, singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onConnect(ip.trim(), port.trim()) },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.btn_connect)) }
    }
}

@Composable
private fun ManualTlsChoiceStep(
    onBack: () -> Unit,
    onChooseConnect: () -> Unit,
    onChoosePair: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StepHeader(stringResource(R.string.discovery_wireless_debugging), onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.discovery_pair_first_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onChoosePair,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.discovery_pair_with_code))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onChooseConnect,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.discovery_connect_already_paired))
        }
    }
}

@Composable
private fun ManualTlsConnectStep(
    initial: DiscoveryStep.ManualTlsConnect,
    discoveryDevices: Collection<DiscoveredAdbDevice>,
    pairingError: String?,
    onBack: () -> Unit,
    onGoToPair: (String, String) -> Unit,
    onConnect: (String, String, ((Boolean, String?) -> Unit)?) -> Unit,
    onDismissAfterConnected: () -> Unit
) {
    var ip by remember { mutableStateOf(initial.ip) }
    var port by remember { mutableStateOf(initial.port) }
    var isConnecting by remember { mutableStateOf(false) }
    var autoConnectAttempted by remember(initial.serviceName, initial.isJustPaired) { mutableStateOf(false) }
    val isValid = ip.isNotBlank() && port.toIntOrNull() != null

    val matchedDeviceId = extractDeviceIdentity(initial.serviceName)
    val autoConnectCandidate = discoveryDevices.firstOrNull {
        it.type == AdbServiceType.TLS_CONNECT && it.deviceId == matchedDeviceId
    }

    LaunchedEffect(initial.isJustPaired, matchedDeviceId, autoConnectCandidate?.host, autoConnectCandidate?.port, autoConnectAttempted) {
        if (!initial.isJustPaired || matchedDeviceId.isBlank() || autoConnectCandidate == null || autoConnectAttempted) return@LaunchedEffect
        autoConnectAttempted = true
        ip = autoConnectCandidate.host
        port = autoConnectCandidate.port.toString()
        isConnecting = true
        onConnect(ip.trim(), port.trim()) { success, _ ->
            isConnecting = false
            if (success) {
                onDismissAfterConnected()
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        StepHeader(stringResource(R.string.discovery_connect_tls), onBack)

        if (initial.isJustPaired) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.discovery_paired_success_connecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = ip, onValueChange = { ip = it },
            label = { Text(stringResource(R.string.label_ip_address)) }, singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.label_port)) }, singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        if (pairingError != null) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pairingError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    onClick = { onGoToPair(ip.trim(), port.trim()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.discovery_pair_instead)) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                isConnecting = true
                onConnect(ip.trim(), port.trim()) { success, _ ->
                    isConnecting = false
                    if (success) {
                        onDismissAfterConnected()
                    }
                }
            },
            enabled = isValid && !isConnecting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.btn_connect))
            }
        }
    }
}

@Composable
private fun ManualTlsPairStep(
    initial: DiscoveryStep.ManualTlsPair,
    onBack: () -> Unit,
    onPair: (String, String, String, (Boolean, String) -> Unit) -> Unit,
    onPaired: (String, String) -> Unit
) {
    var ip by remember { mutableStateOf(initial.ip) }
    var port by remember { mutableStateOf(initial.port) }
    var code by remember { mutableStateOf(initial.code) }
    var isPairing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val isValid = ip.isNotBlank() && port.toIntOrNull() != null && code.isNotBlank()

    Column(modifier = Modifier.fillMaxWidth()) {
        StepHeader(stringResource(R.string.discovery_pair_device), onBack)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text(stringResource(R.string.label_ip_address)) }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.discovery_pairing_port)) }, singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text(stringResource(R.string.discovery_pairing_code)) }, singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        errorMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                isPairing = true
                errorMsg = null
                onPair(ip.trim(), port.trim(), code.trim()) { success, msg ->
                    isPairing = false
                    if (success) onPaired(ip.trim(), "")
                    else errorMsg = msg
                }
            },
            enabled = isValid && !isPairing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isPairing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.discovery_btn_pair))
            }
        }
    }
}
