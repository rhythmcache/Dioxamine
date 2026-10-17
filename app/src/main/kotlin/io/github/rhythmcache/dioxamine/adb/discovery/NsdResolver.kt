package io.github.rhythmcache.dioxamine.adb.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Shared mDNS resolver coordinating [NsdManager.resolveService] requests.
 *
 * Background:
 * - On Android < 34 (API 31-33 and below): Only ONE resolveService request can be active
 *   at a time per client connection; concurrent calls immediately fail with
 *   [NsdManager.FAILURE_ALREADY_ACTIVE] (code 3).
 *   Therefore, requests on API < 34 are serialized through a strict FIFO queue.
 * - On Android 34+ (UPSIDE_DOWN_CAKE+): MdnsDiscoveryManager supports concurrent resolves,
 *   so requests execute immediately without serialization.
 * - In all versions, if code 3 is encountered (e.g. due to race conditions or daemon busy state),
 *   the request is retried up to [MAX_RETRIES] times with a [RETRY_DELAY_MS] backoff.
 * - A fresh [NsdManager.ResolveListener] instance is used per resolution attempt.
 */
object NsdResolver {

    private const val TAG = "NsdResolver"
    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 250L

    private class ResolveRequest(
        val nsdManager: NsdManager,
        val serviceInfo: NsdServiceInfo,
        val onResolved: (NsdServiceInfo) -> Unit,
        val onFailed: (Int) -> Unit,
        var retryCount: Int = 0
    )

    private val queue = ConcurrentLinkedQueue<ResolveRequest>()
    private val queueLock = Any()
    @Volatile
    private var isResolving = false
    private val scope = CoroutineScope(Dispatchers.IO)

    fun resolve(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit,
        onFailed: (Int) -> Unit
    ) {
        val request = ResolveRequest(nsdManager, serviceInfo, onResolved, onFailed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            executeResolve(request)
        } else {
            synchronized(queueLock) {
                queue.add(request)
            }
            processQueue()
        }
    }

    private fun processQueue() {
        val request = synchronized(queueLock) {
            if (isResolving || queue.isEmpty()) return
            isResolving = true
            queue.poll()
        } ?: return

        executeResolve(request)
    }

    private fun executeResolve(request: ResolveRequest) {
        val serviceName = request.serviceInfo.serviceName ?: "unknown"
        AppLogger.i(TAG, "Resolving service: $serviceName (try ${request.retryCount + 1}/$MAX_RETRIES, API ${Build.VERSION.SDK_INT})")

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                val name = serviceInfo.serviceName ?: serviceName
                AppLogger.w(TAG, "Resolve failed for $name: code=$errorCode (attempt ${request.retryCount + 1}/$MAX_RETRIES)")

                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && request.retryCount < MAX_RETRIES) {
                    request.retryCount++
                    AppLogger.i(TAG, "Retrying resolve for $name in ${RETRY_DELAY_MS}ms (retry ${request.retryCount}/$MAX_RETRIES)...")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        synchronized(queueLock) {
                            isResolving = false
                        }
                        processQueue()
                    }
                    scope.launch {
                        delay(RETRY_DELAY_MS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            executeResolve(request)
                        } else {
                            synchronized(queueLock) {
                                queue.add(request)
                            }
                            processQueue()
                        }
                    }
                    return
                }

                AppLogger.e(TAG, "Terminal resolve failure for $name: code=$errorCode (after ${request.retryCount} retries)")
                try {
                    request.onFailed(errorCode)
                } finally {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        synchronized(queueLock) { isResolving = false }
                        processQueue()
                    }
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: serviceName
                AppLogger.i(TAG, "Service successfully resolved: $name")
                try {
                    request.onResolved(serviceInfo)
                } finally {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        synchronized(queueLock) { isResolving = false }
                        processQueue()
                    }
                }
            }
        }

        try {
            @Suppress("DEPRECATION")
            request.nsdManager.resolveService(request.serviceInfo, resolveListener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception calling resolveService for $serviceName", e)
            try {
                request.onFailed(NsdManager.FAILURE_INTERNAL_ERROR)
            } finally {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    synchronized(queueLock) { isResolving = false }
                    processQueue()
                }
            }
        }
    }
}
