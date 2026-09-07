package io.github.rhythmcache.dioxamine.adb.discovery

import android.os.Build
import io.github.rhythmcache.dioxamine.core.AppLogger
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Socket

data class LocalAdbTarget(
    val port: Int,
    val isTls: Boolean
)

/**
 * Detects whether the local Android device has an active ADB daemon running on localhost.
 */
object LocalAdbDetector {
    private const val TAG = "LocalAdbDetector"

    @Volatile
    private var lastKnownTarget: LocalAdbTarget? = null

    fun detect(): LocalAdbTarget? {
        // Check previously detected target first (< 0.1 ms)
        val cached = lastKnownTarget
        if (cached != null && isPortOpen("127.0.0.1", cached.port, 80)) {
            return cached
        }
        lastKnownTarget = null

        // Try reflection on service.adb.tcp.port (zero shell commands)
        val tcpPort = getSystemProperty("service.adb.tcp.port")?.toIntOrNull()?.takeIf { it in 1..65535 }
        if (tcpPort != null && isPortOpen("127.0.0.1", tcpPort, 120)) {
            AppLogger.i(TAG, "Detected local ADB on port $tcpPort via SystemProperties")
            return LocalAdbTarget(tcpPort, isTls = false).also { lastKnownTarget = it }
        }

        // Try reflection on service.adb.tls.port (Android 11+ / API 30+ only)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val tlsPort = getSystemProperty("service.adb.tls.port")?.toIntOrNull()?.takeIf { it in 1..65535 }
            if (tlsPort != null && isPortOpen("127.0.0.1", tlsPort, 120)) {
                AppLogger.i(TAG, "Detected local ADB on port $tlsPort via SystemProperties (TLS)")
                return LocalAdbTarget(tlsPort, isTls = true).also { lastKnownTarget = it }
            }
        }

        // Fast socket probe on standard port 5555 (skip if checked above)
        if (tcpPort != 5555 && isPortOpen("127.0.0.1", 5555, 120)) {
            AppLogger.i(TAG, "Detected local ADB on port 5555 via socket probe")
            return LocalAdbTarget(5555, isTls = false).also { lastKnownTarget = it }
        }

        // Probe standard AOSP odd ports 5557..5585
        for (port in 5557..5585 step 2) {
            if (isPortOpen("127.0.0.1", port, 60)) {
                AppLogger.i(TAG, "Detected local ADB on port $port via socket probe")
                return LocalAdbTarget(port, isTls = false).also { lastKnownTarget = it }
            }
        }

        return null
    }

    
    private fun getSystemProperty(key: String): String? {
        return try {
            val systemProperties: Class<*> = Class.forName("android.os.SystemProperties")
            val getMethod: Method = systemProperties.getMethod("get", String::class.java)
            val value = getMethod.invoke(null, key) as? String
            value?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }
}
