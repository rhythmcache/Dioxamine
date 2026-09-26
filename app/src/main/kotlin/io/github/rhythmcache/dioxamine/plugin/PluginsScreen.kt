package io.github.rhythmcache.dioxamine.plugin

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.fastboot.FastbootViewModel

@Composable
fun PluginsScreen(
    vm: AdbViewModel,
    fastbootVm: FastbootViewModel? = null,
    pluginRepo: PluginRepository,
    permissionGate: PluginPermissionGate,
    dialogGate: PluginDialogGate,
    safBridge: PluginSafBridge,
    onPluginActiveChange: (Boolean) -> Unit = {},
) {
    var activePluginId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(activePluginId) {
        onPluginActiveChange(activePluginId != null)
    }

    DisposableEffect(Unit) {
        onDispose {
            onPluginActiveChange(false)
        }
    }

    if (activePluginId != null) {
        PluginRunnerScreen(
            pluginId = activePluginId!!,
            vm = vm,
            fastbootVm = fastbootVm,
            repo = pluginRepo,
            permissionGate = permissionGate,
            dialogGate = dialogGate,
            safBridge = safBridge,
            onBack = { activePluginId = null }
        )
    } else {
        PluginsTab(
            repo = pluginRepo,
            permissionGate = permissionGate,
            onOpenPlugin = { pluginId ->
                activePluginId = pluginId
            }
        )
    }
}
