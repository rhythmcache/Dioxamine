package io.github.rhythmcache.dioxamine.adb.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rhythmcache.adb.AdbClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the ADB interactive shell backed by Termux [AdbTerminalSession].
 */
class ShellViewModel : ViewModel() {

    private var session: AdbTerminalSession? = null
    private var stateJob: Job? = null
    private var errorJob: Job? = null

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

    var currentSupportsShellV2: Boolean = true
        private set

    // -- Session lifecycle -------------------------------------------

    /**
     * Start (or restart) an interactive shell on [client].
     * Closes any existing session first.
     */
    fun startSession(
        deviceId: String?,
        client: AdbClient,
        supportsShellV2: Boolean = true,
    ) {
        stopSession()
        currentDeviceId = deviceId
        currentSupportsShellV2 = supportsShellV2

        val newSession = AdbTerminalSession(
            client = client,
            scope = viewModelScope,
            supportsShellV2 = supportsShellV2,
        )
        session = newSession
        _terminalSession.value = newSession

        stateJob = viewModelScope.launch {
            newSession.state.collect { _sessionState.value = it }
        }
        errorJob = viewModelScope.launch {
            newSession.errorMessage.collect { _errorMessage.value = it }
        }
    }

    /** Close the current session (if any). */
    fun stopSession() {
        stateJob?.cancel()
        stateJob = null
        errorJob?.cancel()
        errorJob = null
        session?.destroy()
        session = null
        _terminalSession.value = null
        _sessionState.value = ShellSessionState.CLOSED
    }

    // -- Commands ----------------------------------------------------

    fun sendRaw(bytes: ByteArray) { session?.sendRaw(bytes) }
    fun sendInterrupt() { session?.sendInterrupt() }
    fun sendEof()       { session?.sendEof() }
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
