package io.github.rhythmcache.dioxamine.adb.shell

import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.lifecycle.ViewModel
import com.termux.terminal.AdbTerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbInteractiveSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the ADB interactive shell, now backed by Termux's
 * [com.termux.terminal.TerminalEmulator] via [AdbTerminalSession].
 *
 * Owns the session lifecycle and exposes it for the Compose/View layer.
 */
class ShellViewModel : ViewModel() {

    private var session: AdbTerminalSession? = null
    private var terminalView: TerminalView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionState = MutableStateFlow(ShellSessionState.IDLE)
    val sessionState: StateFlow<ShellSessionState> = _sessionState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    var currentDeviceId: String? = null
        private set

    /** Bind the TerminalView (called from Compose AndroidView factory). */
    fun bindTerminalView(view: TerminalView) {
        terminalView = view
        // If a session already exists, re-attach
        session?.let { view.attachSession(it) }
    }

    /**
     * Start (or restart) an interactive ADB shell on [client].
     * Closes any existing session first.
     */
    fun startSession(deviceId: String?, client: AdbClient) {
        stopSession()
        currentDeviceId = deviceId
        _sessionState.value = ShellSessionState.STARTING
        _errorMessage.value = null

        val sessionClient = createSessionClient()
        val newSession = AdbTerminalSession(2000, sessionClient)
        session = newSession

        scope.launch {
            try {
                val interactiveSession = client.openInteractiveShell(
                    terminalType = "xterm-256color",
                    rows = 24,
                    cols = 80,
                )

                // Initialize with default size; TerminalView will call updateSize()
                // once it has measured and will set the real dimensions + send resize.
                withContext(Dispatchers.Main) {
                    newSession.initializeEmulator(80, 24, 12, 24, interactiveSession)
                    terminalView?.attachSession(newSession)
                    _sessionState.value = ShellSessionState.ACTIVE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = e.message ?: "Failed to open shell"
                    _sessionState.value = ShellSessionState.ERROR
                }
            }
        }
    }

    /** Close the current session (if any). */
    fun stopSession() {
        session?.finishIfRunning()
        session = null
        _sessionState.value = ShellSessionState.IDLE
    }

    // -- Commands (forwarded to session via raw writes) ---------------

    fun sendCommand(command: String) {
        session?.write("$command\n")
    }

    fun sendRaw(bytes: ByteArray) {
        session?.write(bytes, 0, bytes.size)
    }

    fun sendInterrupt() = sendRaw(byteArrayOf(0x03))
    fun sendEof() = sendRaw(byteArrayOf(0x04))
    fun sendTab() = sendRaw(byteArrayOf(0x09))
    fun sendSuspend() = sendRaw(byteArrayOf(0x1A))

    /** Clear scrollback and redraw. */
    fun clearBuffer() {
        session?.let { s ->
            s.getEmulator()?.clearScrollCounter()
            terminalView?.let { tv ->
                tv.setTopRow(0)
                tv.invalidate()
            }
        }
    }

    // -- Cleanup ---

    override fun onCleared() {
        session?.destroy()
        scope.cancel()
        super.onCleared()
    }

    // -- TerminalSessionClient implementation ---

    private fun createSessionClient(): TerminalSessionClient {
        return object : TerminalSessionClient {
            override fun onTextChanged(@NonNull changedSession: AdbTerminalSession) {
                terminalView?.onScreenUpdated()
            }

            override fun onTitleChanged(@NonNull changedSession: AdbTerminalSession) {
                // Could update UI title if desired
            }

            override fun onSessionFinished(@NonNull finishedSession: AdbTerminalSession) {
                _sessionState.value = ShellSessionState.CLOSED
            }

            override fun onCopyTextToClipboard(@NonNull session: AdbTerminalSession, text: String?) {
                // Handled by TerminalView's text selection
            }

            override fun onPasteTextFromClipboard(@Nullable session: AdbTerminalSession?) {
                // Handled by TerminalView
            }

            override fun onBell(@NonNull session: AdbTerminalSession) {
                // Could play a sound/vibrate
            }

            override fun onColorsChanged(@NonNull session: AdbTerminalSession) {
                terminalView?.invalidate()
            }

            override fun onTerminalCursorStateChange(state: Boolean) {
                // Cursor visibility change
            }

            override fun setTerminalShellPid(@NonNull session: AdbTerminalSession, pid: Int) {
                // Not applicable for ADB sessions
            }

            override fun getTerminalCursorStyle(): Int = 0

            override fun logError(tag: String?, message: String?) {
                Log.e(tag ?: LOG_TAG, message ?: "")
            }
            override fun logWarn(tag: String?, message: String?) {
                Log.w(tag ?: LOG_TAG, message ?: "")
            }
            override fun logInfo(tag: String?, message: String?) {
                Log.i(tag ?: LOG_TAG, message ?: "")
            }
            override fun logDebug(tag: String?, message: String?) {
                Log.d(tag ?: LOG_TAG, message ?: "")
            }
            override fun logVerbose(tag: String?, message: String?) {
                Log.v(tag ?: LOG_TAG, message ?: "")
            }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
                Log.e(tag ?: LOG_TAG, message, e)
            }
            override fun logStackTrace(tag: String?, e: Exception?) {
                Log.e(tag ?: LOG_TAG, "", e)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "ShellViewModel"
    }
}
