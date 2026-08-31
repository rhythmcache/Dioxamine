package io.github.rhythmcache.dioxamine.fastboot

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R

@Composable
fun ShellInputBar(vm: FastbootViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var forceRawPath by remember { mutableStateOf(false) }

    val parsed = remember(text) { FastbootCommandParser.parse(text) }
    LaunchedEffect(text) { forceRawPath = false }

    // flash/boot: pick a source file to read from.
    val openPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pending = pendingOf(parsed) ?: return@rememberLauncherForActivityResult
        val (name, size) = FastbootFileUtils.resolveNameAndSize(context, uri)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@rememberLauncherForActivityResult
        val job = when (pending) {
            is PendingFileCommand.Flash -> vm.flashImage(pending.partition, pfd.fileDescriptor, size, name)
            is PendingFileCommand.Boot -> vm.bootImage(pfd.fileDescriptor, size, name)
            is PendingFileCommand.Fetch -> null // never routed here
        }
        job?.invokeOnCompletion { runCatching { pfd.close() } }
        text = ""
    }

    // fetch: pick a destination to write to.
    var fetchPartitionPending by remember { mutableStateOf<String?>(null) }
    val savePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val partition = fetchPartitionPending
        fetchPartitionPending = null
        if (uri != null && partition != null) {
            vm.fetchPartition(context, partition, uri)
            text = ""
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {

        if (parsed is ParsedShellInput.NeedsFileRawPathDetected && !forceRawPath) {
            RawPathWarningCard(path = parsed.detectedPath, onProceedAnyway = { forceRawPath = true })
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {

            val pending = pendingOf(parsed)
            val showAttach = pending != null &&
                !(parsed is ParsedShellInput.NeedsFileRawPathDetected && forceRawPath)

            if (showAttach) {
                val isFetch = pending is PendingFileCommand.Fetch
                IconButton(onClick = {
                    if (pending is PendingFileCommand.Fetch) {
                        fetchPartitionPending = pending.partition
                        savePicker.launch("${pending.partition}.img")
                    } else {
                        openPicker.launch(arrayOf("*/*"))
                    }
                }) {
                    Icon(
                        imageVector = if (isFetch) Icons.Filled.Save else Icons.Filled.AttachFile,
                        contentDescription = stringResource(if (isFetch) R.string.cd_choose_save_location else R.string.cd_attach_image),
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.fastboot_shell_placeholder)) },
                modifier = Modifier.weight(1f),
                enabled = !vm.isBusy,
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                enabled = !vm.isBusy && canSubmitDirectly(parsed, forceRawPath),
                onClick = {
                    submit(vm, parsed, forceRawPath)
                    text = ""
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_run))
            }
        }
    }
}

private fun pendingOf(parsed: ParsedShellInput): PendingFileCommand? = when (parsed) {
    is ParsedShellInput.NeedsFile -> parsed.pending
    is ParsedShellInput.NeedsFileRawPathDetected -> parsed.pending
    else -> null
}

private fun canSubmitDirectly(parsed: ParsedShellInput, forceRawPath: Boolean): Boolean = when (parsed) {
    is ParsedShellInput.Empty -> false
    is ParsedShellInput.NeedsFile -> false // must go through attach/save button
    is ParsedShellInput.NeedsFileRawPathDetected -> forceRawPath
    is ParsedShellInput.HelpRequested -> true
    is ParsedShellInput.Runnable -> true
    is ParsedShellInput.Unknown -> true
}

private fun submit(vm: FastbootViewModel, parsed: ParsedShellInput, forceRawPath: Boolean) {
    when (parsed) {
        is ParsedShellInput.HelpRequested -> vm.showShellHelp()
        is ParsedShellInput.Runnable -> vm.runShellCommand(parsed.wireCommand, parsed.displayLabel)
        is ParsedShellInput.Unknown -> vm.runShellCommand(parsed.raw, parsed.raw)
        is ParsedShellInput.NeedsFileRawPathDetected -> if (forceRawPath) {
            // Explicit user override; the literal string is forwarded as-is. This app cannot
            // read arbitrary filesystem paths without storage access, so the device will very
            // likely reject or fail on this — that failure is expected, not silently masked.
            vm.runShellCommand(FastbootCommandParser.translateCliCommand(parsed.raw), parsed.raw)
        }
        else -> Unit
    }
}

@Composable
private fun RawPathWarningCard(path: String, onProceedAnyway: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.fastboot_raw_path_warning_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.fastboot_raw_path_warning_msg, path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    onClick = onProceedAnyway,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.fastboot_proceed_anyway)) }
            }
        }
    }
}
