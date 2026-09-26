package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import io.github.rhythmcache.dioxamine.BuildConfig
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbInteractiveSession
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.fastboot.FastbootDevice
import io.github.rhythmcache.fastboot.FastbootClient
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.UUID

@Serializable
private data class DialogRequestPayload(
    val title: String = "",
    val message: String = "",
    val buttons: List<String> = emptyList(),
)

private data class TrackedForward(val client: AdbClient, val local: String)
private data class TrackedReverse(val client: AdbClient, val remote: String)
private const val MAX_STAGE_DATA_BASE64_LENGTH = 24 * 1024 * 1024 // ~16MB decoded payload limit

class DioxaminePluginBridge(
    private val context: Context,
    private val pluginId: String,
    private val pluginName: String,
    private val declaredPermissions: List<PluginPermission>,
    private val getActiveClient: () -> AdbClient?,
    private val getActiveFastbootClient: () -> FastbootClient? = { null },
    private val getActiveFastbootDevice: () -> FastbootDevice? = { null },
    private val permissionGate: PluginPermissionGate,
    private val dialogGate: PluginDialogGate,
    private val safBridge: PluginSafBridge,
    private val scope: CoroutineScope,
    private val evaluateJs: (String) -> Unit,
    private val onFullScreenChanged: (Boolean) -> Unit = {},
    private val onClosePlugin: () -> Unit = {},
    private val onDefaultBack: () -> Unit = {},
    initialInterceptBackButton: Boolean = false,
    initialInterceptVolumeButtons: Boolean = false,
    private val onInterceptBackButtonChanged: (Boolean) -> Unit = {},
    private val onInterceptVolumeButtonsChanged: (Boolean) -> Unit = {},
) {

    private var interceptBackButton: Boolean = initialInterceptBackButton
    private var interceptVolumeButtons: Boolean = initialInterceptVolumeButtons

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

    @JavascriptInterface
    fun setInterceptBackButton(enable: Boolean) {
        interceptBackButton = enable
        scope.launch(Dispatchers.Main) {
            onInterceptBackButtonChanged(enable)
        }
    }

    @JavascriptInterface
    fun isInterceptingBackButton(): Boolean = interceptBackButton

    @JavascriptInterface
    fun setInterceptVolumeButtons(enable: Boolean) {
        interceptVolumeButtons = enable
        scope.launch(Dispatchers.Main) {
            onInterceptVolumeButtonsChanged(enable)
        }
    }

    @JavascriptInterface
    fun isInterceptingVolumeButtons(): Boolean = interceptVolumeButtons

    @JavascriptInterface
    fun defaultBack() {
        scope.launch(Dispatchers.Main) {
            onDefaultBack()
        }
    }

    fun dispatchBackButton() {
        evaluateJs("window.__dioxamine_on_back_button && window.__dioxamine_on_back_button();")
    }

    fun dispatchVolumeButton(
        button: String,
        action: String,
        keyCode: Int,
        repeatCount: Int,
    ) {
        val escapedButton = JSONObject.quote(button)
        val escapedAction = JSONObject.quote(action)
        evaluateJs("window.__dioxamine_on_volume_button && window.__dioxamine_on_volume_button($escapedButton, $escapedAction, $keyCode, $repeatCount);")
    }

    @JavascriptInterface
    fun getLocaleInfo(): String {
        val info = getPluginLocaleInfo(context)
        return Json.encodeToString(info)
    }

    @JavascriptInterface
    fun getLanguage(): String {
        return getLocaleInfo()
    }

    @JavascriptInterface
    fun getLocale(): String {
        return getLocaleInfo()
    }

    @JavascriptInterface
    fun getLocaleInfoAsync(callbackId: String) {
        try {
            val info = getPluginLocaleInfo(context)
            resolve(
                callbackId,
                buildJsonObject {
                    put("language", info.language)
                    put("languageTag", info.languageTag)
                    put("isRtl", info.isRtl)
                    put("displayName", info.displayName)
                },
            )
        } catch (e: Exception) {
            reject(callbackId, e.message ?: e.toString())
        }
    }

    @JavascriptInterface
    fun getLanguageAsync(callbackId: String) {
        getLocaleInfoAsync(callbackId)
    }

    @JavascriptInterface
    fun getLocaleAsync(callbackId: String) {
        getLocaleInfoAsync(callbackId)
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return buildJsonObject {
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("version", BuildConfig.VERSION_NAME)
            put("appName", BuildConfig.APP_NAME)
            put("applicationId", BuildConfig.APPLICATION_ID)
        }.toString()
    }

    @JavascriptInterface
    fun getVersion(): String = getAppVersion()

    @JavascriptInterface
    fun getAppVersionAsync(callbackId: String) {
        try {
            resolve(
                callbackId,
                buildJsonObject {
                    put("versionName", BuildConfig.VERSION_NAME)
                    put("versionCode", BuildConfig.VERSION_CODE)
                    put("version", BuildConfig.VERSION_NAME)
                    put("appName", BuildConfig.APP_NAME)
                    put("applicationId", BuildConfig.APPLICATION_ID)
                },
            )
        } catch (e: Exception) {
            reject(callbackId, e.message ?: e.toString())
        }
    }

    @JavascriptInterface
    fun getVersionAsync(callbackId: String) {
        getAppVersionAsync(callbackId)
    }

    @JavascriptInterface
    fun openBrowser(
        url: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.Main) {
            try {
                val trimmed = url.trim()
                if (trimmed.isEmpty()) {
                    reject(callbackId, "URL cannot be empty")
                    return@launch
                }
                if (trimmed.length > 2048) {
                    reject(callbackId, "URL exceeds maximum length of 2048 characters")
                    return@launch
                }
                val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    reject(callbackId, "Unsupported URL scheme: Only http:// and https:// URLs are allowed")
                    return@launch
                }
                if (uri.host.isNullOrBlank()) {
                    reject(callbackId, "Invalid URL: Missing host")
                    return@launch
                }

                val buttonIndex =
                    dialogGate.showDialog(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        title = context.getString(R.string.plugin_open_browser_title),
                        message = context.getString(R.string.plugin_open_browser_msg, trimmed),
                        buttons = listOf(
                            context.getString(R.string.btn_cancel),
                            context.getString(R.string.plugin_open_browser_btn_open),
                        ),
                    )

                if (buttonIndex != 1) {
                    reject(callbackId, "User cancelled opening external link")
                    return@launch
                }

                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                resolve(callbackId, buildJsonObject { put("success", true) })
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Failed to open browser")
            }
        }
    }

    @JavascriptInterface
    fun openUrl(
        url: String,
        callbackId: String,
    ) {
        openBrowser(url, callbackId)
    }

    @JavascriptInterface
    fun httpRequest(requestJson: String, callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted = permissionGate.checkPermission(
                    pluginId = pluginId,
                    pluginName = pluginName,
                    declaredPermissions = declaredPermissions,
                    required = PluginPermission.NETWORK,
                )
                if (!granted) {
                    reject(callbackId, "Permission denied: network")
                    return@launch
                }

                val req = JSONObject(requestJson)
                val urlString = req.getString("url").trim()
                val method = req.optString("method", "GET").uppercase().trim()
                val body = if (req.isNull("body")) null else req.optString("body")
                val timeoutMs = req.optInt("timeoutMs", 15000).coerceIn(1000, 60000)
                val headersObj = req.optJSONObject("headers")

                val parsedUrl = runCatching { URL(urlString) }.getOrElse {
                    reject(callbackId, "Malformed URL: $urlString")
                    return@launch
                }

                val protocol = parsedUrl.protocol.lowercase()
                if (protocol != "http" && protocol != "https") {
                    reject(callbackId, "Unsupported URL scheme '$protocol'. Only http and https are allowed.")
                    return@launch
                }

                val rawHost = parsedUrl.host.lowercase()
                if (rawHost.isBlank()) {
                    reject(callbackId, "URL host cannot be empty")
                    return@launch
                }

                // SSRF Protection: Block cloud metadata service endpoints (AWS/GCP/Azure link-local 169.254.169.254)
                if (rawHost == "169.254.169.254" || rawHost == "metadata.google.internal" || rawHost.endsWith(".metadata.google.internal")) {
                    reject(callbackId, "Access to cloud metadata endpoints is blocked")
                    return@launch
                }

                // Verify resolved IP address is not pointing to cloud metadata service
                val resolvedIp = runCatching { InetAddress.getByName(rawHost) }.getOrNull()
                if (resolvedIp?.hostAddress == "169.254.169.254") {
                    reject(callbackId, "Access to cloud metadata endpoints is blocked")
                    return@launch
                }

                val connection = (parsedUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            setRequestProperty(key, headersObj.getString(key))
                        }
                    }
                }

                if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                    connection.doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                }

                val statusCode = connection.responseCode
                val statusMessage = runCatching { connection.responseMessage }.getOrNull() ?: ""

                val responseText = runCatching {
                    val stream = if (statusCode >= 400) {
                        connection.errorStream ?: connection.inputStream
                    } else {
                        connection.inputStream
                    }
                    stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                }.getOrDefault("")

                val responseHeaders = buildJsonObject {
                    connection.headerFields.forEach { (k, v) ->
                        if (k != null && v.isNotEmpty()) {
                            put(k, v.joinToString(", "))
                        }
                    }
                }

                connection.disconnect()

                resolve(
                    callbackId,
                    buildJsonObject {
                        put("status", statusCode)
                        put("statusText", statusMessage)
                        put("data", responseText)
                        put("headers", responseHeaders)
                    }
                )
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "httpRequest failed for $pluginId", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    // -----------------------------------------------------------------
    // Fastboot APIs
    // -----------------------------------------------------------------

    @JavascriptInterface
    fun fastbootGetActiveDevice(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                val device = getActiveFastbootDevice()
                if (client == null || device == null) {
                    resolve(callbackId, buildJsonObject {
                        put("connected", false)
                    })
                } else {
                    resolve(
                        callbackId,
                        buildJsonObject {
                            put("connected", true)
                            put("id", device.id)
                            put("label", device.label)
                            put("deviceName", device.deviceName)
                        },
                    )
                }
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootGetActiveDevice failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun fastbootRawCommand(
        command: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                if (client == null) {
                    reject(callbackId, "No active fastboot device")
                    return@launch
                }

                val infoList = mutableListOf<String>()
                val result = client.rawCommand(command, onInfo = { info ->
                    infoList.add(info)
                })

                resolve(
                    callbackId,
                    buildJsonObject {
                        put("response", result.response)
                        put(
                            "info",
                            buildJsonArray {
                                infoList.forEach { add(it) }
                            },
                        )
                    },
                )
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootRawCommand failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun fastbootGetVariable(
        name: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                if (client == null) {
                    reject(callbackId, "No active fastboot device")
                    return@launch
                }

                val value = client.getVar(name)
                resolve(callbackId, buildJsonObject { put("value", value) })
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootGetVariable failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun fastbootGetAllVariables(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                if (client == null) {
                    reject(callbackId, "No active fastboot device")
                    return@launch
                }

                val vars = client.getAllVars()
                resolve(
                    callbackId,
                    buildJsonObject {
                        vars.forEach { (k, v) ->
                            put(k, v)
                        }
                    },
                )
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootGetAllVariables failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun fastbootFlash(
        partition: String,
        safRequestId: String,
        opId: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                if (client == null) {
                    reject(callbackId, "No active fastboot device")
                    return@launch
                }

                val (pfd, size) = safBridge.resolveFileDescriptorAndSize(safRequestId)
                    ?: run {
                        reject(callbackId, "Invalid or expired safRequestId")
                        return@launch
                    }

                try {
                    if (size <= 0L) {
                        reject(callbackId, "Cannot flash empty file (0 bytes)")
                        return@launch
                    }
                    val infoList = mutableListOf<String>()
                    var lastInfo = ""
                    val encodedOpId = Json.encodeToString(String.serializer(), opId)
                    client.flash(
                        partition = partition,
                        fd = pfd.fileDescriptor,
                        size = size,
                        onInfo = { info ->
                            infoList.add(info)
                            lastInfo = info
                            AppLogger.d("PluginBridge", "fastboot flash [$partition]: $info")
                            val encodedInfo = Json.encodeToString(String.serializer(), info)
                            evaluateJs("window.__dioxamine_on_fastboot_info($encodedOpId, $encodedInfo)")
                        },
                        onProgress = { progress ->
                            val percentage = if (progress.total > 0) (progress.current.toDouble() / progress.total * 100).toInt() else 0
                            val encodedInfo = Json.encodeToString(String.serializer(), lastInfo)
                            evaluateJs("window.__dioxamine_on_fastboot_progress($encodedOpId, ${progress.current}, ${progress.total}, $percentage, $encodedInfo)")
                        },
                    )
                    resolve(
                        callbackId,
                        buildJsonObject {
                            put("success", true)
                            put("bytesTransferred", size)
                            put(
                                "info",
                                buildJsonArray {
                                    infoList.forEach { add(it) }
                                },
                            )
                        },
                    )
                } finally {
                    runCatching { pfd.close() }
                }
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootFlash failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun fastbootBoot(
        safRequestId: String,
        opId: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                if (client == null) {
                    reject(callbackId, "No active fastboot device")
                    return@launch
                }

                val (pfd, size) = safBridge.resolveFileDescriptorAndSize(safRequestId)
                    ?: run {
                        reject(callbackId, "Invalid or expired safRequestId")
                        return@launch
                    }

                try {
                    if (size <= 0L) {
                        reject(callbackId, "Cannot boot empty file (0 bytes)")
                        return@launch
                    }
                    val infoList = mutableListOf<String>()
                    var lastInfo = ""
                    val encodedOpId = Json.encodeToString(String.serializer(), opId)
                    client.boot(
                        fd = pfd.fileDescriptor,
                        size = size,
                        onInfo = { info ->
                            infoList.add(info)
                            lastInfo = info
                            AppLogger.d("PluginBridge", "fastboot boot: $info")
                            val encodedInfo = Json.encodeToString(String.serializer(), info)
                            evaluateJs("window.__dioxamine_on_fastboot_info($encodedOpId, $encodedInfo)")
                        },
                        onProgress = { progress ->
                            val percentage = if (progress.total > 0) (progress.current.toDouble() / progress.total * 100).toInt() else 0
                            val encodedInfo = Json.encodeToString(String.serializer(), lastInfo)
                            evaluateJs("window.__dioxamine_on_fastboot_progress($encodedOpId, ${progress.current}, ${progress.total}, $percentage, $encodedInfo)")
                        },
                    )
                    resolve(
                        callbackId,
                        buildJsonObject {
                            put("success", true)
                            put("bytesTransferred", size)
                            put(
                                "info",
                                buildJsonArray {
                                    infoList.forEach { add(it) }
                                },
                            )
                        },
                    )
                } finally {
                    runCatching { pfd.close() }
                }
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootBoot failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun fastbootStage(
        safRequestId: String,
        opId: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                if (client == null) {
                    reject(callbackId, "No active fastboot device")
                    return@launch
                }

                val (pfd, size) = safBridge.resolveFileDescriptorAndSize(safRequestId)
                    ?: run {
                        reject(callbackId, "Invalid or expired safRequestId")
                        return@launch
                    }

                try {
                    if (size <= 0L) {
                        reject(callbackId, "Cannot stage empty file (0 bytes)")
                        return@launch
                    }
                    val encodedOpId = Json.encodeToString(String.serializer(), opId)
                    val encodedEmpty = Json.encodeToString(String.serializer(), "")
                    val result = client.stage(
                        fd = pfd.fileDescriptor,
                        size = size,
                        onProgress = { progress ->
                            val percentage = if (progress.total > 0) (progress.current.toDouble() / progress.total * 100).toInt() else 0
                            evaluateJs("window.__dioxamine_on_fastboot_progress($encodedOpId, ${progress.current}, ${progress.total}, $percentage, $encodedEmpty)")
                        },
                    )
                    resolve(
                        callbackId,
                        buildJsonObject {
                            put("success", true)
                            put("bytesTransferred", size)
                            put("response", result.response)
                            put(
                                "info",
                                buildJsonArray {
                                    result.info.forEach { add(it) }
                                },
                            )
                        },
                    )
                } finally {
                    runCatching { pfd.close() }
                }
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootStage failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun fastbootStageData(
        dataBase64: String,
        opId: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                if (client == null) {
                    reject(callbackId, "No active fastboot device")
                    return@launch
                }

                if (dataBase64.length > MAX_STAGE_DATA_BASE64_LENGTH) {
                    reject(
                        callbackId,
                        "Base64 payload exceeds maximum in-memory stage limit (16MB). Use fastboot.stage() with SAF for large images.",
                    )
                    return@launch
                }

                val bytes = try {
                    Base64.decode(dataBase64, Base64.DEFAULT)
                } catch (e: Exception) {
                    reject(callbackId, "Invalid base64 payload: ${e.message}")
                    return@launch
                }

                if (bytes.isEmpty()) {
                    reject(callbackId, "Cannot stage empty data (0 bytes)")
                    return@launch
                }

                val encodedOpId = Json.encodeToString(String.serializer(), opId)
                val encodedEmpty = Json.encodeToString(String.serializer(), "")
                val result = client.stage(
                    image = bytes,
                    onProgress = { progress ->
                        val percentage = if (progress.total > 0) (progress.current.toDouble() / progress.total * 100).toInt() else 0
                        evaluateJs("window.__dioxamine_on_fastboot_progress($encodedOpId, ${progress.current}, ${progress.total}, $percentage, $encodedEmpty)")
                    },
                )
                resolve(
                    callbackId,
                    buildJsonObject {
                        put("success", true)
                        put("bytesTransferred", bytes.size.toLong())
                        put("response", result.response)
                        put(
                            "info",
                            buildJsonArray {
                                result.info.forEach { add(it) }
                            },
                        )
                    },
                )
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootStageData failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }

    @JavascriptInterface
    fun fastbootReboot(
        target: String,
        callbackId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val granted =
                    permissionGate.checkPermission(
                        pluginId = pluginId,
                        pluginName = pluginName,
                        declaredPermissions = declaredPermissions,
                        required = PluginPermission.FASTBOOT,
                    )
                if (!granted) {
                    reject(callbackId, "Permission denied: fastboot")
                    return@launch
                }

                val client = getActiveFastbootClient()
                if (client == null) {
                    reject(callbackId, "No active fastboot device")
                    return@launch
                }

                val normalized = target.lowercase().trim()
                val rebootTarget = when (normalized) {
                    "system", "" -> FastbootClient.RebootTarget.SYSTEM
                    "bootloader" -> FastbootClient.RebootTarget.BOOTLOADER
                    "recovery" -> FastbootClient.RebootTarget.RECOVERY
                    "fastboot" -> FastbootClient.RebootTarget.FASTBOOT
                    else -> null
                }

                if (rebootTarget != null) {
                    client.reboot(rebootTarget, onInfo = { info -> AppLogger.d("PluginBridge", "fastboot reboot: $info") })
                } else {
                    client.reboot(normalized, onInfo = { info -> AppLogger.d("PluginBridge", "fastboot reboot: $info") })
                }

                resolve(callbackId, buildJsonObject { put("success", true) })
            } catch (e: Exception) {
                AppLogger.e("PluginBridge", "fastbootReboot failed", e)
                reject(callbackId, e.message ?: e.toString())
            }
        }
    }
}
