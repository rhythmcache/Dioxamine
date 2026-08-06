package io.github.rhythmcache.dioxamine.adb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rhythmcache.adb.*
import io.github.rhythmcache.adb.io.RandomAccessSource
import io.github.rhythmcache.dioxamine.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.io.File

class AdbViewModel(private val keyDir: File) : ViewModel() {

    private val keyManager = AdbKeyManager(keyDir)

    var devices = mutableStateMapOf<String, DeviceConnection>()
        private set

    // id of the device currently "selected" for running shell/native actions against
    var activeDeviceId by mutableStateOf<String?>(null)
        private set

    // form fields for the "add TCP device" row
    var newIp by mutableStateOf("")
    var newPort by mutableStateOf("5555")

    var nativeOutputs = mutableStateMapOf<String, String>()
        private set
    var runningCommands = mutableStateMapOf<String, Boolean>()
        private set

    var keyFingerprint by mutableStateOf<String?>(keyManager.currentFingerprint())
        private set
    var keyMessage by mutableStateOf<String?>(null)
        private set

    var pairingError by mutableStateOf<String?>(null)
        private set

    var daemonDialogMessage by mutableStateOf<String?>(null)
        private set

    var flashState by mutableStateOf<FlashUiState>(FlashUiState.Idle)
        private set

    private var flashJob: Job? = null
    private var flashPfd: ParcelFileDescriptor? = null

    init {
        // Automatically generate key pair on very first launch if no key exists
        viewModelScope.launch(Dispatchers.IO) {
            if (!keyManager.hasKey()) {
                AppLogger.i("ADB_VM", "No ADB authorization key found. Automatically generating key pair on first launch...")
                when (val result = keyManager.regenerateKey()) {
                    is KeyLoadResult.Success -> {
                        withContext(Dispatchers.Main) {
                            keyFingerprint = result.fingerprint
                            AppLogger.i("ADB_VM", "Successfully generated initial ADB key pair: ${result.fingerprint}")
                        }
                    }
                    is KeyLoadResult.Failure -> {
                        AppLogger.e("ADB_VM", "Failed to generate initial ADB key pair: ${result.message}")
                    }
                }
            }
        }

        // background connection health monitor (every 5s)
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(5000)
                for ((id, conn) in devices.toMap()) {
                    if (conn.state is ConnectionState.Connected && conn.client != null) {
                        if (conn.client.isClosed) {
                            AppLogger.w("ADB_VM", "AdbViewModel: Connection $id died (transport=${conn.transport})")
                            handleDeadConnection(id, conn)
                        }
                    }
                }
            }
        }
    }

    private fun handleDeadConnection(id: String, conn: DeviceConnection) {
    // close the dead client cleanly
    runCatching { conn.client?.close() }

    when (conn.transport) {
        DeviceTransport.TCP -> {
            // Auto-reconnect TCP/TLS devices in background
            val isTls = id.startsWith("tls:")
            val stripped = if (isTls) id.removePrefix("tls:") else id
            val parts = stripped.split(":")
            val host = parts.getOrNull(0) ?: return
            val portInt = parts.getOrNull(1)?.toIntOrNull() ?: 5555

            devices[id] = conn.copy(client = null, state = ConnectionState.Connecting)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val keyProvider = keyManager.createKeyProvider()
                    val newClient = if (isTls) {
                        AdbClient.connectTls(host = host, port = portInt, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                    } else {
                        AdbClient.connect(host = host, port = portInt, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                    }
                    val details = fetchDeviceDetails(newClient)
                    devices[id] = conn.copy(
                        client = newClient,
                        state = ConnectionState.Connected(newClient),
                        supportsShellV2 = details.supportsShellV2,
                        androidVersion = details.androidVersion,
                        apiLevel = details.apiLevel,
                        model = details.model ?: conn.label,
                        mode = newClient.deviceMode,
                        isRoot = details.isRoot
                    )
                    AppLogger.i("ADB_VM", "AdbViewModel: Auto-reconnected $id successfully")
                } catch (e: Exception) {
                    devices[id] = conn.copy(client = null, state = ConnectionState.Error("Connection lost: ${e.message}"))
                }
            }
        }
        DeviceTransport.USB -> {
            devices[id] = conn.copy(client = null, state = ConnectionState.Error("USB connection lost. Please reconnect."))
        }
    }
}

    fun connectTcp() {
        val host = newIp.trim()
        val portInt = newPort.trim().toIntOrNull()
        if (host.isEmpty() || portInt == null) return
        val id = "$host:$portInt"
        if (devices.containsKey(id) && devices[id]?.state is ConnectionState.Connected) return

        devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val keyProvider = keyManager.createKeyProvider()
                val identity = keyManager.getPairingIdentity()
                val client = withContext(Dispatchers.IO) {
                    runCatching {
                        AdbClient.connectTls(host = host, port = portInt, keyProvider = keyProvider, identity = identity, handshakeTimeoutMs = 15000)
                    }.getOrElse {
                        AdbClient.connectTcp(host = host, port = portInt, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                    }
                }

                val details = withContext(Dispatchers.IO) { fetchDeviceDetails(client) }
                val displayLabel = details.model ?: id

                devices[id] = DeviceConnection(
                    id = id,
                    label = displayLabel,
                    transport = DeviceTransport.TCP,
                    client = client,
                    state = ConnectionState.Connected(client),
                    supportsShellV2 = details.supportsShellV2,
                    androidVersion = details.androidVersion,
                    apiLevel = details.apiLevel,
                    model = details.model,
                    mode = client.deviceMode,
                    isRoot = details.isRoot
                )
                if (activeDeviceId == null) activeDeviceId = id
            } catch (e: Exception) {
                devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Error(e.message ?: "Connection failed"))
            }
        }
    }

    fun connectTcpDirect(host: String, port: Int) {
        if (host.isEmpty()) return
        val id = "$host:$port"
        if (devices.containsKey(id) && devices[id]?.state is ConnectionState.Connected) return

        devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val keyProvider = keyManager.createKeyProvider()
                val identity = keyManager.getPairingIdentity()
                val client = withContext(Dispatchers.IO) {
                    runCatching {
                        AdbClient.connectTls(host = host, port = port, keyProvider = keyProvider, identity = identity, handshakeTimeoutMs = 15000)
                    }.getOrElse {
                        AdbClient.connectTcp(host = host, port = port, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                    }
                }

                val details = withContext(Dispatchers.IO) { fetchDeviceDetails(client) }
                val displayLabel = details.model ?: id

                devices[id] = DeviceConnection(
                    id = id,
                    label = displayLabel,
                    transport = DeviceTransport.TCP,
                    client = client,
                    state = ConnectionState.Connected(client),
                    supportsShellV2 = details.supportsShellV2,
                    androidVersion = details.androidVersion,
                    apiLevel = details.apiLevel,
                    model = details.model,
                    mode = client.deviceMode,
                    isRoot = details.isRoot
                )
                if (activeDeviceId == null) activeDeviceId = id
            } catch (e: Exception) {
                devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Error(e.message ?: "Connection failed"))
            }
        }
    }


    fun connectUsb(usbManager: UsbManager, device: UsbDevice) {
        val serial = runCatching { device.serialNumber }.getOrNull() ?: device.deviceName
        val defaultLabel = (runCatching { device.productName }.getOrNull() ?: device.deviceName).ifBlank { serial }
        val id = "usb:$serial"
        if (devices.containsKey(id) && devices[id]?.state is ConnectionState.Connected) return

        devices[id] = DeviceConnection(id, defaultLabel, DeviceTransport.USB, null, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val iface = UsbPacketTransport.findAdbInterface(device)
                    ?: throw Exception("No ADB USB interface found on device")
                val (inEp, outEp) = UsbPacketTransport.findAdbEndpoints(iface)
                    ?: throw Exception("Failed to locate USB bulk endpoints")

                val connection = usbManager.openDevice(device)
                    ?: throw Exception("Failed to open USB connection")

                if (!connection.claimInterface(iface, true)) {
                    connection.close()
                    throw Exception("Failed to claim USB interface")
                }

                val transport = UsbPacketTransport(connection, iface, inEp, outEp)
                val keyProvider = keyManager.createKeyProvider()

                val client = withContext(Dispatchers.IO) {
                    AdbClient.connect(transport = transport, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                }

                val details = withContext(Dispatchers.IO) { fetchDeviceDetails(client) }
                val displayLabel = details.model ?: defaultLabel

                UsbHelper.registerDeviceMapping(device.deviceName, id)
                devices[id] = DeviceConnection(
                    id = id,
                    label = displayLabel,
                    transport = DeviceTransport.USB,
                    client = client,
                    state = ConnectionState.Connected(client),
                    supportsShellV2 = details.supportsShellV2,
                    androidVersion = details.androidVersion,
                    apiLevel = details.apiLevel,
                    model = details.model,
                    mode = client.deviceMode,
                    isRoot = details.isRoot
                )
                if (activeDeviceId == null) activeDeviceId = id
            } catch (e: Exception) {
                devices[id] = DeviceConnection(id, defaultLabel, DeviceTransport.USB, null, ConnectionState.Error(e.message ?: "USB connection failed"))
            }
        }
    }

    fun connectTls(host: String, port: Int, onResult: ((Boolean, String?) -> Unit)? = null) {
        val id = "tls:$host:$port"
        if (devices.containsKey(id) && devices[id]?.state is ConnectionState.Connected) {
            onResult?.invoke(true, null)
            return
        }

        devices[id] = DeviceConnection(id, "$host:$port", DeviceTransport.TCP, null, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val keyProvider = keyManager.createKeyProvider()
                val client = withContext(Dispatchers.IO) {
                    AdbClient.connectTls(host = host, port = port, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                }
                val details = withContext(Dispatchers.IO) { fetchDeviceDetails(client) }
                devices[id] = DeviceConnection(
                    id = id,
                    label = details.model ?: id,
                    transport = DeviceTransport.TCP,
                    client = client,
                    state = ConnectionState.Connected(client),
                    supportsShellV2 = details.supportsShellV2,
                    androidVersion = details.androidVersion,
                    apiLevel = details.apiLevel,
                    model = details.model,
                    mode = client.deviceMode,
                    isRoot = details.isRoot
                )
                if (activeDeviceId == null) activeDeviceId = id
                onResult?.invoke(true, null)
            } catch (e: io.github.rhythmcache.adb.AdbException.NotPaired) {
                devices.remove(id)
                val err = "Device not paired. Pair it first."
                pairingError = err
                onResult?.invoke(false, err)
            } catch (e: Exception) {
                val errMsg = e.message ?: "TLS connection failed"
                devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Error(errMsg))
                onResult?.invoke(false, errMsg)
            }
        }
    }

    fun pairTls(host: String, port: Int, pairingCode: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val keyProvider = keyManager.createKeyProvider()
                val result = withContext(Dispatchers.IO) {
                    AdbClient.pairTls(host = host, port = port, pairingCode = pairingCode, keyProvider = keyProvider)
                }
                result.fold(
                    onSuccess = { onResult(true, "Paired successfully") },
                    onFailure = { onResult(false, it.message ?: "Pairing failed") }
                )
            } catch (e: Exception) {
                onResult(false, e.message ?: "Pairing failed")
            }
        }
    }

    fun clearPairingError() { pairingError = null }


    fun disconnect(id: String) {
        val conn = devices[id] ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try { conn.client?.close() } catch (_: Exception) {}
            }
            devices.remove(id)
            nativeOutputs.clear()
            runningCommands.clear()
            flashState = FlashUiState.Idle
            if (activeDeviceId == id) {
                activeDeviceId = devices.keys.firstOrNull { devices[it]?.state is ConnectionState.Connected }
            }
        }
    }

    fun setActiveDevice(id: String) {
        activeDeviceId = id
        nativeOutputs.clear() // fresh output panel per device switch
        runningCommands.clear()
        flashState = FlashUiState.Idle
    }

    fun activeClient(): AdbClient? =
        activeDeviceId?.let { (devices[it]?.state as? ConnectionState.Connected)?.client }

    fun runShell(label: String, command: String) {
        val activeId = activeDeviceId ?: return
        val conn = devices[activeId] ?: return
        val client = activeClient() ?: run {
            nativeOutputs[label] = "Device not connected"
            return
        }
        runningCommands[label] = true
        viewModelScope.launch {
            try {
                val outputText = withContext(Dispatchers.IO) {
                    client.executeShell(command, conn.supportsShellV2)
                }
                nativeOutputs[label] = outputText
            } catch (e: Exception) {
                nativeOutputs[label] = "Failed: ${e.message}"
            } finally {
                runningCommands[label] = false
            }
        }
    }

    suspend fun runShellSuspend(label: String, command: String) {
        val activeId = activeDeviceId ?: return
        val conn = devices[activeId] ?: return
        val client = activeClient() ?: run {
            nativeOutputs[label] = "Device not connected"
            return
        }
        runningCommands[label] = true
        try {
            val outputText = withContext(Dispatchers.IO) {
                client.executeShell(command, conn.supportsShellV2)
            }
            nativeOutputs[label] = outputText
        } catch (e: Exception) {
            nativeOutputs[label] = "Failed: ${e.message}"
        } finally {
            runningCommands[label] = false
        }
    }

    fun startFlash(uri: Uri, contentResolver: ContentResolver) {
        val client = activeClient() ?: run {
            flashState = FlashUiState.Error("No device connected")
            return
        }
        if (flashJob?.isActive == true) {
            return // already flashing, ignore duplicate taps
        }

        flashState = FlashUiState.Running(0f, 0, 0)
        flashJob = viewModelScope.launch(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("Could not open selected file")
                flashPfd = pfd

                val source = RandomAccessSource.of(pfd.fileDescriptor, pfd.statSize)

                val activeMode = devices[activeDeviceId]?.mode ?: AdbDeviceMode.UNKNOWN

                val progressFlow = when (activeMode) {
                    AdbDeviceMode.RESCUE -> client.rescue.install(source)
                    else -> client.sideload.sideload(source)
                }

                
                progressFlow.collect { progress ->
                    
                    flashState = FlashUiState.Running(
                        percent = progress.percentage,
                        bytesTransferred = progress.bytesTransferred,
                        totalBytes = progress.totalBytes
                    )
                }
                
                flashState = FlashUiState.Success
            } catch (e: Exception) {
                
                flashState = FlashUiState.Error(e.message ?: "Flash failed")
            } finally {
                
                try { pfd?.close() } catch (_: Exception) {}
                flashPfd = null
            }
        }
    }

    fun cancelFlash() {
        flashJob?.cancel()
        flashJob = null
        try { flashPfd?.close() } catch (_: Exception) {}
        flashPfd = null
        flashState = FlashUiState.Idle
    }

    fun resetFlashState() {
        flashState = FlashUiState.Idle
    }

    fun rescueWipeUserdata() {
        val client = activeClient() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = client.rescue.wipeUserdata()
                withContext(Dispatchers.Main) { nativeOutputs["wipe"] = result }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { nativeOutputs["wipe"] = "Failed: ${e.message}" }
            }
        }
    }

    fun regenerateKey() {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { keyManager.regenerateKey() }) {
                is KeyLoadResult.Success -> { keyFingerprint = r.fingerprint; keyMessage = "New key generated." }
                is KeyLoadResult.Failure -> keyMessage = r.message
            }
        }
    }

    fun loadCustomKey(bytes: ByteArray) {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { keyManager.loadCustomKey(bytes) }) {
                is KeyLoadResult.Success -> { keyFingerprint = r.fingerprint; keyMessage = "Custom key loaded." }
                is KeyLoadResult.Failure -> keyMessage = r.message
            }
        }
    }

    fun dismissDaemonDialog() {
        daemonDialogMessage = null
    }

    fun toggleRoot(id: String) {
        val conn = devices[id] ?: return
        val client = conn.client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = if (conn.isRoot) {
                    client.unroot()
                } else {
                    client.root()
                }
                withContext(Dispatchers.Main) {
                    daemonDialogMessage = output
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    daemonDialogMessage = "Failed: ${e.message}"
                }
            }
        }
    }

    fun switchTcpip(id: String, port: Int = 5555) {
        val conn = devices[id] ?: return
        val client = conn.client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = client.tcpip(port)
                withContext(Dispatchers.Main) {
                    daemonDialogMessage = output
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    daemonDialogMessage = "Failed: ${e.message}"
                }
            }
        }
    }
}
