package io.github.rhythmcache.dioxamine.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

fun pluginPermissionIcon(permission: PluginPermission): ImageVector =
    when (permission) {
        PluginPermission.SHELL -> Icons.Outlined.Terminal
        PluginPermission.PUSH -> Icons.Outlined.Upload
        PluginPermission.PULL -> Icons.Outlined.Download
        PluginPermission.INSTALL -> Icons.Outlined.InstallMobile
        PluginPermission.FORWARD -> Icons.Outlined.SyncAlt
        PluginPermission.REVERSE -> Icons.Outlined.SwapHoriz
        PluginPermission.NETWORK -> Icons.Outlined.Language
        PluginPermission.FASTBOOT -> Icons.Outlined.FlashOn
    }

@Composable
fun PluginPermissionDialogHost(gate: PluginPermissionGate) {
    val pendingRequest by gate.pendingRequest.collectAsState()

    pendingRequest?.let { request ->
        val rawDesc = pluginPermissionDescription(request.permission)
        val actionText = rawDesc.replaceFirstChar { it.lowercase() }
        val fullQuestion = stringResource(R.string.plugin_perm_prompt_question, request.pluginName, actionText)

        val annotatedTitle = remember(fullQuestion, request.pluginName) {
            val startIndex = fullQuestion.indexOf(request.pluginName)
            buildAnnotatedString {
                if (startIndex >= 0) {
                    append(fullQuestion.substring(0, startIndex))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(request.pluginName)
                    }
                    append(fullQuestion.substring(startIndex + request.pluginName.length))
                } else {
                    append(fullQuestion)
                }
            }
        }

        Dialog(
            onDismissRequest = { request.onDecision(PermissionDecision.DENY_SESSION) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 340.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = pluginPermissionIcon(request.permission),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = annotatedTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { request.onDecision(PermissionDecision.ALWAYS_ALLOW) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.plugin_perm_btn_always_allow),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        FilledTonalButton(
                            onClick = { request.onDecision(PermissionDecision.ALLOW_SESSION) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.plugin_perm_btn_allow_session),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        FilledTonalButton(
                            onClick = { request.onDecision(PermissionDecision.DENY_SESSION) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.plugin_perm_btn_deny_session),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        FilledTonalButton(
                            onClick = { request.onDecision(PermissionDecision.ALWAYS_DENY) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.plugin_perm_btn_always_deny),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
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
