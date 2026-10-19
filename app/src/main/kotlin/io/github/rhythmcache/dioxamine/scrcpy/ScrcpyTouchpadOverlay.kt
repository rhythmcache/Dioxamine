package io.github.rhythmcache.dioxamine.scrcpy

import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.HidKeyCodes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ScrcpyTouchpadOverlay(
    session: ScrcpySession?,
    onClose: () -> Unit,
    isFullScreen: Boolean = false,
    modifier: Modifier = Modifier,
    onDrag: (Float, Float) -> Unit = { _, _ -> }
) {
    val haptics = LocalHapticFeedback.current
    val currentOnDrag by rememberUpdatedState(onDrag)

    var sensitivity by remember { mutableFloatStateOf(1.2f) }
    var showSensitivitySlider by remember { mutableStateOf(false) }
    var showBottomButtons by rememberSaveable { mutableStateOf(false) }

    var isLeftButtonHeld by remember { mutableStateOf(false) }
    var isRightButtonHeld by remember { mutableStateOf(false) }

    // Ensure UHID devices are registered and reset held flags when session changes/reconnects
    LaunchedEffect(session) {
        isLeftButtonHeld = false
        isRightButtonHeld = false
        session?.ensureUhidDevices()
    }

    fun updateButtonMask(left: Boolean, right: Boolean) {
        var mask = HidKeyCodes.MOUSE_BTN_NONE
        if (left) mask = mask or HidKeyCodes.MOUSE_BTN_LEFT
        if (right) mask = mask or HidKeyCodes.MOUSE_BTN_RIGHT
        session?.setMouseButtonState(mask)
    }

    BoxWithConstraints(modifier = modifier) {
        val showExpandedRows = maxHeight > 200.dp

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        down.consume()
                    }
                },
            shape = RoundedCornerShape(18.dp),
            color = Color(0xD81A1A1A), // sleek translucent dark surface
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Slim Drag Header (No title text - maximizes touch space)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                currentOnDrag(drag.x, drag.y)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Subtle horizontal drag pill
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.35f))
                    )

                    // Top-Right: Control buttons cluster pill (Tune, Expand Buttons, Close)
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp,
                            Color.White.copy(alpha = 0.14f)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                        ) {
                            IconButton(
                                onClick = { showSensitivitySlider = !showSensitivitySlider },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Tune,
                                    contentDescription = stringResource(R.string.cd_sensitivity_settings),
                                    tint = if (showSensitivitySlider) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            IconButton(
                                onClick = { showBottomButtons = !showBottomButtons },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Mouse,
                                    contentDescription = "Toggle mouse buttons",
                                    tint = if (showBottomButtons) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_close),
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                // Sensitivity slider (toggled via Tune icon)
                AnimatedVisibility(
                    visible = showSensitivitySlider,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.touchpad_sensitivity),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Slider(
                                value = sensitivity,
                                onValueChange = { sensitivity = it },
                                valueRange = 0.5f..3.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1fx", sensitivity),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Touchpad Canvas (weighted layout to fill remaining window space)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .touchpadGestures(
                                sensitivity = sensitivity,
                                onMove = { dx, dy ->
                                    session?.sendMouseMove(dx, dy)
                                },
                                onTap = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    session?.clickMouseButton(HidKeyCodes.MOUSE_BTN_LEFT)
                                },
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    session?.clickMouseButton(HidKeyCodes.MOUSE_BTN_RIGHT)
                                },
                                onScroll = { wheelDelta, panDelta ->
                                    session?.sendMouseScroll(wheelDelta, panDelta)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.TouchApp,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = Color.White.copy(alpha = 0.35f)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.touchpad_surface_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Collapsible Bottom Controls (Mouse Buttons & Quick Actions)
                AnimatedVisibility(
                    visible = showBottomButtons,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Left / Middle / Right Mouse Buttons
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HoldableMouseButton(
                                label = stringResource(R.string.touchpad_btn_left),
                                isHeld = isLeftButtonHeld,
                                modifier = Modifier.weight(1f),
                                onPressDown = {
                                    isLeftButtonHeld = true
                                    updateButtonMask(left = true, right = isRightButtonHeld)
                                },
                                onPressUp = {
                                    isLeftButtonHeld = false
                                    updateButtonMask(left = false, right = isRightButtonHeld)
                                }
                            )

                            Surface(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    session?.clickMouseButton(HidKeyCodes.MOUSE_BTN_MIDDLE)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .weight(0.7f)
                                    .fillMaxHeight()
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        stringResource(R.string.touchpad_btn_middle),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                            }

                            HoldableMouseButton(
                                label = stringResource(R.string.touchpad_btn_right),
                                isHeld = isRightButtonHeld,
                                modifier = Modifier.weight(1f),
                                onPressDown = {
                                    isRightButtonHeld = true
                                    updateButtonMask(left = isLeftButtonHeld, right = true)
                                },
                                onPressUp = {
                                    isRightButtonHeld = false
                                    updateButtonMask(left = isLeftButtonHeld, right = false)
                                }
                            )
                        }

                        // Quick Action Bar (shown when window height > 200.dp)
                        if (showExpandedRows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                KeyButton("Esc", Modifier.weight(1f)) { session?.sendKeyStroke(HidKeyCodes.KEY_ESC) }
                                KeyButton("Tab", Modifier.weight(1f)) { session?.sendKeyStroke(HidKeyCodes.KEY_TAB) }
                                KeyButton("Enter", Modifier.weight(1.1f)) { session?.sendKeyStroke(HidKeyCodes.KEY_ENTER) }
                                KeyButton("⌫ Del", Modifier.weight(1.1f)) { session?.sendKeyStroke(HidKeyCodes.KEY_BACKSPACE) }
                            }
                        }
                    }
                }
            }
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
        color = Color.White.copy(alpha = 0.12f),
        modifier = modifier.height(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 11.sp
            )
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
        var pressStartTime = 0L
        var releaseJob: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    releaseJob?.cancel()
                    pressStartTime = SystemClock.uptimeMillis()
                    onPressDown()
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val elapsed = SystemClock.uptimeMillis() - pressStartTime
                    releaseJob = launch {
                        // Ensure short tap holds for at least 30ms (enough for 8ms loop ticks)
                        // but don't extend drag-holds that already exceeded 30ms
                        if (elapsed < 30L) {
                            delay(30L - elapsed)
                        }
                        onPressUp()
                    }
                }
            }
        }
    }

    Surface(
        interactionSource = interactionSource,
        onClick = {},
        shape = RoundedCornerShape(8.dp),
        color = if (isHeld) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.12f),
        modifier = modifier.fillMaxHeight()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isHeld) FontWeight.Bold else FontWeight.Medium,
                color = if (isHeld) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

private fun Modifier.touchpadGestures(
    sensitivity: Float,
    onMove: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onScroll: (Int, Int) -> Unit
): Modifier = this.pointerInput(sensitivity) {
    var accumulatedDx = 0f
    var accumulatedDy = 0f
    var accumulatedWheel = 0f
    var accumulatedPan = 0f

    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        val startTime = SystemClock.uptimeMillis()
        var isTwoFinger = false
        var isMoved = false
        var longPressFired = false
        var hasLastTwoFingerCenter = false
        var lastTwoFingerCenter = Offset.Zero
        val initialDownPos = firstDown.position

        accumulatedDx = 0f
        accumulatedDy = 0f
        accumulatedWheel = 0f
        accumulatedPan = 0f

        while (true) {
            val elapsed = SystemClock.uptimeMillis() - startTime
            val timeout = if (!isMoved && !isTwoFinger && !longPressFired && elapsed < 500L) {
                500L - elapsed
            } else {
                Long.MAX_VALUE
            }

            val event = if (timeout < Long.MAX_VALUE) {
                withTimeoutOrNull(timeout) {
                    awaitPointerEvent()
                }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                // 500ms timeout expired while stationary: fire long-press at the threshold
                if (!isMoved && !isTwoFinger && !longPressFired) {
                    longPressFired = true
                    onLongPress()
                }
                continue
            }

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
                if (!isTwoFinger && !isMoved && !longPressFired) {
                    onTap()
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

                    // Carry fractional remainders across events so slow 2-finger scroll is not lost
                    accumulatedWheel += deltaY * -0.2f * sensitivity
                    accumulatedPan += deltaX * 0.2f * sensitivity

                    val sendWheel = accumulatedWheel.roundToInt()
                    val sendPan = accumulatedPan.roundToInt()
                    if (sendWheel != 0 || sendPan != 0) {
                        accumulatedWheel -= sendWheel
                        accumulatedPan -= sendPan
                        onScroll(sendWheel, sendPan)
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
                val distFromDownSq = (change.position.x - initialDownPos.x) * (change.position.x - initialDownPos.x) +
                                     (change.position.y - initialDownPos.y) * (change.position.y - initialDownPos.y)
                if (distFromDownSq > 16f) { // ~4px threshold
                    isMoved = true
                }

                accumulatedDx += delta.x * sensitivity
                accumulatedDy += delta.y * sensitivity

                val sendX = accumulatedDx.roundToInt()
                val sendY = accumulatedDy.roundToInt()

                if (sendX != 0 || sendY != 0) {
                    accumulatedDx -= sendX
                    accumulatedDy -= sendY
                    onMove(sendX, sendY)
                }
                change.consume()
            }
        }
    }
}
