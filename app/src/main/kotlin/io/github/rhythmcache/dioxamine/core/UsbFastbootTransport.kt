package io.github.rhythmcache.dioxamine.core

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.github.rhythmcache.fastboot.FastbootException
import io.github.rhythmcache.fastboot.FastbootTransport

/**
 * Fastboot USB Interface Matcher (Class 0xFF, Subclass 0x42, Protocol 0x03)
 * and Android USB Transport implementation for fastboot-kt.
 */
object UsbFastbootTransport {
    val FASTBOOT_INTERFACE_MATCHER = UsbInterfaceMatcher(0xFF, 0x42, 0x03)

    fun findFastbootInterface(device: UsbDevice): UsbInterface? =
        UsbPacketTransport.findMatchingInterface(device, FASTBOOT_INTERFACE_MATCHER)

    fun findFastbootEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? =
        UsbPacketTransport.findAdbEndpoints(iface)
}

class AndroidUsbFastbootTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val timeoutMs: Int = 10000,
) : FastbootTransport {

    @Volatile
    private var isClosed = false

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (isClosed) throw FastbootException.Io("USB transport is closed")
        var bytesWritten = 0
        val maxChunk = maxOf(outEndpoint.maxPacketSize, 4096)
        while (bytesWritten < length) {
            val chunkSize = minOf(length - bytesWritten, maxChunk)
            val ret = connection.bulkTransfer(outEndpoint, buffer, offset + bytesWritten, chunkSize, timeoutMs)
            if (ret <= 0) {
                throw FastbootException.Io("USB bulk write failed ($ret)")
            }
            bytesWritten += ret
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isClosed) throw FastbootException.Io("USB transport is closed")
        val ret = connection.bulkTransfer(inEndpoint, buffer, offset, length, timeoutMs)
        if (ret < 0) {
            throw FastbootException.Io("USB bulk read failed ($ret)")
        }
        return ret
    }

    override fun reset() {
        // Reset USB interface if needed
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }
}