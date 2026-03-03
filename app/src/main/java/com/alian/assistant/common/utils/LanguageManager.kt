package com.alian.assistant.common.utils

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 语言管理器
 * 支持应用内语言切换
 */
object LanguageManager {

    const val PREFS_NAME = "language_settings"
    const val KEY_LANGUAGE = "selected_language"

    // 支持的语言列表
    val SUPPORTED_LANGUAGES = listOf(
        Language("system", "跟随系统", "Follow System", null),
        Language("zh", "简体中文", "Simplified Chinese", Locale.SIMPLIFIED_CHINESE),
        Language("zh-TW", "繁體中文", "Traditional Chinese", Locale.TRADITIONAL_CHINESE),
        Language("en", "English", "English", Locale.ENGLISH),
        Language("ja", "日本語", "Japanese", Locale.JAPANESE),
        Language("ko", "한국어", "Korean", Locale.KOREAN)
    )

    data class Language(
        val code: String,
        val displayNameZh: String,  // 中文名称
        val displayNameEn: String,  // 英文名称
        val locale: Locale?
    ) {
        /**
         * 根据当前语言返回显示名称
         */
        fun getDisplayName(currentLocale: Locale): String {
            return when {
                currentLocale.language == "zh" -> displayNameZh
                else -> displayNameEn
            }
        }
    }

    /**
     * 获取当前选择的语言代码
     */
    fun getSelectedLanguageCode(context: Context?): String {
        if (context == null) return "system"
        return try {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        } catch (e: Exception) {
            "system"
        }
    }

    /**
     * 设置语言
     */
    fun setLanguage(context: Context, languageCode: String) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    /**
     * 获取当前应用应使用的 Locale
     */
    fun getAppLocale(context: Context?): Locale {
        val languageCode = getSelectedLanguageCode(context)

        if (languageCode == "system" || context == null) {
            // 跟随系统
            return Locale.getDefault()
        }

        // 查找对应的语言
        return SUPPORTED_LANGUAGES.find { it.code == languageCode }?.locale ?: Locale.getDefault()
    }

    /**
     * 应用语言设置到 Context
     * 注意：这个方法返回一个新的 Context，需要在 attachBaseContext 中使用
     */
    fun applyLanguage(context: Context?): Context? {
        if (context == null) return null
        
        val languageCode = getSelectedLanguageCode(context)
        
        // 如果是跟随系统，直接返回原 Context
        if (languageCode == "system") {
            return context
        }
        
        val locale = SUPPORTED_LANGUAGES.find { it.code == languageCode }?.locale 
            ?: return context
        
        // 设置默认 Locale
        Locale.setDefault(locale)
        
        val config = Configuration(context.resources.configuration)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * 切换语言并重启应用
     * @param activity 当前 Activity
     * @param languageCode 语言代码
     */
    fun changeLanguageAndRestart(activity: android.app.Activity, languageCode: String) {
        setLanguage(activity, languageCode)

        // 重启应用 - 不使用 killProcess，而是正常重启
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        activity.finish()
        
        // 兼容旧版本 Android
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * 获取当前选择的语言对象
     */
    fun getSelectedLanguage(context: Context): Language {
        val code = getSelectedLanguageCode(context)
        return SUPPORTED_LANGUAGES.find { it.code == code } ?: SUPPORTED_LANGUAGES[0]
    }
}
