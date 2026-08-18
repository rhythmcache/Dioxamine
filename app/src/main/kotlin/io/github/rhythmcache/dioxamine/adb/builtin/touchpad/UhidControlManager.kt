package io.github.rhythmcache.dioxamine.adb.builtin.touchpad

import android.content.Context
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbEndpoint
import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance ADB control session with scrcpy-server running in control-only mode.
 * Operates kernel /dev/uhid virtual mouse and keyboard with:
 * - Atomic Compare-And-Set (CAS) delta consumption (zero motion loss on large swipes)
 * - Allocation-conscious direct byte packet construction
 * - Deterministic unbounded command queue for keyboard/text (guaranteed FIFO ordering)
 * - Serialized click and keystroke sequencing with Mutex protection
 * - Synchronous device registration before writer loop startup
 * - UTF-8 character boundary safety during text injection truncation
 */
class UhidControlManager(
    private val context: Context,
    private val client: AdbClient,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit
) {
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
    }

    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var sessionJob: Job? = null
    private var serverStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    // Unbounded channel guarantees deterministic FIFO ordering and zero coroutine spawning
    private val commandQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val clickMutex = Mutex()
    private val keyMutex = Mutex()

    // Motion Accumulator with CAS delta consumption
    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)
    private val pendingWheel = AtomicInteger(0)
    private val pendingHPan = AtomicInteger(0)
    @Volatile private var currentMouseButtons = 0
    @Volatile private var mouseStateChanged = false

    @Volatile private var isRunning = false

    fun start() {
        if (sessionJob?.isActive == true) return
        isRunning = true

        if (supervisorJob.isCancelled) {
            supervisorJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + supervisorJob)
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
                val serverCmd = "CLASSPATH=${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 video=false audio=false control=true tunnel_forward=true cleanup=true send_dummy_byte=true"
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

                // Step 6: Start unified high-efficiency writer loop with fair scheduling and CAS delta consumption
                launch {
                    val mouseBuf = ByteArray(10)
                    mouseBuf[0] = TYPE_UHID_INPUT.toByte()
                    mouseBuf[1] = 0
                    mouseBuf[2] = ID_MOUSE.toByte()
                    mouseBuf[3] = 0
                    mouseBuf[4] = 5 // report len

                    while (isActive && !activeControlStream.isClosed) {
                        // 1. Consume mouse deltas via CAS without discarding remainders > 127
                        val dx = consumeDelta(pendingDx)
                        val dy = consumeDelta(pendingDy)
                        val wheel = consumeDelta(pendingWheel)
                        val hPan = consumeDelta(pendingHPan)
                        val hadStateChange = mouseStateChanged
                        mouseStateChanged = false

                        if (dx != 0 || dy != 0 || wheel != 0 || hPan != 0 || hadStateChange) {
                            mouseBuf[5] = currentMouseButtons.toByte()
                            mouseBuf[6] = dx.toByte()
                            mouseBuf[7] = dy.toByte()
                            mouseBuf[8] = wheel.toByte()
                            mouseBuf[9] = hPan.toByte()
                            activeControlStream.write(mouseBuf)
                        }

                        // 2. Process pending reliable commands with a fairness bound
                        var processedCount = 0
                        while (processedCount < 16 && isActive && !activeControlStream.isClosed) {
                            val cmd = commandQueue.tryReceive().getOrNull() ?: break
                            activeControlStream.write(cmd)
                            processedCount++
                        }

                        // 8ms tick rate (~125 Hz)
                        delay(8)
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
     * Coalesces relative mouse movement into the atomic accumulator.
     * Allocation-conscious touch move pipeline.
     */
    fun sendMouseMove(dx: Int, dy: Int, buttons: Int = currentMouseButtons) {
        currentMouseButtons = buttons
        pendingDx.addAndGet(dx)
        pendingDy.addAndGet(dy)
    }

    /**
     * Sets the active mouse button state.
     */
    fun setMouseButtonState(buttons: Int) {
        currentMouseButtons = buttons
        mouseStateChanged = true
    }

    /**
     * Performs a momentary click of the specified button mask with serialized Mutex sequencing.
     */
    fun clickMouseButton(buttonMask: Int) {
        scope.launch {
            clickMutex.withLock {
                setMouseButtonState(currentMouseButtons or buttonMask)
                delay(40)
                setMouseButtonState(currentMouseButtons and buttonMask.inv())
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
     * Sends raw 8-byte HID keyboard report using direct byte construction.
     */
    fun sendKeyboardReport(modifiers: Int, keys: List<Int>) {
        val packet = ByteArray(13)
        packet[0] = TYPE_UHID_INPUT.toByte()
        packet[1] = 0
        packet[2] = ID_KEYBOARD.toByte()
        packet[3] = 0
        packet[4] = 8 // 8-byte report
        packet[5] = modifiers.toByte()
        packet[6] = 0 // Reserved
        val count = minOf(6, keys.size)
        for (i in 0 until count) {
            packet[7 + i] = keys[i].toByte()
        }
        commandQueue.trySend(packet)
    }

    /**
     * Sends a single key press and release event with optional modifiers and serialized Mutex sequencing.
     */
    fun sendKeyStroke(hidKeyCode: Int, modifiers: Int = 0) {
        scope.launch {
            keyMutex.withLock {
                sendKeyboardReport(modifiers, listOf(hidKeyCode))
                delay(40)
                sendKeyboardReport(0, emptyList())
            }
        }
    }

    /**
     * Injects text directly using scrcpy text injection with UTF-8 character boundary safety.
     */
    fun sendInjectText(text: String) {
        if (text.isEmpty()) return
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val len = safeUtf8Length(textBytes, 300)

        val packet = ByteArray(5 + len)
        packet[0] = TYPE_INJECT_TEXT.toByte()
        packet[1] = (len ushr 24).toByte()
        packet[2] = (len ushr 16).toByte()
        packet[3] = (len ushr 8).toByte()
        packet[4] = len.toByte()
        System.arraycopy(textBytes, 0, packet, 5, len)
        commandQueue.trySend(packet)
    }

    private fun safeUtf8Length(bytes: ByteArray, maxLen: Int): Int {
        if (bytes.size <= maxLen) return bytes.size
        var len = maxLen
        // In UTF-8, continuation bytes have the form 10xxxxxx (0x80..0xBF)
        while (len > 0 && (bytes[len - 1].toInt() and 0xC0) == 0x80) {
            len--
        }
        if (len > 0) {
            val lead = bytes[len - 1].toInt() and 0xFF
            val charLen = when {
                lead < 0x80 -> 1
                lead in 0xC0..0xDF -> 2
                lead in 0xE0..0xEF -> 3
                lead in 0xF0..0xF7 -> 4
                else -> 1
            }
            if (len - 1 + charLen > maxLen) {
                len-- // Multi-byte sequence overflows maxLen, drop the leading byte
            } else {
                len = minOf(bytes.size, len - 1 + charLen)
            }
        }
        return len
    }

    /**
     * Sends Android keycode (Home, Back, Volume, etc.).
     */
    fun sendAndroidKeycode(keyCode: Int) {
        sendKeycodePacket(ACTION_DOWN, keyCode)
        sendKeycodePacket(ACTION_UP, keyCode)
    }

    private fun sendKeycodePacket(action: Int, keyCode: Int) {
        val packet = ByteArray(14)
        packet[0] = TYPE_INJECT_KEYCODE.toByte()
        packet[1] = action.toByte()
        packet[2] = (keyCode ushr 24).toByte()
        packet[3] = (keyCode ushr 16).toByte()
        packet[4] = (keyCode ushr 8).toByte()
        packet[5] = keyCode.toByte()
        commandQueue.trySend(packet)
    }

    fun close() {
        isRunning = false
        supervisorJob.cancel()
        commandQueue.close()
        runCatching { controlStream?.close() }
        runCatching { serverStream?.close() }
        controlStream = null
        serverStream = null
    }
}
