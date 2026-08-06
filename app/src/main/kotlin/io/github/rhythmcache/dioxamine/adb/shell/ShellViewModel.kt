package io.github.rhythmcache.dioxamine.adb.shell

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rhythmcache.adb.AdbClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel for the ADB interactive shell.
 *
 * Owns the [ShellSession], collects raw output into a [ShellBuffer],
 * parses ANSI colours, and exposes styled lines plus command history
 * for the Compose UI layer.
 */
class ShellViewModel : ViewModel() {

    private val buffer = ShellBuffer()
    private var session: ShellSession? = null
    private var collectorJob: Job? = null

    // -- Exposed state -----------------------------------------------

    val outputLines: SnapshotStateList<String> = mutableStateListOf()
    var currentLine by mutableStateOf("")
        private set

    private var lastReadIndex = 0L

    private val _sessionState = MutableStateFlow(ShellSessionState.IDLE)
    /** Current session lifecycle state. */
    val sessionState: StateFlow<ShellSessionState> = _sessionState

    private val _errorMessage = MutableStateFlow<String?>(null)
    /** Error detail when session is in ERROR state. */
    val errorMessage: StateFlow<String?> = _errorMessage

    // -- Command history ---------------------------------------------

    private val _history = mutableListOf<String>()
    private var historyIndex = -1

    var currentDeviceId: String? = null
        private set

    // -- Session lifecycle -------------------------------------------

    /**
     * Start (or restart) an interactive shell on [client].
     * Closes any existing session first.
     */
    fun startSession(deviceId: String?, client: AdbClient) {
        stopSession()
        buffer.clear()
        outputLines.clear()
        currentLine = ""
        lastReadIndex = buffer.oldestAvailableIndex()
        currentDeviceId = deviceId

        val newSession = ShellSession()
        session = newSession

        viewModelScope.launch {
            newSession.state.collect { _sessionState.value = it }
        }
        viewModelScope.launch {
            newSession.errorMessage.collect { _errorMessage.value = it }
        }

        collectorJob = viewModelScope.launch {
            val dirty = AtomicBoolean(false)

            launch {
                newSession.output.collect { chunk ->
                    buffer.append(chunk)
                    dirty.set(true)
                }
            }

            launch {
                while (isActive) {
                    if (dirty.compareAndSet(true, false)) {
                        flushToUi()
                    }
                    delay(50)
                }
            }
        }

        newSession.start(client)
    }

    private fun flushToUi() {
        val oldestAvailable = buffer.oldestAvailableIndex()
        if (lastReadIndex < oldestAvailable) {
            lastReadIndex = oldestAvailable
        }

        val total = buffer.completedLineCount()
        if (lastReadIndex < total) {
            outputLines.addAll(buffer.linesInRange(lastReadIndex, total))
            lastReadIndex = total
        }

        currentLine = buffer.currentIncompleteLine()
    }

    /** Close the current session (if any). */
    fun stopSession() {
        collectorJob?.cancel()
        collectorJob = null
        session?.close()
        session = null
    }

    // -- Commands ----------------------------------------------------

    /** Send a command string to the shell (appended to history). */
    fun sendCommand(command: String) {
        if (command.isNotBlank()) {
            _history.add(command)
        }
        historyIndex = _history.size
        session?.sendCommand(command)
    }

    fun sendRaw(bytes: ByteArray) { session?.sendRaw(bytes) }
    fun sendInterrupt() { session?.sendInterrupt() }
    fun sendEof()       { session?.sendEof() }
    fun sendTab()       { session?.sendTab() }
    fun sendSuspend()   { session?.sendSuspend() }

    // -- Buffer management -------------------------------------------

    /** Clear terminal output. */
    fun clearBuffer() {
        buffer.clear()
        outputLines.clear()
        currentLine = ""
        lastReadIndex = buffer.oldestAvailableIndex()
    }

    // -- History navigation ------------------------------------------

    /** Navigate up in history (older). Returns command text or null. */
    fun historyUp(): String? {
        if (_history.isEmpty()) return null
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        return _history[historyIndex]
    }

    /** Navigate down in history (newer). Returns command text or empty string. */
    fun historyDown(): String? {
        if (_history.isEmpty()) return null
        historyIndex = (historyIndex + 1).coerceAtMost(_history.size)
        return if (historyIndex < _history.size) _history[historyIndex] else ""
    }

    // -- Cleanup -----------------------------------------------------

    override fun onCleared() {
        session?.destroy()
        super.onCleared()
    }
}
