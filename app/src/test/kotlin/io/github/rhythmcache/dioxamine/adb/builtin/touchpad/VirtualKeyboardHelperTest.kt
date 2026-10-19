package io.github.rhythmcache.dioxamine.adb.builtin.touchpad

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualKeyboardHelperTest {

    private class RecordingKeyboardController : VirtualKeyboardController {
        val events = mutableListOf<String>()

        override fun sendKeyStroke(hidKeyCode: Int, modifiers: Int) {
            events.add("STROKE:$hidKeyCode:$modifiers")
        }

        override fun sendInjectText(text: String) {
            events.add("TEXT:$text")
        }
    }

    @Test
    fun testTypeTextPureAscii() {
        val controller = RecordingKeyboardController()
        VirtualKeyboardHelper.typeText("Hi!", controller)

        // 'H' (shift + key_H), 'i' (key_I), '!' (shift + key_1)
        assertEquals(3, controller.events.size)
        assertTrue(controller.events.all { it.startsWith("STROKE:") })
        assertEquals("STROKE:${HidKeyCodes.KEY_H}:${HidKeyCodes.MOD_LEFT_SHIFT}", controller.events[0])
        assertEquals("STROKE:${HidKeyCodes.KEY_I}:${HidKeyCodes.MOD_NONE}", controller.events[1])
        assertEquals("STROKE:${HidKeyCodes.KEY_1}:${HidKeyCodes.MOD_LEFT_SHIFT}", controller.events[2])
    }

    @Test
    fun testTypeTextUnicodeFallbackAndEmojiSurrogates() {
        val controller = RecordingKeyboardController()
        VirtualKeyboardHelper.typeText("A🚀B", controller)

        assertEquals(3, controller.events.size)
        // 'A' mapped to HID
        assertEquals("STROKE:${HidKeyCodes.KEY_A}:${HidKeyCodes.MOD_LEFT_SHIFT}", controller.events[0])
        // '🚀' (surrogate pair) batched and flushed via sendInjectText
        assertEquals("TEXT:🚀", controller.events[1])
        // 'B' mapped to HID
        assertEquals("STROKE:${HidKeyCodes.KEY_B}:${HidKeyCodes.MOD_LEFT_SHIFT}", controller.events[2])
    }

    @Test
    fun testTypeTextPureUnmappedRun() {
        val controller = RecordingKeyboardController()
        VirtualKeyboardHelper.typeText("你好世界", controller)

        assertEquals(1, controller.events.size)
        assertEquals("TEXT:你好世界", controller.events[0])
    }

    @Test
    fun testHandleImeValueChangeInsert() {
        val controller = RecordingKeyboardController()
        val anchor = "  "
        val input = TextFieldValue("  Hello", selection = TextRange(7))

        val result = VirtualKeyboardHelper.handleImeValueChange(input, anchor, controller)
        assertEquals(anchor, result.text)
        assertEquals(TextRange(2), result.selection)

        // 'H', 'e', 'l', 'l', 'o'
        assertEquals(5, controller.events.size)
        assertTrue(controller.events.all { it.startsWith("STROKE:") })
    }

    @Test
    fun testHandleImeValueChangeLeadingAndTrailingSpaces() {
        val controller = RecordingKeyboardController()
        val anchor = "  "
        val input = TextFieldValue("   foo bar  ", selection = TextRange(12))

        val result = VirtualKeyboardHelper.handleImeValueChange(input, anchor, controller)
        assertEquals(anchor, result.text)
        assertEquals(TextRange(2), result.selection)

        // " foo bar  ": Space, f, o, o, Space, b, a, r, Space, Space -> 10 strokes
        assertEquals(10, controller.events.size)
        assertTrue(controller.events.all { it.startsWith("STROKE:") })
    }

    @Test
    fun testHandleImeValueChangeEmojiWithSurrogate() {
        val controller = RecordingKeyboardController()
        val anchor = "  "
        val input = TextFieldValue("  🚀", selection = TextRange(4))

        val result = VirtualKeyboardHelper.handleImeValueChange(input, anchor, controller)
        assertEquals(anchor, result.text)
        assertEquals(TextRange(2), result.selection)

        assertEquals(1, controller.events.size)
        assertEquals("TEXT:🚀", controller.events[0])
    }

    @Test
    fun testHandleImeValueChangeBackspace() {
        val controller = RecordingKeyboardController()
        val anchor = "  "
        // User backspaced once: length drops from 2 to 1
        val input = TextFieldValue(" ", selection = TextRange(1))

        val result = VirtualKeyboardHelper.handleImeValueChange(input, anchor, controller)
        assertEquals(anchor, result.text)
        assertEquals(TextRange(2), result.selection)

        assertEquals(1, controller.events.size)
        assertEquals("STROKE:${HidKeyCodes.KEY_BACKSPACE}:${HidKeyCodes.MOD_NONE}", controller.events[0])
    }

    @Test
    fun testHandleImeValueChangeDoubleBackspace() {
        val controller = RecordingKeyboardController()
        val anchor = "  "
        // User deleted everything: length drops from 2 to 0
        val input = TextFieldValue("", selection = TextRange(0))

        val result = VirtualKeyboardHelper.handleImeValueChange(input, anchor, controller)
        assertEquals(anchor, result.text)
        assertEquals(TextRange(2), result.selection)

        assertEquals(2, controller.events.size)
        assertEquals("STROKE:${HidKeyCodes.KEY_BACKSPACE}:${HidKeyCodes.MOD_NONE}", controller.events[0])
        assertEquals("STROKE:${HidKeyCodes.KEY_BACKSPACE}:${HidKeyCodes.MOD_NONE}", controller.events[1])
    }

    @Test
    fun testHandleImeValueChangeReplacement() {
        val controller = RecordingKeyboardController()
        val anchor = "  "
        // Gboard suggested word replaced the buffer completely without starting with "  "
        val input = TextFieldValue("good", selection = TextRange(4))

        val result = VirtualKeyboardHelper.handleImeValueChange(input, anchor, controller)
        assertEquals(anchor, result.text)
        assertEquals(TextRange(2), result.selection)

        // "good" typed in full (g, o, o, d)
        assertEquals(4, controller.events.size)
        assertTrue(controller.events.all { it.startsWith("STROKE:") })
    }

    @Test
    fun testSafeUtf8ChunkLengthContract() {
        val text = "Hello! 🚀 你好! 世界!"
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            val chunkLen = VirtualKeyboardHelper.safeUtf8ChunkLength(bytes, offset, maxLen = 6)
            assertTrue("Chunk length must be positive", chunkLen > 0)
            assertTrue("Chunk length must not exceed maxLen", chunkLen <= 6)
            offset += chunkLen
        }
        assertEquals(bytes.size, offset)
    }

    @Test
    fun testTypeTextStartingWithEmoji() {
        val controller = RecordingKeyboardController()
        VirtualKeyboardHelper.typeText("🎉Party", controller)

        assertEquals(6, controller.events.size)
        assertEquals("TEXT:🎉", controller.events[0])
        assertEquals("STROKE:${HidKeyCodes.KEY_P}:${HidKeyCodes.MOD_LEFT_SHIFT}", controller.events[1])
        assertEquals("STROKE:${HidKeyCodes.KEY_A}:${HidKeyCodes.MOD_NONE}", controller.events[2])
        assertEquals("STROKE:${HidKeyCodes.KEY_R}:${HidKeyCodes.MOD_NONE}", controller.events[3])
        assertEquals("STROKE:${HidKeyCodes.KEY_T}:${HidKeyCodes.MOD_NONE}", controller.events[4])
        assertEquals("STROKE:${HidKeyCodes.KEY_Y}:${HidKeyCodes.MOD_NONE}", controller.events[5])
    }

    @Test
    fun testSafeUtf8ChunkLengthFallback() {
        val bytes = "🚀".toByteArray(Charsets.UTF_8)
        assertEquals(4, bytes.size)
        // With maxLen = 4, end starts at 4, which is bytes.size, so early return gives 4
        assertEquals(4, VirtualKeyboardHelper.safeUtf8ChunkLength(bytes, 0, maxLen = 4))
    }

    @Test
    fun testSafeUtf8ChunkLengthWithOnlyContinuationBytes() {
        // 0x80 bytes are continuation bytes with no lead byte: invalid UTF-8
        val bytes = ByteArray(10) { 0x80.toByte() }
        val len = VirtualKeyboardHelper.safeUtf8ChunkLength(bytes, 0, maxLen = 4)
        assertTrue("Must make progress", len > 0)
        assertTrue("Must not exceed maxLen", len <= 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSafeUtf8ChunkLengthRejectsSmallerThanFour() {
        val bytes = "test".toByteArray(Charsets.UTF_8)
        VirtualKeyboardHelper.safeUtf8ChunkLength(bytes, 0, maxLen = 3)
    }
}
