package io.github.rhythmcache.dioxamine.adb.builtin.packagemanager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile

@Composable
fun PackageManagerTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.pkg_manager_title),
        description = stringResource(R.string.pkg_manager_subtitle),
        icon = Icons.Filled.Apps,
        enabled = isConnected,
        onClick = onClick
    )
}
