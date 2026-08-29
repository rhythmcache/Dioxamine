package io.github.rhythmcache.dioxamine.core

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.rhythmcache.dioxamine.R

enum class AppTheme(@StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    AMOLED(R.string.theme_amoled)
}

val DioxamineDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8ED99C),
    onPrimary = Color(0xFF003916),
    primaryContainer = Color(0xFF23512E),
    onPrimaryContainer = Color(0xFFA9F5B7),
    inversePrimary = Color(0xFF1E6C37),
    secondary = Color(0xFFB3CCB6),
    onSecondary = Color(0xFF1F3525),
    secondaryContainer = Color(0xFF354B3A),
    onSecondaryContainer = Color(0xFFCFE8D1),
    tertiary = Color(0xFF9ED2DC),
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF1E4D57),
    onTertiaryContainer = Color(0xFFBAEEF9),
    background = Color(0xFF101512),
    onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF404941),
    onSurfaceVariant = Color(0xFFC0C9BF),
    surfaceContainerLowest = Color(0xFF0B0F0C),
    surfaceContainerLow = Color(0xFF141915),
    surfaceContainer = Color(0xFF18201A),
    surfaceContainerHigh = Color(0xFF222B24),
    surfaceContainerHighest = Color(0xFF2D362F),
    outline = Color(0xFF8A938A),
    outlineVariant = Color(0xFF404941)
)

val DioxamineLightColorScheme = lightColorScheme(
    primary = Color(0xFF1E6C37),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9F5B7),
    onPrimaryContainer = Color(0xFF00210A),
    inversePrimary = Color(0xFF8ED99C),
    secondary = Color(0xFF4D6351),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE8D1),
    onSecondaryContainer = Color(0xFF0B1F11),
    tertiary = Color(0xFF39656E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBAEEF9),
    onTertiaryContainer = Color(0xFF001F26),
    background = Color(0xFFF6FAF4),
    onBackground = Color(0xFF171D18),
    surface = Color(0xFFF6FAF4),
    onSurface = Color(0xFF171D18),
    surfaceVariant = Color(0xFFDCE5DA),
    onSurfaceVariant = Color(0xFF404941),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainer = Color(0xFFEBF0E8),
    surfaceContainerHigh = Color(0xFFE5EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD),
    outline = Color(0xFF707970),
    outlineVariant = Color(0xFFC0C9BF)
)

private fun ColorScheme.toAmoledScheme(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF070707),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF262626)
)

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun DioxamineTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    useMonet: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (appTheme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK, AppTheme.AMOLED -> true
    }

    val context = LocalContext.current
    val baseColorScheme = when {
        useMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DioxamineDarkColorScheme
        else -> DioxamineLightColorScheme
    }

    val colorScheme = if (appTheme == AppTheme.AMOLED) {
        baseColorScheme.toAmoledScheme()
    } else {
        baseColorScheme
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
