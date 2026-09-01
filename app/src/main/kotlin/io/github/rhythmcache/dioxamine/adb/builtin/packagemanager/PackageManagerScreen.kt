package io.github.rhythmcache.dioxamine.adb.builtin.packagemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.adb.readExactly
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.Constants
import io.github.rhythmcache.dioxamine.core.executeShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class AppFilter {
    ALL, USER, SYSTEM, DISABLED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageManagerScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val client = vm.activeClient()
    val activeConn = vm.devices[vm.activeDeviceId]

    val appList = remember { mutableStateListOf<AppPackageItem>() }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(AppFilter.ALL) }
    var filterDropdownExpanded by remember { mutableStateOf(false) }

    var selectedAppForInfo by remember { mutableStateOf<AppPackageItem?>(null) }
    var selectedAppForPermissions by remember { mutableStateOf<AppPackageItem?>(null) }
    var selectedAppForUninstall by remember { mutableStateOf<AppPackageItem?>(null) }

    var isOperating by remember { mutableStateOf(false) }
    var operationProgressMsg by remember { mutableStateOf<String?>(null) }

    var selectedAppForPull by remember { mutableStateOf<AppPackageItem?>(null) }

    val allCount = remember(appList.size) { appList.size }
    val userCount = remember(appList.size) { appList.count { !it.isSystem } }
    val systemCount = remember(appList.size) { appList.count { it.isSystem } }
    val disabledCount = remember(appList.size) { appList.count { !it.isEnabled } }

    val pullLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val app = selectedAppForPull ?: return@rememberLauncherForActivityResult
        if (uri == null || client == null) return@rememberLauncherForActivityResult

        coroutineScope.launch(Dispatchers.IO) {
            isOperating = true
            operationProgressMsg = context.getString(R.string.pkg_manager_pulling)
            try {
                if (!app.hasSplits) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        client.sync.pull(remotePath = app.sourceDir, output = out)
                    }
                } else {
                    val tempDir = File(context.cacheDir, "pull_${app.packageName}_${System.currentTimeMillis()}")
                    tempDir.mkdirs()
                    try {
                        val baseMasterFile = File(tempDir, "base-master.apk")
                        FileOutputStream(baseMasterFile).use { out ->
                            client.sync.pull(remotePath = app.sourceDir, output = out)
                        }

                        val pulledSplits = mutableListOf<Pair<String, File>>()
                        app.splitDirs.forEachIndexed { idx, splitRemotePath ->
                            val filename = splitRemotePath.substringAfterLast('/')
                            val splitLocalName = if (filename.startsWith("split_")) {
                                "base-${filename.removePrefix("split_")}"
                            } else if (!filename.endsWith(".apk")) {
                                "base-split_$idx.apk"
                            } else {
                                filename
                            }
                            val splitLocalFile = File(tempDir, splitLocalName)
                            FileOutputStream(splitLocalFile).use { out ->
                                client.sync.pull(remotePath = splitRemotePath, output = out)
                            }
                            pulledSplits.add(Pair(splitLocalName, splitLocalFile))
                        }

                        val apkDescArray = JSONArray()

                        val baseDesc = JSONObject()
                        baseDesc.put("path", "splits/base-master.apk")
                        baseDesc.put("targeting", JSONObject())
                        apkDescArray.put(baseDesc)

                        pulledSplits.forEach { (name, _) ->
                            val desc = JSONObject()
                            desc.put("path", "splits/$name")
                            val targeting = JSONObject()
                            val lower = name.lowercase()
                            when {
                                lower.contains("arm64") || lower.contains("v8a") -> {
                                    val abiObj = JSONObject().put("value", JSONArray().put(JSONObject().put("alias", "ARM64_V8A")))
                                    targeting.put("abi", abiObj)
                                }
                                lower.contains("armeabi") || lower.contains("v7a") -> {
                                    val abiObj = JSONObject().put("value", JSONArray().put(JSONObject().put("alias", "ARMEABI_V7A")))
                                    targeting.put("abi", abiObj)
                                }
                                lower.contains("x86_64") -> {
                                    val abiObj = JSONObject().put("value", JSONArray().put(JSONObject().put("alias", "X86_64")))
                                    targeting.put("abi", abiObj)
                                }
                                lower.contains("x86") -> {
                                    val abiObj = JSONObject().put("value", JSONArray().put(JSONObject().put("alias", "X86")))
                                    targeting.put("abi", abiObj)
                                }
                                lower.contains("xxhdpi") -> {
                                    val densityObj = JSONObject().put("value", JSONArray().put(JSONObject().put("density_alias", "XXHDPI")))
                                    targeting.put("screen_density", densityObj)
                                }
                                lower.contains("xhdpi") -> {
                                    val densityObj = JSONObject().put("value", JSONArray().put(JSONObject().put("density_alias", "XHDPI")))
                                    targeting.put("screen_density", densityObj)
                                }
                                lower.contains("hdpi") -> {
                                    val densityObj = JSONObject().put("value", JSONArray().put(JSONObject().put("density_alias", "HDPI")))
                                    targeting.put("screen_density", densityObj)
                                }
                            }
                            desc.put("targeting", targeting)
                            apkDescArray.put(desc)
                        }

                        val apkSetObj = JSONObject()
                        val moduleMeta = JSONObject().apply {
                            put("name", "base")
                            put("is_instant", false)
                        }
                        apkSetObj.put("module_metadata", moduleMeta)
                        apkSetObj.put("apk_description", apkDescArray)

                        val variantObj = JSONObject()
                        variantObj.put("apk_set", JSONArray().put(apkSetObj))

                        val tocJson = JSONObject()
                        tocJson.put("package_name", app.packageName)
                        tocJson.put("version_code", app.versionCode)
                        tocJson.put("version_name", app.versionName)
                        tocJson.put("variant", JSONArray().put(variantObj))

                        context.contentResolver.openOutputStream(uri)?.use { rawOut ->
                            ZipOutputStream(BufferedOutputStream(rawOut)).use { zos ->
                                val tocEntry = ZipEntry("toc.json")
                                zos.putNextEntry(tocEntry)
                                zos.write(tocJson.toString(2).toByteArray(Charsets.UTF_8))
                                zos.closeEntry()

                                val baseEntry = ZipEntry("splits/base-master.apk")
                                zos.putNextEntry(baseEntry)
                                baseMasterFile.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()

                                pulledSplits.forEach { (name, file) ->
                                    val splitEntry = ZipEntry("splits/$name")
                                    zos.putNextEntry(splitEntry)
                                    file.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        }
                    } finally {
                        tempDir.deleteRecursively()
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.pkg_manager_pull_success, app.label), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e("PkgManager", "Pull failed for ${app.packageName}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.pkg_manager_pull_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isOperating = false
                    operationProgressMsg = null
                    selectedAppForPull = null
                }
            }
        }
    }

    val installPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty() || client == null || activeConn == null) return@rememberLauncherForActivityResult

        coroutineScope.launch(Dispatchers.IO) {
            isOperating = true
            operationProgressMsg = context.getString(R.string.pkg_manager_installing)
            val tempDir = File(context.cacheDir, "install_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                val copiedFiles = mutableListOf<File>()
                for (uri in uris) {
                    val displayName = getFileNameFromUri(context, uri) ?: "temp_${copiedFiles.size}.apk"
                    val targetFile = File(tempDir, displayName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (displayName.endsWith(".apks", ignoreCase = true) || displayName.endsWith(".xapk", ignoreCase = true) || displayName.endsWith(".zip", ignoreCase = true)) {
                        ZipInputStream(targetFile.inputStream()).use { zis ->
                            var entry: ZipEntry? = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                                    val extractedName = entry.name.substringAfterLast('/')
                                    val extractedFile = File(tempDir, extractedName)
                                    FileOutputStream(extractedFile).use { out -> zis.copyTo(out) }
                                    copiedFiles.add(extractedFile)
                                }
                                entry = zis.nextEntry
                            }
                        }
                        targetFile.delete()
                    } else {
                        copiedFiles.add(targetFile)
                    }
                }

                if (copiedFiles.isEmpty()) {
                    throw Exception("No valid APK files found in selection")
                }

                if (copiedFiles.size == 1 && copiedFiles.first().name.endsWith(".apk", ignoreCase = true)) {
                    val apkFile = copiedFiles.first()
                    val remotePath = "${Constants.DEVICE_TMP_DIR}/temp_install.apk"
                    apkFile.inputStream().use { input ->
                        client.sync.push(input, remotePath)
                    }
                    val output = client.executeShell("pm install -r $remotePath", activeConn.supportsShellV2)
                    client.executeShell("rm -f $remotePath", activeConn.supportsShellV2)

                    if (output.contains("Success", ignoreCase = true)) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.pkg_manager_install_success), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        throw Exception(output.trim())
                    }
                } else {
                    val remoteDir = "${Constants.DEVICE_TMP_DIR}/split_install_${System.currentTimeMillis()}"
                    client.executeShell("mkdir -p $remoteDir", activeConn.supportsShellV2)

                    try {
                        copiedFiles.forEach { file ->
                            file.inputStream().use { input ->
                                client.sync.push(input, "$remoteDir/${file.name}")
                            }
                        }

                        val createOutput = client.executeShell("pm install-create -r", activeConn.supportsShellV2)
                        val sessionIdMatch = Regex("(?:session|created|\\s|^)\\[?(\\d+)\\]?").find(createOutput)
                        val sessionId = sessionIdMatch?.groupValues?.get(1)
                            ?: throw Exception("Failed to parse session ID from: $createOutput")

                        copiedFiles.forEachIndexed { index, file ->
                            val writeCmd = "pm install-write $sessionId ${index}_${file.name} $remoteDir/${file.name}"
                            val writeOut = client.executeShell(writeCmd, activeConn.supportsShellV2)
                            if (!writeOut.contains("Success", ignoreCase = true) && writeOut.isNotBlank()) {
                                AppLogger.w("PkgManager", "pm install-write output: $writeOut")
                            }
                        }

                        val commitOutput = client.executeShell("pm install-commit $sessionId", activeConn.supportsShellV2)
                        if (commitOutput.contains("Success", ignoreCase = true)) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.pkg_manager_install_success), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            throw Exception(commitOutput.trim())
                        }
                    } finally {
                        client.executeShell("rm -rf $remoteDir", activeConn.supportsShellV2)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("PkgManager", "Install failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.pkg_manager_install_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } finally {
                tempDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    isOperating = false
                    operationProgressMsg = null
                }
            }
        }
    }

    fun loadPackages() {
        if (client == null) return
        isLoading = true
        appList.clear()

        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                runCatching {
                    val killStream = client.open("shell:pkill -f DioxAgent || killall DioxAgent || pkill -f PkgDump")
                    val buf = ByteArray(256)
                    while (killStream.read(buf) > 0) { /* drain */ }
                    killStream.close()
                }

                context.assets.open("diox-agent.jar").use { input ->
                    client.sync.push(input, "${Constants.DEVICE_TMP_DIR}/diox-agent.jar")
                }

                val cmd = "CLASSPATH=${Constants.DEVICE_TMP_DIR}/diox-agent.jar app_process / DioxAgent --icons"
                val stream = client.open("exec:$cmd")
                val stdoutStream = RawStdoutStream(stream)

                if (!stdoutStream.findMagic()) {
                    throw Exception("Could not locate PKGD magic header in stream")
                }

                val version = stdoutStream.readByte().toInt()

                var recordCount = 0
                while (isActive) {
                    val item = stdoutStream.readNextAppPackageItem() ?: break
                    recordCount++
                    withContext(Dispatchers.Main) {
                        appList.add(item)
                    }
                }
                AppLogger.i("PKGDUMP_DIAGNOSTIC", ">>> [PKGDUMP_DIAGNOSTIC] STREAM_FINISHED total_records=$recordCount")
                stream.close()
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }.onFailure { err ->
                AppLogger.e("PKGDUMP_DIAGNOSTIC", ">>> [PKGDUMP_DIAGNOSTIC] STREAM_FAILED: ${err.message}", err)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, context.getString(R.string.pkg_manager_dump_failed, err.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(client) {
        if (appList.isEmpty() && client != null) {
            loadPackages()
        }
    }

    val filteredApps = remember(appList.size, searchQuery, selectedFilter) {
        appList.filter { app ->
            val matchesFilter = when (selectedFilter) {
                AppFilter.ALL -> true
                AppFilter.USER -> !app.isSystem
                AppFilter.SYSTEM -> app.isSystem
                AppFilter.DISABLED -> !app.isEnabled
            }
            val matchesSearch = searchQuery.isBlank() ||
                    app.label.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            matchesFilter && matchesSearch
        }
    }

    BackHandler {
        if (selectedAppForInfo != null) {
            selectedAppForInfo = null
        } else if (selectedAppForUninstall != null) {
            selectedAppForUninstall = null
        } else if (selectedAppForPull != null) {
            selectedAppForPull = null
        } else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else {
            onBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        if (isSearchActive) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(stringResource(R.string.pkg_manager_search_hint)) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Column {
                                Text(
                                    text = stringResource(R.string.pkg_manager_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${filteredApps.size} packages (${
                                        when (selectedFilter) {
                                            AppFilter.ALL -> "All: $allCount"
                                            AppFilter.USER -> "User: $userCount"
                                            AppFilter.SYSTEM -> "System: $systemCount"
                                            AppFilter.DISABLED -> "Disabled: $disabledCount"
                                        }
                                    })",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isSearchActive) {
                                isSearchActive = false
                                searchQuery = ""
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = !isSearchActive }) {
                            Icon(
                                if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = null
                            )
                        }

                        IconButton(onClick = { loadPackages() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                        }

                        Box {
                            IconButton(onClick = { filterDropdownExpanded = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = filterDropdownExpanded,
                                onDismissRequest = { filterDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("${stringResource(R.string.pkg_manager_filter_all)} ($allCount)") },
                                    onClick = { selectedFilter = AppFilter.ALL; filterDropdownExpanded = false },
                                    leadingIcon = { if (selectedFilter == AppFilter.ALL) Icon(Icons.Default.Check, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("${stringResource(R.string.pkg_manager_filter_user)} ($userCount)") },
                                    onClick = { selectedFilter = AppFilter.USER; filterDropdownExpanded = false },
                                    leadingIcon = { if (selectedFilter == AppFilter.USER) Icon(Icons.Default.Check, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("${stringResource(R.string.pkg_manager_filter_system)} ($systemCount)") },
                                    onClick = { selectedFilter = AppFilter.SYSTEM; filterDropdownExpanded = false },
                                    leadingIcon = { if (selectedFilter == AppFilter.SYSTEM) Icon(Icons.Default.Check, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("${stringResource(R.string.pkg_manager_filter_disabled)} ($disabledCount)") },
                                    onClick = { selectedFilter = AppFilter.DISABLED; filterDropdownExpanded = false },
                                    leadingIcon = { if (selectedFilter == AppFilter.DISABLED) Icon(Icons.Default.Check, null) }
                                )
                            }
                        }

                        IconButton(onClick = { installPickerLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.pkg_manager_install_apks))
                        }
                    }
                )

                if (isLoading || isOperating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (isOperating && operationProgressMsg != null) {
                        Text(
                            text = operationProgressMsg!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (filteredApps.isEmpty() && !isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pkg_manager_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        AppTileItem(
                            app = app,
                            onEnableDisable = {
                                if (client == null || activeConn == null) return@AppTileItem
                                coroutineScope.launch(Dispatchers.IO) {
                                    val action = if (app.isEnabled) "disable-user --user 0" else "enable"
                                    val cmd = "pm $action ${app.packageName}"
                                    val out = client.executeShell(cmd, activeConn.supportsShellV2)
                                    val success = out.contains(app.packageName, ignoreCase = true) || out.contains("new state", ignoreCase = true) || out.isBlank()
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            val index = appList.indexOfFirst { it.packageName == app.packageName }
                                            if (index >= 0) {
                                                appList[index] = appList[index].copy(isEnabled = !app.isEnabled)
                                            }
                                            val msgRes = if (app.isEnabled) R.string.pkg_manager_disable_success else R.string.pkg_manager_enable_success
                                            Toast.makeText(context, context.getString(msgRes, app.label), Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, out.trim(), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onForceStop = {
                                if (client == null || activeConn == null) return@AppTileItem
                                coroutineScope.launch(Dispatchers.IO) {
                                    client.executeShell("am force-stop ${app.packageName}", activeConn.supportsShellV2)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.pkg_manager_force_stop_success, app.label), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onPull = {
                                selectedAppForPull = app
                                val suffix = if (app.hasSplits) ".apks" else ".apk"
                                val defaultName = "${app.packageName}_v${app.versionName}$suffix"
                                pullLauncher.launch(defaultName)
                            },
                            onUninstall = {
                                selectedAppForUninstall = app
                            },
                            onPermissions = {
                                selectedAppForPermissions = app
                            },
                            onInfo = {
                                selectedAppForInfo = app
                            }
                        )
                    }
                }
            }
        }
    }

    selectedAppForUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { selectedAppForUninstall = null },
            title = { Text(stringResource(R.string.pkg_manager_uninstall_confirm_title)) },
            text = { Text(stringResource(R.string.pkg_manager_uninstall_confirm_msg, app.label, app.packageName)) },
            confirmButton = {
                TextButton(onClick = {
                    val appToUninstall = app
                    selectedAppForUninstall = null
                    if (client != null && activeConn != null) {
                        coroutineScope.launch(Dispatchers.IO) {
                            val out = client.executeShell("pm uninstall ${appToUninstall.packageName}", activeConn.supportsShellV2)
                            val success = out.contains("Success", ignoreCase = true)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    appList.removeAll { it.packageName == appToUninstall.packageName }
                                    Toast.makeText(context, context.getString(R.string.pkg_manager_uninstall_success, appToUninstall.label), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.pkg_manager_uninstall_failed, out.trim()), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.pkg_manager_action_uninstall), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAppForUninstall = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    selectedAppForInfo?.let { app ->
        AppInfoDialog(
            app = app,
            onDismiss = { selectedAppForInfo = null },
            onOpenSettings = {
                if (client != null && activeConn != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        client.executeShell("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${app.packageName}", activeConn.supportsShellV2)
                    }
                }
            }
        )
    }

    selectedAppForPermissions?.let { app ->
        AppPermissionsDialog(
            app = app,
            onDismiss = { selectedAppForPermissions = null },
            onGrantPermission = { name, cmd ->
                if (client != null && activeConn != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        val out = client.executeShell(cmd, activeConn.supportsShellV2).trim()
                        val isError = out.contains("Error", ignoreCase = true) ||
                                out.contains("Exception", ignoreCase = true) ||
                                out.contains("SecurityException", ignoreCase = true) ||
                                out.contains("not requested", ignoreCase = true) ||
                                out.contains("Operation not allowed", ignoreCase = true) ||
                                out.contains("Failure", ignoreCase = true) ||
                                out.contains("unknown", ignoreCase = true)
                        withContext(Dispatchers.Main) {
                            if (isError) {
                                Toast.makeText(context, context.getString(R.string.pkg_manager_perm_failed, out), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.pkg_manager_perm_granted, name), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun AppTileItem(
    app: AppPackageItem,
    onEnableDisable: () -> Unit,
    onForceStop: () -> Unit,
    onPull: () -> Unit,
    onUninstall: () -> Unit,
    onPermissions: () -> Unit,
    onInfo: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val iconBitmap: ImageBitmap? = remember(app.iconBytes) {
        if (app.iconBytes.isNotEmpty()) {
            runCatching {
                BitmapFactory.decodeByteArray(app.iconBytes, 0, app.iconBytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isEnabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (app.versionName.isNotBlank()) {
                        BadgeChip(
                            text = "v${app.versionName}",
                            color = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (app.isSystem) {
                        BadgeChip(
                            text = stringResource(R.string.pkg_badge_system),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (!app.isEnabled) {
                        BadgeChip(
                            text = stringResource(R.string.pkg_badge_disabled),
                            color = MaterialTheme.colorScheme.errorContainer,
                            textColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    if (app.hasSplits) {
                        BadgeChip(
                            text = stringResource(R.string.pkg_badge_splits, app.splitDirs.size + 1),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            textColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (app.isEnabled) stringResource(R.string.pkg_manager_action_disable) else stringResource(R.string.pkg_manager_action_enable)) },
                        onClick = {
                            menuExpanded = false
                            onEnableDisable()
                        },
                        leadingIcon = {
                            Icon(
                                if (app.isEnabled) Icons.Default.Block else Icons.Default.CheckCircle,
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pkg_manager_action_force_stop)) },
                        onClick = {
                            menuExpanded = false
                            onForceStop()
                        },
                        leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pkg_manager_action_pull)) },
                        onClick = {
                            menuExpanded = false
                            onPull()
                        },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) }
                    )
                    if (!app.isSystem) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pkg_manager_action_uninstall)) },
                            onClick = {
                                menuExpanded = false
                                onUninstall()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pkg_manager_action_permissions)) },
                        onClick = {
                            menuExpanded = false
                            onPermissions()
                        },
                        leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pkg_manager_action_info)) },
                        onClick = {
                            menuExpanded = false
                            onInfo()
                        },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                    )
                }
            }
        }
    }
}

@Composable
fun BadgeChip(
    text: String,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = textColor
        )
    }
}

@Composable
fun AppInfoDialog(
    app: AppPackageItem,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoRow(stringResource(R.string.pkg_info_package_name), app.packageName)
                InfoRow(stringResource(R.string.pkg_info_version_name), app.versionName.ifBlank { stringResource(R.string.pkg_info_not_available) })
                InfoRow(stringResource(R.string.pkg_info_version_code), app.versionCode.toString())
                InfoRow(stringResource(R.string.pkg_info_target_sdk), "API ${app.targetSdk}")
                if (app.minSdk > 0) {
                    InfoRow(stringResource(R.string.pkg_info_min_sdk), "API ${app.minSdk}")
                }
                InfoRow(stringResource(R.string.pkg_info_uid), app.uid.toString())
                if (app.installer.isNotBlank()) {
                    InfoRow(stringResource(R.string.pkg_info_installer), app.installer)
                }
                InfoRow(stringResource(R.string.pkg_info_source_dir), app.sourceDir)
                if (app.dataDir.isNotBlank()) {
                    InfoRow(stringResource(R.string.pkg_info_data_dir), app.dataDir)
                }
                if (app.firstInstallTime > 0) {
                    InfoRow(stringResource(R.string.pkg_info_first_installed), dateFormat.format(Date(app.firstInstallTime)))
                }
                if (app.lastUpdateTime > 0) {
                    InfoRow(stringResource(R.string.pkg_info_last_updated), dateFormat.format(Date(app.lastUpdateTime)))
                }
                if (app.hasSplits) {
                    Text(
                        text = stringResource(R.string.pkg_info_split_apks, app.splitDirs.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    app.splitDirs.forEach { splitPath ->
                        Text(
                            text = "\u2022 ${splitPath.substringAfterLast('/')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(context.getString(R.string.pkg_info_package_name), app.packageName)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.pkg_manager_name_copied), Toast.LENGTH_SHORT).show()
            }) {
                Text(stringResource(R.string.pkg_manager_copy_package))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.pkg_manager_app_settings))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                name = it.getString(nameIndex)
            }
        }
    }
    return name ?: uri.lastPathSegment
}

@Composable
fun AppPermissionsDialog(
    app: AppPackageItem,
    onDismiss: () -> Unit,
    onGrantPermission: (String, String) -> Unit
) {
    val context = LocalContext.current
    var permissionInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.pkg_manager_dialog_permissions_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(app.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.pkg_manager_perm_prompt),
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = permissionInput,
                    onValueChange = { permissionInput = it },
                    placeholder = { Text(stringResource(R.string.pkg_manager_custom_perm_placeholder)) },
                    label = { Text(stringResource(R.string.pkg_manager_perm_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    text = stringResource(R.string.pkg_manager_perm_manifest_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = permissionInput.trim()
                    if (trimmed.isNotBlank()) {
                        val fullPerm = if (trimmed.contains('.')) trimmed else "android.permission.$trimmed"
                        val validRegex = Regex("""^[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+$""")
                        if (!validRegex.matches(fullPerm)) {
                            Toast.makeText(context, context.getString(R.string.pkg_manager_perm_invalid_format), Toast.LENGTH_SHORT).show()
                        } else {
                            onGrantPermission(fullPerm, "pm grant ${app.packageName} $fullPerm")
                            onDismiss()
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.pkg_manager_btn_grant))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
