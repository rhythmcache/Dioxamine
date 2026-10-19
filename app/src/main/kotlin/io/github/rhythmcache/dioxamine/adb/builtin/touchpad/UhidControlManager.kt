package io.github.rhythmcache.dioxamine.adb.builtin.touchpad

import android.content.Context
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbEndpoint
import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance ADB control session with scrcpy-server running in control-only mode.
 * Operates kernel /dev/uhid virtual mouse and keyboard with:
 * - Atomic Compare-And-Set (CAS) delta consumption (zero motion loss on large swipes)
 * - Allocation-conscious direct byte packet construction
 * - Deterministic sequential keystroke channel (guaranteed FIFO ordering with wire-direct writes)
 * - Serialized click sequencing with Mutex protection and sequential HID keystroke worker
 * - Synchronous device registration before writer loop startup
 * - UTF-8 character boundary safety during text injection truncation
 */
class UhidControlManager(
    private val context: Context,
    private val client: AdbClient,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit
) : VirtualKeyboardController {
    companion object {
        private const val TAG = "UHID_CONTROL"

        const val ID_MOUSE = 0
        const val ID_KEYBOARD = 1

        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_UHID_CREATE = 12
        const val TYPE_UHID_INPUT = 13
        const val TYPE_UHID_DESTROY = 14

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        // Standard 5-byte HID Mouse Report Descriptor
        val MOUSE_REPORT_DESC = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Buttons)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (1)
            0x29.toByte(), 0x05.toByte(), //     Usage Maximum (5)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x95.toByte(), 0x05.toByte(), //     Report Count (5)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x81.toByte(), 0x02.toByte(), //     Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x75.toByte(), 0x03.toByte(), //     Report Size (3)
            0x81.toByte(), 0x01.toByte(), //     Input (Constant)
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0x05.toByte(), 0x0C.toByte(), //     Usage Page (Consumer Page)
            0x0A.toByte(), 0x38.toByte(), //     Usage (AC Pan)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0xC0.toByte(),                 //   End Collection
            0xC0.toByte()                  // End Collection
        )

        // Standard 8-byte HID Keyboard Report Descriptor
        val KEYBOARD_REPORT_DESC = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Key Codes)
            0x19.toByte(), 0xE0.toByte(), //   Usage Minimum (224)
            0x29.toByte(), 0xE7.toByte(), //   Usage Maximum (231)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x95.toByte(), 0x08.toByte(), //   Report Count (8)
            0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x81.toByte(), 0x01.toByte(), //   Input (Constant)
            0x05.toByte(), 0x08.toByte(), //   Usage Page (LEDs)
            0x19.toByte(), 0x01.toByte(), //   Usage Minimum (1)
            0x29.toByte(), 0x05.toByte(), //   Usage Maximum (5)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x95.toByte(), 0x05.toByte(), //   Report Count (5)
            0x91.toByte(), 0x02.toByte(), //   Output (Data, Variable, Absolute)
            0x75.toByte(), 0x03.toByte(), //   Report Size (3)
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x91.toByte(), 0x01.toByte(), //   Output (Constant)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Key Codes)
            0x19.toByte(), 0x00.toByte(), //   Usage Minimum (0)
            0x29.toByte(), 0xFF.toByte(), //   Usage Maximum (255)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), // Logical Maximum (255)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x95.toByte(), 0x06.toByte(), //   Report Count (6)
            0x81.toByte(), 0x00.toByte(), //   Input (Data, Array)
            0xC0.toByte()                  // End Collection
        )

        /**
         * Calculates the byte length of the next valid UTF-8 chunk not exceeding [maxLen].
         * Delegated to [VirtualKeyboardHelper.safeUtf8ChunkLength].
         */
        internal fun safeUtf8ChunkLength(bytes: ByteArray, offset: Int, maxLen: Int): Int =
            VirtualKeyboardHelper.safeUtf8ChunkLength(bytes, offset, maxLen)
    }

    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var sessionJob: Job? = null
    private var serverStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    // Unbounded channel guarantees deterministic FIFO ordering and zero coroutine spawning
    private val channelLock = Any()
    private val streamWriteMutex = Mutex()
    private val clickMutex = Mutex()

    @Volatile private var keyboardActionChannel = Channel<KeyboardAction>(Channel.UNLIMITED)
    private var keyStrokeJob: Job? = null

    // Motion Accumulator with CAS delta consumption
    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)
    private val pendingWheel = AtomicInteger(0)
    private val pendingHPan = AtomicInteger(0)
    private val currentMouseButtons = AtomicInteger(0)
    private val mouseStateChanged = AtomicBoolean(false)

    @Volatile private var isRunning = false

    fun start() {
        if (sessionJob?.isActive == true) return
        isRunning = true

        if (supervisorJob.isCancelled) {
            supervisorJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + supervisorJob)
        }
        synchronized(channelLock) {
            if (keyboardActionChannel.isClosedForSend) {
                keyboardActionChannel = Channel(Channel.UNLIMITED)
            }
        }

        sessionJob = scope.launch {
            try {
                // Step 1: Clean up any stale scrcpy instances
                runCatching {
                    val killStream = client.open("shell:pkill -f com.genymobile.scrcpy.Server || killall com.genymobile.scrcpy.Server")
                    val buf = ByteArray(128)
                    runCatching { killStream.read(buf) }
                    killStream.close()
                }

                // Step 2: Push scrcpy-server asset
                AppLogger.i(TAG, "Pushing scrcpy-server.jar...")
                context.assets.open("scrcpy-server.jar").use { input ->
                    client.sync.push(input, "${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar")
                }

                // Step 3: Launch server in control-only mode
                val serverCmd = "CLASSPATH=${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server ${Constants.SCRCPY_SERVER_VERSION} video=false audio=false control=true tunnel_forward=true cleanup=true send_dummy_byte=true"
                AppLogger.i(TAG, "Starting server: $serverCmd")

                launch {
                    try {
                        val stream = client.open("shell:$serverCmd")
                        serverStream = stream
                        val buf = ByteArray(4096)
                        while (isActive) {
                            val n = stream.read(buf)
                            if (n == -1) break
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            AppLogger.e(TAG, "Server shell stopped: ${e.message}")
                        }
                    }
                }

                delay(350)

                // Step 4: Connect to scrcpy control socket
                AppLogger.i(TAG, "Connecting to scrcpy control socket...")
                var stream: AdbStream? = null
                for (attempt in 1..25) {
                    if (!isActive || !isRunning) break
                    try {
                        stream = client.open(AdbEndpoint.LocalAbstract("scrcpy"))
                        break
                    } catch (_: Exception) {
                        delay(150)
                    }
                }

                val activeControlStream = stream ?: throw Exception("Could not connect to virtual input control socket.")
                controlStream = activeControlStream

                // Read dummy handshake byte
                val dummyBuf = ByteArray(1)
                val readDummy = activeControlStream.read(dummyBuf)
                if (readDummy == -1) throw Exception("Failed to handshake with virtual input service.")

                // Step 5: Synchronously register UHID Mouse and Keyboard BEFORE starting writer loop
                AppLogger.i(TAG, "Registering UHID Mouse and Keyboard devices...")
                activeControlStream.write(buildCreateDevicePacket(ID_MOUSE, 0x18d1, 0x0001, "Dioxamine Mouse", MOUSE_REPORT_DESC))
                activeControlStream.write(buildCreateDevicePacket(ID_KEYBOARD, 0x18d1, 0x0002, "Dioxamine Keyboard", KEYBOARD_REPORT_DESC))

                // Launch sequential FIFO keyboard processor (preserves strict ordering of HID keys & injected text)
                keyStrokeJob = launch {
                    for (action in keyboardActionChannel) {
                        if (!isActive || !isRunning || activeControlStream.isClosed) break
                        when (action) {
                            is KeyboardAction.Stroke -> {
                                try {
                                    sendKeyboardReport(action.modifiers, listOf(action.keyCode))
                                    delay(12)
                                } finally {
                                    runCatching {
                                        withContext(NonCancellable) {
                                            sendKeyboardReport(0, emptyList())
                                            delay(4)
                                        }
                                    }
                                }
                            }
                            is KeyboardAction.Text -> {
                                sendInjectTextInternal(action.text)
                                delay(8)
                            }
                        }
                    }
                }

                // Step 6: Start dedicated 125 Hz mouse motion loop with fair CAS delta consumption
                launch {
                    val mouseBuf = ByteArray(10)
                    mouseBuf[0] = TYPE_UHID_INPUT.toByte()
                    mouseBuf[1] = 0
                    mouseBuf[2] = ID_MOUSE.toByte()
                    mouseBuf[3] = 0
                    mouseBuf[4] = 5 // report len

                    try {
                        while (isActive && isRunning && !activeControlStream.isClosed) {
                            val hadStateChange = mouseStateChanged.getAndSet(false)
                            if (hadStateChange || !streamWriteMutex.isLocked) {
                                val dx = consumeDelta(pendingDx)
                                val dy = consumeDelta(pendingDy)
                                val wheel = consumeDelta(pendingWheel)
                                val hPan = consumeDelta(pendingHPan)

                                if (dx != 0 || dy != 0 || wheel != 0 || hPan != 0 || hadStateChange) {
                                    mouseBuf[5] = currentMouseButtons.get().toByte()
                                    mouseBuf[6] = dx.toByte()
                                    mouseBuf[7] = dy.toByte()
                                    mouseBuf[8] = wheel.toByte()
                                    mouseBuf[9] = hPan.toByte()
                                    val ok = writePacketDirect(mouseBuf)
                                    if (!ok) {
                                        if (hadStateChange) mouseStateChanged.set(true)
                                        if (dx != 0) pendingDx.addAndGet(dx)
                                        if (dy != 0) pendingDy.addAndGet(dy)
                                        if (wheel != 0) pendingWheel.addAndGet(wheel)
                                        if (hPan != 0) pendingHPan.addAndGet(hPan)
                                    }
                                }
                            }

                            // 8ms tick rate (~125 Hz)
                            delay(8)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            AppLogger.e(TAG, "Mouse writer loop error: ${e.message}", e)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    onConnected()
                }
            } catch (e: Exception) {
                if (isRunning) {
                    AppLogger.e(TAG, "Failed to initialize UHID session: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        onError(e.message ?: "Failed to initialize virtual mouse and keyboard.")
                    }
                }
            }
        }
    }

    /**
     * Consumes up to [-127, 127] from the atomic accumulator via CAS.
     * Retains any remainder in the accumulator for subsequent ticks to prevent motion loss on fast swipes.
     */
    private fun consumeDelta(accumulator: AtomicInteger): Int {
        while (true) {
            val current = accumulator.get()
            if (current == 0) return 0
            val amount = current.coerceIn(-127, 127)
            if (accumulator.compareAndSet(current, current - amount)) {
                return amount
            }
        }
    }

    private fun buildCreateDevicePacket(
        id: Int,
        vendorId: Int,
        productId: Int,
        name: String,
        reportDesc: ByteArray
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 2 + 2 + 2 + 1 + nameBytes.size + 2 + reportDesc.size)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_UHID_CREATE.toByte())
        buf.putShort(id.toShort())
        buf.putShort(vendorId.toShort())
        buf.putShort(productId.toShort())
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.putShort(reportDesc.size.toShort())
        buf.put(reportDesc)
        return buf.array()
    }

    /**
     * Coalesces relative mouse movement into the atomic accumulator without mutating button state.
     * Allocation-conscious touch move pipeline.
     */
    fun sendMouseMove(dx: Int, dy: Int) {
        pendingDx.addAndGet(dx)
        pendingDy.addAndGet(dy)
    }

    /**
     * Coalesces relative mouse movement and explicitly updates mouse buttons.
     */
    fun sendMouseMove(dx: Int, dy: Int, buttons: Int) {
        currentMouseButtons.set(buttons)
        mouseStateChanged.set(true)
        pendingDx.addAndGet(dx)
        pendingDy.addAndGet(dy)
    }

    /**
     * Sets the active mouse button state.
     */
    fun setMouseButtonState(buttons: Int) {
        currentMouseButtons.set(buttons)
        mouseStateChanged.set(true)
    }

    /**
     * Performs a momentary click of the specified button mask with serialized Mutex sequencing
     * and atomic CAS operations to prevent race conditions with concurrent button updates.
     */
    fun clickMouseButton(buttonMask: Int) {
        scope.launch {
            clickMutex.withLock {
                try {
                    currentMouseButtons.updateAndGet { it or buttonMask }
                    mouseStateChanged.set(true)
                    delay(40)
                } finally {
                    withContext(NonCancellable) {
                        currentMouseButtons.updateAndGet { it and buttonMask.inv() }
                        mouseStateChanged.set(true)
                    }
                }
            }
        }
    }

    /**
     * Sends vertical mouse wheel and horizontal scroll deltas.
     */
    fun sendMouseScroll(wheel: Int, hPan: Int = 0) {
        pendingWheel.addAndGet(wheel)
        pendingHPan.addAndGet(hPan)
    }

    /**
     * Writes packet directly to control stream under mutex.
     * Guarantees wire ordering without intermediate queue delays.
     */
    private suspend fun writePacketDirect(packet: ByteArray): Boolean {
        val stream = controlStream ?: run {
            AppLogger.d(TAG, "Dropping packet (control stream not connected)")
            return false
        }
        return streamWriteMutex.withLock {
            runCatching {
                if (!stream.isClosed) {
                    stream.write(packet)
                    true
                } else {
                    false
                }
            }.getOrElse { e ->
                if (isRunning && e !is CancellationException) {
                    AppLogger.w(TAG, "Failed to write packet direct: ${e.message}")
                }
                false
            }
        }
    }

    private fun buildKeyboardReportPacket(modifiers: Int = 0, keys: List<Int> = emptyList()): ByteArray {
        val packet = ByteArray(13)
        packet[0] = TYPE_UHID_INPUT.toByte()
        packet[1] = 0
        packet[2] = ID_KEYBOARD.toByte()
        packet[3] = 0
        packet[4] = 8 // 8-byte report length
        packet[5] = modifiers.toByte()
        packet[6] = 0 // Reserved
        val count = minOf(6, keys.size)
        for (i in 0 until count) {
            packet[7 + i] = keys[i].toByte()
        }
        return packet
    }

    private fun buildDestroyDevicePacket(id: Int): ByteArray {
        val buf = ByteBuffer.allocate(3)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_UHID_DESTROY.toByte())
        buf.putShort(id.toShort())
        return buf.array()
    }

    /**
     * Sends raw 8-byte HID keyboard report using direct byte construction.
     * Internal to the keyboard sequential processor to prevent interleaving.
     */
    private suspend fun sendKeyboardReport(modifiers: Int, keys: List<Int>) {
        writePacketDirect(buildKeyboardReportPacket(modifiers, keys))
    }

    /**
     * Sends a single key press and release event with optional modifiers via sequential FIFO channel.
     */
    override fun sendKeyStroke(hidKeyCode: Int, modifiers: Int) {
        keyboardActionChannel.trySend(KeyboardAction.Stroke(hidKeyCode, modifiers)).onFailure {
            AppLogger.w(TAG, "Failed to enqueue key stroke (channel closed)")
        }
    }

    /**
     * Injects text directly using scrcpy text injection with UTF-8 character boundary safety,
     * routed through the sequential keyboard channel to preserve ordering with HID keystrokes.
     */
    override fun sendInjectText(text: String) {
        if (text.isEmpty()) return
        keyboardActionChannel.trySend(KeyboardAction.Text(text)).onFailure {
            AppLogger.w(TAG, "Failed to enqueue inject text (channel closed)")
        }
    }

    private suspend fun sendInjectTextInternal(text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < textBytes.size && isRunning) {
            val chunkLen = safeUtf8ChunkLength(textBytes, offset, 300)
            if (chunkLen <= 0) break

            val packet = ByteArray(5 + chunkLen)
            packet[0] = TYPE_INJECT_TEXT.toByte()
            packet[1] = (chunkLen ushr 24).toByte()
            packet[2] = (chunkLen ushr 16).toByte()
            packet[3] = (chunkLen ushr 8).toByte()
            packet[4] = chunkLen.toByte()
            System.arraycopy(textBytes, offset, packet, 5, chunkLen)
            writePacketDirect(packet)

            offset += chunkLen
        }
    }

    /**
     * Sends Android keycode (Home, Back, Volume, etc.).
     */
    fun sendAndroidKeycode(keyCode: Int) {
        if (!isRunning) {
            AppLogger.w(TAG, "Dropping keycode $keyCode (manager not running)")
            return
        }
        scope.launch {
            sendKeycodePacket(ACTION_DOWN, keyCode)
            sendKeycodePacket(ACTION_UP, keyCode)
        }
    }

    private suspend fun sendKeycodePacket(action: Int, keyCode: Int) {
        val packet = ByteArray(14)
        packet[0] = TYPE_INJECT_KEYCODE.toByte()
        packet[1] = action.toByte()
        packet[2] = (keyCode ushr 24).toByte()
        packet[3] = (keyCode ushr 16).toByte()
        packet[4] = (keyCode ushr 8).toByte()
        packet[5] = keyCode.toByte()
        writePacketDirect(packet)
    }

    fun close() {
        isRunning = false

        // 1. Close incoming keyboard channel and cancel worker so pending actions are discarded
        synchronized(channelLock) {
            keyboardActionChannel.close()
        }
        runCatching { keyStrokeJob?.cancel() }

        // 2. Guarantee active key release and UHID destruction synchronized against writer loop
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(300) {
                    controlStream?.let { stream ->
                        streamWriteMutex.withLock {
                            if (!stream.isClosed) {
                                // Release mouse buttons if held
                                if (currentMouseButtons.getAndSet(0) != 0) {
                                    val mouseRelease = ByteArray(10)
                                    mouseRelease[0] = TYPE_UHID_INPUT.toByte()
                                    mouseRelease[1] = 0
                                    mouseRelease[2] = ID_MOUSE.toByte()
                                    mouseRelease[3] = 0
                                    mouseRelease[4] = 5
                                    stream.write(mouseRelease)
                                }

                                // Send explicit keyboard release (all 0s) to prevent stuck keys
                                stream.write(buildKeyboardReportPacket(0, emptyList()))

                                // Send UHID destroy packets to unregister devices from kernel /dev/uhid
                                stream.write(buildDestroyDevicePacket(ID_KEYBOARD))
                                stream.write(buildDestroyDevicePacket(ID_MOUSE))
                            }
                        }
                    }
                }
            }
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to send teardown packets: ${e.message}")
        }

        supervisorJob.cancel()
        runCatching { controlStream?.close() }
        runCatching { serverStream?.close() }
        controlStream = null
        serverStream = null
    }
}
