package io.github.rhythmcache.dioxamine.adb.builtin.processmanager

import android.content.Context
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.EOFException

class ProcessManagerClient(
    private val context: Context,
    private val client: AdbClient
) {
    companion object {
        private const val TAG = "ProcessManagerClient"
        private const val SOCKET_NAME = "localabstract:diox_agent"
        private const val CMD_LIST_PROCESSES = 0x01
        private const val CMD_GET_ICON = 0x02
        private const val CMD_FORCE_STOP = 0x03
        private const val CMD_KILL_PID = 0x04

        private const val STATUS_OK = 0
        private const val STATUS_ERROR = 1
    }

    suspend fun ensureDaemonStarted(): Boolean = withContext(Dispatchers.IO) {
        // First check if socket is already responsive
        try {
            val testStream = client.open(SOCKET_NAME)
            testStream.close()
            return@withContext true
        } catch (_: Exception) {
            // Not running yet, proceed with launch
        }

        runCatching {
            context.assets.open("diox-agent.jar").use { input ->
                client.sync.push(input, "${Constants.DEVICE_TMP_DIR}/diox-agent.jar")
            }

            // Launch daemon in background
            val daemonCmd = "CLASSPATH=${Constants.DEVICE_TMP_DIR}/diox-agent.jar app_process / DioxAgent --daemon"
            client.open("shell:$daemonCmd")

            for (i in 1..15) {
                delay(100)
                try {
                    val testStream = client.open(SOCKET_NAME)
                    testStream.close()
                    return@withContext true
                } catch (_: Exception) {
                }
            }
            false
        }.getOrDefault(false)
    }

    suspend fun fetchProcesses(): Pair<SystemMemoryStats, List<ProcessItem>> = withContext(Dispatchers.IO) {
        if (!ensureDaemonStarted()) {
            throw Exception("Could not connect to DioxAgent daemon ($SOCKET_NAME)")
        }

        val stream = client.open(SOCKET_NAME)
        try {
            val out = Buffer()
            out.writeByte(CMD_LIST_PROCESSES)
            stream.write(out.readByteArray())

            val reader = AgentStreamReader(stream)
            val status = reader.readUnsignedByte()
            if (status != STATUS_OK) {
                val errorMsg = reader.readUTF()
                throw Exception(errorMsg.ifBlank { "Daemon reported error $status" })
            }

            val totalRamKb = reader.readLong()
            val availRamKb = reader.readLong()
            val cpuUsagePercent = reader.readDouble()
            val cpuCoreCount = reader.readInt()
            val count = reader.readInt()

            val items = ArrayList<ProcessItem>(count.coerceAtLeast(0))

            for (i in 0 until count) {
                val pid = reader.readInt()
                val uid = reader.readInt()
                val processName = reader.readUTF()
                val packageName = reader.readUTF()
                val appLabel = reader.readUTF()
                val rssKb = reader.readLong()
                val threadCount = reader.readInt()
                val cpuPercent = reader.readDouble()
                val isSystemApp = reader.readBoolean()

                items.add(
                    ProcessItem(
                        pid = pid,
                        uid = uid,
                        processName = processName,
                        packageName = packageName,
                        appLabel = appLabel,
                        rssKb = rssKb,
                        threadCount = threadCount,
                        cpuPercent = cpuPercent,
                        isSystemApp = isSystemApp
                    )
                )
            }

            Pair(SystemMemoryStats(totalRamKb, availRamKb, cpuUsagePercent, cpuCoreCount), items)
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    suspend fun fetchIcon(packageName: String): ByteArray? = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext null
        if (!ensureDaemonStarted()) return@withContext null

        try {
            val stream = client.open(SOCKET_NAME)
            val out = Buffer()
            out.writeByte(CMD_GET_ICON)
            out.writeJavaUtf(packageName)
            stream.write(out.readByteArray())

            val reader = AgentStreamReader(stream)
            val status = reader.readUnsignedByte()
            if (status != STATUS_OK) {
                stream.close()
                return@withContext null
            }

            val length = reader.readInt()
            if (length <= 0 || length > 10 * 1024 * 1024) {
                stream.close()
                return@withContext null
            }

            val bytes = ByteArray(length)
            val ok = reader.readFully(bytes)
            stream.close()
            if (ok) bytes else null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to fetch icon for $packageName: ${e.message}")
            null
        }
    }

    suspend fun forceStop(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext Result.failure(IllegalArgumentException("Package name is blank"))
        if (!ensureDaemonStarted()) return@withContext Result.failure(Exception("Daemon not connected"))

        try {
            val stream = client.open(SOCKET_NAME)
            val out = Buffer()
            out.writeByte(CMD_FORCE_STOP)
            out.writeJavaUtf(packageName)
            stream.write(out.readByteArray())

            val reader = AgentStreamReader(stream)
            val status = reader.readUnsignedByte()
            if (status == STATUS_OK) {
                stream.close()
                Result.success(Unit)
            } else {
                val msg = reader.readUTF()
                stream.close()
                Result.failure(Exception(msg.ifBlank { "Force stop failed" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun killPid(pid: Int, signal: Int = 9): Result<Unit> = withContext(Dispatchers.IO) {
        if (pid <= 0) return@withContext Result.failure(IllegalArgumentException("Invalid PID: $pid"))
        if (!ensureDaemonStarted()) return@withContext Result.failure(Exception("Daemon not connected"))

        try {
            val stream = client.open(SOCKET_NAME)
            val out = Buffer()
            out.writeByte(CMD_KILL_PID)
            out.writeInt(pid)
            out.writeInt(signal)
            stream.write(out.readByteArray())

            val reader = AgentStreamReader(stream)
            val status = reader.readUnsignedByte()
            if (status == STATUS_OK) {
                stream.close()
                Result.success(Unit)
            } else {
                val msg = reader.readUTF()
                stream.close()
                Result.failure(Exception(msg.ifBlank { "Kill PID failed" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

private class AgentStreamReader(private val stream: AdbStream) {
    private val buffer = Buffer()
    private var isEof = false

    suspend fun readByte(): Byte {
        if (!ensureBytes(1)) throw EOFException("Unexpected EOF reading byte")
        return buffer.readByte()
    }

    suspend fun readUnsignedByte(): Int {
        return readByte().toInt() and 0xFF
    }

    suspend fun readBoolean(): Boolean {
        return readByte().toInt() != 0
    }

    suspend fun readShort(): Short {
        if (!ensureBytes(2)) throw EOFException("Unexpected EOF reading short")
        return buffer.readShort()
    }

    suspend fun readUnsignedShort(): Int {
        return readShort().toInt() and 0xFFFF
    }

    suspend fun readInt(): Int {
        if (!ensureBytes(4)) throw EOFException("Unexpected EOF reading int")
        return buffer.readInt()
    }

    suspend fun readLong(): Long {
        if (!ensureBytes(8)) throw EOFException("Unexpected EOF reading long")
        return buffer.readLong()
    }

    suspend fun readDouble(): Double {
        return Double.fromBits(readLong())
    }

    suspend fun readUTF(): String {
        val len = readUnsignedShort()
        if (len == 0) return ""
        if (!ensureBytes(len.toLong())) throw EOFException("Unexpected EOF reading UTF of len $len")
        val bytes = buffer.readByteArray(len.toLong())
        return String(bytes, Charsets.UTF_8)
    }

    suspend fun readFully(target: ByteArray): Boolean {
        if (!ensureBytes(target.size.toLong())) return false
        buffer.readFully(target)
        return true
    }

    private suspend fun ensureBytes(requiredBytes: Long): Boolean {
        while (buffer.size < requiredBytes && !isEof) {
            val chunk = stream.recv()
            if (chunk == null) {
                isEof = true
                break
            }
            buffer.write(chunk)
        }
        return buffer.size >= requiredBytes
    }
}

private fun Buffer.writeJavaUtf(str: String) {
    val bytes = str.toByteArray(Charsets.UTF_8)
    writeShort(bytes.size)
    write(bytes)
}
