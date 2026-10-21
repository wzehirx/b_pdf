package io.github.wzehirx.bpdf.core.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.provider.Settings

/**
 * Per-app language. Below API 33 the system has no per-app language, so [isSupported] is false and
 * the app follows the system language (the settings screen points to the system language settings).
 */
object AppLanguage {
    /** Language tags the app is translated into; the empty tag means "follow the system". */
    val tags = listOf("", "en", "tr")

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The chosen tag from [tags], or "" when following the system. */
    fun current(context: Context): String {
        if (!isSupported) return ""
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return ""
        val language = locales[0].language
        return tags.firstOrNull { it == language } ?: ""
    }

    fun set(context: Context, tag: String) {
        if (!isSupported) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    fun systemLanguageSettingsIntent(): Intent =
        Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
