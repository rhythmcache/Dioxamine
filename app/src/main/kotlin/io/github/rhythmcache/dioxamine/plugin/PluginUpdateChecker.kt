package io.github.rhythmcache.dioxamine.plugin

import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class PluginUpdateInfo(
    val id: String,
    val version: String,
    val versionCode: Int,
    val download: String,
    val changelog: String? = null,
)

object PluginUpdateChecker {
    private const val TAG = "PluginUpdateChecker"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_FILE_SIZE_BYTES = 500L * 1024L * 1024L // 500MB safety limit

    internal fun openConnectionWithRedirects(initialUrl: String, maxRedirects: Int = MAX_REDIRECTS): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Dioxamine-Android-App")
                setRequestProperty("Accept", "*/*")
                instanceFollowRedirects = false
            }
            try {
                val responseCode = conn.responseCode
                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrBlank()) {
                        currentUrl = URL(url, location).toString()
                        redirects++
                        continue
                    }
                }
                return conn
            } catch (e: Throwable) {
                conn.disconnect()
                throw e
            }
        }
        throw IOException("Too many redirects")
    }

    /**
     * Fetches and deserializes [PluginUpdateInfo] from the provided remote JSON URL.
     *
     * Validates that the payload contains a non-blank ID, version string, positive versionCode,
     * and a valid HTTP/HTTPS download URL. Also ensures changelog URL, if present, is a valid
     * HTTP/HTTPS scheme.
     *
     * Note: This method does NOT check whether [PluginUpdateInfo.id] matches the installed plugin ID
     * or whether [PluginUpdateInfo.versionCode] is newer than the installed version. Those comparisons
     * are performed by the caller / repository layer.
     */
    suspend fun fetchUpdate(updateJsonUrl: String): PluginUpdateInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmedUrl = updateJsonUrl.trim()
                if (!trimmedUrl.startsWith("http://", ignoreCase = true) && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
                    return@runCatching null
                }
                val conn = openConnectionWithRedirects(trimmedUrl)
                try {
                    if (conn.responseCode !in 200..299) {
                        return@runCatching null
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = json.decodeFromString<PluginUpdateInfo>(body)
                    if (parsed.id.isBlank() || parsed.version.isBlank() || parsed.versionCode <= 0) {
                        return@runCatching null
                    }
                    val downloadUrl = parsed.download.trim()
                    if (downloadUrl.isBlank() || (!downloadUrl.startsWith("http://", ignoreCase = true) && !downloadUrl.startsWith("https://", ignoreCase = true))) {
                        return@runCatching null
                    }
                    val changelogUrl = parsed.changelog?.trim()
                    val validChangelog = if (!changelogUrl.isNullOrBlank() &&
                        (changelogUrl.startsWith("http://", ignoreCase = true) || changelogUrl.startsWith("https://", ignoreCase = true))) {
                        changelogUrl
                    } else {
                        null
                    }
                    parsed.copy(
                        download = downloadUrl,
                        changelog = validChangelog,
                    )
                } finally {
                    conn.disconnect()
                }
            }.onFailure { e ->
                AppLogger.w(TAG, "Failed to fetch plugin update from $updateJsonUrl: ${e.message}")
            }.getOrNull()
        }

    suspend fun downloadFile(downloadUrl: String, destinationFile: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmedUrl = downloadUrl.trim()
                if (!trimmedUrl.startsWith("http://", ignoreCase = true) && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
                    return@runCatching false
                }
                val conn = openConnectionWithRedirects(trimmedUrl)
                try {
                    if (conn.responseCode !in 200..299) {
                        return@runCatching false
                    }
                    val contentLength = conn.contentLengthLong
                    if (contentLength > MAX_FILE_SIZE_BYTES) {
                        return@runCatching false
                    }
                    destinationFile.parentFile?.mkdirs()
                    var totalRead = 0L
                    conn.inputStream.use { input ->
                        destinationFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytes: Int
                            while (input.read(buffer).also { bytes = it } >= 0) {
                                totalRead += bytes
                                if (totalRead > MAX_FILE_SIZE_BYTES) {
                                    return@runCatching false
                                }
                                output.write(buffer, 0, bytes)
                            }
                        }
                    }
                    totalRead > 0
                } finally {
                    conn.disconnect()
                }
            }.onFailure { e ->
                AppLogger.w(TAG, "Failed to download update from $downloadUrl: ${e.message}")
                destinationFile.delete()
            }.getOrElse {
                destinationFile.delete()
                false
            }
        }
}
