package io.github.rhythmcache.dioxamine.core

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.github.rhythmcache.adb.AdbException
import io.github.rhythmcache.adb.AdbPacket
import io.github.rhythmcache.adb.PacketTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class UsbInterfaceMatcher(val interfaceClass: Int, val interfaceSubclass: Int, val interfaceProtocol: Int)

/**
 * Implementation of adb-kt PacketTransport for Android USB Host API.
 * Transmits ADB packets over USB bulk endpoints.
 */
class UsbPacketTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val timeoutMs: Int = 10000
) : PacketTransport {

    private val sendLock = ReentrantLock()
    private val recvLock = ReentrantLock()

    // Read buffer aligned to USB maxPacketSize
    private val readBuffer = ByteArray(maxOf(inEndpoint.maxPacketSize, 4096))
    private var readBufferOffset = 0
    private var readBufferLength = 0

    @Volatile
    private var isClosed = false

    override fun send(pkt: AdbPacket) {
        if (isClosed) throw AdbException.StreamClosed("USB transport is closed")

        val payloadSize = pkt.payloadLength
        val payloadChecksum = AdbPacket.checksum(pkt.payload, pkt.payloadOffset, pkt.payloadLength)
        val magic = pkt.command xor -1

        val headerBuffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        headerBuffer.putInt(pkt.command)
        headerBuffer.putInt(pkt.arg0)
        headerBuffer.putInt(pkt.arg1)
        headerBuffer.putInt(payloadSize)
        headerBuffer.putInt(payloadChecksum)
        headerBuffer.putInt(magic)

        val headerArray = headerBuffer.array()

        sendLock.withLock {
            val headerRet = connection.bulkTransfer(outEndpoint, headerArray, 0, 24, timeoutMs)
            if (headerRet != 24) {
                throw AdbException.Transport("USB bulk write header failed ($headerRet)")
            }

            if (payloadSize > 0) {
                var bytesWritten = 0
                val maxWriteSize = maxOf(outEndpoint.maxPacketSize, 4096)
                while (bytesWritten < payloadSize) {
                    if (isClosed) throw AdbException.StreamClosed("USB transport closed during payload write")
                    val chunkSize = minOf(payloadSize - bytesWritten, maxWriteSize)
                    val ret = connection.bulkTransfer(
                        outEndpoint,
                        pkt.payload,
                        pkt.payloadOffset + bytesWritten,
                        chunkSize,
                        timeoutMs,
                    )
                    if (ret <= 0) {
                        throw AdbException.Transport("USB bulk write payload failed ($ret)")
                    }
                    bytesWritten += ret
                }
            }

            if (outEndpoint.maxPacketSize > 0 && payloadSize > 0 && payloadSize % outEndpoint.maxPacketSize == 0) {
                connection.bulkTransfer(outEndpoint, ByteArray(0), 0, timeoutMs)
            }
        }
    }

    override fun recv(): AdbPacket {
        if (isClosed) throw AdbException.StreamClosed("USB transport is closed")

        recvLock.withLock {
            val headerBytes = ByteArray(24)
            // Idle-between-packets: nothing committed yet, safe to signal Timeout and let
            // AdbConnection's reader loop retry recv() from scratch.
            readExact(headerBytes, 24, allowTimeoutRetry = true)

            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val len = buffer.int
            val expectedChecksum = buffer.int
            val magic = buffer.int

            if ((command xor magic) != -1) {
                throw AdbException.Protocol("Invalid ADB USB packet magic (cmd: 0x${command.toString(16)}, magic: 0x${magic.toString(16)})")
            }

            val payload = if (len > 0) {
                val payloadBytes = ByteArray(len)
                // Mid-frame: header already consumed off the wire. A timeout here can't be
                // safely retried without desyncing the stream, so it must stay fatal.
                readExact(payloadBytes, len, allowTimeoutRetry = false)

                // Validate payload checksum against expected header checksum if expectedChecksum is provided.
                // Protocol version >= 0x01000001 (Android 8+) skips checksum calculation by setting expectedChecksum to 0.
                if (expectedChecksum != 0) {
                    val actualChecksum = AdbPacket.checksum(payloadBytes)
                    if (actualChecksum != expectedChecksum) {
                        throw AdbException.Protocol(
                            "USB ADB checksum mismatch: header expected 0x${expectedChecksum.toString(16)}, got 0x${actualChecksum.toString(16)}"
                        )
                    }
                }

                payloadBytes
            } else {
                ByteArray(0)
            }

            return AdbPacket(command, arg0, arg1, payload)
        }
    }

    /**
     * Reads one chunk from the USB IN endpoint into [buffer].
     * Returns the number of bytes copied, or -1 if the underlying bulkTransfer
     * timed out or returned an ambiguous non-positive result (no real data).
     */
    private fun readChunk(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readBufferOffset >= readBufferLength) {
            val maxReadSize = readBuffer.size
            val ret = connection.bulkTransfer(inEndpoint, readBuffer, 0, maxReadSize, timeoutMs)
            if (ret <= 0) {
                // Ambiguous on Android's API: could be a plain idle timeout (completely normal
                // when no ADB traffic is flowing) or a genuine pipe/device error. We can't tell
                // them apart here, so we surface it as -1 and let readExact() decide, based on
                // whether we're at a safe retry point (start of a fresh packet) or not.
                return -1
            }
            readBufferOffset = 0
            readBufferLength = ret
        }
        val bytesToCopy = minOf(length, readBufferLength - readBufferOffset)
        System.arraycopy(readBuffer, readBufferOffset, buffer, offset, bytesToCopy)
        readBufferOffset += bytesToCopy
        return bytesToCopy
    }

    private fun readExact(buffer: ByteArray, length: Int, allowTimeoutRetry: Boolean) {
        var bytesRead = 0
        while (bytesRead < length) {
            if (isClosed) throw AdbException.StreamClosed("USB transport closed during read")
            val ret = readChunk(buffer, bytesRead, length - bytesRead)
            if (ret == -1) {
                if (allowTimeoutRetry && bytesRead == 0) {
                    // Nothing committed yet for this field - safe for the caller (AdbConnection's
                    // reader loop) to just call recv() again.
                    throw AdbException.Timeout("USB bulk read timed out waiting for next packet")
                }
                // We're mid-frame (already read part of this field, or retry isn't allowed here).
                // Can't safely resume without desyncing the wire - must be fatal.
                throw AdbException.Transport("USB bulk read failed after $bytesRead/$length bytes (mid-frame timeout or error)")
            }
            bytesRead += ret
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    companion object {
        val ADB_INTERFACE_MATCHER = UsbInterfaceMatcher(0xFF, 0x42, 0x01)

        /**
         * Scans a UsbDevice for an interface matching the specified class/subclass/protocol.
         */
        fun findMatchingInterface(device: UsbDevice, matcher: UsbInterfaceMatcher): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == matcher.interfaceClass &&
                    iface.interfaceSubclass == matcher.interfaceSubclass &&
                    iface.interfaceProtocol == matcher.interfaceProtocol) {
                    return iface
                }
            }
            return null
        }

        /**
         * Scans a UsbDevice for an ADB interface (Class 0xFF / Subclass 0x42 / Protocol 0x01).
         */
        fun findAdbInterface(device: UsbDevice): UsbInterface? {
            return findMatchingInterface(device, ADB_INTERFACE_MATCHER)
        }

        /**
         * Discovers USB bulk IN and OUT endpoints from an ADB UsbInterface.
         */
        fun findAdbEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        inEp = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEp = ep
                    }
                }
            }
            return if (inEp != null && outEp != null) Pair(inEp, outEp) else null
        }
    }
}