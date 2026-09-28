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

sealed class DiscoveryQueryResult {
    data class Success(val output: String) : DiscoveryQueryResult()
    data class TimedOut(val timeoutMs: Long) : DiscoveryQueryResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : DiscoveryQueryResult()
}

/**
 * In-memory cache for scrcpy device metadata (discovered apps and camera sizes).
 * Persists across tab switches and Composable recompositions so long-running discovery operations
 * do not re-run needlessly when navigating between screens.
 */
object ScrcpyDiscoveryCache {
    private const val TAG = "ScrcpyDiscovery"
    private val appCache = ConcurrentHashMap<String, List<ScrcpyApp>>()
    private val cameraCache = ConcurrentHashMap<String, List<CameraDevice>>()
    private val deviceMutexes = ConcurrentHashMap<String, Mutex>()

    private fun getDeviceMutex(deviceId: String): Mutex =
        deviceMutexes.computeIfAbsent(deviceId) { Mutex() }

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
        // Device mutexes are intentionally retained across disconnects.
        // If a query is in-flight when a device disconnects/reconnects, removing the mutex
        // would cause computeIfAbsent to create a fresh Mutex, allowing concurrent server push races.
    }

    fun clearAll() {
        appCache.clear()
        cameraCache.clear()
        // Device mutexes are retained for the same reason.
    }

    /**
     * Executes a scrcpy-server query command (e.g. list_apps=true or list_camera_sizes=true)
     * safely serialized per device via Mutex to avoid concurrent server push races.
     * The entire operation (lock wait, jar push, and execution) is bounded by timeoutMs.
     */
    suspend fun runQuery(
        client: AdbClient,
        context: Context,
        deviceId: String,
        queryArg: String,
        timeoutMs: Long = 25_000L
    ): DiscoveryQueryResult = withContext(Dispatchers.IO) {
        val mutex = getDeviceMutex(deviceId)
        val result = withTimeoutOrNull(timeoutMs) {
            mutex.withLock {
                try {
                    context.assets.open("scrcpy-server.jar").use { input ->
                        client.sync.push(input, "${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to push scrcpy-server.jar for query: $queryArg", e)
                    return@withLock DiscoveryQueryResult.Failed("Failed to push server jar: ${e.message}", e)
                }

                val cmd = "CLASSPATH=${Constants.DEVICE_TMP_DIR}/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1 log_level=info $queryArg cleanup=false"

                try {
                    val shellRes = client.shell(cmd)
                    val output = shellRes.stdoutText.ifBlank { shellRes.stderrText }
                    DiscoveryQueryResult.Success(output)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(TAG, "client.shell() failed for query: $queryArg", e)
                    DiscoveryQueryResult.Failed("Command execution failed: ${e.message}", e)
                }
            }
        }

        if (result == null) {
            AppLogger.w(TAG, "Discovery query timed out after ${timeoutMs}ms for: $queryArg")
            DiscoveryQueryResult.TimedOut(timeoutMs)
        } else {
            result
        }
    }
}
