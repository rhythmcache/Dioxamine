package io.github.rhythmcache.dioxamine.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R

@Composable
fun pluginPermissionDescription(permission: PluginPermission): String =
    when (permission) {
        PluginPermission.SHELL -> stringResource(R.string.plugin_perm_shell)
        PluginPermission.PUSH -> stringResource(R.string.plugin_perm_push)
        PluginPermission.PULL -> stringResource(R.string.plugin_perm_pull)
        PluginPermission.INSTALL -> stringResource(R.string.plugin_perm_install)
        PluginPermission.FORWARD -> stringResource(R.string.plugin_perm_forward)
        PluginPermission.REVERSE -> stringResource(R.string.plugin_perm_reverse)
        PluginPermission.NETWORK -> stringResource(R.string.plugin_perm_network)
        PluginPermission.FASTBOOT -> stringResource(R.string.plugin_perm_fastboot)
    }

@Composable
fun PluginPermissionDialogHost(gate: PluginPermissionGate) {
    val pendingRequest by gate.pendingRequest.collectAsState()

    pendingRequest?.let { request ->
        val permissionDescription = pluginPermissionDescription(request.permission)
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

@Composable
fun PluginPermissionTile(
    permission: PluginPermission,
    currentPolicy: PermissionPolicy,
    onPolicyChange: (PermissionPolicy) -> Unit,
    modifier: Modifier = Modifier,
    isSessionGranted: Boolean = false,
    isSessionDenied: Boolean = false,
    onResetSession: (() -> Unit)? = null,
) {
    val permTitle = permission.name.lowercase().replaceFirstChar { it.uppercase() }
    val permDesc = pluginPermissionDescription(permission)
    var expandedDropdown by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = permTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = permDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(contentAlignment = Alignment.CenterEnd) {
                OutlinedButton(
                    onClick = { expandedDropdown = true },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    val label = when (currentPolicy) {
                        PermissionPolicy.ALWAYS_ALLOW -> stringResource(R.string.plugin_policy_always_allow)
                        PermissionPolicy.ALWAYS_DENY -> stringResource(R.string.plugin_policy_always_deny)
                        PermissionPolicy.ASK -> stringResource(R.string.plugin_policy_ask)
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }

                DropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plugin_policy_ask_default)) },
                        onClick = {
                            onPolicyChange(PermissionPolicy.ASK)
                            expandedDropdown = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plugin_policy_always_allow)) },
                        onClick = {
                            onPolicyChange(PermissionPolicy.ALWAYS_ALLOW)
                            expandedDropdown = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plugin_policy_always_deny)) },
                        onClick = {
                            onPolicyChange(PermissionPolicy.ALWAYS_DENY)
                            expandedDropdown = false
                        },
                    )
                }
            }
        }

        if (currentPolicy == PermissionPolicy.ASK && (isSessionGranted || isSessionDenied) && onResetSession != null) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = if (isSessionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                shape = CircleShape,
                            ),
                    )
                    Text(
                        text = if (isSessionGranted) {
                            stringResource(R.string.plugin_perm_session_status_allowed)
                        } else {
                            stringResource(R.string.plugin_perm_session_status_denied)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSessionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }

                TextButton(
                    onClick = onResetSession,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) {
                    Text(
                        text = stringResource(R.string.plugin_perm_session_reset),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
