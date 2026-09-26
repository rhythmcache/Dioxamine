package io.github.rhythmcache.dioxamine.plugin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                PluginPermission.NETWORK -> stringResource(R.string.plugin_perm_network)
                PluginPermission.FASTBOOT -> stringResource(R.string.plugin_perm_fastboot)
            }

        val permissionName = request.permission.name.lowercase().replaceFirstChar { it.uppercase() }

        AlertDialog(
            onDismissRequest = { request.onDecision(PermissionDecision.DENY_SESSION) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.plugin_perm_dialog_title, request.pluginName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Text(
                            text = permissionName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }

                    Text(
                        text = permissionDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = { request.onDecision(PermissionDecision.ALWAYS_ALLOW) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_perm_btn_always_allow),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    FilledTonalButton(
                        onClick = { request.onDecision(PermissionDecision.ALLOW_SESSION) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_perm_btn_allow_session),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    OutlinedButton(
                        onClick = { request.onDecision(PermissionDecision.DENY_SESSION) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_perm_btn_deny_session),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    TextButton(
                        onClick = { request.onDecision(PermissionDecision.ALWAYS_DENY) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_perm_btn_always_deny),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
}
