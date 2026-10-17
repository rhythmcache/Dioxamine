package io.github.rhythmcache.dioxamine.adb.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rhythmcache.adb.AdbKeyProvider
import io.github.rhythmcache.adb.pairing.PairingClient
import io.github.rhythmcache.adb.pairing.PeerInfoBuilder
import io.github.rhythmcache.adb.pairing.buildPairingIdentity
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.SecureRandom
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
    private val outcomeDispatched = AtomicBoolean(false)

    var qrPayload by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf<QrPairingState>(QrPairingState.Starting)
        private set

    fun start(scope: CoroutineScope, onResult: (QrPairingOutcome) -> Unit) {
        pairingAttempted.set(false)
        outcomeDispatched.set(false)
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
                dispatchOutcome(QrPairingOutcome.Failure(e.message ?: "Failed to start QR pairing engine"), onResult) {
                    state = QrPairingState.Error(e.message ?: "Failed to start QR pairing engine")
                }
            }
        }
    }

    private fun dispatchOutcome(
        outcome: QrPairingOutcome,
        onResult: (QrPairingOutcome) -> Unit,
        updateState: () -> Unit
    ) {
        if (outcomeDispatched.compareAndSet(false, true)) {
            updateState()
            onResult(outcome)
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

                    NsdResolver.resolve(
                        nsdManager = nsdManager,
                        serviceInfo = serviceInfo,
                        onResolved = { info ->
                            val candidates = NsdAddressHelper.pickHosts(info)
                            if (candidates.isEmpty()) {
                                val msg = "No usable host addresses found for ${info.serviceName}"
                                AppLogger.w("QrPairingEngine", msg)
                                if (!pairingAttempted.get()) {
                                    dispatchOutcome(QrPairingOutcome.Failure(msg), onResult) {
                                        state = QrPairingState.Error(msg)
                                    }
                                }
                            } else {
                                val portNum = info.port
                                if (pairingAttempted.compareAndSet(false, true)) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            var lastError: Throwable? = null
                                            var success = false

                                            for ((index, candidateHost) in candidates.withIndex()) {
                                                AppLogger.i("QrPairingEngine", "Connecting as PairingClient to phone at $candidateHost:$portNum (API ${Build.VERSION.SDK_INT}, candidate ${index + 1}/${candidates.size})...")
                                                val pairResult = PairingClient.pair(candidateHost, portNum, password, ourPeerInfo, identity)
                                                pairResult.fold(
                                                    onSuccess = { pairingResult ->
                                                        val guid = runCatching {
                                                            PeerInfoBuilder.extractDeviceGuid(pairingResult.peerInfo)
                                                        }.getOrNull()
                                                        AppLogger.i("QrPairingEngine", "Paired successfully with phone at $candidateHost! Device GUID: $guid (used candidate $candidateHost, API ${Build.VERSION.SDK_INT})")
                                                        dispatchOutcome(QrPairingOutcome.Success(guid = guid, host = candidateHost), onResult) {
                                                            state = QrPairingState.Paired
                                                        }
                                                        stopInternal()
                                                        success = true
                                                    },
                                                    onFailure = { e ->
                                                        lastError = e
                                                        AppLogger.w("QrPairingEngine", "PairingClient.pair failed on candidate $candidateHost: ${e.message}")
                                                        if (index < candidates.size - 1) {
                                                            AppLogger.i("QrPairingEngine", "Trying next candidate address (${index + 2}/${candidates.size})...")
                                                        }
                                                    }
                                                )
                                                if (success) {
                                                    return@launch
                                                }
                                            }

                                            AppLogger.e("QrPairingEngine", "PairingClient.pair failed on all candidates: $candidates", lastError)
                                            dispatchOutcome(QrPairingOutcome.Failure(lastError?.message ?: "Pairing failed"), onResult) {
                                                state = QrPairingState.Error(lastError?.message ?: "Pairing failed")
                                            }
                                            stopInternal()
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            AppLogger.e("QrPairingEngine", "Exception during pairing attempt", e)
                                            dispatchOutcome(QrPairingOutcome.Failure(e.message ?: "Pairing failed"), onResult) {
                                                state = QrPairingState.Error(e.message ?: "Pairing failed")
                                            }
                                            stopInternal()
                                        }
                                    }
                                }
                            }
                        },
                        onFailed = { errorCode ->
                            val msg = "Failed to resolve pairing service $foundName: code=$errorCode"
                            AppLogger.e("QrPairingEngine", msg)
                            if (!pairingAttempted.get()) {
                                dispatchOutcome(QrPairingOutcome.Failure(msg), onResult) {
                                    state = QrPairingState.Error(msg)
                                }
                            }
                        }
                    )
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                val msg = "mDNS discovery failed (code=$errorCode)"
                AppLogger.e("QrPairingEngine", "mDNS start discovery failed: code=$errorCode")
                dispatchOutcome(QrPairingOutcome.Failure(msg), onResult) {
                    state = QrPairingState.Error(msg)
                }
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