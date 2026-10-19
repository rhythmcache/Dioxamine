package io.github.rhythmcache.dioxamine.adb.builtin.touchpad

/**
 * Standard USB HID Usage Tables (Page 0x07 - Keyboard / Keypad).
 * Verified against USB HID 1.11 Specification and Linux input subsystem.
 */
object HidKeyCodes {
    // Modifier bitmasks (Report Byte 0)
    const val MOD_NONE = 0
    const val MOD_LEFT_CTRL = 1 shl 0
    const val MOD_LEFT_SHIFT = 1 shl 1
    const val MOD_LEFT_ALT = 1 shl 2
    const val MOD_LEFT_GUI = 1 shl 3 // Windows / Meta / Command
    const val MOD_RIGHT_CTRL = 1 shl 4
    const val MOD_RIGHT_SHIFT = 1 shl 5
    const val MOD_RIGHT_ALT = 1 shl 6
    const val MOD_RIGHT_GUI = 1 shl 7

    // Mouse Button bitmasks (Report Byte 0)
    const val MOUSE_BTN_NONE = 0
    const val MOUSE_BTN_LEFT = 1 shl 0
    const val MOUSE_BTN_RIGHT = 1 shl 1
    const val MOUSE_BTN_MIDDLE = 1 shl 2
    const val MOUSE_BTN_BACK = 1 shl 3
    const val MOUSE_BTN_FORWARD = 1 shl 4

    // Special & Non-Android Soft Keyboard Keys (Page 0x07)
    const val KEY_NONE = 0x00
    const val KEY_ENTER = 0x28
    const val KEY_ESC = 0x29
    const val KEY_BACKSPACE = 0x2A
    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C
    const val KEY_CAPS_LOCK = 0x39

    // Function Keys
    const val KEY_F1 = 0x3A
    const val KEY_F2 = 0x3B
    const val KEY_F3 = 0x3C
    const val KEY_F4 = 0x3D
    const val KEY_F5 = 0x3E
    const val KEY_F6 = 0x3F
    const val KEY_F7 = 0x40
    const val KEY_F8 = 0x41
    const val KEY_F9 = 0x42
    const val KEY_F10 = 0x43
    const val KEY_F11 = 0x44
    const val KEY_F12 = 0x45

    // Navigation & Editing
    const val KEY_PRINT_SCREEN = 0x46
    const val KEY_SCROLL_LOCK = 0x47
    const val KEY_PAUSE = 0x48
    const val KEY_INSERT = 0x49
    const val KEY_HOME = 0x4A
    const val KEY_PAGE_UP = 0x4B
    const val KEY_DELETE = 0x4C
    const val KEY_END = 0x4D
    const val KEY_PAGE_DOWN = 0x4E
    const val KEY_RIGHT = 0x4F
    const val KEY_LEFT = 0x50
    const val KEY_DOWN = 0x51
    const val KEY_UP = 0x52

    // Media & System
    const val KEY_MUTE = 0x7F
    const val KEY_VOLUME_UP = 0x80
    const val KEY_VOLUME_DOWN = 0x81

    // Alphanumeric keys (Page 0x07)
    const val KEY_A = 0x04
    const val KEY_B = 0x05
    const val KEY_C = 0x06
    const val KEY_D = 0x07
    const val KEY_E = 0x08
    const val KEY_F = 0x09
    const val KEY_G = 0x0A
    const val KEY_H = 0x0B
    const val KEY_I = 0x0C
    const val KEY_J = 0x0D
    const val KEY_K = 0x0E
    const val KEY_L = 0x0F
    const val KEY_M = 0x10
    const val KEY_N = 0x11
    const val KEY_O = 0x12
    const val KEY_P = 0x13
    const val KEY_Q = 0x14
    const val KEY_R = 0x15
    const val KEY_S = 0x16
    const val KEY_T = 0x17
    const val KEY_U = 0x18
    const val KEY_V = 0x19
    const val KEY_W = 0x1A
    const val KEY_X = 0x1B
    const val KEY_Y = 0x1C
    const val KEY_Z = 0x1D

    const val KEY_1 = 0x1E
    const val KEY_2 = 0x1F
    const val KEY_3 = 0x20
    const val KEY_4 = 0x21
    const val KEY_5 = 0x22
    const val KEY_6 = 0x23
    const val KEY_7 = 0x24
    const val KEY_8 = 0x25
    const val KEY_9 = 0x26
    const val KEY_0 = 0x27
}
