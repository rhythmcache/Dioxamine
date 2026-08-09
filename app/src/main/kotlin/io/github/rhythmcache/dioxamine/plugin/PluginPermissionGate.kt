package io.github.rhythmcache.dioxamine.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PluginPermission {
    SHELL,
    PUSH,
    PULL,
    INSTALL,
    FORWARD,
    REVERSE;

    companion object {
        fun fromManifestString(s: String): PluginPermission? =
            when (s.lowercase().trim()) {
                "shell" -> SHELL
                "push" -> PUSH
                "pull" -> PULL
                "install" -> INSTALL
                "forward" -> FORWARD
                "reverse" -> REVERSE
                else -> null
            }
    }
}

data class PendingPermissionRequest(
    val pluginId: String,
    val pluginName: String,
    val permission: PluginPermission,
    val onResult: (granted: Boolean) -> Unit,
)

class PluginPermissionGate {
    private val _pendingRequest = MutableStateFlow<PendingPermissionRequest?>(null)
    val pendingRequest: StateFlow<PendingPermissionRequest?> = _pendingRequest.asStateFlow()

    private val gateMutex = Mutex()
    private val grantedMutex = Mutex()
    private val grantedPermissions = mutableMapOf<String, MutableSet<PluginPermission>>()

    suspend fun checkPermission(
        pluginId: String,
        pluginName: String,
        declaredPermissions: List<PluginPermission>,
        required: PluginPermission,
    ): Boolean {
        // 1. If required !in declaredPermissions -> return false immediately, no UI shown
        if (required !in declaredPermissions) {
            return false
        }

        // 2. If already granted this session -> return true immediately
        grantedMutex.withLock {
            if (grantedPermissions[pluginId]?.contains(required) == true) {
                return true
            }
        }

        // 3. Otherwise: serialize requests via gateMutex so concurrent checks do not overwrite _pendingRequest
        return gateMutex.withLock {
            // Re-check inside lock in case a previous queued request granted it
            grantedMutex.withLock {
                if (grantedPermissions[pluginId]?.contains(required) == true) {
                    return@withLock true
                }
            }

            val deferred = CompletableDeferred<Boolean>()

            val request =
                PendingPermissionRequest(
                    pluginId = pluginId,
                    pluginName = pluginName,
                    permission = required,
                    onResult = { granted ->
                        if (!deferred.isCompleted) {
                            deferred.complete(granted)
                        }
                    },
                )

            _pendingRequest.value = request

            val result =
                try {
                    deferred.await()
                } finally {
                    _pendingRequest.value = null
                }

            if (result) {
                grantedMutex.withLock {
                    grantedPermissions.getOrPut(pluginId) { mutableSetOf() }.add(required)
                }
            }

            result
        }
    }
}
