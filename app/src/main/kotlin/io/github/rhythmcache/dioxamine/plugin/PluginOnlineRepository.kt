package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.rhythmcache.dioxamine.BuildConfig
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
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

@OptIn(ExperimentalSerializationApi::class)
fun parsePluginIndex(inputStream: InputStream): Result<PluginIndex> {
    return runCatching {
        val root = onlineJsonParser.decodeFromStream<JsonElement>(inputStream)
        if (root !is JsonObject) {
            throw IllegalArgumentException("Root element must be a JSON object")
        }

        val schemaVersion = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 1
        val updated = (root["updated"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
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

fun parsePluginIndex(jsonString: String): Result<PluginIndex> {
    return jsonString.byteInputStream().use { parsePluginIndex(it) }
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
        const val MAX_REPO_INDEX_SIZE_BYTES = 10 * 1024 * 1024L
        private const val CACHE_FILE_NAME = "plugin_feed_cache.json"
        private const val TAG = "PluginOnlineRepo"
    }

    val officialRepoUrl: String = runCatching { BuildConfig.OFFICIAL_PLUGIN_REPO_URL }.getOrDefault(DEFAULT_REPO_URL)
    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)

    fun clearCache() {
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun loadCache(): CachedPluginFeed? {
        if (!cacheFile.exists() || !cacheFile.isFile) return null
        return runCatching {
            cacheFile.inputStream().buffered().use { input ->
                onlineJsonParser.decodeFromStream<CachedPluginFeed>(input)
            }
        }.getOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun saveCache(feed: CachedPluginFeed) {
        val parent = cacheFile.parentFile ?: return
        runCatching {
            parent.mkdirs()
            val tempFile = File.createTempFile("plugin_feed_", ".tmp", parent)
            try {
                tempFile.outputStream().buffered().use { output ->
                    onlineJsonParser.encodeToStream(feed, output)
                }
                // Atomically replace cacheFile without deleting it beforehand to prevent data loss
                if (!tempFile.renameTo(cacheFile)) {
                    tempFile.copyTo(cacheFile, overwrite = true)
                    tempFile.delete()
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to write plugin cache: ${e.message}")
        }
    }

    suspend fun fetchFromNetwork(): Result<CachedPluginFeed> = withContext(Dispatchers.IO) {
        val repoUrl = officialRepoUrl
        try {
            val conn = PluginUpdateChecker.openConnectionWithRedirects(repoUrl)
            try {
                if (conn.responseCode in 200..299) {
                    val contentLength = conn.contentLengthLong
                    if (contentLength > MAX_REPO_INDEX_SIZE_BYTES) {
                        AppLogger.w(TAG, "Repository at $repoUrl rejected: Content-Length $contentLength exceeds 10MB limit")
                        return@withContext Result.failure(IOException("Repository index exceeds maximum allowed size of 10MB"))
                    }

                    val tempFile = File.createTempFile("repo_index_", ".json", context.cacheDir)
                    try {
                        downloadIndexStreamToTempFile(conn.inputStream, tempFile, MAX_REPO_INDEX_SIZE_BYTES)

                        val parsed = tempFile.inputStream().buffered().use { parsePluginIndex(it) }
                        parsed.fold(
                            onSuccess = { index ->
                                val deduplicated = deduplicatePlugins(listOf(index.plugins))
                                val feed = CachedPluginFeed(
                                    fetchedAtMs = System.currentTimeMillis(),
                                    updated = index.updated,
                                    plugins = deduplicated,
                                )
                                saveCache(feed)
                                Result.success(feed)
                            },
                            onFailure = { err ->
                                AppLogger.w(TAG, "Failed to parse repository at $repoUrl: ${err.message}")
                                Result.failure(err)
                            }
                        )
                    } finally {
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                    }
                } else {
                    AppLogger.w(TAG, "Repository HTTP ${conn.responseCode} at $repoUrl")
                    Result.failure(IOException("HTTP ${conn.responseCode}"))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to connect to repository at $repoUrl: ${e.message}")
            Result.failure(e)
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

internal fun downloadIndexStreamToTempFile(
    input: InputStream,
    destination: File,
    maxBytes: Long = PluginOnlineRepositoryManager.MAX_REPO_INDEX_SIZE_BYTES,
): Long {
    var totalBytes = 0L
    destination.outputStream().buffered().use { output ->
        val buffer = ByteArray(8192)
        var bytes: Int
        while (input.read(buffer).also { bytes = it } >= 0) {
            totalBytes += bytes
            if (totalBytes > maxBytes) {
                throw IOException("Repository index exceeds maximum allowed size of ${maxBytes / (1024 * 1024)}MB")
            }
            output.write(buffer, 0, bytes)
        }
    }
    return totalBytes
}
