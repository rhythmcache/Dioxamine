package io.github.rhythmcache.dioxamine.adb.builtin.touchpad

import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import kotlin.math.roundToInt

// Static immutable list of function keys (hoisted outside composables)
private val FUNCTION_KEYS = arrayOf(
    "F1" to HidKeyCodes.KEY_F1, "F2" to HidKeyCodes.KEY_F2, "F3" to HidKeyCodes.KEY_F3,
    "F4" to HidKeyCodes.KEY_F4, "F5" to HidKeyCodes.KEY_F5, "F6" to HidKeyCodes.KEY_F6,
    "F7" to HidKeyCodes.KEY_F7, "F8" to HidKeyCodes.KEY_F8, "F9" to HidKeyCodes.KEY_F9,
    "F10" to HidKeyCodes.KEY_F10, "F11" to HidKeyCodes.KEY_F11, "F12" to HidKeyCodes.KEY_F12
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchpadScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val client = vm.activeClient()
    val haptics = LocalHapticFeedback.current

    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Tab state: 0 = TouchPad, 1 = PC Keyboard
    var selectedTab by remember { mutableIntStateOf(0) }

    var sensitivity by remember { mutableFloatStateOf(1.2f) }
    var showSettings by remember { mutableStateOf(false) }
    var showQuickImeOnTouchpad by remember { mutableStateOf(false) }

    // Active Modifier Latches
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    var shiftLatched by remember { mutableStateOf(false) }
    var metaLatched by remember { mutableStateOf(false) }

    fun getActiveModifiers(): Int {
        var mods = HidKeyCodes.MOD_NONE
        if (ctrlLatched) mods = mods or HidKeyCodes.MOD_LEFT_CTRL
        if (altLatched) mods = mods or HidKeyCodes.MOD_LEFT_ALT
        if (shiftLatched) mods = mods or HidKeyCodes.MOD_LEFT_SHIFT
        if (metaLatched) mods = mods or HidKeyCodes.MOD_LEFT_GUI
        return mods
    }

    // Physical button hold states
    var isLeftButtonHeld by remember { mutableStateOf(false) }
    var isRightButtonHeld by remember { mutableStateOf(false) }

    val manager = remember(client) {
        if (client == null) null
        else UhidControlManager(
            context = context,
            client = client,
            onConnected = {
                isConnected = true
                isConnecting = false
            },
            onError = { err ->
                isConnected = false
                isConnecting = false
                errorMessage = err
            }
        )
    }

    LaunchedEffect(manager) {
        if (manager != null) {
            isConnecting = true
            isConnected = false
            manager.start()
        }
    }

    DisposableEffect(manager) {
        onDispose {
            manager?.close()
        }
    }

    fun updateButtonMask(left: Boolean, right: Boolean) {
        var mask = HidKeyCodes.MOUSE_BTN_NONE
        if (left) mask = mask or HidKeyCodes.MOUSE_BTN_LEFT
        if (right) mask = mask or HidKeyCodes.MOUSE_BTN_RIGHT
        manager?.setMouseButtonState(mask)
    }

    fun sendKey(hidCode: Int) {
        val mods = getActiveModifiers()
        manager?.sendKeyStroke(hidCode, mods)
        // Reset latches after single stroke
        if (ctrlLatched) ctrlLatched = false
        if (altLatched) altLatched = false
        if (shiftLatched) shiftLatched = false
        if (metaLatched) metaLatched = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.touchpad_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isConnected -> Color(0xFF4CAF50)
                                            isConnecting -> Color(0xFFFF9800)
                                            else -> MaterialTheme.colorScheme.error
                                        }
                                    )
                            )
                        }
                        Text(
                            text = if (isConnecting) stringResource(R.string.touchpad_connecting)
                            else if (isConnected) stringResource(R.string.touchpad_connected)
                            else stringResource(R.string.status_disconnected),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_nav_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.cd_sensitivity_settings),
                            tint = if (showSettings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Mode Selector Tabs: [ 🖱️ TouchPad ] | [ ⌨️ PC Keyboard ]
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_touchpad), fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Mouse, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_keyboard), fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) }
                )
            }

            // Sensitivity Settings Dropdown
            AnimatedVisibility(
                visible = showSettings,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.touchpad_sensitivity),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                String.format("%.1fx", sensitivity),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = sensitivity,
                            onValueChange = { sensitivity = it },
                            valueRange = 0.5f..3.0f,
                            steps = 25
                        )
                    }
                }
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (selectedTab == 0) {
                    // TAB 0: Full TouchPad Screen
                    TouchpadTabContent(
                        sensitivity = sensitivity,
                        isLeftButtonHeld = isLeftButtonHeld,
                        isRightButtonHeld = isRightButtonHeld,
                        showQuickIme = showQuickImeOnTouchpad,
                        onToggleQuickIme = { showQuickImeOnTouchpad = !showQuickImeOnTouchpad },
                        onMove = { dx, dy ->
                            var currentMask = HidKeyCodes.MOUSE_BTN_NONE
                            if (isLeftButtonHeld) currentMask = currentMask or HidKeyCodes.MOUSE_BTN_LEFT
                            if (isRightButtonHeld) currentMask = currentMask or HidKeyCodes.MOUSE_BTN_RIGHT
                            manager?.sendMouseMove(dx, dy, currentMask)
                        },
                        onTap = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            manager?.clickMouseButton(HidKeyCodes.MOUSE_BTN_LEFT)
                        },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            manager?.clickMouseButton(HidKeyCodes.MOUSE_BTN_RIGHT)
                        },
                        onScroll = { wheelDelta, panDelta ->
                            manager?.sendMouseScroll(wheelDelta, panDelta)
                        },
                        onLeftPressDown = {
                            isLeftButtonHeld = true
                            updateButtonMask(left = true, right = isRightButtonHeld)
                        },
                        onLeftPressUp = {
                            isLeftButtonHeld = false
                            updateButtonMask(left = false, right = isRightButtonHeld)
                        },
                        onMiddleClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            manager?.clickMouseButton(HidKeyCodes.MOUSE_BTN_MIDDLE)
                        },
                        onRightPressDown = {
                            isRightButtonHeld = true
                            updateButtonMask(left = isLeftButtonHeld, right = true)
                        },
                        onRightPressUp = {
                            isRightButtonHeld = false
                            updateButtonMask(left = isLeftButtonHeld, right = false)
                        },
                        onKeyPress = { sendKey(it) },
                        manager = manager
                    )
                } else {
                    // TAB 1: Full PC Keyboard Screen
                    PcKeyboardTabContent(
                        ctrlLatched = ctrlLatched,
                        altLatched = altLatched,
                        shiftLatched = shiftLatched,
                        metaLatched = metaLatched,
                        onToggleCtrl = { ctrlLatched = !ctrlLatched },
                        onToggleAlt = { altLatched = !altLatched },
                        onToggleShift = { shiftLatched = !shiftLatched },
                        onToggleMeta = { metaLatched = !metaLatched },
                        onKeyPress = { sendKey(it) },
                        onShortcut = { hidKey, mods -> manager?.sendKeyStroke(hidKey, mods) },
                        manager = manager
                    )
                }
            }
        }
    }

    // Error Dialog
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            icon = { Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.touchpad_error_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.touchpad_error_desc))
                    Text(
                        text = stringResource(R.string.touchpad_error_details, errorMessage ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        )
    }
}

/**
 * Tab 0: Spacious Full-Height TouchPad with discrete buttons and quick real-time keyboard overlay.
 */
@Composable
private fun TouchpadTabContent(
    sensitivity: Float,
    isLeftButtonHeld: Boolean,
    isRightButtonHeld: Boolean,
    showQuickIme: Boolean,
    onToggleQuickIme: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onScroll: (Int, Int) -> Unit,
    onLeftPressDown: () -> Unit,
    onLeftPressUp: () -> Unit,
    onMiddleClick: () -> Unit,
    onRightPressDown: () -> Unit,
    onRightPressUp: () -> Unit,
    onKeyPress: (Int) -> Unit,
    manager: UhidControlManager?
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Large TouchPad Canvas Area
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .touchpadGestures(
                        sensitivity = sensitivity,
                        onMove = onMove,
                        onTap = onTap,
                        onLongPress = onLongPress,
                        onScroll = onScroll
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.touchpad_surface_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Discrete Left / Middle / Right Mouse Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HoldableMouseButton(
                label = stringResource(R.string.touchpad_btn_left),
                isHeld = isLeftButtonHeld,
                modifier = Modifier.weight(1f),
                onPressDown = onLeftPressDown,
                onPressUp = onLeftPressUp
            )

            Surface(
                onClick = onMiddleClick,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.touchpad_btn_middle),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HoldableMouseButton(
                label = stringResource(R.string.touchpad_btn_right),
                isHeld = isRightButtonHeld,
                modifier = Modifier.weight(1f),
                onPressDown = onRightPressDown,
                onPressUp = onRightPressUp
            )
        }

        // Quick Bottom Action Strip with Soft Keyboard Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton("Esc", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_ESC) }
            KeyButton("Tab", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_TAB) }
            KeyButton("Enter", Modifier.weight(1.2f)) { onKeyPress(HidKeyCodes.KEY_ENTER) }
            KeyButton("⌫ Del", Modifier.weight(1.2f)) { onKeyPress(HidKeyCodes.KEY_BACKSPACE) }

            // Instant Keyboard Toggle Button
            Surface(
                onClick = onToggleQuickIme,
                shape = RoundedCornerShape(10.dp),
                color = if (showQuickIme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .weight(1.4f)
                    .height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = stringResource(R.string.touchpad_toggle_ime),
                        modifier = Modifier.size(16.dp),
                        tint = if (showQuickIme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.touchpad_type),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (showQuickIme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Real-Time Keyboard Input Box (Appears when quick keyboard is toggled)
        AnimatedVisibility(
            visible = showQuickIme,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            RealTimeImeInterceptor(
                manager = manager,
                autoFocus = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Tab 1: Comprehensive PC Hardware Keyboard layout with Real-Time Soft Keyboard listener.
 */
@Composable
private fun PcKeyboardTabContent(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    shiftLatched: Boolean,
    metaLatched: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleMeta: () -> Unit,
    onKeyPress: (Int) -> Unit,
    onShortcut: (Int, Int) -> Unit,
    manager: UhidControlManager?
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Real-Time Soft Keyboard Input Box
        RealTimeImeInterceptor(
            manager = manager,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth()
        )

        // Section: Modifier Keys & Navigation
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.touchpad_modifiers),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModifierKeyChip("Ctrl", ctrlLatched, onToggleCtrl, Modifier.weight(1f))
                    ModifierKeyChip("Alt", altLatched, onToggleAlt, Modifier.weight(1f))
                    ModifierKeyChip("Shift", shiftLatched, onToggleShift, Modifier.weight(1f))
                    ModifierKeyChip("Win", metaLatched, onToggleMeta, Modifier.weight(1f))
                    KeyButton("Esc", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_ESC) }
                    KeyButton("Tab", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_TAB) }
                }
            }
        }

        // Section: Navigation & Editing Block
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.touchpad_navigation),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeyButton("Del", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_DELETE) }
                    KeyButton("Ins", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_INSERT) }
                    KeyButton("Home", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_HOME) }
                    KeyButton("End", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_END) }
                    KeyButton("PgUp", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_PAGE_UP) }
                    KeyButton("PgDn", Modifier.weight(1f)) { onKeyPress(HidKeyCodes.KEY_PAGE_DOWN) }
                }

                // Arrow Keys Pad & Action Keys
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KeyButton("Enter ↵", Modifier.weight(1.2f)) { onKeyPress(HidKeyCodes.KEY_ENTER) }
                    KeyButton("⌫ Backspace", Modifier.weight(1.5f)) { onKeyPress(HidKeyCodes.KEY_BACKSPACE) }

                    Spacer(Modifier.weight(0.5f))

                    KeyIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onKeyPress(HidKeyCodes.KEY_LEFT) }
                    KeyIconButton(Icons.Filled.KeyboardArrowUp) { onKeyPress(HidKeyCodes.KEY_UP) }
                    KeyIconButton(Icons.Filled.KeyboardArrowDown) { onKeyPress(HidKeyCodes.KEY_DOWN) }
                    KeyIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight) { onKeyPress(HidKeyCodes.KEY_RIGHT) }
                }
            }
        }

        // Section: Function Keys (F1 - F12)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.touchpad_functions),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (i in 0 until 6) {
                        val (label, code) = FUNCTION_KEYS[i]
                        Surface(
                            onClick = { onKeyPress(code) },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (i in 6 until 12) {
                        val (label, code) = FUNCTION_KEYS[i]
                        Surface(
                            onClick = { onKeyPress(code) },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Section: Common Desktop Shortcuts
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.touchpad_shortcuts),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShortcutChip("Ctrl+C") { onShortcut(HidKeyCodes.KEY_C, HidKeyCodes.MOD_LEFT_CTRL) }
                    ShortcutChip("Ctrl+V") { onShortcut(HidKeyCodes.KEY_V, HidKeyCodes.MOD_LEFT_CTRL) }
                    ShortcutChip("Ctrl+Z") { onShortcut(HidKeyCodes.KEY_Z, HidKeyCodes.MOD_LEFT_CTRL) }
                    ShortcutChip("Ctrl+A") { onShortcut(HidKeyCodes.KEY_A, HidKeyCodes.MOD_LEFT_CTRL) }
                    ShortcutChip("Alt+Tab") { onShortcut(HidKeyCodes.KEY_TAB, HidKeyCodes.MOD_LEFT_ALT) }
                }
            }
        }
    }
}

/**
 * Real-Time Input Connection with Anchor Buffer ("  ").
 * Any character typed, pasted, or deleted is streamed instantly to the target device without buffering or "Send" buttons.
 */
@Composable
private fun RealTimeImeInterceptor(
    manager: UhidControlManager?,
    autoFocus: Boolean,
    modifier: Modifier = Modifier
) {
    val anchorText = "  "
    var bufferState by remember { mutableStateOf(TextFieldValue(anchorText, selection = TextRange(2))) }
    var isFocused by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isFocused) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (isFocused) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Keyboard,
                contentDescription = null,
                tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (!isFocused) {
                    Text(
                        text = stringResource(R.string.touchpad_type_here),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.touchpad_realtime_typing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }

                BasicTextField(
                    value = bufferState,
                    onValueChange = { newBuffer ->
                        val oldLen = bufferState.text.length
                        val newLen = newBuffer.text.length

                        if (newLen > oldLen) {
                            val inserted = newBuffer.text.substring(oldLen)
                            manager?.sendInjectText(inserted)
                        } else if (newLen < oldLen) {
                            val deletedCount = oldLen - newLen
                            repeat(deletedCount) {
                                manager?.sendKeyStroke(HidKeyCodes.KEY_BACKSPACE)
                            }
                        } else if (newBuffer.text != anchorText) {
                            manager?.sendInjectText(newBuffer.text.trim())
                        }

                        bufferState = TextFieldValue(anchorText, selection = TextRange(2))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        manager?.sendKeyStroke(HidKeyCodes.KEY_ENTER)
                                        true
                                    }
                                    Key.Tab -> {
                                        manager?.sendKeyStroke(HidKeyCodes.KEY_TAB)
                                        true
                                    }
                                    Key.Escape -> {
                                        manager?.sendKeyStroke(HidKeyCodes.KEY_ESC)
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
                    cursorBrush = SolidColor(if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.None,
                        autoCorrectEnabled = false
                    ),
                    singleLine = true
                )
            }

            if (isFocused) {
                IconButton(
                    onClick = {
                        keyboardController?.hide()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardHide,
                        contentDescription = stringResource(R.string.cd_hide_keyboard),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Multi-touch gesture detector with allocation-conscious pointer iteration and optimized movement checks.
 */
@Composable
private fun Modifier.touchpadGestures(
    sensitivity: Float,
    onMove: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onScroll: (Int, Int) -> Unit
): Modifier {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnScroll by rememberUpdatedState(onScroll)

    return this.pointerInput(sensitivity) {
        var accumulatedDx = 0f
        var accumulatedDy = 0f

        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val startTime = SystemClock.uptimeMillis()
            var isTwoFinger = false
            var isMoved = false
            var hasLastTwoFingerCenter = false
            var lastTwoFingerCenter = Offset.Zero

            accumulatedDx = 0f
            accumulatedDy = 0f

            while (true) {
                val event = awaitPointerEvent()
                
                // Zero-allocation loop to count pressed pointers and extract first & second
                var pressedCount = 0
                var firstPointer: PointerInputChange? = null
                var secondPointer: PointerInputChange? = null
                val changes = event.changes
                for (i in 0 until changes.size) {
                    val c = changes[i]
                    if (c.pressed) {
                        if (pressedCount == 0) firstPointer = c
                        else if (pressedCount == 1) secondPointer = c
                        pressedCount++
                    }
                }

                if (pressedCount == 0) {
                    // All fingers lifted
                    val duration = SystemClock.uptimeMillis() - startTime
                    if (!isTwoFinger && !isMoved) {
                        if (duration >= 500) {
                            currentOnLongPress()
                        } else {
                            currentOnTap()
                        }
                    }
                    break
                }

                if (pressedCount >= 2 && firstPointer != null && secondPointer != null) {
                    isTwoFinger = true
                    val p1 = firstPointer.position
                    val p2 = secondPointer.position
                    val center = Offset((p1.x + p2.x) * 0.5f, (p1.y + p2.y) * 0.5f)

                    if (hasLastTwoFingerCenter) {
                        val deltaY = center.y - lastTwoFingerCenter.y
                        val deltaX = center.x - lastTwoFingerCenter.x
                        val wheel = (deltaY * -0.2f * sensitivity).roundToInt()
                        val pan = (deltaX * 0.2f * sensitivity).roundToInt()
                        if (wheel != 0 || pan != 0) {
                            currentOnScroll(wheel, pan)
                        }
                    }
                    lastTwoFingerCenter = center
                    hasLastTwoFingerCenter = true
                    for (i in 0 until changes.size) {
                        changes[i].consume()
                    }
                } else if (pressedCount == 1 && !isTwoFinger && firstPointer != null) {
                    val change = firstPointer
                    val delta = change.position - change.previousPosition
                    // Squared distance check avoids Math.sqrt() in hot loop
                    val distSq = delta.x * delta.x + delta.y * delta.y
                    if (distSq > 2.25f) {
                        isMoved = true
                    }

                    accumulatedDx += delta.x * sensitivity
                    accumulatedDy += delta.y * sensitivity

                    val sendX = accumulatedDx.roundToInt()
                    val sendY = accumulatedDy.roundToInt()

                    if (sendX != 0 || sendY != 0) {
                        accumulatedDx -= sendX
                        accumulatedDy -= sendY
                        // Pass full integer delta; UhidControlManager's CAS loop handles HID range chunking
                        currentOnMove(sendX, sendY)
                    }
                    change.consume()
                }
            }
        }
    }
}

@Composable
private fun HoldableMouseButton(
    label: String,
    isHeld: Boolean,
    modifier: Modifier = Modifier,
    onPressDown: () -> Unit,
    onPressUp: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> onPressDown()
                is PressInteraction.Release, is PressInteraction.Cancel -> onPressUp()
            }
        }
    }

    Surface(
        interactionSource = interactionSource,
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        color = if (isHeld) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxHeight()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ModifierKeyChip(
    label: String,
    isLatched: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(8.dp),
        color = if (isLatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isLatched) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun KeyIconButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ShortcutChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
