package io.github.rhythmcache.dioxamine.adb.shell

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe ring buffer that accumulates terminal output and exposes it
 * as a list of lines.
 *
 * Handles:
 * - `\n` (newline) - completes the current line
 * - `\r\n` (CRLF) - normalised to `\n`
 * - `\r` (carriage return) - resets current line position (for progress bars)
 * - Partial lines at the end of a chunk (no trailing `\n`)
 *
 * Oldest lines are dropped once the buffer exceeds [maxLines].
 */
class ShellBuffer(private val maxLines: Int = 10_000) {

    private val lock = ReentrantLock()
    private val lines = ArrayDeque<String>()
    private val incompleteLine = StringBuilder()
    private var cursorCol = 0
    private var baseIndex = 0L

    /**
     * Append a raw text chunk from the terminal stream.
     * May contain zero or more newlines and partial lines.
     */
    fun append(text: String) = lock.withLock {
        val normalised = text.replace("\r\n", "\n")
        for (ch in normalised) {
            when (ch) {
                '\n' -> {
                    lines.addLast(incompleteLine.toString())
                    incompleteLine.clear()
                    cursorCol = 0
                    while (lines.size > maxLines) {
                        lines.removeFirst()
                        baseIndex++
                    }
                }
                '\r' -> {
                    cursorCol = 0
                }
                '\b' -> {
                    if (cursorCol > 0) {
                        cursorCol--
                        if (cursorCol < incompleteLine.length) incompleteLine.deleteCharAt(cursorCol)
                    }
                }
                else -> {
                    if (cursorCol < incompleteLine.length) {
                        incompleteLine.setCharAt(cursorCol, ch)
                    } else {
                        incompleteLine.append(ch)
                    }
                    cursorCol++
                }
            }
        }
    }

    /** Absolute index of the oldest completed line still retained. */
    fun oldestAvailableIndex(): Long = lock.withLock { baseIndex }

    /** Absolute index one past the newest completed line (i.e. total completed lines ever appended). */
    fun completedLineCount(): Long = lock.withLock { baseIndex + lines.size }

    /** Completed lines in absolute index range [from, until). */
    fun linesInRange(from: Long, until: Long): List<String> = lock.withLock {
        val start = (from - baseIndex).coerceAtLeast(0L).toInt()
        val end = (until - baseIndex).coerceAtMost(lines.size.toLong()).toInt()
        if (start >= end) return emptyList()
        val result = ArrayList<String>(end - start)
        var idx = 0
        for (line in lines) {
            if (idx >= start && idx < end) result.add(line)
            if (idx >= end) break
            idx++
        }
        result
    }

    /** The current in-progress (not yet newline-terminated) line. */
    fun currentIncompleteLine(): String = lock.withLock { incompleteLine.toString() }

    /** Clear the entire buffer. */
    fun clear() = lock.withLock {
        baseIndex += lines.size   // monotonic - never reset to 0
        lines.clear()
        incompleteLine.clear()
        cursorCol = 0
    }

    /** Current number of lines (including any incomplete trailing line). */
    val lineCount: Int
        get() = lock.withLock {
            lines.size + if (incompleteLine.isNotEmpty()) 1 else 0
        }
}
