package io.github.rhythmcache.dioxamine.adb.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel

private val TerminalBackground = Color(0xFF0D1117)

/**
 * Main ADB Shell screen composable backed by Termux terminal engine.
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
    val terminalSession by shellVm.terminalSession.collectAsState()
    val errorMessage by shellVm.errorMessage.collectAsState()

    var ctrlActive by remember { mutableStateOf(false) }
    var terminalViewRef by remember { mutableStateOf<BoundedTerminalView?>(null) }

    val typeface = remember {
        try {
            Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float = 1.0f

            override fun onSingleTapUp(e: MotionEvent) {
                // Tapping terminal area requests focus and shows soft keyboard for direct typing (vim/nano)
                terminalViewRef?.let { tv ->
                    tv.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}

            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
            override fun onLongPress(event: MotionEvent): Boolean = false

            override fun readControlKey(): Boolean = ctrlActive
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
                if (ctrlActive) {
                    ctrlActive = false
                }
                return false
            }

            override fun onEmulatorSet() {}

            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }
    }

    val sessionClient = remember {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalViewRef?.onScreenUpdated()
            }

            override fun onTitleChanged(changedSession: TerminalSession) {}

            override fun onSessionFinished(finishedSession: TerminalSession) {
                terminalViewRef?.onScreenUpdated()
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                if (text != null && session != null) {
                    session.write(text)
                }
            }

            override fun onBell(session: TerminalSession) {}

            override fun onColorsChanged(session: TerminalSession) {
                terminalViewRef?.onScreenUpdated()
            }

            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun getTerminalCursorStyle(): Int = 0
        }
    }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding() // Bounds the entire column above the software keyboard
    ) {
        // -- Terminal surface (strictly bounded and clipped) --------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(TerminalBackground),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                factory = { ctx ->
                    BoundedTerminalView(ctx).apply {
                        terminalViewRef = this
                        val fontPx = (13 * ctx.resources.displayMetrics.scaledDensity).toInt()
                        setTextSize(fontPx)
                        setTypeface(typeface)
                        setTerminalViewClient(viewClient)
                        terminalSession?.let { session ->
                            session.updateTerminalSessionClient(sessionClient)
                            attachSession(session)
                        }
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                },
                update = { view ->
                    terminalViewRef = view
                    view.setTerminalViewClient(viewClient)
                    if (terminalSession != null && view.currentSession != terminalSession) {
                        terminalSession?.updateTerminalSessionClient(sessionClient)
                        view.attachSession(terminalSession)
                    }
                },
            )
        }

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
