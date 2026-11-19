package com.wcam.app.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Manages app language preference (`system`, `tr`, `en`), supporting both
 * Android 13+ per-app LocaleManager settings and in-app configuration wrapping
 * on API 24-32, as well as localized context lookup for services and Android Auto.
 */
object AppLanguageManager {

    const val MODE_SYSTEM = "system"
    const val MODE_TR = "tr"
    const val MODE_EN = "en"

    private const val PREFS_NAME = "wcam_prefs"
    private const val KEY_LANGUAGE = "app_language"

    fun effectiveLanguageTag(savedMode: String, systemLanguage: String): String {
        return when (savedMode) {
            MODE_TR -> MODE_TR
            MODE_EN -> MODE_EN
            else -> if (systemLanguage.lowercase(Locale.ROOT).startsWith("tr")) MODE_TR else MODE_EN
        }
    }

    fun getSavedMode(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val appLocales = localeManager?.applicationLocales
            if (appLocales != null && !appLocales.isEmpty) {
                val firstTag = appLocales[0]?.language?.lowercase(Locale.ROOT).orEmpty()
                return if (firstTag.startsWith("tr")) MODE_TR else MODE_EN
            }
            return MODE_SYSTEM
        }
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, MODE_SYSTEM)
        return normalizeMode(raw)
    }

    fun getEffectiveLanguage(context: Context): String {
        return effectiveLanguageTag(getSavedMode(context), systemLocaleLanguage(context))
    }

    /**
     * Applies [mode] (`MODE_SYSTEM`, `MODE_TR`, `MODE_EN`).
     * Returns true if the caller should manually `recreate()` the Activity (API < 33 when language changed).
     */
    fun setLanguageMode(context: Context, mode: String): Boolean {
        val normalized = normalizeMode(mode)
        val systemLang = systemLocaleLanguage(context)
        val prevEffective = effectiveLanguageTag(getSavedMode(context), systemLang)
        val nextEffective = effectiveLanguageTag(normalized, systemLang)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val locales = when (normalized) {
                MODE_TR -> LocaleList.forLanguageTags("tr")
                MODE_EN -> LocaleList.forLanguageTags("en")
                else -> LocaleList.getEmptyLocaleList()
            }
            localeManager?.applicationLocales = locales
            return false
        }
        return prevEffective != nextEffective
    }

    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return base
        }
        val mode = getSavedMode(base)
        if (mode == MODE_SYSTEM) {
            return base
        }
        return createLocaleContext(base, mode)
    }

    fun localizedContext(context: Context): Context {
        val targetLang = getEffectiveLanguage(context)
        val currentLang = context.resources.configuration.locales[0]?.language?.lowercase(Locale.ROOT)
        if (currentLang == targetLang) {
            return context
        }
        return createLocaleContext(context, targetLang)
    }

    private fun createLocaleContext(context: Context, languageTag: String): Context {
        val locale = Locale(languageTag)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    private fun systemLocaleLanguage(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sysLocales = context.getSystemService(LocaleManager::class.java)?.systemLocales
            if (sysLocales != null && !sysLocales.isEmpty) {
                return sysLocales[0]?.language.orEmpty()
            }
        }
        return Resources.getSystem().configuration.locales[0]?.language
            ?: Locale.getDefault().language
    }

    private fun normalizeMode(mode: String?): String {
        return when (mode) {
            MODE_TR, MODE_EN, MODE_SYSTEM -> mode
            else -> MODE_SYSTEM
        }
    }
}
