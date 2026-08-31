package io.github.rhythmcache.dioxamine.adb.builtin.processmanager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile

@Composable
fun ProcessManagerTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.proc_manager_title),
        description = stringResource(R.string.proc_manager_subtitle),
        icon = Icons.Filled.Memory,
        enabled = isConnected,
        onClick = onClick
    )
}
