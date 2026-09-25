package io.github.rhythmcache.dioxamine.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginManifestTest {

    private fun validJson(permissionsJson: String = "{}"): String = """
        {
            "schemaVersion": 1,
            "id": "com.example.fastbootplugin",
            "name": "Fastboot Test Plugin",
            "version": "1.0.0",
            "versionCode": 1,
            "entry": "index.html",
            "permissions": $permissionsJson
        }
    """.trimIndent()

    @Test
    fun testFastbootPermissionParsing() {
        assertEquals(PluginPermission.FASTBOOT, PluginPermission.fromFastbootString("fastboot"))
        assertNull(PluginPermission.fromFastbootString("access"))
        assertEquals(PluginPermission.FASTBOOT, PluginPermission.fromManifestString("fastboot"))
        assertNull(PluginPermission.fromFastbootString("shell"))
    }

    @Test
    fun testValidFastbootManifest() {
        val json = validJson("""{"fastboot": ["fastboot"]}""")
        val result = parseManifest(json)
        assertTrue(result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals(listOf("fastboot"), manifest.permissions.fastboot)
        assertEquals(listOf("fastboot"), manifest.permissions.allList())
    }

    @Test
    fun testUnknownFastbootPermission() {
        val json = validJson("""{"fastboot": ["invalid_perm"]}""")
        val result = parseManifest(json)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("Unknown fastboot permission 'invalid_perm'"))
    }

    @Test
    fun testCrossValidationFastbootUnderAdb() {
        val json = validJson("""{"adb": ["fastboot"]}""")
        val result = parseManifest(json)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("belongs to 'fastboot', not 'adb'"))
        assertTrue(msg.contains("move to permissions.fastboot"))
    }

    @Test
    fun testCrossValidationFastbootUnderCommon() {
        val json = validJson("""{"common": ["fastboot"]}""")
        val result = parseManifest(json)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("belongs to 'fastboot', not 'common'"))
        assertTrue(msg.contains("move to permissions.fastboot"))
    }

    @Test
    fun testCrossValidationAdbUnderFastboot() {
        val json = validJson("""{"fastboot": ["shell"]}""")
        val result = parseManifest(json)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("belongs to 'adb', not 'fastboot'"))
        assertTrue(msg.contains("move to permissions.adb"))
    }

    @Test
    fun testCrossValidationCommonUnderFastboot() {
        val json = validJson("""{"fastboot": ["network"]}""")
        val result = parseManifest(json)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("belongs to 'common', not 'fastboot'"))
        assertTrue(msg.contains("move to permissions.common"))
    }

    @Test
    fun testButtonInterceptionDefaultFalse() {
        val json = validJson("{}")
        val result = parseManifest(json)
        assertTrue(result.isSuccess)
        val manifest = result.getOrThrow()
        assertFalse(manifest.interceptBackButton)
        assertFalse(manifest.interceptVolumeButtons)
    }

    @Test
    fun testButtonInterceptionExplicitTrue() {
        val json = """
            {
                "schemaVersion": 1,
                "id": "com.example.buttonsplugin",
                "name": "Buttons Test Plugin",
                "version": "1.0.0",
                "versionCode": 1,
                "entry": "index.html",
                "interceptBackButton": true,
                "interceptVolumeButtons": true
            }
        """.trimIndent()
        val result = parseManifest(json)
        assertTrue(result.isSuccess)
        val manifest = result.getOrThrow()
        assertTrue(manifest.interceptBackButton)
        assertTrue(manifest.interceptVolumeButtons)
    }
}
