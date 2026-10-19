package io.github.rhythmcache.dioxamine.scrcpy

import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.adb.readExactly
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.KeyboardAction
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.UhidControlManager
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.VirtualKeyboardController
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.VirtualKeyboardHelper
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds and sends scrcpy control-socket messages and handles incoming device messages.
 * Wire formats verified against com.genymobile.scrcpy.control.ControlMessageReader.
 * Keyboard strokes, text injection, and mouse reports are written directly to the wire under [wireMutex],
 * so a key's press is on the socket before its release delay starts. Touch events and other
 * control messages still go through the guaranteed/droppable queue.
 * Runs on its own dedicated scope so it is never starved by contention with the
 * video decode pipeline, and never dies if the parent session scope is cancelled.
 */
class ScrcpyControl(
    parentScope: CoroutineScope,
    private val stream: AdbStream,
    private val videoWidth: () -> Int,
    private val videoHeight: () -> Int,
    private val onClipboardReceived: ((String) -> Unit)? = null
) : VirtualKeyboardController {
    companion object {
        private const val TAG = "SCRCPY_CLIENT"
        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_INJECT_SCROLL_EVENT = 3
        const val TYPE_BACK_OR_SCREEN_ON = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_EXPAND_SETTINGS_PANEL = 6
        const val TYPE_COLLAPSE_PANELS = 7
        const val TYPE_GET_CLIPBOARD = 8
        const val TYPE_SET_CLIPBOARD = 9
        const val TYPE_SET_DISPLAY_POWER = 10
        const val TYPE_ROTATE_DEVICE = 11
        const val TYPE_UHID_CREATE = 12
        const val TYPE_UHID_INPUT = 13
        const val TYPE_UHID_DESTROY = 14
        const val TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 15
        const val TYPE_START_APP = 16
        const val TYPE_RESET_VIDEO = 17
        const val TYPE_CAMERA_SET_TORCH = 18
        const val TYPE_CAMERA_ZOOM_IN = 19
        const val TYPE_CAMERA_ZOOM_OUT = 20
        const val TYPE_RESIZE_DISPLAY = 21
        const val TYPE_SCAN_FILE = 22

        const val DEVICE_MSG_TYPE_CLIPBOARD = 0
        const val DEVICE_MSG_TYPE_ACK_CLIPBOARD = 1
        const val DEVICE_MSG_TYPE_UHID_OUTPUT = 2

        const val CLIPBOARD_TEXT_MAX_LENGTH = (1 shl 18) - 14

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        private const val PRESSURE_FULL: Short = 0xFFFF.toShort()

        // scrcpy supports up to 10 concurrent pointers (matches Android's MAX_POINTERS)
        const val MAX_POINTERS = 10
    }

    // Dedicated scopes: not tied to sessionJob/video decode lifecycle, so they are never
    // starved by decoder work on Dispatchers.IO and never die if the parent is cancelled.
    private val writerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Unbounded queue: guaranteed events (DOWN/UP) must never block waiting for space,
    // and under normal load this queue drains far faster than it fills.
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private val wireMutex = Mutex()
    private val writerJob = writerScope.launch {
        try {
            for (packet in queue) {
                if (stream.isClosed) break
                wireMutex.withLock {
                    if (!stream.isClosed) {
                        stream.write(packet)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "ScrcpyControl writer channel crashed: ${e.message}", e)
        }
    }

    private suspend fun writeNow(packet: ByteArray): Boolean {
        if (stream.isClosed) return false
        return wireMutex.withLock {
            runCatching {
                if (!stream.isClosed) {
                    stream.write(packet)
                    true
                } else {
                    false
                }
            }.getOrElse { e ->
                if (e !is CancellationException) {
                    AppLogger.w(TAG, "ScrcpyControl failed to write packet direct: ${e.message}")
                }
                false
            }
        }
    }

    init {
        if (onClipboardReceived != null) {
            readerScope.launch {
                try {
                    readDeviceMessages(onClipboardReceived)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Device message reader stopped: ${e.message}")
                }
            }
        }
    }

    private suspend fun readDeviceMessages(onClipboard: (String) -> Unit) {
        val typeBuf = ByteArray(1)
        val lenBuf = ByteArray(4)
        while (!stream.isClosed) {
            if (!stream.readExactly(typeBuf)) return
            when (val type = typeBuf[0].toInt() and 0xFF) {
                DEVICE_MSG_TYPE_CLIPBOARD -> {
                    if (!stream.readExactly(lenBuf)) return
                    val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                            ((lenBuf[1].toInt() and 0xFF) shl 16) or
                            ((lenBuf[2].toInt() and 0xFF) shl 8) or
                            (lenBuf[3].toInt() and 0xFF)
                    if (len < 0 || len > CLIPBOARD_TEXT_MAX_LENGTH) {
                        AppLogger.e(TAG, "Invalid clipboard length $len, stopping reader (stream cannot resync)")
                        return
                    }
                    val data = ByteArray(len)
                    if (len > 0 && !stream.readExactly(data)) return
                    onClipboard(String(data, Charsets.UTF_8))
                }
                DEVICE_MSG_TYPE_ACK_CLIPBOARD -> {
                    if (!stream.readExactly(ByteArray(8))) return
                }
                DEVICE_MSG_TYPE_UHID_OUTPUT -> {
                    val hdr = ByteArray(4) // id(2) + size(2)
                    if (!stream.readExactly(hdr)) return
                    val size = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                    if (size > 0 && !stream.readExactly(ByteArray(size))) return
                }
                else -> {
                    AppLogger.e(TAG, "Unknown device message type $type, stopping reader (stream cannot resync)")
                    return
                }
            }
        }
    }

    // UHID Device & Mouse/Keyboard state
    @Volatile private var uhidDevicesRegistered = false
    private val uhidInitLock = Any()

    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)
    private val pendingWheel = AtomicInteger(0)
    private val pendingHPan = AtomicInteger(0)
    private val heldMouseButtons = AtomicInteger(0)
    private val clickedMouseButtons = AtomicInteger(0)
    private val currentMouseButtons = AtomicInteger(0)
    private val mouseStateChanged = AtomicBoolean(false)

    private val clickMutex = Mutex()
    private var mouseLoopJob: Job? = null

    private val keyboardActionChannel = Channel<KeyboardAction>(Channel.UNLIMITED)
    private val keyStrokeJob = writerScope.launch {
        for (action in keyboardActionChannel) {
            if (stream.isClosed) break
            when (action) {
                is KeyboardAction.Stroke -> {
                    try {
                        writeNow(buildKeyboardReportPacket(action.modifiers, listOf(action.keyCode)))
                        delay(12)
                    } finally {
                        withContext(NonCancellable) {
                            runCatching { writeNow(buildKeyboardReportPacket(0, emptyList())) }
                            delay(4)
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

    private fun updateEffectiveButtons() {
        val total = heldMouseButtons.get() or clickedMouseButtons.get()
        val prev = currentMouseButtons.getAndSet(total)
        if (prev != total) {
            mouseStateChanged.set(true)
        }
    }

    private fun releaseAllMouseButtons(): Boolean {
        heldMouseButtons.set(0)
        clickedMouseButtons.set(0)
        return currentMouseButtons.getAndSet(0) != 0
    }

    /**
     * Closes the control message queue and waits for pending messages (e.g. power-on)
     * to flush before cancelling the writer and reader scopes.
     */
    suspend fun close(drainTimeoutMs: Long = 300) {
        mouseLoopJob?.cancel()
        keyboardActionChannel.close()
        // Cancel and wait: the stroke's finally{} writes the key release, then exits.
        runCatching { keyStrokeJob.cancelAndJoin() }

        if (uhidDevicesRegistered) {
            withTimeoutOrNull(drainTimeoutMs) {
                runCatching {
                    if (releaseAllMouseButtons()) {
                        writeNow(buildMouseReportPacket(0, 0, 0, 0, 0))
                    }
                    writeNow(buildKeyboardReportPacket(0, emptyList()))
                    writeNow(buildDestroyPacket(UhidControlManager.ID_MOUSE))
                    writeNow(buildDestroyPacket(UhidControlManager.ID_KEYBOARD))
                }
            }
            uhidDevicesRegistered = false
        }
        queue.close()
        runCatching {
            withTimeoutOrNull(drainTimeoutMs) {
                writerJob.join()
            }
        }
        writerScope.cancel()
        readerScope.cancel()
    }

    fun closeImmediately() {
        mouseLoopJob?.cancel()
        keyboardActionChannel.close()
        runCatching { keyStrokeJob.cancel() }
        queue.close()
        writerJob.cancel()
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(50) {
                    writerJob.join()
                }
            }
        }
        if (uhidDevicesRegistered && !stream.isClosed) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(200) {
                        wireMutex.withLock {
                            if (!stream.isClosed) {
                                if (releaseAllMouseButtons()) {
                                    stream.write(buildMouseReportPacket(0, 0, 0, 0, 0))
                                }
                                stream.write(buildKeyboardReportPacket(0, emptyList()))
                                stream.write(buildDestroyPacket(UhidControlManager.ID_MOUSE))
                                stream.write(buildDestroyPacket(UhidControlManager.ID_KEYBOARD))
                            }
                        }
                    }
                }
            }.onFailure { AppLogger.w(TAG, "Failed to send immediate teardown packets: ${it.message}") }
            uhidDevicesRegistered = false
        }
        writerScope.cancel()
        readerScope.cancel()
    }

    /**
     * Sends a touch event for a specific finger.
     *
     * @param pointerId stable per-finger ID for the life of the gesture — on Android,
     *   use MotionEvent.getPointerId(pointerIndex) directly. Do not invent your own
     *   tracking scheme; scrcpy identifies which finger is which purely by this ID.
     */
    fun sendTouchEvent(
        action: Int,
        pointerId: Long,
        localX: Float,
        localY: Float,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val targetWidth = videoWidth()
        val targetHeight = videoHeight()
        if (targetWidth <= 0 || targetHeight <= 0) return
        val normX = ((localX / viewWidth.toFloat()) * targetWidth).toInt().coerceIn(0, targetWidth)
        val normY = ((localY / viewHeight.toFloat()) * targetHeight).toInt().coerceIn(0, targetHeight)

        // type(1) + action(1) + pointerId(8) + x(4) + y(4) + screenW(2) + screenH(2)
        // + pressure(2) + actionButton(4) + buttons(4) = 32 bytes
        val buf = ByteBuffer.allocate(32)
        buf.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buf.put(action.toByte())
        buf.putLong(pointerId)
        buf.putInt(normX)
        buf.putInt(normY)
        buf.putShort(targetWidth.toShort())
        buf.putShort(targetHeight.toShort())
        buf.putShort(PRESSURE_FULL)
        buf.putInt(0) // actionButton
        buf.putInt(0) // buttons

        val bytes = buf.array()
        if (action == ACTION_MOVE) {
            // Drop this MOVE event if the queue is full to keep interaction responsive
            sendDroppable(bytes)
        } else {
            // DOWN and UP events must be guaranteed delivery to prevent stuck finger bug
            sendGuaranteed(bytes)
        }
    }

    fun sendKeycode(
        action: Int,
        keycode: Int,
        repeat: Int = 0,
        metaState: Int = 0
    ) {
        // type(1) + action(1) + keycode(4) + repeat(4) + metaState(4) = 14 bytes
        val buf = ByteBuffer.allocate(14)
        buf.put(TYPE_INJECT_KEYCODE.toByte())
        buf.put(action.toByte())
        buf.putInt(keycode)
        buf.putInt(repeat)
        buf.putInt(metaState)
        sendGuaranteed(buf.array())
    }

    fun sendNavBack() {
        sendKeycode(ACTION_DOWN, 4)
        sendKeycode(ACTION_UP, 4)
    }

    fun sendNavHome() {
        sendKeycode(ACTION_DOWN, 3)
        sendKeycode(ACTION_UP, 3)
    }

    fun sendNavRecents() {
        sendKeycode(ACTION_DOWN, 187)
        sendKeycode(ACTION_UP, 187)
    }

    fun sendBackOrScreenOn(action: Int = ACTION_DOWN) {
        // type(1) + action(1) = 2 bytes
        val buf = ByteBuffer.allocate(2)
        buf.put(TYPE_BACK_OR_SCREEN_ON.toByte())
        buf.put(action.toByte())
        sendGuaranteed(buf.array())
    }

    fun sendSetDisplayPower(on: Boolean) {
        // type(1) + on(1) = 2 bytes
        val buf = ByteBuffer.allocate(2)
        buf.put(TYPE_SET_DISPLAY_POWER.toByte())
        buf.put(if (on) 1.toByte() else 0.toByte())
        sendGuaranteed(buf.array())
    }

    fun sendCameraSetTorch(on: Boolean) {
        // type(1) + on(1) = 2 bytes
        val buf = ByteBuffer.allocate(2)
        buf.put(TYPE_CAMERA_SET_TORCH.toByte())
        buf.put(if (on) 1.toByte() else 0.toByte())
        sendGuaranteed(buf.array())
    }

    fun sendCameraZoomIn() = sendEmpty(TYPE_CAMERA_ZOOM_IN)
    fun sendCameraZoomOut() = sendEmpty(TYPE_CAMERA_ZOOM_OUT)
    fun sendRotateDevice() = sendEmpty(TYPE_ROTATE_DEVICE)
    fun sendExpandNotificationPanel() = sendEmpty(TYPE_EXPAND_NOTIFICATION_PANEL)
    fun sendExpandSettingsPanel() = sendEmpty(TYPE_EXPAND_SETTINGS_PANEL)
    fun sendCollapsePanels() = sendEmpty(TYPE_COLLAPSE_PANELS)
    fun sendResetVideo() = sendEmpty(TYPE_RESET_VIDEO)
    fun sendOpenHardKeyboardSettings() = sendEmpty(TYPE_OPEN_HARD_KEYBOARD_SETTINGS)

    fun sendResizeDisplay(width: Int, height: Int) {
        require(width in 0..65535 && height in 0..65535) { "resize dims must fit in u16" }
        // type(1) + width(2) + height(2) = 5 bytes
        val buf = ByteBuffer.allocate(5)
        buf.put(TYPE_RESIZE_DISPLAY.toByte())
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        sendGuaranteed(buf.array())
    }

    fun sendStartApp(name: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 255) { "app name too long for 1-byte length prefix" }
        // type(1) + len(1) + name(N)
        val buf = ByteBuffer.allocate(2 + nameBytes.size)
        buf.put(TYPE_START_APP.toByte())
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        sendGuaranteed(buf.array())
    }
    fun ensureUhidDevices() {
        if (uhidDevicesRegistered) return
        synchronized(uhidInitLock) {
            if (uhidDevicesRegistered) return
            val mousePkt = buildCreateDevicePacket(
                UhidControlManager.ID_MOUSE,
                0x18d1,
                0x0001,
                "Dioxamine Mouse",
                UhidControlManager.MOUSE_REPORT_DESC
            )
            val kbPkt = buildCreateDevicePacket(
                UhidControlManager.ID_KEYBOARD,
                0x18d1,
                0x0002,
                "Dioxamine Keyboard",
                UhidControlManager.KEYBOARD_REPORT_DESC
            )
            sendGuaranteed(mousePkt)
            sendGuaranteed(kbPkt)
            uhidDevicesRegistered = true
            startMouseLoop()
        }
    }

    fun sendUhidDestroy(id: Int) {
        sendGuaranteed(buildDestroyPacket(id))
    }

    private fun buildDestroyPacket(id: Int): ByteArray {
        val buf = ByteBuffer.allocate(3)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_UHID_DESTROY.toByte())
        buf.putShort(id.toShort())
        return buf.array()
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

    private fun buildMouseReportPacket(
        buttons: Int,
        dx: Int,
        dy: Int,
        wheel: Int,
        hPan: Int
    ): ByteArray {
        val mouseBuf = ByteBuffer.allocate(10)
        mouseBuf.order(ByteOrder.BIG_ENDIAN)
        mouseBuf.put(TYPE_UHID_INPUT.toByte())
        mouseBuf.putShort(UhidControlManager.ID_MOUSE.toShort())
        mouseBuf.putShort(5.toShort()) // report len: 5 bytes
        mouseBuf.put(buttons.toByte())
        mouseBuf.put(dx.toByte())
        mouseBuf.put(dy.toByte())
        mouseBuf.put(wheel.toByte())
        mouseBuf.put(hPan.toByte())
        return mouseBuf.array()
    }

    private fun buildKeyboardReportPacket(modifiers: Int, keys: List<Int>): ByteArray {
        val buf = ByteBuffer.allocate(13)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_UHID_INPUT.toByte())
        buf.putShort(UhidControlManager.ID_KEYBOARD.toShort())
        buf.putShort(8.toShort()) // report len: 8 bytes
        buf.put(modifiers.toByte())
        buf.put(0.toByte()) // Reserved
        val count = minOf(6, keys.size)
        for (i in 0 until count) {
            buf.put(keys[i].toByte())
        }
        while (buf.position() < 13) {
            buf.put(0.toByte())
        }
        return buf.array()
    }

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

    private fun startMouseLoop() {
        if (mouseLoopJob?.isActive == true) return
        mouseLoopJob = writerScope.launch {
            while (isActive && !stream.isClosed) {
                val hadStateChange = mouseStateChanged.getAndSet(false)
                if (hadStateChange || !wireMutex.isLocked) {
                    val dx = consumeDelta(pendingDx)
                    val dy = consumeDelta(pendingDy)
                    val wheel = consumeDelta(pendingWheel)
                    val hPan = consumeDelta(pendingHPan)
                    val buttons = currentMouseButtons.get()

                    if (dx != 0 || dy != 0 || wheel != 0 || hPan != 0 || hadStateChange) {
                        val packet = buildMouseReportPacket(buttons, dx, dy, wheel, hPan)
                        val ok = writeNow(packet)
                        if (!ok) {
                            if (hadStateChange) mouseStateChanged.set(true)
                            if (dx != 0) pendingDx.addAndGet(dx)
                            if (dy != 0) pendingDy.addAndGet(dy)
                            if (wheel != 0) pendingWheel.addAndGet(wheel)
                            if (hPan != 0) pendingHPan.addAndGet(hPan)
                        }
                    }
                }
                delay(8)
            }
        }
    }

    fun sendMouseMove(dx: Int, dy: Int, buttons: Int? = null) {
        ensureUhidDevices()
        if (buttons != null) {
            heldMouseButtons.set(buttons)
            updateEffectiveButtons()
        }
        pendingDx.addAndGet(dx)
        pendingDy.addAndGet(dy)
    }

    fun setMouseButtonState(buttons: Int) {
        ensureUhidDevices()
        heldMouseButtons.set(buttons)
        updateEffectiveButtons()
    }

    fun clickMouseButton(buttonMask: Int) {
        ensureUhidDevices()
        writerScope.launch {
            clickMutex.withLock {
                try {
                    clickedMouseButtons.updateAndGet { it or buttonMask }
                    updateEffectiveButtons()
                    delay(40)
                } finally {
                    withContext(NonCancellable) {
                        clickedMouseButtons.updateAndGet { it and buttonMask.inv() }
                        updateEffectiveButtons()
                    }
                }
            }
        }
    }

    fun sendMouseScroll(wheel: Int, hPan: Int = 0) {
        ensureUhidDevices()
        pendingWheel.addAndGet(wheel)
        pendingHPan.addAndGet(hPan)
    }

    private suspend fun sendKeyboardReport(modifiers: Int, keys: List<Int>) {
        writeNow(buildKeyboardReportPacket(modifiers, keys))
    }

    override fun sendKeyStroke(hidKeyCode: Int, modifiers: Int) {
        ensureUhidDevices()
        keyboardActionChannel.trySend(KeyboardAction.Stroke(hidKeyCode, modifiers)).onFailure {
            AppLogger.w(TAG, "Failed to enqueue key stroke (channel closed)")
        }
    }

    override fun sendInjectText(text: String) {
        if (text.isEmpty()) return
        ensureUhidDevices()
        keyboardActionChannel.trySend(KeyboardAction.Text(text)).onFailure {
            AppLogger.w(TAG, "Failed to enqueue inject text (channel closed)")
        }
    }

    private suspend fun sendInjectTextInternal(text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < textBytes.size && !stream.isClosed) {
            val chunkLen = VirtualKeyboardHelper.safeUtf8ChunkLength(textBytes, offset, 300)
            if (chunkLen <= 0) break

            val packet = ByteArray(5 + chunkLen)
            packet[0] = TYPE_INJECT_TEXT.toByte()
            packet[1] = (chunkLen ushr 24).toByte()
            packet[2] = (chunkLen ushr 16).toByte()
            packet[3] = (chunkLen ushr 8).toByte()
            packet[4] = chunkLen.toByte()
            System.arraycopy(textBytes, offset, packet, 5, chunkLen)
            writeNow(packet)

            offset += chunkLen
        }
    }

    private fun truncateUtf8(bytes: ByteArray, max: Int): ByteArray {
        if (bytes.size <= max) return bytes
        var end = max
        // Back up while the byte at `end` is a continuation byte (10xxxxxx),
        // so we never cut in the middle of a multi-byte character.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    fun sendSetClipboard(text: String, paste: Boolean = false, sequence: Long = 0L) {
        val truncated = truncateUtf8(text.toByteArray(Charsets.UTF_8), CLIPBOARD_TEXT_MAX_LENGTH)
        // type(1) + sequence(8) + paste(1) + len(4) + text(N) = 14 + N bytes
        val buf = ByteBuffer.allocate(14 + truncated.size)
        buf.put(TYPE_SET_CLIPBOARD.toByte())
        buf.putLong(sequence)
        buf.put(if (paste) 1.toByte() else 0.toByte())
        buf.putInt(truncated.size)
        buf.put(truncated)
        sendGuaranteed(buf.array())
    }

    fun sendGetClipboard(copyKey: Boolean = false) {
        // type(1) + copyKey(1) = 2 bytes
        val buf = ByteBuffer.allocate(2)
        buf.put(TYPE_GET_CLIPBOARD.toByte())
        buf.put(if (copyKey) 1.toByte() else 0.toByte())
        sendGuaranteed(buf.array())
    }

    fun sendScanFile(path: String) {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        // type(1) + len(4) + path(N)
        val buf = ByteBuffer.allocate(5 + pathBytes.size)
        buf.put(TYPE_SCAN_FILE.toByte())
        buf.putInt(pathBytes.size)
        buf.put(pathBytes)
        sendGuaranteed(buf.array())
    }

    private fun sendEmpty(type: Int) {
        sendGuaranteed(byteArrayOf(type.toByte()))
    }

    /**
     * Sends critical events. With an unbounded queue this never actually blocks —
     * trySend only fails if the channel is closed, in which case there's nothing
     * useful to retry.
     */
    private fun sendGuaranteed(bytes: ByteArray) {
        queue.trySend(bytes).onFailure {
            AppLogger.w(TAG, "ScrcpyControl: Failed to send guaranteed command (channel closed).")
        }
    }

    /**
     * Sends droppable coordinate move updates. Kept as trySend for symmetry and
     * logging; with an unbounded queue this will essentially never fail unless closed.
     */
    private fun sendDroppable(bytes: ByteArray) {
        queue.trySend(bytes).onFailure {
            AppLogger.w(TAG, "ScrcpyControl: Dropping droppable move event (channel closed).")
        }
    }
}
