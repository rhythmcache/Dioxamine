package io.github.rhythmcache.dioxamine.scrcpy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import io.github.rhythmcache.dioxamine.core.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingInfo(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val createdAt: Long
)

object RecordingsManager {
    private const val TAG = "SCRCPY_CLIENT"
    private const val RECORDINGS_DIR = "scrcpy_recordings"
    const val FILE_PROVIDER_AUTHORITY = "io.github.rhythmcache.dioxamine.fileprovider"

    private val activeRecordingFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun markRecordingActive(file: File) {
        activeRecordingFiles.add(file.absolutePath)
    }

    fun markRecordingFinished(file: File) {
        activeRecordingFiles.remove(file.absolutePath)
    }

    fun isRecordingActive(file: File): Boolean {
        return activeRecordingFiles.contains(file.absolutePath)
    }

    fun getRecordingsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), RECORDINGS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun generateRecordingFile(context: Context): File {
        val dir = getRecordingsDir(context)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        return File(dir, "scrcpy_$timestamp.mp4")
    }

    fun hasEnoughFreeSpace(context: Context, minBytes: Long = 50 * 1024 * 1024): Boolean {
        val dir = getRecordingsDir(context)
        val freeBytes = dir.usableSpace
        return freeBytes >= minBytes
    }

    fun listRecordings(context: Context): List<RecordingInfo> {
        val dir = getRecordingsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("mp4", ignoreCase = true) }
            ?: return emptyList()

        return files.filter { file ->
            !activeRecordingFiles.contains(file.absolutePath)
        }.mapNotNull { file ->
            runCatching {
                val durationMs = getDuration(file)
                RecordingInfo(
                    file = file,
                    name = file.name,
                    sizeBytes = file.length(),
                    durationMs = durationMs,
                    createdAt = file.lastModified()
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    private fun getDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to get duration for ${file.name}: ${e.message}")
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun deleteRecording(file: File): Boolean {
        return runCatching {
            val deleted = file.delete()
            AppLogger.i(TAG, "Recording deleted: ${file.name} (success=$deleted)")
            deleted
        }.getOrElse { e ->
            AppLogger.e(TAG, "Failed to delete recording: ${e.message}", e)
            false
        }
    }

    fun exportRecording(context: Context, sourceFile: File, destUri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            AppLogger.i(TAG, "Recording exported: ${sourceFile.name}")
            true
        }.getOrElse { e ->
            AppLogger.e(TAG, "Failed to export recording: ${e.message}", e)
            false
        }
    }

    fun openRecording(context: Context, file: File) {
        runCatching {
            val contentUri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { e ->
            if (e is ActivityNotFoundException) {
                AppLogger.w(TAG, "No video player found to open recording")
            } else {
                AppLogger.e(TAG, "Failed to open recording: ${e.message}", e)
            }
        }
    }

    fun getRecordingThumbnail(file: File): android.graphics.Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to get thumbnail for ${file.name}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
