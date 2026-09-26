package io.github.rhythmcache.dioxamine.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginOnlineRepositoryTest {

    @Test
    fun testParseValidSampleIndexJson() {
        val sample = """
        {
          "schemaVersion": 1,
          "updated": "2026-09-26T14:00:00Z",
          "count": 1,
          "plugins": [
            {
              "id": "io.github.rhythmcache.dioxamine.xtermterminal",
              "name": "Terminal",
              "description": "Interactive PTY terminal powered by xterm.js",
              "author": "rhythmcache",
              "icon": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA",
              "homepage": "https://github.com/rhythmcache/Terminal",
              "permissions": {
                "adb": ["shell"]
              },
              "version": "1.0.1",
              "versionCode": 2,
              "download": "https://github.com/rhythmcache/Terminal/releases/download/v1.0.1/Terminal-v1.0.1.zip",
              "changelog": "https://raw.githubusercontent.com/rhythmcache/Terminal/main/changelog.md",
              "updateUrl": "https://raw.githubusercontent.com/rhythmcache/Terminal/main/update.json",
              "repo": "https://github.com/rhythmcache/Terminal"
            }
          ]
        }
        """.trimIndent()

        val result = parsePluginIndex(sample)
        assertTrue("Parsing valid sample index should succeed", result.isSuccess)
        val index = result.getOrThrow()
        assertEquals(1, index.schemaVersion)
        assertEquals("2026-09-26T14:00:00Z", index.updated)
        assertEquals(1, index.plugins.size)

        val plugin = index.plugins[0]
        assertEquals("io.github.rhythmcache.dioxamine.xtermterminal", plugin.id)
        assertEquals("Terminal", plugin.name)
        assertEquals("Interactive PTY terminal powered by xterm.js", plugin.description)
        assertEquals("rhythmcache", plugin.author)
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA", plugin.icon)
        assertEquals("https://github.com/rhythmcache/Terminal", plugin.homepage)
        assertEquals(listOf("shell"), plugin.permissions.adb)
        assertEquals("1.0.1", plugin.version)
        assertEquals(2, plugin.versionCode)
        assertEquals("https://github.com/rhythmcache/Terminal/releases/download/v1.0.1/Terminal-v1.0.1.zip", plugin.download)
        assertEquals("https://raw.githubusercontent.com/rhythmcache/Terminal/main/changelog.md", plugin.changelog)
        assertEquals("https://raw.githubusercontent.com/rhythmcache/Terminal/main/update.json", plugin.updateUrl)
        assertEquals("https://github.com/rhythmcache/Terminal", plugin.repo)
    }

    @Test
    fun testDefensiveParsingSkipsInvalidEntriesAndPreservesValid() {
        val jsonWithBadEntries = """
        {
          "schemaVersion": 1,
          "plugins": [
            {
              "id": "valid.one",
              "name": "Valid One",
              "version": "1.0.0",
              "versionCode": 1,
              "download": "https://example.com/valid1.zip"
            },
            {
              "name": "Missing ID",
              "version": "1.0.0",
              "versionCode": 1,
              "download": "https://example.com/bad.zip"
            },
            {
              "id": "missing.name",
              "version": "1.0.0",
              "versionCode": 1,
              "download": "https://example.com/bad.zip"
            },
            {
              "id": "missing.version",
              "name": "No Version",
              "versionCode": 1,
              "download": "https://example.com/bad.zip"
            },
            {
              "id": "invalid.code",
              "name": "Zero VersionCode",
              "version": "1.0.0",
              "versionCode": 0,
              "download": "https://example.com/bad.zip"
            },
            {
              "id": "bad.download",
              "name": "Bad Download Scheme",
              "version": "1.0.0",
              "versionCode": 1,
              "download": "ftp://example.com/bad.zip"
            },
            {
              "id": "valid.two",
              "name": "Valid Two",
              "version": "2.0.0",
              "versionCode": 5,
              "download": "http://example.com/valid2.zip",
              "changelog": "invalid_changelog_url"
            }
          ]
        }
        """.trimIndent()

        val result = parsePluginIndex(jsonWithBadEntries)
        assertTrue(result.isSuccess)
        val plugins = result.getOrThrow().plugins
        assertEquals(2, plugins.size)
        assertEquals("valid.one", plugins[0].id)
        assertEquals("valid.two", plugins[1].id)
        assertNull("Invalid changelog URL should be set to null", plugins[1].changelog)
        assertNull("Missing updated field should parse as null", result.getOrThrow().updated)
    }

    @Test
    fun testMalformedJsonReturnsFailure() {
        val badJson = "this is not json"
        val result = parsePluginIndex(badJson)
        assertTrue(result.isFailure)
    }

    @Test
    fun testParseIsoTimeMs() {
        val iso = "2026-09-26T14:00:00Z"
        val timeMs = parseIsoTimeMs(iso)
        assertNotNull(timeMs)
        assertTrue(timeMs!! > 0)

        val isoMillis = "2026-09-26T14:00:00.000Z"
        val timeMsMillis = parseIsoTimeMs(isoMillis)
        assertNotNull(timeMsMillis)
        assertEquals(timeMs, timeMsMillis)

        val invalidIso = "not-a-date"
        val invalidResult = parseIsoTimeMs(invalidIso)
        assertNull(invalidResult)
    }

    @Test
    fun testMalformedPermissionsRejectsEntry() {
        val jsonWithBadPerms = """
        {
          "schemaVersion": 1,
          "plugins": [
            {
              "id": "valid.entry",
              "name": "Valid Entry",
              "version": "1.0.0",
              "versionCode": 1,
              "download": "https://example.com/valid.zip",
              "permissions": {
                "adb": ["shell"]
              }
            },
            {
              "id": "bad.perms.string",
              "name": "Bad Perms String",
              "version": "1.0.0",
              "versionCode": 1,
              "download": "https://example.com/bad.zip",
              "permissions": "not-an-object"
            },
            {
              "id": "bad.perms.array",
              "name": "Bad Perms Array",
              "version": "1.0.0",
              "versionCode": 1,
              "download": "https://example.com/bad2.zip",
              "permissions": ["adb.shell"]
            }
          ]
        }
        """.trimIndent()

        val result = parsePluginIndex(jsonWithBadPerms)
        assertTrue(result.isSuccess)
        val plugins = result.getOrThrow().plugins
        assertEquals(1, plugins.size)
        assertEquals("valid.entry", plugins[0].id)
    }

    @Test
    fun testDeduplicatePluginsRepoPriorityPreventsShadowing() {
        val officialPluginV1 = PluginIndexItem(
            id = "org.dioxamine.core",
            name = "Official Plugin",
            version = "1.0.0",
            versionCode = 10,
            download = "https://official.example.com/plugin-v1.zip",
        )
        val officialPluginV2 = PluginIndexItem(
            id = "org.dioxamine.core",
            name = "Official Plugin",
            version = "2.0.0",
            versionCode = 20,
            download = "https://official.example.com/plugin-v2.zip",
        )
        val maliciousSpoof = PluginIndexItem(
            id = "org.dioxamine.core",
            name = "Official Plugin Impostor",
            version = "99.0.0",
            versionCode = 9999,
            download = "https://malicious.example.com/evil.zip",
        )
        val untrustedUnique = PluginIndexItem(
            id = "org.untrusted.unique",
            name = "Community Plugin",
            version = "1.0.0",
            versionCode = 1,
            download = "https://community.example.com/plugin.zip",
        )

        // Repo 1 (official): contains both v1 and v2
        val repo1 = listOf(officialPluginV1, officialPluginV2)
        // Repo 2 (untrusted/community): tries to shadow official ID with versionCode 9999, plus has unique plugin
        val repo2 = listOf(maliciousSpoof, untrustedUnique)

        val merged = deduplicatePlugins(listOf(repo1, repo2))

        assertEquals(2, merged.size)
        val official = merged.first { it.id == "org.dioxamine.core" }
        // Verify official repo's highest version (v2, versionCode 20) won, NOT the spoof from repo 2
        assertEquals(20, official.versionCode)
        assertEquals("https://official.example.com/plugin-v2.zip", official.download)

        // Verify untrusted unique plugin was included
        val community = merged.first { it.id == "org.untrusted.unique" }
        assertEquals(1, community.versionCode)
    }
}
