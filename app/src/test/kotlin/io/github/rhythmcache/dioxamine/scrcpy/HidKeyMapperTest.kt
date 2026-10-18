package io.github.rhythmcache.dioxamine.scrcpy

import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.HidKeyCodes
import org.junit.Assert.*
import org.junit.Test

class HidKeyMapperTest {

    @Test
    fun testHelloKeyMapping() {
        val input = "hello"
        val mappings = input.map { HidKeyMapper.charToHid(it) }

        // All characters in "hello" must be valid mappings with shift = false
        assertTrue(mappings.all { it != null && !it.shift })

        // 'h' = 0x0B, 'e' = 0x08, 'l' = 0x0F, 'l' = 0x0F, 'o' = 0x12
        assertEquals(0x0B, mappings[0]?.hidKeyCode)
        assertEquals(0x08, mappings[1]?.hidKeyCode)
        assertEquals(0x0F, mappings[2]?.hidKeyCode)
        assertEquals(0x0F, mappings[3]?.hidKeyCode)
        assertEquals(0x12, mappings[4]?.hidKeyCode)

        // Verify identical consecutive characters ('l' and 'l') map to the exact same keycode
        assertEquals(mappings[2]?.hidKeyCode, mappings[3]?.hidKeyCode)
    }

    @Test
    fun testUppercaseLettersRequireShift() {
        val lowerA = HidKeyMapper.charToHid('a')
        val upperA = HidKeyMapper.charToHid('A')

        assertNotNull(lowerA)
        assertNotNull(upperA)

        assertEquals(lowerA?.hidKeyCode, upperA?.hidKeyCode)
        assertFalse(lowerA!!.shift)
        assertTrue(upperA!!.shift)
    }

    @Test
    fun testSymbolsAndShift() {
        val one = HidKeyMapper.charToHid('1')
        val exclamation = HidKeyMapper.charToHid('!')

        assertNotNull(one)
        assertNotNull(exclamation)

        assertEquals(0x1E, one?.hidKeyCode)
        assertEquals(0x1E, exclamation?.hidKeyCode)
        assertFalse(one!!.shift)
        assertTrue(exclamation!!.shift)

        val space = HidKeyMapper.charToHid(' ')
        assertEquals(HidKeyCodes.KEY_SPACE, space?.hidKeyCode)
        assertFalse(space!!.shift)
    }

    @Test
    fun testUnmappedCharactersReturnNull() {
        // Emojis arrive as surrogate pairs; test the high surrogate
        assertNull(HidKeyMapper.charToHid("\uD83D\uDE0A"[0]))
        // Non-ASCII CJK and accented characters
        assertNull(HidKeyMapper.charToHid('你'))
        assertNull(HidKeyMapper.charToHid('é'))
    }
}
