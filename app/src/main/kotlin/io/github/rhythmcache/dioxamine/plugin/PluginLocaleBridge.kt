package io.github.rhythmcache.dioxamine.plugin

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.text.TextUtilsCompat
import io.github.rhythmcache.dioxamine.settings.supportedLanguages
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class PluginLocaleInfo(
    val language: String,
    val languageTag: String,
    val isRtl: Boolean,
    val displayName: String,
)

fun getEffectiveAppLocale(context: Context): Locale {
    val appLocales = AppCompatDelegate.getApplicationLocales()
    if (!appLocales.isEmpty) {
        val appLocale = appLocales[0]
        if (appLocale != null) return appLocale
    }
    val configLocales = ConfigurationCompat.getLocales(context.resources.configuration)
    return if (!configLocales.isEmpty) {
        configLocales[0] ?: Locale.getDefault()
    } else {
        Locale.getDefault()
    }
}

fun getPluginLocaleInfo(context: Context): PluginLocaleInfo {
    val locale = getEffectiveAppLocale(context)
    val language = if (locale.language.isNotBlank()) locale.language else "en"
    val rawTag = runCatching { locale.toLanguageTag() }.getOrNull()
    val languageTag = if (rawTag.isNullOrBlank() || rawTag.equals("und", ignoreCase = true)) {
        language
    } else {
        rawTag
    }
    val isRtl = TextUtilsCompat.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL

    // 1. Exact match on full language tag (e.g. "zh-CN", "ru", "en")
    // 2. Match on base language only if the supported option is regional-agnostic (e.g. "en" matches "en-US", but "zh-CN" won't falsely match "zh-TW")
    val matched = supportedLanguages.find { option ->
        option.languageTag != null && option.languageTag.equals(languageTag, ignoreCase = true)
    } ?: supportedLanguages.find { option ->
        option.languageTag != null && !option.languageTag.contains("-") && option.languageTag.equals(language, ignoreCase = true)
    }

    val displayName = if (matched != null) {
        runCatching { context.getString(matched.nameRes) }.getOrNull() ?: locale.getDisplayName(locale)
    } else {
        val name = if (locale.language.isNotBlank()) locale.getDisplayName(locale) else ""
        if (name.isNotBlank()) {
            name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        } else {
            if (language == "en") "English" else languageTag
        }
    }

    return PluginLocaleInfo(
        language = language,
        languageTag = languageTag,
        isRtl = isRtl,
        displayName = displayName,
    )
}

fun buildLocaleInjectionScript(localeInfo: PluginLocaleInfo): String {
    val encodedJson = Json.encodeToString(localeInfo)
    val langTag = localeInfo.languageTag
    val dirAttr = if (localeInfo.isRtl) "rtl" else "ltr"
    return """
        (function() {
            var info = $encodedJson;
            window.__dioxamine_locale_info = info;
            if (document.documentElement) {
                document.documentElement.setAttribute('lang', '$langTag');
                document.documentElement.setAttribute('dir', '$dirAttr');
                document.documentElement.setAttribute('data-dioxamine-lang', '$langTag');
            }
            if (window.__dioxamine_language_listener) {
                try {
                    window.__dioxamine_language_listener(info);
                } catch(e) {}
            }
            try {
                window.dispatchEvent(new CustomEvent('dioxamine-language-change', { detail: info }));
            } catch(e) {}
        })();
    """.trimIndent()
}
