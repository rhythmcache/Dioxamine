package io.github.rhythmcache.dioxamine.adb.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rhythmcache.adb.AdbKeyProvider
import io.github.rhythmcache.adb.pairing.PairingClient
import io.github.rhythmcache.adb.pairing.PeerInfoBuilder
import io.github.rhythmcache.adb.pairing.buildPairingIdentity
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicBoolean

/** Result of a completed QR-pairing session. */
sealed class QrPairingOutcome {
    data class Success(val guid: String?, val host: String) : QrPairingOutcome()
    data class Failure(val message: String) : QrPairingOutcome()
}

sealed class QrPairingState {
    object Starting : QrPairingState()
    object WaitingForScan : QrPairingState()
    object Paired : QrPairingState()
    data class Error(val message: String) : QrPairingState()
}

/**
 * Drives the "device scans our QR" pairing flow:
 * 1. Generates serviceName + password, builds the QR payload
 *    `WIFI:T:ADB;S:<name>;P:<password>;;`.
 * 2. Starts an mDNS browser listening for `_adb-tls-pairing._tcp` services
 *    advertising under that name (the phone that scans the QR is the pairing
 *    SERVER).
 * 3. Resolves the phone's host:port and calls [PairingClient.pair].
 * 4. Reports the paired device's ADB GUID via [QrPairingOutcome.Success].
 */
class QrPairingEngine(
    private val context: Context,
    private val keyProvider: AdbKeyProvider,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var engineJob: Job? = null
    private val pairingAttempted = AtomicBoolean(false)

    var qrPayload by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf<QrPairingState>(QrPairingState.Starting)
        private set

    fun start(scope: CoroutineScope, onResult: (QrPairingOutcome) -> Unit) {
        pairingAttempted.set(false)
        engineJob = scope.launch(Dispatchers.IO) {
            try {
                val identity = buildPairingIdentity(keyProvider)
                val ourPeerInfo = PeerInfoBuilder.forOurKeyProvider(keyProvider)

                val password = randomPassword()
                val targetServiceName = randomServiceName()

                qrPayload = buildWifiQrPayload(targetServiceName, password)
                state = QrPairingState.WaitingForScan

                startDiscovery(scope, targetServiceName, password, ourPeerInfo, identity, onResult)
            } catch (e: Exception) {
                AppLogger.e("QrPairingEngine", "Failed to initialize QR pairing engine", e)
                state = QrPairingState.Error(e.message ?: "Failed to start QR pairing engine")
                onResult(QrPairingOutcome.Failure(e.message ?: "Failed to start QR pairing engine"))
            }
        }
    }

    private fun startDiscovery(
        scope: CoroutineScope,
        targetServiceName: String,
        password: ByteArray,
        ourPeerInfo: io.github.rhythmcache.adb.pairing.PeerInfo,
        identity: io.github.rhythmcache.adb.pairing.PairingIdentity,
        onResult: (QrPairingOutcome) -> Unit
    ) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                AppLogger.i("QrPairingEngine", "mDNS discovery started for QR pairing service")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val foundName = serviceInfo.serviceName
                AppLogger.i("QrPairingEngine", "Discovered potential pairing service: $foundName")

                if (foundName == targetServiceName || foundName.contains(targetServiceName) || foundName.startsWith("studio-")) {
                    if (pairingAttempted.get()) return
                    runCatching {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                AppLogger.e("QrPairingEngine", "Failed to resolve pairing service $foundName: code=$errorCode")
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val hostStr = info.host?.hostAddress ?: return
                                val portNum = info.port
                                val cleanHost = hostStr.removePrefix("/").split("%")[0]

                                if (pairingAttempted.compareAndSet(false, true)) {
                                    AppLogger.i("QrPairingEngine", "Connecting as PairingClient to phone at $cleanHost:$portNum...")
                                    scope.launch(Dispatchers.IO) {
                                        val pairResult = PairingClient.pair(cleanHost, portNum, password, ourPeerInfo, identity)
                                        pairResult.fold(
                                            onSuccess = { pairingResult ->
                                                val guid = runCatching {
                                                    PeerInfoBuilder.extractDeviceGuid(pairingResult.peerInfo)
                                                }.getOrNull()
                                                AppLogger.i("QrPairingEngine", "Paired successfully with phone at $cleanHost! Device GUID: $guid")
                                                state = QrPairingState.Paired
                                                onResult(QrPairingOutcome.Success(guid = guid, host = cleanHost))
                                            },
                                            onFailure = { e ->
                                                AppLogger.e("QrPairingEngine", "PairingClient.pair failed", e)
                                                state = QrPairingState.Error(e.message ?: "Pairing failed")
                                                onResult(QrPairingOutcome.Failure(e.message ?: "Pairing failed"))
                                            }
                                        )
                                        stopInternal()
                                    }
                                }
                            }
                        })
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLogger.e("QrPairingEngine", "mDNS start discovery failed: code=$errorCode")
                state = QrPairingState.Error("mDNS discovery failed (code=$errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        discoveryListener = listener
        nsdManager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        engineJob?.cancel()
        engineJob = null
    }

    private fun randomPassword(): ByteArray {
        val rnd = SecureRandom()
        val digits = CharArray(10) { ('0' + rnd.nextInt(10)) }
        return String(digits).toByteArray(Charsets.US_ASCII)
    }

    private fun randomServiceName(): String {
        val rnd = SecureRandom()
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        return "studio-$suffix"
    }

    private fun buildWifiQrPayload(serviceName: String, password: ByteArray): String =
        "WIFI:T:ADB;S:$serviceName;P:${String(password, Charsets.US_ASCII)};;"
}