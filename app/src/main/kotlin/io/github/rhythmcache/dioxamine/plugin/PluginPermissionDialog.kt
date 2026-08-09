package io.github.rhythmcache.dioxamine.plugin

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.github.rhythmcache.dioxamine.R

@Composable
fun PluginPermissionDialogHost(gate: PluginPermissionGate) {
    val pendingRequest by gate.pendingRequest.collectAsState()

    pendingRequest?.let { request ->
        val permissionDescription =
            when (request.permission) {
                PluginPermission.SHELL -> stringResource(R.string.plugin_perm_shell)
                PluginPermission.PUSH -> stringResource(R.string.plugin_perm_push)
                PluginPermission.PULL -> stringResource(R.string.plugin_perm_pull)
                PluginPermission.INSTALL -> stringResource(R.string.plugin_perm_install)
                PluginPermission.FORWARD -> stringResource(R.string.plugin_perm_forward)
                PluginPermission.REVERSE -> stringResource(R.string.plugin_perm_reverse)
            }

        AlertDialog(
            onDismissRequest = { request.onResult(false) },
            title = {
                Text(text = stringResource(R.string.plugin_perm_dialog_title, request.pluginName))
            },
            text = {
                Text(text = permissionDescription)
            },
            confirmButton = {
                TextButton(onClick = { request.onResult(true) }) {
                    Text(text = stringResource(R.string.btn_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { request.onResult(false) }) {
                    Text(text = stringResource(R.string.btn_deny))
                }
            },
        )
    }
}
