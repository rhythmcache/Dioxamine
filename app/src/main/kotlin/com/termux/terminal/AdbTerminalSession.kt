package com.termux.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import io.github.rhythmcache.adb.AdbInteractiveSession
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * A terminal session that runs over an ADB Shell v2 PTY stream.
 *
 * Uses [AdbInteractiveSession] (from adb-kt) which provides:
 * - Shell v2 protocol framing (stdin/stdout/stderr/exit)
 * - PTY-based session with `TERM=xterm-256color`
 * - **Window resize** via `SIGWINCH` (`resize()`)
 *
 * All terminal emulation is handled by Termux's [TerminalEmulator].
 * **No JNI or native code is used.**
 */
class AdbTerminalSession(
    private val transcriptRows: Int?,
    client: TerminalSessionClient,
) : TerminalOutput() {

    companion object {
        private const val MSG_NEW_INPUT = 1
        private const val MSG_SESSION_FINISHED = 4
    }

    @JvmField
    val mHandle: String = UUID.randomUUID().toString()

    @JvmField
    var mEmulator: TerminalEmulator? = null

    /** Queue: reader coroutine writes bytes from ADB, main thread reads to feed emulator. */
    private val mProcessToTerminalIOQueue = ByteQueue(64 * 1024)

    /** Queue: main thread writes user input, writer coroutine reads and sends to ADB. */
    private val mTerminalToProcessIOQueue = ByteQueue(4096)

    private val mUtf8InputBuffer = ByteArray(5)

    @JvmField
    var mClient: TerminalSessionClient = client

    private var mInteractiveSession: AdbInteractiveSession? = null

    @Volatile
    private var mRunning = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set by the application for user identification of session. */
    @JvmField
    var mSessionName: String? = null

    @JvmField
    val mMainThreadHandler: Handler = MainThreadHandler()

    fun updateTerminalSessionClient(client: TerminalSessionClient) {
        mClient = client
        mEmulator?.updateTerminalSessionClient(client)
    }

    /**
     * Initialize the emulator and start I/O coroutines.
     *
     * @param interactiveSession An [AdbInteractiveSession] from
     *   `AdbClient.openInteractiveShell()`.
     */
    fun initializeEmulator(
        columns: Int,
        rows: Int,
        cellWidthPixels: Int,
        cellHeightPixels: Int,
        interactiveSession: AdbInteractiveSession,
    ) {
        mEmulator = TerminalEmulator(
            this, columns, rows, cellWidthPixels, cellHeightPixels,
            transcriptRows ?: 2000, mClient,
        )
        mInteractiveSession = interactiveSession
        mRunning = true

        // Reader coroutine: ADB Shell v2 output → queue → emulator (via Handler)
        scope.launch {
            try {
                interactiveSession.outputFlow.collect { chunk ->
                    if (!mRunning) return@collect
                    if (!mProcessToTerminalIOQueue.write(chunk, 0, chunk.size)) return@collect
                    mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT)
                }
            } catch (_: Exception) {
                // Stream closed or error
            }
            mMainThreadHandler.sendMessage(
                mMainThreadHandler.obtainMessage(MSG_SESSION_FINISHED, 0),
            )
        }

        // Writer coroutine: queue → ADB Shell v2 stdin
        scope.launch {
            val buffer = ByteArray(4096)
            try {
                while (mRunning) {
                    val bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true)
                    if (bytesToWrite == -1) return@launch
                    interactiveSession.write(buffer.copyOf(bytesToWrite))
                }
            } catch (_: Exception) {
                // Stream closed
            }
        }
    }

    /**
     * Resize the terminal. Updates the emulator dimensions AND sends
     * a `SIGWINCH` to the remote shell so apps like htop/vim/nano
     * adapt to the new size.
     */
    fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val emu = mEmulator ?: return
        emu.resize(columns, rows, cellWidthPixels, cellHeightPixels)

        // Notify the remote shell of the new window size
        val session = mInteractiveSession ?: return
        scope.launch {
            try {
                session.resize(columns, rows, cellWidthPixels, cellHeightPixels)
            } catch (_: Exception) {
                // Ignore resize failures (stream may be closing)
            }
        }
    }

    fun getTitle(): String? = mEmulator?.title

    fun getEmulator(): TerminalEmulator? = mEmulator

    /** Write raw bytes to device stdin (Shell v2 framed). */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (mRunning) mTerminalToProcessIOQueue.write(data, offset, count)
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        require(codePoint <= 1114111 && (codePoint < 0xD800 || codePoint > 0xDFFF)) {
            "Invalid code point: $codePoint"
        }

        var pos = 0
        if (prependEscape) mUtf8InputBuffer[pos++] = 27

        when {
            codePoint <= 0x7F -> {
                mUtf8InputBuffer[pos++] = codePoint.toByte()
            }
            codePoint <= 0x7FF -> {
                mUtf8InputBuffer[pos++] = (0xC0 or (codePoint shr 6)).toByte()
                mUtf8InputBuffer[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            codePoint <= 0xFFFF -> {
                mUtf8InputBuffer[pos++] = (0xE0 or (codePoint shr 12)).toByte()
                mUtf8InputBuffer[pos++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                mUtf8InputBuffer[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            else -> {
                mUtf8InputBuffer[pos++] = (0xF0 or (codePoint shr 18)).toByte()
                mUtf8InputBuffer[pos++] = (0x80 or ((codePoint shr 12) and 0x3F)).toByte()
                mUtf8InputBuffer[pos++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                mUtf8InputBuffer[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
        }
        write(mUtf8InputBuffer, 0, pos)
    }

    fun notifyScreenUpdate() {
        mClient.onTextChanged(this)
    }

    fun reset() {
        mEmulator?.reset()
        notifyScreenUpdate()
    }

    fun finishIfRunning() {
        if (isRunning) {
            mRunning = false
            mTerminalToProcessIOQueue.close()
            mProcessToTerminalIOQueue.close()
            runCatching { mInteractiveSession?.close() }
        }
    }

    private fun cleanupResources() {
        mRunning = false
        mTerminalToProcessIOQueue.close()
        mProcessToTerminalIOQueue.close()
        runCatching { mInteractiveSession?.close() }
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        mClient.onTitleChanged(this)
    }

    val isRunning: Boolean
        @Synchronized get() = mRunning

    val exitStatus: Int
        @Synchronized get() = 0

    override fun onCopyTextToClipboard(text: String) {
        mClient.onCopyTextToClipboard(this, text)
    }

    override fun onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this)
    }

    override fun onBell() {
        mClient.onBell(this)
    }

    override fun onColorsChanged() {
        mClient.onColorsChanged(this)
    }

    fun destroy() {
        finishIfRunning()
        scope.cancel()
    }

    @SuppressLint("HandlerLeak")
    inner class MainThreadHandler : Handler(Looper.getMainLooper()) {
        private val mReceiveBuffer = ByteArray(64 * 1024)

        override fun handleMessage(msg: Message) {
            val bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)
            if (bytesRead > 0) {
                mEmulator?.append(mReceiveBuffer, bytesRead)
                notifyScreenUpdate()
            }

            if (msg.what == MSG_SESSION_FINISHED) {
                cleanupResources()

                val exitDescription = "\r\n[ADB session ended - press Enter]"
                val bytes = exitDescription.toByteArray(StandardCharsets.UTF_8)
                mEmulator?.append(bytes, bytes.size)
                notifyScreenUpdate()
                mClient.onSessionFinished(this@AdbTerminalSession)
            }
        }
    }
}
