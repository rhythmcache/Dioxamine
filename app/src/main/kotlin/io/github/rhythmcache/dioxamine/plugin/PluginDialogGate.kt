package io.github.rhythmcache.dioxamine.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PluginDialogRequest(
    val pluginId: String,
    val pluginName: String,
    val title: String,
    val message: String,
    val buttons: List<String>,
    val onResult: (buttonIndex: Int) -> Unit,
)

class PluginDialogGate {
    private val _pendingRequest = MutableStateFlow<PluginDialogRequest?>(null)
    val pendingRequest: StateFlow<PluginDialogRequest?> = _pendingRequest.asStateFlow()

    private val mutex = Mutex()

    suspend fun showDialog(
        pluginId: String,
        pluginName: String,
        title: String,
        message: String,
        buttons: List<String>,
    ): Int =
        mutex.withLock {
            val deferred = CompletableDeferred<Int>()
            val request =
                PluginDialogRequest(
                    pluginId = pluginId,
                    pluginName = pluginName,
                    title = title,
                    message = message,
                    buttons = buttons,
                    onResult = { buttonIndex ->
                        if (!deferred.isCompleted) {
                            deferred.complete(buttonIndex)
                        }
                    },
                )

            _pendingRequest.value = request

            try {
                deferred.await()
            } finally {
                _pendingRequest.value = null
            }
        }
}
