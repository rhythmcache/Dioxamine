package io.github.rhythmcache.dioxamine.plugin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R

@Composable
fun PluginRepositoriesDialog(
    repoManager: PluginOnlineRepositoryManager,
    onDismiss: () -> Unit,
) {
    val repositories = remember { mutableStateListOf<String>().apply { addAll(repoManager.getRepositories()) } }
    var showAddDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                text = stringResource(R.string.dialog_repos_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_repos_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (repositories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dialog_repos_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    repositories.forEach { url ->
                        val isDefault = url.equals(PluginOnlineRepositoryManager.DEFAULT_REPO_URL, ignoreCase = true)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (isDefault) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.dialog_repos_default_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }

                                if (!isDefault) {
                                    IconButton(
                                        onClick = {
                                            repoManager.removeRepository(url)
                                            repositories.remove(url)
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.btn_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dialog_repos_btn_add))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_done))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val defaults = repoManager.resetToDefault()
                    repositories.clear()
                    repositories.addAll(defaults)
                },
            ) {
                Text(
                    stringResource(R.string.btn_reset_all),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )

    if (showAddDialog) {
        var inputUrl by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        val invalidUrlMsg = stringResource(R.string.dialog_repos_invalid_url)
        val duplicateUrlMsg = stringResource(R.string.dialog_repos_duplicate_url)

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_repos_add_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.dialog_repos_add_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = {
                            inputUrl = it
                            errorMessage = null
                        },
                        placeholder = {
                            Text(
                                stringResource(R.string.dialog_repos_url_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        singleLine = true,
                        isError = errorMessage != null,
                        supportingText = errorMessage?.let { msg ->
                            { Text(msg, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = inputUrl.trim()
                        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                            errorMessage = invalidUrlMsg
                            return@TextButton
                        }
                        if (repositories.any { it.equals(trimmed, ignoreCase = true) }) {
                            errorMessage = duplicateUrlMsg
                            return@TextButton
                        }
                        val added = repoManager.addRepository(trimmed)
                        if (added) {
                            repositories.add(trimmed)
                            showAddDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.btn_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}
