package io.github.rhythmcache.dioxamine.settings

import androidx.annotation.StringRes
import io.github.rhythmcache.dioxamine.R

/**
 * Represents a selectable language option in the app settings.
 *
 * @property nameRes String resource ID for the localized language name.
 * @property languageTag BCP-47 language tag (e.g., "en", "es", "ru", "zh-CN"), or null for System Default.
 */
data class LanguageOption(
    @StringRes val nameRes: Int,
    val languageTag: String?
)

/**
 * List of languages supported in Dioxamine.
 *
 * --- HOW TO ADD A NEW LANGUAGE ---
 * 1. Add your translated strings file: `app/src/main/res/values-<locale>/strings.xml`
 * 2. Add your language's native name (endonym) in `app/src/main/res/values/strings.xml`:
 *      <string name="settings_language_<locale>" translatable="false">NativeName (e.g. Español, Русский, 简体中文)</string>
 *    Do NOT translate the language name in localized strings.xml files so each language is displayed in its own script.
 * 3. Add an entry below:
 *      LanguageOption(R.string.settings_language_<locale>, "<locale>"),
 */
val supportedLanguages = listOf(
    LanguageOption(R.string.settings_language_system_default, null),
    LanguageOption(R.string.settings_language_english, "en"),
    LanguageOption(R.string.settings_language_simplified_chinese, "zh-CN"),
    LanguageOption(R.string.settings_language_hindi, "hi"),
)
