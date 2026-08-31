package io.github.rhythmcache.dioxamine.adb.builtin.screencap

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun ScreencapTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.adb_screenshot_tile_title),
        description = stringResource(R.string.adb_screenshot_tile_desc),
        icon = Icons.Filled.PhotoCamera,
        enabled = isConnected,
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreencapScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val client = vm.activeClient()

    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var rawPngBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? ->
        val bytes = rawPngBytes
        if (uri != null && bytes != null) {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.adb_screenshot_saved), Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, err.message ?: context.getString(R.string.err_save_file_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun captureScreenshot() {
        if (client == null) return
        isCapturing = true
        errorMessage = null

        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val stream = client.open("exec:screencap -p")
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } > 0) {
                    baos.write(buffer, 0, bytesRead)
                }
                stream.close()

                val bytes = baos.toByteArray()
                if (bytes.isEmpty()) throw Exception("Received 0 bytes from screencap")

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("Could not decode PNG screenshot image")

                bytes to bitmap.asImageBitmap()
            }.onSuccess { (bytes, bitmap) ->
                withContext(Dispatchers.Main) {
                    rawPngBytes = bytes
                    imageBitmap = bitmap
                    isCapturing = false
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    errorMessage = err.message ?: "Unknown error"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (imageBitmap == null && client != null) {
            captureScreenshot()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.adb_screenshot_tile_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            actions = {
                if (rawPngBytes != null && !isCapturing) {
                    IconButton(onClick = {
                        val timestamp = System.currentTimeMillis()
                        saveLauncher.launch("screenshot_$timestamp.png")
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = stringResource(R.string.adb_screenshot_save_btn)
                        )
                    }
                }
                IconButton(
                    enabled = client != null && !isCapturing,
                    onClick = { captureScreenshot() }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.adb_screenshot_take_btn)
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isCapturing -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.adb_screenshot_capturing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.adb_screenshot_failed, errorMessage.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { captureScreenshot() }) {
                            Text(stringResource(R.string.fastboot_retry))
                        }
                    }
                }
                imageBitmap != null -> {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = imageBitmap!!,
                                contentDescription = stringResource(R.string.adb_screenshot_tile_title),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.adb_screenshot_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
