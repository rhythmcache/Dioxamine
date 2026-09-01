package io.github.rhythmcache.dioxamine.scrcpy

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScrcpyRecordingsViewer(
    modifier: Modifier = Modifier,
    isRecording: Boolean = false
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    var recordings by remember { mutableStateOf<List<RecordingInfo>>(emptyList()) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var fileToExport by remember { mutableStateOf<File?>(null) }

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy \u2022 h:mm a", Locale.getDefault()) }

    LaunchedEffect(refreshKey, isRecording) {
        recordings = withContext(Dispatchers.IO) {
            RecordingsManager.listRecordings(context)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri: Uri? ->
        if (uri != null && fileToExport != null) {
            val success = RecordingsManager.exportRecording(context, fileToExport!!, uri)
            if (success) {
                Toast.makeText(context, context.getString(R.string.scrcpy_recording_exported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.scrcpy_recording_export_failed), Toast.LENGTH_SHORT).show()
            }
            fileToExport = null
        }
    }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(R.string.scrcpy_recording_delete_confirm)) },
            text = { Text(stringResource(R.string.scrcpy_recording_delete_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val success = RecordingsManager.deleteRecording(fileToDelete!!)
                        if (success) {
                            Toast.makeText(context, context.getString(R.string.scrcpy_recording_deleted), Toast.LENGTH_SHORT).show()
                        }
                        fileToDelete = null
                        refreshKey++
                    }
                ) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (recordings.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.VideoFile,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.scrcpy_recording_empty),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.scrcpy_recording_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(recordings, key = { it.file.absolutePath }) { recording ->
                RecordingCard(
                    recording = recording,
                    dateFormat = dateFormat,
                    onOpen = { RecordingsManager.openRecording(context, it.file) },
                    onExport = {
                        fileToExport = it.file
                        exportLauncher.launch(it.name)
                    },
                    onDelete = { fileToDelete = it.file }
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: RecordingInfo,
    dateFormat: SimpleDateFormat,
    onOpen: (RecordingInfo) -> Unit,
    onExport: (RecordingInfo) -> Unit,
    onDelete: (RecordingInfo) -> Unit
) {
    var thumbnail by remember(recording.file) { mutableStateOf<Bitmap?>(null) }
    var thumbnailLoaded by remember(recording.file) { mutableStateOf(false) }

    LaunchedEffect(recording.file) {
        thumbnail = withContext(Dispatchers.IO) {
            RecordingsManager.getRecordingThumbnail(recording.file)
        }
        thumbnailLoaded = true
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(recording) },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!thumbnailLoaded) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Filled.VideoFile,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // Duration badge
                if (recording.durationMs > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = RecordingsManager.formatDuration(recording.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.name.removeSuffix(".mp4"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${RecordingsManager.formatFileSize(recording.sizeBytes)} \u2022 ${dateFormat.format(Date(recording.createdAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { onExport(recording) }) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.btn_export),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onDelete(recording) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.btn_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
