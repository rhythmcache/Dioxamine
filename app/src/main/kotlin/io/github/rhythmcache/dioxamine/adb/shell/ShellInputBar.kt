package io.github.rhythmcache.dioxamine.adb.shell

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R

/**
 * Command input bar for the terminal.
 *
 * - Enter (soft or hard keyboard) submits the command.
 * - ↑ / ↓ arrows navigate command history.
 * - Intercepts typed letters when [ctrlActive] is true to send control bytes.
 * - Disabled when the session is not active.
 */
@Composable
fun ShellInputBar(
    onSend: (String) -> Unit,
    onHistoryUp: () -> String?,
    onHistoryDown: () -> String?,
    onRawKey: (ByteArray) -> Unit,
    ctrlActive: Boolean,
    onCtrlConsumed: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val monoFamily = remember {
        val tf = Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")
        FontFamily(tf)
    }

    var text by remember { mutableStateOf("") }

    val submit = {
        if (text.isNotEmpty() || enabled) {
            onSend(text)
            text = ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // -- Prompt symbol ------------------------------------------
        Text(
            text = "$",
            color = Color(0xFF8AE234),
            fontFamily = monoFamily,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 6.dp),
        )

        // -- Input field --------------------------------------------
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                if (ctrlActive && newText.length > text.length) {
                    val typedChar = newText.last()
                    if (typedChar.isLetter()) {
                        val ctrlByte = (typedChar.uppercaseChar().code - 'A'.code + 1).toByte()
                        onRawKey(byteArrayOf(ctrlByte))
                        onCtrlConsumed()
                        return@OutlinedTextField
                    }
                }
                text = newText
            },
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    if (ctrlActive) {
                        val ch = event.utf16CodePoint.takeIf { it in 'a'.code..'z'.code || it in 'A'.code..'Z'.code }
                        if (ch != null) {
                            val letter = ch.toChar().uppercaseChar()
                            val ctrlByte = (letter.code - 'A'.code + 1).toByte()
                            onRawKey(byteArrayOf(ctrlByte))
                            onCtrlConsumed()
                            return@onPreviewKeyEvent true
                        }
                    }

                    when (event.key) {
                        Key.DirectionUp -> {
                            onHistoryUp()?.let { text = it }
                            true
                        }
                        Key.DirectionDown -> {
                            onHistoryDown()?.let { text = it }
                            true
                        }
                        Key.Enter -> {
                            submit()
                            true
                        }
                        else -> false
                    }
                },
            enabled = enabled,
            singleLine = true,
            placeholder = {
                Text(
                    "Enter command…",
                    fontFamily = monoFamily,
                    fontSize = 13.sp,
                )
            },
            textStyle = TextStyle(
                fontFamily = monoFamily,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                cursorColor = Color(0xFF8AE234),
            ),
        )

        Spacer(Modifier.width(6.dp))

        // -- Send button --------------------------------------------
        IconButton(
            onClick = { submit() },
            enabled = enabled,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.cd_send),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
