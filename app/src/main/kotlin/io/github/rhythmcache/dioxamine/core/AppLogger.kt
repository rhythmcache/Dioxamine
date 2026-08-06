package io.github.rhythmcache.dioxamine.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * App-wide logging facade. Wraps android.util.Log while retaining
 * an in-memory ring buffer for live UI streaming AND dual-writing to
 * a rotated file sink on disk (context.filesDir/logs/).
 */
object AppLogger {

    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestamp: Long,
        val tag: String,
        val level: Level,
        val message: String,
        val throwable: String? = null
    ) {
        fun formatted(): String {
            val ts = formatter.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
            val lvl = when (level) {
                Level.VERBOSE -> "V"; Level.DEBUG -> "D"; Level.INFO -> "I"
                Level.WARN -> "W"; Level.ERROR -> "E"
            }
            val base = "$ts $lvl/$tag: $message"
            return if (throwable != null) "$base\n$throwable" else base
        }

        companion object {
            private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
        }
    }

    @Volatile
    var enabled: Boolean = true

    private const val MAX_LINES_PER_TAG = 3000
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024 // 5MB before rotation

    private val buffers = mutableMapOf<String, ArrayDeque<Entry>>()
    private val lock = Any()

    private var logDir: File? = null
    private var currentFile: File? = null
    private var writer: BufferedWriter? = null
    private var currentFileBytes: Long = 0L

    private val counter = AtomicLong(0)
    private val _updates = MutableStateFlow(0L)
    val updates = _updates.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (writer != null) return
            val dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
            logDir = dir
            val file = File(dir, "app_log.txt")
            currentFile = file
            currentFileBytes = if (file.exists()) file.length() else 0L
            writer = BufferedWriter(FileWriter(file, /* append = */ true))
        }
    }

    fun v(tag: String, message: String) = log(tag, Level.VERBOSE, message, null)
    fun d(tag: String, message: String) = log(tag, Level.DEBUG, message, null)
    fun i(tag: String, message: String) = log(tag, Level.INFO, message, null)
    fun w(tag: String, message: String) = log(tag, Level.WARN, message, null)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(tag, Level.ERROR, message, throwable)

    /** For piping raw external process output (e.g. scrcpy-server stdout) line-by-line. */
    fun raw(tag: String, line: String) = log(tag, Level.INFO, line, null)

    private fun log(tag: String, level: Level, message: String, throwable: Throwable?) {
        if (!enabled) return

        when (level) {
            Level.VERBOSE -> Log.v(tag, message, throwable)
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR -> Log.e(tag, message, throwable)
        }

        val entry = Entry(System.currentTimeMillis(), tag, level, message, throwable?.stackTraceToString())
        synchronized(lock) {
            val buf = buffers.getOrPut(tag) { ArrayDeque() }
            buf.addLast(entry)
            while (buf.size > MAX_LINES_PER_TAG) buf.removeFirst()

            writer?.let { w ->
                runCatching {
                    val line = entry.formatted() + "\n"
                    w.write(line)
                    currentFileBytes += line.toByteArray(Charsets.UTF_8).size
                    if (level == Level.ERROR) w.flush()
                }
                if (currentFileBytes >= MAX_FILE_BYTES) {
                    rotateFilesLocked()
                }
            }
        }
        _updates.value = counter.incrementAndGet()
    }

    private fun rotateFilesLocked() {
        val file = currentFile ?: return
        runCatching {
            writer?.flush()
            writer?.close()
            val rotated = File(file.parentFile, "${file.name}.1")
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)

            currentFile = File(file.parentFile, "app_log.txt")
            writer = BufferedWriter(FileWriter(currentFile!!, false))
            currentFileBytes = 0L
        }
    }

    fun getEntries(tag: String): List<Entry> = synchronized(lock) {
        buffers[tag]?.toList() ?: emptyList()
    }

    fun getEntries(tags: List<String>): List<Entry> = synchronized(lock) {
        tags.flatMap { buffers[it]?.toList() ?: emptyList() }.sortedBy { it.timestamp }
    }

    fun export(tags: List<String>): String =
        getEntries(tags).joinToString("\n") { it.formatted() }

    fun export(tag: String): String = export(listOf(tag))

    /** Write persistent logs and device_info.json directly to a ZipOutputStream without loading into memory. */
    fun writePersistedLogsToZip(zos: ZipOutputStream, activeConn: DeviceConnection? = null) {
        synchronized(lock) {
            writer?.flush()
            val dir = logDir ?: return

            val rotated = File(dir, "app_log.txt.1")
            if (rotated.exists() && rotated.length() > 0) {
                zos.putNextEntry(ZipEntry("app_log_previous.txt"))
                rotated.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            val current = File(dir, "app_log.txt")
            if (current.exists() && current.length() > 0) {
                zos.putNextEntry(ZipEntry("app_log_current.txt"))
                current.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            zos.putNextEntry(ZipEntry("device_info.json"))
            zos.write(DeviceInfoCollector.collect(includeTargetDevice = false).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    fun activeTags(): List<String> = synchronized(lock) {
        buffers.entries.filter { it.value.isNotEmpty() }.map { it.key }
    }

    fun clear(tag: String) {
        synchronized(lock) { buffers.remove(tag) }
        _updates.value = counter.incrementAndGet()
    }

    fun clearAll() {
        synchronized(lock) { buffers.clear() }
        _updates.value = counter.incrementAndGet()
    }

    fun clearPersistedLogs() {
        synchronized(lock) {
            runCatching { writer?.close() }
            logDir?.listFiles()?.forEach { it.delete() }
            currentFile?.let { file ->
                runCatching {
                    writer = BufferedWriter(FileWriter(file, false))
                    currentFileBytes = 0L
                }
            }
            buffers.clear()
        }
        _updates.value = counter.incrementAndGet()
    }
}