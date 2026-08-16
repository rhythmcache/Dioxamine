package io.github.rhythmcache.dioxamine.plugin

import io.github.rhythmcache.dioxamine.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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
    val permissions: List<String> = emptyList(),
    val homepage: String? = null,
    val fullscreen: Boolean = false,
)

private val jsonParser = Json { ignoreUnknownKeys = true }
private val idRegex = Regex("^[a-z0-9]+(\\.[a-z0-9_]+)+$")

fun parseManifest(json: String): Result<PluginManifest> {
    val manifest =
        try {
            jsonParser.decodeFromString<PluginManifest>(json)
        } catch (e: SerializationException) {
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

    // 9. minAppVersionCode enforcement
    if (manifest.minAppVersionCode > BuildConfig.VERSION_CODE) {
        return Result.failure(
            IllegalArgumentException(
                "Plugin requires app version code ${manifest.minAppVersionCode}, but current app version is ${BuildConfig.VERSION_CODE}",
            ),
        )
    }

    // 10. Reject unknown permissions in manifest
    manifest.permissions.firstOrNull { PluginPermission.fromManifestString(it) == null }?.let { unknownPerm ->
        return Result.failure(IllegalArgumentException("Unknown permission '$unknownPerm' in plugin manifest"))
    }

    return Result.success(manifest)
}
