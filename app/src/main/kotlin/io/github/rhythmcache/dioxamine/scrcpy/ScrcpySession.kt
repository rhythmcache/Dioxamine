package io.github.rhythmcache.dioxamine.scrcpy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.SurfaceHolder
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbEndpoint
import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.VirtualKeyboardController
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.Constants
import kotlinx.coroutines.*
import java.util.concurrent.CancellationException

class ScrcpySession(
    private val context: Context,
    private val client: AdbClient,
    private val config: ScrcpyConfig,
    private val onDimensions: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
    private val onRecordingStateChanged: (Boolean) -> Unit = {},
    private val awaitPreviousTeardown: suspend () -> Unit = {}
) : VirtualKeyboardController {
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var sessionJob: Job? = null
    @Volatile private var stoppedByUser = false
    @Volatile private var socketsConnected = false
    @Volatile private var errorReported = false
    private val recentSentToDevice = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 16
            }
        }
    )
    @Volatile private var lastAppliedFromDevice: String? = null
    @Volatile private var lastSentText: String? = null
    @Volatile var clipboardSyncEnabled: Boolean = config.clipboardSync
        set(value) {
            field = value
            if (value) {
                setupHostClipboardSync()
            } else {
                removeHostClipboardSync()
            }
        }
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private var serverStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var audioStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    private var videoDecoder: ScrcpyDecoder? = null
    private var audioDecoder: ScrcpyAudioDecoder? = null
    private var control: ScrcpyControl? = null
    private var recorder: ScrcpyRecorder? = null

    private var videoWidth = 0
    private var videoHeight = 0

    val isRecording: Boolean get() = recorder?.isRecording == true

    companion object {
        private const val TAG_CLIENT = "SCRCPY_CLIENT"
        private const val TAG_SERVER = "SCRCPY_SERVER"
    }

    private val cleanedUp = java.util.concurrent.atomic.AtomicBoolean(false)
    private val teardownLock = Any()
    @Volatile private var teardownJob: Job? = null

    private fun reportError(msg: String) {
        synchronized(this) {
            if (stoppedByUser || errorReported) return
            errorReported = true
        }
        AppLogger.e(TAG_CLIENT, "Scrcpy Error: $msg")
        CoroutineScope(Dispatchers.Main).launch {
            if (!stoppedByUser) {
                onError(msg)
            }
        }
    }

    fun start(holder: SurfaceHolder? = null) {
        if (sessionJob?.isActive == true) {
            AppLogger.w(TAG_CLIENT, "start() called while a session is already active - ignoring")
            return
        }

        stoppedByUser = false
        socketsConnected = false
        errorReported = false

        sessionJob = sessionScope.launch {
            try {
                awaitPreviousTeardown()
                runCatching {
                    val killStream = client.open("shell:pkill -f com.genymobile.scrcpy.Server || killall com.genymobile.scrcpy.Server")
                    val buf = ByteArray(128)
                    runCatching { killStream.read(buf) }
                    killStream.close()
                }

                AppLogger.i(TAG_CLIENT, "Step 1: Pushing scrcpy-server.jar asset to device ${Constants.DEVICE_TMP_DIR}/...")
                withContext(Dispatchers.IO) {
                    context.assets.open("scrcpy-server.jar").use { input ->
                        client.sync.push(input, "${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar")
                    }
                }
                AppLogger.i(TAG_CLIENT, "Step 1 Complete: Asset pushed successfully")

                val serverCmd = "CLASSPATH=${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server ${Constants.SCRCPY_SERVER_VERSION} ${config.toServerArgs()}"
                AppLogger.i(TAG_CLIENT, "Step 2: Executing server command: $serverCmd")

                launch {
                    try {
                        AppLogger.i(TAG_CLIENT, "Server shell starting")
                        val stream = client.open("shell:$serverCmd")
                        serverStream = stream
                        AppLogger.i(TAG_CLIENT, "Server shell connected, reading stdout...")

                        val buf = ByteArray(4096)
                        val lineBuilder = StringBuilder()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = stream.read(buf)
                            if (n == -1) break
                            val chunk = String(buf, 0, n, Charsets.UTF_8)
                            lineBuilder.append(chunk)
                            var idx: Int
                            while (lineBuilder.indexOf("\n").also { idx = it } >= 0) {
                                val line = lineBuilder.substring(0, idx).trimEnd('\r')
                                if (line.isNotBlank()) AppLogger.raw(TAG_SERVER, line)
                                lineBuilder.delete(0, idx + 1)
                            }
                        }
                        if (lineBuilder.isNotBlank()) AppLogger.raw(TAG_SERVER, lineBuilder.toString())
                        AppLogger.i(TAG_CLIENT, "Server shell stdout stream ended (EOF)")

                        if (!stoppedByUser) {
                            val msg = if (!socketsConnected) {
                                context.getString(io.github.rhythmcache.dioxamine.R.string.scrcpy_server_start_failed)
                            } else {
                                context.getString(io.github.rhythmcache.dioxamine.R.string.scrcpy_server_terminated)
                            }
                            reportError(msg)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!stoppedByUser) {
                            AppLogger.e(TAG_CLIENT, "Server shell exception", e)
                            reportError("Server shell exception: ${e.message}")
                        } else {
                            AppLogger.d(TAG_CLIENT, "Server shell closed during shutdown: ${e.message}")
                        }
                    } finally {
                        AppLogger.i(TAG_CLIENT, "Server shell coroutine finished")
                    }
                }

                delay(300)

                // Sockets are accepted in order: video (if enabled), audio (if enabled), control (if enabled)
                if (config.videoEnabled) {
                    AppLogger.i(TAG_CLIENT, "Step 3: Connecting to video socket...")
                    videoStream = connectSocket(AdbEndpoint.LocalAbstract("scrcpy"), 25, 200)
                        ?: throw Exception("Failed to connect to scrcpy video socket after retries")
                    socketsConnected = true
                }

                if (config.audioEnabled) {
                    AppLogger.i(TAG_CLIENT, "Step 3.x: Connecting to audio socket...")
                    audioStream = connectSocket(AdbEndpoint.LocalAbstract("scrcpy"), 25, 200)
                        ?: throw Exception("Failed to connect to scrcpy audio socket after retries")
                    socketsConnected = true
                }

                if (config.controlEnabled) {
                    AppLogger.i(TAG_CLIENT, "Step 3.x: Connecting to control socket...")
                    controlStream = connectSocket(AdbEndpoint.LocalAbstract("scrcpy"), 25, 200)
                        ?: throw Exception("Failed to connect to scrcpy control socket after retries")
                    socketsConnected = true
                }

                var audioJob: Job? = null
                val currentAudioStream = audioStream
                if (config.audioEnabled && currentAudioStream != null) {
                    audioJob = launch {
                        val decoder = ScrcpyAudioDecoder()
                        audioDecoder = decoder
                        decoder.decodeAudioStream(currentAudioStream)
                    }
                }

                val currentControlStream = controlStream
                if (config.controlEnabled && currentControlStream != null) {
                    control = ScrcpyControl(
                        parentScope = this,
                        stream = currentControlStream,
                        videoWidth = { videoWidth },
                        videoHeight = { videoHeight },
                        onClipboardReceived = { text ->
                            handleDeviceClipboard(text)
                        }
                    )

                    if (clipboardSyncEnabled) {
                        setupHostClipboardSync()
                    }

                    if (config.turnScreenOff) {
                        AppLogger.i(TAG_CLIENT, "Sending SET_DISPLAY_POWER(off) via control channel")
                        control?.sendSetDisplayPower(false)
                    }

                    if (config.videoSource == "virtual" && !config.newDisplayApp.isNullOrBlank()) {
                        launch {
                            delay(500)
                            if (!stoppedByUser) {
                                AppLogger.i(TAG_CLIENT, "Launching app on virtual display: ${config.newDisplayApp}")
                                control?.sendStartApp(config.newDisplayApp)
                            }
                        }
                    }
                }

                if (config.videoEnabled) {
                    val currentVideoStream = videoStream ?: throw Exception("Video stream not available")
                    val surface = holder?.surface ?: throw IllegalArgumentException("SurfaceHolder is required when video is enabled")
                    AppLogger.i(TAG_CLIENT, "Step 4: Initializing MediaCodec video decoder pipeline...")
                    val targetWidth = if (config.maxSize > 0) config.maxSize else 1920
                    val targetHeight = if (config.maxSize > 0) config.maxSize else 1080

                    val decoder = ScrcpyDecoder(
                        surface = surface,
                        defaultWidth = targetWidth,
                        defaultHeight = targetHeight,
                        onDimensionsParsed = { w, h ->
                            videoWidth = w
                            videoHeight = h
                            onDimensions(w, h)
                        },
                        isStoppedByUser = { stoppedByUser }
                    )
                    videoDecoder = decoder

                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("scrcpy_auto_record", false)) {
                        startRecording()
                    }

                    decoder.decodeStream(currentVideoStream)
                } else if (config.audioEnabled) {
                    AppLogger.i(TAG_CLIENT, "Video disabled - running in audio-only streaming mode")
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("scrcpy_auto_record", false)) {
                        startRecording()
                    }
                    audioJob?.join()
                } else {
                    awaitCancellation()
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    AppLogger.i(TAG_CLIENT, "Scrcpy session cancelled")
                    return@launch
                }
                if (stoppedByUser) {
                    AppLogger.i(TAG_CLIENT, "Scrcpy session ended by user")
                    return@launch
                }
                AppLogger.e(TAG_CLIENT, "Scrcpy Session Error: ${e.message}", e)
                reportError("Mirroring stopped unexpectedly: ${e.message}")
            } finally {
                cleanup()
                if (!stoppedByUser) {
                    reportError("Mirroring session ended")
                }
            }
        }
    }

    private suspend fun connectSocket(endpoint: AdbEndpoint, retries: Int, delayMs: Long = 40): AdbStream? {
        var attempts = 0
        var lastError: Exception? = null
        while (attempts < retries) {
            currentCoroutineContext().ensureActive()
            attempts++
            try {
                return client.open(endpoint)
            } catch (e: Exception) {
                lastError = e
                AppLogger.d(TAG_CLIENT, "Socket connect attempt $attempts/$retries failed: ${e.message}")
                delay(delayMs)
            }
        }
        AppLogger.w(TAG_CLIENT, "Socket connect exhausted $retries retries, last error: ${lastError?.message}")
        return null
    }

    fun attachSurface(surface: android.view.Surface) {
        videoDecoder?.attachSurface(surface)
        sendResetVideo()
    }

    fun detachSurface() {
        videoDecoder?.detachSurface()
    }

    fun sendResetVideo() {
        control?.sendResetVideo()
    }

    fun setSurface(surface: android.view.Surface) {
        attachSurface(surface)
    }

    fun sendTouchEvent(action: Int, pointerId: Long, x: Float, y: Float, vw: Int, vh: Int) {
    control?.sendTouchEvent(action, pointerId, x, y, vw, vh)
    }

    fun sendKeycode(action: Int, keycode: Int) {
        control?.sendKeycode(action, keycode)
    }

    fun sendCameraSetTorch(on: Boolean) {
        control?.sendCameraSetTorch(on)
    }

    fun sendNavBack() {
        control?.sendNavBack()
    }

    fun sendNavHome() {
        control?.sendNavHome()
    }

    fun sendNavRecents() {
        control?.sendNavRecents()
    }

    fun sendRotateDevice() {
        control?.sendRotateDevice()
    }

    fun sendSetClipboard(text: String, paste: Boolean = false) {
        lastSentText = text
        val now = SystemClock.uptimeMillis()
        synchronized(recentSentToDevice) {
            recentSentToDevice[text] = now + 2000L
        }
        control?.sendSetClipboard(text, paste)
    }

    private fun handleDeviceClipboard(text: String) {
        if (!clipboardSyncEnabled || text.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val isRecentEcho = synchronized(recentSentToDevice) {
            val expiry = recentSentToDevice[text]
            expiry != null && now < expiry
        }
        if (isRecentEcho || text == lastAppliedFromDevice) return
        lastAppliedFromDevice = text
        lastSentText = null
        sessionScope.launch {
            delay(1000)
            if (lastAppliedFromDevice == text) {
                lastAppliedFromDevice = null
            }
        }
        sessionScope.launch(Dispatchers.Main) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("scrcpy", text)
                cm?.setPrimaryClip(clip)
                AppLogger.d(TAG_CLIENT, "Device clipboard synced to host (${text.length} chars)")
            }
        }
    }

    private fun setupHostClipboardSync() {
        if (!config.controlEnabled || clipboardListener != null) return
        sessionScope.launch(Dispatchers.Main) {
            if (clipboardListener != null || !clipboardSyncEnabled) return@launch
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@launch
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                if (!clipboardSyncEnabled) return@OnPrimaryClipChangedListener
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrEmpty()) {
                        if (text == lastAppliedFromDevice) {
                            lastAppliedFromDevice = null
                            return@OnPrimaryClipChangedListener
                        }
                        if (text == lastSentText) {
                            return@OnPrimaryClipChangedListener
                        }
                        lastSentText = text
                        val now = SystemClock.uptimeMillis()
                        synchronized(recentSentToDevice) {
                            recentSentToDevice[text] = now + 2000L
                        }
                        AppLogger.d(TAG_CLIENT, "Host clipboard syncing to device (${text.length} chars)")
                        control?.sendSetClipboard(text, paste = false)
                    }
                }
            }
            clipboardListener = listener
            cm.addPrimaryClipChangedListener(listener)
        }
    }

    private fun removeHostClipboardSync() {
        clipboardListener?.let { listener ->
            clipboardListener = null
            CoroutineScope(Dispatchers.Main).launch {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                runCatching { cm?.removePrimaryClipChangedListener(listener) }
            }
        }
    }

    fun ensureUhidDevices() {
        control?.ensureUhidDevices()
    }

    fun sendMouseMove(dx: Int, dy: Int, buttons: Int? = null) {
        control?.sendMouseMove(dx, dy, buttons)
    }

    fun setMouseButtonState(buttons: Int) {
        control?.setMouseButtonState(buttons)
    }

    fun clickMouseButton(buttonMask: Int) {
        control?.clickMouseButton(buttonMask)
    }

    fun sendMouseScroll(wheel: Int, hPan: Int = 0) {
        control?.sendMouseScroll(wheel, hPan)
    }

    override fun sendKeyStroke(hidKeyCode: Int, modifiers: Int) {
        val ctrl = control
        if (ctrl == null) {
            AppLogger.d(TAG_CLIENT, "Dropping key stroke (control channel not connected)")
            return
        }
        ctrl.sendKeyStroke(hidKeyCode, modifiers)
    }

    override fun sendInjectText(text: String) {
        val ctrl = control
        if (ctrl == null) {
            AppLogger.d(TAG_CLIENT, "Dropping inject text (control channel not connected)")
            return
        }
        ctrl.sendInjectText(text)
    }

    fun startRecording() {
        if (recorder?.isRecording == true) return

        // Check codec compatibility
        val videoCompatible = !config.videoEnabled || config.videoCodec in listOf("h264", "h265")
        val audioCompatible = !config.audioEnabled || config.audioCodec == "aac"
        if (!videoCompatible) {
            AppLogger.w(TAG_CLIENT, "Cannot record: video codec ${config.videoCodec} is not supported by MediaMuxer")
            return
        }

        if (!RecordingsManager.hasEnoughFreeSpace(context)) {
            AppLogger.w(TAG_CLIENT, "Cannot start recording: storage is critically low (< 50MB free)")
            return
        }

        val outputFile = RecordingsManager.generateRecordingFile(context)
        val rec = ScrcpyRecorder(outputFile)
        rec.setAudioExpected(config.audioEnabled && audioCompatible)
        rec.prepare()
        recorder = rec

        // Wire recorder to decoders using attachRecorder so cached config/CSD is immediately forwarded
        videoDecoder?.attachRecorder(rec)
        if (audioCompatible) {
            audioDecoder?.attachRecorder(rec)
        }

        // Request a fresh keyframe from the encoder so recording starts on a clean IDR frame
        if (config.videoEnabled && config.controlEnabled) {
            control?.sendResetVideo()
        }

        AppLogger.i(TAG_CLIENT, "Recording started: ${outputFile.absolutePath}")
        sessionScope.launch(Dispatchers.Main) {
            onRecordingStateChanged(true)
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        videoDecoder?.recorder = null
        audioDecoder?.recorder = null
        rec.stop()
        recorder = null
        AppLogger.i(TAG_CLIENT, "Recording stopped")
        sessionScope.launch(Dispatchers.Main) {
            onRecordingStateChanged(false)
        }
    }

    fun stop(): Job = synchronized(teardownLock) {
        teardownJob?.let { return@synchronized it }
        stoppedByUser = true
        val job = sessionJob
        sessionJob = null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { stopRecording() }
            if (config.turnScreenOff) {
                runCatching { control?.sendSetDisplayPower(true) }
            }
            runCatching { control?.close() }
            job?.cancel("Session stopped by user")
            job?.join()
            cleanup()
            sessionScope.cancel("Session stopped by user")
        }.also { teardownJob = it }
    }

    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        AppLogger.i(TAG_CLIENT, "Cleaning up scrcpy session streams...")
        removeHostClipboardSync()
        synchronized(recentSentToDevice) {
            recentSentToDevice.clear()
        }
        lastAppliedFromDevice = null
        lastSentText = null
        runCatching { stopRecording() }
        runCatching { control?.closeImmediately() }
        runCatching { videoDecoder?.stop() }
        runCatching { audioDecoder?.stop() }
        runCatching { controlStream?.close() }
        runCatching { audioStream?.close() }
        runCatching { videoStream?.close() }
        runCatching { serverStream?.close() }

        videoDecoder = null
        audioDecoder = null
        control = null
        recorder = null
        controlStream = null
        audioStream = null
        videoStream = null
        serverStream = null
    }
}
