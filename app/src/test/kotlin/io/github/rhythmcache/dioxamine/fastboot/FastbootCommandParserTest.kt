package io.github.rhythmcache.dioxamine.fastboot

import org.junit.Assert.*
import org.junit.Test

class FastbootCommandParserTest {

    @Test
    fun testNormalize() {
        assertEquals("reboot", FastbootCommandParser.normalize("fastboot reboot"))
        assertEquals("reboot", FastbootCommandParser.normalize("   fastboot reboot   "))
        assertEquals("getvar all", FastbootCommandParser.normalize("getvar all"))
        assertEquals("", FastbootCommandParser.normalize("  "))
    }

    @Test
    fun testEmptyInput() {
        assertTrue(FastbootCommandParser.parse("") is ParsedShellInput.Empty)
        assertTrue(FastbootCommandParser.parse("   ") is ParsedShellInput.Empty)
    }

    @Test
    fun testHelpInput() {
        assertTrue(FastbootCommandParser.parse("help") is ParsedShellInput.HelpRequested)
        assertTrue(FastbootCommandParser.parse("fastboot --help") is ParsedShellInput.HelpRequested)
        assertTrue(FastbootCommandParser.parse("-h") is ParsedShellInput.HelpRequested)
    }

    @Test
    fun testFlashBare() {
        val parsed = FastbootCommandParser.parse("fastboot flash boot")
        assertTrue(parsed is ParsedShellInput.NeedsFile)
        val needsFile = parsed as ParsedShellInput.NeedsFile
        assertEquals(PendingFileCommand.Flash("boot"), needsFile.pending)
        assertEquals("fastboot flash boot", needsFile.raw)
    }

    @Test
    fun testFlashWithPath() {
        val parsed = FastbootCommandParser.parse("flash boot /path/to/boot.img")
        assertTrue(parsed is ParsedShellInput.NeedsFileRawPathDetected)
        val rawPath = parsed as ParsedShellInput.NeedsFileRawPathDetected
        assertEquals(PendingFileCommand.Flash("boot"), rawPath.pending)
        assertEquals("/path/to/boot.img", rawPath.detectedPath)
    }

    @Test
    fun testBootBare() {
        val parsed = FastbootCommandParser.parse("boot")
        assertTrue(parsed is ParsedShellInput.NeedsFile)
        val needsFile = parsed as ParsedShellInput.NeedsFile
        assertEquals(PendingFileCommand.Boot, needsFile.pending)
    }

    @Test
    fun testBootWithPath() {
        val parsed = FastbootCommandParser.parse("fastboot boot /sdcard/twrp.img")
        assertTrue(parsed is ParsedShellInput.NeedsFileRawPathDetected)
        val rawPath = parsed as ParsedShellInput.NeedsFileRawPathDetected
        assertEquals(PendingFileCommand.Boot, rawPath.pending)
        assertEquals("/sdcard/twrp.img", rawPath.detectedPath)
    }

    @Test
    fun testFetch() {
        val parsed = FastbootCommandParser.parse("fetch boot /local/save.img")
        assertTrue(parsed is ParsedShellInput.NeedsFile)
        val needsFile = parsed as ParsedShellInput.NeedsFile
        assertEquals(PendingFileCommand.Fetch("boot"), needsFile.pending)
    }

    @Test
    fun testRunnableTranslations() {
        val testCases = listOf(
            "getvar all" to Pair("getvar:all", "getvar all"),
            "getvar product" to Pair("getvar:product", "getvar product"),
            "erase userdata" to Pair("erase:userdata", "erase userdata"),
            "set_active a" to Pair("set_active:a", "set_active a"),
            "reboot-bootloader" to Pair("reboot-bootloader", "reboot-bootloader"),
            "reboot recovery" to Pair("reboot-recovery", "reboot recovery"),
            "reboot" to Pair("reboot", "reboot"),
            "continue" to Pair("continue", "continue"),
            "oem unlock" to Pair("oem unlock", "oem unlock"),
            "gsi status" to Pair("gsi status", "gsi status"),
            "snapshot-update cancel" to Pair("snapshot-update:cancel", "snapshot-update cancel"),
            "create-logical-partition system_b 1024" to Pair("create-logical-partition:system_b:1024", "create-logical-partition system_b 1024"),
            "delete-logical-partition system_b" to Pair("delete-logical-partition:system_b", "delete-logical-partition system_b"),
            "resize-logical-partition system_a 2048" to Pair("resize-logical-partition:system_a:2048", "resize-logical-partition system_a 2048"),
            "flashing unlock" to Pair("flashing unlock", "flashing unlock")
        )

        for ((input, expected) in testCases) {
            val parsed = FastbootCommandParser.parse(input)
            assertTrue("Expected Runnable for input '$input'", parsed is ParsedShellInput.Runnable)
            val runnable = parsed as ParsedShellInput.Runnable
            assertEquals("Wire command mismatch for '$input'", expected.first, runnable.wireCommand)
            assertEquals("Display label mismatch for '$input'", expected.second, runnable.displayLabel)
        }
    }

    @Test
    fun testUnknownCommand() {
        val parsed = FastbootCommandParser.parse("fastboot custom_cmd arg1")
        assertTrue(parsed is ParsedShellInput.Unknown)
        assertEquals("fastboot custom_cmd arg1", (parsed as ParsedShellInput.Unknown).raw)
    }

    @Test
    fun testTranslateCliCommand() {
        assertEquals("getvar:product", FastbootCommandParser.translateCliCommand("fastboot getvar product"))
        assertEquals("reboot-fastboot", FastbootCommandParser.translateCliCommand("reboot fastboot"))
        assertEquals("unknown_cmd", FastbootCommandParser.translateCliCommand("unknown_cmd"))
    }
}
