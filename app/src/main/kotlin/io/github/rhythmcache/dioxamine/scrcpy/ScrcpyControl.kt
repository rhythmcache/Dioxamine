package io.github.rhythmcache.dioxamine.scrcpy

import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Builds and sends scrcpy control-socket messages.
 * Wire formats verified against com.genymobile.scrcpy.control.ControlMessageReader.
 * Uses a single reader/writer bounded channel queue (size 128) to eliminate lock contention,
 * prevent memory leaks under socket stalls, and avoid coroutine churn.
 */
class ScrcpyControl(
    private val scope: CoroutineScope,
    private val stream: AdbStream,
    private val videoWidth: () -> Int,
    private val videoHeight: () -> Int
) {
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

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        private const val PRESSURE_FULL: Short = 0xFFFF.toShort()
    }

    // Bounded channel to prevent infinite memory growth under congestion
    private val queue = Channel<ByteArray>(128)

    init {
        scope.launch(Dispatchers.IO) {
            try {
                for (packet in queue) {
                    if (stream.isClosed) break
                    stream.write(packet)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "ScrcpyControl writer channel crashed: ${e.message}", e)
            }
        }
    }

    /**
     * Closes the control message queue to cleanly release resources and exit the writer thread.
     */
    fun close() {
        queue.close()
    }

    fun sendTouchEvent(
        action: Int,
        localX: Float,
        localY: Float,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val targetWidth = videoWidth()
        val targetHeight = videoHeight()
        val normX = ((localX / viewWidth.toFloat()) * targetWidth).toInt().coerceIn(0, targetWidth)
        val normY = ((localY / viewHeight.toFloat()) * targetHeight).toInt().coerceIn(0, targetHeight)

        // type(1) + action(1) + pointerId(8) + x(4) + y(4) + screenW(2) + screenH(2)
        // + pressure(2) + actionButton(4) + buttons(4) = 32 bytes
        val buf = ByteBuffer.allocate(32)
        buf.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buf.put(action.toByte())
        buf.putLong(0L) // pointerId
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

    fun sendInjectText(text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val truncated = if (textBytes.size > 300) textBytes.copyOf(300) else textBytes
        // type(1) + len(4) + text(N)
        val buf = ByteBuffer.allocate(5 + truncated.size)
        buf.put(TYPE_INJECT_TEXT.toByte())
        buf.putInt(truncated.size)
        buf.put(truncated)
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
     * Sends critical events by launching a worker coroutine that suspends (waits)
     * if the queue is full, ensuring guaranteed delivery.
     */
    private fun sendGuaranteed(bytes: ByteArray) {
        val result = queue.trySend(bytes)
        if (result.isFailure) {
            // Queue is temporarily full, launch a suspending worker to guarantee delivery
            scope.launch(Dispatchers.IO) {
                runCatching {
                    queue.send(bytes)
                }.onFailure {
                    AppLogger.w(TAG, "ScrcpyControl: Failed to send guaranteed command.")
                }
            }
        }
    }

    /**
     * Sends droppable coordinate move updates. If the channel queue is full,
     * it drops the packet immediately to prevent lagging and keep interactions real-time.
     */
    private fun sendDroppable(bytes: ByteArray) {
        queue.trySend(bytes).onFailure {
            AppLogger.w(TAG, "ScrcpyControl: Dropping droppable move event (queue congested).")
        }
    }
}


