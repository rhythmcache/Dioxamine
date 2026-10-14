package io.github.rhythmcache.dioxamine.adb.shell

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Three-state modifier key tracking (OFF, ON for next key, LOCKED).
 */
enum class ModifierState {
    OFF,
    ON,
    LOCKED
}

/**
 * Termux-style 2x7 extra keys panel.
 *
 * Row 1: ESC  /  -  HOME  ↑  END  PGUP
 * Row 2: ↹  CTRL  ALT  ←  ↓  →  PGDN
 *
 * Equal-width columns, solid black background, light text.
 */
@Composable
fun ExtraKeysPanel(
    ctrlState: ModifierState,
    altState: ModifierState,
    onToggleCtrl: () -> Unit,
    onLockCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onLockAlt: () -> Unit,
    onKeyAction: (keyCode: Int) -> Unit,
    onCharAction: (char: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(vertical = 1.dp)
    ) {
        // Row 1: ESC  /  -  HOME  ↑  END  PGUP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RegularKeyCell("ESC", onClick = { onCharAction("ESC") }, modifier = Modifier.weight(1f))
            RegularKeyCell("/", onClick = { onCharAction("/") }, modifier = Modifier.weight(1f))
            RegularKeyCell("-", onClick = { onCharAction("-") }, modifier = Modifier.weight(1f))
            RegularKeyCell("HOME", onClick = { onKeyAction(KeyEvent.KEYCODE_MOVE_HOME) }, modifier = Modifier.weight(1f))
            RepeatableKeyCell("↑", onPress = { onKeyAction(KeyEvent.KEYCODE_DPAD_UP) }, modifier = Modifier.weight(1f))
            RegularKeyCell("END", onClick = { onKeyAction(KeyEvent.KEYCODE_MOVE_END) }, modifier = Modifier.weight(1f))
            RepeatableKeyCell("PGUP", onPress = { onKeyAction(KeyEvent.KEYCODE_PAGE_UP) }, modifier = Modifier.weight(1f))
        }

        // Row 2: ↹  CTRL  ALT  ←  ↓  →  PGDN
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RegularKeyCell("↹", onClick = { onCharAction("TAB") }, modifier = Modifier.weight(1f))
            ModifierKeyCell("CTRL", state = ctrlState, onToggle = onToggleCtrl, onLock = onLockCtrl, modifier = Modifier.weight(1f))
            ModifierKeyCell("ALT", state = altState, onToggle = onToggleAlt, onLock = onLockAlt, modifier = Modifier.weight(1f))
            RepeatableKeyCell("←", onPress = { onKeyAction(KeyEvent.KEYCODE_DPAD_LEFT) }, modifier = Modifier.weight(1f))
            RepeatableKeyCell("↓", onPress = { onKeyAction(KeyEvent.KEYCODE_DPAD_DOWN) }, modifier = Modifier.weight(1f))
            RepeatableKeyCell("→", onPress = { onKeyAction(KeyEvent.KEYCODE_DPAD_RIGHT) }, modifier = Modifier.weight(1f))
            RepeatableKeyCell("PGDN", onPress = { onKeyAction(KeyEvent.KEYCODE_PAGE_DOWN) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RegularKeyCell(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = Color.White),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.SansSerif,
        )
    }
}

@Composable
private fun RepeatableKeyCell(
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnPress by rememberUpdatedState(onPress)
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isPressed) Color(0xFF333333) else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentOnPress()
                    val repeatJob = coroutineScope.launch {
                        delay(400)
                        while (isActive) {
                            currentOnPress()
                            delay(75)
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeatJob.cancel()
                        isPressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.SansSerif,
        )
    }
}

@Composable
private fun ModifierKeyCell(
    label: String,
    state: ModifierState,
    onToggle: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnLock by rememberUpdatedState(onLock)
    val view = LocalView.current

    val bgColor = when (state) {
        ModifierState.OFF -> Color.Transparent
        ModifierState.ON -> Color(0xFF2E3B4E)
        ModifierState.LOCKED -> Color(0xFF1B3A26)
    }
    val textColor = when (state) {
        ModifierState.OFF -> Color.White
        ModifierState.ON -> Color(0xFF4FC3F7)
        ModifierState.LOCKED -> Color(0xFF81C784)
    }
    val textWeight = when (state) {
        ModifierState.OFF -> FontWeight.Normal
        ModifierState.ON -> FontWeight.Bold
        ModifierState.LOCKED -> FontWeight.Bold
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnToggle()
                    },
                    onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnLock()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = textWeight,
                fontFamily = FontFamily.SansSerif,
            )
            if (state == ModifierState.LOCKED) {
                Box(
                    modifier = Modifier
                        .size(width = 16.dp, height = 2.dp)
                        .background(textColor)
                )
            }
        }
    }
}
