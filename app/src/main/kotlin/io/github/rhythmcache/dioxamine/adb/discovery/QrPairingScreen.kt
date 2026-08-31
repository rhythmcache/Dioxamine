package io.github.rhythmcache.dioxamine.adb.discovery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import io.github.alexzhirkevich.qrose.options.*
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.rhythmcache.adb.*
import io.github.rhythmcache.dioxamine.BuildConfig
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.core.AppLogger
import java.io.File

/**
 * Full-screen "pair by QR" flow. Generates a QR the device's Wireless
 * Debugging QR scanner can read, waits for the device to pair, then
 * identifies the device's _adb-tls-connect._tcp advertisement by matching
 * its ADB GUID or IP address against NsdAdbDiscovery's live results, and hands off host:port for auto-connect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPairingScreen(
    keyDir: File,
    onBack: () -> Unit,
    onPairedAndDiscoverable: (host: String, port: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engine = remember {
        val model = android.os.Build.MODEL.ifBlank { "Device" }.replace(" ", "_")
        QrPairingEngine(context, FileKeyProvider(File(keyDir, "adbkey"), identityComment = "$model@${BuildConfig.APP_NAME}"))
    }
    val discovery = remember { NsdAdbDiscovery(context) }

    var outcome by remember { mutableStateOf<QrPairingOutcome?>(null) }
    var connectHandedOff by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        discovery.start()
        engine.start(scope) { result -> outcome = result }
        onDispose {
            discovery.stop()
            engine.stop()
        }
    }

    // Match the paired device's IP or GUID against live _adb-tls-connect._tcp
    // entries every time discovery updates (covers both "already advertised
    // before we finished pairing" and "shows up a moment later").
    LaunchedEffect(outcome, discovery.discovered.size) {
        val paired = outcome as? QrPairingOutcome.Success ?: return@LaunchedEffect
        if (connectHandedOff) return@LaunchedEffect
        val guid = paired.guid
        val host = paired.host

        AppLogger.i("QrPairingScreen", "Checking discovery for paired phone (guid='$guid', host='$host'). Discovered total=${discovery.discovered.size}")

        val matchingEntry = discovery.discovered.values.firstOrNull { dev ->
            if (dev.type != AdbServiceType.TLS_CONNECT) return@firstOrNull false

            val matchesHost = dev.host == host
            val matchesGuid = guid != null && (dev.deviceId == guid || dev.serviceName.contains(guid))

            matchesHost || matchesGuid
        }

        if (matchingEntry != null) {
            AppLogger.i("QrPairingScreen", "Matched discovered TLS_CONNECT device: ${matchingEntry.serviceName} @ ${matchingEntry.host}:${matchingEntry.port}")
            connectHandedOff = true
            onPairedAndDiscoverable(matchingEntry.host, matchingEntry.port)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cd_pair_qr)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_nav_back))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val o = outcome) {
                null -> {
                    val payload = engine.qrPayload
                    if (payload == null) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.qr_starting_server), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val logoPainter = remember(context) {
                            runCatching {
                                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                                val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                drawable.setBounds(0, 0, 192, 192)
                                drawable.draw(canvas)
                                BitmapPainter(bitmap.asImageBitmap())
                            }.getOrNull()
                        }

                        val painter = rememberQrCodePainter(payload) {
                            colors {
                                dark = QrBrush.solid(primaryColor)
                            }
                            if (logoPainter != null) {
                                logo {
                                    painter = logoPainter
                                    padding = QrLogoPadding.Natural(0.12f)
                                    shape = QrLogoShape.circle()
                                    size = 0.25f
                                }
                            }
                            shapes {
                                ball = QrBallShape.circle()
                                darkPixel = QrPixelShape.roundCorners()
                                frame = QrFrameShape.roundCorners(0.25f)
                            }
                            errorCorrectionLevel = QrErrorCorrectionLevel.High
                        }
                        Surface(
                            color = androidx.compose.ui.graphics.Color.White,
                            contentColor = androidx.compose.ui.graphics.Color.Black,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            shadowElevation = 4.dp
                        ) {
                            Image(
                                painter = painter,
                                contentDescription = stringResource(R.string.cd_qr_code),
                                modifier = Modifier
                                    .size(240.dp)
                                    .padding(16.dp)
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            stringResource(R.string.qr_instruction),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.qr_wifi_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is QrPairingOutcome.Success -> {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.qr_paired_success), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(if (connectHandedOff) R.string.qr_connecting else R.string.qr_waiting_discoverable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!connectHandedOff) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                is QrPairingOutcome.Failure -> {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.qr_pairing_failed), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(o.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onBack) { Text(stringResource(R.string.cd_close)) }
                }
            }
        }
    }
}