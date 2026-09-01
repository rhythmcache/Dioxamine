package io.github.rhythmcache.dioxamine.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import io.github.rhythmcache.dioxamine.BuildConfig
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.AppTheme
import io.github.rhythmcache.dioxamine.core.DioxForegroundService
import io.github.rhythmcache.dioxamine.plugin.PermissionPolicy
import io.github.rhythmcache.dioxamine.plugin.PluginManifest
import io.github.rhythmcache.dioxamine.plugin.PluginPermission
import io.github.rhythmcache.dioxamine.plugin.PluginPermissionStore
import io.github.rhythmcache.dioxamine.plugin.PluginRepository
import kotlinx.coroutines.launch
import java.util.zip.ZipOutputStream

@Composable
fun SettingsScreen(vm: AdbViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var themeMode by remember {
        mutableStateOf(
            runCatching { AppTheme.valueOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM") }
                .getOrDefault(AppTheme.SYSTEM)
        )
    }

    var useMonet by remember {
        mutableStateOf(prefs.getBoolean("use_monet", false))
    }

    var loggingEnabled by remember {
        mutableStateOf(prefs.getBoolean("app_logging_enabled", true))
    }

    LaunchedEffect(loggingEnabled) {
        AppLogger.enabled = loggingEnabled
    }

    var allowCustomValues by remember {
        mutableStateOf(prefs.getBoolean("scrcpy_allow_custom_values", false))
    }
    var autoRecord by remember {
        mutableStateOf(prefs.getBoolean("scrcpy_auto_record", false))
    }

    var themeExpanded by remember { mutableStateOf(false) }
    var scrcpyExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var keyExpanded by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }
    var pluginExpanded by remember { mutableStateOf(false) }
    var miscExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }
    var keepAlive by remember {
        mutableStateOf(prefs.getBoolean("keep_alive_enabled", false))
    }

    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "keep_alive_enabled") {
                keepAlive = p.getBoolean("keep_alive_enabled", false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var pluginWebViewDebug by remember {
        mutableStateOf(prefs.getBoolean("plugin_webview_debug", false))
    }
    var showPluginPermissionsDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val permissionStore = remember { PluginPermissionStore(context.applicationContext) }
    val pluginRepo = remember { PluginRepository(context.applicationContext, scope) }
    val installedPlugins by pluginRepo.installedPlugins.collectAsState()

    var showRegenDialog by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }

    val openUrl = { url: String ->
        if (url.isNotBlank()) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }.onFailure { e ->
                AppLogger.e("SettingsScreen", "Failed to open URL $url", e)
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) vm.loadCustomKey(bytes)
        }
    }

    val exportKeyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    val keyFile = java.io.File(context.filesDir, "adbkey")
                    if (keyFile.exists() && keyFile.length() > 0) {
                        out.write(keyFile.readBytes())
                    } else {
                        throw Exception(context.getString(R.string.err_no_adb_key_file))
                    }
                }
            }.onSuccess {
                Toast.makeText(context, context.getString(R.string.msg_key_exported), Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, context.getString(R.string.msg_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(out).use { zos ->
                        AppLogger.writePersistedLogsToZip(zos)
                    }
                }
            }.onSuccess {
                Toast.makeText(context, context.getString(R.string.msg_logs_exported), Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, context.getString(R.string.msg_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AppLogger.i("SettingsScreen", "Notification permission granted")
        } else {
            AppLogger.w("SettingsScreen", "Notification permission denied")
            Toast.makeText(context, context.getString(R.string.settings_notif_perm_denied_warning), Toast.LENGTH_LONG).show()
        }
    }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            AppLogger.i("SettingsScreen", "Battery optimization ignoring: $isIgnoring")
            if (!isIgnoring) {
                Toast.makeText(context, context.getString(R.string.settings_battery_opt_denied_warning), Toast.LENGTH_LONG).show()
            }
        }
    }

    val onToggleKeepAlive = { enable: Boolean ->
        if (enable) {
            // 1. Check and request notification permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            // 2. Check and request battery optimization exemption (Android 6.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    runCatching {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        batteryOptLauncher.launch(intent)
                    }.onFailure { e ->
                        AppLogger.e("SettingsScreen", "Failed to launch battery optimization intent", e)
                    }
                }
            }
            keepAlive = true
            prefs.edit().putBoolean("keep_alive_enabled", true).apply()
            DioxForegroundService.start(context)
        } else {
            keepAlive = false
            prefs.edit().putBoolean("keep_alive_enabled", false).apply()
            DioxForegroundService.stop(context)
        }
    }

    val currentAppLocales = AppCompatDelegate.getApplicationLocales()
    val currentTag = if (currentAppLocales.isEmpty) null else currentAppLocales.toLanguageTags()
    val currentSelectedLanguage = supportedLanguages.find { it.languageTag == currentTag } ?: supportedLanguages.first()

    val isMonetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // -- Theme Settings Card (Expandable) ------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { themeExpanded = !themeExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_theme_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(themeMode.labelRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (themeExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (themeExpanded) R.string.cd_collapse else R.string.cd_expand)
                    )
                }

                if (themeExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(modifier = Modifier.padding(16.dp)) {
                        // Monet / Dynamic Colors Option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    stringResource(R.string.settings_theme_dynamic_title),
                                    fontWeight = FontWeight.Medium,
                                    color = if (isMonetSupported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                                Text(
                                    if (isMonetSupported) stringResource(R.string.settings_theme_dynamic_desc) else stringResource(R.string.settings_theme_dynamic_req),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isMonetSupported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                )
                            }
                            Switch(
                                checked = useMonet && isMonetSupported,
                                enabled = isMonetSupported,
                                onCheckedChange = { checked ->
                                    useMonet = checked
                                    prefs.edit().putBoolean("use_monet", checked).apply()
                                }
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_theme_mode_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))

                        // Theme Mode Options
                        AppTheme.entries.forEach { option ->
                            val isSelected = option == themeMode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        themeMode = option
                                        prefs.edit().putString("theme_mode", option.name).apply()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(option.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // -- Language Settings Card (Expandable) ---------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { languageExpanded = !languageExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_language_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(currentSelectedLanguage.nameRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (languageExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (languageExpanded) R.string.cd_collapse else R.string.cd_expand)
                    )
                }

                if (languageExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(modifier = Modifier.padding(16.dp)) {
                        supportedLanguages.forEach { option ->
                            val isSelected = option.languageTag == currentTag
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newLocales = if (option.languageTag == null) {
                                            LocaleListCompat.getEmptyLocaleList()
                                        } else {
                                            LocaleListCompat.forLanguageTags(option.languageTag)
                                        }
                                        AppCompatDelegate.setApplicationLocales(newLocales)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(option.nameRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openUrl(BuildConfig.TRANSLATION_URL) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.settings_language_contribute),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.Underline
                                ),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // -- ADB Key Card (Expandable) ------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keyExpanded = !keyExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_key_title), fontWeight = FontWeight.Bold)
                            Text(
                                vm.keyFingerprint ?: stringResource(R.string.settings_no_key),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (keyExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (keyExpanded) R.string.cd_collapse else R.string.cd_expand)
                    )
                }

                if (keyExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_key_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        if (vm.keyFingerprint != null) {
                            Text(stringResource(R.string.settings_fingerprint_label), style = MaterialTheme.typography.labelMedium)
                            Text(
                                vm.keyFingerprint!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                        } else {
                            Text(
                                stringResource(R.string.settings_no_key),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        OutlinedButton(
                            onClick = { showRegenDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_btn_regen_key))
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_btn_load_custom_key))
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { exportKeyLauncher.launch("adbkey") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_btn_export_key))
                        }

                        if (vm.keyMessage != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                vm.keyMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // -- scrcpy Settings Card (Expandable) -----------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scrcpyExpanded = !scrcpyExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_scrcpy_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.settings_scrcpy_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (scrcpyExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (scrcpyExpanded) R.string.cd_collapse else R.string.cd_expand)
                    )
                }

                if (scrcpyExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    stringResource(R.string.settings_scrcpy_allow_custom_title),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    stringResource(R.string.settings_scrcpy_allow_custom_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = allowCustomValues,
                                onCheckedChange = { checked ->
                                    allowCustomValues = checked
                                    prefs.edit().putBoolean("scrcpy_allow_custom_values", checked).apply()
                                }
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    stringResource(R.string.settings_scrcpy_auto_record_title),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    stringResource(R.string.settings_scrcpy_auto_record_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = autoRecord,
                                onCheckedChange = { checked ->
                                    autoRecord = checked
                                    prefs.edit().putBoolean("scrcpy_auto_record", checked).apply()
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // -- Logs Settings Card (Expandable) -------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { logsExpanded = !logsExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_logs_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.settings_logs_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (logsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (logsExpanded) R.string.cd_collapse else R.string.cd_expand)
                    )
                }

                if (logsExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    stringResource(R.string.settings_enable_logging_title),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    stringResource(R.string.settings_enable_logging_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = loggingEnabled,
                                onCheckedChange = { checked ->
                                    loggingEnabled = checked
                                    AppLogger.enabled = checked
                                    prefs.edit().putBoolean("app_logging_enabled", checked).apply()
                                }
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                val filename = "dioxamine_logs_${System.currentTimeMillis()}.zip"
                                exportLogsLauncher.launch(filename)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_btn_export_all_logs))
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { showClearLogsDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_btn_clear_logs))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // -- Plugin Settings Card (Expandable) -----------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pluginExpanded = !pluginExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_plugins_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.settings_plugins_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (pluginExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (pluginExpanded) R.string.cd_collapse else R.string.cd_expand)
                    )
                }

                if (pluginExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(modifier = Modifier.padding(16.dp)) {
                        // WebView Debugging Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    stringResource(R.string.settings_plugins_webview_debug_title),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    stringResource(R.string.settings_plugins_webview_debug_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = pluginWebViewDebug,
                                onCheckedChange = { checked ->
                                    pluginWebViewDebug = checked
                                    prefs.edit().putBoolean("plugin_webview_debug", checked).apply()
                                }
                            )
                        }

                        Text(
                            stringResource(R.string.settings_plugins_take_effect_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        // Plugin Permissions Manager Tile
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { pluginRepo.refresh() }
                                    showPluginPermissionsDialog = true
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        stringResource(R.string.settings_plugins_permissions_title),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        stringResource(R.string.settings_plugins_permissions_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    scope.launch { pluginRepo.refresh() }
                                    showPluginPermissionsDialog = true
                                },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(stringResource(R.string.settings_plugins_btn_manage), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // -- Miscellaneous Settings Card (Expandable) ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { miscExpanded = !miscExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_misc_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.settings_misc_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (miscExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (miscExpanded) R.string.cd_collapse else R.string.cd_expand)
                    )
                }

                if (miscExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    stringResource(R.string.settings_keep_alive_title),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    stringResource(R.string.settings_keep_alive_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = keepAlive,
                                onCheckedChange = { checked ->
                                    onToggleKeepAlive(checked)
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // -- About Card (Expandable) --------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { aboutExpanded = !aboutExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.settings_about_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.settings_about_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (aboutExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (aboutExpanded) R.string.cd_collapse else R.string.cd_expand)
                    )
                }

                if (aboutExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "DI",
                                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 3.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "\u232C",
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "XAMINE",
                                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 3.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "© ${BuildConfig.COPYRIGHT_YEAR} ${BuildConfig.AUTHOR}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { openUrl(BuildConfig.GITHUB_URL) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_gh),
                                    contentDescription = stringResource(R.string.cd_github),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            IconButton(onClick = { openUrl(BuildConfig.TELEGRAM_URL) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_tg),
                                    contentDescription = stringResource(R.string.cd_telegram),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.settings_about_documentation),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDecoration = TextDecoration.Underline
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { openUrl(BuildConfig.DOCUMENTATION_URL) }
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.settings_about_source_code),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDecoration = TextDecoration.Underline
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { openUrl(BuildConfig.SOURCE_CODE_URL) }
                        )
                    }
                }
            }
        }
    }

    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text(stringResource(R.string.dialog_regen_key_title)) },
            text = { Text(stringResource(R.string.dialog_regen_key_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.regenerateKey()
                    showRegenDialog = false
                }) { Text(stringResource(R.string.btn_regenerate)) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            title = { Text(stringResource(R.string.dialog_clear_logs_title)) },
            text = { Text(stringResource(R.string.dialog_clear_logs_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLogger.clearPersistedLogs()
                        showClearLogsDialog = false
                        Toast.makeText(context, context.getString(R.string.msg_logs_cleared), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.btn_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogsDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (showPluginPermissionsDialog) {
        PluginPermissionsDialog(
            plugins = installedPlugins,
            permissionStore = permissionStore,
            onDismiss = { showPluginPermissionsDialog = false }
        )
    }
}

@Composable
private fun PluginPermissionsDialog(
    plugins: List<PluginManifest>,
    permissionStore: PluginPermissionStore,
    onDismiss: () -> Unit
) {
    var triggerUpdate by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_plugins_permissions_title), fontWeight = FontWeight.Bold)
        },
        text = {
            if (plugins.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.plugins_no_plugins),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    plugins.forEach { plugin ->
                        val declaredPermissions = remember(plugin.id) {
                            plugin.permissions.mapNotNull { PluginPermission.fromManifestString(it) }
                        }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = plugin.name,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = plugin.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(Modifier.height(8.dp))

                                if (declaredPermissions.isEmpty()) {
                                    Text(
                                        stringResource(R.string.settings_plugins_no_permissions),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                } else {
                                    declaredPermissions.forEach { perm ->
                                        key(triggerUpdate, plugin.id, perm) {
                                            val currentPolicy = permissionStore.getPolicy(plugin.id, perm)

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = perm.name.lowercase().replaceFirstChar { it.uppercase() },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium
                                                )

                                                var expandedDropdown by remember { mutableStateOf(false) }

                                                Box {
                                                    OutlinedButton(
                                                        onClick = { expandedDropdown = true },
                                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(36.dp)
                                                    ) {
                                                        val label = when (currentPolicy) {
                                                            PermissionPolicy.ALWAYS_ALLOW -> stringResource(R.string.plugin_policy_always_allow)
                                                            PermissionPolicy.ALWAYS_DENY -> stringResource(R.string.plugin_policy_always_deny)
                                                            PermissionPolicy.ASK -> stringResource(R.string.plugin_policy_ask)
                                                        }
                                                        Text(label, style = MaterialTheme.typography.bodySmall)
                                                    }

                                                    DropdownMenu(
                                                        expanded = expandedDropdown,
                                                        onDismissRequest = { expandedDropdown = false }
                                                    ) {
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.plugin_policy_ask_default)) },
                                                            onClick = {
                                                                permissionStore.setPolicy(plugin.id, perm, PermissionPolicy.ASK)
                                                                expandedDropdown = false
                                                                triggerUpdate++
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.plugin_policy_always_allow)) },
                                                            onClick = {
                                                                permissionStore.setPolicy(plugin.id, perm, PermissionPolicy.ALWAYS_ALLOW)
                                                                expandedDropdown = false
                                                                triggerUpdate++
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.plugin_policy_always_deny)) },
                                                            onClick = {
                                                                permissionStore.setPolicy(plugin.id, perm, PermissionPolicy.ALWAYS_DENY)
                                                                expandedDropdown = false
                                                                triggerUpdate++
                                                            }
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
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_done))
            }
        },
        dismissButton = {
            if (plugins.isNotEmpty()) {
                TextButton(
                    onClick = {
                        permissionStore.resetAll()
                        triggerUpdate++
                    }
                ) {
                    Text(stringResource(R.string.btn_reset_all), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}
