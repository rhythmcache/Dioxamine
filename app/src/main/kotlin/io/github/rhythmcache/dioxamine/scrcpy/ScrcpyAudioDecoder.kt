package io.github.rhythmcache.dioxamine.scrcpy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.adb.readExactly
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * Handles audio stream decoding from scrcpy using MediaCodec + AudioTrack playback.
 * Supported audio codecs: Opus, AAC, FLAC, and RAW (uncompressed PCM).
 * Uses reusable buffers and direct ByteBuffer writes to remove GC allocations.
 */
class ScrcpyAudioDecoder {
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannelCount = 0

    // Preallocated buffers to prevent GC allocation churn
    private val headerBuffer = ByteArray(12)
    private var payloadBuffer = ByteArray(64 * 1024) // 64KB default, auto-expands

    companion object {
        private const val TAG = "SCRCPY_CLIENT"
        private const val PACKET_FLAG_CONFIG = 1L shl 62
    }

    var recorder: ScrcpyRecorder? = null
    private var isAAC = false
    private var lastCsdBytes: ByteArray? = null
    private var lastSampleRate: Int = 48000
    private var lastChannelCount: Int = 2

    fun attachRecorder(rec: ScrcpyRecorder) {
        this.recorder = rec
        val csd = lastCsdBytes
        if (isAAC && csd != null) {
            rec.setAudioFormat(lastSampleRate, lastChannelCount, csd)
        }
    }

    suspend fun decodeAudioStream(stream: AdbStream) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "ScrcpyAudioDecoder: Starting audio stream decoding...")
        try {
            // --- Step 1: Read 4-byte Audio Codec Header ---
            val codecIdBuf = ByteArray(4)
            if (!stream.readExactly(codecIdBuf)) {
                AppLogger.w(TAG, "ScrcpyAudioDecoder: EOF while reading codec header")
                return@withContext
            }

            val codecId = ByteBuffer.wrap(codecIdBuf).int
            if (codecId == 0) {
                AppLogger.w(TAG, "ScrcpyAudioDecoder: Audio disabled by device (code 0). Skipping audio.")
                return@withContext
            }
            if (codecId == 1) {
                AppLogger.e(TAG, "ScrcpyAudioDecoder: Audio configuration error on device (code 1).")
                return@withContext
            }

            val fourCC = String(codecIdBuf, Charsets.US_ASCII).trim()
            AppLogger.i(TAG, "ScrcpyAudioDecoder: Audio codec header = 0x${codecId.toString(16)} ('$fourCC')")

            val isRaw = fourCC == "raw"
            var sampleRate = 48000
            var channelCount = 2
            lastSampleRate = sampleRate
            lastChannelCount = channelCount

            recreateAudioTrack(sampleRate, channelCount)

            var packetCount = 0

            // Read the first frame header + payload - expected to be the config (CSD) packet
            val firstHeader = ByteArray(12)
            if (!stream.readExactly(firstHeader)) {
                AppLogger.w(TAG, "ScrcpyAudioDecoder: EOF reading first frame header")
                return@withContext
            }
            val firstPtsAndFlags = ByteBuffer.wrap(firstHeader, 0, 8).long
            val firstPayloadSize = ByteBuffer.wrap(firstHeader, 8, 4).int
            val firstIsConfig = (firstPtsAndFlags and PACKET_FLAG_CONFIG) != 0L

            var csdBytes: ByteArray? = null
            var firstPayload: ByteArray? = null

            if (firstPayloadSize > 0 && firstPayloadSize <= 1_048_576) {
                val payload = ByteArray(firstPayloadSize)
                if (!stream.readExactly(payload)) {
                    AppLogger.w(TAG, "ScrcpyAudioDecoder: EOF reading first payload ($firstPayloadSize bytes)")
                    return@withContext
                }
                if (firstIsConfig && firstPayloadSize in 1..4096) {
                    csdBytes = payload
                    lastCsdBytes = payload
                    packetCount++
                    AppLogger.i(TAG, "ScrcpyAudioDecoder: Got CSD-0, ${csdBytes.size} bytes: ${csdBytes.joinToString(" ") { "%02x".format(it) }}")
                } else {
                    firstPayload = payload
                }
            }

            var mediaCodec: MediaCodec? = null
            if (!isRaw) {
                val mimeType = when (fourCC) {
                    "opus" -> MediaFormat.MIMETYPE_AUDIO_OPUS
                    "flac" -> MediaFormat.MIMETYPE_AUDIO_FLAC
                    else -> MediaFormat.MIMETYPE_AUDIO_AAC
                }
                isAAC = (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC)
                val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)
                if (csdBytes != null) {
                    if (mimeType == MediaFormat.MIMETYPE_AUDIO_FLAC) {
                        val flacHeader = byteArrayOf(
                            0x66.toByte(), 0x4c.toByte(), 0x61.toByte(), 0x43.toByte(),
                            0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x22.toByte()
                        )
                        val flacCsd = ByteArray(flacHeader.size + csdBytes.size)
                        System.arraycopy(flacHeader, 0, flacCsd, 0, flacHeader.size)
                        System.arraycopy(csdBytes, 0, flacCsd, flacHeader.size, csdBytes.size)
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(flacCsd))
                    } else {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(csdBytes))
                    }

                    if (mimeType == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                        val preSkipVal = 312L * 1_000_000_000L / 48000L
                        val seekPreRollVal = 80_000_000L
                        
                        val csd1Bytes = ByteArray(8)
                        ByteBuffer.wrap(csd1Bytes).order(java.nio.ByteOrder.nativeOrder()).putLong(preSkipVal)
                        val csd2Bytes = ByteArray(8)
                        ByteBuffer.wrap(csd2Bytes).order(java.nio.ByteOrder.nativeOrder()).putLong(seekPreRollVal)
                        
                        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1Bytes))
                        format.setByteBuffer("csd-2", ByteBuffer.wrap(csd2Bytes))
                    }
                }
                mediaCodec = MediaCodec.createDecoderByType(mimeType)
                mediaCodec.configure(format, null, null, 0)
                mediaCodec.start()
                codec = mediaCodec
                AppLogger.i(TAG, "ScrcpyAudioDecoder: $fourCC decoder and AudioTrack started successfully")

                // Tee audio format to recorder (only for AAC)
                if (isAAC && csdBytes != null) {
                    recorder?.setAudioFormat(sampleRate, channelCount, csdBytes)
                }
            } else {
                AppLogger.i(TAG, "ScrcpyAudioDecoder: RAW PCM playback and AudioTrack started successfully")
            }

            val bufferInfo = MediaCodec.BufferInfo()

            // If we read a non-config packet first, queue it now
            if (firstPayload != null) {
                packetCount++
                if (isRaw) {
                    audioTrack?.write(firstPayload, 0, firstPayload.size)
                } else {
                    val mc = mediaCodec ?: return@withContext
                    val inIndex = mc.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inputBuffer = mc.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(firstPayload)
                            mc.queueInputBuffer(inIndex, 0, firstPayload.size, System.nanoTime() / 1000, 0)
                        }
                    }
                }
            }

            // --- Step 2: Read repeating audio frames ---
            while (!stream.isClosed) {
                if (!stream.readExactly(headerBuffer)) {
                    AppLogger.w(TAG, "ScrcpyAudioDecoder: Stream EOF while reading audio frame header (after $packetCount packets)")
                    break
                }

                val ptsAndFlags = ByteBuffer.wrap(headerBuffer, 0, 8).long
                val payloadSize = ByteBuffer.wrap(headerBuffer, 8, 4).int

                packetCount++

                if (payloadSize <= 0 || payloadSize > 1_048_576) {
                    AppLogger.e(TAG, "ScrcpyAudioDecoder: Invalid payload size: $payloadSize (after $packetCount packets)")
                    break
                }

                // Auto-expand payload buffer if necessary
                if (payloadSize > payloadBuffer.size) {
                    payloadBuffer = ByteArray(payloadSize)
                }

                if (!stream.readExactly(payloadBuffer, 0, payloadSize)) {
                    AppLogger.w(TAG, "ScrcpyAudioDecoder: Stream EOF while reading payload ($payloadSize bytes)")
                    break
                }

                if (isRaw) {
                    audioTrack?.write(payloadBuffer, 0, payloadSize)
                } else {
                    val isConfigPacket = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
                    val flags = if (isConfigPacket) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    val pts = ptsAndFlags and (PACKET_FLAG_CONFIG - 1)

                    // Tee non-config AAC audio samples to recorder
                    if (isAAC && !isConfigPacket) {
                        recorder?.writeAudioSample(payloadBuffer, 0, payloadSize, pts)
                    }

                    val mc = mediaCodec ?: break
                    val inIndex = mc.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inputBuffer = mc.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(payloadBuffer, 0, payloadSize)
                            mc.queueInputBuffer(inIndex, 0, payloadSize, System.nanoTime() / 1000, flags)
                        }
                    }

                    var outIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
                    while (true) {
                        when {
                            outIndex >= 0 -> {
                                val outputBuffer = mc.getOutputBuffer(outIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    // Use direct ByteBuffer write to avoid array allocation
                                    audioTrack?.write(outputBuffer, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                                }
                                runCatching { mc.releaseOutputBuffer(outIndex, false) }
                                outIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
                            }
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val newFormat = mc.outputFormat
                                val newSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val newChannelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                AppLogger.i(TAG, "ScrcpyAudioDecoder: Codec output format changed, recreating AudioTrack")
                                recreateAudioTrack(newSampleRate, newChannelCount)
                                outIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
                            }
                            else -> {
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                AppLogger.e(TAG, "ScrcpyAudioDecoder Error: ${e.message}", e)
            }
        } finally {
            stop()
        }
    }

    private fun recreateAudioTrack(sampleRate: Int, channelCount: Int) {
        if (audioTrack != null && sampleRate == currentSampleRate && channelCount == currentChannelCount) {
            return
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}

        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, 16384))
            .build()

        audioTrack?.play()
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        AppLogger.i(TAG, "ScrcpyAudioDecoder: Rebuilt AudioTrack for ${sampleRate}Hz, ${channelCount}ch")
    }

    fun stop() {
        AppLogger.i(TAG, "ScrcpyAudioDecoder: Stopping AudioTrack & MediaCodec...")
        runCatching {
            codec?.stop()
            codec?.release()
        }
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        codec = null
        audioTrack = null
    }
}
