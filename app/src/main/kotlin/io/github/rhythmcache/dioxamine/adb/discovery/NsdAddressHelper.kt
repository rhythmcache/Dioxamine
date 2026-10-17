package io.github.rhythmcache.dioxamine.adb.discovery

import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.rhythmcache.dioxamine.core.AppLogger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Helper for extracting candidate host addresses from a resolved [NsdServiceInfo].
 *
 * Prioritizes candidates in the following order:
 * 1. IPv4 addresses (excluding loopback and wildcard)
 * 2. Global / site-local IPv6 addresses (excluding link-local, loopback, and wildcard)
 * 3. Link-local IPv6 addresses with scope ID preserved (e.g. fe80::...%wlan0 or %24)
 *
 * For API >= 34, uses [NsdServiceInfo.getHostAddresses].
 * Below API 34, uses [NsdServiceInfo.getHost].
 */
object NsdAddressHelper {

    fun pickHosts(info: NsdServiceInfo): List<String> {
        val rawAddresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }

        val candidates = mutableListOf<String>()

        // 1. IPv4 candidates
        rawAddresses
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
            .mapNotNull { addr ->
                addr.hostAddress?.removePrefix("/")?.split("%")?.get(0)
            }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { candidates.add(it) }

        // 2. Global IPv6 candidates (non link-local)
        rawAddresses
            .filterIsInstance<Inet6Address>()
            .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isLinkLocalAddress }
            .mapNotNull { addr ->
                addr.hostAddress?.removePrefix("/")?.split("%")?.get(0)
            }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { candidates.add(it) }

        // 3. Link-local IPv6 candidates (MUST preserve scope ID)
        rawAddresses
            .filterIsInstance<Inet6Address>()
            .filter { it.isLinkLocalAddress && !it.isLoopbackAddress && !it.isAnyLocalAddress }
            .mapNotNull { addr ->
                val hostStr = addr.hostAddress?.removePrefix("/") ?: return@mapNotNull null
                if (!hostStr.contains("%")) {
                    val scope = addr.scopedInterface?.name
                        ?: (if (addr.scopeId > 0) addr.scopeId.toString() else null)
                    if (scope != null) "$hostStr%$scope" else hostStr
                } else {
                    hostStr
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { candidates.add(it) }

        AppLogger.i("NsdAddressHelper", "API ${Build.VERSION.SDK_INT}: picked ${candidates.size} candidate(s) for ${info.serviceName}: $candidates")
        return candidates
    }
}

