package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

sealed class PluginInstallResult {
    data class Installed(val manifest: PluginManifest) : PluginInstallResult()
    data class Updated(val old: PluginManifest, val new: PluginManifest) : PluginInstallResult()
    data class UpdateRejected(val installed: PluginManifest, val attempted: PluginManifest) : PluginInstallResult()
    data class Error(val message: String) : PluginInstallResult()
}

class PluginInstaller(private val context: Context) {

    suspend fun installFromZip(zipUri: Uri): PluginInstallResult =
        withContext(Dispatchers.IO) {
            val pluginsDir = File(context.filesDir, "plugins").apply { mkdirs() }
            val stagingDir = File(context.cacheDir, "plugin_staging/${UUID.randomUUID()}").apply { mkdirs() }

            try {
                val inputStream = context.contentResolver.openInputStream(zipUri)
                    ?: return@withContext PluginInstallResult.Error("Could not open file")

                val maxSizeBytes = 50L * 1024L * 1024L // 50MB total limit
                val maxEntries = 500
                var totalBytes = 0L
                var entryCount = 0

                val canonicalStagingPath = stagingDir.canonicalPath + File.separator

                inputStream.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var zipEntry = zis.nextEntry
                        while (zipEntry != null) {
                            entryCount++
                            if (entryCount > maxEntries) {
                                return@withContext PluginInstallResult.Error("Plugin exceeds size limits")
                            }

                            val outputFile = File(stagingDir, zipEntry.name)
                            val canonicalOutputPath = outputFile.canonicalPath

                            // CRITICAL SECURITY CHECK: Ensure canonical path stays strictly inside staging directory
                            if (!canonicalOutputPath.startsWith(canonicalStagingPath) && canonicalOutputPath != stagingDir.canonicalPath) {
                                return@withContext PluginInstallResult.Error("Zip contains invalid path: ${zipEntry.name}")
                            }

                            if (zipEntry.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                outputFile.parentFile?.mkdirs()
                                outputFile.outputStream().use { fos ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (zis.read(buffer).also { len = it } > 0) {
                                        totalBytes += len
                                        if (totalBytes > maxSizeBytes) {
                                            return@withContext PluginInstallResult.Error("Plugin exceeds size limits")
                                        }
                                        fos.write(buffer, 0, len)
                                    }
                                }
                            }
                            zis.closeEntry()
                            zipEntry = zis.nextEntry
                        }
                    }
                }

                // Look for plugin.json at the STAGING ROOT ONLY (not subdirectories)
                val manifestFile = File(stagingDir, "plugin.json")
                if (!manifestFile.exists() || !manifestFile.isFile) {
                    return@withContext PluginInstallResult.Error("plugin.json not found at zip root")
                }

                val manifestJson = runCatching { manifestFile.readText() }.getOrElse {
                    return@withContext PluginInstallResult.Error("Failed to read plugin.json: ${it.message}")
                }

                val parseResult = parseManifest(manifestJson)
                if (parseResult.isFailure) {
                    val errorMsg = parseResult.exceptionOrNull()?.message ?: "Invalid plugin.json manifest"
                    return@withContext PluginInstallResult.Error(errorMsg)
                }

                val manifest = parseResult.getOrThrow()

                // Verify entry file exists after extraction
                val entryFile = File(stagingDir, manifest.entry)
                if (!entryFile.exists() || !entryFile.isFile) {
                    return@withContext PluginInstallResult.Error("Entry file '${manifest.entry}' not found in zip")
                }

                val targetDir = File(pluginsDir, manifest.id)
                val existingManifestFile = File(targetDir, "plugin.json")

                if (!existingManifestFile.exists() || !existingManifestFile.isFile) {
                    // New installation
                    if (!moveDir(stagingDir, targetDir)) {
                        return@withContext PluginInstallResult.Error("Failed to move plugin files to destination")
                    }
                    PluginInstallResult.Installed(manifest)
                } else {
                    // Updating existing plugin
                    val oldJson = runCatching { existingManifestFile.readText() }.getOrNull()
                    val oldManifest = oldJson?.let { parseManifest(it).getOrNull() }

                    if (oldManifest != null && manifest.versionCode <= oldManifest.versionCode) {
                        return@withContext PluginInstallResult.UpdateRejected(oldManifest, manifest)
                    }

                    // Safe backup-and-swap strategy to prevent destroying working installation on move failure
                    val backupDir = File(pluginsDir, "${manifest.id}_backup_${UUID.randomUUID()}")
                    val backedUp = moveDir(targetDir, backupDir)
                    val installedNew = moveDir(stagingDir, targetDir)

                    if (installedNew) {
                        if (backedUp) backupDir.deleteRecursively()
                        if (oldManifest == null) {
                            PluginInstallResult.Installed(manifest)
                        } else {
                            PluginInstallResult.Updated(oldManifest, manifest)
                        }
                    } else {
                        // Restore backup if installation failed
                        if (backedUp && backupDir.exists()) {
                            targetDir.deleteRecursively()
                            moveDir(backupDir, targetDir)
                        }
                        PluginInstallResult.Error("Failed to move plugin files to destination")
                    }
                }
            } finally {
                // Guarantee cleanup of staging directory on success, failure, or exception
                stagingDir.deleteRecursively()
            }
        }

    suspend fun uninstall(pluginId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (pluginId.contains("/") || pluginId.contains("\\") || pluginId.contains("..")) {
                return@withContext false
            }
            val targetDir = File(File(context.filesDir, "plugins"), pluginId)
            if (targetDir.exists() && targetDir.isDirectory) {
                targetDir.deleteRecursively()
            } else {
                false
            }
        }

    private fun moveDir(sourceDir: File, destDir: File): Boolean {
        destDir.parentFile?.mkdirs()
        if (sourceDir.renameTo(destDir)) {
            return true
        }
        destDir.mkdirs()
        val copied = sourceDir.copyRecursively(destDir, overwrite = true)
        if (copied) {
            sourceDir.deleteRecursively()
        }
        return copied
    }
}
