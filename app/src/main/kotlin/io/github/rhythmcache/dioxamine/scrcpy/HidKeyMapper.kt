package io.github.rhythmcache.dioxamine.scrcpy

import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.HidKeyCodes

data class HidKeyMapping(val hidKeyCode: Int, val shift: Boolean)

/**
 * Maps standard ASCII characters to USB HID Keyboard (Page 0x07) keycodes
 * and Shift modifier state. Non-mappable characters return null.
 */
object HidKeyMapper {
    fun charToHid(c: Char): HidKeyMapping? = when (c) {
        in 'a'..'z' -> HidKeyMapping(0x04 + (c - 'a'), shift = false)
        in 'A'..'Z' -> HidKeyMapping(0x04 + (c - 'A'), shift = true)

        '1' -> HidKeyMapping(0x1E, shift = false)
        '!' -> HidKeyMapping(0x1E, shift = true)
        '2' -> HidKeyMapping(0x1F, shift = false)
        '@' -> HidKeyMapping(0x1F, shift = true)
        '3' -> HidKeyMapping(0x20, shift = false)
        '#' -> HidKeyMapping(0x20, shift = true)
        '4' -> HidKeyMapping(0x21, shift = false)
        '$' -> HidKeyMapping(0x21, shift = true)
        '5' -> HidKeyMapping(0x22, shift = false)
        '%' -> HidKeyMapping(0x22, shift = true)
        '6' -> HidKeyMapping(0x23, shift = false)
        '^' -> HidKeyMapping(0x23, shift = true)
        '7' -> HidKeyMapping(0x24, shift = false)
        '&' -> HidKeyMapping(0x24, shift = true)
        '8' -> HidKeyMapping(0x25, shift = false)
        '*' -> HidKeyMapping(0x25, shift = true)
        '9' -> HidKeyMapping(0x26, shift = false)
        '(' -> HidKeyMapping(0x26, shift = true)
        '0' -> HidKeyMapping(0x27, shift = false)
        ')' -> HidKeyMapping(0x27, shift = true)

        ' ' -> HidKeyMapping(HidKeyCodes.KEY_SPACE, shift = false)
        '\n', '\r' -> HidKeyMapping(HidKeyCodes.KEY_ENTER, shift = false)
        '\t' -> HidKeyMapping(HidKeyCodes.KEY_TAB, shift = false)

        '-' -> HidKeyMapping(0x2D, shift = false)
        '_' -> HidKeyMapping(0x2D, shift = true)
        '=' -> HidKeyMapping(0x2E, shift = false)
        '+' -> HidKeyMapping(0x2E, shift = true)
        '[' -> HidKeyMapping(0x2F, shift = false)
        '{' -> HidKeyMapping(0x2F, shift = true)
        ']' -> HidKeyMapping(0x30, shift = false)
        '}' -> HidKeyMapping(0x30, shift = true)
        '\\' -> HidKeyMapping(0x31, shift = false)
        '|' -> HidKeyMapping(0x31, shift = true)
        ';' -> HidKeyMapping(0x33, shift = false)
        ':' -> HidKeyMapping(0x33, shift = true)
        '\'' -> HidKeyMapping(0x34, shift = false)
        '"' -> HidKeyMapping(0x34, shift = true)
        '`' -> HidKeyMapping(0x35, shift = false)
        '~' -> HidKeyMapping(0x35, shift = true)
        ',' -> HidKeyMapping(0x36, shift = false)
        '<' -> HidKeyMapping(0x36, shift = true)
        '.' -> HidKeyMapping(0x37, shift = false)
        '>' -> HidKeyMapping(0x37, shift = true)
        '/' -> HidKeyMapping(0x38, shift = false)
        '?' -> HidKeyMapping(0x38, shift = true)

        else -> null
    }
}
