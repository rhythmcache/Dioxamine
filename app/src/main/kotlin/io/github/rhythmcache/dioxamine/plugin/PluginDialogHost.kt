package io.github.rhythmcache.dioxamine.plugin

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R

@Composable
fun PluginDialogHost(gate: PluginDialogGate) {
    val pendingRequest by gate.pendingRequest.collectAsState()

    pendingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { request.onResult(-1) },
            title = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.plugin_dialog_attribution, request.pluginName),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (request.title.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = request.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            },
            text = {
                if (request.message.isNotBlank()) {
                    Text(
                        text = request.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    request.buttons.forEachIndexed { index, buttonText ->
                        TextButton(onClick = { request.onResult(index) }) {
                            Text(text = buttonText)
                        }
                    }
                }
            },
        )
    }
}
