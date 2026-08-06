package io.github.rhythmcache.dioxamine.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.github.rhythmcache.dioxamine.core.*
import kotlinx.coroutines.delay

@Composable
fun ListenForUsbDevices(
    vm: AdbViewModel,
    matcher: UsbInterfaceMatcher = UsbPacketTransport.ADB_INTERFACE_MATCHER
) {
    val context = LocalContext.current
    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return

    DisposableEffect(context, vm) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val action = intent.action ?: return
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                        if (device != null && UsbPacketTransport.findMatchingInterface(device, matcher) != null) {
                            if (usbManager.hasPermission(device)) {
                                vm.connectUsb(usbManager, device)
                            } else {
                                UsbHelper.requestUsbPermission(ctx, usbManager, device)
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                        if (device != null) {
                            val connId = UsbHelper.removeDeviceMapping(device.deviceName)
                            if (connId != null) {
                                vm.disconnect(connId)
                            } else {
                                // Fallback serial lookup
                                val serial = runCatching { device.serialNumber }.getOrNull() ?: device.deviceName
                                vm.disconnect("usb:$serial")
                            }
                        }
                    }
                    UsbHelper.ACTION_USB_PERMISSION -> {
                        synchronized(this) {
                            val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            if (granted && device != null && UsbPacketTransport.findMatchingInterface(device, matcher) != null) {
                                vm.connectUsb(usbManager, device)
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbHelper.ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // Initial scan for devices already plugged in
        UsbHelper.scanAndConnectUsbDevices(context, usbManager, matcher) { mgr, dev -> vm.connectUsb(mgr, dev) }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Periodic status poll to automatically prune unplugged USB devices
    LaunchedEffect(vm) {
        while (true) {
            delay(3000)
            val activeUsbNames = usbManager.deviceList.values.map { it.deviceName }.toSet()
            val toRemove = mutableListOf<String>()
            for (conn in vm.devices.values) {
                if (conn.transport == DeviceTransport.USB) {
                    val isStillAttached = UsbHelper.isDeviceNamePresent(conn.id, activeUsbNames)
                    if (!isStillAttached) {
                        toRemove.add(conn.id)
                    }
                }
            }
            for (id in toRemove) {
                vm.disconnect(id)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
