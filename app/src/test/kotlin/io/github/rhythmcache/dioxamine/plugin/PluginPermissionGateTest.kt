package io.github.rhythmcache.dioxamine.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PluginPermissionGateTest {

    @Test
    fun testUndeclaredPermissionRejectedImmediately() = runTest {
        val gate = PluginPermissionGate(store = null)
        val declared = listOf(PluginPermission.SHELL)
        val result = gate.checkPermission(
            pluginId = "test.plugin",
            pluginName = "Test Plugin",
            declaredPermissions = declared,
            required = PluginPermission.NETWORK,
        )
        assertFalse(result)
        assertNull(gate.pendingRequest.value)
    }

    @Test
    fun testAllowSessionGrantsForSession() = runTest {
        val gate = PluginPermissionGate(store = null)
        val declared = listOf(PluginPermission.SHELL)

        val checkDeferred = async {
            gate.checkPermission(
                pluginId = "test.plugin",
                pluginName = "Test Plugin",
                declaredPermissions = declared,
                required = PluginPermission.SHELL,
            )
        }

        val request = gate.pendingRequest.filterNotNull().first()
        assertEquals("test.plugin", request.pluginId)
        assertEquals(PluginPermission.SHELL, request.permission)

        request.onDecision(PermissionDecision.ALLOW_SESSION)
        assertTrue(checkDeferred.await())

        assertTrue(gate.isSessionGranted("test.plugin", PluginPermission.SHELL))
        assertFalse(gate.isSessionDenied("test.plugin", PluginPermission.SHELL))

        // Subsequent check in the same session succeeds immediately without prompting
        val secondResult = gate.checkPermission(
            pluginId = "test.plugin",
            pluginName = "Test Plugin",
            declaredPermissions = declared,
            required = PluginPermission.SHELL,
        )
        assertTrue(secondResult)
        assertNull(gate.pendingRequest.value)
    }

    @Test
    fun testDenySessionDeniesForSession() = runTest {
        val gate = PluginPermissionGate(store = null)
        val declared = listOf(PluginPermission.PUSH)

        val checkDeferred = async {
            gate.checkPermission(
                pluginId = "test.plugin",
                pluginName = "Test Plugin",
                declaredPermissions = declared,
                required = PluginPermission.PUSH,
            )
        }

        val request = gate.pendingRequest.filterNotNull().first()
        request.onDecision(PermissionDecision.DENY_SESSION)
        assertFalse(checkDeferred.await())

        assertFalse(gate.isSessionGranted("test.plugin", PluginPermission.PUSH))
        assertTrue(gate.isSessionDenied("test.plugin", PluginPermission.PUSH))

        // Subsequent check in the same session is denied immediately
        val secondResult = gate.checkPermission(
            pluginId = "test.plugin",
            pluginName = "Test Plugin",
            declaredPermissions = declared,
            required = PluginPermission.PUSH,
        )
        assertFalse(secondResult)
        assertNull(gate.pendingRequest.value)
    }

    @Test
    fun testClearSessionPermissionResetsState() = runTest {
        val gate = PluginPermissionGate(store = null)
        val declared = listOf(PluginPermission.INSTALL)

        val checkDeferred = async {
            gate.checkPermission(
                pluginId = "test.plugin",
                pluginName = "Test Plugin",
                declaredPermissions = declared,
                required = PluginPermission.INSTALL,
            )
        }

        val request = gate.pendingRequest.filterNotNull().first()
        request.onDecision(PermissionDecision.ALLOW_SESSION)
        assertTrue(checkDeferred.await())
        assertTrue(gate.isSessionGranted("test.plugin", PluginPermission.INSTALL))

        gate.clearSessionPermission("test.plugin", PluginPermission.INSTALL)
        assertFalse(gate.isSessionGranted("test.plugin", PluginPermission.INSTALL))
    }

    @Test
    fun testClearSessionResetsAllPermissionsForPlugin() = runTest {
        val gate = PluginPermissionGate(store = null)
        val declared = listOf(PluginPermission.SHELL, PluginPermission.NETWORK)

        val check1 = async {
            gate.checkPermission("test.plugin", "Test Plugin", declared, PluginPermission.SHELL)
        }
        val req1 = gate.pendingRequest.filterNotNull().first()
        req1.onDecision(PermissionDecision.ALLOW_SESSION)
        assertTrue(check1.await())

        val check2 = async {
            gate.checkPermission("test.plugin", "Test Plugin", declared, PluginPermission.NETWORK)
        }
        val req2 = gate.pendingRequest.filterNotNull().first()
        req2.onDecision(PermissionDecision.DENY_SESSION)
        assertFalse(check2.await())

        assertTrue(gate.isSessionGranted("test.plugin", PluginPermission.SHELL))
        assertTrue(gate.isSessionDenied("test.plugin", PluginPermission.NETWORK))

        gate.clearSession("test.plugin")

        assertFalse(gate.isSessionGranted("test.plugin", PluginPermission.SHELL))
        assertFalse(gate.isSessionDenied("test.plugin", PluginPermission.NETWORK))
    }

    @Test
    fun testClearAllSessionsResetsAllPlugins() = runTest {
        val gate = PluginPermissionGate(store = null)

        val check1 = async {
            gate.checkPermission("plugin.a", "Plugin A", listOf(PluginPermission.SHELL), PluginPermission.SHELL)
        }
        val req1 = gate.pendingRequest.filterNotNull().first()
        req1.onDecision(PermissionDecision.ALLOW_SESSION)
        assertTrue(check1.await())

        val check2 = async {
            gate.checkPermission("plugin.b", "Plugin B", listOf(PluginPermission.NETWORK), PluginPermission.NETWORK)
        }
        val req2 = gate.pendingRequest.filterNotNull().first()
        req2.onDecision(PermissionDecision.ALLOW_SESSION)
        assertTrue(check2.await())

        assertTrue(gate.isSessionGranted("plugin.a", PluginPermission.SHELL))
        assertTrue(gate.isSessionGranted("plugin.b", PluginPermission.NETWORK))

        gate.clearAllSessions()

        assertFalse(gate.isSessionGranted("plugin.a", PluginPermission.SHELL))
        assertFalse(gate.isSessionGranted("plugin.b", PluginPermission.NETWORK))
    }
}
