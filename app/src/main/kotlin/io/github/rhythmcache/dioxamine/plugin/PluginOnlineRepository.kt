package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Serializable
data class PluginIndexItem(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val download: String,
    val description: String = "",
    val author: String? = null,
    val icon: String? = null,
    val homepage: String? = null,
    val permissions: PluginPermissionsConfig = PluginPermissionsConfig(),
    val changelog: String? = null,
    val updateUrl: String? = null,
    val repo: String? = null,
)

@Serializable
data class PluginIndex(
    val schemaVersion: Int = 1,
    val updated: String? = null,
    val count: Int = 0,
    val plugins: List<PluginIndexItem> = emptyList(),
)

@Serializable
data class CachedPluginFeed(
    val fetchedAtMs: Long,
    val updated: String? = null,
    val plugins: List<PluginIndexItem> = emptyList(),
)

internal val onlineJsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun parsePluginIndex(jsonString: String): Result<PluginIndex> {
    return runCatching {
        val root = onlineJsonParser.parseToJsonElement(jsonString)
        if (root !is JsonObject) {
            throw IllegalArgumentException("Root element must be a JSON object")
        }

        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        val updated = root["updated"]?.jsonPrimitive?.contentOrNull
        val pluginsElement = root["plugins"]

        val pluginsList = if (pluginsElement is JsonArray) {
            pluginsElement.mapNotNull { itemElement ->
                runCatching {
                    val item = runCatching {
                        onlineJsonParser.decodeFromJsonElement<PluginIndexItem>(itemElement)
                    }.getOrNull() ?: return@mapNotNull null

                    val trimmedId = item.id.trim()
                    val trimmedName = item.name.trim()
                    val trimmedVersion = item.version.trim()
                    val trimmedDownload = item.download.trim()

                    if (trimmedId.isBlank() || trimmedName.isBlank() || trimmedVersion.isBlank() ||
                        item.versionCode <= 0 || trimmedDownload.isBlank() ||
                        (!trimmedDownload.startsWith("http://", ignoreCase = true) && !trimmedDownload.startsWith("https://", ignoreCase = true))
                    ) {
                        return@mapNotNull null
                    }

                    val validChangelog = item.changelog?.trim()?.let {
                        if (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) it else null
                    }

                    val validUpdateUrl = item.updateUrl?.trim()?.let {
                        if (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) it else null
                    }

                    item.copy(
                        id = trimmedId,
                        name = trimmedName,
                        version = trimmedVersion,
                        download = trimmedDownload,
                        changelog = validChangelog,
                        updateUrl = validUpdateUrl,
                    )
                }.getOrNull()
            }
        } else {
            emptyList()
        }

        PluginIndex(
            schemaVersion = schemaVersion,
            updated = updated,
            count = pluginsList.size,
            plugins = pluginsList,
        )
    }
}

fun decodeBase64Icon(iconStr: String?): ImageBitmap? {
    if (iconStr.isNullOrBlank()) return null
    return runCatching {
        val base64Data = if (iconStr.contains(",")) {
            iconStr.substringAfter(",")
        } else {
            iconStr
        }.trim()
        val decodedBytes = runCatching {
            android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        }.getOrElse {
            java.util.Base64.getDecoder().decode(base64Data)
        }
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)?.asImageBitmap()
    }.getOrNull()
}

fun parseIsoTimeMs(iso: String): Long? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.Instant.parse(iso).toEpochMilli()
        } else {
            val cleaned = iso.trim()
            val pattern = when {
                cleaned.endsWith("Z", ignoreCase = true) && cleaned.contains(".") -> "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
                cleaned.endsWith("Z", ignoreCase = true) -> "yyyy-MM-dd'T'HH:mm:ss'Z'"
                cleaned.contains(".") -> "yyyy-MM-dd'T'HH:mm:ss.SSS"
                else -> "yyyy-MM-dd'T'HH:mm:ss"
            }
            val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(cleaned)?.time
        }
    }.getOrNull()
}

fun formatRelativeTime(context: Context, updatedIso: String?, fetchedAtMs: Long): String {
    val timeMs = updatedIso?.let { parseIsoTimeMs(it) } ?: fetchedAtMs
    if (timeMs <= 0L) return ""
    val now = System.currentTimeMillis()
    return DateUtils.getRelativeTimeSpanString(
        timeMs,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

suspend fun downloadAndInstallPlugin(
    context: Context,
    repo: PluginRepository,
    plugin: PluginIndexItem,
): PluginInstallResult = withContext(Dispatchers.IO) {
    val tempZip = File(context.cacheDir, "plugin_online_${plugin.id}_${UUID.randomUUID()}.zip")
    try {
        val downloaded = PluginUpdateChecker.downloadFile(plugin.download, tempZip)
        if (!downloaded || !tempZip.exists() || tempZip.length() == 0L) {
            return@withContext PluginInstallResult.Error(
                context.getString(R.string.plugin_msg_download_failed),
            )
        }
        repo.installFromZipFile(tempZip)
    } finally {
        if (tempZip.exists()) {
            tempZip.delete()
        }
    }
}

class PluginOnlineRepositoryManager(private val context: Context) {
    companion object {
        const val DEFAULT_REPO_URL = "https://raw.githubusercontent.com/Dioxamine-plugins-repo/index/main/index.json"
        private const val PREFS_NAME = "plugin_repositories"
        private const val PREFS_KEY_URLS = "configured_urls"
        private const val CACHE_FILE_NAME = "plugin_feed_cache.json"
        private const val TAG = "PluginOnlineRepo"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)

    fun getRepositories(): List<String> {
        val raw = prefs.getString(PREFS_KEY_URLS, null)
        if (raw.isNullOrBlank()) {
            return listOf(DEFAULT_REPO_URL)
        }
        return runCatching {
            onlineJsonParser.decodeFromString<List<String>>(raw)
        }.getOrDefault(listOf(DEFAULT_REPO_URL)).ifEmpty {
            listOf(DEFAULT_REPO_URL)
        }
    }

    fun setRepositories(urls: List<String>) {
        val cleaned = urls.map { it.trim() }.filter {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }.distinct()
        val toSave = cleaned.ifEmpty { listOf(DEFAULT_REPO_URL) }
        val serialized = onlineJsonParser.encodeToString(toSave)
        prefs.edit().putString(PREFS_KEY_URLS, serialized).apply()
        clearCache()
    }

    fun addRepository(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val current = getRepositories().toMutableList()
        if (current.any { it.equals(trimmed, ignoreCase = true) }) {
            return false
        }
        current.add(trimmed)
        setRepositories(current)
        return true
    }

    fun removeRepository(url: String): Boolean {
        val current = getRepositories().toMutableList()
        val removed = current.removeAll { it.equals(url.trim(), ignoreCase = true) }
        if (removed) {
            setRepositories(current)
            return true
        }
        return false
    }

    fun resetToDefault(): List<String> {
        val defaults = listOf(DEFAULT_REPO_URL)
        setRepositories(defaults)
        return defaults
    }

    fun clearCache() {
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
    }

    fun loadCache(): CachedPluginFeed? {
        if (!cacheFile.exists() || !cacheFile.isFile) return null
        return runCatching {
            val content = cacheFile.readText()
            onlineJsonParser.decodeFromString<CachedPluginFeed>(content)
        }.getOrNull()
    }

    fun saveCache(feed: CachedPluginFeed) {
        runCatching {
            val content = onlineJsonParser.encodeToString(feed)
            cacheFile.writeText(content)
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to write plugin cache: ${e.message}")
        }
    }

    suspend fun fetchFromNetwork(): Result<CachedPluginFeed> = withContext(Dispatchers.IO) {
        val repos = getRepositories()
        if (repos.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No repository URLs configured"))
        }

        val repoPluginsList = mutableListOf<List<PluginIndexItem>>()
        var latestUpdated: String? = null
        var anySuccess = false
        var lastError: Throwable? = null

        for (repoUrl in repos) {
            try {
                val conn = PluginUpdateChecker.openConnectionWithRedirects(repoUrl)
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val parsed = parsePluginIndex(body)
                        parsed.fold(
                            onSuccess = { index ->
                                anySuccess = true
                                repoPluginsList.add(index.plugins)
                                if (latestUpdated == null && index.updated != null) {
                                    latestUpdated = index.updated
                                }
                            },
                            onFailure = { err ->
                                AppLogger.w(TAG, "Failed to parse repository at $repoUrl: ${err.message}")
                                lastError = err
                            }
                        )
                    } else {
                        AppLogger.w(TAG, "Repository HTTP ${conn.responseCode} at $repoUrl")
                        lastError = IOException("HTTP ${conn.responseCode}")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to connect to repository at $repoUrl: ${e.message}")
                lastError = e
            }
        }

        if (anySuccess) {
            val deduplicated = deduplicatePlugins(repoPluginsList)
            val feed = CachedPluginFeed(
                fetchedAtMs = System.currentTimeMillis(),
                updated = latestUpdated,
                plugins = deduplicated,
            )
            saveCache(feed)
            Result.success(feed)
        } else {
            Result.failure(lastError ?: IOException("Failed to fetch repository"))
        }
    }
}

internal fun deduplicatePlugins(
    repoPluginsList: List<List<PluginIndexItem>>,
): List<PluginIndexItem> {
    // Repositories are processed in priority order (first repository has highest priority).
    // A plugin ID claimed by a higher-priority repository cannot be shadowed by later repositories.
    // Within the same repository, if multiple versions of the same plugin exist, the highest versionCode wins.
    val result = LinkedHashMap<String, PluginIndexItem>()
    for (pluginsInRepo in repoPluginsList) {
        val repoBest = pluginsInRepo.groupBy { it.id }.mapValues { (_, items) ->
            items.maxByOrNull { it.versionCode }!!
        }
        for ((id, item) in repoBest) {
            if (!result.containsKey(id)) {
                result[id] = item
            }
        }
    }
    return result.values.sortedBy { it.name.lowercase() }
}
