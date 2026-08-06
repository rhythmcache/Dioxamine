package io.github.rhythmcache.dioxamine.adb.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Parses ANSI SGR escape sequences in raw terminal text and produces
 * Compose [AnnotatedString] with the appropriate [SpanStyle]s.
 *
 * Supported sequences:
 * - Reset (`\e[0m`), bold (`\e[1m`), italic (`\e[3m`), underline (`\e[4m`)
 * - Standard foreground colours (`\e[30-37m`), bright (`\e[90-97m`)
 * - Standard background colours (`\e[40-47m`), bright (`\e[100-107m`)
 * - 256-colour foreground (`\e[38;5;Nm`) and background (`\e[48;5;Nm`)
 *
 * All other CSI sequences (cursor movement, erase, etc.) are silently
 * stripped so the output stays clean.
 */
object AnsiParser {

    // Standard terminal palette (dark theme oriented)
    private val STANDARD = arrayOf(
        Color(0xFF2E3436), // 0  Black
        Color(0xFFCC0000), // 1  Red
        Color(0xFF4E9A06), // 2  Green
        Color(0xFFC4A000), // 3  Yellow
        Color(0xFF3465A4), // 4  Blue
        Color(0xFF75507B), // 5  Magenta
        Color(0xFF06989A), // 6  Cyan
        Color(0xFFD3D7CF), // 7  White
    )

    private val BRIGHT = arrayOf(
        Color(0xFF555753), // 8   Bright Black
        Color(0xFFEF2929), // 9   Bright Red
        Color(0xFF8AE234), // 10  Bright Green
        Color(0xFFFCE94F), // 11  Bright Yellow
        Color(0xFF729FCF), // 12  Bright Blue
        Color(0xFFAD7FA8), // 13  Bright Magenta
        Color(0xFF34E2E2), // 14  Bright Cyan
        Color(0xFFEEEEEC), // 15  Bright White
    )

    /**
     * Match any CSI sequence `ESC [ ... <final>`, OSC sequence `ESC ] ... BEL`,
     * or two-byte escape `ESC <char>`.
     */
    private val ESC_RE = Regex("\u001B\\[[0-9;]*[A-Za-z]|\u001B][^\u0007]*\u0007|\u001B[^\\[\\]]")

    /**
     * Parse [raw] terminal text and return a styled [AnnotatedString].
     *
     * @param raw          the raw text possibly containing ANSI escapes
     * @param defaultColor colour used for unstyled text (typically light grey)
     */
    fun parse(raw: String, defaultColor: Color = Color(0xFFCCCCCC)): AnnotatedString {
        val builder = AnnotatedString.Builder()

        var fg: Color? = null
        var bg: Color? = null
        var bold = false
        var italic = false
        var underline = false
        var strikethrough = false
        var faint = false
        var inverse = false
        var lastEnd = 0

        for (match in ESC_RE.findAll(raw)) {
            // -- Text before this escape ----------------------------
            if (match.range.first > lastEnd) {
                val text = raw.substring(lastEnd, match.range.first)
                builder.append(AnnotatedString(text, spanOf(fg ?: defaultColor, bg, bold, italic, underline, strikethrough, faint, inverse)))
            }
            lastEnd = match.range.last + 1

            val seq = match.value
            // We only interpret SGR (ends with 'm')
            if (!seq.endsWith('m') || !seq.startsWith("\u001B[")) continue

            val paramStr = seq.substring(2, seq.length - 1)
            val codes: List<Int> = if (paramStr.isEmpty()) listOf(0) else paramStr.split(';').mapNotNull { it.toIntOrNull() }

            var i = 0
            while (i < codes.size) {
                when (val c = codes[i]) {
                    0  -> { fg = null; bg = null; bold = false; italic = false; underline = false
                             strikethrough = false; faint = false; inverse = false }
                    1  -> bold = true
                    2  -> faint = true
                    3  -> italic = true
                    4  -> underline = true
                    5, 6 -> { /* blink - visually ignored, but consumed so it doesn't fall through */ }
                    7  -> inverse = true
                    9  -> strikethrough = true
                    22 -> { bold = false; faint = false }
                    23 -> italic = false
                    24 -> underline = false
                    27 -> inverse = false
                    29 -> strikethrough = false
                    in 30..37   -> fg = STANDARD[c - 30]
                    38 -> {
                        when {
                            i + 1 < codes.size && codes[i + 1] == 5 && i + 2 < codes.size -> {
                                fg = color256(codes[i + 2]); i += 2
                            }
                            i + 1 < codes.size && codes[i + 1] == 2 && i + 4 < codes.size -> {
                                fg = Color(
                                    codes[i + 2].coerceIn(0, 255),
                                    codes[i + 3].coerceIn(0, 255),
                                    codes[i + 4].coerceIn(0, 255)
                                )
                                i += 4
                            }
                        }
                    }
                    39 -> fg = null
                    in 40..47   -> bg = STANDARD[c - 40]
                    48 -> {
                        when {
                            i + 1 < codes.size && codes[i + 1] == 5 && i + 2 < codes.size -> {
                                bg = color256(codes[i + 2]); i += 2
                            }
                            i + 1 < codes.size && codes[i + 1] == 2 && i + 4 < codes.size -> {
                                bg = Color(
                                    codes[i + 2].coerceIn(0, 255),
                                    codes[i + 3].coerceIn(0, 255),
                                    codes[i + 4].coerceIn(0, 255)
                                )
                                i += 4
                            }
                        }
                    }
                    49 -> bg = null
                    in 90..97   -> fg = BRIGHT[c - 90]
                    in 100..107 -> bg = BRIGHT[c - 100]
                }
                i++
            }
        }

        // -- Remaining text after last escape ----------------------
        if (lastEnd < raw.length) {
            val text = raw.substring(lastEnd)
            builder.append(AnnotatedString(text, spanOf(fg ?: defaultColor, bg, bold, italic, underline, strikethrough, faint, inverse)))
        }

        return builder.toAnnotatedString()
    }

    // -- Helpers -----------------------------------------------------

    private fun spanOf(
        color: Color,
        bg: Color?,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strikethrough: Boolean = false,
        faint: Boolean = false,
        inverse: Boolean = false,
    ): SpanStyle {
        var finalFg = color
        var finalBg = bg ?: Color.Unspecified

        if (inverse) {
            val swap = finalFg
            finalFg = if (finalBg != Color.Unspecified) finalBg else Color.Black
            finalBg = swap
        }
        if (faint) {
            finalFg = finalFg.copy(alpha = finalFg.alpha * 0.6f)
        }

        val decorations = listOfNotNull(
            if (underline) TextDecoration.Underline else null,
            if (strikethrough) TextDecoration.LineThrough else null,
        )

        return SpanStyle(
            color = finalFg,
            background = finalBg,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else null,
        )
    }

    /**
     * Resolve a 256-colour index to a [Color].
     *
     *   0-7   standard, 8-15 bright,
     *  16-231 6x6x6 colour cube,
     * 232-255 grey ramp
     */
    private fun color256(n: Int): Color = when {
        n < 0   -> STANDARD[0]
        n < 8   -> STANDARD[n]
        n < 16  -> BRIGHT[n - 8]
        n < 232 -> {
            val idx = n - 16
            val r = (idx / 36) * 51
            val g = ((idx % 36) / 6) * 51
            val b = (idx % 6) * 51
            Color(r, g, b)
        }
        n < 256 -> {
            val v = 8 + (n - 232) * 10
            Color(v, v, v)
        }
        else -> STANDARD[7]
    }
}
