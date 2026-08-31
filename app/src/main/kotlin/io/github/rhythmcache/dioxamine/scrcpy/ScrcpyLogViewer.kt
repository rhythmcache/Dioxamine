package io.github.rhythmcache.dioxamine.scrcpy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.rhythmcache.dioxamine.core.DeviceInfoCollector
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.core.DeviceConnection
import io.github.rhythmcache.dioxamine.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG_CLIENT = "SCRCPY_CLIENT"
private const val TAG_SERVER = "SCRCPY_SERVER"

private enum class LogChip { CLIENT, SERVER }

@Composable
fun ScrcpyLogViewer(
    modifier: Modifier = Modifier,
    activeConn: DeviceConnection?,
    config: ScrcpyConfig = ScrcpyConfig()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jetBrainsMonoFont = remember(context) {
        runCatching {
            FontFamily(Typeface(android.graphics.Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")))
        }.getOrDefault(FontFamily.Monospace)
    }

    var selectedChip by remember { mutableStateOf(LogChip.CLIENT) }
    var entries by remember { mutableStateOf<List<AppLogger.Entry>>(emptyList()) }
    var isExporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportSuccess by remember { mutableStateOf(false) }
    var copySuccess by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val updateSignal by AppLogger.updates.collectAsState(initial = 0L)

    LaunchedEffect(selectedChip, updateSignal) {
        val tag = if (selectedChip == LogChip.CLIENT) TAG_CLIENT else TAG_SERVER
        entries = AppLogger.getEntries(tag)
    }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            val atBottom = listState.firstVisibleItemIndex >= entries.size - 3
            if (atBottom) {
                listState.animateScrollToItem(entries.size - 1)
            }
        }
    }

    LaunchedEffect(copySuccess) {
        if (copySuccess) {
            kotlinx.coroutines.delay(1500)
            copySuccess = false
        }
    }

    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) {
            isExporting = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeLogsZipToUri(context, uri, activeConn, config)
                }
                exportSuccess = true
            } catch (e: Exception) {
                exportError = e.message ?: context.getString(R.string.scrcpy_logs_export_failed)
            } finally {
                isExporting = false
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedChip == LogChip.CLIENT,
                onClick = { selectedChip = LogChip.CLIENT },
                label = { Text(stringResource(R.string.scrcpy_logs_client)) }
            )
            FilterChip(
                selected = selectedChip == LogChip.SERVER,
                onClick = { selectedChip = LogChip.SERVER },
                label = { Text(stringResource(R.string.scrcpy_logs_server)) }
            )

            Spacer(Modifier.weight(1f))

            IconButton(onClick = {
                val tag = if (selectedChip == LogChip.CLIENT) TAG_CLIENT else TAG_SERVER
                val text = AppLogger.export(tag)
                val clip = ClipData.newPlainText("scrcpy_${tag.lowercase()}_logs", text)
                clipboardManager?.setPrimaryClip(clip)
                copySuccess = true
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_logs_to_clipboard))
            }

            IconButton(onClick = {
                AppLogger.clear(if (selectedChip == LogChip.CLIENT) TAG_CLIENT else TAG_SERVER)
            }) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_logs))
            }

            IconButton(
                enabled = !isExporting,
                onClick = {
                    isExporting = true
                    exportError = null
                    exportSuccess = false
                    val filename = "scrcpy_logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
                    createDocumentLauncher.launch(filename)
                }
            ) {
                if (isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.cd_export_logs_zip))
                }
            }
        }

        if (exportError != null) {
            Text(
                exportError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (exportSuccess) {
            Text(
                stringResource(R.string.scrcpy_logs_exported),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (copySuccess) {
            Text(
                "Copied to clipboard",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        HorizontalDivider()

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No logs yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(entries, key = { it.timestamp.toString() + it.message.hashCode() }) { entry ->
                    LogLine(entry, jetBrainsMonoFont)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: AppLogger.Entry, fontFamily: FontFamily) {
    val color = when (entry.level) {
        AppLogger.Level.ERROR -> Color(0xFFFF6E6E)
        AppLogger.Level.WARN -> Color(0xFFFFD166)
        AppLogger.Level.INFO -> Color(0xFFB0FFB0)
        AppLogger.Level.DEBUG -> Color(0xFF9E9E9E)
        AppLogger.Level.VERBOSE -> Color(0xFF757575)
    }
    Text(
        text = entry.formatted(),
        color = color,
        fontFamily = fontFamily,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

private fun writeLogsZipToUri(context: Context, uri: Uri, activeConn: DeviceConnection?, config: ScrcpyConfig) {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("client_logs.txt"))
            zos.write(AppLogger.export(TAG_CLIENT).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("server_logs.txt"))
            zos.write(AppLogger.export(TAG_SERVER).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("device_info.json"))
            zos.write(DeviceInfoCollector.collect(activeConn, config).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    } ?: throw Exception("Could not open output stream for selected location")
}
