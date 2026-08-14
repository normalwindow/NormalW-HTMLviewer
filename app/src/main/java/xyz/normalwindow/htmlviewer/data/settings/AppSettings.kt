package xyz.normalwindow.htmlviewer.data.settings

/** 主题模式:跟随系统 / 浅色 / 深色 */
enum class ThemeMode(val storageValue: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromStorage(v: Int): ThemeMode = entries.firstOrNull { it.storageValue == v } ?: SYSTEM
    }
}

/** 渲染内核:轻量(系统 WebView)/ 兼容(GeckoView) */
enum class EngineType(val storageValue: String) {
    WEBVIEW("webview"),
    GECKO("gecko");

    companion object {
        fun fromStorage(v: String): EngineType =
            entries.firstOrNull { it.storageValue == v } ?: WEBVIEW
    }
}

/**
 * 配色方案:跟随动态取色 / 8 种 Material3 色调方案。
 * 色调方案由 Google material-color-utilities 算法生成,改变整套主题的色调走向,
 * 使更多界面元素(按钮/容器/高亮等)随主题色变化。
 */
enum class ColorStyle(val storageValue: String, val displayName: String) {
    /** 跟随系统动态取色(默认,Material You 壁纸配色) */
    SYSTEM("system", "System"),
    TONAL_SPOT("tonal_spot", "TonalSpot"),
    NEUTRAL("neutral", "Neutral"),
    VIBRANT("vibrant", "Vibrant"),
    EXPRESSIVE("expressive", "Expressive"),
    RAINBOW("rainbow", "Rainbow"),
    FRUIT_SALAD("fruit_salad", "FruitSalad"),
    MONOCHROME("monochrome", "Monochrome"),
    FIDELITY("fidelity", "Fidelity");

    companion object {
        fun fromStorage(v: String): ColorStyle =
            entries.firstOrNull { it.storageValue == v } ?: SYSTEM
    }
}

/** 应用界面语言:跟随系统 / 简体中文 / English */
enum class AppLanguage(val storageValue: String, val localeTag: String?) {
    SYSTEM("system", null),
    ZH("zh", "zh"),
    EN("en", "en");

    companion object {
        fun fromStorage(v: String): AppLanguage =
            entries.firstOrNull { it.storageValue == v } ?: SYSTEM
    }
}
