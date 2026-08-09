package io.github.rhythmcache.dioxamine.plugin

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import io.github.rhythmcache.dioxamine.core.AppLogger
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.core.LocalDarkTheme
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
    repo: PluginRepository,
    permissionGate: PluginPermissionGate,
    dialogGate: PluginDialogGate,
    safBridge: PluginSafBridge,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val installedPlugins by repo.installedPlugins.collectAsState()
    val manifest = remember(installedPlugins, pluginId) { installedPlugins.find { it.id == pluginId } }

    val colorScheme = MaterialTheme.colorScheme
    val isDark = LocalDarkTheme.current

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

    LaunchedEffect(colorScheme, isDark) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript(buildThemeInjectionScript(colorScheme, isDark), null)
        }
    }

    val bridge =
        remember(manifest.id, bridgeScope) {
            DioxaminePluginBridge(
                context = context.applicationContext,
                pluginId = manifest.id,
                pluginName = manifest.name,
                declaredPermissions = manifest.permissions.mapNotNull { PluginPermission.fromManifestString(it) },
                getActiveClient = { vm.activeClient() },
                permissionGate = permissionGate,
                dialogGate = dialogGate,
                safBridge = safBridge,
                scope = bridgeScope,
                evaluateJs = { script ->
                    webViewRef?.post {
                        webViewRef?.evaluateJavascript(script, null)
                    }
                },
            )
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
        topBar = {
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
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        @Suppress("DEPRECATION")
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.domStorageEnabled = true

                        addJavascriptInterface(bridge, "DioxamineNative")

                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

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
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    super.onPageFinished(view, url)
                                    if (bridgeJsContent.isNotBlank()) {
                                        view?.evaluateJavascript(bridgeJsContent, null)
                                    }
                                    val themeScript = buildThemeInjectionScript(colorScheme, isDark)
                                    view?.evaluateJavascript(themeScript, null)
                                }
                            }

                        loadUrl(entryUrl)
                    }
                },
            )
        }
    }
}

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
        val file = File(pluginDir, path)
        val canonicalPluginPath = pluginDir.canonicalPath + File.separator
        val canonicalFilePath = file.canonicalPath
        if (!canonicalFilePath.startsWith(canonicalPluginPath) && canonicalFilePath != pluginDir.canonicalPath) {
            AppLogger.w("PluginPathHandler", "Path traversal blocked: $path")
            return null
        }
        if (!file.exists() || !file.isFile) {
            AppLogger.w("PluginPathHandler", "Plugin file not found: ${file.absolutePath}")
            return null
        }
        val mimeType = when (file.extension.lowercase()) {
            "html", "htm" -> "text/html"
            "js" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
        return try {
            if (mimeType == "text/html") {
                val html = file.readText()
                val injected = injectBridgeScript(html)
                WebResourceResponse(mimeType, "UTF-8", injected.byteInputStream())
            } else {
                WebResourceResponse(mimeType, "UTF-8", file.inputStream())
            }
        } catch (e: Exception) {
            AppLogger.e("PluginPathHandler", "Error opening plugin file: ${file.absolutePath}", e)
            null
        }
    }

    private fun injectBridgeScript(html: String): String {
        // Inject bridge script as the first child of <head> for synchronous loading
        val headIndex = html.indexOf("<head>", ignoreCase = true)
        if (headIndex >= 0) {
            val insertAt = headIndex + "<head>".length
            return html.substring(0, insertAt) + "\n    " + BRIDGE_SCRIPT_TAG + html.substring(insertAt)
        }
        // Handle <head> with attributes
        val headWithAttrsRegex = Regex("<head\\s[^>]*>", RegexOption.IGNORE_CASE)
        val match = headWithAttrsRegex.find(html)
        if (match != null) {
            val insertAt = match.range.last + 1
            return html.substring(0, insertAt) + "\n    " + BRIDGE_SCRIPT_TAG + html.substring(insertAt)
        }
        // Last resort: prepend the script tag
        return BRIDGE_SCRIPT_TAG + "\n" + html
    }
}
