package io.github.rhythmcache.dioxamine.adb.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R

/**
 * Termux-styled extra keys toolbar.
 *
 * Emulates Termux's ExtraKeysView with flat rectangular keys, monospace typography,
 * modifier state highlighting (CTRL, ALT in theme primary accent), and common shell symbols.
 */
@Composable
fun ShellToolbar(
    sessionState: ShellSessionState,
    ctrlActive: Boolean,
    altActive: Boolean = false,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit = {},
    onEsc: () -> Unit = {},
    onTab: () -> Unit = {},
    onInterrupt: () -> Unit = {},
    onEof: () -> Unit = {},
    onChar: (String) -> Unit = {},
    onArrowUp: () -> Unit = {},
    onArrowDown: () -> Unit = {},
    onArrowLeft: () -> Unit = {},
    onArrowRight: () -> Unit = {},
    onToggleKeyboard: () -> Unit = {},
    onClear: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = sessionState == ShellSessionState.ACTIVE

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Horizontally scrolling Termux-style keys
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TermuxKeyButton(label = "ESC", onClick = onEsc, enabled = enabled)
                TermuxKeyButton(label = "TAB", onClick = onTab, enabled = enabled)
                TermuxKeyButton(
                    label = "CTRL",
                    onClick = onToggleCtrl,
                    enabled = enabled,
                    active = ctrlActive,
                )
                TermuxKeyButton(
                    label = "ALT",
                    onClick = onToggleAlt,
                    enabled = enabled,
                    active = altActive,
                )
                TermuxKeyButton(label = "-", onClick = { onChar("-") }, enabled = enabled)
                TermuxKeyButton(label = "/", onClick = { onChar("/") }, enabled = enabled)
                TermuxKeyButton(label = "|", onClick = { onChar("|") }, enabled = enabled)
                TermuxKeyButton(label = "~", onClick = { onChar("~") }, enabled = enabled)
                TermuxKeyButton(label = "^C", onClick = onInterrupt, enabled = enabled)
                TermuxKeyButton(label = "^D", onClick = onEof, enabled = enabled)
                TermuxKeyButton(label = "▲", onClick = onArrowUp, enabled = enabled)
                TermuxKeyButton(label = "▼", onClick = onArrowDown, enabled = enabled)
                TermuxKeyButton(label = "◀", onClick = onArrowLeft, enabled = enabled)
                TermuxKeyButton(label = "▶", onClick = onArrowRight, enabled = enabled)
            }

            Spacer(Modifier.width(4.dp))

            // Utility action buttons
            IconButton(
                onClick = onToggleKeyboard,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Filled.Keyboard,
                    contentDescription = "Toggle Keyboard",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(
                onClick = onClear,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.btn_clear),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (sessionState == ShellSessionState.CLOSED || sessionState == ShellSessionState.ERROR) {
                IconButton(
                    onClick = onRestart,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Filled.RestartAlt,
                        contentDescription = stringResource(R.string.cd_restart_shell),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * A single key button styled after Termux's ExtraKeysView:
 * flat rectangular key tile with subtle corners, monospace uppercase text,
 * and high-contrast theme accent highlighting when active.
 */
@Composable
private fun TermuxKeyButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val keyShape = RoundedCornerShape(4.dp)
    val containerColor = when {
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        active -> MaterialTheme.colorScheme.onPrimary
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .height(34.dp)
            .defaultMinSize(minWidth = 38.dp)
            .clip(keyShape)
            .background(containerColor)
            .then(
                if (active) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, keyShape)
                } else {
                    Modifier.border(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        keyShape,
                    )
                }
            )
            .clickable(
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            color = contentColor,
        )
    }
}
