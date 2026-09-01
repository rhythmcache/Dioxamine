package io.github.rhythmcache.dioxamine.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.rhythmcache.dioxamine.core.AppLogger
import java.io.File
import java.nio.ByteBuffer

class ScrcpyRecorder(private val outputFile: File) {
    private val lock = Any()
    
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var videoFormatSet = false
    private var audioFormatSet = false
    private var audioExpected = false
    private var muxer: MediaMuxer? = null
    
    @Volatile var isRecording = false
        private set
        
    private var baseTimestampUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    
    private val pendingVideoSamples = mutableListOf<PendingSample>()
    private val pendingAudioSamples = mutableListOf<PendingSample>()
    
    fun setAudioExpected(expected: Boolean) {
        synchronized(lock) {
            audioExpected = expected
            AppLogger.d("SCRCPY_CLIENT", "ScrcpyRecorder: Audio expected = $expected")
        }
    }
    
    fun setVideoFormat(mime: String, width: Int, height: Int, csdData: ByteArray) {
        synchronized(lock) {
            if (!isRecording || muxerStarted || videoFormatSet) return
            
            val validWidth = if (width > 0) width else 1080
            val validHeight = if (height > 0) height else 1920
            val format = MediaFormat.createVideoFormat(mime, validWidth, validHeight)
            
            if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                val nals = splitAnnexBNalUnits(csdData)
                if (nals.size >= 2) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(nals[0]))
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(nals[1]))
                } else {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csdData))
                }
            } else {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csdData))
            }
            
            runCatching {
                videoTrackIndex = muxer?.addTrack(format) ?: -1
                videoFormatSet = true
                AppLogger.i("SCRCPY_CLIENT", "ScrcpyRecorder: Video track added (index=$videoTrackIndex, format=$mime ${validWidth}x${validHeight})")
                tryStartMuxer()
            }.onFailure { e ->
                AppLogger.e("SCRCPY_CLIENT", "ScrcpyRecorder: Failed to add video track", e)
            }
        }
    }
    
    fun setAudioFormat(sampleRate: Int, channelCount: Int, csd0: ByteArray) {
        synchronized(lock) {
            if (!isRecording || muxerStarted || audioFormatSet) return
            
            val validSampleRate = if (sampleRate > 0) sampleRate else 48000
            val validChannels = if (channelCount > 0) channelCount else 2
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, validSampleRate, validChannels)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            
            runCatching {
                audioTrackIndex = muxer?.addTrack(format) ?: -1
                audioFormatSet = true
                AppLogger.i("SCRCPY_CLIENT", "ScrcpyRecorder: Audio track added (index=$audioTrackIndex, AAC ${validSampleRate}Hz ${validChannels}ch)")
                tryStartMuxer()
            }.onFailure { e ->
                AppLogger.e("SCRCPY_CLIENT", "ScrcpyRecorder: Failed to add audio track", e)
            }
        }
    }
    
    fun writeVideoSample(data: ByteArray, offset: Int, size: Int, pts: Long, flags: Int) {
        synchronized(lock) {
            if (!isRecording) return
            
            if (baseTimestampUs < 0) {
                baseTimestampUs = pts
            }
            
            val normalizedPts = maxOf(0L, pts - baseTimestampUs)
            
            if (!muxerStarted) {
                val sampleData = ByteArray(size)
                System.arraycopy(data, offset, sampleData, 0, size)
                pendingVideoSamples.add(PendingSample(sampleData, size, normalizedPts, flags))
                // If audio was expected but format hasn't arrived after sufficient video samples (~1.5-3s), start with video
                if (pendingVideoSamples.size >= 90 && videoFormatSet) {
                    AppLogger.w("SCRCPY_CLIENT", "ScrcpyRecorder: Audio track timeout, starting muxer with video only")
                    audioExpected = false
                    tryStartMuxer()
                }
                return
            }
            
            if (videoTrackIndex >= 0) {
                writeSampleDataInternal(videoTrackIndex, data, offset, size, normalizedPts, flags, isVideo = true)
            }
        }
    }
    
    fun writeAudioSample(data: ByteArray, offset: Int, size: Int, pts: Long) {
        synchronized(lock) {
            if (!isRecording) return
            
            if (baseTimestampUs < 0) {
                baseTimestampUs = pts
            }
            
            val normalizedPts = maxOf(0L, pts - baseTimestampUs)
            
            if (!muxerStarted) {
                val sampleData = ByteArray(size)
                System.arraycopy(data, offset, sampleData, 0, size)
                pendingAudioSamples.add(PendingSample(sampleData, size, normalizedPts, 0))
                return
            }
            
            if (audioTrackIndex >= 0) {
                writeSampleDataInternal(audioTrackIndex, data, offset, size, normalizedPts, 0, isVideo = false)
            }
        }
    }
    
    private fun writeSampleDataInternal(trackIndex: Int, data: ByteArray, offset: Int, size: Int, pts: Long, flags: Int, isVideo: Boolean) {
        var adjustedPts = pts
        if (isVideo) {
            if (adjustedPts <= lastVideoPtsUs) {
                adjustedPts = lastVideoPtsUs + 1000L // Ensure monotonically increasing by at least 1ms
            }
            lastVideoPtsUs = adjustedPts
        } else {
            if (adjustedPts <= lastAudioPtsUs) {
                adjustedPts = lastAudioPtsUs + 500L
            }
            lastAudioPtsUs = adjustedPts
        }
        
        val buffer = ByteBuffer.wrap(data, offset, size)
        val info = MediaCodec.BufferInfo()
        info.set(offset, size, adjustedPts, flags)
        
        runCatching {
            muxer?.writeSampleData(trackIndex, buffer, info)
        }.onFailure { e ->
            AppLogger.e("SCRCPY_CLIENT", "ScrcpyRecorder: Error writing sample data (track=$trackIndex, size=$size, pts=$adjustedPts)", e)
        }
    }
    
    private fun tryStartMuxer() {
        if (muxerStarted || !isRecording) return
        
        val readyToStart = videoFormatSet && (!audioExpected || audioFormatSet)
        if (readyToStart) {
            runCatching {
                muxer?.start()
                muxerStarted = true
                AppLogger.i("SCRCPY_CLIENT", "ScrcpyRecorder: MediaMuxer started successfully!")
                
                // Flush pending video samples
                for (sample in pendingVideoSamples) {
                    writeSampleDataInternal(videoTrackIndex, sample.data, 0, sample.size, sample.pts, sample.flags, isVideo = true)
                }
                pendingVideoSamples.clear()
                
                // Flush pending audio samples
                if (audioTrackIndex >= 0) {
                    for (sample in pendingAudioSamples) {
                        writeSampleDataInternal(audioTrackIndex, sample.data, 0, sample.size, sample.pts, sample.flags, isVideo = false)
                    }
                }
                pendingAudioSamples.clear()
            }.onFailure { e ->
                AppLogger.e("SCRCPY_CLIENT", "ScrcpyRecorder: Failed to start MediaMuxer", e)
                isRecording = false
            }
        }
    }
    
    fun prepare() {
        synchronized(lock) {
            outputFile.parentFile?.mkdirs()
            runCatching {
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                isRecording = true
                muxerStarted = false
                videoFormatSet = false
                audioFormatSet = false
                baseTimestampUs = -1L
                lastVideoPtsUs = -1L
                lastAudioPtsUs = -1L
                pendingVideoSamples.clear()
                pendingAudioSamples.clear()
                RecordingsManager.markRecordingActive(outputFile)
                AppLogger.i("SCRCPY_CLIENT", "ScrcpyRecorder: Prepared output file ${outputFile.absolutePath}")
            }.onFailure { e ->
                AppLogger.e("SCRCPY_CLIENT", "ScrcpyRecorder: Failed to create MediaMuxer", e)
                isRecording = false
                RecordingsManager.markRecordingFinished(outputFile)
                runCatching { muxer?.release() }
                muxer = null
            }
        }
    }
    
    fun stop() {
        synchronized(lock) {
            if (!isRecording && !muxerStarted) return
            
            isRecording = false
            RecordingsManager.markRecordingFinished(outputFile)
            
            runCatching {
                if (muxerStarted) {
                    muxer?.stop()
                    AppLogger.i("SCRCPY_CLIENT", "ScrcpyRecorder: MediaMuxer stopped cleanly, file size = ${outputFile.length()} bytes")
                } else {
                    AppLogger.w("SCRCPY_CLIENT", "ScrcpyRecorder: Stopped before muxer started (pending video=${pendingVideoSamples.size}, audio=${pendingAudioSamples.size})")
                }
                muxer?.release()
                muxerStarted = false
                muxer = null
            }.onFailure { e ->
                AppLogger.e("SCRCPY_CLIENT", "ScrcpyRecorder: Failed to stop/release MediaMuxer", e)
            }
            
            pendingVideoSamples.clear()
            pendingAudioSamples.clear()
        }
    }
    
    private fun splitAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                starts.add(i)
                i += 4
            } else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts.add(i)
                i += 3
            } else {
                i++
            }
        }
        val nals = mutableListOf<ByteArray>()
        for (idx in 0 until starts.size) {
            val start = starts[idx]
            val end = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            nals.add(data.copyOfRange(start, end))
        }
        return nals
    }
}

private data class PendingSample(
    val data: ByteArray,
    val size: Int,
    val pts: Long,
    val flags: Int
)
