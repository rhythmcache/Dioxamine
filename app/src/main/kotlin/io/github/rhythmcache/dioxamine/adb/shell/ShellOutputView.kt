package io.github.rhythmcache.dioxamine.adb.shell

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R

/** Dark background for the terminal surface. */
val TerminalBackground = Color(0xFF0D1117)

/** Default text colour when no ANSI colour is active. */
val TerminalDefaultText = Color(0xFFCCCCCC)

/**
 * Renders terminal output as a scrollable list of ANSI-parsed lines.
 *
 * - Auto-scrolls to the bottom when new output arrives (if already at bottom).
 * - Shows a small FAB to jump back to bottom when the user scrolls up.
 */
@Composable
fun ShellOutputView(
    completedLines: List<String>,
    currentLine: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val monoFamily = remember {
        val tf = Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")
        FontFamily(tf)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var followBottom by remember { mutableStateOf(true) }

    val totalCount = completedLines.size + if (currentLine.isNotEmpty()) 1 else 0

    // User-initiated scroll away from bottom turns off auto-follow.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val atBottom = lastVisible >= (info.totalItemsCount - 2).coerceAtLeast(0)
                    followBottom = atBottom
                }
            }
    }

    LaunchedEffect(completedLines.size, currentLine) {
        if (totalCount > 0 && followBottom) {
            listState.scrollToItem(totalCount - 1)
        }
    }

    Box(modifier = modifier.background(TerminalBackground)) {
        if (totalCount == 0) {
            // Subtle hint when the terminal is empty
            Text(
                text = stringResource(R.string.shell_live_hint),
                color = Color(0xFF484F58),
                fontFamily = monoFamily,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            // small content padding at bottom so text doesn't touch the toolbar
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(
                count = completedLines.size,
                // Use index as key - stable enough, avoids content hashing
                key = { it },
            ) { index ->
                val raw = completedLines[index]
                // Cache the parsed AnnotatedString per raw line string
                val parsed: AnnotatedString = remember(raw) {
                    AnsiParser.parse(raw, TerminalDefaultText)
                }

                Text(
                    text = parsed,
                    fontFamily = monoFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (currentLine.isNotEmpty()) {
                item(key = "current-line") {
                    val parsed: AnnotatedString = remember(currentLine) {
                        AnsiParser.parse(currentLine, TerminalDefaultText)
                    }

                    Text(
                        text = parsed,
                        fontFamily = monoFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // -- Scroll-to-bottom FAB -----------------------------------
        if (!followBottom && totalCount > 5) {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(totalCount - 1)
                        followBottom = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(36.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
