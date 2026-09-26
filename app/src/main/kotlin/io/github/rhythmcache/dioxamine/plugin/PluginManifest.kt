package io.github.rhythmcache.dioxamine.plugin

import io.github.rhythmcache.dioxamine.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class PluginPermissionsConfig(
    val adb: List<String> = emptyList(),
    val common: List<String> = emptyList(),
    val fastboot: List<String> = emptyList(),
) {
    fun allList(): List<String> = adb + common + fastboot
    fun isEmpty(): Boolean = adb.isEmpty() && common.isEmpty() && fastboot.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
}

@Serializable
data class PluginManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val description: String = "",
    val version: String,
    val versionCode: Int,
    val author: String? = null,
    val entry: String,
    val icon: String? = null,
    val minAppVersionCode: Int = 1,
    val minAppVersion: String? = null,
    val permissions: PluginPermissionsConfig = PluginPermissionsConfig(),
    val homepage: String? = null,
    val fullscreen: Boolean = false,
    val interceptBackButton: Boolean = false,
    val interceptVolumeButtons: Boolean = false,
)

private val jsonParser = Json { ignoreUnknownKeys = true }
private val idRegex = Regex("^[a-z0-9]+(\\.[a-z0-9_]+)+$")

fun parseManifest(json: String): Result<PluginManifest> {
    val manifest =
        try {
            jsonParser.decodeFromString<PluginManifest>(json)
        } catch (e: SerializationException) {
            val isFlatPermissions = runCatching {
                val root = jsonParser.parseToJsonElement(json)
                root is JsonObject && root["permissions"] is JsonArray
            }.getOrDefault(false)
            if (isFlatPermissions) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid permissions format: 'permissions' must be an object with subkeys like 'adb' and 'common' (e.g. {\"permissions\": {\"adb\": [\"shell\"]}}). Flat arrays are no longer supported.",
                        e,
                    ),
                )
            }
            return Result.failure(IllegalArgumentException("JSON deserialization failed: ${e.message}", e))
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid JSON format: ${e.message}", e))
        }

    // 1. id must match regex ^[a-z0-9]+(\.[a-z0-9_]+)+$ (reverse-DNS style, lowercase, at least one dot)
    if (!idRegex.matches(manifest.id)) {
        return Result.failure(
            IllegalArgumentException(
                "Invalid plugin ID '${manifest.id}': must be lowercase reverse-DNS format (e.g. com.example.plugin)",
            ),
        )
    }

    // 2. entry must be non-blank and a relative path with NO ".." segments and NO leading "/" or "\"
    if (manifest.entry.isBlank() || manifest.entry.contains("..") || manifest.entry.startsWith("/") || manifest.entry.startsWith("\\")) {
        return Result.failure(
            IllegalArgumentException(
                "Invalid entry path '${manifest.entry}': path traversal or absolute path detected",
            ),
        )
    }

    // 3. if icon is non-null, apply the same traversal check as entry
    manifest.icon?.let { iconPath ->
        if (iconPath.isBlank() || iconPath.contains("..") || iconPath.startsWith("/") || iconPath.startsWith("\\")) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid icon path '$iconPath': path traversal or absolute path detected",
                ),
            )
        }
    }

    // 4. schemaVersion must equal 1 (the only version this app version supports)
    if (manifest.schemaVersion != 1) {
        return Result.failure(
            IllegalArgumentException(
                "Unsupported schemaVersion ${manifest.schemaVersion}: only version 1 is supported",
            ),
        )
    }

    // 5. versionCode must be > 0
    if (manifest.versionCode <= 0) {
        return Result.failure(
            IllegalArgumentException(
                "Invalid versionCode ${manifest.versionCode}: must be greater than 0",
            ),
        )
    }

    // 6. name must not be blank and capped at 100 chars
    if (manifest.name.isBlank() || manifest.name.length > 100) {
        return Result.failure(IllegalArgumentException("Plugin name must be non-blank and max 100 characters"))
    }

    // 7. version string must not be blank
    if (manifest.version.isBlank()) {
        return Result.failure(IllegalArgumentException("Plugin version cannot be blank"))
    }

    // 8. description & author size bounds
    if (manifest.description.length > 1000) {
        return Result.failure(IllegalArgumentException("Plugin description exceeds 1000 characters limit"))
    }
    manifest.author?.let { authorStr ->
        if (authorStr.length > 100) {
            return Result.failure(IllegalArgumentException("Plugin author exceeds 100 characters limit"))
        }
    }

    // 9. minAppVersionCode & minAppVersion enforcement
    if (manifest.minAppVersionCode > BuildConfig.VERSION_CODE) {
        return Result.failure(
            IllegalArgumentException(
                "Plugin requires app version code ${manifest.minAppVersionCode} or higher (current version code is ${BuildConfig.VERSION_CODE})",
            ),
        )
    }
    manifest.minAppVersion?.let { requiredVersion ->
        if (requiredVersion.isNotBlank() && !isAppVersionAtLeast(requiredVersion, BuildConfig.VERSION_NAME)) {
            return Result.failure(
                IllegalArgumentException(
                    "Plugin requires Dioxamine version $requiredVersion or higher (current version is ${BuildConfig.VERSION_NAME})",
                ),
            )
        }
    }

    // 10. Reject unknown permissions in manifest by subkey
    manifest.permissions.adb.firstOrNull { PluginPermission.fromAdbString(it) == null }?.let { unknown ->
        if (PluginPermission.fromCommonString(unknown) != null) {
            return Result.failure(
                IllegalArgumentException("Permission '$unknown' belongs to 'common', not 'adb' (move to permissions.common)"),
            )
        }
        if (PluginPermission.fromFastbootString(unknown) != null) {
            return Result.failure(
                IllegalArgumentException("Permission '$unknown' belongs to 'fastboot', not 'adb' (move to permissions.fastboot)"),
            )
        }
        return Result.failure(IllegalArgumentException("Unknown ADB permission '$unknown' in plugin manifest"))
    }

    manifest.permissions.common.firstOrNull { PluginPermission.fromCommonString(it) == null }?.let { unknown ->
        if (PluginPermission.fromAdbString(unknown) != null) {
            return Result.failure(
                IllegalArgumentException("Permission '$unknown' belongs to 'adb', not 'common' (move to permissions.adb)"),
            )
        }
        if (PluginPermission.fromFastbootString(unknown) != null) {
            return Result.failure(
                IllegalArgumentException("Permission '$unknown' belongs to 'fastboot', not 'common' (move to permissions.fastboot)"),
            )
        }
        return Result.failure(IllegalArgumentException("Unknown common permission '$unknown' in plugin manifest"))
    }

    manifest.permissions.fastboot.firstOrNull { PluginPermission.fromFastbootString(it) == null }?.let { unknown ->
        if (PluginPermission.fromAdbString(unknown) != null) {
            return Result.failure(
                IllegalArgumentException("Permission '$unknown' belongs to 'adb', not 'fastboot' (move to permissions.adb)"),
            )
        }
        if (PluginPermission.fromCommonString(unknown) != null) {
            return Result.failure(
                IllegalArgumentException("Permission '$unknown' belongs to 'common', not 'fastboot' (move to permissions.common)"),
            )
        }
        return Result.failure(IllegalArgumentException("Unknown fastboot permission '$unknown' in plugin manifest"))
    }

    return Result.success(manifest)
}

internal fun isAppVersionAtLeast(required: String, current: String): Boolean {
    val reqParts = required.trim().removePrefix("v").split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    val curParts = current.trim().removePrefix("v").split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    val maxLen = maxOf(reqParts.size, curParts.size)
    for (i in 0 until maxLen) {
        val r = reqParts.getOrElse(i) { 0 }
        val c = curParts.getOrElse(i) { 0 }
        if (c != r) return c > r
    }
    return true
}

