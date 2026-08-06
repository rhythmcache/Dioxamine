package io.github.rhythmcache.dioxamine.fastboot

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
import io.github.rhythmcache.dioxamine.core.UsbFastbootTransport
import io.github.rhythmcache.dioxamine.core.UsbHelper
import io.github.rhythmcache.dioxamine.core.UsbPacketTransport
import kotlinx.coroutines.delay

@Composable
fun ListenForFastbootDevices(vm: FastbootViewModel) {
    val context = LocalContext.current
    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
    val matcher = UsbFastbootTransport.FASTBOOT_INTERFACE_MATCHER

    DisposableEffect(context, vm) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val action = intent.action ?: return
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                        if (device != null && UsbPacketTransport.findMatchingInterface(device, matcher) != null) {
                            if (usbManager.hasPermission(device)) {
                                vm.onDeviceDetected(usbManager, device)
                            } else {
                                UsbHelper.requestUsbPermission(ctx, usbManager, device)
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                        if (device != null) {
                            val connId = UsbHelper.removeFastbootDeviceMapping(device.deviceName)
                            if (connId != null) {
                                vm.onDeviceDisconnected(connId)
                            } else {
                                val serial = runCatching { device.serialNumber }.getOrNull() ?: device.deviceName
                                vm.onDeviceDisconnected("fastboot:$serial")
                            }
                        }
                    }
                    UsbHelper.ACTION_USB_PERMISSION -> {
                        synchronized(this) {
                            val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            if (granted && device != null && UsbPacketTransport.findMatchingInterface(device, matcher) != null) {
                                vm.onDeviceDetected(usbManager, device)
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

        // Initial scan for devices already plugged in / already in fastboot mode
        UsbHelper.scanAndConnectUsbDevices(context, usbManager, matcher) { mgr, dev ->
            vm.onDeviceDetected(mgr, dev)
        }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Periodic prune catches detach events missed for any reason
    LaunchedEffect(vm) {
        while (true) {
            delay(3000)
            val activeUsbNames = usbManager.deviceList.values.map { it.deviceName }.toSet()
            val toRemove = mutableListOf<String>()
            for (device in vm.devices.values) {
                val isStillAttached = UsbHelper.isFastbootDeviceNamePresent(device.id, activeUsbNames)
                if (!isStillAttached) {
                    toRemove.add(device.id)
                }
            }
            for (id in toRemove) {
                vm.onDeviceDisconnected(id)
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