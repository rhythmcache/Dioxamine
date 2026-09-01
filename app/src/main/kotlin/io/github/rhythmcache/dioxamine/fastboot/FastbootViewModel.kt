package io.github.rhythmcache.dioxamine.fastboot

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rhythmcache.dioxamine.core.AndroidUsbFastbootTransport
import io.github.rhythmcache.dioxamine.core.UsbFastbootTransport
import io.github.rhythmcache.dioxamine.core.UsbHelper
import io.github.rhythmcache.fastboot.FastbootClient
import io.github.rhythmcache.fastboot.FastbootConnection
import io.github.rhythmcache.fastboot.FastbootException
import io.github.rhythmcache.fastboot.Progress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileOutputStream

private data class FastbootSession(
    val deviceId: String,
    val client: FastbootClient,
)

class FastbootViewModel : ViewModel() {

    var devices = mutableStateMapOf<String, FastbootDevice>()
        private set

    private var session: FastbootSession? by mutableStateOf<FastbootSession?>(null)
    val connectedDeviceId: String? get() = session?.deviceId
    val isConnected: Boolean get() = session != null

    fun activeClient(): FastbootClient? = session?.client

    val logs = mutableStateListOf<LogEntry>()

    var isBusy by mutableStateOf(false)
        private set
    var currentOperation by mutableStateOf<String?>(null)
        private set
    var currentProgress by mutableStateOf<Progress?>(null)
        private set
    var lastOperationStatus by mutableStateOf<OperationStatus>(OperationStatus.Idle)
        private set

    fun clearOperationStatus() {
        lastOperationStatus = OperationStatus.Idle
    }

    private var connections = mutableMapOf<String, Pair<UsbManager, UsbDevice>>()

    // -----------------------------------------------------------------
    // USB device presence
    // -----------------------------------------------------------------

    fun onDeviceDetected(usbManager: UsbManager, device: UsbDevice) {
        val serial = runCatching { device.serialNumber }.getOrNull() ?: device.deviceName
        val defaultLabel = (runCatching { device.productName }.getOrNull() ?: device.deviceName).ifBlank { serial }
        val id = "fastboot:$serial"

        UsbHelper.registerFastbootDeviceMapping(device.deviceName, id)
        connections[id] = usbManager to device
        devices[id] = FastbootDevice(id = id, label = defaultLabel, deviceName = device.deviceName)
        log(LogLevel.SYSTEM, "Device detected: $defaultLabel ($id)")

        if (session == null) connect(id)
    }

    fun onDeviceDisconnected(id: String) {
        devices.remove(id)
        connections.remove(id)
        if (session?.deviceId == id) {
            log(LogLevel.SYSTEM, "Device disconnected: $id")
            closeSession()
        }
    }

    // -----------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------

    fun connect(deviceId: String) {
        if (session?.deviceId == deviceId) return
        closeSession()
        val (usbManager, usbDevice) = connections[deviceId] ?: run {
            log(LogLevel.ERROR, "Cannot connect: device $deviceId no longer present")
            return
        }
        runCatching {
            val iface = UsbFastbootTransport.findFastbootInterface(usbDevice)
                ?: throw FastbootException.Io("No fastboot interface found on device")
            val (inEp, outEp) = UsbFastbootTransport.findFastbootEndpoints(iface)
                ?: throw FastbootException.Io("No fastboot endpoints found on interface")

            val usbConnection: UsbDeviceConnection = usbManager.openDevice(usbDevice)
                ?: throw FastbootException.Io("Failed to open USB device")
            if (!usbConnection.claimInterface(iface, true)) {
                usbConnection.close()
                throw FastbootException.Io("Failed to claim USB interface")
            }

            val transport = AndroidUsbFastbootTransport(usbConnection, iface, inEp, outEp)
            val client = FastbootClient(FastbootConnection(transport))
            session = FastbootSession(deviceId, client)
            log(LogLevel.SYSTEM, "Connected to $deviceId")
        }.onFailure { e ->
            log(LogLevel.ERROR, "Connect failed: ${e.message}")
        }
    }

    fun closeSession() {
        session?.client?.let { runCatching { it.close() } }
        session = null
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }

    // -----------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------

    private fun log(level: LogLevel, text: String) {
        logs.add(LogEntry(level = level, text = text))
    }

    fun clearLogs() = logs.clear()

    fun showShellHelp() {
        log(LogLevel.SYSTEM, FastbootCommandParser.HELP_TEXT)
    }

    // -----------------------------------------------------------------
    // Core action runner — every command funnels through this
    // -----------------------------------------------------------------

    private fun run(
        commandLabel: String,
        opLabel: String,
        block: suspend (FastbootClient, onInfo: (String) -> Unit, onProgress: (Progress) -> Unit) -> String?,
    ): Job {
        val client = activeClient()
        if (client == null) {
            log(LogLevel.ERROR, "No device connected")
            lastOperationStatus = OperationStatus.Failed(opLabel, "No device connected")
            return viewModelScope.launch { }
        }
        log(LogLevel.COMMAND, commandLabel)
        isBusy = true
        currentOperation = opLabel
        currentProgress = null
        lastOperationStatus = OperationStatus.Running(opLabel)
        return viewModelScope.launch {
            try {
                val result = block(
                    client,
                    { info -> log(LogLevel.INFO, info) },
                    { progress -> currentProgress = progress },
                )
                if (result != null) log(LogLevel.RESULT, result)
                log(LogLevel.RESULT, "OKAY")
                lastOperationStatus = OperationStatus.Success(opLabel, result)
            } catch (e: FastbootException) {
                val msg = e.message ?: e.toString()
                log(LogLevel.ERROR, msg)
                lastOperationStatus = OperationStatus.Failed(opLabel, msg)
            } catch (e: Exception) {
                val msg = e.message ?: "Unexpected error"
                log(LogLevel.ERROR, "Unexpected error: $msg")
                lastOperationStatus = OperationStatus.Failed(opLabel, msg)
            } finally {
                isBusy = false
                currentOperation = null
                currentProgress = null
            }
        }
    }

    // -----------------------------------------------------------------
    // Actions — Reboot
    // -----------------------------------------------------------------

    fun reboot(target: FastbootClient.RebootTarget) = run(
        commandLabel = "reboot (${target.name.lowercase()})",
        opLabel = "Rebooting",
    ) { client, onInfo, _ -> client.reboot(target, onInfo = onInfo); null }

    fun rebootTo(customTarget: String) = run(
        commandLabel = "reboot-$customTarget",
        opLabel = "Rebooting",
    ) { client, onInfo, _ -> client.reboot(customTarget, onInfo = onInfo); null }

    fun continueBoot() = run("continue", "Continuing boot") { client, onInfo, _ ->
        client.continueBoot(onInfo = onInfo); null
    }

    fun shutdown() = run("shutdown", "Shutting down") { client, onInfo, _ ->
        client.shutdown(onInfo = onInfo); null
    }

    // -----------------------------------------------------------------
    // Actions — Flash / Boot (shared by dedicated screens AND the shell)
    // -----------------------------------------------------------------

    fun flashImage(partition: String, fd: FileDescriptor, size: Long, displayName: String) = run(
        commandLabel = "flash $partition ($displayName, $size bytes)",
        opLabel = "Flashing $partition",
    ) { client, onInfo, onProgress ->
        client.flash(partition, fd, size, onInfo = onInfo, onProgress = onProgress); null
    }

    fun bootImage(fd: FileDescriptor, size: Long, displayName: String) = run(
        commandLabel = "boot ($displayName, $size bytes)",
        opLabel = "Booting image",
    ) { client, onInfo, onProgress ->
        client.boot(fd, size, onInfo = onInfo, onProgress = onProgress); null
    }

    // -----------------------------------------------------------------
    // Actions — Fetch (device -> local file, via SAF "create document")
    // -----------------------------------------------------------------

    /**
     * [context] is only used to open the SAF-picked destination Uri for writing; it's passed
     * in per-call from the Composable rather than held by the ViewModel.
     */
    fun fetchPartition(context: Context, partition: String, destUri: Uri) = run(
    commandLabel = "fetch $partition",
    opLabel = "Fetching $partition",
) { client, _, onProgress ->
    context.contentResolver.openFileDescriptor(destUri, "w")?.use { pfd ->
        FileOutputStream(pfd.fileDescriptor).use { out ->
            client.fetch(
                partition = partition,
                sink = { data, length -> out.write(data, 0, length) },
                onProgress = onProgress,
            )
        }
    } ?: throw FastbootException.Io("Could not open destination file for writing")
    null
}

    // -----------------------------------------------------------------
    // Actions — Lock state
    // -----------------------------------------------------------------

    fun setLockMode(mode: FastbootClient.LockMode) = run(mode.wire, "Updating lock state") { client, _, _ ->
        client.setLockMode(mode); null
    }

    fun getUnlockAbility() = run("flashing get_unlock_ability", "Checking unlock ability") { client, _, _ ->
        val allowed = client.flashingGetUnlockAbility()
        "unlock_ability: ${if (allowed) "1 (allowed)" else "0 (not allowed)"}"
    }

    // -----------------------------------------------------------------
    // Actions — Variables
    // -----------------------------------------------------------------

    fun getVar(key: String) = run("getvar:$key", "Reading $key") { client, _, _ ->
        "$key: ${client.getVar(key)}"
    }

    fun getAllVars() = run("getvar:all", "Reading all variables") { client, _, _ ->
        client.getAllVars().entries.joinToString("\n") { (k, v) -> "$k: $v" }
    }

    // -----------------------------------------------------------------
    // Actions — Raw / shell passthrough
    // -----------------------------------------------------------------

    /** Entry point for anything typed in the shell tab that isn't a file/save command. */
    fun runShellCommand(wireCommand: String, displayLabel: String) = run(
        commandLabel = displayLabel,
        opLabel = "Running command",
    ) { client, onInfo, _ ->
        client.rawCommand(wireCommand, onInfo = onInfo).response.ifBlank { null }
    }
}