package xyz.normalwindow.htmlviewer.util

import android.content.Context
import android.content.res.Configuration
import xyz.normalwindow.htmlviewer.data.settings.AppLanguage
import java.util.Locale

/**
 * 应用内语言切换工具:
 * - [apply]:在 Activity.attachBaseContext 中把目标语言套到 Configuration 上,
 *   使资源(字符串/布局)按所选语言解析,切换后 Activity.recreate() 即时生效;
 * - 跟随系统(SYSTEM)时不改写,完全由系统语言决定。
 */
object LocaleManager {

    /** 语言 → Locale(跟随系统时返回 null) */
    private fun localeOf(language: AppLanguage): Locale? =
        language.localeTag?.let { Locale.forLanguageTag(it) }

    /**
     * 返回套用目标语言后的 Context(仅资源层改变,Activity 本身不变)。
     * 传入 SYSTEM 时原样返回,不产生额外包装。
     */
    fun apply(context: Context, language: AppLanguage): Context {
        val locale = localeOf(language) ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
