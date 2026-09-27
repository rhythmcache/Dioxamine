package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import io.github.rhythmcache.dioxamine.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private data class TempFileHandle(
    val file: File,
    val mutex: Mutex = Mutex(),
)

class TempFileBridge(
    private val baseDir: File,
    private val pluginId: String,
    private val pluginName: String,
    private val dialogGate: PluginDialogGate,
    private val scope: CoroutineScope,
    private val onResolve: (callbackId: String, result: JsonElement) -> Unit,
    private val onReject: (callbackId: String, error: String) -> Unit,
    private val dialogTitle: String? = null,
    private val dialogMessage: String? = null,
    private val btnCancelText: String = "Cancel",
    private val btnAllowText: String = "Allow",
) {
    constructor(
        context: Context,
        pluginId: String,
        pluginName: String,
        dialogGate: PluginDialogGate,
        scope: CoroutineScope,
        onResolve: (callbackId: String, result: JsonElement) -> Unit,
        onReject: (callbackId: String, error: String) -> Unit,
    ) : this(
        baseDir = File(context.cacheDir, "plugin_tmp"),
        pluginId = pluginId,
        pluginName = pluginName,
        dialogGate = dialogGate,
        scope = scope,
        onResolve = onResolve,
        onReject = onReject,
        dialogTitle = runCatching { context.getString(R.string.plugin_temp_file_title) }.getOrNull(),
        dialogMessage = runCatching { context.getString(R.string.plugin_temp_file_msg) }.getOrNull(),
        btnCancelText = runCatching { context.getString(R.string.btn_cancel) }.getOrDefault("Cancel"),
        btnAllowText = runCatching { context.getString(R.string.btn_allow) }.getOrDefault("Allow"),
    )

    companion object {
        const val MAX_READ_CHUNK_SIZE = 16 * 1024 * 1024 // 16MB per read chunk to prevent memory exhaustion

        private val isAndroidRuntime by lazy {
            System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
        }
    }

    private val tokenMap = ConcurrentHashMap<String, TempFileHandle>()
    private val secureRandom = SecureRandom()

    init {
        cleanupStaleFiles()
    }

    private fun generateDiskFilename(): String {
        val randomBytes = ByteArray(16)
        secureRandom.nextBytes(randomBytes)
        val hex = randomBytes.joinToString("") { "%02x".format(it) }
        return "tmp_$hex.bin"
    }

    private fun decodeBase64(base64Str: String): ByteArray {
        return if (isAndroidRuntime) {
            Base64.decode(base64Str, Base64.DEFAULT)
        } else {
            java.util.Base64.getDecoder().decode(base64Str)
        }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return if (isAndroidRuntime) {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } else {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    @JavascriptInterface
    fun requestTempFile(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val title = dialogTitle ?: "Temporary Storage Request"
                val message = dialogMessage ?: "$pluginName requests permission to create a temporary file."
                val buttons = listOf(btnCancelText, btnAllowText)

                val buttonIndex = dialogGate.showDialog(
                    pluginId = pluginId,
                    pluginName = pluginName,
                    title = title,
                    message = message,
                    buttons = buttons,
                )

                if (buttonIndex != 1) {
                    onReject(callbackId, "User denied permission to create temporary file")
                    return@launch
                }

                if (!baseDir.exists()) {
                    baseDir.mkdirs()
                }

                val token = UUID.randomUUID().toString()
                val targetFile = File(baseDir, generateDiskFilename())
                tokenMap[token] = TempFileHandle(file = targetFile)
                // Lazy file creation: file is not created on disk until first write

                onResolve(callbackId, buildJsonObject { put("token", token) })
            } catch (e: Exception) {
                onReject(callbackId, "Failed to request temporary file")
            }
        }
    }

    @JavascriptInterface
    fun writeTempFileChunk(token: String, base64Chunk: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val handle = tokenMap[token]
                if (handle == null) {
                    onReject(callbackId, "Unknown or invalid temporary file token")
                    return@launch
                }

                val bytes = try {
                    decodeBase64(base64Chunk)
                } catch (e: Exception) {
                    onReject(callbackId, "Invalid base64 payload")
                    return@launch
                }

                handle.mutex.withLock {
                    if (!handle.file.exists()) {
                        handle.file.parentFile?.mkdirs()
                        handle.file.createNewFile()
                    }

                    FileOutputStream(handle.file, true).use { fos ->
                        fos.write(bytes)
                        fos.flush()
                    }
                }

                onResolve(callbackId, buildJsonObject {
                    put("bytesWritten", bytes.size)
                })
            } catch (e: Exception) {
                onReject(callbackId, "Failed to write temporary file chunk")
            }
        }
    }

    @JavascriptInterface
    fun readTempFileChunk(token: String, offset: Long, length: Int, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val handle = tokenMap[token]
                if (handle == null) {
                    onReject(callbackId, "Unknown or invalid temporary file token")
                    return@launch
                }

                if (offset < 0) {
                    onReject(callbackId, "Offset cannot be negative")
                    return@launch
                }
                if (length < 0) {
                    onReject(callbackId, "Length cannot be negative")
                    return@launch
                }

                val base64Data = handle.mutex.withLock {
                    if (!handle.file.exists() || handle.file.length() == 0L) {
                        // Empty string if file was never written, not an error
                        return@withLock ""
                    }

                    val fileLen = handle.file.length()
                    if (offset >= fileLen || length == 0) {
                        return@withLock ""
                    }

                    val available = (fileLen - offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val toRead = minOf(length, available, MAX_READ_CHUNK_SIZE)

                    val buffer = ByteArray(toRead)
                    RandomAccessFile(handle.file, "r").use { raf ->
                        raf.seek(offset)
                        var totalRead = 0
                        while (totalRead < toRead) {
                            val read = raf.read(buffer, totalRead, toRead - totalRead)
                            if (read < 0) break
                            totalRead += read
                        }
                        val actualBytes = if (totalRead == toRead) buffer else buffer.copyOf(totalRead)
                        encodeBase64(actualBytes)
                    }
                }

                onResolve(callbackId, JsonPrimitive(base64Data))
            } catch (e: Exception) {
                onReject(callbackId, "Failed to read temporary file chunk")
            }
        }
    }

    @JavascriptInterface
    fun getTempFileSize(token: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val handle = tokenMap[token]
                if (handle == null) {
                    onReject(callbackId, "Unknown or invalid temporary file token")
                    return@launch
                }

                val sizeStr = handle.mutex.withLock {
                    if (handle.file.exists()) handle.file.length().toString() else "0"
                }
                onResolve(callbackId, JsonPrimitive(sizeStr))
            } catch (e: Exception) {
                onReject(callbackId, "Failed to get temporary file size")
            }
        }
    }

    @JavascriptInterface
    fun deleteTempFile(token: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val handle = tokenMap.remove(token)
                if (handle == null) {
                    onReject(callbackId, "Unknown or invalid temporary file token")
                    return@launch
                }

                handle.mutex.withLock {
                    if (handle.file.exists()) {
                        handle.file.delete()
                    }
                }
                onResolve(callbackId, buildJsonObject { put("success", true) })
            } catch (e: Exception) {
                onReject(callbackId, "Failed to delete temporary file")
            }
        }
    }

    fun deleteAllForSession() {
        try {
            val handles = tokenMap.values.toList()
            tokenMap.clear()
            handles.forEach { handle ->
                runCatching {
                    if (handle.file.exists()) {
                        handle.file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun cleanupStaleFiles() {
        try {
            if (baseDir.exists()) {
                baseDir.listFiles()?.forEach { file ->
                    runCatching {
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Testing helper to inspect active token count in memory.
     */
    internal fun activeTokenCount(): Int = tokenMap.size

    /**
     * Testing helper to check if a token is registered.
     */
    internal fun hasToken(token: String): Boolean = tokenMap.containsKey(token)

    /**
     * Testing helper to inspect target file state for a token without exposing it to JS.
     */
    internal fun getFileForToken(token: String): File? = tokenMap[token]?.file
}
