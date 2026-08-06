package io.github.rhythmcache.dioxamine.adb.builtin.remotecontrol

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile

@Composable
fun RemoteControlTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.remote_control_title),
        description = stringResource(R.string.remote_control_desc),
        icon = Icons.Filled.Tv,
        enabled = isConnected,
        onClick = onClick
    )
}
