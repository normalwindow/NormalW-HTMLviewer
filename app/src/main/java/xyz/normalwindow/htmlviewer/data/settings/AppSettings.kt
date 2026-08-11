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
