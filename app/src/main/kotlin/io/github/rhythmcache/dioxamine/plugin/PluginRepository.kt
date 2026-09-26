package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.net.Uri
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

    private val _availableUpdates = MutableStateFlow<Map<String, PluginUpdateInfo>>(emptyMap())
    val availableUpdates: StateFlow<Map<String, PluginUpdateInfo>> = _availableUpdates.asStateFlow()
    private val checkUpdatesMutex = Mutex()

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
                _availableUpdates.value = emptyMap()
                return@withContext
            }

            val dirs = pluginsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val validManifests = mutableListOf<PluginManifest>()

            for (dir in dirs) {
                val manifestFile = File(dir, "plugin.json")
                if (!manifestFile.exists() || !manifestFile.isFile) {
                    AppLogger.w(TAG, "Missing plugin.json in directory: ${dir.name}")
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
                                AppLogger.w(TAG, "Directory name '${dir.name}' does not match manifest ID '${manifest.id}'")
                            }
                        },
                        onFailure = { err ->
                            AppLogger.w(TAG, "Invalid plugin.json manifest in ${dir.name}: ${err.message}")
                        },
                    )
                }.onFailure { err ->
                    AppLogger.w(TAG, "Failed to read plugin.json in ${dir.name}: ${err.message}")
                }
            }

            val plugins = validManifests.distinctBy { it.id }.sortedBy { it.name }
            _installedPlugins.value = plugins

            _availableUpdates.update { currentUpdates ->
                val manifestMap = plugins.associateBy { it.id }
                currentUpdates.filter { (id, update) ->
                    val installed = manifestMap[id]
                    installed != null && update.versionCode > installed.versionCode
                }
            }
        }

    suspend fun checkForUpdates() =
        withContext(Dispatchers.IO) {
            checkUpdatesMutex.withLock {
                val plugins = _installedPlugins.value
                val checkable = plugins.filter { !it.updateJson.isNullOrBlank() }
                if (checkable.isEmpty()) {
                    _availableUpdates.value = emptyMap()
                    return@withLock
                }

                val updates =
                    coroutineScope {
                        checkable.map { manifest ->
                            async {
                                val updateJsonUrl = manifest.updateJson ?: return@async null
                                val updateInfo = PluginUpdateChecker.fetchUpdate(updateJsonUrl)
                                if (updateInfo != null && updateInfo.id == manifest.id && updateInfo.versionCode > manifest.versionCode) {
                                    manifest.id to updateInfo
                                } else {
                                    null
                                }
                            }
                        }.awaitAll().filterNotNull().toMap()
                    }

                _availableUpdates.update { currentUpdates ->
                    val latestInstalled = _installedPlugins.value.associateBy { it.id }
                    (currentUpdates + updates).filter { (id, update) ->
                        val installed = latestInstalled[id]
                        installed != null && update.versionCode > installed.versionCode
                    }
                }
            }
        }

    suspend fun downloadAndInstallUpdate(
        manifest: PluginManifest,
        updateInfo: PluginUpdateInfo,
    ): PluginInstallResult =
        withContext(Dispatchers.IO) {
            val tempZip = File(context.cacheDir, "plugin_update_${manifest.id}_${UUID.randomUUID()}.zip")
            try {
                val downloaded = PluginUpdateChecker.downloadFile(updateInfo.download, tempZip)
                if (!downloaded || !tempZip.exists() || tempZip.length() == 0L) {
                    return@withContext PluginInstallResult.Error(
                        context.getString(R.string.plugin_msg_download_failed),
                    )
                }
                val result = installer.installFromZipFile(tempZip)
                if (result is PluginInstallResult.Installed || result is PluginInstallResult.Updated) {
                    refresh()
                }
                result
            } finally {
                if (tempZip.exists()) {
                    tempZip.delete()
                }
            }
        }

    suspend fun install(zipUri: Uri): PluginInstallResult {
        val result = installer.installFromZip(zipUri)
        if (result is PluginInstallResult.Installed || result is PluginInstallResult.Updated) {
            refresh()
        }
        return result
    }

    suspend fun installFromZipFile(file: File): PluginInstallResult {
        val result = installer.installFromZipFile(file)
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
