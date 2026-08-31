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
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R

/**
 * Compact toolbar above the input bar with Ctrl toggle, Tab, Clear, and Restart.
 */
@Composable
fun ShellToolbar(
    sessionState: ShellSessionState,
    ctrlActive: Boolean,
    onToggleCtrl: () -> Unit,
    onTab: () -> Unit,
    onClear: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = sessionState == ShellSessionState.ACTIVE

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyPill(
                label = "Ctrl",
                onClick = onToggleCtrl,
                enabled = enabled,
                active = ctrlActive,
            )
            KeyPill("Tab", onClick = onTab, enabled = enabled)
        }

        Spacer(Modifier.width(4.dp))

        IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = stringResource(R.string.btn_clear),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (sessionState == ShellSessionState.CLOSED || sessionState == ShellSessionState.ERROR) {
            IconButton(onClick = onRestart, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = stringResource(R.string.cd_restart_shell),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun KeyPill(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .border(
                width = 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
        )
    }
}
