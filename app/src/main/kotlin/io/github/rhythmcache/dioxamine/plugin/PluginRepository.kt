package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PluginRepository(
    private val context: Context,
    scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "PluginRepository"
    }

    private val installer = PluginInstaller(context)
    private val _installedPlugins = MutableStateFlow<List<PluginManifest>>(emptyList())
    val installedPlugins: StateFlow<List<PluginManifest>> = _installedPlugins.asStateFlow()

    init {
        scope.launch {
            refresh()
        }
    }

    suspend fun refresh() =
        withContext(Dispatchers.IO) {
            val pluginsDir = File(context.filesDir, "plugins")
            if (!pluginsDir.exists() || !pluginsDir.isDirectory) {
                _installedPlugins.value = emptyList()
                return@withContext
            }

            val dirs = pluginsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val validManifests = mutableListOf<PluginManifest>()

            for (dir in dirs) {
                val manifestFile = File(dir, "plugin.json")
                if (!manifestFile.exists() || !manifestFile.isFile) {
                    Log.w(TAG, "Missing plugin.json in directory: ${dir.name}")
                    continue
                }

                runCatching {
                    manifestFile.readText()
                }.onSuccess { json ->
                    parseManifest(json).fold(
                        onSuccess = { manifest ->
                            if (dir.name == manifest.id) {
                                validManifests.add(manifest)
                            } else {
                                Log.w(TAG, "Directory name '${dir.name}' does not match manifest ID '${manifest.id}'")
                            }
                        },
                        onFailure = { err ->
                            Log.w(TAG, "Invalid plugin.json manifest in ${dir.name}: ${err.message}")
                        },
                    )
                }.onFailure { err ->
                    Log.w(TAG, "Failed to read plugin.json in ${dir.name}: ${err.message}")
                }
            }

            _installedPlugins.value = validManifests.distinctBy { it.id }.sortedBy { it.name }
        }

    suspend fun install(zipUri: Uri): PluginInstallResult {
        val result = installer.installFromZip(zipUri)
        if (result is PluginInstallResult.Installed || result is PluginInstallResult.Updated) {
            refresh()
        }
        return result
    }

    suspend fun uninstall(pluginId: String): Boolean {
        val deleted = installer.uninstall(pluginId)
        if (deleted) {
            refresh()
        }
        return deleted
    }

    fun pluginDir(pluginId: String): File = File(File(context.filesDir, "plugins"), pluginId)
}
