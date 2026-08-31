package io.github.rhythmcache.dioxamine.adb.builtin.remotecontrol

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.core.executeShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(
    vm: AdbViewModel,
    onBack: () -> Unit,
    onOpenTouchpad: (() -> Unit)? = null
) {
    val client = vm.activeClient()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isTvDetected by remember { mutableStateOf(false) }
    var isDetecting by remember { mutableStateOf(true) }
    var manualOverride by remember { mutableStateOf<Boolean?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    var textInput by remember { mutableStateOf("") }
    var showNumPad by remember { mutableStateOf(false) }

    val effectiveIsTv = manualOverride ?: isTvDetected

    LaunchedEffect(client) {
        if (client != null) {
            isDetecting = true
            try {
                val output = client.executeShell(
                    "getprop ro.build.characteristics; echo '---'; pm list features",
                    supportsShellV2 = true
                )
                val isTv = output.contains("tv", ignoreCase = true) ||
                        output.contains("leanback", ignoreCase = true) ||
                        output.contains("television", ignoreCase = true)
                isTvDetected = isTv
            } catch (_: Exception) {
                isTvDetected = false
            } finally {
                isDetecting = false
            }
        }
    }

    fun sendKeyEvent(keyCode: Int) {
        val activeClient = client ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                activeClient.executeShell("input keyevent $keyCode", supportsShellV2 = true)
            }
        }
    }

    fun sendText(text: String) {
        val activeClient = client ?: return
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val escaped = text.replace(" ", "%s").replace("'", "\\'")
                activeClient.executeShell("input text '$escaped'", supportsShellV2 = true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { dropdownExpanded = true }
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.remote_control_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (dropdownExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                                    contentDescription = stringResource(R.string.cd_select_mode)
                                )
                            }
                            Text(
                                text = if (isDetecting) stringResource(R.string.remote_detecting_device)
                                else if (effectiveIsTv) {
                                    stringResource(if (isTvDetected) R.string.remote_mode_tv_detected else R.string.remote_mode_tv)
                                } else {
                                    stringResource(if (!isTvDetected) R.string.remote_mode_standard_detected else R.string.remote_mode_standard)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (effectiveIsTv) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        val tvLabel = stringResource(if (isTvDetected) R.string.remote_mode_tv_detected else R.string.remote_mode_tv)
                        val standardLabel = stringResource(if (!isTvDetected) R.string.remote_mode_standard_detected else R.string.remote_mode_standard)

                        DropdownMenuItem(
                            text = {
                                Text(
                                    tvLabel,
                                    fontWeight = if (effectiveIsTv) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                manualOverride = true
                                dropdownExpanded = false
                            },
                            leadingIcon = {
                                if (effectiveIsTv) {
                                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    standardLabel,
                                    fontWeight = if (!effectiveIsTv) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                manualOverride = false
                                dropdownExpanded = false
                            },
                            leadingIcon = {
                                if (!effectiveIsTv) {
                                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                if (onOpenTouchpad != null) {
                    IconButton(onClick = onOpenTouchpad) {
                        Icon(
                            imageVector = Icons.Filled.Mouse,
                            contentDescription = stringResource(R.string.cd_open_touchpad),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Power & Volume Row
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RemoteIconButton(
                        icon = Icons.Filled.PowerSettingsNew,
                        contentDescription = stringResource(R.string.cd_power),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { sendKeyEvent(26) } // KEYCODE_POWER
                    )
                    RemoteIconButton(
                        icon = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = stringResource(R.string.cd_mute),
                        onClick = { sendKeyEvent(164) } // KEYCODE_VOLUME_MUTE
                    )
                    RemoteIconButton(
                        icon = Icons.AutoMirrored.Filled.VolumeDown,
                        contentDescription = stringResource(R.string.cd_volume_down),
                        onClick = { sendKeyEvent(25) } // KEYCODE_VOLUME_DOWN
                    )
                    RemoteIconButton(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.cd_volume_up),
                        onClick = { sendKeyEvent(24) } // KEYCODE_VOLUME_UP
                    )
                }
            }

            // D-Pad Directional Pad
            Card(
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.size(240.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // D-Pad Up
                    IconButton(
                        onClick = { sendKeyEvent(19) }, // KEYCODE_DPAD_UP
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .size(56.dp)
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.cd_nav_up), modifier = Modifier.size(36.dp))
                    }

                    // D-Pad Down
                    IconButton(
                        onClick = { sendKeyEvent(20) }, // KEYCODE_DPAD_DOWN
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .size(56.dp)
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_nav_down), modifier = Modifier.size(36.dp))
                    }

                    // D-Pad Left
                    IconButton(
                        onClick = { sendKeyEvent(21) }, // KEYCODE_DPAD_LEFT
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .size(56.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.cd_nav_left), modifier = Modifier.size(36.dp))
                    }

                    // D-Pad Right
                    IconButton(
                        onClick = { sendKeyEvent(22) }, // KEYCODE_DPAD_RIGHT
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .size(56.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.cd_nav_right), modifier = Modifier.size(36.dp))
                    }

                    // D-Pad Center (OK / Select)
                    Surface(
                        onClick = { sendKeyEvent(23) }, // KEYCODE_DPAD_CENTER
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.btn_ok),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            // Navigation Buttons Row (Back, Home, Recents, Menu)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RemoteIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_nav_back),
                        onClick = { sendKeyEvent(4) } // KEYCODE_BACK
                    )
                    RemoteIconButton(
                        icon = Icons.Filled.RadioButtonUnchecked,
                        contentDescription = stringResource(R.string.cd_nav_home),
                        onClick = { sendKeyEvent(3) } // KEYCODE_HOME
                    )
                    RemoteIconButton(
                        icon = Icons.Filled.CropSquare,
                        contentDescription = stringResource(R.string.cd_nav_recents),
                        onClick = { sendKeyEvent(187) } // KEYCODE_APP_SWITCH
                    )
                    RemoteIconButton(
                        icon = Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.cd_nav_menu),
                        onClick = { sendKeyEvent(82) } // KEYCODE_MENU
                    )
                }
            }

            // Android TV Specific Controls Section (Media & TV Controls)
            AnimatedVisibility(
                visible = effectiveIsTv,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // TV Special Actions Row
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.remote_section_tv_controls),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                RemoteActionButton(label = stringResource(R.string.remote_tv_guide), icon = Icons.AutoMirrored.Filled.List) { sendKeyEvent(172) } // KEYCODE_GUIDE
                                RemoteActionButton(label = stringResource(R.string.remote_tv_input), icon = Icons.AutoMirrored.Filled.Input) { sendKeyEvent(178) } // KEYCODE_TV_INPUT
                                RemoteActionButton(label = stringResource(R.string.remote_tv_ch_up), icon = Icons.Filled.ExpandLess) { sendKeyEvent(166) } // KEYCODE_CHANNEL_UP
                                RemoteActionButton(label = stringResource(R.string.remote_tv_ch_down), icon = Icons.Filled.ExpandMore) { sendKeyEvent(167) } // KEYCODE_CHANNEL_DOWN
                                RemoteActionButton(label = stringResource(R.string.remote_tv_search), icon = Icons.Filled.Search) { sendKeyEvent(84) } // KEYCODE_SEARCH
                            }
                        }
                    }

                    // Media Playback Controls
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.remote_media_controls), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                RemoteIconButton(icon = Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.cd_previous)) { sendKeyEvent(88) }
                                RemoteIconButton(icon = Icons.Filled.FastRewind, contentDescription = stringResource(R.string.cd_rewind)) { sendKeyEvent(89) }
                                RemoteIconButton(icon = Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.cd_play_pause), tint = MaterialTheme.colorScheme.primary) { sendKeyEvent(85) }
                                RemoteIconButton(icon = Icons.Filled.FastForward, contentDescription = stringResource(R.string.cd_fast_forward)) { sendKeyEvent(90) }
                                RemoteIconButton(icon = Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_next)) { sendKeyEvent(87) }
                            }
                        }
                    }
                }
            }

            // Minimalist Input Injection Bar
            RemoteInputBar(
                value = textInput,
                onValueChange = { textInput = it },
                onSendText = {
                    sendText(textInput)
                    textInput = ""
                },
                showNumPad = showNumPad,
                onToggleNumPad = { showNumPad = !showNumPad }
            )

            // Numeric Pad Drawer (0-9 keycodes)
            AnimatedVisibility(visible = showNumPad) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.remote_numeric_keypad), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        val rows = listOf(
                            listOf(7 to 14, 8 to 15, 9 to 16),
                            listOf(4 to 11, 5 to 12, 6 to 13),
                            listOf(1 to 8, 2 to 9, 3 to 10),
                            listOf(0 to 7)
                        )
                        for (row in rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                for ((num, keyCode) in row) {
                                    OutlinedButton(
                                        onClick = { sendKeyEvent(keyCode) },
                                        modifier = Modifier.size(56.dp),
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(num.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSendText: () -> Unit,
    showNumPad: Boolean,
    onToggleNumPad: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        placeholder = {
            Text(
                text = stringResource(R.string.remote_text_placeholder),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Keyboard,
                contentDescription = stringResource(R.string.cd_keyboard),
                tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    IconButton(onClick = onSendText) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.cd_send_text),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onToggleNumPad) {
                    Icon(
                        imageVector = Icons.Filled.Dialpad,
                        contentDescription = stringResource(R.string.cd_toggle_numpad),
                        tint = if (showNumPad) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSendText() }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent
        )
    )
}

@Composable
private fun RemoteIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RemoteActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
