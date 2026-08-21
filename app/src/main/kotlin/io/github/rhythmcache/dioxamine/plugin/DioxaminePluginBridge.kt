package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbInteractiveSession
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
private data class DialogRequestPayload(
    val title: String = "",
    val message: String = "",
    val buttons: List<String> = emptyList(),
)

private data class TrackedForward(val client: AdbClient, val local: String)
private data class TrackedReverse(val client: AdbClient, val remote: String)

class DioxaminePluginBridge(
    private val context: Context,
    private val pluginId: String,
    private val pluginName: String,
    private val declaredPermissions: List<PluginPermission>,
    private val getActiveClient: () -> AdbClient?,
    private val permissionGate: PluginPermissionGate,
    private val dialogGate: PluginDialogGate,
    private val safBridge: PluginSafBridge,
    private val scope: CoroutineScope,
    private val evaluateJs: (String) -> Unit,
    private val onFullScreenChanged: (Boolean) -> Unit = {},
    private val onClosePlugin: () -> Unit = {},
) {

    private val logTimestamps = ArrayDeque<Long>()
    private val toastTimestamps = ArrayDeque<Long>()
    private val activeShellSessions = mutableMapOf<String, Pair<AdbInteractiveSession, Job>>()

    fun closeAllSessions() {
        val sessions =
            synchronized(activeShellSessions) {
                val list = activeShellSessions.values.toList()
                activeShellSessions.clear()
                list
            }
        sessions.forEach { (session, job) ->
            job.cancel()
            runCatching { session.close() }
        }
    }


    private fun resolve(
        callbackId: String,
        result: JsonElement,
    ) {
        val encodedId = Json.encodeToString(String.serializer(), callbackId)
        val encodedResult = Json.encodeToString(JsonElement.serializer(), result)
        evaluateJs("window.__dioxamine_resolve($encodedId, $encodedResult)")
    }

    private fun reject(
        callbackId: String,
        message: String,
    ) {
        val encodedId = Json.encodeToString(String.serializer(), callbackId)
        val encodedMessage = Json.encodeToString(String.serializer(), message)
        evaluateJs("window.__dioxamine_reject($encodedId, $encodedMessage)")
    }

    @JavascriptInterface
    fun getActiveDevice(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val client = getActiveClient()
                if (client == null) {
                    resolve(callbackId, JsonNull)
                } else {
                    resolve(callbackId, buildJsonObject { put("connected", true) })
                }
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun shellExec(
        cmd: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.SHELL,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: shell")
                    return@launch
                }

                val client = getActiveClient()
                if (client == null) {
                    reject(callbackId, "No active device")
                    return@launch
                }

                val result = client.shell(cmd)
                resolve(
                    callbackId,
                    buildJsonObject {
                        put("exitCode", result.exitCode)
                        put("stdout", result.stdoutText)
                        put("stderr", result.stderrText)
                    },
                )
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun openInteractiveShell(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.SHELL,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: shell")
                    return@launch
                }

                val client = getActiveClient()
                if (client == null) {
                    reject(callbackId, "No active device")
                    return@launch
                }

                val session = client.openInteractiveShell(terminalType = "xterm-256color")
                val sessionId = UUID.randomUUID().toString()

                val readJob =
                    scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        try {
                            session.outputFlow.collect { bytes ->
                                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                val encodedSessionId = Json.encodeToString(String.serializer(), sessionId)
                                val encodedB64 = Json.encodeToString(String.serializer(), b64)
                                evaluateJs("window.__dioxamine_shell_data($encodedSessionId, $encodedB64)")
                            }
                            // flow completed normally = EOF/remote closed
                            val encodedSessionId = Json.encodeToString(String.serializer(), sessionId)
                            evaluateJs("window.__dioxamine_shell_closed($encodedSessionId, null)")
                        } catch (e: CancellationException) {
                            throw e // don't treat our own closeInteractiveShell() cancellation as an error
                        } catch (e: Exception) {
                            val encodedSessionId = Json.encodeToString(String.serializer(), sessionId)
                            val encodedMsg = Json.encodeToString(String.serializer(), e.message ?: e.toString())
                            evaluateJs("window.__dioxamine_shell_closed($encodedSessionId, $encodedMsg)")
                        } finally {
                            synchronized(activeShellSessions) {
                                activeShellSessions.remove(sessionId)
                            }
                            runCatching { session.close() }
                        }
                    }

                synchronized(activeShellSessions) {
                    activeShellSessions[sessionId] = session to readJob
                }
                
                readJob.start()

                resolve(callbackId, buildJsonObject { put("sessionId", sessionId) })
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Failed to open shell")
            }
        }
    }

    @JavascriptInterface
    fun writeInteractiveShell(
        sessionId: String,
        base64Data: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val entry =
                    synchronized(activeShellSessions) {
                        activeShellSessions[sessionId]
                    } ?: run {
                        reject(callbackId, "Invalid or closed session")
                        return@launch
                    }

                val bytes =
                    try {
                        Base64.decode(base64Data, Base64.NO_WRAP)
                    } catch (e: IllegalArgumentException) {
                        reject(callbackId, "Invalid base64 data")
                        return@launch
                    }

                entry.first.write(bytes)
                resolve(callbackId, JsonNull)
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Write failed")
            }
        }
    }

    @JavascriptInterface
    fun resizeInteractiveShell(
        sessionId: String,
        cols: Int,
        rows: Int,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val entry =
                    synchronized(activeShellSessions) {
                        activeShellSessions[sessionId]
                    } ?: run {
                        reject(callbackId, "Invalid or closed session")
                        return@launch
                    }

                entry.first.resize(cols = cols, rows = rows)
                resolve(callbackId, JsonNull)
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Resize failed")
            }
        }
    }

    @JavascriptInterface
    fun closeInteractiveShell(
        sessionId: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val entry =
                    synchronized(activeShellSessions) {
                        activeShellSessions.remove(sessionId)
                    }
                if (entry == null) {
                    resolve(callbackId, JsonNull)
                    return@launch
                }

                entry.second.cancel()
                runCatching { entry.first.close() }
                resolve(callbackId, JsonNull)
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Close failed")
            }
        }
    }


    @JavascriptInterface
    fun requestFilePicker(
        mode: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val requestId =
                    when (mode.lowercase().trim()) {
                        "open" -> safBridge.requestOpenDocument()
                        "create" -> safBridge.requestCreateDocument()
                        else -> throw IllegalArgumentException("Invalid file picker mode: '$mode'")
                    }
                resolve(callbackId, buildJsonObject { put("requestId", requestId) })
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun pull(
        remotePath: String,
        safRequestId: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.PULL,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: pull")
                    return@launch
                }

                val client = getActiveClient()
                if (client == null) {
                    reject(callbackId, "No active device")
                    return@launch
                }

                val outputStream =
                    safBridge.resolveOutputStream(safRequestId)
                        ?: run {
                            reject(callbackId, "Invalid or expired safRequestId")
                            return@launch
                        }

                var bytesTransferred = 0L
                try {
                    client.sync.pull(
                        remotePath = remotePath,
                        output = outputStream,
                        onProgress = { bytesDone -> bytesTransferred = bytesDone },
                    )
                    resolve(callbackId, buildJsonObject { put("bytesTransferred", bytesTransferred) })
                } finally {
                    try {
                        outputStream.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun push(
        localSafRequestId: String,
        remotePath: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.PUSH,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: push")
                    return@launch
                }

                val client = getActiveClient()
                if (client == null) {
                    reject(callbackId, "No active device")
                    return@launch
                }

                val inputStream =
                    safBridge.resolveInputStream(localSafRequestId)
                        ?: run {
                            reject(callbackId, "Invalid or expired localSafRequestId")
                            return@launch
                        }

                var bytesTransferred = 0L
                try {
                    client.sync.push(
                        input = inputStream,
                        remotePath = remotePath,
                        onProgress = { bytesDone -> bytesTransferred = bytesDone },
                    )
                    resolve(callbackId, buildJsonObject { put("bytesTransferred", bytesTransferred) })
                } finally {
                    try {
                        inputStream.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    private val portMappingsMutex = Mutex()
    private val activeForwardMappings = mutableSetOf<TrackedForward>()
    private val activeReverseMappings = mutableSetOf<TrackedReverse>()

    suspend fun closeAllPortMappings() {
        val (forwards, reverses) =
            portMappingsMutex.withLock {
                val fList = activeForwardMappings.toList()
                val rList = activeReverseMappings.toList()
                activeForwardMappings.clear()
                activeReverseMappings.clear()
                fList to rList
            }

        forwards.forEach { entry ->
            runCatching { entry.client.forward.remove(entry.local) }
        }
        reverses.forEach { entry ->
            runCatching { entry.client.reverse.remove(entry.remote) }
        }
    }

    @JavascriptInterface
    fun forwardAdd(
        local: String,
        remote: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FORWARD,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: forward")
                    return@launch
                }

                val client = getActiveClient()
                if (client == null) {
                    reject(callbackId, "No active device")
                    return@launch
                }

                client.forward.add(local = local, remote = remote)
                portMappingsMutex.withLock {
                    activeForwardMappings.add(TrackedForward(client, local))
                }
                resolve(callbackId, buildJsonObject {})
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun reverseAdd(
        remote: String,
        local: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.REVERSE,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: reverse")
                    return@launch
                }

                val client = getActiveClient()
                if (client == null) {
                    reject(callbackId, "No active device")
                    return@launch
                }

                client.reverse.add(local = remote, remote = local)
                portMappingsMutex.withLock {
                    activeReverseMappings.add(TrackedReverse(client, remote))
                }
                resolve(callbackId, buildJsonObject {})
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun forwardRemove(
        local: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FORWARD,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: forward")
                    return@launch
                }

                val client = getActiveClient()
                if (client == null) {
                    reject(callbackId, "No active device")
                    return@launch
                }

                client.forward.remove(local)
                portMappingsMutex.withLock {
                    activeForwardMappings.removeAll { it.client == client && it.local == local }
                }
                resolve(callbackId, buildJsonObject {})
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun reverseRemove(
        remote: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.REVERSE,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: reverse")
                    return@launch
                }

                val client = getActiveClient()
                if (client == null) {
                    reject(callbackId, "No active device")
                    return@launch
                }

                client.reverse.remove(remote)
                portMappingsMutex.withLock {
                    activeReverseMappings.removeAll { it.client == client && it.remote == remote }
                }
                resolve(callbackId, buildJsonObject {})
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun showToast(
        message: String,
        duration: String,
    ) {
        val truncated = if (message.length > 200) message.take(200) + "..." else message
        val now = System.currentTimeMillis()
        val shouldShow =
            synchronized(toastTimestamps) {
                while (toastTimestamps.isNotEmpty() && now - toastTimestamps.first() > 2000L) {
                    toastTimestamps.removeFirst()
                }
                if (toastTimestamps.size < 3) {
                    toastTimestamps.addLast(now)
                    true
                } else {
                    false
                }
            }
        if (!shouldShow) return

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            val len = if (duration == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, truncated, len).show()
        }
    }

    @JavascriptInterface
    fun showDialog(
        requestJson: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val payload =
                    try {
                        Json.decodeFromString(DialogRequestPayload.serializer(), requestJson)
                    } catch (e: Exception) {
                        reject(callbackId, "Invalid dialog request")
                        return@launch
                    }

                if (payload.title.length > 100) {
                    reject(callbackId, "Title exceeds 100 characters limit")
                    return@launch
                }

                if (payload.message.length > 1000) {
                    reject(callbackId, "Message exceeds 1000 characters limit")
                    return@launch
                }

                val effectiveButtons = if (payload.buttons.isEmpty()) listOf("OK") else payload.buttons

                if (effectiveButtons.size !in 1..3) {
                    reject(callbackId, "Buttons count must be between 1 and 3")
                    return@launch
                }

                if (effectiveButtons.any { it.isBlank() || it.length > 30 }) {
                    reject(callbackId, "Button text must be non-blank and max 30 characters")
                    return@launch
                }

                val buttonIndex =
                    dialogGate.showDialog(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        title = payload.title,
                        message = payload.message,
                        buttons = effectiveButtons,
                    )

                resolve(callbackId, buildJsonObject { put("buttonIndex", buttonIndex) })
            } catch (e: Exception) {
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun logMessage(level: String, tag: String, message: String) {
        val now = System.currentTimeMillis()
        val shouldLog =
            synchronized(logTimestamps) {
                while (logTimestamps.isNotEmpty() && now - logTimestamps.first() > 2000L) {
                    logTimestamps.removeFirst()
                }
                if (logTimestamps.size < 50) {
                    logTimestamps.addLast(now)
                    true
                } else {
                    false
                }
            }
        if (!shouldLog) return

        val truncated = if (message.length > 2000) message.take(2000) else message
        val pluginTag = "Plugin:$pluginId/$tag"

        when (level.uppercase()) {
            "V" -> AppLogger.v(pluginTag, truncated)
            "D" -> AppLogger.d(pluginTag, truncated)
            "I" -> AppLogger.i(pluginTag, truncated)
            "W" -> AppLogger.w(pluginTag, truncated)
            "E" -> AppLogger.e(pluginTag, truncated)
            else -> AppLogger.d(pluginTag, truncated)
        }
    }

    @JavascriptInterface
    fun setFullScreen(enable: Boolean) {
        scope.launch(Dispatchers.Main) {
            onFullScreenChanged(enable)
        }
    }

    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        setFullScreen(enable)
    }

    @JavascriptInterface
    fun exitPlugin() {
        scope.launch(Dispatchers.Main) {
            onClosePlugin()
        }
    }

    @JavascriptInterface
    fun closePlugin() {
        exitPlugin()
    }
}
