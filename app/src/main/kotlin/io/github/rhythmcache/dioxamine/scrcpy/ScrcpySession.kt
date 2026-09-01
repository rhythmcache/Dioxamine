package io.github.rhythmcache.dioxamine.scrcpy

import android.content.Context
import android.view.SurfaceHolder
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbEndpoint
import io.github.rhythmcache.adb.AdbStream
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
    private val onRecordingStateChanged: (Boolean) -> Unit = {}
) {
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionJob: Job? = null
    @Volatile private var stoppedByUser = false
    @Volatile private var socketsConnected = false
    @Volatile private var errorReported = false

    private var serverStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var audioStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    private var videoDecoder: ScrcpyDecoder? = null
    private var audioDecoder: ScrcpyAudioDecoder? = null
    private var control: ScrcpyControl? = null
    private var recorder: ScrcpyRecorder? = null

    private var videoWidth = 486
    private var videoHeight = 1080

    val isRecording: Boolean get() = recorder?.isRecording == true

    companion object {
        private const val TAG_CLIENT = "SCRCPY_CLIENT"
        private const val TAG_SERVER = "SCRCPY_SERVER"
    }

    private fun reportError(msg: String) {
        synchronized(this) {
            if (stoppedByUser || errorReported) return
            errorReported = true
        }
        AppLogger.e(TAG_CLIENT, "Scrcpy Error: $msg")
        sessionScope.launch(Dispatchers.Main) {
            onError(msg)
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

                val serverCmd = "CLASSPATH=${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 ${config.toServerArgs()}"
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
                        scope = this,
                        stream = currentControlStream,
                        videoWidth = { videoWidth },
                        videoHeight = { videoHeight }
                    )

                    if (config.turnScreenOff) {
                        AppLogger.i(TAG_CLIENT, "Sending SET_DISPLAY_POWER(off) via control channel")
                        control?.sendSetDisplayPower(false)
                    }
                }

                if (config.videoEnabled) {
                    val currentVideoStream = videoStream ?: throw Exception("Video stream not available")
                    val surface = holder?.surface ?: throw IllegalArgumentException("SurfaceHolder is required when video is enabled")
                    AppLogger.i(TAG_CLIENT, "Step 4: Initializing MediaCodec video decoder pipeline...")
                    val targetWidth = if (config.maxSize > 0) (config.maxSize * 9 / 16) else 1080
                    val targetHeight = if (config.maxSize > 0) config.maxSize else 1920

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

    fun setSurface(surface: android.view.Surface) {
        videoDecoder?.setSurface(surface)
    }

    fun sendTouchEvent(action: Int, x: Float, y: Float, vw: Int, vh: Int) {
        control?.sendTouchEvent(action, x, y, vw, vh)
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

    fun stop() {
        stoppedByUser = true
        // Stop recording gracefully before tearing down session
        runCatching { stopRecording() }
        if (config.turnScreenOff) {
            runCatching { control?.sendSetDisplayPower(true) }
        }
        runCatching { control?.close() }
        sessionJob?.cancel("Session stopped by user")
        sessionScope.cancel("Session stopped by user")
    }

    private fun cleanup() {
        AppLogger.i(TAG_CLIENT, "Cleaning up scrcpy session streams...")
        runCatching { stopRecording() }
        runCatching { control?.close() }
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