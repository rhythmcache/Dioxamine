package io.github.rhythmcache.dioxamine.fastboot

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.fastboot.FastbootFileUtils.formatBytes
import io.github.rhythmcache.dioxamine.fastboot.FastbootFileUtils.resolveNameAndSize
import io.github.rhythmcache.dioxamine.fastboot.FastbootFileUtils.stripExtension

private data class PickedFile(val uri: Uri, val displayName: String, val size: Long)

@Composable
private fun OperationFeedback(vm: FastbootViewModel) {
    if (vm.isBusy) {
        Spacer(modifier = Modifier.height(24.dp))
        val progress = vm.currentProgress
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = vm.currentOperation ?: stringResource(R.string.fastboot_working_placeholder), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (progress != null && progress.total > 0) {
                    val percent = ((progress.current.toDouble() / progress.total.toDouble()) * 100).toInt()
                    LinearProgressIndicator(
                        progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "$percent% (${formatBytes(progress.current)} / ${formatBytes(progress.total)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    } else if (vm.lastOperationStatus !is OperationStatus.Idle) {
        Spacer(modifier = Modifier.height(24.dp))
        OperationStatusCard(status = vm.lastOperationStatus, onDismiss = { vm.clearOperationStatus() })
    }
}


@Composable
fun FlashImageScreen(vm: FastbootViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var partitionName by remember { mutableStateOf("") }
    var showPartitionDialog by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = resolveNameAndSize(context, uri)
            picked = PickedFile(uri, name, size)
            partitionName = stripExtension(name)
            showPartitionDialog = true
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FastbootDetailHeader(title = stringResource(R.string.fastboot_flash_title), onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.fastboot_flash_desc),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        FastbootActionTile(
            icon = Icons.Filled.FileUpload,
            title = stringResource(R.string.fastboot_choose_image),
            description = picked?.displayName ?: stringResource(R.string.fastboot_no_file_selected),
            enabled = !vm.isBusy,
            onClick = { pickerLauncher.launch(arrayOf("*/*")) },
        )

        OperationFeedback(vm)
    }

    if (showPartitionDialog && picked != null) {
        AlertDialog(
            onDismissRequest = { showPartitionDialog = false },
            title = { Text(stringResource(R.string.fastboot_dialog_flash_partition_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.fastboot_dialog_flash_partition_msg, picked!!.displayName),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = partitionName,
                        onValueChange = { partitionName = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(stringResource(R.string.fastboot_partition_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = partitionName.isNotBlank(),
                    onClick = {
                        showPartitionDialog = false
                        val file = picked!!
                        val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
                        if (pfd != null) {
                            val job = vm.flashImage(
                                partition = partitionName.trim(),
                                fd = pfd.fileDescriptor,
                                size = file.size,
                                displayName = file.displayName,
                            )
                            job.invokeOnCompletion { runCatching { pfd.close() } }
                        }
                    }
                ) { Text(stringResource(R.string.fastboot_btn_flash)) }
            },
            dismissButton = {
                TextButton(onClick = { showPartitionDialog = false }) { Text(stringResource(R.string.fastboot_btn_cancel)) }
            }
        )
    }
}

@Composable
fun BootImageScreen(vm: FastbootViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var displayName by remember { mutableStateOf<String?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = resolveNameAndSize(context, uri)
            displayName = name
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val job = vm.bootImage(fd = pfd.fileDescriptor, size = size, displayName = name)
                job.invokeOnCompletion { runCatching { pfd.close() } }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FastbootDetailHeader(title = stringResource(R.string.fastboot_boot_title), onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.fastboot_boot_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))
        FastbootActionTile(
            icon = Icons.Filled.PlayCircle,
            title = stringResource(R.string.fastboot_choose_image_and_boot),
            description = displayName ?: stringResource(R.string.fastboot_no_file_selected),
            enabled = !vm.isBusy,
            onClick = { pickerLauncher.launch(arrayOf("*/*")) },
        )
        OperationFeedback(vm)
    }
}
