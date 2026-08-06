package io.github.rhythmcache.dioxamine.fastboot

/** Result of parsing a line typed into the shell. */
sealed class ParsedShellInput {
    object Empty : ParsedShellInput()
    object HelpRequested : ParsedShellInput()

    /** A command we recognize and can translate to wire format, no file needed. */
    data class Runnable(val wireCommand: String, val displayLabel: String) : ParsedShellInput()

    /** flash/boot/fetch with no local file resolved yet -> show attach/save button. */
    data class NeedsFile(val pending: PendingFileCommand, val raw: String) : ParsedShellInput()

    /** flash/boot where the user typed a source path we can't read without storage access. */
    data class NeedsFileRawPathDetected(
        val pending: PendingFileCommand,
        val detectedPath: String,
        val raw: String,
    ) : ParsedShellInput()

    /** Doesn't match any known pattern; sent as raw passthrough if the user forces it. */
    data class Unknown(val raw: String) : ParsedShellInput()
}

/**
 * Translates CLI-style fastboot shell input into wire-format protocol commands,
 * and detects when a command needs a locally-attached (or locally-saved) file
 * rather than a plain string send.
 *
 * Pure/stateless — no dependency on FastbootClient or Android APIs, easy to unit test.
 */
object FastbootCommandParser {

    /** Strips a leading "fastboot " prefix and surrounding whitespace, if present. */
    fun normalize(input: String): String =
        input.trim().removePrefix("fastboot ").trim()

    /** Top-level entry point for the shell tab. */
    fun parse(input: String): ParsedShellInput {
        val normalized = normalize(input)
        if (normalized.isBlank()) return ParsedShellInput.Empty
        if (normalized == "help" || normalized == "--help" || normalized == "-h") {
            return ParsedShellInput.HelpRequested
        }

        // File-needing commands are checked first, including "raw path typed anyway"
        // variants, so they never silently fall through to a plain wire-command send.

        FLASH_WITH_PATH.find(normalized)?.let { m ->
            return ParsedShellInput.NeedsFileRawPathDetected(
                pending = PendingFileCommand.Flash(partition = m.groupValues[1]),
                detectedPath = m.groupValues[2],
                raw = input,
            )
        }
        FLASH_BARE.find(normalized)?.let { m ->
            return ParsedShellInput.NeedsFile(PendingFileCommand.Flash(m.groupValues[1]), input)
        }

        BOOT_WITH_PATH.find(normalized)?.let { m ->
            return ParsedShellInput.NeedsFileRawPathDetected(
                pending = PendingFileCommand.Boot,
                detectedPath = m.groupValues[1],
                raw = input,
            )
        }
        if (BOOT_BARE.matches(normalized)) {
            return ParsedShellInput.NeedsFile(PendingFileCommand.Boot, input)
        }

        // fetch PARTITION [OUT_FILE] — the out-file token, if typed, is always ignored;
        // a SAF "save as" dialog decides where the file actually goes. So there is no
        // "raw path" warning case for fetch, only "needs a save location".
        FETCH_PATTERN.find(normalized)?.let { m ->
            return ParsedShellInput.NeedsFile(PendingFileCommand.Fetch(m.groupValues[1]), input)
        }

        for (t in TRANSLATIONS) {
            val match = t.pattern.find(normalized)
            if (match != null) return ParsedShellInput.Runnable(t.transform(match), t.label(match))
        }

        return ParsedShellInput.Unknown(input)
    }

    /** Translates CLI-style syntax into wire-format; unrecognized input passes through unchanged. */
    fun translateCliCommand(input: String): String {
        val normalized = normalize(input)
        for (t in TRANSLATIONS) {
            val match = t.pattern.find(normalized)
            if (match != null) return t.transform(match)
        }
        return normalized
    }

    // ---- Patterns for commands that need a locally attached/saved file ----

    private val FLASH_BARE = Regex("""^flash(?::\s*|\s+)(\S+)$""")
    private val FLASH_WITH_PATH = Regex("""^flash(?::\s*|\s+)(\S+)\s+(\S+)$""")
    private val BOOT_BARE = Regex("""^boot$""")
    private val BOOT_WITH_PATH = Regex("""^boot\s+(\S+)""")
    private val FETCH_PATTERN = Regex("""^fetch\s+(\S+)(?:\s+\S+)?$""")

    // ---- Everything else ----

    private class Translation(
        val pattern: Regex,
        val transform: (MatchResult) -> String,
        val label: (MatchResult) -> String,
    )

    private val TRANSLATIONS: List<Translation> = listOf(
        Translation(Regex("""^getvar\s+all$"""), { "getvar:all" }, { "getvar all" }),
        Translation(
            Regex("""^getvar\s+(\S+)$"""),
            { m -> "getvar:${m.groupValues[1]}" },
            { m -> "getvar ${m.groupValues[1]}" },
        ),
        Translation(
            Regex("""^erase\s+(\S+)$"""),
            { m -> "erase:${m.groupValues[1]}" },
            { m -> "erase ${m.groupValues[1]}" },
        ),
        Translation(
            Regex("""^set_active\s+(\S+)$"""),
            { m -> "set_active:${m.groupValues[1]}" },
            { m -> "set_active ${m.groupValues[1]}" },
        ),
        Translation(
            Regex("""^reboot-(\S+)$"""),
            { m -> "reboot-${m.groupValues[1]}" },
            { m -> "reboot-${m.groupValues[1]}" },
        ),
        Translation(
            Regex("""^reboot\s+(\S+)$"""),
            { m -> "reboot-${m.groupValues[1]}" },
            { m -> "reboot ${m.groupValues[1]}" },
        ),
        Translation(Regex("""^reboot$"""), { "reboot" }, { "reboot" }),
        Translation(Regex("""^continue$"""), { "continue" }, { "continue" }),
        Translation(
            Regex("""^oem\s+(.+)$"""),
            { m -> "oem ${m.groupValues[1]}" },
            { m -> "oem ${m.groupValues[1]}" },
        ),
        Translation(
            Regex("""^gsi\s+(\S+)$"""),
            { m -> "gsi ${m.groupValues[1]}" },
            { m -> "gsi ${m.groupValues[1]}" },
        ),
        Translation(
            Regex("""^snapshot-update\s+(\S+)$"""),
            { m -> "snapshot-update:${m.groupValues[1]}" },
            { m -> "snapshot-update ${m.groupValues[1]}" },
        ),
        Translation(
            Regex("""^create-logical-partition\s+(\S+)\s+(\S+)$"""),
            { m -> "create-logical-partition:${m.groupValues[1]}:${m.groupValues[2]}" },
            { m -> "create-logical-partition ${m.groupValues[1]} ${m.groupValues[2]}" },
        ),
        Translation(
            Regex("""^delete-logical-partition\s+(\S+)$"""),
            { m -> "delete-logical-partition:${m.groupValues[1]}" },
            { m -> "delete-logical-partition ${m.groupValues[1]}" },
        ),
        Translation(
            Regex("""^resize-logical-partition\s+(\S+)\s+(\S+)$"""),
            { m -> "resize-logical-partition:${m.groupValues[1]}:${m.groupValues[2]}" },
            { m -> "resize-logical-partition ${m.groupValues[1]} ${m.groupValues[2]}" },
        ),
        Translation(
            Regex("""^flashing\s+(.+)$"""),
            { m -> "flashing ${m.groupValues[1]}" },
            { m -> "flashing ${m.groupValues[1]}" },
        ),
    )

    val HELP_TEXT: String = buildString {
        appendLine("Commands:")
        appendLine("  getvar NAME | getvar all")
        appendLine("  flash PARTITION            (Attach button picks the image)")
        appendLine("  boot                       (Attach button picks the image)")
        appendLine("  fetch PARTITION            (prompts where to save)")
        appendLine("  reboot [bootloader|recovery|fastboot]")
        appendLine("  continue")
        appendLine("  erase PARTITION")
        appendLine("  set_active SLOT")
        appendLine("  flashing lock|unlock|lock_critical|unlock_critical|get_unlock_ability")
        appendLine("  oem COMMAND")
        appendLine("  gsi wipe|disable|status")
        appendLine("  snapshot-update cancel|merge")
        appendLine("  create-logical-partition NAME SIZE")
        appendLine("  delete-logical-partition NAME")
        appendLine("  resize-logical-partition NAME SIZE")
    }
}