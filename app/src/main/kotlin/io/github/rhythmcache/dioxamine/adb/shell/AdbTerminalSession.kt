package io.github.rhythmcache.dioxamine.adb.shell

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbInteractiveSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.charset.StandardCharsets

/**
 * An ADB-backed [TerminalSession] connecting [TerminalEmulator] to an interactive PTY session
 * over [AdbInteractiveSession].
 */
class AdbTerminalSession(
    private val client: AdbClient,
    private val scope: CoroutineScope,
    private val transcriptRows: Int = 2000,
) : TerminalSession() {

    companion object {
        private const val TAG = "AdbTerminalSession"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var adbSession: AdbInteractiveSession? = null
    private var emulator: TerminalEmulator? = null
    private var sessionClient: TerminalSessionClient? = null

    private var active = false
    private var exitCode: Int? = null

    // Single ordered writer Channel drained by ONE dedicated coroutine
    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var writerJob: Job? = null
    private var sessionJob: Job? = null

    private val _state = MutableStateFlow(ShellSessionState.IDLE)
    val state: StateFlow<ShellSessionState> = _state

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    var onScreenUpdate: (() -> Unit)? = null

    override fun getEmulator(): TerminalEmulator? = emulator

    override fun updateTerminalSessionClient(client: TerminalSessionClient?) {
        this.sessionClient = client
        emulator?.updateTerminalSessionClient(client)
    }

    override fun isRunning(): Boolean = active

    override fun getExitStatus(): Int = exitCode ?: 0

    override fun getPid(): Int = 0

    override fun getCwd(): String? = null

    override fun getTitle(): String? = emulator?.title

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val boundedCols = columns.coerceAtLeast(4)
        val boundedRows = rows.coerceAtLeast(4)
        Log.d(TAG, "updateSize: cols=$boundedCols, rows=$boundedRows, cellW=$cellWidthPixels, cellH=$cellHeightPixels")

        if (emulator == null) {
            initialize(boundedCols, boundedRows, cellWidthPixels, cellHeightPixels)
        } else {
            emulator?.resize(boundedCols, boundedRows, cellWidthPixels, cellHeightPixels)
            scope.launch(Dispatchers.IO) {
                try {
                    adbSession?.resize(
                        cols = boundedCols,
                        rows = boundedRows,
                        xPixel = cellWidthPixels * boundedCols,
                        yPixel = cellHeightPixels * boundedRows,
                    )
                    Log.d(TAG, "Sent resize to adbd: cols=$boundedCols, rows=$boundedRows")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending resize to adbd: ${e.message}")
                }
            }
        }
    }

    private fun initialize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val emu = TerminalEmulator(
            this,
            columns,
            rows,
            cellWidthPixels,
            cellHeightPixels,
            transcriptRows,
            sessionClient,
        )
        this.emulator = emu

        // 1. Start ONE ordered writer coroutine draining writeChannel
        writerJob = scope.launch(Dispatchers.IO) {
            for (chunk in writeChannel) {
                val s = adbSession ?: continue
                try {
                    s.write(chunk)
                } catch (e: Exception) {
                    Log.w(TAG, "Error writing to adb session: ${e.message}")
                }
            }
        }

        // 2. Open interactive shell v2 with PTY
        sessionJob = scope.launch(Dispatchers.IO) {
            try {
                _state.value = ShellSessionState.STARTING
                _errorMessage.value = null

                val session = client.openInteractiveShell(
                    terminalType = "xterm-256color",
                    rows = rows,
                    cols = columns,
                )
                adbSession = session
                active = true

                withContext(Dispatchers.Main) {
                    _state.value = ShellSessionState.ACTIVE
                }

                // 3. Feed outputFlow to emulator.append() on the Main Thread
                session.outputFlow.collect { bytes ->
                    withContext(Dispatchers.Main) {
                        emulator?.append(bytes, bytes.size)
                        notifyScreenUpdate()
                    }
                }

                // Normal exit
                val code = session.exitCode.await()
                withContext(Dispatchers.Main) {
                    handleExit(code)
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    handleExit(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ADB shell session error", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = e.message
                    _state.value = ShellSessionState.ERROR
                    val errText = "\r\n[Session error: ${e.message}]\r\n"
                    val bytes = errText.toByteArray(StandardCharsets.UTF_8)
                    emulator?.append(bytes, bytes.size)
                    notifyScreenUpdate()
                }
            }
        }
    }

    private fun handleExit(code: Int?) {
        active = false
        exitCode = code
        val exitText = if (code != null) {
            "\r\n[Process completed (code $code) - press restart]\r\n"
        } else {
            "\r\n[Process completed - press restart]\r\n"
        }
        val bytes = exitText.toByteArray(StandardCharsets.UTF_8)
        emulator?.append(bytes, bytes.size)
        notifyScreenUpdate()

        if (_state.value != ShellSessionState.ERROR) {
            _state.value = ShellSessionState.CLOSED
        }
        sessionClient?.onSessionFinished(this)
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        val chunk = data.copyOfRange(offset, offset + count)
        writeChannel.trySend(chunk)
    }

    /** Write a command string with newline. */
    fun sendCommand(command: String) {
        val line = "$command\n"
        writeChannel.trySend(line.toByteArray(StandardCharsets.UTF_8))
    }

    /** Send raw bytes to stdin. */
    fun sendRaw(bytes: ByteArray) {
        writeChannel.trySend(bytes)
    }

    fun sendInterrupt() = sendRaw(byteArrayOf(0x03)) // Ctrl+C
    fun sendEof()       = sendRaw(byteArrayOf(0x04)) // Ctrl+D
    fun sendSuspend()   = sendRaw(byteArrayOf(0x1A)) // Ctrl+Z
    fun sendTab()       = sendRaw(byteArrayOf(0x09)) // Tab

    override fun reset() {
        mainHandler.post {
            emulator?.reset()
            notifyScreenUpdate()
        }
    }

    override fun finishIfRunning() {
        active = false
        scope.launch(Dispatchers.IO) {
            try {
                adbSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing adb session: ${e.message}")
            }
        }
    }

    private fun notifyScreenUpdate() {
        sessionClient?.onTextChanged(this)
        onScreenUpdate?.invoke()
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        sessionClient?.onTitleChanged(this)
    }

    override fun onCopyTextToClipboard(text: String?) {
        if (text != null) sessionClient?.onCopyTextToClipboard(this, text)
    }

    override fun onPasteTextFromClipboard() {
        sessionClient?.onPasteTextFromClipboard(this)
    }

    override fun onBell() {
        sessionClient?.onBell(this)
    }

    override fun onColorsChanged() {
        sessionClient?.onColorsChanged(this)
    }

    fun destroy() {
        finishIfRunning()
        writeChannel.close()
        writerJob?.cancel()
        sessionJob?.cancel()
    }
}
