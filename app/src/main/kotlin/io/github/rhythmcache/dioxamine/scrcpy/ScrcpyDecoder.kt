package io.github.rhythmcache.dioxamine.scrcpy

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.adb.readExactly
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * Handles video decoding from scrcpy 4.1 framed stream using Android MediaCodec.
 * Supported video codecs: H.264 (AVC), H.265 (HEVC), and AV1.
 * Uses reusable buffers to eliminate allocations on every frame.
 */
class ScrcpyDecoder(
    private var surface: Surface,
    private val defaultWidth: Int = 1080,
    private val defaultHeight: Int = 1920,
    private val onDimensionsParsed: (Int, Int) -> Unit = { _, _ -> },
    private val isStoppedByUser: () -> Boolean = { false }
) {
    private var codec: MediaCodec? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC

    var recorder: ScrcpyRecorder? = null
    private var lastConfigBytes: ByteArray? = null

    private val headerBuffer = ByteArray(12)
    private var payloadBuffer = ByteArray(256 * 1024)
    private var pendingConfigBytes: ByteArray? = null
    private var mustMergeConfigPacket = true

    companion object {
        private const val TAG = "SCRCPY_CLIENT"
        private const val PACKET_FLAG_SESSION = 1L shl 63
        private const val PACKET_FLAG_CONFIG = 1L shl 62
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 61
    }

    fun attachRecorder(rec: ScrcpyRecorder) {
        this.recorder = rec
        val config = lastConfigBytes
        if (config != null) {
            val w = if (currentWidth > 0) currentWidth else defaultWidth
            val h = if (currentHeight > 0) currentHeight else defaultHeight
            rec.setVideoFormat(mimeType, w, h, config)
        }
    }

    fun setSurface(newSurface: Surface) {
        this.surface = newSurface
        runCatching {
            codec?.setOutputSurface(newSurface)
            AppLogger.i(TAG, "ScrcpyDecoder: Output surface swapped successfully")
        }.onFailure {
            AppLogger.e(TAG, "ScrcpyDecoder: Failed to swap output surface: ${it.message}", it)
        }
    }

    suspend fun decodeStream(stream: AdbStream) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "ScrcpyDecoder: Starting video stream decoding (framed mode)...")
        try {
            // --- Step 1: Codec ID header (4 bytes) ---
            val codecIdBuf = ByteArray(4)
            if (!stream.readExactly(codecIdBuf)) throw Exception("EOF reading codec ID header")

            val codecIdInt = ((codecIdBuf[0].toInt() and 0xFF) shl 24) or
                    ((codecIdBuf[1].toInt() and 0xFF) shl 16) or
                    ((codecIdBuf[2].toInt() and 0xFF) shl 8) or
                    (codecIdBuf[3].toInt() and 0xFF)

            mimeType = when (codecIdInt) {
                0x68323634 -> MediaFormat.MIMETYPE_VIDEO_AVC   // "h264"
                0x68323635 -> MediaFormat.MIMETYPE_VIDEO_HEVC  // "h265"
                0x00617631 -> MediaFormat.MIMETYPE_VIDEO_AV1   // "av1"
                else -> {
                    AppLogger.e(TAG, "Unknown codec id: 0x%08x".format(codecIdInt))
                    MediaFormat.MIMETYPE_VIDEO_AVC
                }
            }
            AppLogger.i(TAG, "Codec header raw=0x%08x -> MimeType=$mimeType".format(codecIdInt))
            mustMergeConfigPacket = (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC || mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC)
            AppLogger.i(TAG, "ScrcpyDecoder: mustMergeConfigPacket=$mustMergeConfigPacket")

            // --- Step 2: Session packet (12 bytes) ---
            val sessionBuf = ByteArray(12)
            if (!stream.readExactly(sessionBuf)) throw Exception("EOF reading session packet")
            initSession(sessionBuf)

            // --- Step 3: Repeating media packets ---
            val bufferInfo = MediaCodec.BufferInfo()
            var totalBytesRead = 0L

            while (!stream.isClosed) {
                if (codec == null || isStoppedByUser()) break

                if (!stream.readExactly(headerBuffer)) {
                    if (isStoppedByUser()) {
                        AppLogger.i(TAG, "ScrcpyDecoder: Stream closed (user stop)")
                        break
                    }
                    throw java.io.IOException("Video stream closed unexpectedly (EOF)")
                }

                val ptsAndFlags = ByteBuffer.wrap(headerBuffer, 0, 8).long
                val isSessionPacket = (ptsAndFlags and PACKET_FLAG_SESSION) != 0L

                if (isSessionPacket) {
                    AppLogger.i(TAG, "ScrcpyDecoder: Mid-stream session packet detected")
                    initSession(headerBuffer)
                    continue
                }

                val isConfigPacket = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
                val pts = ptsAndFlags and (PACKET_FLAG_KEY_FRAME - 1)
                val packetSize = ByteBuffer.wrap(headerBuffer, 8, 4).int

                if (packetSize <= 0 || packetSize > 8_000_000) {
                    AppLogger.w(TAG, "ScrcpyDecoder: Implausible packet size $packetSize, stopping")
                    if (!isStoppedByUser()) {
                        throw java.io.IOException("Implausible video packet size ($packetSize bytes)")
                    }
                    break
                }

                if (packetSize > payloadBuffer.size) {
                    payloadBuffer = ByteArray(packetSize)
                }

                if (!stream.readExactly(payloadBuffer, 0, packetSize)) {
                    if (isStoppedByUser()) break
                    throw java.io.IOException("Video stream closed unexpectedly (payload EOF)")
                }
                totalBytesRead += packetSize

                if (isConfigPacket) {
                    val configData = payloadBuffer.copyOf(packetSize)
                    lastConfigBytes = configData
                    val w = if (currentWidth > 0) currentWidth else defaultWidth
                    val h = if (currentHeight > 0) currentHeight else defaultHeight
                    recorder?.setVideoFormat(mimeType, w, h, configData)

                    if (mustMergeConfigPacket) {
                        pendingConfigBytes = configData
                        AppLogger.i(TAG, "Buffered config packet ($packetSize bytes), waiting for next media packet")
                    } else {
                        val mc = codec ?: continue
                        var inIndex = -1
                        var retries = 0
                        while (inIndex < 0 && retries < 5) {
                            inIndex = try { mc.dequeueInputBuffer(2000) } catch (e: IllegalStateException) { -2 }
                            if (inIndex == -2) break
                            if (inIndex < 0) { drainOutput(mc, bufferInfo); retries++ }
                        }
                        if (inIndex >= 0) {
                            val inputBuffer = mc.getInputBuffer(inIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                inputBuffer.put(payloadBuffer, 0, packetSize)
                                mc.queueInputBuffer(inIndex, 0, packetSize, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            }
                        }
                        drainOutput(mc, bufferInfo)
                    }
                    continue
                } else {
                    val configBytes = pendingConfigBytes
                    val finalSize: Int
                    val finalBuffer: ByteArray

                    if (configBytes != null) {
                        finalSize = configBytes.size + packetSize
                        finalBuffer = ByteArray(finalSize)
                        System.arraycopy(configBytes, 0, finalBuffer, 0, configBytes.size)
                        System.arraycopy(payloadBuffer, 0, finalBuffer, configBytes.size, packetSize)
                        pendingConfigBytes = null
                        AppLogger.i(TAG, "Merged config (${configBytes.size}b) + media ($packetSize b) = $finalSize b")
                    } else {
                        finalSize = packetSize
                        finalBuffer = payloadBuffer
                    }

                    val mc = codec ?: break

                    var inIndex = -1
                    var retries = 0
                    while (inIndex < 0 && retries < 5) {
                        inIndex = try {
                            mc.dequeueInputBuffer(2000)
                        } catch (e: IllegalStateException) {
                            -2
                        }
                        if (inIndex == -2) {
                            if (codec == null || isStoppedByUser()) break
                            retries++
                        } else if (inIndex < 0) {
                            drainOutput(mc, bufferInfo)
                            retries++
                        }
                    }

                    if (codec == null || isStoppedByUser()) break

                    if (inIndex >= 0) {
                        val inputBuffer = mc.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(finalBuffer, 0, finalSize)
                            mc.queueInputBuffer(inIndex, 0, finalSize, pts, 0)
                        }
                    }

                    // Tee media packet to recorder
                    val isKeyFrame = (ptsAndFlags and PACKET_FLAG_KEY_FRAME) != 0L
                    val recorderFlags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    recorder?.writeVideoSample(finalBuffer, 0, finalSize, pts, recorderFlags)

                    drainOutput(mc, bufferInfo)
                }
            }
            AppLogger.i(TAG, "ScrcpyDecoder: Stream ended. Total payload bytes = $totalBytesRead")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "ScrcpyDecoder Error: ${e.message}", e)
            if (!isStoppedByUser()) throw e
        } finally {
            stop()
        }
    }

    private fun drainOutput(mc: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        if (codec == null || isStoppedByUser()) return
        var outIndex = try { mc.dequeueOutputBuffer(bufferInfo, 0) } catch (e: IllegalStateException) {
            return
        }
        while (true) {
            when {
                outIndex >= 0 -> {
                    runCatching { mc.releaseOutputBuffer(outIndex, true) }
                    outIndex = try { mc.dequeueOutputBuffer(bufferInfo, 0) } catch (e: IllegalStateException) {
                        return
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    AppLogger.i(TAG, "drainOutput: format changed to ${mc.outputFormat}")
                    outIndex = try { mc.dequeueOutputBuffer(bufferInfo, 0) } catch (e: IllegalStateException) {
                        return
                    }
                }
                else -> break
            }
        }
    }

    private suspend fun initSession(sessionBuf: ByteArray) {
        val sessionFlags = ByteBuffer.wrap(sessionBuf, 0, 4).int
        val isSessionPacket = (sessionFlags.toLong() and (PACKET_FLAG_SESSION ushr 32)) != 0L
        var width = if (currentWidth > 0) currentWidth else defaultWidth
        var height = if (currentHeight > 0) currentHeight else defaultHeight

        if (isSessionPacket) {
            val w = ByteBuffer.wrap(sessionBuf, 4, 4).int
            val h = ByteBuffer.wrap(sessionBuf, 8, 4).int
            if (w in 1..8192 && h in 1..8192) {
                width = w
                height = h
            } else {
                AppLogger.w(TAG, "ScrcpyDecoder: Implausible session dims ($w x $h), keeping previous")
            }
        }

        if (codec != null && width == currentWidth && height == currentHeight) {
            runCatching { codec?.flush() }
            AppLogger.i(TAG, "ScrcpyDecoder: Flushed existing decoder for resolution ${width}x${height}")
            return
        }

        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null

        currentWidth = width
        currentHeight = height

        withContext(Dispatchers.Main) {
            onDimensionsParsed(width, height)
        }

        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        codec = createDecoderWithFallback(mimeType, width, height, format, surface)
        AppLogger.i(TAG, "ScrcpyDecoder: (Re)initialized decoder for ${width}x${height} using $mimeType")
    }

    private fun createDecoderWithFallback(
        mimeType: String,
        width: Int,
        height: Int,
        format: MediaFormat,
        outputSurface: Surface
    ): MediaCodec {
        val candidates = rankedDecoderCandidates(mimeType, width, height)

        if (candidates.isEmpty()) {
            AppLogger.w(TAG, "ScrcpyDecoder: No codec explicitly supports ${width}x${height}; trying default selection")
            return tryConfigureDefault(mimeType, format, outputSurface)
        }

        var lastError: Exception? = null
        for (name in candidates) {
            try {
                AppLogger.i(TAG, "ScrcpyDecoder: Trying decoder candidate '$name' for ${width}x${height}")
                val mc = MediaCodec.createByCodecName(name)
                mc.configure(format, outputSurface, null, 0)
                mc.start()
                AppLogger.i(TAG, "ScrcpyDecoder: Decoder '$name' configured and started successfully")
                return mc
            } catch (e: Exception) {
                AppLogger.w(TAG, "ScrcpyDecoder: Candidate '$name' failed: ${e.message}")
                lastError = e
            }
        }

        AppLogger.e(TAG, "ScrcpyDecoder: All ${candidates.size} candidate decoder(s) failed, trying default selection as last resort")
        return try {
            tryConfigureDefault(mimeType, format, outputSurface)
        } catch (e: Exception) {
            throw Exception("No working $mimeType decoder found for ${width}x${height} (tried ${candidates.size} candidates)", lastError ?: e)
        }
    }

    private fun tryConfigureDefault(mimeType: String, format: MediaFormat, outputSurface: Surface): MediaCodec {
        val mc = MediaCodec.createDecoderByType(mimeType)
        mc.configure(format, outputSurface, null, 0)
        mc.start()
        return mc
    }

    private data class DecoderCandidate(val name: String, val isHardware: Boolean, val sizeSupport: Boolean?)

    private fun rankedDecoderCandidates(mimeType: String, width: Int, height: Int): List<String> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val candidates = mutableListOf<DecoderCandidate>()

        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            val types = info.supportedTypes
            if (types.none { it.equals(mimeType, ignoreCase = true) }) continue

            val isHw = isHardwareAccelerated(info)
            val sizeSupport: Boolean? = runCatching {
                info.getCapabilitiesForType(mimeType).videoCapabilities?.isSizeSupported(width, height)
            }.getOrNull()

            candidates.add(DecoderCandidate(info.name, isHw, sizeSupport))
        }

        val viable = candidates.filter { it.sizeSupport != false }

        val ranked = viable.sortedWith(
            compareByDescending<DecoderCandidate> { it.isHardware && it.sizeSupport == true }
                .thenByDescending { it.isHardware }
                .thenByDescending { it.sizeSupport == true }
        )

        return ranked.map { it.name }.distinct()
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val n = info.name.lowercase()
            !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("sw"))
        }
    }

    fun stop() {
        AppLogger.i(TAG, "ScrcpyDecoder: Stopping MediaCodec decoder...")
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }
}