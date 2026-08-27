package io.github.rhythmcache.dioxamine.adb.builtin.filemanager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Shortcut
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.Constants
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RemoteFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val isParentDir: Boolean = false,
    val size: Long = 0L,
    val permissions: String = "",
    val mtime: Long = 0L,
    val symlinkTarget: String? = null,
    val hasWarnError: Boolean = false,
    val warnErrorMsg: String? = null,
    var isPendingUpload: Boolean = false,
    var isUploading: Boolean = false,
    var isDownloading: Boolean = false,
    var uploadProgress: Float = 0f,
    var downloadProgress: Float = 0f
)

private enum class ActiveDialog { NONE, PROPERTIES, CREATE_FOLDER, RENAME, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val client = vm.activeClient()

    var currentPath by remember { mutableStateOf("/sdcard") }
    var fileList by remember { mutableStateOf<List<RemoteFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isDirectoryWritable by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var selectedItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var activeDialog by remember { mutableStateOf(ActiveDialog.NONE) }
    var dialogInputValue by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }
    var dxlsDaemonStarted by remember { mutableStateOf(false) }

    var pendingDownloadFile by remember { mutableStateOf<RemoteFileItem?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            if (client != null) {
                vm.viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val shutdownStream = client.open("localabstract:dxls")
                        val req = JSONObject().put("cmd", "shutdown").toString() + "\n"
                        shutdownStream.write(req.toByteArray(Charsets.UTF_8))
                        shutdownStream.close()
                    }
                }
            }
        }
    }

    fun ensureNativeDaemon(onReady: () -> Unit) {
        if (client == null) return

        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                var daemonReady = false
                try {
                    val pingStream = client.open("localabstract:dxls")
                    pingStream.close()
                    daemonReady = true
                } catch (_: Exception) {
                }

                if (!daemonReady) {
                    val abiStream = client.open("shell:getprop ro.product.cpu.abi")
                    val buf = ByteArray(128)
                    val n = abiStream.read(buf)
                    val rawAbi = if (n > 0) String(buf, 0, n, Charsets.UTF_8).trim() else "arm64-v8a"
                    abiStream.close()

                    val abi = when {
                        rawAbi.startsWith("arm64") || rawAbi.contains("aarch64") -> "arm64-v8a"
                        rawAbi.startsWith("arm") || rawAbi.contains("v7a") -> "armeabi-v7a"
                        rawAbi.contains("x86_64") -> "x86_64"
                        rawAbi.contains("x86") -> "x86"
                        else -> "arm64-v8a"
                    }

                    val assetPath = "dxls/dxls-$abi"
                    context.assets.open(assetPath).use { input ->
                        client.sync.push(input, "${Constants.DEVICE_TMP_DIR}/dxls", mode = 493) // 493 = octal 0755
                    }

                    val chmodStream = client.open("shell:chmod 755 ${Constants.DEVICE_TMP_DIR}/dxls")
                    chmodStream.close()

                    vm.viewModelScope.launch(Dispatchers.IO) {
                        runCatching {
                            val daemonExecStream = client.open("shell:${Constants.DEVICE_TMP_DIR}/dxls")
                            val buf = ByteArray(1024)
                            while (daemonExecStream.read(buf) != -1) { }
                            daemonExecStream.close()
                        }
                    }

                    for (i in 1..10) {
                        kotlinx.coroutines.delay(100)
                        try {
                            val testStream = client.open("localabstract:dxls")
                            testStream.close()
                            daemonReady = true
                            break
                        } catch (_: Exception) {
                        }
                    }
                }

                dxlsDaemonStarted = daemonReady
                if (!daemonReady) throw Exception("Failed to connect to dxls socket server @dxls")
            }.onSuccess {
                withContext(Dispatchers.Main) { onReady() }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to start dxls daemon: ${err.message}"
                    isLoading = false
                }
            }
        }
    }

    fun loadDirectory(targetPath: String) {
        if (client == null) return
        isLoading = true
        errorMessage = null

        ensureNativeDaemon {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    val cleanPath = targetPath.trimEnd('/')
                    val cmdPath = if (cleanPath.isEmpty()) "/" else cleanPath

                    val stream = client.open("localabstract:dxls")
                    val jsonReq = JSONObject().put("cmd", "list").put("path", cmdPath).toString() + "\n"
                    stream.write(jsonReq.toByteArray(Charsets.UTF_8))

                    val buf = ByteArray(16384)
                    val sb = StringBuilder()
                    while (true) {
                        val n = stream.read(buf)
                        if (n == -1) break
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                        if (sb.contains("\"type\":\"done\"")) break
                    }
                    stream.close()

                    val rawLines = sb.toString().lines()
                    val parsedItems = mutableListOf<RemoteFileItem>()
                    var isWritable = true
                    var metaError: String? = null

                    for (line in rawLines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue

                        runCatching {
                            val json = JSONObject(trimmed)
                            when (json.optString("type")) {
                                "meta" -> {
                                    val readable = json.optBoolean("readable", true)
                                    isWritable = json.optBoolean("writable", true)
                                    if (!readable) {
                                        metaError = "Permission Denied: Directory is not readable"
                                    } else if (!json.isNull("error")) {
                                        metaError = json.optString("error")
                                    }
                                }
                                "entry" -> {
                                    val name = json.getString("name")
                                    val ftype = json.getString("ftype")
                                    val isDir = ftype == "dir"
                                    val isSym = ftype == "symlink"
                                    val targetIsDir = json.optBoolean("target_is_dir", isDir)
                                    val size = json.optLong("size", 0L)
                                    val mtime = json.optLong("mtime", 0L)
                                    val mode = json.optString("mode", "")
                                    val symlinkTarget = if (!json.isNull("symlink_target")) json.optString("symlink_target") else null

                                    val fullPath = if (cmdPath == "/") "/$name" else "$cmdPath/$name"
                                    parsedItems.add(
                                        RemoteFileItem(
                                            name = name,
                                            path = fullPath,
                                            isDirectory = targetIsDir,
                                            isSymlink = isSym,
                                            size = size,
                                            permissions = mode,
                                            mtime = mtime,
                                            symlinkTarget = symlinkTarget
                                        )
                                    )
                                }
                                "warn" -> {
                                    val name = json.optString("name", "")
                                    val err = json.optString("error", "Access Error")
                                    if (name.isNotEmpty()) {
                                        val fullPath = if (cmdPath == "/") "/$name" else "$cmdPath/$name"
                                        parsedItems.add(
                                            RemoteFileItem(
                                                name = name,
                                                path = fullPath,
                                                isDirectory = false,
                                                hasWarnError = true,
                                                warnErrorMsg = err
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (metaError != null) {
                        throw Exception(metaError)
                    }

                    val sorted = parsedItems.sortedWith(
                        compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() }
                    )

                    val finalList = mutableListOf<RemoteFileItem>()
                    if (cmdPath != "/") {
                        val parentPath = if (cmdPath.substringBeforeLast('/').isEmpty()) "/" else cmdPath.substringBeforeLast('/')
                        finalList.add(
                            RemoteFileItem(
                                name = "..",
                                path = parentPath,
                                isDirectory = true,
                                isParentDir = true
                            )
                        )
                    }
                    finalList.addAll(sorted)

                    withContext(Dispatchers.Main) {
                        currentPath = cmdPath
                        fileList = finalList
                        isDirectoryWritable = isWritable
                        isLoading = false
                    }
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        errorMessage = err.message ?: "Failed to list directory"
                        isLoading = false
                    }
                }
            }
        }
    }

    LaunchedEffect(currentPath) {
        if (fileList.isEmpty() && !isLoading) {
            loadDirectory(currentPath)
        }
    }

    val pushLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty() || client == null) return@rememberLauncherForActivityResult

        coroutineScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                var fileName = "file_${System.currentTimeMillis()}"
                var fileSize = 0L

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx != -1) fileName = cursor.getString(nameIdx)
                        if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                    }
                }

                val remoteDest = if (currentPath == "/") "/$fileName" else "$currentPath/$fileName"
                val pendingItem = RemoteFileItem(
                    name = fileName,
                    path = remoteDest,
                    isDirectory = false,
                    size = fileSize,
                    isPendingUpload = true,
                    isUploading = true
                )

                withContext(Dispatchers.Main) {
                    fileList = fileList + pendingItem
                }

                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        client.sync.push(
                            input = inputStream,
                            remotePath = remoteDest,
                            onProgress = { bytesDone ->
                                if (fileSize > 0) {
                                    pendingItem.uploadProgress = (bytesDone.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f)
                                }
                            }
                        )
                    }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        pendingItem.isUploading = false
                        pendingItem.isPendingUpload = false
                    }
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.file_manager_upload_failed, fileName, err.message ?: ""), Toast.LENGTH_LONG).show()
                        fileList = fileList.filter { it.path != remoteDest }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                loadDirectory(currentPath)
            }
        }
    }

    fun handlePushClick() {
        if (!isDirectoryWritable) {
            Toast.makeText(context, context.getString(R.string.file_manager_write_denied), Toast.LENGTH_LONG).show()
        } else {
            pushLauncher.launch(arrayOf("*/*"))
        }
    }

    val pullLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { localUri: Uri? ->
        val item = pendingDownloadFile
        if (localUri == null || item == null || client == null) return@rememberLauncherForActivityResult

        coroutineScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                item.isDownloading = true
            }

            runCatching {
                context.contentResolver.openOutputStream(localUri)?.use { outputStream ->
                    client.sync.pull(
                        remotePath = item.path,
                        output = outputStream,
                        onProgress = { bytesDone ->
                            if (item.size > 0) {
                                item.downloadProgress = (bytesDone.toFloat() / item.size.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    )
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    item.isDownloading = false
                    Toast.makeText(context, context.getString(R.string.file_manager_download_success, item.name), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    item.isDownloading = false
                    Toast.makeText(context, context.getString(R.string.file_manager_download_failed, err.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val filteredList = remember(fileList, searchQuery) {
        if (searchQuery.isEmpty()) fileList
        else fileList.filter { it.isParentDir || it.name.contains(searchQuery, ignoreCase = true) }
    }

    BackHandler {
        if (activeDialog != ActiveDialog.NONE) {
            activeDialog = ActiveDialog.NONE
        } else if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else if (currentPath != "/sdcard" && currentPath != "/") {
            val parent = java.io.File(currentPath).parent ?: "/sdcard"
            loadDirectory(parent)
        } else {
            onBack()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text(stringResource(R.string.file_manager_title), style = MaterialTheme.typography.titleMedium)
                        Text(currentPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { isSearching = !isSearching }) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    }
                    IconButton(onClick = { handlePushClick() }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.file_manager_push_file))
                    }
                    IconButton(onClick = {
                        if (!isDirectoryWritable) {
                            Toast.makeText(context, context.getString(R.string.file_manager_write_denied), Toast.LENGTH_LONG).show()
                        } else {
                            dialogInputValue = ""
                            activeDialog = ActiveDialog.CREATE_FOLDER
                        }
                    }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.file_manager_new_folder))
                    }
                    IconButton(onClick = { loadDirectory(currentPath) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BreadcrumbBar(
                currentPath = currentPath,
                onNavigate = { loadDirectory(it) }
            )

            if (isSearching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.file_manager_search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                            }
                        }
                    }
                )
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { loadDirectory(currentPath) }) {
                            Text(stringResource(R.string.file_manager_retry))
                        }
                    }
                }
            } else if (filteredList.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.file_manager_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredList, key = { it.path }) { item ->
                        FileItemRow(
                            item = item,
                            onClick = {
                                if (item.isDirectory) {
                                    loadDirectory(item.path)
                                } else {
                                    selectedItem = item
                                    activeDialog = ActiveDialog.PROPERTIES
                                }
                            },
                            onProperties = {
                                selectedItem = item
                                activeDialog = ActiveDialog.PROPERTIES
                            },
                            onDownload = {
                                selectedItem = item
                                if (item.isDirectory) {
                                    Toast.makeText(context, context.getString(R.string.file_manager_folder_download_unsupported), Toast.LENGTH_SHORT).show()
                                } else {
                                    pendingDownloadFile = item
                                    pullLauncher.launch(item.name)
                                }
                            },
                            onRename = {
                                selectedItem = item
                                dialogInputValue = item.path
                                activeDialog = ActiveDialog.RENAME
                            },
                            onDelete = {
                                selectedItem = item
                                activeDialog = ActiveDialog.DELETE
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }

    if (activeDialog == ActiveDialog.PROPERTIES && selectedItem != null) {
        val item = selectedItem!!
        val typeDir = stringResource(R.string.file_manager_prop_type_dir)
        val typeFile = stringResource(R.string.file_manager_prop_type_file)
        val typeSym = stringResource(R.string.file_manager_prop_type_symlink, if (item.isDirectory) typeDir else typeFile)
        AlertDialog(
            onDismissRequest = { activeDialog = ActiveDialog.NONE },
            title = { Text(stringResource(R.string.file_manager_properties_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PropertyRow(stringResource(R.string.file_manager_prop_name), item.name)
                    PropertyRow(stringResource(R.string.file_manager_prop_path), item.path)
                    PropertyRow(stringResource(R.string.file_manager_prop_type), if (item.isSymlink) typeSym else if (item.isDirectory) typeDir else typeFile)
                    if (item.isSymlink && item.symlinkTarget != null) {
                        PropertyRow(stringResource(R.string.file_manager_prop_link_target), "${item.path} -> ${item.symlinkTarget}")
                    }
                    if (!item.isDirectory) PropertyRow(stringResource(R.string.file_manager_prop_size), "${formatFileSize(item.size)} (${item.size} bytes)")
                    if (item.permissions.isNotEmpty()) PropertyRow(stringResource(R.string.file_manager_prop_mode), item.permissions)
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = ActiveDialog.NONE }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    if (activeDialog == ActiveDialog.CREATE_FOLDER) {
        AlertDialog(
            onDismissRequest = { activeDialog = ActiveDialog.NONE },
            title = { Text(stringResource(R.string.file_manager_create_folder_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_manager_create_folder_msg), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogInputValue,
                        onValueChange = { dialogInputValue = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = dialogInputValue.isNotBlank() && !isOperating,
                    onClick = {
                        val name = dialogInputValue.trim()
                        if (name.isNotEmpty() && client != null) {
                            isOperating = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val target = if (currentPath == "/") "/$name" else "$currentPath/$name"
                                runCatching {
                                    val stream = client.open("shell:mkdir -p '$target'")
                                    stream.close()
                                }
                                withContext(Dispatchers.Main) {
                                    isOperating = false
                                    activeDialog = ActiveDialog.NONE
                                    loadDirectory(currentPath)
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.file_manager_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialog = ActiveDialog.NONE }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (activeDialog == ActiveDialog.RENAME && selectedItem != null) {
        val item = selectedItem!!
        AlertDialog(
            onDismissRequest = { activeDialog = ActiveDialog.NONE },
            title = { Text(stringResource(R.string.file_manager_rename_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_manager_rename_msg), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogInputValue,
                        onValueChange = { dialogInputValue = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = dialogInputValue.isNotBlank() && dialogInputValue != item.path && !isOperating,
                    onClick = {
                        val newPath = dialogInputValue.trim()
                        if (newPath.isNotEmpty() && client != null) {
                            isOperating = true
                            coroutineScope.launch(Dispatchers.IO) {
                                runCatching {
                                    val stream = client.open("shell:mv '${item.path}' '$newPath'")
                                    stream.close()
                                }
                                withContext(Dispatchers.Main) {
                                    isOperating = false
                                    activeDialog = ActiveDialog.NONE
                                    loadDirectory(currentPath)
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.file_manager_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialog = ActiveDialog.NONE }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (activeDialog == ActiveDialog.DELETE && selectedItem != null) {
        val item = selectedItem!!
        AlertDialog(
            onDismissRequest = { activeDialog = ActiveDialog.NONE },
            title = { Text(stringResource(R.string.file_manager_delete_title)) },
            text = {
                Text(stringResource(R.string.file_manager_delete_msg, item.name))
            },
            confirmButton = {
                TextButton(
                    enabled = !isOperating,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        if (client != null) {
                            isOperating = true
                            coroutineScope.launch(Dispatchers.IO) {
                                runCatching {
                                    val stream = client.open("shell:rm -rf '${item.path}'")
                                    stream.close()
                                }
                                withContext(Dispatchers.Main) {
                                    isOperating = false
                                    activeDialog = ActiveDialog.NONE
                                    loadDirectory(currentPath)
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.file_manager_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialog = ActiveDialog.NONE }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun BreadcrumbBar(
    currentPath: String,
    onNavigate: (String) -> Unit
) {
    val parts = remember(currentPath) {
        val segments = currentPath.split("/").filter { it.isNotEmpty() }
        val list = mutableListOf("/" to "/")
        var acc = ""
        for (seg in segments) {
            acc += "/$seg"
            list.add(seg to acc)
        }
        list
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        parts.forEachIndexed { index, (label, path) ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (index == parts.lastIndex) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (index == parts.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onNavigate(path) }
                    .padding(vertical = 2.dp, horizontal = 2.dp)
            )
            if (index < parts.lastIndex) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun FileItemRow(
    item: RemoteFileItem,
    onClick: () -> Unit,
    onProperties: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val icon = when {
        item.isParentDir -> Icons.AutoMirrored.Filled.DriveFileMove
        item.hasWarnError -> Icons.Filled.Lock
        item.isDirectory -> Icons.Filled.Folder
        item.name.endsWith(".apk", true) || item.name.endsWith(".zip", true) || item.name.endsWith(".tar", true) -> Icons.Filled.Archive
        item.name.endsWith(".mp4", true) || item.name.endsWith(".mkv", true) || item.name.endsWith(".webm", true) -> Icons.Filled.Movie
        item.name.endsWith(".mp3", true) || item.name.endsWith(".wav", true) || item.name.endsWith(".flac", true) -> Icons.Filled.MusicNote
        item.name.endsWith(".jpg", true) || item.name.endsWith(".png", true) || item.name.endsWith(".webp", true) -> Icons.Filled.Image
        item.name.endsWith(".txt", true) || item.name.endsWith(".json", true) || item.name.endsWith(".xml", true) -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    val iconTint = when {
        item.isParentDir -> MaterialTheme.colorScheme.secondary
        item.hasWarnError -> MaterialTheme.colorScheme.error
        item.isDirectory -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (item.isPendingUpload || item.hasWarnError) Modifier.alpha(0.6f) else Modifier)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.TopStart)
                    )
                    if (item.isSymlink) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.surface, shape = CircleShape)
                                .padding(1.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Shortcut,
                                contentDescription = stringResource(R.string.cd_symlink),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.isParentDir) {
                        Spacer(Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.hasWarnError) {
                                Text(item.warnErrorMsg ?: "Access Denied", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            } else {
                                if (item.isSymlink && item.symlinkTarget != null) {
                                    Text("-> ${item.symlinkTarget}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (!item.isDirectory) {
                                    Text(formatFileSize(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val formattedDate = formatMtime(item.mtime)
                                if (formattedDate.isNotEmpty()) {
                                    Text(formattedDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                if (!item.isParentDir) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_actions))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.file_manager_action_properties)) },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onProperties()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.file_manager_action_download)) },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDownload()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.file_manager_action_rename)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.file_manager_action_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            if (item.isUploading) {
                LinearProgressIndicator(
                    progress = { item.uploadProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            } else if (item.isDownloading) {
                LinearProgressIndicator(
                    progress = { item.downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label: ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatMtime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    return try {
        val instant = java.time.Instant.ofEpochSecond(epochSeconds)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
        formatter.format(instant)
    } catch (_: Exception) {
        ""
    }
}
