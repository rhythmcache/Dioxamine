package io.github.rhythmcache.dioxamine.adb.shell

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel

/**
 * Main ADB Shell screen composable.
 *
 * Wires [ShellViewModel] to the currently-active device from [AdbViewModel].
 * Automatically starts a new shell session when a device connects and
 * tears it down on disconnect or device change.
 */
@Composable
fun ShellScreen(adbViewModel: AdbViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var showInfoDialog by rememberSaveable {
        mutableStateOf(!prefs.getBoolean("adb_shell_dont_show_info", false))
    }

    if (showInfoDialog) {
        ShellInfoDialog(
            onDismiss = { showInfoDialog = false },
            onConfirm = { dontShowAgain ->
                if (dontShowAgain) {
                    prefs.edit().putBoolean("adb_shell_dont_show_info", true).apply()
                }
                showInfoDialog = false
            },
        )
    }

    val shellVm: ShellViewModel = viewModel()

    val activeClient = adbViewModel.activeClient()
    val activeDeviceId = adbViewModel.activeDeviceId

    val sessionState by shellVm.sessionState.collectAsState()
    val outputLines = shellVm.outputLines
    val currentLine = shellVm.currentLine
    val errorMessage by shellVm.errorMessage.collectAsState()

    var ctrlActive by remember { mutableStateOf(false) }

    // Start / restart shell when the active device changes
    LaunchedEffect(activeDeviceId) {
        if (activeClient != null && !activeClient.isClosed) {
            val needsRestart = shellVm.currentDeviceId != activeDeviceId ||
                sessionState == ShellSessionState.CLOSED ||
                sessionState == ShellSessionState.ERROR
            if (needsRestart) {
                shellVm.startSession(activeDeviceId, activeClient)
            }
        } else {
            shellVm.stopSession()
        }
    }

    if (activeClient == null) {
        NoDeviceState()
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // -- Terminal output (fills available space) ----------------
        ShellOutputView(
            completedLines = outputLines,
            currentLine = currentLine,
            modifier = Modifier.weight(1f),
        )

        // -- Error banner ------------------------------------------
        if (sessionState == ShellSessionState.ERROR && errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.shell_error_message, errorMessage ?: ""),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // -- Toolbar (Ctrl toggle, Tab, etc.) -----------------------
        ShellToolbar(
            sessionState = sessionState,
            ctrlActive   = ctrlActive,
            onToggleCtrl = { ctrlActive = !ctrlActive },
            onTab        = { shellVm.sendTab() },
            onClear      = { shellVm.clearBuffer() },
            onRestart    = {
                val client = adbViewModel.activeClient()
                if (client != null && !client.isClosed) {
                    shellVm.startSession(activeDeviceId, client)
                }
            },
        )

        // -- Input bar ---------------------------------------------
        ShellInputBar(
            onSend         = { shellVm.sendCommand(it) },
            onHistoryUp    = { shellVm.historyUp() },
            onHistoryDown  = { shellVm.historyDown() },
            onRawKey       = { shellVm.sendRaw(it) },
            ctrlActive     = ctrlActive,
            onCtrlConsumed = { ctrlActive = false },
            enabled        = sessionState == ShellSessionState.ACTIVE,
        )
    }
}

// -- Empty state when no device is connected -------------------------

@Composable
private fun NoDeviceState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Terminal,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No Device Connected",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect a device to start a shell session",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
