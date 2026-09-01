package io.github.rhythmcache.dioxamine

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rhythmcache.dioxamine.adb.*
import io.github.rhythmcache.dioxamine.core.*
import io.github.rhythmcache.dioxamine.fastboot.FastbootScreen
import io.github.rhythmcache.dioxamine.fastboot.FastbootViewModel
import io.github.rhythmcache.dioxamine.fastboot.ListenForFastbootDevices
import io.github.rhythmcache.dioxamine.scrcpy.ScrcpyScreen
import io.github.rhythmcache.dioxamine.settings.SettingsScreen
import io.github.rhythmcache.adb.AdbDeviceMode
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

enum class Tab(@StringRes val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ADB(R.string.tab_adb, Icons.Filled.PhoneAndroid),
    SCRCPY(R.string.tab_scrcpy, Icons.AutoMirrored.Filled.ScreenShare),
    FASTBOOT(R.string.tab_fastboot, Icons.Filled.Bolt),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings)
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("keep_alive_enabled", false)) {
            DioxForegroundService.start(this)
        }

        setContent {
            val context = LocalContext.current
            val prefsState = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
            var currentAppTheme by remember {
                mutableStateOf(
                    runCatching { AppTheme.valueOf(prefsState.getString("theme_mode", "SYSTEM") ?: "SYSTEM") }
                        .getOrDefault(AppTheme.SYSTEM)
                )
            }

            var currentUseMonet by remember {
                mutableStateOf(prefsState.getBoolean("use_monet", false))
            }

            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    if (key == "theme_mode") {
                        currentAppTheme = runCatching { AppTheme.valueOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM") }
                            .getOrDefault(AppTheme.SYSTEM)
                    } else if (key == "use_monet") {
                        currentUseMonet = prefs.getBoolean("use_monet", false)
                    }
                }
                prefsState.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefsState.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            DioxamineTheme(appTheme = currentAppTheme, useMonet = currentUseMonet) {
                DioxamineApp(keyDir = filesDir)
            }
        }
    }
}

@Composable
fun DioxamineApp(keyDir: File) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(Tab.ADB) }
    val vm: AdbViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AdbViewModel(keyDir) as T
        }
    })
    val fastbootVm: FastbootViewModel = viewModel()

    val coroutineScope = rememberCoroutineScope()
    val pluginRepo = remember { io.github.rhythmcache.dioxamine.plugin.PluginRepository(context.applicationContext, coroutineScope) }
    val permissionStore = remember { io.github.rhythmcache.dioxamine.plugin.PluginPermissionStore(context.applicationContext) }
    val permissionGate = remember { io.github.rhythmcache.dioxamine.plugin.PluginPermissionGate(store = permissionStore) }
    val dialogGate = remember { io.github.rhythmcache.dioxamine.plugin.PluginDialogGate() }
    val safBridge = remember { io.github.rhythmcache.dioxamine.plugin.PluginSafBridge(context.applicationContext) }

    io.github.rhythmcache.dioxamine.plugin.PluginPermissionDialogHost(permissionGate)
    io.github.rhythmcache.dioxamine.plugin.PluginDialogHost(dialogGate)
    io.github.rhythmcache.dioxamine.plugin.PluginSafLauncherHost(safBridge)

    ListenForUsbDevices(vm)
    ListenForFastbootDevices(fastbootVm)

    val adbConnectedCount = vm.devices.values.count { it.state is ConnectionState.Connected }
    val fastbootConnectedCount = if (fastbootVm.isConnected) 1 else fastbootVm.devices.size

    LaunchedEffect(adbConnectedCount, fastbootConnectedCount) {
        DioxForegroundService.updateDeviceCounts(context, adbConnectedCount, fastbootConnectedCount)
    }

    var isScrcpyFullScreen by remember { mutableStateOf(false) }
    var isPluginActive by remember { mutableStateOf(false) }

    DisposableEffect(isScrcpyFullScreen) {
        val activity = context as? ComponentActivity
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isScrcpyFullScreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val activityOnDispose = context as? ComponentActivity
            val windowOnDispose = activityOnDispose?.window
            if (windowOnDispose != null) {
                WindowCompat.getInsetsController(windowOnDispose, windowOnDispose.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val hideBottomBar = isScrcpyFullScreen || isPluginActive

    // Navigate back to ADB home tab before exiting app
    BackHandler(enabled = selectedTab != Tab.ADB && !hideBottomBar) {
        selectedTab = Tab.ADB
    }

    // Exit Scrcpy fullscreen on back gesture
    BackHandler(enabled = isScrcpyFullScreen) {
        isScrcpyFullScreen = false
    }

    Scaffold(
        bottomBar = {
            if (!hideBottomBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = if (hideBottomBar) Modifier.fillMaxSize()
                       else Modifier.padding(padding).fillMaxSize()
        ) {
            when (selectedTab) {
                Tab.ADB -> AdbScreen(vm, pluginRepo, permissionGate, dialogGate, safBridge, onPluginActiveChange = { isPluginActive = it })
                Tab.SCRCPY -> ScrcpyScreen(vm, onFullScreenChange = { isScrcpyFullScreen = it })
                Tab.FASTBOOT -> FastbootScreen(fastbootVm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
