package io.github.rhythmcache.dioxamine.adb.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rhythmcache.adb.AdbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the ADB interactive shell backed by Termux [AdbTerminalSession].
 */
class ShellViewModel : ViewModel() {

    private var session: AdbTerminalSession? = null

    private val _terminalSession = MutableStateFlow<AdbTerminalSession?>(null)
    /** Currently active terminal session, if any. */
    val terminalSession: StateFlow<AdbTerminalSession?> = _terminalSession

    private val _sessionState = MutableStateFlow(ShellSessionState.IDLE)
    /** Current session lifecycle state. */
    val sessionState: StateFlow<ShellSessionState> = _sessionState

    private val _errorMessage = MutableStateFlow<String?>(null)
    /** Error detail when session is in ERROR state. */
    val errorMessage: StateFlow<String?> = _errorMessage

    var currentDeviceId: String? = null
        private set

    // -- Session lifecycle -------------------------------------------

    /**
     * Start (or restart) an interactive shell on [client].
     * Closes any existing session first.
     */
    fun startSession(deviceId: String?, client: AdbClient) {
        stopSession()
        currentDeviceId = deviceId

        val newSession = AdbTerminalSession(client, viewModelScope)
        session = newSession
        _terminalSession.value = newSession

        viewModelScope.launch {
            newSession.state.collect { _sessionState.value = it }
        }
        viewModelScope.launch {
            newSession.errorMessage.collect { _errorMessage.value = it }
        }
    }

    /** Close the current session (if any). */
    fun stopSession() {
        session?.destroy()
        session = null
        _terminalSession.value = null
        _sessionState.value = ShellSessionState.CLOSED
    }

    // -- Commands ----------------------------------------------------

    fun sendRaw(bytes: ByteArray) { session?.sendRaw(bytes) }
    fun sendInterrupt() { session?.sendInterrupt() }
    fun sendEof()       { session?.sendEof() }
    fun sendTab()       { session?.sendTab() }
    fun sendSuspend()   { session?.sendSuspend() }

    // -- Buffer management -------------------------------------------

    /** Clear terminal output. */
    fun clearBuffer() {
        session?.reset()
    }

    // -- Cleanup -----------------------------------------------------

    override fun onCleared() {
        session?.destroy()
        super.onCleared()
    }
}
