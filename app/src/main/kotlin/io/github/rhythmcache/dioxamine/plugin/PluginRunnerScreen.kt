package io.github.rhythmcache.dioxamine.plugin

import android.annotation.SuppressLint
import android.net.Uri
import android.view.KeyEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import io.github.rhythmcache.dioxamine.MainActivity
import io.github.rhythmcache.dioxamine.core.AppLogger
import android.widget.Toast
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.core.LocalDarkTheme
import io.github.rhythmcache.dioxamine.fastboot.FastbootViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginRunnerScreen(
    pluginId: String,
    vm: AdbViewModel,
    fastbootVm: FastbootViewModel? = null,
    repo: PluginRepository,
    permissionGate: PluginPermissionGate,
    dialogGate: PluginDialogGate,
    safBridge: PluginSafBridge,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val installedPlugins by repo.installedPlugins.collectAsState()
    val manifest = remember(installedPlugins, pluginId) { installedPlugins.find { it.id == pluginId } }

    val colorScheme = MaterialTheme.colorScheme
    val isDark = LocalDarkTheme.current
    val locales = ConfigurationCompat.getLocales(configuration)
    val localeInfo = remember(locales) { getPluginLocaleInfo(context) }

    if (manifest == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.plugin_runner_not_found),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text(text = stringResource(R.string.cd_nav_back))
            }
        }
        return
    }

    val bridgeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(colorScheme, isDark, webViewRef) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript(buildThemeInjectionScript(colorScheme, isDark), null)
        }
    }

    LaunchedEffect(localeInfo, webViewRef) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript(buildLocaleInjectionScript(localeInfo), null)
        }
    }

    var isFullScreen by remember(manifest.id) { mutableStateOf(manifest.fullscreen) }
    var interceptBackButton by remember(manifest.id) { mutableStateOf(manifest.interceptBackButton) }
    var interceptVolumeButtons by remember(manifest.id) { mutableStateOf(manifest.interceptVolumeButtons) }

    val declaredPermissions = remember(manifest.permissions) {
        manifest.permissions.allList().mapNotNull { PluginPermission.fromManifestString(it) }
    }

    val bridge =
        remember(manifest.id, bridgeScope) {
            DioxaminePluginBridge(
                context = context.applicationContext,
                pluginId = manifest.id,
                pluginName = manifest.name,
                declaredPermissions = declaredPermissions,
                getActiveClient = { vm.activeClient() },
                getActiveFastbootClient = { fastbootVm?.activeClient() },
                getActiveFastbootDevice = {
                    fastbootVm?.connectedDeviceId?.let { id -> fastbootVm.devices[id] }
                },
                permissionGate = permissionGate,
                dialogGate = dialogGate,
                safBridge = safBridge,
                scope = bridgeScope,
                evaluateJs = { script ->
                    webViewRef?.post {
                        webViewRef?.evaluateJavascript(script, null)
                    }
                },
                onFullScreenChanged = { enable ->
                    isFullScreen = enable
                },
                onClosePlugin = onBack,
                onDefaultBack = {
                    if (webViewRef?.canGoBack() == true) {
                        webViewRef?.goBack()
                    } else {
                        onBack()
                    }
                },
                initialInterceptBackButton = manifest.interceptBackButton,
                initialInterceptVolumeButtons = manifest.interceptVolumeButtons,
                onInterceptBackButtonChanged = { enable ->
                    interceptBackButton = enable
                },
                onInterceptVolumeButtonsChanged = { enable ->
                    interceptVolumeButtons = enable
                },
            )
        }

    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var rapidBackPressCount by remember { mutableIntStateOf(0) }

    BackHandler {
        if (interceptBackButton) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000L) {
                rapidBackPressCount++
            } else {
                rapidBackPressCount = 1
            }
            lastBackPressTime = now

            if (rapidBackPressCount >= 3) {
                Toast.makeText(context, R.string.plugin_force_exit_done, Toast.LENGTH_SHORT).show()
                onBack()
            } else {
                if (rapidBackPressCount == 2) {
                    Toast.makeText(context, R.string.plugin_force_exit_prompt, Toast.LENGTH_SHORT).show()
                }
                bridge.dispatchBackButton()
            }
        } else if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else {
            onBack()
        }
    }

    DisposableEffect(interceptVolumeButtons, bridge) {
        if (interceptVolumeButtons) {
            MainActivity.setKeyEventInterceptor(bridge) { event ->
                val keyCode = event.keyCode
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                ) {
                    val button = when (keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> "volume_up"
                        KeyEvent.KEYCODE_VOLUME_DOWN -> "volume_down"
                        KeyEvent.KEYCODE_VOLUME_MUTE -> "volume_mute"
                        else -> "unknown"
                    }
                    val action = when (event.action) {
                        KeyEvent.ACTION_DOWN -> "down"
                        KeyEvent.ACTION_UP -> "up"
                        else -> "unknown"
                    }
                    bridge.dispatchVolumeButton(
                        button = button,
                        action = action,
                        keyCode = keyCode,
                        repeatCount = event.repeatCount,
                    )
                    true
                } else {
                    false
                }
            }
        } else {
            MainActivity.clearKeyEventInterceptor(bridge)
        }

        onDispose {
            MainActivity.clearKeyEventInterceptor(bridge)
        }
    }

    DisposableEffect(bridge) {
        onDispose {
            val cleanupJob = bridgeScope.launch(Dispatchers.IO) {
                bridge.closeAllPortMappings()
            }
            bridge.closeAllSessions()
            cleanupJob.invokeOnCompletion {
                bridgeScope.cancel()
            }
        }
    }

    val bridgeJsContent =
        remember {
            runCatching {
                context.assets.open("plugin_runtime/dioxamine-bridge.js").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }

    val pluginDir = remember(pluginId) { repo.pluginDir(pluginId) }
    val assetLoader =
        remember(context, pluginDir, pluginId) {
            WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler(
                    "/assets/",
                    WebViewAssetLoader.AssetsPathHandler(context),
                )
                .addPathHandler(
                    "/plugin/",
                    PluginStoragePathHandler(pluginDir),
                )
                .build()
        }

    val entryUrl = "https://appassets.androidplatform.net/plugin/${manifest.entry}"

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = manifest.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "v${manifest.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_nav_back),
                            )
                        }
                    },
                    windowInsets = TopAppBarDefaults.windowInsets,
                )
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            val webViewDebugEnabled = remember {
                context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                    .getBoolean("plugin_webview_debug", false)
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView.setWebContentsDebuggingEnabled(webViewDebugEnabled)
                    WebView(ctx).apply {
                        webViewRef = this
                        @Suppress("DEPRECATION")
                        @SuppressLint("SetJavaScriptEnabled")
                        with(settings) {
                            javaScriptEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            allowUniversalAccessFromFileURLs = false
                            allowFileAccessFromFileURLs = false
                        }
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                        addJavascriptInterface(bridge, "DioxamineNative")

                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    val url = request.url
                                    // Strictly allow only local sandbox assets (appassets.androidplatform.net).
                                    // Direct external network loading (fetch/XHR/images/scripts) by the WebView engine is blocked.
                                    // Plugins must use the permission-gated window.dioxamine.http.fetch() bridge for network requests.
                                    if (isTrustedPluginUrl(url)) {
                                        return assetLoader.shouldInterceptRequest(url)
                                    }
                                    return WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        403,
                                        "Forbidden",
                                        emptyMap(),
                                        java.io.ByteArrayInputStream(ByteArray(0)),
                                    )
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val uri = request?.url ?: return true
                                    return !isTrustedPluginUrl(uri)
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    AppLogger.d("PluginWebView", "onPageStarted: $url")
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    super.onPageFinished(view, url)
                                    AppLogger.d("PluginWebView", "onPageFinished: $url")
                                    if (bridgeJsContent.isNotBlank()) {
                                        AppLogger.d("PluginWebView", "Injecting bridge JS (${bridgeJsContent.length} chars)")
                                        view?.evaluateJavascript(bridgeJsContent, null)
                                    }
                                    val themeScript = buildThemeInjectionScript(colorScheme, isDark)
                                    view?.evaluateJavascript(themeScript, null)
                                    val localeScript = buildLocaleInjectionScript(localeInfo)
                                    view?.evaluateJavascript(localeScript, null)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    super.onReceivedError(view, request, error)
                                    AppLogger.e("PluginWebView", "onReceivedError: url=${request?.url}, code=${error?.errorCode}, desc=${error?.description}")
                                }
                            }

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                consoleMessage?.let {
                                    val tag = "PluginJS:${manifest.id}"
                                    val msg = "[${it.sourceId()?.substringAfterLast('/') ?: "?"}:${it.lineNumber()}] ${it.message()}"
                                    when (it.messageLevel()) {
                                        ConsoleMessage.MessageLevel.ERROR -> AppLogger.e(tag, msg)
                                        ConsoleMessage.MessageLevel.WARNING -> AppLogger.w(tag, msg)
                                        ConsoleMessage.MessageLevel.LOG -> AppLogger.i(tag, msg)
                                        ConsoleMessage.MessageLevel.TIP -> AppLogger.d(tag, msg)
                                        ConsoleMessage.MessageLevel.DEBUG -> AppLogger.d(tag, msg)
                                        else -> AppLogger.d(tag, msg)
                                    }
                                }
                                return true
                            }
                        }

                        loadUrl(entryUrl)
                    }
                },
            )
        }
    }
}

/**
 * Verifies that a URI targets only local bundled plugin files or app assets loaded via WebViewAssetLoader.
 * Strictly checks that scheme is "https", host is "appassets.androidplatform.net", and path starts with "/plugin/" or "/assets/".
 * NEVER matches remote or external internet endpoints.
 */
private fun isTrustedPluginUrl(uri: Uri): Boolean {
    if (uri.scheme != "https") return false
    if (uri.host != "appassets.androidplatform.net") return false
    val path = uri.path ?: return false
    return path.startsWith("/plugin/") || path.startsWith("/assets/")
}

private class PluginStoragePathHandler(private val pluginDir: File) : WebViewAssetLoader.PathHandler {

    companion object {
        private const val BRIDGE_SCRIPT_TAG =
            """<script src="https://appassets.androidplatform.net/assets/plugin_runtime/dioxamine-bridge.js"></script>"""
    }

    override fun handle(path: String): WebResourceResponse? {
        AppLogger.d("PluginPathHandler", "handle() called: path=$path, pluginDir=${pluginDir.absolutePath}")
        val file = File(pluginDir, path)
        val canonicalPluginPath = pluginDir.canonicalPath + File.separator
        val canonicalFilePath = file.canonicalPath
        if (!canonicalFilePath.startsWith(canonicalPluginPath) && canonicalFilePath != pluginDir.canonicalPath) {
            AppLogger.w("PluginPathHandler", "Path traversal blocked: $path")
            return WebResourceResponse(null, null, null)
        }
        if (!file.exists() || !file.isFile) {
            AppLogger.w("PluginPathHandler", "Plugin file not found: ${file.absolutePath}")
            return WebResourceResponse(null, null, null)
        }
        val mimeType = when (file.extension.lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "xml" -> "text/xml"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "jpg", "jpeg", "jfif" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg", "svgz" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "bmp" -> "image/bmp"
            "apng" -> "image/apng"
            "avif" -> "image/avif"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "eot" -> "application/vnd.ms-fontobject"
            "mp3" -> "audio/mpeg"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "ogv" -> "video/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "gz", "tgz" -> "application/gzip"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }
        return try {
            if (mimeType == "text/html") {
                val html = file.readText()
                val injected = injectBridgeScript(html)
                WebResourceResponse(mimeType, "UTF-8", injected.byteInputStream())
            } else {
                WebResourceResponse(mimeType, null, file.inputStream())
            }
        } catch (e: Exception) {
            AppLogger.e("PluginPathHandler", "Error opening plugin file: ${file.absolutePath}", e)
            WebResourceResponse(null, null, null)
        }
    }

    private fun injectBridgeScript(html: String): String {
        var result = html
        // If developer omitted viewport meta tag, inject it automatically for mobile responsiveness
        val hasViewport = html.contains("<meta name=\"viewport\"", ignoreCase = true) || html.contains("<meta name='viewport'", ignoreCase = true)
        val viewportTag = if (!hasViewport) "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">\n    " else ""

        val headIndex = result.indexOf("<head>", ignoreCase = true)
        if (headIndex >= 0) {
            val insertAt = headIndex + "<head>".length
            return result.substring(0, insertAt) + "\n    " + viewportTag + BRIDGE_SCRIPT_TAG + result.substring(insertAt)
        }
        val headWithAttrsRegex = Regex("<head\\s[^>]*>", RegexOption.IGNORE_CASE)
        val match = headWithAttrsRegex.find(result)
        if (match != null) {
            val insertAt = match.range.last + 1
            return result.substring(0, insertAt) + "\n    " + viewportTag + BRIDGE_SCRIPT_TAG + result.substring(insertAt)
        }
        return viewportTag + BRIDGE_SCRIPT_TAG + "\n" + result
    }
}
