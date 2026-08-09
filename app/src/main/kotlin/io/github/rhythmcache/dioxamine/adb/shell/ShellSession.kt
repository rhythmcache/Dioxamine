package io.github.rhythmcache.dioxamine.adb.shell

import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Lifecycle states for a shell session.
 */
enum class ShellSessionState {
    IDLE, STARTING, ACTIVE, CLOSED, ERROR
}

/**
 * Manages an interactive ADB shell session over a single persistent [AdbStream].
 *
 * Opening `"shell:"` (with no command) starts an interactive `/system/bin/sh`
 * on the device.  Working-directory changes, environment variables, and all
 * other shell state persist for the lifetime of the stream.
 *
 * Output arrives chunk-by-chunk via [output] (a [SharedFlow]).  The caller is
 * responsible for collecting and rendering it.
 */
class ShellSession {

    private var stream: AdbStream? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val utf8Decoder = Charsets.UTF_8.newDecoder().apply {
        onMalformedInput(CodingErrorAction.REPLACE)
        onUnmappableCharacter(CodingErrorAction.REPLACE)
    }
    private var leftoverBytes: ByteArray = ByteArray(0)

    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 128)
    /** Raw text chunks as they arrive from the device. */
    val output: SharedFlow<String> = _output

    private val _state = MutableStateFlow(ShellSessionState.IDLE)
    /** Current session lifecycle state. */
    val state: StateFlow<ShellSessionState> = _state

    private val _errorMessage = MutableStateFlow<String?>(null)
    /** Human-readable error detail when [state] is [ShellSessionState.ERROR]. */
    val errorMessage: StateFlow<String?> = _errorMessage

    private fun decodeChunk(chunk: ByteArray): String {
        val combined = if (leftoverBytes.isEmpty()) chunk else leftoverBytes + chunk
        val input = ByteBuffer.wrap(combined)
        val output = CharBuffer.allocate(combined.size)

        utf8Decoder.decode(input, output, false)

        leftoverBytes = if (input.hasRemaining()) {
            ByteArray(input.remaining()).also { input.get(it) }
        } else {
            ByteArray(0)
        }

        output.flip()
        return output.toString()
    }

    private fun flushLeftoverBytes(): String {
        if (leftoverBytes.isEmpty()) return ""
        val input = ByteBuffer.wrap(leftoverBytes)
        val output = CharBuffer.allocate(leftoverBytes.size)
        utf8Decoder.decode(input, output, true)
        utf8Decoder.flush(output)
        leftoverBytes = ByteArray(0)
        output.flip()
        return output.toString()
    }

    /**
     * Open an interactive shell on [client].
     * If a session is already active this is a no-op.
     */
    fun start(client: AdbClient) {
        if (_state.value == ShellSessionState.ACTIVE ||
            _state.value == ShellSessionState.STARTING
        ) return

        _state.value = ShellSessionState.STARTING
        _errorMessage.value = null

        readerJob = scope.launch {
            try {
                utf8Decoder.reset()
                leftoverBytes = ByteArray(0)

                val s = client.open("shell:")
                stream = s
                _state.value = ShellSessionState.ACTIVE

                // Continuous reader - emits chunks the instant they arrive
                while (isActive) {
                    val chunk = s.recv() ?: break          // null = EOF
                    val text = decodeChunk(chunk)
                    if (text.isNotEmpty()) {
                        _output.emit(text)
                    }
                }

                val finalRemaining = flushLeftoverBytes()
                if (finalRemaining.isNotEmpty()) {
                    _output.emit(finalRemaining)
                }

                // Stream ended normally (device closed the shell)
                _state.value = ShellSessionState.CLOSED
            } catch (e: CancellationException) {
                // Coroutine was cancelled (close() was called) - not an error
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    _errorMessage.value = e.message ?: "Unknown error"
                    _state.value = ShellSessionState.ERROR
                }
            }
        }
    }

    // - Writing to the shell --------------------------------------------

    /**
     * Send a command string followed by a newline.
     * The device shell will echo it back (normal TTY behaviour).
     */
    fun sendCommand(command: String) {
        val s = stream ?: return
        scope.launch {
            try {
                s.writeLine(command)
            } catch (e: Exception) {
                _errorMessage.value = e.message
                _state.value = ShellSessionState.ERROR
            }
        }
    }

    /** Send raw bytes (for control characters). */
    fun sendRaw(bytes: ByteArray) {
        val s = stream ?: return
        scope.launch {
            try {
                s.write(bytes)
            } catch (e: Exception) {
                _errorMessage.value = e.message
                _state.value = ShellSessionState.ERROR
            }
        }
    }

    /** Ctrl+C - SIGINT the foreground process. */
    fun sendInterrupt() = sendRaw(byteArrayOf(0x03))

    /** Ctrl+D - send EOF. */
    fun sendEof() = sendRaw(byteArrayOf(0x04))

    /** Ctrl+Z - SIGTSTP (suspend foreground process). */
    fun sendSuspend() = sendRaw(byteArrayOf(0x1A))

    /** Tab - trigger shell autocompletion. */
    fun sendTab() = sendRaw(byteArrayOf(0x09))

    // -- Lifecycle -------------------------------------------------------

    /** Close the stream and cancel the reader. */
    fun close() {
        readerJob?.cancel()
        readerJob = null
        runCatching { stream?.close() }
        stream = null
        if (_state.value != ShellSessionState.ERROR) {
            _state.value = ShellSessionState.CLOSED
        }
    }

    /** Tear down completely (cancel coroutine scope). Call in ViewModel.onCleared(). */
    fun destroy() {
        close()
        scope.cancel()
    }
}
