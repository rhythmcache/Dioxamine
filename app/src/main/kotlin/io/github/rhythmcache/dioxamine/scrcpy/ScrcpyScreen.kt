package io.github.rhythmcache.dioxamine.scrcpy

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.core.Constants
import io.github.rhythmcache.adb.AdbDeviceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class ScrcpyTab { CONFIGURATOR, LOGS, RECORDINGS }
private enum class AddCustomDialogType { MAX_SIZE, FPS, BITRATE, AUDIO_BITRATE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrcpyScreen(
    vm: AdbViewModel,
    onFullScreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activeId = vm.activeDeviceId
    val activeConn = activeId?.let { vm.devices[it] }
    val client = vm.activeClient()
    val isDeviceConnected = activeConn != null && client != null

    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val allowCustomValues = remember(prefs) { prefs.getBoolean("scrcpy_allow_custom_values", false) }

    var customMaxSizes by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_max_sizes", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }
    var customFps by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_fps", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }
    var customBitrates by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_bitrates", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }
    var customAudioBitrates by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_audio_bitrates", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }

    var activeAddDialog by remember { mutableStateOf<AddCustomDialogType?>(null) }
    var dialogInputValue by remember { mutableStateOf("") }
    var dialogErrorMsg by remember { mutableStateOf<String?>(null) }

    var config by remember { mutableStateOf(ScrcpyConfig()) }
    var isMirroring by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var isFullScreen by remember { mutableStateOf(false) }
    var showFloatingNav by remember { mutableStateOf(false) }
    var selectedTab by remember(isDeviceConnected) {
        mutableStateOf(if (isDeviceConnected) ScrcpyTab.CONFIGURATOR else ScrcpyTab.RECORDINGS)
    }

    val canRecord = (!config.videoEnabled || config.videoCodec in listOf("h264", "h265")) &&
            (!config.audioEnabled || config.audioCodec == "aac")

    LaunchedEffect(isDeviceConnected) {
        if (!isDeviceConnected) {
            selectedTab = ScrcpyTab.RECORDINGS
        }
    }

    LaunchedEffect(isFullScreen) {
        onFullScreenChange(isFullScreen)
    }

    LaunchedEffect(allowCustomValues) {
        if (!allowCustomValues) {
            val defaultMaxSizes = listOf(0, 480, 720, 1080)
            val defaultFps = listOf(15, 30, 60)
            val defaultBitrates = listOf(2, 4, 8)
            val defaultAudioBitrates = listOf(64, 128, 192, 256, 320)

            var newConfig = config
            if (config.maxSize !in defaultMaxSizes) newConfig = newConfig.copy(maxSize = 1080)
            if (config.maxFps !in defaultFps) newConfig = newConfig.copy(maxFps = 60)
            if (config.bitRateMbps !in defaultBitrates) newConfig = newConfig.copy(bitRateMbps = 8)
            if (config.audioBitRateKbps !in defaultAudioBitrates) newConfig = newConfig.copy(audioBitRateKbps = 128)
            config = newConfig
        }
    }

    var torchOn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var videoWidth by remember { mutableStateOf(486) }
    var videoHeight by remember { mutableStateOf(1080) }

    var activeSession by remember { mutableStateOf<ScrcpySession?>(null) }

    var discoveredCameras by remember { mutableStateOf<List<CameraDevice>>(emptyList()) }
    var isDiscoveringCameras by remember { mutableStateOf(false) }

    val apiLevel = activeConn?.apiLevel ?: 30
    val supportsAudio = apiLevel >= 30
    val supportsCamera = apiLevel >= 31

    LaunchedEffect(supportsCamera) {
        if (!supportsCamera && config.videoSource == "camera") {
            config = config.copy(videoSource = "display")
        }
    }

    LaunchedEffect(activeId, config.videoSource) {
        if (supportsCamera && config.videoSource == "camera" && client != null && discoveredCameras.isEmpty() && !isDiscoveringCameras) {
            isDiscoveringCameras = true
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("scrcpy-server.jar").use { input ->
                        client.sync.push(input, "${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar")
                    }
                    val stream = client.open("shell:CLASSPATH=${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 log_level=info list_camera_sizes=true")
                    val buf = ByteArray(4096)
                    val sb = StringBuilder()
                    while (true) {
                        val n = stream.read(buf)
                        if (n == -1) break
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                    }
                    stream.close()
                    ScrcpyCameraParser.parse(sb.toString())
                }.onSuccess { parsed ->
                    withContext(Dispatchers.Main) {
                        discoveredCameras = parsed
                        isDiscoveringCameras = false
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        isDiscoveringCameras = false
                    }
                }
            }
        }
    }

    val isModeSupported = activeConn == null || activeConn.mode !in setOf(AdbDeviceMode.SIDELOAD, AdbDeviceMode.RECOVERY, AdbDeviceMode.RESCUE)
    val unsupportedModeName = when (activeConn?.mode) {
        AdbDeviceMode.SIDELOAD -> "Sideload"
        AdbDeviceMode.RECOVERY -> "Recovery"
        AdbDeviceMode.RESCUE -> "Rescue"
        else -> "this"
    }

    fun stopMirroring() {
        activeSession?.stop()
        activeSession = null
        isMirroring = false
        isFullScreen = false
        isRecording = false
    }

    DisposableEffect(Unit) {
        onDispose {
            stopMirroring()
            onFullScreenChange(false)
        }
    }

    fun startMirroring(holder: SurfaceHolder? = null) {
        val activeClient = client ?: return
        val validationErrors = config.validate(apiLevel)
        if (validationErrors.isNotEmpty()) {
            errorMessage = context.getString(R.string.scrcpy_invalid_config_prefix) + validationErrors.joinToString("\n") { "\u2022 $it" }
            isMirroring = false
            return
        }

        val session = ScrcpySession(
            context = context,
            client = activeClient,
            config = config,
            onDimensions = { w, h ->
                videoWidth = w
                videoHeight = h
            },
            onError = { msg ->
                errorMessage = msg
                stopMirroring()
            },
            onRecordingStateChanged = { recording ->
                isRecording = recording
            }
        )
        activeSession = session
        session.start(holder)
    }

    fun toggleRecording() {
        val session = activeSession ?: return
        if (isRecording) {
            session.stopRecording()
        } else {
            session.startRecording()
        }
    }

    val fixedPlayerHeight = 280.dp
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = if (isFullScreen) {
                    Modifier.fillMaxSize().background(Color.Black)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .height(fixedPlayerHeight)
                        .background(Color.Black)
                },
                contentAlignment = Alignment.Center
            ) {
                if (isMirroring) {
                    if (config.videoEnabled) {
                        ScrcpyVideoPlayer(
                            modifier = Modifier.fillMaxSize(),
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            isFullScreen = isFullScreen,
                            showFloatingNav = showFloatingNav,
                            bindVolumeKeys = config.bindVolumeKeys,
                            videoSourceIsCamera = config.videoSource == "camera",
                            torchOn = torchOn,
                            isRecording = isRecording,
                            canRecord = canRecord,
                            onToggleRecord = { toggleRecording() },
                            onToggleTorch = {
                                torchOn = !torchOn
                                activeSession?.sendCameraSetTorch(torchOn)
                            },
                            onToggleFullScreen = { isFullScreen = !isFullScreen },
                            onStop = { stopMirroring() },
                            onNavBack = { activeSession?.sendNavBack() },
                            onNavHome = { activeSession?.sendNavHome() },
                            onNavRecents = { activeSession?.sendNavRecents() },
                            onKeyEvent = { action, keyCode ->
                                activeSession?.sendKeycode(action, keyCode)
                            },
                            onTouchEvent = { action, x, y, vw, vh ->
                                if (config.videoSource != "camera") {
                                    activeSession?.sendTouchEvent(action, x, y, vw, vh)
                                }
                            },
                            onSurfaceCreated = { holder ->
                                val existingSession = activeSession
                                if (existingSession != null) {
                                    existingSession.setSurface(holder.surface)
                                } else {
                                    startMirroring(holder)
                                }
                            }
                        )
                    } else {
                        ScrcpyAudioOnlyPlayer(
                            modifier = Modifier.fillMaxSize(),
                            config = config,
                            isRecording = isRecording,
                            canRecord = canRecord,
                            onToggleRecord = { toggleRecording() },
                            onStop = { stopMirroring() }
                        )
                    }
                } else if (errorMessage != null) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.scrcpy_mirroring_failed),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { errorMessage = null }) {
                            Text(stringResource(R.string.btn_dismiss), color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (!isDeviceConnected) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ScreenShare,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.scrcpy_no_device_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.scrcpy_no_device_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else if (!isModeSupported) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ScreenShare,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.scrcpy_mode_not_supported, unsupportedModeName),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.scrcpy_mode_not_supported_desc, unsupportedModeName),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ScreenShare,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.scrcpy_mirroring_idle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            if (!isFullScreen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(stringResource(R.string.scrcpy_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        Text(
                            if (activeConn?.androidVersion != null) {
                                stringResource(R.string.scrcpy_target_version, activeConn.label, activeConn.androidVersion)
                            } else if (activeConn != null) {
                                stringResource(R.string.scrcpy_target, activeConn.label)
                            } else {
                                stringResource(R.string.no_devices_connected)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(Modifier.height(8.dp))

                        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                            Tab(
                                selected = selectedTab == ScrcpyTab.CONFIGURATOR,
                                onClick = { if (isDeviceConnected) selectedTab = ScrcpyTab.CONFIGURATOR },
                                enabled = isDeviceConnected,
                                text = { Text(stringResource(R.string.scrcpy_tab_configurator)) }
                            )
                            Tab(
                                selected = selectedTab == ScrcpyTab.LOGS,
                                onClick = { if (isDeviceConnected) selectedTab = ScrcpyTab.LOGS },
                                enabled = isDeviceConnected,
                                text = { Text(stringResource(R.string.scrcpy_tab_logs)) }
                            )
                            Tab(
                                selected = selectedTab == ScrcpyTab.RECORDINGS,
                                onClick = { selectedTab = ScrcpyTab.RECORDINGS },
                                text = { Text(stringResource(R.string.scrcpy_tab_recordings)) }
                            )
                        }

                        when (selectedTab) {
                            ScrcpyTab.CONFIGURATOR -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(16.dp)
                                ) {
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            VideoSettings(
                                                config = config,
                                                isMirroring = isMirroring,
                                                supportsAudio = supportsAudio,
                                                supportsCamera = supportsCamera,
                                                apiLevel = apiLevel,
                                                allowCustomValues = allowCustomValues,
                                                customMaxSizes = customMaxSizes,
                                                customFps = customFps,
                                                customBitrates = customBitrates,
                                                discoveredCameras = discoveredCameras,
                                                isDiscoveringCameras = isDiscoveringCameras,
                                                onConfigChange = { config = it },
                                                onOpenAddDialog = { type ->
                                                    activeAddDialog = type
                                                    dialogInputValue = ""
                                                    dialogErrorMsg = null
                                                }
                                            )

                                            Spacer(Modifier.height(12.dp))
                                            HorizontalDivider()
                                            Spacer(Modifier.height(12.dp))

                                            AudioSettings(
                                                config = config,
                                                isMirroring = isMirroring,
                                                supportsAudio = supportsAudio,
                                                apiLevel = apiLevel,
                                                allowCustomValues = allowCustomValues,
                                                customAudioBitrates = customAudioBitrates,
                                                onConfigChange = { config = it },
                                                onOpenAddDialog = { type ->
                                                    activeAddDialog = type
                                                    dialogInputValue = ""
                                                    dialogErrorMsg = null
                                                }
                                            )

                                            Spacer(Modifier.height(12.dp))
                                            HorizontalDivider()
                                            Spacer(Modifier.height(12.dp))

                                            DisplaySettings(
                                                config = config,
                                                isMirroring = isMirroring,
                                                showFloatingNav = showFloatingNav,
                                                onShowFloatingNavChange = { showFloatingNav = it },
                                                onConfigChange = { config = it }
                                            )

                                            Spacer(Modifier.height(16.dp))

                                            if (errorMessage != null) {
                                                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                                Spacer(Modifier.height(8.dp))
                                            }

                                            Button(
                                                onClick = {
                                                    errorMessage = null
                                                    if (isMirroring) {
                                                        stopMirroring()
                                                    } else {
                                                        if (config.videoEnabled) {
                                                            isMirroring = true
                                                        } else {
                                                            isMirroring = true
                                                            startMirroring(null)
                                                        }
                                                    }
                                                },
                                                enabled = isDeviceConnected && isModeSupported,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(if (isMirroring) Icons.Filled.Stop else Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(if (isMirroring) stringResource(R.string.scrcpy_stop_mirroring) else stringResource(R.string.scrcpy_start_mirroring))
                                            }
                                        }
                                    }
                                }
                            }
                            ScrcpyTab.LOGS -> {
                                ScrcpyLogViewer(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    activeConn = activeConn,
                                    config = config
                                )
                            }
                            ScrcpyTab.RECORDINGS -> {
                                ScrcpyRecordingsViewer(
                                    modifier = Modifier.fillMaxSize(),
                                    isRecording = isRecording
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (activeAddDialog != null) {
        val dialogType = activeAddDialog!!
        val titleText = when (dialogType) {
            AddCustomDialogType.MAX_SIZE -> stringResource(R.string.dialog_add_max_size_title)
            AddCustomDialogType.FPS -> stringResource(R.string.dialog_add_fps_title)
            AddCustomDialogType.BITRATE -> stringResource(R.string.dialog_add_bitrate_title)
            AddCustomDialogType.AUDIO_BITRATE -> stringResource(R.string.dialog_add_audio_bitrate_title)
        }
        val msgText = when (dialogType) {
            AddCustomDialogType.MAX_SIZE -> stringResource(R.string.dialog_add_max_size_msg)
            AddCustomDialogType.FPS -> stringResource(R.string.dialog_add_fps_msg)
            AddCustomDialogType.BITRATE -> stringResource(R.string.dialog_add_bitrate_msg)
            AddCustomDialogType.AUDIO_BITRATE -> stringResource(R.string.dialog_add_audio_bitrate_msg)
        }
        val invalidNumMsg = stringResource(R.string.err_invalid_positive_number)

        AlertDialog(
            onDismissRequest = { activeAddDialog = null },
            title = { Text(titleText) },
            text = {
                Column {
                    Text(msgText, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogInputValue,
                        onValueChange = {
                            dialogInputValue = it
                            dialogErrorMsg = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (dialogErrorMsg != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(dialogErrorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val num = dialogInputValue.toIntOrNull()
                        if (num == null || num <= 0) {
                            dialogErrorMsg = invalidNumMsg
                        } else {
                            when (dialogType) {
                                AddCustomDialogType.MAX_SIZE -> {
                                    val newList = (customMaxSizes + num).distinct().sorted()
                                    customMaxSizes = newList
                                    prefs.edit().putStringSet("scrcpy_custom_max_sizes", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(maxSize = num)
                                }
                                AddCustomDialogType.FPS -> {
                                    val newList = (customFps + num).distinct().sorted()
                                    customFps = newList
                                    prefs.edit().putStringSet("scrcpy_custom_fps", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(maxFps = num)
                                }
                                AddCustomDialogType.BITRATE -> {
                                    val newList = (customBitrates + num).distinct().sorted()
                                    customBitrates = newList
                                    prefs.edit().putStringSet("scrcpy_custom_bitrates", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(bitRateMbps = num)
                                }
                                AddCustomDialogType.AUDIO_BITRATE -> {
                                    val newList = (customAudioBitrates + num).distinct().sorted()
                                    customAudioBitrates = newList
                                    prefs.edit().putStringSet("scrcpy_custom_audio_bitrates", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(audioBitRateKbps = num)
                                }
                            }
                            activeAddDialog = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.btn_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { activeAddDialog = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun VideoSourceSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    supportsCamera: Boolean,
    apiLevel: Int,
    onConfigChange: (ScrcpyConfig) -> Unit
) {
    Text(stringResource(R.string.section_video_source), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            stringResource(R.string.video_source_screen) to "display",
            stringResource(R.string.video_source_camera) to "camera"
        ).forEach { (label, value) ->
            val isCamera = value == "camera"
            FilterChip(
                selected = config.videoSource == value,
                onClick = {
                    if (!isCamera || supportsCamera) {
                        onConfigChange(
                            config.copy(
                                videoSource = value,
                                maxSize = if (value == "camera" && config.maxSize == 0) 1080 else config.maxSize
                            )
                        )
                    }
                },
                enabled = !isMirroring && (!isCamera || supportsCamera),
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    if (!supportsCamera) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.camera_source_subtitle_req, apiLevel),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun CameraSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    discoveredCameras: List<CameraDevice>,
    isDiscovering: Boolean,
    onConfigChange: (ScrcpyConfig) -> Unit
) {
    if (config.videoSource != "camera") return

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.section_camera), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))

    if (isDiscovering) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.camera_discovering), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
    }

    if (discoveredCameras.isNotEmpty()) {
        Text(stringResource(R.string.camera_selector), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            discoveredCameras.forEach { cam ->
                val label = stringResource(R.string.camera_label_format, cam.id, cam.facing)
                FilterChip(
                    selected = config.cameraId == cam.id || (config.cameraId == null && config.cameraFacing == cam.facing),
                    onClick = {
                        onConfigChange(
                            config.copy(
                                cameraId = cam.id,
                                cameraFacing = cam.facing
                            )
                        )
                    },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        val activeCam = discoveredCameras.find { it.id == config.cameraId } ?: discoveredCameras.find { it.facing == config.cameraFacing } ?: discoveredCameras.first()

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.camera_resolution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val allSizes = (activeCam.standardSizes + activeCam.highSpeedOptions.map { it.resolution }).distinct()
            allSizes.forEach { sizeStr ->
                FilterChip(
                    selected = config.cameraSize == sizeStr,
                    onClick = {
                        onConfigChange(config.copy(cameraSize = sizeStr))
                    },
                    enabled = !isMirroring,
                    label = { Text(sizeStr, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.camera_frame_rate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val highSpeedForSize = activeCam.highSpeedOptions.find { it.resolution == config.cameraSize }
            val availableFps = if (highSpeedForSize != null) {
                (activeCam.fpsRange + highSpeedForSize.fpsList).distinct().sortedDescending()
            } else if (activeCam.fpsRange.isNotEmpty()) {
                activeCam.fpsRange.sortedDescending()
            } else {
                listOf(60, 30, 15)
            }

            availableFps.forEach { fps ->
                FilterChip(
                    selected = config.cameraFps == fps,
                    onClick = {
                        val isHighSpeed = fps >= 120
                        onConfigChange(
                            config.copy(
                                cameraFps = fps,
                                cameraHighSpeed = isHighSpeed,
                                videoCodec = if (isHighSpeed) "h264" else config.videoCodec
                            )
                        )
                    },
                    enabled = !isMirroring,
                    label = { Text(stringResource(R.string.label_fps_format, fps), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                stringResource(R.string.camera_facing_back) to "back",
                stringResource(R.string.camera_facing_front) to "front",
                stringResource(R.string.camera_facing_external) to "external"
            ).forEach { (label, value) ->
                FilterChip(
                    selected = config.cameraFacing == value,
                    onClick = { onConfigChange(config.copy(cameraFacing = value, cameraId = null)) },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

@Composable
private fun VideoSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    supportsAudio: Boolean,
    supportsCamera: Boolean,
    apiLevel: Int,
    allowCustomValues: Boolean,
    customMaxSizes: List<Int>,
    customFps: List<Int>,
    customBitrates: List<Int>,
    discoveredCameras: List<CameraDevice>,
    isDiscoveringCameras: Boolean,
    onConfigChange: (ScrcpyConfig) -> Unit,
    onOpenAddDialog: (AddCustomDialogType) -> Unit
) {
    Text(stringResource(R.string.section_video), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.video_enable_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.video_enable_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = config.videoEnabled,
            onCheckedChange = { isChecked ->
                val newConfig = if (!isChecked && supportsAudio && !config.audioEnabled) {
                    config.copy(videoEnabled = false, audioEnabled = true)
                } else {
                    config.copy(videoEnabled = isChecked)
                }
                onConfigChange(newConfig)
            },
            enabled = !isMirroring
        )
    }

    if (!config.videoEnabled) return

    Spacer(Modifier.height(8.dp))

    VideoSourceSettings(
        config = config,
        isMirroring = isMirroring,
        supportsCamera = supportsCamera,
        apiLevel = apiLevel,
        onConfigChange = onConfigChange
    )

    CameraSettings(
        config = config,
        isMirroring = isMirroring,
        discoveredCameras = discoveredCameras,
        isDiscovering = isDiscoveringCameras,
        onConfigChange = onConfigChange
    )

    if (config.videoSource != "camera") {
        Text(stringResource(R.string.label_resolution), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val defaultResolutions = listOf(
                stringResource(R.string.res_1080p) to 1080,
                stringResource(R.string.res_720p) to 720,
                stringResource(R.string.res_480p) to 480,
                stringResource(R.string.res_auto) to 0
            )
            defaultResolutions.forEach { (label, value) ->
                FilterChip(
                    selected = config.maxSize == value,
                    onClick = { onConfigChange(config.copy(maxSize = value)) },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
            if (allowCustomValues) {
                customMaxSizes.forEach { customVal ->
                    FilterChip(
                        selected = config.maxSize == customVal,
                        onClick = { onConfigChange(config.copy(maxSize = customVal)) },
                        enabled = !isMirroring,
                        label = { Text("${customVal}p", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { onOpenAddDialog(AddCustomDialogType.MAX_SIZE) },
                    enabled = !isMirroring,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_frame_rate), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val defaultFps = listOf(60, 30, 15)
            defaultFps.forEach { fps ->
                FilterChip(
                    selected = config.maxFps == fps,
                    onClick = { onConfigChange(config.copy(maxFps = fps)) },
                    enabled = !isMirroring,
                    label = { Text(stringResource(R.string.label_fps_format, fps), style = MaterialTheme.typography.labelSmall) }
                )
            }
            if (allowCustomValues) {
                customFps.forEach { customVal ->
                    FilterChip(
                        selected = config.maxFps == customVal,
                        onClick = { onConfigChange(config.copy(maxFps = customVal)) },
                        enabled = !isMirroring,
                        label = { Text(stringResource(R.string.label_fps_format, customVal), style = MaterialTheme.typography.labelSmall) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { onOpenAddDialog(AddCustomDialogType.FPS) },
                    enabled = !isMirroring,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    Text(stringResource(R.string.label_bitrate), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(8, 4, 2).forEach { mbps ->
            FilterChip(
                selected = config.bitRateMbps == mbps,
                onClick = { onConfigChange(config.copy(bitRateMbps = mbps)) },
                enabled = !isMirroring,
                label = { Text(stringResource(R.string.label_mbps_format, mbps), style = MaterialTheme.typography.labelSmall) }
            )
        }
        if (allowCustomValues) {
            customBitrates.forEach { customVal ->
                FilterChip(
                    selected = config.bitRateMbps == customVal,
                    onClick = { onConfigChange(config.copy(bitRateMbps = customVal)) },
                    enabled = !isMirroring,
                    label = { Text(stringResource(R.string.label_mbps_format, customVal), style = MaterialTheme.typography.labelSmall) }
                )
            }
            FilterChip(
                selected = false,
                onClick = { onOpenAddDialog(AddCustomDialogType.BITRATE) },
                enabled = !isMirroring,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.label_codec), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            stringResource(R.string.codec_h264) to "h264",
            stringResource(R.string.codec_h265) to "h265",
            stringResource(R.string.codec_av1) to "av1"
        ).forEach { (label, value) ->
            val isEnabled = !isMirroring && (!config.cameraHighSpeed || value == "h264")
            FilterChip(
                selected = config.videoCodec == value,
                onClick = { onConfigChange(config.copy(videoCodec = value)) },
                enabled = isEnabled,
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    if (config.videoCodec == "av1") {
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.scrcpy_recording_codec_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.label_orientation), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            stringResource(R.string.orient_normal) to "0",
            stringResource(R.string.orient_90) to "90",
            stringResource(R.string.orient_180) to "180",
            stringResource(R.string.orient_270) to "270",
            stringResource(R.string.orient_flip_0) to "flip0",
            stringResource(R.string.orient_flip_90) to "flip90",
            stringResource(R.string.orient_flip_180) to "flip180",
            stringResource(R.string.orient_flip_270) to "flip270"
        ).forEach { (label, value) ->
            FilterChip(
                selected = config.captureOrientation == value || (config.captureOrientation == null && value == "0"),
                onClick = { onConfigChange(config.copy(captureOrientation = if (value == "0") null else value)) },
                enabled = !isMirroring,
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun AudioSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    supportsAudio: Boolean,
    apiLevel: Int,
    allowCustomValues: Boolean,
    customAudioBitrates: List<Int>,
    onConfigChange: (ScrcpyConfig) -> Unit,
    onOpenAddDialog: (AddCustomDialogType) -> Unit
) {
    Text(stringResource(R.string.section_audio), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.audio_enable_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (supportsAudio) stringResource(R.string.audio_enable_subtitle_ok)
                else stringResource(R.string.audio_enable_subtitle_req, apiLevel),
                style = MaterialTheme.typography.labelSmall,
                color = if (supportsAudio) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
        Switch(
            checked = config.audioEnabled && supportsAudio,
            onCheckedChange = { onConfigChange(config.copy(audioEnabled = it)) },
            enabled = supportsAudio && !isMirroring
        )
    }

    if (config.audioEnabled && supportsAudio) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_audio_source), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                stringResource(R.string.audio_source_device) to "output",
                stringResource(R.string.audio_source_mic) to "mic"
            ).forEach { (label, value) ->
                FilterChip(
                    selected = config.effectiveAudioSource() == value ||
                        (value == "output" && config.effectiveAudioSource() == "playback"),
                    onClick = { onConfigChange(config.copy(audioSource = value, audioSourceExplicit = true)) },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_audio_format), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                stringResource(R.string.codec_opus) to "opus",
                stringResource(R.string.codec_aac) to "aac",
                stringResource(R.string.codec_flac) to "flac",
                stringResource(R.string.codec_raw_pcm) to "raw"
            ).forEach { (label, value) ->
                FilterChip(
                    selected = config.audioCodec == value,
                    onClick = { onConfigChange(config.copy(audioCodec = value)) },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        if (config.audioCodec != "aac") {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.scrcpy_recording_codec_warning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (config.audioCodec == "opus" || config.audioCodec == "aac") {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.label_audio_bitrate), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(64, 128, 192, 256, 320).forEach { kbps ->
                    FilterChip(
                        selected = config.audioBitRateKbps == kbps,
                        onClick = { onConfigChange(config.copy(audioBitRateKbps = kbps)) },
                        enabled = !isMirroring,
                        label = { Text("${kbps} Kbps", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                customAudioBitrates.forEach { customVal ->
                    FilterChip(
                        selected = config.audioBitRateKbps == customVal,
                        onClick = { onConfigChange(config.copy(audioBitRateKbps = customVal)) },
                        enabled = !isMirroring,
                        label = { Text("${customVal} Kbps", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                if (allowCustomValues) {
                    FilterChip(
                        selected = false,
                        onClick = { onOpenAddDialog(AddCustomDialogType.AUDIO_BITRATE) },
                        enabled = !isMirroring,
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }
        }

        val showAudioDup = config.effectiveAudioSource() != "mic"
        if (showAudioDup) {
            val supportsAudioDup = apiLevel >= 33
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.audio_dup_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (supportsAudioDup) stringResource(R.string.audio_dup_subtitle_ok)
                        else stringResource(R.string.audio_dup_subtitle_req),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (supportsAudioDup) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
                Switch(
                    checked = config.audioDup && supportsAudioDup,
                    onCheckedChange = { isChecked ->
                        onConfigChange(config.copy(audioDup = isChecked))
                    },
                    enabled = supportsAudioDup && !isMirroring
                )
            }
        }
    }
}

@Composable
private fun DisplaySettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    showFloatingNav: Boolean,
    onShowFloatingNavChange: (Boolean) -> Unit,
    onConfigChange: (ScrcpyConfig) -> Unit
) {
    Text(stringResource(R.string.section_display), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.display_screen_off_title), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.display_screen_off_subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = config.turnScreenOff,
            onCheckedChange = { onConfigChange(config.copy(turnScreenOff = it)) },
            enabled = !isMirroring
        )
    }

    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.display_floating_nav_title), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.display_floating_nav_subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = showFloatingNav,
            onCheckedChange = { onShowFloatingNavChange(it) }
        )
    }

    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.display_bind_volume_title), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.display_bind_volume_subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = config.bindVolumeKeys,
            onCheckedChange = { onConfigChange(config.copy(bindVolumeKeys = it)) }
        )
    }
}

@Composable
private fun FloatingVerticalNavBar(
    modifier: Modifier = Modifier,
    onNavBack: () -> Unit,
    onNavHome: () -> Unit,
    onNavRecents: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            },
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.75f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onNavBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_nav_back),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onNavHome,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.RadioButtonUnchecked,
                    contentDescription = stringResource(R.string.cd_nav_home),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onNavRecents,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CropSquare,
                    contentDescription = stringResource(R.string.cd_nav_recents),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ScrcpyVideoPlayer(
    modifier: Modifier,
    videoWidth: Int,
    videoHeight: Int,
    isFullScreen: Boolean,
    showFloatingNav: Boolean,
    bindVolumeKeys: Boolean,
    videoSourceIsCamera: Boolean,
    torchOn: Boolean,
    isRecording: Boolean,
    canRecord: Boolean,
    onToggleRecord: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onStop: () -> Unit,
    onNavBack: () -> Unit,
    onNavHome: () -> Unit,
    onNavRecents: () -> Unit,
    onKeyEvent: (action: Int, keyCode: Int) -> Unit,
    onTouchEvent: (action: Int, x: Float, y: Float, vw: Int, vh: Int) -> Unit,
    onSurfaceCreated: (SurfaceHolder) -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { keyEvent ->
                if (bindVolumeKeys) {
                    val nativeEvent = keyEvent.nativeKeyEvent
                    val keyCode = nativeEvent.keyCode
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                        keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                    ) {
                        val action = when (nativeEvent.action) {
                            KeyEvent.ACTION_DOWN -> 0
                            KeyEvent.ACTION_UP -> 1
                            else -> -1
                        }
                        if (action != -1) {
                            onKeyEvent(action, keyCode)
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        val aspectRatio = (videoWidth.toFloat() / maxOf(videoHeight, 1).toFloat()).coerceIn(0.2f, 5.0f)

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    if (bindVolumeKeys) {
                        requestFocus()
                    }
                    setOnKeyListener { _, keyCode, event ->
                        if (bindVolumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                                               keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                                               keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)) {
                            val action = when (event.action) {
                                KeyEvent.ACTION_DOWN -> 0
                                KeyEvent.ACTION_UP -> 1
                                else -> -1
                            }
                            if (action != -1) {
                                onKeyEvent(action, keyCode)
                            }
                            true
                        } else {
                            false
                        }
                    }
                    setOnTouchListener { view, event ->
                        if (bindVolumeKeys) {
                            view.requestFocus()
                        }
                        val action = when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> 0
                            MotionEvent.ACTION_UP -> 1
                            MotionEvent.ACTION_MOVE -> 2
                            else -> -1
                        }
                        if (action != -1) {
                            onTouchEvent(action, event.x, event.y, view.width, view.height)
                        }
                        true
                    }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceCreated(holder)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {}
                    })
                }
            },
            update = { /* view identity persists across recompositions */ },
            modifier = Modifier.fillMaxHeight().aspectRatio(aspectRatio)
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .then(if (isFullScreen) Modifier.statusBarsPadding() else Modifier)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canRecord) {
                IconButton(
                    onClick = onToggleRecord,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isRecording) Color.Red.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f)
                    )
                ) {
                    Icon(
                        if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = stringResource(if (isRecording) R.string.scrcpy_recording_stopped else R.string.scrcpy_recording_started),
                        tint = if (isRecording) Color.White else Color.Red
                    )
                }
            }
            if (videoSourceIsCamera) {
                IconButton(
                    onClick = onToggleTorch,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = stringResource(R.string.cd_toggle_torch),
                        tint = if (torchOn) Color.Yellow else Color.White
                    )
                }
            }
            IconButton(
                onClick = onToggleFullScreen,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(
                    if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.cd_toggle_fullscreen),
                    tint = Color.White
                )
            }
            IconButton(
                onClick = onStop,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_stop_mirroring), tint = Color.White)
            }
        }

        if (showFloatingNav) {
            FloatingVerticalNavBar(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(16.dp),
                onNavBack = onNavBack,
                onNavHome = onNavHome,
                onNavRecents = onNavRecents
            )
        }
    }
}

@Composable
private fun ScrcpyAudioOnlyPlayer(
    modifier: Modifier = Modifier,
    config: ScrcpyConfig,
    isRecording: Boolean,
    canRecord: Boolean,
    onToggleRecord: () -> Unit,
    onStop: () -> Unit
) {
    Box(
        modifier = modifier.background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.scrcpy_audio_only_banner),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.scrcpy_audio_only_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text(config.audioCodec.uppercase()) }
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text("${config.audioBitRateKbps} Kbps") }
                )
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(if (config.effectiveAudioSource() == "mic") stringResource(R.string.audio_source_mic) else stringResource(R.string.audio_source_device))
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canRecord) {
                IconButton(
                    onClick = onToggleRecord,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isRecording) Color.Red.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f)
                    )
                ) {
                    Icon(
                        if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = stringResource(if (isRecording) R.string.scrcpy_recording_stopped else R.string.scrcpy_recording_started),
                        tint = if (isRecording) Color.White else Color.Red
                    )
                }
            }
            IconButton(
                onClick = onStop,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_stop_mirroring), tint = Color.White)
            }
        }
    }
}
