package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

sealed class PendingSafPickerRequest {
    data class OpenDocument(
        val onResult: (Uri?) -> Unit,
    ) : PendingSafPickerRequest()

    data class CreateDocument(
        val suggestedName: String,
        val onResult: (Uri?) -> Unit,
    ) : PendingSafPickerRequest()
}

class PluginSafBridge(private val context: Context) {
    private val _pendingRequest = MutableStateFlow<PendingSafPickerRequest?>(null)
    val pendingRequest: StateFlow<PendingSafPickerRequest?> = _pendingRequest.asStateFlow()

    private val pickerMutex = Mutex()
    private val resolvedUris = LinkedHashMap<String, Uri>()

    companion object {
        private const val MAX_PENDING_URIS = 20
    }

    suspend fun requestOpenDocument(): String =
        pickerMutex.withLock {
            val deferred = CompletableDeferred<Uri?>()
            _pendingRequest.value = PendingSafPickerRequest.OpenDocument { uri ->
                if (!deferred.isCompleted) {
                    deferred.complete(uri)
                }
            }

            val uri =
                try {
                    deferred.await()
                } finally {
                    _pendingRequest.value = null
                }

            if (uri == null) {
                throw IllegalArgumentException("User cancelled file selection")
            }

            storeUri(uri)
        }

    suspend fun requestCreateDocument(suggestedName: String = "file"): String =
        pickerMutex.withLock {
            val deferred = CompletableDeferred<Uri?>()
            _pendingRequest.value = PendingSafPickerRequest.CreateDocument(suggestedName) { uri ->
                if (!deferred.isCompleted) {
                    deferred.complete(uri)
                }
            }

            val uri =
                try {
                    deferred.await()
                } finally {
                    _pendingRequest.value = null
                }

            if (uri == null) {
                throw IllegalArgumentException("User cancelled file creation")
            }

            storeUri(uri)
        }

    fun resolveInputStream(requestId: String): InputStream? {
        val uri =
            synchronized(resolvedUris) {
                resolvedUris.remove(requestId)
            } ?: return null
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    fun resolveOutputStream(requestId: String): OutputStream? {
        val uri =
            synchronized(resolvedUris) {
                resolvedUris.remove(requestId)
            } ?: return null
        return runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
    }

    private fun storeUri(uri: Uri): String {
        val requestId = UUID.randomUUID().toString()
        synchronized(resolvedUris) {
            while (resolvedUris.size >= MAX_PENDING_URIS) {
                val oldest = resolvedUris.keys.iterator().next()
                resolvedUris.remove(oldest)
            }
            resolvedUris[requestId] = uri
        }
        return requestId
    }
}

@Composable
fun PluginSafLauncherHost(safBridge: PluginSafBridge) {
    val pendingRequest by safBridge.pendingRequest.collectAsState()

    val openLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            (pendingRequest as? PendingSafPickerRequest.OpenDocument)?.onResult?.invoke(uri)
        }

    val createLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("*/*"),
        ) { uri ->
            (pendingRequest as? PendingSafPickerRequest.CreateDocument)?.onResult?.invoke(uri)
        }

    LaunchedEffect(pendingRequest) {
        when (val req = pendingRequest) {
            is PendingSafPickerRequest.OpenDocument -> {
                openLauncher.launch(arrayOf("*/*"))
            }

            is PendingSafPickerRequest.CreateDocument -> {
                createLauncher.launch(req.suggestedName)
            }

            null -> {}
        }
    }
}
