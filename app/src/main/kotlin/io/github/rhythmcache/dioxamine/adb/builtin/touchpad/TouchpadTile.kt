package io.github.rhythmcache.dioxamine.adb.builtin.touchpad

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile

@Composable
fun TouchpadTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.touchpad_title),
        description = stringResource(R.string.touchpad_desc),
        icon = Icons.Filled.Mouse,
        enabled = isConnected,
        onClick = onClick
    )
}
