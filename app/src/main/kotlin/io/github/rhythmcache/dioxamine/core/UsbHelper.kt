package io.github.rhythmcache.dioxamine.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

object UsbHelper {
    const val ACTION_USB_PERMISSION = "io.github.rhythmcache.dioxamine.USB_PERMISSION"

    // Maps UsbDevice.deviceName (e.g. "/dev/bus/usb/001/002") to connection ID ("usb:serial")
    private val deviceNameMap = mutableMapOf<String, String>()

    // In UsbHelper - separate map, mirrors the existing deviceNameMap pattern
private val fastbootDeviceNameMap = mutableMapOf<String, String>()

fun registerFastbootDeviceMapping(deviceName: String, connectionId: String) {
    fastbootDeviceNameMap[deviceName] = connectionId
}

fun getFastbootConnectionId(deviceName: String): String? = fastbootDeviceNameMap[deviceName]

fun removeFastbootDeviceMapping(deviceName: String): String? = fastbootDeviceNameMap.remove(deviceName)

    fun registerDeviceMapping(deviceName: String, connectionId: String) {
        deviceNameMap[deviceName] = connectionId
    }

    fun getConnectionId(deviceName: String): String? {
        return deviceNameMap[deviceName]
    }

    fun removeDeviceMapping(deviceName: String): String? {
        return deviceNameMap.remove(deviceName)
    }

    fun isDeviceNamePresent(connectionId: String, activeUsbNames: Set<String>): Boolean {
        return deviceNameMap.entries.any { (deviceName, connId) -> connId == connectionId && deviceName in activeUsbNames }
    }

    fun isFastbootDeviceNamePresent(connectionId: String, activeUsbNames: Set<String>): Boolean {
        return fastbootDeviceNameMap.entries.any { (deviceName, connId) -> connId == connectionId && deviceName in activeUsbNames }
    }

    fun scanAndConnectUsbDevices(
        context: Context,
        usbManager: UsbManager,
        matcher: UsbInterfaceMatcher,
        onConnect: (UsbManager, UsbDevice) -> Unit
    ) {
        val deviceList = usbManager.deviceList ?: return
        for (device in deviceList.values) {
            val iface = UsbPacketTransport.findMatchingInterface(device, matcher) ?: continue
            if (usbManager.hasPermission(device)) {
                onConnect(usbManager, device)
            } else {
                requestUsbPermission(context, usbManager, device)
            }
        }
    }

    fun requestUsbPermission(context: Context, usbManager: UsbManager, device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }
}
