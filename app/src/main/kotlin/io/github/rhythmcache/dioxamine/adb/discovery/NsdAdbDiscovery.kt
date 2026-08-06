package io.github.rhythmcache.dioxamine.adb.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.mutableStateMapOf
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

enum class AdbServiceType(val serviceType: String) {
    TLS_CONNECT("_adb-tls-connect._tcp"),
    TLS_PAIRING("_adb-tls-pairing._tcp"),
    TCP("_adb._tcp")
}

data class DiscoveredAdbDevice(
    val serviceName: String,
    val host: String,
    val port: Int,
    val type: AdbServiceType,
    val deviceId: String = extractDeviceIdentity(serviceName)
)

fun extractDeviceIdentity(serviceName: String): String {
    val trimmed = serviceName.trim()
    if (trimmed.startsWith("adb-")) {
        val afterPrefix = trimmed.removePrefix("adb-")
        val lastDash = afterPrefix.lastIndexOf('-')
        if (lastDash > 0) {
            return afterPrefix.substring(0, lastDash)
        }
    }
    return trimmed
}

class NsdAdbDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val discovered = mutableStateMapOf<String, DiscoveredAdbDevice>()

    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var isDiscovering = false

    private val resolveQueue = ConcurrentLinkedQueue<Pair<NsdServiceInfo, AdbServiceType>>()
    private val resolveLock = Any()
    private var isResolving = false
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (isDiscovering) return
        isDiscovering = true
        discovered.clear()
        resolveQueue.clear()
        AdbServiceType.values().forEach { startForType(it) }
    }

    fun stop() {
        if (!isDiscovering) return
        isDiscovering = false
        listeners.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listeners.clear()
        resolveQueue.clear()
    }

    private fun startForType(type: AdbServiceType) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                AppLogger.i("NsdAdbDiscovery", "Discovery started for $serviceType")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                AppLogger.i("NsdAdbDiscovery", "Service found: ${service.serviceName} (${type.serviceType})")
                enqueueResolve(service, type)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                AppLogger.i("NsdAdbDiscovery", "Service lost: ${service.serviceName}")
                discovered.remove("${service.serviceName}|${type.serviceType}")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLogger.e("NsdAdbDiscovery", "Start discovery failed for $serviceType: code=$errorCode")
                isDiscovering = false
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listeners.add(listener)
        runCatching {
            nsdManager.discoverServices(type.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun enqueueResolve(service: NsdServiceInfo, type: AdbServiceType) {
        resolveQueue.add(Pair(service, type))
        processNextResolve()
    }

    private fun processNextResolve() {
        synchronized(resolveLock) {
            if (isResolving || resolveQueue.isEmpty()) return
            isResolving = true
        }

        val item = resolveQueue.poll()
        if (item == null) {
            synchronized(resolveLock) { isResolving = false }
            return
        }

        val (service, type) = item
        AppLogger.i("NsdAdbDiscovery", "Resolving service: ${service.serviceName} (${type.serviceType})")

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AppLogger.e("NsdAdbDiscovery", "Resolve failed for ${serviceInfo.serviceName}: code=$errorCode")
                if (errorCode == 3) {
                    // FAILURE_ALREADY_ACTIVE -> retry after short delay
                    scope.launch {
                        delay(250)
                        enqueueResolve(serviceInfo, type)
                    }
                }
                synchronized(resolveLock) {
                    isResolving = false
                }
                processNextResolve()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val hostStr = serviceInfo.host?.hostAddress
                if (hostStr != null) {
                    val cleanHost = hostStr.removePrefix("/").split("%")[0]
                    val key = "${serviceInfo.serviceName}|${type.serviceType}"
                    val deviceId = extractDeviceIdentity(serviceInfo.serviceName)
                    discovered[key] = DiscoveredAdbDevice(serviceInfo.serviceName, cleanHost, serviceInfo.port, type, deviceId)
                    AppLogger.i("NsdAdbDiscovery", "Successfully resolved ${serviceInfo.serviceName} -> $cleanHost:${serviceInfo.port} (deviceId=$deviceId)")
                }
                synchronized(resolveLock) {
                    isResolving = false
                }
                processNextResolve()
            }
        }

        runCatching {
            nsdManager.resolveService(service, resolveListener)
        }.onFailure { e ->
            AppLogger.e("NsdAdbDiscovery", "Exception calling resolveService for ${service.serviceName}", e)
            synchronized(resolveLock) { isResolving = false }
            processNextResolve()
        }
    }

    // convenience: does this host currently advertise TLS_CONNECT (paired)?
    fun isPairedHost(host: String): Boolean =
        discovered.values.any { it.host == host && it.type == AdbServiceType.TLS_CONNECT }

    fun isPairingOnlyHost(host: String): Boolean =
        discovered.values.any { it.host == host && it.type == AdbServiceType.TLS_PAIRING } &&
        discovered.values.none { it.host == host && it.type == AdbServiceType.TLS_CONNECT }
}
