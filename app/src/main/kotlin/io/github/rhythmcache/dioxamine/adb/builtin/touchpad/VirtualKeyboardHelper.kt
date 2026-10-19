package io.github.rhythmcache.dioxamine.adb.builtin.touchpad

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Common contract for any controller that supports real-time hardware USB HID keyboard
 * events and fallback UTF-8 text injection.
 */
interface VirtualKeyboardController {
    fun sendKeyStroke(hidKeyCode: Int, modifiers: Int = 0)
    fun sendInjectText(text: String)
}

/**
 * Action type processed sequentially by the virtual keyboard FIFO coroutine worker.
 */
sealed interface KeyboardAction {
    data class Stroke(val keyCode: Int, val modifiers: Int) : KeyboardAction
    data class Text(val text: String) : KeyboardAction
}

/**
 * Shared helper for mapping user text and IME events to hardware USB HID keystrokes
 * and fallback UTF-8 text injection across Touchpad and Scrcpy screens.
 */
object VirtualKeyboardHelper {

    /**
     * Calculates the byte length of the next valid UTF-8 chunk not exceeding [maxLen].
     *
     * Preconditions & Contract:
     * - [maxLen] must be at least 4 bytes to guarantee room for any valid UTF-8 code point.
     * - [bytes] must contain valid UTF-8 encoded data.
     * - [offset] must point to a valid UTF-8 character start boundary.
     *
     * Guarantees:
     * - Returned chunk length is strictly positive (when [offset] < [bytes.size]).
     * - Returned chunk length is guaranteed to be <= [maxLen].
     * - Multibyte UTF-8 sequences (2, 3, or 4 bytes) are never split across chunks.
     */
    fun safeUtf8ChunkLength(bytes: ByteArray, offset: Int, maxLen: Int = 300): Int {
        require(maxLen >= 4) { "maxLen must be at least 4 bytes to accommodate a complete UTF-8 code point, got $maxLen" }
        if (offset >= bytes.size) return 0
        if (offset + maxLen >= bytes.size) return bytes.size - offset

        var end = offset + maxLen
        while (end > offset && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }

        if (end == offset) {
            val lead = bytes[offset].toInt() and 0xFF
            val charLen = when {
                lead < 0x80 -> 1
                lead in 0xC0..0xDF -> 2
                lead in 0xE0..0xEF -> 3
                lead in 0xF0..0xF7 -> 4
                else -> 1
            }
            return minOf(bytes.size - offset, minOf(charLen, maxLen))
        }

        return end - offset
    }

    /**
     * Translates a string into a sequential stream of USB HID keystrokes (for standard ASCII)
     * and chunked text injection (for non-ASCII / Unicode / Emojis), maintaining strict FIFO order.
     */
    fun typeText(
        text: String,
        controller: VirtualKeyboardController
    ) {
        val unmappedBuffer = java.lang.StringBuilder()

        fun flushUnmapped() {
            if (unmappedBuffer.isNotEmpty()) {
                controller.sendInjectText(unmappedBuffer.toString())
                unmappedBuffer.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val mapping = HidKeyMapper.charToHid(ch)
            if (mapping != null) {
                flushUnmapped()
                val mods = if (mapping.shift) HidKeyCodes.MOD_LEFT_SHIFT else HidKeyCodes.MOD_NONE
                controller.sendKeyStroke(mapping.hidKeyCode, mods)
                i++
            } else {
                if (Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    unmappedBuffer.append(ch)
                    unmappedBuffer.append(text[i + 1])
                    i += 2
                } else {
                    unmappedBuffer.append(ch)
                    i++
                }
                if (unmappedBuffer.length >= 100) {
                    flushUnmapped()
                }
            }
        }
        flushUnmapped()
    }

    /**
     * Processes IME buffer changes against a sentinel anchor buffer ("  ") to capture
     * typed characters, deletions, and autocompleted text in real time.
     *
     * Architectural Model & Intent:
     * - The target device is connected via USB HID (or scrcpy control stream), which is a
     *   write-only hardware input protocol. The host has no query access to the target app's
     *   internal EditText state, cursor position, or selection.
     * - The local Compose [BasicTextField] uses a 2-space sentinel anchor ("  ") so that the
     *   host IME (such as Gboard) allows backspacing (which emits hardware Backspace keycodes)
     *   and streaming typed text without local buffering.
     * - For optimal behavior without conflicting multi-stage underline composition spans,
     *   the Compose [BasicTextField] should specify [KeyboardType.Password] (or textNoSuggestions)
     *   with [autoCorrectEnabled = false].
     */
    fun handleImeValueChange(
        newBuffer: TextFieldValue,
        anchorText: String = "  ",
        controller: VirtualKeyboardController
    ): TextFieldValue {
        if (newBuffer.text.startsWith(anchorText)) {
            val inserted = newBuffer.text.substring(anchorText.length)
            if (inserted.isNotEmpty()) {
                typeText(inserted, controller)
            }
        } else if (newBuffer.text.length < anchorText.length) {
            val deletedCount = anchorText.length - newBuffer.text.length
            repeat(deletedCount) {
                controller.sendKeyStroke(HidKeyCodes.KEY_BACKSPACE)
            }
        } else if (newBuffer.text != anchorText) {
            typeText(newBuffer.text, controller)
        }
        return TextFieldValue(anchorText, selection = TextRange(2))
    }
}
