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

enum class PermissionPolicy {
    ASK,           // Current behavior: prompt user each session
    ALWAYS_ALLOW,  // Auto-grant without prompting
    ALWAYS_DENY;   // Auto-deny without prompting

    companion object {
        fun fromString(s: String): PermissionPolicy =
            when (s.uppercase()) {
                "ALWAYS_ALLOW" -> ALWAYS_ALLOW
                "ALWAYS_DENY" -> ALWAYS_DENY
                else -> ASK
            }
    }
}

class PluginPermissionStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("plugin_permissions", android.content.Context.MODE_PRIVATE)

    /** Key format: "policy:{pluginId}:{permission}" */
    private fun policyKey(pluginId: String, permission: PluginPermission): String =
        "policy:${pluginId}:${permission.name}"

    fun getPolicy(pluginId: String, permission: PluginPermission): PermissionPolicy {
        val raw = prefs.getString(policyKey(pluginId, permission), null) ?: return PermissionPolicy.ASK
        return PermissionPolicy.fromString(raw)
    }

    fun setPolicy(pluginId: String, permission: PluginPermission, policy: PermissionPolicy) {
        prefs.edit().putString(policyKey(pluginId, permission), policy.name).apply()
    }

    /** Get all policies for a specific plugin. Returns only explicitly set policies (non-ASK). */
    fun getPoliciesForPlugin(pluginId: String): Map<PluginPermission, PermissionPolicy> {
        val result = mutableMapOf<PluginPermission, PermissionPolicy>()
        PluginPermission.entries.forEach { perm ->
            val policy = getPolicy(pluginId, perm)
            if (policy != PermissionPolicy.ASK) {
                result[perm] = policy
            }
        }
        return result
    }

    /** Reset all policies for a plugin back to ASK */
    fun resetPlugin(pluginId: String) {
        val editor = prefs.edit()
        PluginPermission.entries.forEach { perm ->
            editor.remove(policyKey(pluginId, perm))
        }
        editor.apply()
    }

    /** Reset all policies for all plugins */
    fun resetAll() {
        prefs.edit().clear().apply()
    }
}

data class PendingPermissionRequest(
    val pluginId: String,
    val pluginName: String,
    val permission: PluginPermission,
    val onResult: (granted: Boolean) -> Unit,
)

class PluginPermissionGate(
    private val store: PluginPermissionStore? = null,
) {
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

        // 1.5 Check persistent policy
        store?.let { s ->
            when (s.getPolicy(pluginId, required)) {
                PermissionPolicy.ALWAYS_ALLOW -> return true
                PermissionPolicy.ALWAYS_DENY -> return false
                PermissionPolicy.ASK -> { /* fall through to session check */ }
            }
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
