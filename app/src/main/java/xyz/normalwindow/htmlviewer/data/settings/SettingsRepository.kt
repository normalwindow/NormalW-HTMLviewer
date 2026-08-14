package xyz.normalwindow.htmlviewer.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings"
)

/** 用户偏好聚合(一次性读取快照) */
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultEngine: EngineType = EngineType.WEBVIEW,
    val editorFontSize: Float = 14f,
    val editorTabSize: Int = 4,
    val editorAutoSave: Boolean = true,
    val editorWrap: Boolean = false,
    /** 编辑器右侧自定义滚动条(vscode 风格,菜单可隐藏) */
    val editorScrollbar: Boolean = true,
    val fullscreenImmersive: Boolean = true,
    /** 浏览器/预览默认 UA 预设(见 UserAgentPreset) */
    val uaPreset: String = "DEFAULT",
    /** 浏览器/预览 JavaScript 开关 */
    val jsEnabled: Boolean = true,
    /** 浏览器控制台收集开关(工具栏抽屉查看日志/报错/警告) */
    val browserConsole: Boolean = true,
    /** 浏览器预览页右侧大滑动条(编辑器同款,菜单可切换) */
    val browserScrollbar: Boolean = true,
    /** 资源本地固化缓存开关(网络资源保存到 HTML 同目录隐藏文件夹,支持离线) */
    val resourceCacheEnabled: Boolean = true,
    /** Debug 模式:开启后记录应用内日志,可在设置页导出分析 */
    val debugMode: Boolean = false,
    /** 单击文件默认动作:true=浏览器预览,false=编辑器 */
    val clickOpensPreview: Boolean = true,
    /** 文件列表默认视图:true=网格,false=列表 */
    val gridView: Boolean = false,
    /** 自定义主题色种子 ARGB(可空,null=跟随动态取色/默认) */
    val customColorSeed: Long? = null,
    /** 配色方案(8 种 Material3 色调方案,见 ColorStyle) */
    val colorStyle: ColorStyle = ColorStyle.SYSTEM,
    /** 应用界面语言(跟随系统 / 简体中文 / English) */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** SAF 授权的外部目录树 URI(可空,Phase 3 使用) */
    val externalRootUri: String? = null
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEFAULT_ENGINE = stringPreferencesKey("default_engine")
        val EDITOR_FONT_SIZE = floatPreferencesKey("editor_font_size")
        val EDITOR_TAB_SIZE = intPreferencesKey("editor_tab_size")
        val EDITOR_AUTO_SAVE = booleanPreferencesKey("editor_auto_save")
        val EDITOR_WRAP = booleanPreferencesKey("editor_wrap")
        val EDITOR_SCROLLBAR = booleanPreferencesKey("editor_scrollbar")
        val FULLSCREEN_IMMERSIVE = booleanPreferencesKey("fullscreen_immersive")
        val UA_PRESET = stringPreferencesKey("ua_preset")
        val JS_ENABLED = booleanPreferencesKey("js_enabled")
        val BROWSER_CONSOLE = booleanPreferencesKey("browser_console")
        val BROWSER_SCROLLBAR = booleanPreferencesKey("browser_scrollbar")
        val RESOURCE_CACHE = booleanPreferencesKey("resource_cache")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val CLICK_OPENS_PREVIEW = booleanPreferencesKey("click_opens_preview")
        val GRID_VIEW = booleanPreferencesKey("grid_view")
        val CUSTOM_COLOR_SEED = longPreferencesKey("custom_color_seed")
        val COLOR_STYLE = stringPreferencesKey("color_style")
        val LANGUAGE = stringPreferencesKey("language")
        val EXTERNAL_ROOT_URI = stringPreferencesKey("external_root_uri")
    }

    val preferences: Flow<UserPreferences> = context.settingsDataStore.data.map { p ->
        UserPreferences(
            themeMode = ThemeMode.fromStorage(p[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.storageValue),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            defaultEngine = EngineType.fromStorage(
                p[Keys.DEFAULT_ENGINE] ?: EngineType.WEBVIEW.storageValue
            ),
            editorFontSize = p[Keys.EDITOR_FONT_SIZE] ?: 14f,
            editorTabSize = p[Keys.EDITOR_TAB_SIZE] ?: 4,
            editorAutoSave = p[Keys.EDITOR_AUTO_SAVE] ?: true,
            editorWrap = p[Keys.EDITOR_WRAP] ?: false,
            editorScrollbar = p[Keys.EDITOR_SCROLLBAR] ?: true,
            fullscreenImmersive = p[Keys.FULLSCREEN_IMMERSIVE] ?: true,
            uaPreset = p[Keys.UA_PRESET] ?: "DEFAULT",
            jsEnabled = p[Keys.JS_ENABLED] ?: true,
            browserConsole = p[Keys.BROWSER_CONSOLE] ?: true,
            browserScrollbar = p[Keys.BROWSER_SCROLLBAR] ?: true,
            resourceCacheEnabled = p[Keys.RESOURCE_CACHE] ?: true,
            debugMode = p[Keys.DEBUG_MODE] ?: false,
            clickOpensPreview = p[Keys.CLICK_OPENS_PREVIEW] ?: true,
            gridView = p[Keys.GRID_VIEW] ?: false,
            customColorSeed = p[Keys.CUSTOM_COLOR_SEED],
            colorStyle = ColorStyle.fromStorage(p[Keys.COLOR_STYLE] ?: ColorStyle.SYSTEM.storageValue),
            language = AppLanguage.fromStorage(p[Keys.LANGUAGE] ?: AppLanguage.SYSTEM.storageValue),
            externalRootUri = p[Keys.EXTERNAL_ROOT_URI]
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.storageValue }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setDefaultEngine(engine: EngineType) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_ENGINE] = engine.storageValue }
    }

    suspend fun setEditorFontSize(size: Float) {
        context.settingsDataStore.edit { it[Keys.EDITOR_FONT_SIZE] = size }
    }

    suspend fun setEditorTabSize(size: Int) {
        context.settingsDataStore.edit { it[Keys.EDITOR_TAB_SIZE] = size }
    }

    suspend fun setEditorAutoSave(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.EDITOR_AUTO_SAVE] = enabled }
    }

    suspend fun setEditorWrap(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.EDITOR_WRAP] = enabled }
    }

    suspend fun setEditorScrollbar(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.EDITOR_SCROLLBAR] = enabled }
    }

    suspend fun setFullscreenImmersive(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FULLSCREEN_IMMERSIVE] = enabled }
    }

    suspend fun setUaPreset(preset: String) {
        context.settingsDataStore.edit { it[Keys.UA_PRESET] = preset }
    }

    suspend fun setJsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.JS_ENABLED] = enabled }
    }

    suspend fun setBrowserConsole(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.BROWSER_CONSOLE] = enabled }
    }

    suspend fun setBrowserScrollbar(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.BROWSER_SCROLLBAR] = enabled }
    }

    suspend fun setResourceCacheEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.RESOURCE_CACHE] = enabled }
    }

    suspend fun setDebugMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DEBUG_MODE] = enabled }
    }

    suspend fun setClickOpensPreview(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CLICK_OPENS_PREVIEW] = enabled }
    }

    suspend fun setGridView(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GRID_VIEW] = enabled }
    }

    suspend fun setCustomColorSeed(seed: Long?) {
        context.settingsDataStore.edit { p ->
            if (seed == null) p.remove(Keys.CUSTOM_COLOR_SEED)
            else p[Keys.CUSTOM_COLOR_SEED] = seed
        }
    }

    suspend fun setColorStyle(style: ColorStyle) {
        context.settingsDataStore.edit { it[Keys.COLOR_STYLE] = style.storageValue }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { it[Keys.LANGUAGE] = language.storageValue }
    }

    suspend fun setExternalRootUri(uri: String?) {
        context.settingsDataStore.edit { p ->
            if (uri == null) p.remove(Keys.EXTERNAL_ROOT_URI) else p[Keys.EXTERNAL_ROOT_URI] = uri
        }
    }
}
