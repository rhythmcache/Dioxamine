package io.github.rhythmcache.dioxamine.plugin

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PluginUpdateCheckerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testParsePluginUpdateInfoWithChangelog() {
        val payload = """
            {
                "id": "io.github.rhythmcache.dioxamine.xtermterminal",
                "version": "1.0.2",
                "versionCode": 3,
                "download": "https://github.com/dioxamine-plugins/xtermterminal/releases/download/v1.0.2/xtermterminal-1.0.2.zip",
                "changelog": "https://raw.githubusercontent.com/dioxamine-plugins/xtermterminal/main/CHANGELOG.md"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<PluginUpdateInfo>(payload)
        assertEquals("io.github.rhythmcache.dioxamine.xtermterminal", parsed.id)
        assertEquals("1.0.2", parsed.version)
        assertEquals(3, parsed.versionCode)
        assertEquals("https://github.com/dioxamine-plugins/xtermterminal/releases/download/v1.0.2/xtermterminal-1.0.2.zip", parsed.download)
        assertEquals("https://raw.githubusercontent.com/dioxamine-plugins/xtermterminal/main/CHANGELOG.md", parsed.changelog)
    }

    @Test
    fun testParsePluginUpdateInfoWithoutChangelogAndWithExtraFields() {
        val payload = """
            {
                "id": "io.github.rhythmcache.dioxamine.xtermterminal",
                "version": "1.0.2",
                "versionCode": 3,
                "download": "https://github.com/dioxamine-plugins/xtermterminal/releases/download/v1.0.2/xtermterminal-1.0.2.zip",
                "extraField": "somethingExtra"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<PluginUpdateInfo>(payload)
        assertEquals("io.github.rhythmcache.dioxamine.xtermterminal", parsed.id)
        assertEquals("1.0.2", parsed.version)
        assertEquals(3, parsed.versionCode)
        assertNull(parsed.changelog)
    }

    @Test
    fun testUpdateDetectionComparison() {
        val installedVersionCode = 2
        val updateInfo = PluginUpdateInfo(
            id = "test.plugin",
            version = "1.0.2",
            versionCode = 3,
            download = "https://example.com/plugin.zip",
        )

        assertTrue(updateInfo.versionCode > installedVersionCode)

        val olderUpdateInfo = PluginUpdateInfo(
            id = "test.plugin",
            version = "1.0.0",
            versionCode = 2,
            download = "https://example.com/plugin.zip",
        )

        assertFalse(olderUpdateInfo.versionCode > installedVersionCode)
    }

    @Test
    fun testChangelogHttpSchemeValidation() {
        val validHttp = "http://example.com/changelog.md"
        val validHttps = "https://example.com/changelog.md"
        val invalidContent = "content://com.malicious.provider/data"
        val invalidIntent = "intent:#Intent;action=android.intent.action.VIEW;end"
        val invalidFile = "file:///etc/hosts"

        fun isHttpUrl(url: String?): Boolean {
            val trimmed = url?.trim() ?: return false
            return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        }

        assertTrue(isHttpUrl(validHttp))
        assertTrue(isHttpUrl(validHttps))
        assertFalse(isHttpUrl(invalidContent))
        assertFalse(isHttpUrl(invalidIntent))
        assertFalse(isHttpUrl(invalidFile))
        assertFalse(isHttpUrl(null))
        assertFalse(isHttpUrl("   "))
    }
}
