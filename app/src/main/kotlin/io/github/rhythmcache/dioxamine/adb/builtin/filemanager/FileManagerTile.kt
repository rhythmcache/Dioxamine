package io.github.rhythmcache.dioxamine.adb.builtin.filemanager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile

@Composable
fun FileManagerTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.file_manager_title),
        description = stringResource(R.string.file_manager_subtitle),
        icon = Icons.Filled.FolderSpecial,
        enabled = isConnected,
        onClick = onClick
    )
}
