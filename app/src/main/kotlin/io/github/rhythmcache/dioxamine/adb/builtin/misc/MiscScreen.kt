package io.github.rhythmcache.dioxamine.adb.builtin.misc

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile
import io.github.rhythmcache.dioxamine.core.executeShell
import io.github.rhythmcache.dioxamine.core.executeShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TileOutput(
    val text: String,
    val isError: Boolean = false
)

enum class MiscDialogType {
    NONE,
    ORIENTATION,
    DENSITY,
    RESOLUTION,
    STAY_AWAKE,
    DEMO_MODE,
    ANIMATION_SCALE,
    BATTERY,
    OPEN_URL
}

private fun escapeShellArg(arg: String): String {
    return "'" + arg.replace("'", "'\\''") + "'"
}

@Composable
fun MiscTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.adb_misc_tile_title),
        description = stringResource(R.string.adb_misc_tile_desc),
        icon = Icons.Filled.MiscellaneousServices,
        enabled = isConnected,
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiscScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = vm.activeClient()
    val activeConn = vm.devices[vm.activeDeviceId]
    val supportsShellV2 = activeConn?.supportsShellV2 ?: true

    // Outputs for each tile (keyed by action identifier)
    val outputs = remember { mutableStateMapOf<String, TileOutput>() }
    val runningActions = remember { mutableStateMapOf<String, Boolean>() }

    // Active Dialog state
    var activeDialog by remember { mutableStateOf(MiscDialogType.NONE) }
    var pendingClearPackage by remember { mutableStateOf<String?>(null) }

    // Dialog input states
    var inputDensity by remember { mutableStateOf("") }
    var currentDensityInfo by remember { mutableStateOf("") }
    var inputResWidth by remember { mutableStateOf("") }
    var inputResHeight by remember { mutableStateOf("") }
    var currentResInfo by remember { mutableStateOf("") }
    var inputUrl by remember { mutableStateOf("") }

    fun executeAction(
        id: String,
        command: String,
        customOutputSuccess: ((String) -> String)? = null
    ) {
        val targetClient = client
        if (targetClient == null) {
            Toast.makeText(context, context.getString(R.string.adb_misc_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        runningActions[id] = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                val result = targetClient.executeShellResult(command, supportsShellV2)
                val isError = !result.isSuccess
                val displayText = if (!isError && customOutputSuccess != null) {
                    customOutputSuccess(result.output)
                } else {
                    result.output.ifBlank { context.getString(R.string.adb_misc_executed_success) }
                }
                withContext(Dispatchers.Main) {
                    outputs[id] = TileOutput(displayText, isError)
                    runningActions[id] = false
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    outputs[id] = TileOutput("Error: ${e.message}", isError = true)
                    runningActions[id] = false
                }
            }
        }
    }

    fun detectForegroundPackage(onResult: (String?) -> Unit) {
        val targetClient = client ?: return
        scope.launch(Dispatchers.IO) {
            // 1. Try dumpsys activity activities (standard on modern Android)
            var dump = runCatching {
                targetClient.executeShell(
                    "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'",
                    supportsShellV2
                )
            }.getOrDefault("")

            // 2. Fallback to dumpsys window
            if (dump.isBlank()) {
                dump = runCatching {
                    targetClient.executeShell(
                        "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
                        supportsShellV2
                    )
                }.getOrDefault("")
            }

            // Extract package name (e.g. u0 com.example.app/...)
            val regex = Regex("""(?:\s|u0\s+|/)([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""")
            var pkg = regex.find(dump)?.groups?.get(1)?.value

            if (pkg == null) {
                val fallbackRegex = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)""")
                pkg = fallbackRegex.findAll(dump)
                    .map { it.value }
                    .firstOrNull { it != "android" && !it.startsWith("com.android.server") && it.contains('.') }
            }

            withContext(Dispatchers.Main) {
                onResult(pkg)
            }
        }
    }

    fun toggleSetting(id: String, table: String, key: String, label: String) {
        val targetClient = client ?: return
        runningActions[id] = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                val current = targetClient.executeShellResult("settings get $table $key", supportsShellV2)
                val currentVal = current.output.trim()
                val nextVal = if (currentVal == "1") "0" else "1"
                val setResult = targetClient.executeShellResult("settings put $table $key $nextVal", supportsShellV2)
                withContext(Dispatchers.Main) {
                    if (setResult.isSuccess) {
                        val stateText = if (nextVal == "1") context.getString(R.string.adb_misc_state_enabled) else context.getString(R.string.adb_misc_state_disabled)
                        outputs[id] = TileOutput("$label: $stateText", isError = false)
                    } else {
                        val err = setResult.output.ifBlank { "Failed to update $label" }
                        outputs[id] = TileOutput("Error: $err", isError = true)
                    }
                    runningActions[id] = false
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    outputs[id] = TileOutput("Error: ${e.message}", isError = true)
                    runningActions[id] = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.adb_misc_tile_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back)
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // -------------------------------------------------------------
            // APP & TASK MANAGEMENT
            // -------------------------------------------------------------
            item {
                SectionHeader(stringResource(R.string.adb_misc_section_app_process))
            }

            item {
                MiscActionCard(
                    id = "kill_app",
                    title = stringResource(R.string.adb_misc_kill_app_title),
                    description = stringResource(R.string.adb_misc_kill_app_desc),
                    icon = Icons.Filled.Close,
                    isRunning = runningActions["kill_app"] == true,
                    output = outputs["kill_app"],
                    onExecute = {
                        runningActions["kill_app"] = true
                        detectForegroundPackage { pkg ->
                            if (pkg != null) {
                                executeAction("kill_app", "am force-stop $pkg") {
                                    "Force stopped package: $pkg"
                                }
                            } else {
                                runningActions["kill_app"] = false
                                outputs["kill_app"] = TileOutput(context.getString(R.string.adb_misc_no_foreground_app), isError = true)
                            }
                        }
                    },
                    onDismissOutput = { outputs.remove("kill_app") }
                )
            }

            item {
                MiscActionCard(
                    id = "clear_app",
                    title = stringResource(R.string.adb_misc_clear_app_title),
                    description = stringResource(R.string.adb_misc_clear_app_desc),
                    icon = Icons.Filled.CleaningServices,
                    isRunning = runningActions["clear_app"] == true,
                    output = outputs["clear_app"],
                    onExecute = {
                        runningActions["clear_app"] = true
                        detectForegroundPackage { pkg ->
                            runningActions["clear_app"] = false
                            if (pkg != null) {
                                pendingClearPackage = pkg
                            } else {
                                outputs["clear_app"] = TileOutput(context.getString(R.string.adb_misc_no_foreground_app), isError = true)
                            }
                        }
                    },
                    onDismissOutput = { outputs.remove("clear_app") }
                )
            }

            // -------------------------------------------------------------
            // DISPLAY & ORIENTATION
            // -------------------------------------------------------------
            item {
                Spacer(Modifier.height(6.dp))
                SectionHeader(stringResource(R.string.adb_misc_section_display))
            }

            item {
                MiscActionCard(
                    id = "orientation",
                    title = stringResource(R.string.adb_misc_orientation_title),
                    description = stringResource(R.string.adb_misc_orientation_desc),
                    icon = Icons.Filled.ScreenRotation,
                    isRunning = runningActions["orientation"] == true,
                    output = outputs["orientation"],
                    onExecute = { activeDialog = MiscDialogType.ORIENTATION },
                    onDismissOutput = { outputs.remove("orientation") }
                )
            }

            item {
                MiscActionCard(
                    id = "auto_rotate",
                    title = stringResource(R.string.adb_misc_auto_rotate_title),
                    description = stringResource(R.string.adb_misc_auto_rotate_desc),
                    icon = Icons.Filled.Sync,
                    isRunning = runningActions["auto_rotate"] == true,
                    output = outputs["auto_rotate"],
                    onExecute = { toggleSetting("auto_rotate", "system", "accelerometer_rotation", context.getString(R.string.adb_misc_auto_rotate_title)) },
                    onDismissOutput = { outputs.remove("auto_rotate") }
                )
            }

            item {
                MiscActionCard(
                    id = "density",
                    title = stringResource(R.string.adb_misc_density_title),
                    description = stringResource(R.string.adb_misc_density_desc),
                    icon = Icons.Filled.AspectRatio,
                    isRunning = runningActions["density"] == true,
                    output = outputs["density"],
                    onExecute = {
                        currentDensityInfo = ""
                        activeDialog = MiscDialogType.DENSITY
                        if (client != null) {
                            scope.launch(Dispatchers.IO) {
                                val current = client.executeShell("wm density", supportsShellV2)
                                withContext(Dispatchers.Main) {
                                    currentDensityInfo = current.trim()
                                }
                            }
                        }
                    },
                    onDismissOutput = { outputs.remove("density") }
                )
            }

            item {
                MiscActionCard(
                    id = "resolution",
                    title = stringResource(R.string.adb_misc_resolution_title),
                    description = stringResource(R.string.adb_misc_resolution_desc),
                    icon = Icons.Filled.FitScreen,
                    isRunning = runningActions["resolution"] == true,
                    output = outputs["resolution"],
                    onExecute = {
                        currentResInfo = ""
                        activeDialog = MiscDialogType.RESOLUTION
                        if (client != null) {
                            scope.launch(Dispatchers.IO) {
                                val current = client.executeShell("wm size", supportsShellV2)
                                withContext(Dispatchers.Main) {
                                    currentResInfo = current.trim()
                                }
                            }
                        }
                    },
                    onDismissOutput = { outputs.remove("resolution") }
                )
            }

            item {
                MiscActionCard(
                    id = "stay_awake",
                    title = stringResource(R.string.adb_misc_stay_awake_title),
                    description = stringResource(R.string.adb_misc_stay_awake_desc),
                    icon = Icons.Filled.Visibility,
                    isRunning = runningActions["stay_awake"] == true,
                    output = outputs["stay_awake"],
                    onExecute = { activeDialog = MiscDialogType.STAY_AWAKE },
                    onDismissOutput = { outputs.remove("stay_awake") }
                )
            }

            // -------------------------------------------------------------
            // SYSTEM UI & NOTIFICATIONS
            // -------------------------------------------------------------
            item {
                Spacer(Modifier.height(6.dp))
                SectionHeader(stringResource(R.string.adb_misc_section_system_ui))
            }

            item {
                MiscActionCard(
                    id = "expand_notifs",
                    title = stringResource(R.string.adb_misc_expand_notifs_title),
                    description = stringResource(R.string.adb_misc_expand_notifs_desc),
                    icon = Icons.Filled.Notifications,
                    isRunning = runningActions["expand_notifs"] == true,
                    output = outputs["expand_notifs"],
                    onExecute = {
                        executeAction("expand_notifs", "cmd statusbar expand-notifications || service call statusbar 1") {
                            "Notification shade expanded."
                        }
                    },
                    onDismissOutput = { outputs.remove("expand_notifs") }
                )
            }

            item {
                MiscActionCard(
                    id = "expand_qs",
                    title = stringResource(R.string.adb_misc_expand_qs_title),
                    description = stringResource(R.string.adb_misc_expand_qs_desc),
                    icon = Icons.Filled.Settings,
                    isRunning = runningActions["expand_qs"] == true,
                    output = outputs["expand_qs"],
                    onExecute = {
                        executeAction("expand_qs", "cmd statusbar expand-settings || service call statusbar 2") {
                            "Quick Settings panel expanded."
                        }
                    },
                    onDismissOutput = { outputs.remove("expand_qs") }
                )
            }

            item {
                MiscActionCard(
                    id = "collapse_statusbar",
                    title = stringResource(R.string.adb_misc_collapse_statusbar_title),
                    description = stringResource(R.string.adb_misc_collapse_statusbar_desc),
                    icon = Icons.Filled.VerticalAlignTop,
                    isRunning = runningActions["collapse_statusbar"] == true,
                    output = outputs["collapse_statusbar"],
                    onExecute = {
                        executeAction("collapse_statusbar", "cmd statusbar collapse || service call statusbar 3") {
                            "Status bar collapsed."
                        }
                    },
                    onDismissOutput = { outputs.remove("collapse_statusbar") }
                )
            }

            // -------------------------------------------------------------
            // DEVELOPER & UI TWEAKS
            // -------------------------------------------------------------
            item {
                Spacer(Modifier.height(6.dp))
                SectionHeader(stringResource(R.string.adb_misc_section_dev_tweaks))
            }

            item {
                MiscActionCard(
                    id = "demo_mode",
                    title = stringResource(R.string.adb_misc_demo_mode_title),
                    description = stringResource(R.string.adb_misc_demo_mode_desc),
                    icon = Icons.Filled.PhotoCamera,
                    isRunning = runningActions["demo_mode"] == true,
                    output = outputs["demo_mode"],
                    onExecute = { activeDialog = MiscDialogType.DEMO_MODE },
                    onDismissOutput = { outputs.remove("demo_mode") }
                )
            }

            item {
                MiscActionCard(
                    id = "show_touches",
                    title = stringResource(R.string.adb_misc_show_touches_title),
                    description = stringResource(R.string.adb_misc_show_touches_desc),
                    icon = Icons.Filled.TouchApp,
                    isRunning = runningActions["show_touches"] == true,
                    output = outputs["show_touches"],
                    onExecute = { toggleSetting("show_touches", "system", "show_touches", context.getString(R.string.adb_misc_show_touches_title)) },
                    onDismissOutput = { outputs.remove("show_touches") }
                )
            }

            item {
                MiscActionCard(
                    id = "pointer_location",
                    title = stringResource(R.string.adb_misc_pointer_location_title),
                    description = stringResource(R.string.adb_misc_pointer_location_desc),
                    icon = Icons.Filled.AdsClick,
                    isRunning = runningActions["pointer_location"] == true,
                    output = outputs["pointer_location"],
                    onExecute = { toggleSetting("pointer_location", "system", "pointer_location", context.getString(R.string.adb_misc_pointer_location_title)) },
                    onDismissOutput = { outputs.remove("pointer_location") }
                )
            }

            item {
                MiscActionCard(
                    id = "anim_scale",
                    title = stringResource(R.string.adb_misc_anim_scale_title),
                    description = stringResource(R.string.adb_misc_anim_scale_desc),
                    icon = Icons.Filled.Speed,
                    isRunning = runningActions["anim_scale"] == true,
                    output = outputs["anim_scale"],
                    onExecute = { activeDialog = MiscDialogType.ANIMATION_SCALE },
                    onDismissOutput = { outputs.remove("anim_scale") }
                )
            }

            // -------------------------------------------------------------
            // BATTERY SIMULATION
            // -------------------------------------------------------------
            item {
                Spacer(Modifier.height(6.dp))
                SectionHeader(stringResource(R.string.adb_misc_section_battery))
            }

            item {
                MiscActionCard(
                    id = "battery_sim",
                    title = stringResource(R.string.adb_misc_battery_sim_title),
                    description = stringResource(R.string.adb_misc_battery_sim_desc),
                    icon = Icons.Filled.BatteryChargingFull,
                    isRunning = runningActions["battery_sim"] == true,
                    output = outputs["battery_sim"],
                    onExecute = { activeDialog = MiscDialogType.BATTERY },
                    onDismissOutput = { outputs.remove("battery_sim") }
                )
            }

            // -------------------------------------------------------------
            // INTENT & URL
            // -------------------------------------------------------------
            item {
                Spacer(Modifier.height(6.dp))
                SectionHeader(stringResource(R.string.adb_misc_section_intent_url))
            }

            item {
                MiscActionCard(
                    id = "open_url",
                    title = stringResource(R.string.adb_misc_open_url_title),
                    description = stringResource(R.string.adb_misc_open_url_desc),
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    isRunning = runningActions["open_url"] == true,
                    output = outputs["open_url"],
                    onExecute = { activeDialog = MiscDialogType.OPEN_URL },
                    onDismissOutput = { outputs.remove("open_url") }
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // =========================================================================
    // CONFIRMATION DIALOG FOR CLEAR ACTIVE APP DATA
    // =========================================================================

    pendingClearPackage?.let { pkg ->
        AlertDialog(
            onDismissRequest = { pendingClearPackage = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.adb_misc_dialog_clear_title))
                }
            },
            text = {
                Text(stringResource(R.string.adb_misc_dialog_clear_msg, pkg))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetPkg = pkg
                        pendingClearPackage = null
                        executeAction("clear_app", "pm clear $targetPkg") {
                            "Cleared all data for package: $targetPkg\n$it"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.adb_misc_dialog_clear_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearPackage = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // =========================================================================
    // DIALOGS FOR TILES THAT REQUIRE PARAMETERS / PRESETS
    // =========================================================================

    when (activeDialog) {
        MiscDialogType.ORIENTATION -> {
            AlertDialog(
                onDismissRequest = { activeDialog = MiscDialogType.NONE },
                title = { Text(stringResource(R.string.adb_misc_dialog_orientation_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            stringResource(R.string.adb_misc_orientation_0) to 0,
                            stringResource(R.string.adb_misc_orientation_90) to 1,
                            stringResource(R.string.adb_misc_orientation_180) to 2,
                            stringResource(R.string.adb_misc_orientation_270) to 3
                        ).forEach { (label, value) ->
                            FilledTonalButton(
                                onClick = {
                                    activeDialog = MiscDialogType.NONE
                                    executeAction("orientation", "settings put system accelerometer_rotation 0 && settings put system user_rotation $value") {
                                        "Screen orientation set to: $label (Rotation: $value)"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(label)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { activeDialog = MiscDialogType.NONE }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        MiscDialogType.DENSITY -> {
            AlertDialog(
                onDismissRequest = { activeDialog = MiscDialogType.NONE },
                title = { Text(stringResource(R.string.adb_misc_dialog_density_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (currentDensityInfo.isNotBlank()) {
                            Text(stringResource(R.string.adb_misc_density_current, currentDensityInfo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.adb_misc_density_reading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Text(stringResource(R.string.adb_misc_density_presets), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(360, 400, 420, 480, 560, 600).forEach { dpi ->
                                SuggestionChip(
                                    onClick = { inputDensity = dpi.toString() },
                                    label = { Text("$dpi") }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = inputDensity,
                            onValueChange = { inputDensity = it },
                            placeholder = { Text(stringResource(R.string.adb_misc_density_placeholder)) },
                            label = { Text(stringResource(R.string.adb_misc_density_custom_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val dpi = inputDensity.trim().toIntOrNull()
                            if (dpi == null || dpi !in 72..1200) {
                                Toast.makeText(context, context.getString(R.string.adb_misc_density_invalid), Toast.LENGTH_SHORT).show()
                            } else {
                                activeDialog = MiscDialogType.NONE
                                executeAction("density", "wm density $dpi && wm density")
                            }
                        }
                    ) {
                        Text(stringResource(R.string.adb_misc_btn_apply))
                    }
                },
                dismissButton = {
                    Row {
                        OutlinedButton(
                            onClick = {
                                activeDialog = MiscDialogType.NONE
                                executeAction("density", "wm density reset && wm density") {
                                    "Reset to physical display density.\n$it"
                                }
                            }
                        ) {
                            Text(stringResource(R.string.adb_misc_btn_reset_default))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { activeDialog = MiscDialogType.NONE }) {
                            Text(stringResource(R.string.btn_cancel))
                        }
                    }
                }
            )
        }

        MiscDialogType.RESOLUTION -> {
            AlertDialog(
                onDismissRequest = { activeDialog = MiscDialogType.NONE },
                title = { Text(stringResource(R.string.adb_misc_dialog_resolution_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (currentResInfo.isNotBlank()) {
                            Text(stringResource(R.string.adb_misc_size_current, currentResInfo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.adb_misc_resolution_reading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = inputResWidth,
                                onValueChange = { inputResWidth = it },
                                label = { Text(stringResource(R.string.adb_misc_resolution_width)) },
                                placeholder = { Text("1080") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = inputResHeight,
                                onValueChange = { inputResHeight = it },
                                label = { Text(stringResource(R.string.adb_misc_resolution_height)) },
                                placeholder = { Text("2400") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val width = inputResWidth.trim().toIntOrNull()
                            val height = inputResHeight.trim().toIntOrNull()
                            if (width == null || height == null || width !in 240..10000 || height !in 240..10000) {
                                Toast.makeText(context, context.getString(R.string.adb_misc_resolution_invalid), Toast.LENGTH_SHORT).show()
                            } else {
                                activeDialog = MiscDialogType.NONE
                                executeAction("resolution", "wm size ${width}x${height} && wm size")
                            }
                        }
                    ) {
                        Text(stringResource(R.string.adb_misc_btn_apply))
                    }
                },
                dismissButton = {
                    Row {
                        OutlinedButton(
                            onClick = {
                                activeDialog = MiscDialogType.NONE
                                executeAction("resolution", "wm size reset && wm size") {
                                    "Reset to physical display size.\n$it"
                                }
                            }
                        ) {
                            Text(stringResource(R.string.adb_misc_btn_reset_default))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { activeDialog = MiscDialogType.NONE }) {
                            Text(stringResource(R.string.btn_cancel))
                        }
                    }
                }
            )
        }

        MiscDialogType.STAY_AWAKE -> {
            AlertDialog(
                onDismissRequest = { activeDialog = MiscDialogType.NONE },
                title = { Text(stringResource(R.string.adb_misc_dialog_stay_awake_title)) },
                text = { Text(stringResource(R.string.adb_misc_dialog_stay_awake_msg)) },
                confirmButton = {
                    Button(
                        onClick = {
                            activeDialog = MiscDialogType.NONE
                            executeAction("stay_awake", "svc power stayon true || settings put global stay_on_while_plugged_in 7") {
                                "Screen will stay awake while connected to USB."
                            }
                        }
                    ) {
                        Text(stringResource(R.string.adb_misc_btn_stay_awake_enable))
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            activeDialog = MiscDialogType.NONE
                            executeAction("stay_awake", "svc power stayon false || settings put global stay_on_while_plugged_in 0") {
                                "Normal screen timeout restored."
                            }
                        }
                    ) {
                        Text(stringResource(R.string.adb_misc_btn_stay_awake_disable))
                    }
                }
            )
        }

        MiscDialogType.DEMO_MODE -> {
            AlertDialog(
                onDismissRequest = { activeDialog = MiscDialogType.NONE },
                title = { Text(stringResource(R.string.adb_misc_dialog_demo_mode_title)) },
                text = { Text(stringResource(R.string.adb_misc_dialog_demo_mode_msg)) },
                confirmButton = {
                    Button(
                        onClick = {
                            activeDialog = MiscDialogType.NONE
                            executeAction(
                                "demo_mode",
                                "settings put global sysui_demo_allowed 1 && am broadcast -a com.android.systemui.demo -e command enter && am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200 && am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false && am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype lte -e level 4 && am broadcast -a com.android.systemui.demo -e command notifications -e visible false"
                            ) {
                                "Demo Mode active: 100% battery, 12:00 clock, notifications hidden."
                            }
                        }
                    ) {
                        Text(stringResource(R.string.adb_misc_btn_demo_mode_enter))
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            activeDialog = MiscDialogType.NONE
                            executeAction("demo_mode", "am broadcast -a com.android.systemui.demo -e command exit") {
                                "Exited Demo Mode. Normal status bar restored."
                            }
                        }
                    ) {
                        Text(stringResource(R.string.adb_misc_btn_demo_mode_exit))
                    }
                }
            )
        }

        MiscDialogType.ANIMATION_SCALE -> {
            AlertDialog(
                onDismissRequest = { activeDialog = MiscDialogType.NONE },
                title = { Text(stringResource(R.string.adb_misc_dialog_anim_scale_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "0x (Instant / Animations Off)" to "0",
                            "0.5x (Fast)" to "0.5",
                            "1.0x (Normal / Default)" to "1.0",
                            "1.5x (Smooth)" to "1.5",
                            "2.0x (Slow Motion)" to "2.0"
                        ).forEach { (label, scale) ->
                            FilledTonalButton(
                                onClick = {
                                    activeDialog = MiscDialogType.NONE
                                    executeAction(
                                        "anim_scale",
                                        "settings put global window_animation_scale $scale && settings put global transition_animation_scale $scale && settings put global animator_duration_scale $scale"
                                    ) {
                                        "Window, transition, and animator scales set to $label ($scale)"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(label)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { activeDialog = MiscDialogType.NONE }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        MiscDialogType.BATTERY -> {
            AlertDialog(
                onDismissRequest = { activeDialog = MiscDialogType.NONE },
                title = { Text(stringResource(R.string.adb_misc_dialog_battery_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.adb_misc_battery_level_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(5, 20, 50, 100).forEach { lvl ->
                                OutlinedButton(
                                    onClick = {
                                        activeDialog = MiscDialogType.NONE
                                        executeAction("battery_sim", "dumpsys battery set level $lvl && dumpsys battery")
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(2.dp)
                                ) {
                                    Text("$lvl%")
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.adb_misc_battery_charging_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    activeDialog = MiscDialogType.NONE
                                    executeAction("battery_sim", "dumpsys battery set status 2 && dumpsys battery set ac 1 && dumpsys battery")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.adb_misc_battery_btn_charging))
                            }
                            FilledTonalButton(
                                onClick = {
                                    activeDialog = MiscDialogType.NONE
                                    executeAction("battery_sim", "dumpsys battery set status 3 && dumpsys battery set ac 0 && dumpsys battery")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.adb_misc_battery_btn_discharging))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            activeDialog = MiscDialogType.NONE
                            executeAction("battery_sim", "dumpsys battery reset && dumpsys battery") {
                                "Battery simulation reset to real hardware state."
                            }
                        }
                    ) {
                        Text(stringResource(R.string.adb_misc_battery_btn_reset))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = MiscDialogType.NONE }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        MiscDialogType.OPEN_URL -> {
            AlertDialog(
                onDismissRequest = { activeDialog = MiscDialogType.NONE },
                title = { Text(stringResource(R.string.adb_misc_dialog_open_url_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.adb_misc_dialog_open_url_msg), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            placeholder = { Text(stringResource(R.string.adb_misc_open_url_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmedUrl = inputUrl.trim()
                            if (trimmedUrl.isNotBlank()) {
                                val formattedUrl = if (!trimmedUrl.startsWith("http://", ignoreCase = true) &&
                                    !trimmedUrl.startsWith("https://", ignoreCase = true) &&
                                    !trimmedUrl.contains("://")
                                ) {
                                    "https://$trimmedUrl"
                                } else trimmedUrl
                                val escapedArg = escapeShellArg(formattedUrl)
                                activeDialog = MiscDialogType.NONE
                                executeAction("open_url", "am start -a android.intent.action.VIEW -d $escapedArg") {
                                    "Opened URL: $formattedUrl"
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.adb_misc_btn_open))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = MiscDialogType.NONE }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        MiscDialogType.NONE -> {}
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun MiscActionCard(
    id: String,
    title: String,
    description: String,
    icon: ImageVector,
    isRunning: Boolean,
    output: TileOutput?,
    onExecute: () -> Unit,
    onDismissOutput: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isRunning, onClick = onExecute)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(
                        onClick = onExecute,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Run $title",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Expandable Output Result Section
            AnimatedVisibility(
                visible = output != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                output?.let { res ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (res.isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (res.isError) stringResource(R.string.adb_misc_status_failed) else stringResource(R.string.adb_misc_status_success),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (res.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = res.text,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.5.sp
                                        ),
                                        color = if (res.isError) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                IconButton(
                                    onClick = onDismissOutput,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.adb_misc_status_dismiss),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
