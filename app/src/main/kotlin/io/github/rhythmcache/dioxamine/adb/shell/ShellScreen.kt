package io.github.rhythmcache.dioxamine.adb.shell

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.AdbTerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel

/**
 * Main ADB Shell screen composable.
 *
 * Uses Termux's [TerminalView] (an Android View) wrapped in Compose [AndroidView]
 * for full terminal emulation including cursor, colors, scrollback, text selection,
 * and proper keyboard handling.
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
    val errorMessage by shellVm.errorMessage.collectAsState()

    var ctrlActive by remember { mutableStateOf(false) }
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }

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
        // -- Terminal view (Termux TerminalView wrapped in AndroidView) --
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    val monoTypeface = try {
                        Typeface.createFromAsset(ctx.assets, "fonts/JetBrainsMono-Regular.ttf")
                    } catch (_: Exception) {
                        Typeface.MONOSPACE
                    }
                    setTextSize(14)
                    setTypeface(monoTypeface)
                    setTerminalViewClient(createTerminalViewClient(shellVm, { this }, { ctrlActive }, { ctrlActive = false }))
                    isFocusable = true
                    isFocusableInTouchMode = true
                    shellVm.bindTerminalView(this)
                    terminalViewRef = this
                    post {
                        requestFocus()
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        val shown = imm?.showSoftInput(this, 0) == true
                        if (!shown) {
                            imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                        }
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF000000)),
        )

        // -- Error banner --
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

        // -- Toolbar (Ctrl, ESC, Tab, ^C, navigation, keyboard toggle, etc.) --
        ShellToolbar(
            sessionState = sessionState,
            ctrlActive = ctrlActive,
            onToggleCtrl = { ctrlActive = !ctrlActive },
            onEsc = { shellVm.sendEscape() },
            onTab = { shellVm.sendTab() },
            onInterrupt = { shellVm.sendInterrupt() },
            onEof = { shellVm.sendEof() },
            onArrowUp = { shellVm.sendArrowUp() },
            onArrowDown = { shellVm.sendArrowDown() },
            onArrowLeft = { shellVm.sendArrowLeft() },
            onArrowRight = { shellVm.sendArrowRight() },
            onToggleKeyboard = {
                terminalViewRef?.let { view ->
                    view.requestFocus()
                    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                }
            },
            onClear = { shellVm.clearBuffer() },
            onRestart = {
                val client = adbViewModel.activeClient()
                if (client != null && !client.isClosed) {
                    shellVm.startSession(activeDeviceId, client)
                }
            },
        )
    }
}

/**
 * Create a [TerminalViewClient] that bridges Termux's view callbacks
 * to our ViewModel and Compose state.
 */
private fun createTerminalViewClient(
    shellVm: ShellViewModel,
    terminalViewProvider: () -> TerminalView?,
    readCtrl: () -> Boolean,
    consumeCtrl: () -> Unit,
): TerminalViewClient {
    return object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f

        override fun onSingleTapUp(e: MotionEvent) {
            terminalViewProvider()?.let { view ->
                view.requestFocus()
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                val shown = imm?.showSoftInput(view, 0) == true
                if (!shown) {
                    imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                }
            }
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: AdbTerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean {
            val active = readCtrl()
            if (active) consumeCtrl()
            return active
        }

        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: AdbTerminalSession): Boolean = false
        override fun onEmulatorSet() {}

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}

// -- Empty state when no device is connected --

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
