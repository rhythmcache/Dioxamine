package io.github.rhythmcache.dioxamine.scrcpy

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for scrcpy device metadata (discovered apps and camera sizes).
 * Persists across tab switches and Composable recompositions so long-running discovery operations
 * do not re-run needlessly when navigating between screens.
 */
object ScrcpyDiscoveryCache {
    private val appCache = ConcurrentHashMap<String, List<ScrcpyApp>>()
    private val cameraCache = ConcurrentHashMap<String, List<CameraDevice>>()

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
}
