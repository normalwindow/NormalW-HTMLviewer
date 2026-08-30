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
import xyz.normalwindow.htmlviewer.data.cloud.CloudProviderType
import xyz.normalwindow.htmlviewer.data.cloud.SyncConflictPolicy
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
    /** 文件排序方式(name/time/size/type,见 HomeViewModel.SortMode) */
    val sortMode: String = "name",
    /** 文件排序方向:true=升序,false=降序 */
    val sortAscending: Boolean = true,
    /** 自定义主题色种子 ARGB(可空,null=跟随动态取色/默认) */
    val customColorSeed: Long? = null,
    /** 配色方案(8 种 Material3 色调方案,见 ColorStyle) */
    val colorStyle: ColorStyle = ColorStyle.SYSTEM,
    /** 应用界面语言(跟随系统 / 简体中文 / English) */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** SAF 授权的外部目录树 URI(可空,Phase 3 使用) */
    val externalRootUri: String? = null,
    /** 当前活动云盘(云本地切换/云盘切换的数据源,none=未启用) */
    val cloudProvider: CloudProviderType = CloudProviderType.NONE,
    /** 百度开放平台凭据(空 = 使用 BuildConfig 默认值) */
    val baiduAppKey: String = "",
    val baiduSecretKey: String = "",
    /** 百度 OAuth 令牌(null = 未登录) */
    val baiduAccessToken: String? = null,
    val baiduRefreshToken: String? = null,
    /** 百度令牌过期时间(epoch 毫秒,0 = 未登录) */
    val baiduTokenExpiresAt: Long = 0L,
    /** 百度远端根目录(空 = 使用默认 /apps/HTMLviewer;同步与云端浏览的根) */
    val baiduRemoteRoot: String = "",
    /** WebDAV 配置 */
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
    /** WebDAV 远端根目录(空 = 使用默认 /NormalW-HTMLviewer) */
    val webdavDir: String = "",
    /** 双向同步冲突策略(默认每次询问) */
    val syncConflictPolicy: SyncConflictPolicy = SyncConflictPolicy.ASK,
    /** 云端文件的本地修改保存后自动上传 */
    val syncAutoUpload: Boolean = true,
    /** 打开应用时自动执行一次双向同步 */
    val syncOnStart: Boolean = false,
    /** 各云盘上次完整同步时间(epoch 毫秒,0 = 从未同步) */
    val lastSyncBaidu: Long = 0L,
    val lastSyncWebdav: Long = 0L
) {
    /** 活动云盘的上次同步时间 */
    val lastSyncAt: Long
        get() = when (cloudProvider) {
            CloudProviderType.BAIDU -> lastSyncBaidu
            CloudProviderType.WEBDAV -> lastSyncWebdav
            CloudProviderType.NONE -> 0L
        }
}

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
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
        val CUSTOM_COLOR_SEED = longPreferencesKey("custom_color_seed")
        val COLOR_STYLE = stringPreferencesKey("color_style")
        val LANGUAGE = stringPreferencesKey("language")
        val EXTERNAL_ROOT_URI = stringPreferencesKey("external_root_uri")
        val CLOUD_PROVIDER = stringPreferencesKey("cloud_provider")
        val BAIDU_APP_KEY = stringPreferencesKey("baidu_app_key")
        val BAIDU_SECRET_KEY = stringPreferencesKey("baidu_secret_key")
        val BAIDU_ACCESS_TOKEN = stringPreferencesKey("baidu_access_token")
        val BAIDU_REFRESH_TOKEN = stringPreferencesKey("baidu_refresh_token")
        val BAIDU_TOKEN_EXPIRES_AT = longPreferencesKey("baidu_token_expires_at")
        val BAIDU_REMOTE_ROOT = stringPreferencesKey("baidu_remote_root")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        val WEBDAV_DIR = stringPreferencesKey("webdav_dir")
        val SYNC_CONFLICT_POLICY = stringPreferencesKey("sync_conflict_policy")
        val SYNC_AUTO_UPLOAD = booleanPreferencesKey("sync_auto_upload")
        val SYNC_ON_START = booleanPreferencesKey("sync_on_start")
        val LAST_SYNC_BAIDU = longPreferencesKey("last_sync_baidu")
        val LAST_SYNC_WEBDAV = longPreferencesKey("last_sync_webdav")
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
            sortMode = p[Keys.SORT_MODE] ?: "name",
            sortAscending = p[Keys.SORT_ASCENDING] ?: true,
            customColorSeed = p[Keys.CUSTOM_COLOR_SEED],
            colorStyle = ColorStyle.fromStorage(p[Keys.COLOR_STYLE] ?: ColorStyle.SYSTEM.storageValue),
            language = AppLanguage.fromStorage(p[Keys.LANGUAGE] ?: AppLanguage.SYSTEM.storageValue),
            externalRootUri = p[Keys.EXTERNAL_ROOT_URI],
            cloudProvider = CloudProviderType.fromStorage(p[Keys.CLOUD_PROVIDER]),
            baiduAppKey = p[Keys.BAIDU_APP_KEY] ?: "",
            baiduSecretKey = p[Keys.BAIDU_SECRET_KEY] ?: "",
            baiduAccessToken = p[Keys.BAIDU_ACCESS_TOKEN],
            baiduRefreshToken = p[Keys.BAIDU_REFRESH_TOKEN],
            baiduTokenExpiresAt = p[Keys.BAIDU_TOKEN_EXPIRES_AT] ?: 0L,
            baiduRemoteRoot = p[Keys.BAIDU_REMOTE_ROOT] ?: "",
            webdavUrl = p[Keys.WEBDAV_URL] ?: "",
            webdavUsername = p[Keys.WEBDAV_USERNAME] ?: "",
            webdavPassword = p[Keys.WEBDAV_PASSWORD] ?: "",
            webdavDir = p[Keys.WEBDAV_DIR] ?: "",
            syncConflictPolicy = SyncConflictPolicy.fromStorage(p[Keys.SYNC_CONFLICT_POLICY]),
            syncAutoUpload = p[Keys.SYNC_AUTO_UPLOAD] ?: true,
            syncOnStart = p[Keys.SYNC_ON_START] ?: false,
            lastSyncBaidu = p[Keys.LAST_SYNC_BAIDU] ?: 0L,
            lastSyncWebdav = p[Keys.LAST_SYNC_WEBDAV] ?: 0L
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

    suspend fun setSortMode(mode: String) {
        context.settingsDataStore.edit { it[Keys.SORT_MODE] = mode }
    }

    suspend fun setSortAscending(ascending: Boolean) {
        context.settingsDataStore.edit { it[Keys.SORT_ASCENDING] = ascending }
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

    /** 切换活动云盘(云盘切换;none=关闭云端功能) */
    suspend fun setCloudProvider(type: CloudProviderType) {
        context.settingsDataStore.edit { it[Keys.CLOUD_PROVIDER] = type.storageValue }
    }

    suspend fun setBaiduKeys(appKey: String, secretKey: String) {
        context.settingsDataStore.edit { p ->
            p[Keys.BAIDU_APP_KEY] = appKey.trim()
            p[Keys.BAIDU_SECRET_KEY] = secretKey.trim()
        }
    }

    /** 保存/清除百度令牌(null = 退出登录) */
    suspend fun setBaiduTokens(accessToken: String?, refreshToken: String?, expiresAt: Long) {
        context.settingsDataStore.edit { p ->
            if (accessToken == null || refreshToken == null) {
                p.remove(Keys.BAIDU_ACCESS_TOKEN)
                p.remove(Keys.BAIDU_REFRESH_TOKEN)
                p.remove(Keys.BAIDU_TOKEN_EXPIRES_AT)
            } else {
                p[Keys.BAIDU_ACCESS_TOKEN] = accessToken
                p[Keys.BAIDU_REFRESH_TOKEN] = refreshToken
                p[Keys.BAIDU_TOKEN_EXPIRES_AT] = expiresAt
            }
        }
    }

    /** 设置百度远端根目录(空 = 使用默认;切换云盘根后建议清空对应同步快照) */
    suspend fun setBaiduRemoteRoot(root: String) {
        context.settingsDataStore.edit { p ->
            val trimmed = root.trim()
            if (trimmed.isBlank()) p.remove(Keys.BAIDU_REMOTE_ROOT)
            else p[Keys.BAIDU_REMOTE_ROOT] = trimmed
        }
    }

    suspend fun setWebdavConfig(url: String, username: String, password: String, dir: String) {
        context.settingsDataStore.edit { p ->
            p[Keys.WEBDAV_URL] = url.trim()
            p[Keys.WEBDAV_USERNAME] = username.trim()
            p[Keys.WEBDAV_PASSWORD] = password
            p[Keys.WEBDAV_DIR] = dir.trim()
        }
    }

    suspend fun setSyncConflictPolicy(policy: SyncConflictPolicy) {
        context.settingsDataStore.edit { it[Keys.SYNC_CONFLICT_POLICY] = policy.storageValue }
    }

    suspend fun setSyncAutoUpload(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SYNC_AUTO_UPLOAD] = enabled }
    }

    suspend fun setSyncOnStart(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SYNC_ON_START] = enabled }
    }

    suspend fun setLastSyncAt(type: CloudProviderType, timeMs: Long) {
        if (type == CloudProviderType.NONE) return
        context.settingsDataStore.edit { p ->
            p[if (type == CloudProviderType.BAIDU) Keys.LAST_SYNC_BAIDU else Keys.LAST_SYNC_WEBDAV] = timeMs
        }
    }
}
