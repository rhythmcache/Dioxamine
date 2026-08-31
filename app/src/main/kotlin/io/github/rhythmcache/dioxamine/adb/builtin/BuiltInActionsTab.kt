package io.github.rhythmcache.dioxamine.adb.builtin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel

import io.github.rhythmcache.dioxamine.adb.builtin.filemanager.FileManagerScreen
import io.github.rhythmcache.dioxamine.adb.builtin.filemanager.FileManagerTile
import io.github.rhythmcache.dioxamine.adb.builtin.misc.MiscScreen
import io.github.rhythmcache.dioxamine.adb.builtin.misc.MiscTile
import io.github.rhythmcache.dioxamine.adb.builtin.packagemanager.PackageManagerScreen
import io.github.rhythmcache.dioxamine.adb.builtin.packagemanager.PackageManagerTile
import io.github.rhythmcache.dioxamine.adb.builtin.processmanager.ProcessManagerScreen
import io.github.rhythmcache.dioxamine.adb.builtin.processmanager.ProcessManagerTile
import io.github.rhythmcache.dioxamine.adb.builtin.reboot.RebootScreen
import io.github.rhythmcache.dioxamine.adb.builtin.reboot.RebootTile
import io.github.rhythmcache.dioxamine.adb.builtin.remotecontrol.RemoteControlScreen
import io.github.rhythmcache.dioxamine.adb.builtin.remotecontrol.RemoteControlTile
import io.github.rhythmcache.dioxamine.adb.builtin.screencap.ScreencapScreen
import io.github.rhythmcache.dioxamine.adb.builtin.screencap.ScreencapTile
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.TouchpadScreen
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.TouchpadTile

sealed class BuiltInSubScreen {
    object TilesList : BuiltInSubScreen()
    object DeviceInfo : BuiltInSubScreen()
    object RemoteControl : BuiltInSubScreen()
    object Touchpad : BuiltInSubScreen()
    object FileManager : BuiltInSubScreen()
    object PackageManager : BuiltInSubScreen()
    object ProcessManager : BuiltInSubScreen()
    object Misc : BuiltInSubScreen()
    object Screenshot : BuiltInSubScreen()
    object Reboot : BuiltInSubScreen()
}

@Composable
fun BuiltInActionsTab(vm: AdbViewModel) {
    var activeSubScreen by remember { mutableStateOf<BuiltInSubScreen>(BuiltInSubScreen.TilesList) }
    val isConnected = vm.activeClient() != null

    // Return to tools list when on a sub-screen
    BackHandler(enabled = activeSubScreen != BuiltInSubScreen.TilesList) {
        activeSubScreen = BuiltInSubScreen.TilesList
    }

    when (activeSubScreen) {
        BuiltInSubScreen.TilesList -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isConnected) {
                    item {
                        Text(
                            text = stringResource(R.string.connect_device_warning),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                item {
                    DeviceInformationTile(
                        vm = vm,
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.DeviceInfo }
                    )
                }
                item {
                    RemoteControlTile(
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.RemoteControl }
                    )
                }
                item {
                    TouchpadTile(
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.Touchpad }
                    )
                }
                item {
                    FileManagerTile(
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.FileManager }
                    )
                }
                item {
                    PackageManagerTile(
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.PackageManager }
                    )
                }
                item {
                    ProcessManagerTile(
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.ProcessManager }
                    )
                }
                item {
                    MiscTile(
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.Misc }
                    )
                }
                item {
                    ScreencapTile(
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.Screenshot }
                    )
                }
                item {
                    RebootTile(
                        isConnected = isConnected,
                        onClick = { activeSubScreen = BuiltInSubScreen.Reboot }
                    )
                }
            }
        }
        BuiltInSubScreen.DeviceInfo -> {
            DeviceInformationDetailScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList }
            )
        }
        BuiltInSubScreen.RemoteControl -> {
            RemoteControlScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList },
                onOpenTouchpad = { activeSubScreen = BuiltInSubScreen.Touchpad }
            )
        }
        BuiltInSubScreen.Touchpad -> {
            TouchpadScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList }
            )
        }
        BuiltInSubScreen.FileManager -> {
            FileManagerScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList }
            )
        }
        BuiltInSubScreen.PackageManager -> {
            PackageManagerScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList }
            )
        }
        BuiltInSubScreen.ProcessManager -> {
            ProcessManagerScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList }
            )
        }
        BuiltInSubScreen.Misc -> {
            MiscScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList }
            )
        }
        BuiltInSubScreen.Screenshot -> {
            ScreencapScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList }
            )
        }
        BuiltInSubScreen.Reboot -> {
            RebootScreen(
                vm = vm,
                onBack = { activeSubScreen = BuiltInSubScreen.TilesList }
            )
        }
    }
}

