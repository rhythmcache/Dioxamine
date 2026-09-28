package io.github.rhythmcache.dioxamine.scrcpy

import android.content.Context
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for scrcpy device metadata (discovered apps and camera sizes).
 * Persists across tab switches and Composable recompositions so long-running discovery operations
 * do not re-run needlessly when navigating between screens.
 */
object ScrcpyDiscoveryCache {
    private const val TAG = "ScrcpyDiscovery"
    private val appCache = ConcurrentHashMap<String, List<ScrcpyApp>>()
    private val cameraCache = ConcurrentHashMap<String, List<CameraDevice>>()
    private val queryMutex = Mutex()

    fun getApps(deviceId: String): List<ScrcpyApp>? = appCache[deviceId]

    fun setApps(deviceId: String, apps: List<ScrcpyApp>) {
        appCache[deviceId] = apps
    }

    fun getCameras(deviceId: String): List<CameraDevice>? = cameraCache[deviceId]

    fun setCameras(deviceId: String, cameras: List<CameraDevice>) {
        cameraCache[deviceId] = cameras
    }

    fun clearApps(deviceId: String) {
        appCache.remove(deviceId)
    }

    fun clearCameras(deviceId: String) {
        cameraCache.remove(deviceId)
    }

    fun clear(deviceId: String) {
        appCache.remove(deviceId)
        cameraCache.remove(deviceId)
    }

    fun clearAll() {
        appCache.clear()
        cameraCache.clear()
    }

    /**
     * Executes a scrcpy-server query command (e.g. list_apps=true or list_camera_sizes=true)
     * safely serialized via Mutex to avoid concurrent server push races.
     */
    suspend fun runQuery(
        client: AdbClient,
        context: Context,
        queryArg: String,
        timeoutMs: Long = 25_000L
    ): String? = withContext(Dispatchers.IO) {
        queryMutex.withLock {
            try {
                context.assets.open("scrcpy-server.jar").use { input ->
                    client.sync.push(input, "${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to push scrcpy-server.jar for query: $queryArg", e)
                return@withLock null
            }

            val cmd = "CLASSPATH=${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 log_level=info $queryArg cleanup=false"

            val output = withTimeoutOrNull(timeoutMs) {
                try {
                    val shellRes = client.shell(cmd)
                    shellRes.stdoutText.ifBlank { shellRes.stderrText }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(TAG, "client.shell() threw exception, falling back to open(shell:)", e)
                    try {
                        val stream = client.open("shell:$cmd")
                        val buf = ByteArray(4096)
                        val sb = StringBuilder()
                        stream.use { s ->
                            while (true) {
                                val n = s.read(buf)
                                if (n == -1) break
                                sb.append(String(buf, 0, n, Charsets.UTF_8))
                            }
                            sb.toString()
                        }
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        AppLogger.e(TAG, "Fallback open(shell:) also failed", e2)
                        null
                    }
                }
            }

            if (output == null) {
                AppLogger.w(TAG, "Discovery query timed out after ${timeoutMs}ms for: $queryArg")
            }

            output
        }
    }
}
