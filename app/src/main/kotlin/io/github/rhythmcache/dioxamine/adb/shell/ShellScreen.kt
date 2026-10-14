package io.github.rhythmcache.dioxamine.adb.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel

private val TerminalBackground = Color(0xFF0D1117)

/**
 * Main ADB Shell screen composable backed by Termux terminal engine.
 *
 * Direct terminal typing: tapping the terminal focuses it and opens the soft keyboard.
 * Keyboard input is complemented by a 2x7 Termux-style extra keys panel sitting right
 * above the soft keyboard (via imePadding).
 */
@Composable
fun ShellScreen(adbViewModel: AdbViewModel) {
    val context = LocalContext.current
    val shellVm: ShellViewModel = viewModel()

    val activeClient = adbViewModel.activeClient()
    val activeDeviceId = adbViewModel.activeDeviceId
    val activeConn = activeDeviceId?.let { adbViewModel.devices[it] }
    val supportsShellV2 = activeConn?.let { conn ->
        conn.apiLevel?.let { it >= 24 } ?: conn.supportsShellV2
    } ?: true

    val sessionState by shellVm.sessionState.collectAsState()
    val terminalSession by shellVm.terminalSession.collectAsState()
    val errorMessage by shellVm.errorMessage.collectAsState()

    var ctrlState by remember { mutableStateOf(ModifierState.OFF) }
    var altState by remember { mutableStateOf(ModifierState.OFF) }
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
                // Tapping terminal area requests focus and shows soft keyboard for direct typing
                terminalViewRef?.let { tv ->
                    tv.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(tv, 0)
                }
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}

            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
                // Consume single-use sticky modifiers after key release
                if (ctrlState == ModifierState.ON) ctrlState = ModifierState.OFF
                if (altState == ModifierState.ON) altState = ModifierState.OFF
                return false
            }

            override fun onLongPress(event: MotionEvent): Boolean {
                // Return false to allow Termux's built-in TextSelectionMode (copy/paste)
                return false
            }

            override fun readControlKey(): Boolean = ctrlState != ModifierState.OFF
            override fun readAltKey(): Boolean = altState != ModifierState.OFF
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
                // Auto-clear single-use sticky modifiers after typing a character from soft keyboard
                if (ctrlState == ModifierState.ON) {
                    ctrlState = ModifierState.OFF
                }
                if (altState == ModifierState.ON) {
                    altState = ModifierState.OFF
                }
                return false
            }

            override fun onEmulatorSet() {}
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

    // Start / restart shell when the active device or protocol support changes
    LaunchedEffect(activeDeviceId, supportsShellV2) {
        if (activeClient != null && !activeClient.isClosed) {
            val needsRestart = shellVm.currentDeviceId != activeDeviceId ||
                shellVm.currentSupportsShellV2 != supportsShellV2 ||
                sessionState == ShellSessionState.CLOSED ||
                sessionState == ShellSessionState.ERROR
            if (needsRestart) {
                shellVm.startSession(activeDeviceId, activeClient, supportsShellV2)
            }
        } else {
            shellVm.stopSession()
        }
    }

    if (activeClient == null) {
        NoDeviceState()
        return
    }

    // Extra keys actions
    val onKeyAction: (Int) -> Unit = { keyCode ->
        val ctrl = ctrlState != ModifierState.OFF
        val alt = altState != ModifierState.OFF
        var keyMod = 0
        if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT

        terminalViewRef?.handleKeyCode(keyCode, keyMod)

        if (ctrlState == ModifierState.ON) ctrlState = ModifierState.OFF
        if (altState == ModifierState.ON) altState = ModifierState.OFF
    }

    val onCharAction: (String) -> Unit = { char ->
        val ctrl = ctrlState != ModifierState.OFF
        val alt = altState != ModifierState.OFF

        when (char) {
            "ESC" -> {
                var keyMod = 0
                if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
                if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
                terminalViewRef?.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, keyMod)
                    ?: terminalSession?.write("\u001B")
            }
            "TAB" -> {
                var keyMod = 0
                if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
                if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
                terminalViewRef?.handleKeyCode(KeyEvent.KEYCODE_TAB, keyMod)
                    ?: terminalSession?.write("\t")
            }
            else -> {
                terminalViewRef?.let { view ->
                    char.codePoints().forEach { cp ->
                        view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, cp, ctrl, alt)
                    }
                } ?: run {
                    terminalSession?.write(char)
                }
            }
        }

        if (ctrlState == ModifierState.ON) ctrlState = ModifierState.OFF
        if (altState == ModifierState.ON) altState = ModifierState.OFF
    }

    val onToggleCtrl = {
        ctrlState = when (ctrlState) {
            ModifierState.OFF -> ModifierState.ON
            ModifierState.ON -> ModifierState.OFF
            ModifierState.LOCKED -> ModifierState.OFF
        }
    }
    val onLockCtrl = {
        ctrlState = when (ctrlState) {
            ModifierState.LOCKED -> ModifierState.OFF
            else -> ModifierState.LOCKED
        }
    }
    val onToggleAlt = {
        altState = when (altState) {
            ModifierState.OFF -> ModifierState.ON
            ModifierState.ON -> ModifierState.OFF
            ModifierState.LOCKED -> ModifierState.OFF
        }
    }
    val onLockAlt = {
        altState = when (altState) {
            ModifierState.LOCKED -> ModifierState.OFF
            else -> ModifierState.LOCKED
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding() // Automatically positions extra keys right above the soft keyboard
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
                        val fontPx = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP,
                            13f,
                            ctx.resources.displayMetrics,
                        ).toInt()
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

            // -- Overflow options menu (Clear screen / Restart shell) --
            var showOverflowMenu by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                IconButton(
                    onClick = { showOverflowMenu = true },
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Terminal options",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }

                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.btn_clear)) },
                        leadingIcon = {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                        },
                        onClick = {
                            showOverflowMenu = false
                            shellVm.clearBuffer()
                        },
                    )
                    if (sessionState == ShellSessionState.CLOSED || sessionState == ShellSessionState.ERROR) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cd_restart_shell)) },
                            leadingIcon = {
                                Icon(Icons.Filled.RestartAlt, contentDescription = null)
                            },
                            onClick = {
                                showOverflowMenu = false
                                val client = adbViewModel.activeClient()
                                if (client != null && !client.isClosed) {
                                    shellVm.startSession(activeDeviceId, client, supportsShellV2)
                                }
                            },
                        )
                    }
                }
            }
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

        // -- 2x7 Termux Extra-Keys Panel ----------------------------
        ExtraKeysPanel(
            ctrlState = ctrlState,
            altState = altState,
            onToggleCtrl = onToggleCtrl,
            onLockCtrl = onLockCtrl,
            onToggleAlt = onToggleAlt,
            onLockAlt = onLockAlt,
            onKeyAction = onKeyAction,
            onCharAction = onCharAction,
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
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Connect an ADB device above to start a shell session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
