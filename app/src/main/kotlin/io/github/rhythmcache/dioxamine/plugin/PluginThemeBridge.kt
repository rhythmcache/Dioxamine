package io.github.rhythmcache.dioxamine.plugin

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

fun Color.toCssHex(): String {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X%02X", r, g, b, a)
}

fun buildThemeCss(colorScheme: ColorScheme): String =
    """
    :root {
        --dioxamine-primary: ${colorScheme.primary.toCssHex()};
        --dioxamine-on-primary: ${colorScheme.onPrimary.toCssHex()};
        --dioxamine-primary-container: ${colorScheme.primaryContainer.toCssHex()};
        --dioxamine-on-primary-container: ${colorScheme.onPrimaryContainer.toCssHex()};
        --dioxamine-secondary: ${colorScheme.secondary.toCssHex()};
        --dioxamine-on-secondary: ${colorScheme.onSecondary.toCssHex()};
        --dioxamine-secondary-container: ${colorScheme.secondaryContainer.toCssHex()};
        --dioxamine-on-secondary-container: ${colorScheme.onSecondaryContainer.toCssHex()};
        --dioxamine-tertiary: ${colorScheme.tertiary.toCssHex()};
        --dioxamine-on-tertiary: ${colorScheme.onTertiary.toCssHex()};
        --dioxamine-tertiary-container: ${colorScheme.tertiaryContainer.toCssHex()};
        --dioxamine-on-tertiary-container: ${colorScheme.onTertiaryContainer.toCssHex()};
        --dioxamine-error: ${colorScheme.error.toCssHex()};
        --dioxamine-on-error: ${colorScheme.onError.toCssHex()};
        --dioxamine-error-container: ${colorScheme.errorContainer.toCssHex()};
        --dioxamine-on-error-container: ${colorScheme.onErrorContainer.toCssHex()};
        --dioxamine-background: ${colorScheme.background.toCssHex()};
        --dioxamine-on-background: ${colorScheme.onBackground.toCssHex()};
        --dioxamine-surface: ${colorScheme.surface.toCssHex()};
        --dioxamine-on-surface: ${colorScheme.onSurface.toCssHex()};
        --dioxamine-surface-variant: ${colorScheme.surfaceVariant.toCssHex()};
        --dioxamine-on-surface-variant: ${colorScheme.onSurfaceVariant.toCssHex()};
        --dioxamine-outline: ${colorScheme.outline.toCssHex()};
        --dioxamine-outline-variant: ${colorScheme.outlineVariant.toCssHex()};
        --dioxamine-inverse-surface: ${colorScheme.inverseSurface.toCssHex()};
        --dioxamine-inverse-on-surface: ${colorScheme.inverseOnSurface.toCssHex()};
        --dioxamine-inverse-primary: ${colorScheme.inversePrimary.toCssHex()};
        --dioxamine-surface-tint: ${colorScheme.surfaceTint.toCssHex()};
        --dioxamine-scrim: ${colorScheme.scrim.toCssHex()};

        --dioxamine-bg: var(--dioxamine-background);
        --dioxamine-fg: var(--dioxamine-on-background);
        --dioxamine-card-bg: var(--dioxamine-surface);
        --dioxamine-card-fg: var(--dioxamine-on-surface);
        --dioxamine-accent: var(--dioxamine-primary);
        --dioxamine-danger: var(--dioxamine-error);
    }

    *, *::before, *::after {
        box-sizing: border-box;
    }
    """.trimIndent()

fun buildThemeInjectionScript(colorScheme: ColorScheme, isDark: Boolean): String {
    val cssString = buildThemeCss(colorScheme)
    val encodedCss = Json.encodeToString(String.serializer(), cssString)
    val darkAttr = if (isDark) "dark" else "light"
    return """
        (function() {
            var css = $encodedCss;
            var target = document.head || document.documentElement;
            if (!target) return;
            var existing = document.getElementById('dioxamine-theme');
            if (existing) {
                existing.textContent = css;
            } else {
                var style = document.createElement('style');
                style.id = 'dioxamine-theme';
                style.textContent = css;
                target.appendChild(style);
            }
            if (document.documentElement) {
                document.documentElement.setAttribute('data-dioxamine-theme', '$darkAttr');
            }
            if (window.__dioxamine_theme_listener) { window.__dioxamine_theme_listener(); }
        })();
    """.trimIndent()
}
