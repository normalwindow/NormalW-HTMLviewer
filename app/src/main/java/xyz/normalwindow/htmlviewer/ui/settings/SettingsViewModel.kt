package xyz.normalwindow.htmlviewer.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewCompat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.normalwindow.htmlviewer.BuildConfig
import xyz.normalwindow.htmlviewer.data.backup.DataBackup
import xyz.normalwindow.htmlviewer.data.cache.CacheLocation
import xyz.normalwindow.htmlviewer.data.cache.CacheStats
import xyz.normalwindow.htmlviewer.data.cache.ResourceCache
import xyz.normalwindow.htmlviewer.data.cloud.CloudFile
import xyz.normalwindow.htmlviewer.data.cloud.CloudManager
import xyz.normalwindow.htmlviewer.data.cloud.CloudProviderType
import xyz.normalwindow.htmlviewer.data.cloud.CloudSyncEngine
import xyz.normalwindow.htmlviewer.data.cloud.SyncSnapshotStore
import xyz.normalwindow.htmlviewer.data.cloud.SyncConflictPolicy
import xyz.normalwindow.htmlviewer.data.cloud.exchangeBaiduCode
import xyz.normalwindow.htmlviewer.data.cloud.WebDavProvider
import xyz.normalwindow.htmlviewer.data.debug.AppLog
import xyz.normalwindow.htmlviewer.data.debug.LogExporter
import xyz.normalwindow.htmlviewer.data.file.FileRootProvider
import xyz.normalwindow.htmlviewer.data.settings.AppLanguage
import xyz.normalwindow.htmlviewer.data.settings.ColorStyle
import xyz.normalwindow.htmlviewer.data.settings.EngineType
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import xyz.normalwindow.htmlviewer.data.settings.ThemeMode
import xyz.normalwindow.htmlviewer.data.update.UpdateChecker
import xyz.normalwindow.htmlviewer.data.update.UpdateInfo
import xyz.normalwindow.htmlviewer.data.update.isNewerVersion
import xyz.normalwindow.htmlviewer.render.UserAgentPreset
import xyz.normalwindow.htmlviewer.ui.cloud.SyncController
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultEngine: EngineType = EngineType.WEBVIEW,
    val editorFontSize: Float = 14f,
    val editorTabSize: Int = 4,
    val editorAutoSave: Boolean = true,
    val editorWrap: Boolean = false,
    val fullscreenImmersive: Boolean = true,
    /** 单击文件默认动作:true=浏览器预览,false=编辑器 */
    val clickOpensPreview: Boolean = true,
    /** 默认 UA 预设 */
    val uaPreset: UserAgentPreset = UserAgentPreset.DEFAULT,
    /** 浏览器 JavaScript 开关 */
    val jsEnabled: Boolean = true,
    /** 浏览器控制台收集开关 */
    val browserConsole: Boolean = true,
    /** 资源本地固化缓存开关 */
    val resourceCacheEnabled: Boolean = true,
    /** 缓存统计(未加载/为空时为 null) */
    val cacheStats: CacheStats? = null,
    /** 各缓存位置明细(设置页"选择清理"对话框数据源) */
    val cacheLocations: List<CacheLocation> = emptyList(),
    /** Debug 模式开关 */
    val debugMode: Boolean = false,
    /** 文件列表默认视图:true=网格 */
    val gridView: Boolean = false,
    /** 自定义主题色种子 ARGB(可空) */
    val customColorSeed: Long? = null,
    /** 配色方案(8 种 Material3 色调方案) */
    val colorStyle: ColorStyle = ColorStyle.SYSTEM,
    /** 应用界面语言 */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** 系统 WebView 包版本(如 135.0.7049.38) */
    val webViewVersion: String = "",
    /** GeckoView 版本号(从 BuildConfig 固定值读取) */
    val geckoVersion: String = "",
    /** 应用版本号(如 1.1.0) */
    val appVersion: String = "",
    /** 当前活动云盘(云盘切换) */
    val cloudProvider: CloudProviderType = CloudProviderType.NONE,
    /** 百度是否已登录(存在访问令牌) */
    val baiduLoggedIn: Boolean = false,
    /** 百度令牌过期时间(epoch 毫秒,0 = 未登录) */
    val baiduTokenExpiresAt: Long = 0L,
    /** 百度开放平台凭据(含 BuildConfig 默认值回填) */
    val baiduAppKey: String = "",
    val baiduSecretKey: String = "",
    /** 百度 OAuth 授权页 URL(WebView 加载) */
    val baiduAuthorizeUrl: String = "",
    /** 百度远端根目录(含默认值回填;同步与云端浏览的根) */
    val baiduRemoteRoot: String = "",
    /** WebDAV 配置 */
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
    val webdavDir: String = "",
    /** 同步设置 */
    val syncConflictPolicy: SyncConflictPolicy = SyncConflictPolicy.ASK,
    val syncAutoUpload: Boolean = true,
    val syncOnStart: Boolean = false,
    /** 活动云盘上次完整同步时间(epoch 毫秒,0 = 从未) */
    val lastSyncAt: Long = 0L
)

/** 更新检测结果(设置页"检查更新"对话框数据源) */
sealed interface UpdateUiState {
    /** 空闲(未检查) */
    data object Idle : UpdateUiState
    /** 检查中 */
    data object Checking : UpdateUiState
    /** 发现新版本 */
    data class Found(val info: UpdateInfo) : UpdateUiState
    /** 已是最新版本(当前版本号) */
    data class UpToDate(val currentVersion: String) : UpdateUiState
    /** 检查失败(网络/仓库无 Release 等) */
    data class Failed(val message: String) : UpdateUiState
}

/** WebDAV 测试连接结果(设置页提示) */
sealed interface WebDavTestResult {
    data object Success : WebDavTestResult
    data class Failed(val message: String) : WebDavTestResult
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val fileRootProvider: FileRootProvider,
    private val updateChecker: UpdateChecker,
    private val cloudManager: CloudManager,
    private val cloudSyncEngine: CloudSyncEngine,
    private val syncSnapshotStore: SyncSnapshotStore
) : ViewModel() {

    private val resourceCache = ResourceCache()

    /** 云同步流程(与主页共用控制器:进度状态 + 冲突决定收集器) */
    val sync = SyncController(
        context = context,
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        fileRootProvider = fileRootProvider,
        cloudManager = cloudManager,
        cloudSyncEngine = cloudSyncEngine,
        syncSnapshotStore = syncSnapshotStore
    )

    /** WebDAV 测试连接结果(null = 无进行中的测试) */
    private val webdavTestFlow = MutableStateFlow<WebDavTestResult?>(null)
    val webdavTest: StateFlow<WebDavTestResult?> = webdavTestFlow.asStateFlow()

    /** 更新检测结果(检查更新对话框数据源) */
    private val updateStateFlow = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = updateStateFlow.asStateFlow()

    /** 更新检测来源:主源 Atom(github.com 域,国内直连较稳定),备源 API(api.github.com) */
    private val atomUrl = "https://github.com/normalwindow/NormlW-HTMLviewer/releases.atom"
    private val downloadBaseUrl = "https://github.com/normalwindow/NormlW-HTMLviewer/releases/download/"
    private val apiUrl = "https://api.github.com/repos/normalwindow/NormlW-HTMLviewer/releases/latest"

    /**
     * 手动检查更新:主源 GitHub Releases Atom,失败回退官方 API,与当前版本比较。
     * 结果含完整 Release 信息(版本/说明/发布时间/下载直链)。
     */
    fun checkForUpdate() {
        if (updateStateFlow.value == UpdateUiState.Checking) return
        updateStateFlow.value = UpdateUiState.Checking
        viewModelScope.launch {
            val currentVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: ""
            updateStateFlow.value = runCatching {
                updateChecker.checkLatestAtom(atomUrl, downloadBaseUrl).getOrThrow()
            }.recoverCatching {
                android.util.Log.w("UpdateCheck", "Atom 源失败,回退 API: ${it.message}")
                updateChecker.checkLatestApi(apiUrl).getOrThrow()
            }.fold(
                onSuccess = { info ->
                    if (isNewerVersion(info.tagName, currentVersion)) {
                        UpdateUiState.Found(info)
                    } else {
                        UpdateUiState.UpToDate(currentVersion)
                    }
                },
                onFailure = {
                    android.util.Log.e("UpdateCheck", "检查更新失败: ${it.message}", it)
                    UpdateUiState.Failed(it.message ?: "network error")
                }
            )
        }
    }

    /** 当前发行版是否 Lite(按 applicationId 后缀判断) */
    val isLiteEdition: Boolean
        get() = BuildConfig.APPLICATION_ID.endsWith(".lite")

    /** 当前设备主 ABI(资产匹配用,如 arm64-v8a) */
    val primaryAbi: String
        get() = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /** UI 消费检查结果后重置 */
    fun consumeUpdateState() {
        updateStateFlow.value = UpdateUiState.Idle
    }

    /** 缓存统计(IO 线程异步刷新,与偏好合并为 UI 状态) */
    private val statsFlow = MutableStateFlow<CacheStats?>(null)

    /** 各缓存位置明细(选择清理对话框数据源) */
    private val cacheLocationsFlow = MutableStateFlow<List<CacheLocation>>(emptyList())

    /** 日志导出结果文件(UI 监听后分享) */
    private val exportFileFlow = MutableStateFlow<File?>(null)
    val exportFile: StateFlow<File?> = exportFileFlow.asStateFlow()

    /** 数据备份导出结果文件(UI 监听后分享) */
    private val exportDataFileFlow = MutableStateFlow<File?>(null)
    val exportDataFile: StateFlow<File?> = exportDataFileFlow.asStateFlow()

    /** 数据导入反馈(导入文件数,UI 监听后提示) */
    private val importFeedbackFlow = MutableStateFlow<Int?>(null)
    val importFeedback: StateFlow<Int?> = importFeedbackFlow.asStateFlow()

    /** 已导出日志文件统计(数量/字节,设置页展示) */
    private val logsInfoFlow = MutableStateFlow<Pair<Int, Long>?>(null)
    val logsInfo: StateFlow<Pair<Int, Long>?> = logsInfoFlow.asStateFlow()

    /** 清理日志反馈(删除的文件数,UI 监听后 Toast) */
    private val clearLogsFeedbackFlow = MutableStateFlow<Int?>(null)
    val clearLogsFeedback: StateFlow<Int?> = clearLogsFeedbackFlow.asStateFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.preferences, statsFlow, cacheLocationsFlow
    ) { prefs, stats, locations ->
        SettingsUiState(
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            defaultEngine = prefs.defaultEngine,
            editorFontSize = prefs.editorFontSize,
            editorTabSize = prefs.editorTabSize,
            editorAutoSave = prefs.editorAutoSave,
            editorWrap = prefs.editorWrap,
            fullscreenImmersive = prefs.fullscreenImmersive,
            clickOpensPreview = prefs.clickOpensPreview,
            uaPreset = UserAgentPreset.fromName(prefs.uaPreset),
            jsEnabled = prefs.jsEnabled,
            browserConsole = prefs.browserConsole,
            resourceCacheEnabled = prefs.resourceCacheEnabled,
            cacheStats = stats,
            cacheLocations = locations,
            debugMode = prefs.debugMode,
            gridView = prefs.gridView,
            customColorSeed = prefs.customColorSeed,
            colorStyle = prefs.colorStyle,
            language = prefs.language,
            webViewVersion = runCatching {
                WebViewCompat.getCurrentWebViewPackage(context)?.versionName
            }.getOrNull() ?: "",
            geckoVersion = if (BuildConfig.GECKO_ENABLED) GECKO_VERSION else "-",
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "",
            cloudProvider = prefs.cloudProvider,
            baiduLoggedIn = !prefs.baiduAccessToken.isNullOrBlank(),
            baiduTokenExpiresAt = prefs.baiduTokenExpiresAt,
            baiduAppKey = prefs.baiduAppKey,
            baiduSecretKey = prefs.baiduSecretKey,
            baiduAuthorizeUrl = cloudManager.baiduAuthorizeUrl(prefs),
            baiduRemoteRoot = prefs.baiduRemoteRoot.ifBlank { CloudManager.DEFAULT_BAIDU_REMOTE_ROOT },
            webdavUrl = prefs.webdavUrl,
            webdavUsername = prefs.webdavUsername,
            webdavPassword = prefs.webdavPassword,
            webdavDir = prefs.webdavDir,
            syncConflictPolicy = prefs.syncConflictPolicy,
            syncAutoUpload = prefs.syncAutoUpload,
            syncOnStart = prefs.syncOnStart,
            lastSyncAt = prefs.lastSyncAt
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        settingsRepository.setThemeMode(mode)
    }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDynamicColor(enabled)
    }

    fun setDefaultEngine(engine: EngineType) = viewModelScope.launch {
        settingsRepository.setDefaultEngine(engine)
    }

    fun setEditorFontSize(size: Float) = viewModelScope.launch {
        settingsRepository.setEditorFontSize(size)
    }

    fun setEditorTabSize(size: Int) = viewModelScope.launch {
        settingsRepository.setEditorTabSize(size)
    }

    fun setEditorAutoSave(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setEditorAutoSave(enabled)
    }

    fun setEditorWrap(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setEditorWrap(enabled)
    }

    fun setFullscreenImmersive(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setFullscreenImmersive(enabled)
    }

    fun setClickOpensPreview(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setClickOpensPreview(enabled)
    }

    fun setUaPreset(preset: UserAgentPreset) = viewModelScope.launch {
        settingsRepository.setUaPreset(preset.name)
    }

    fun setJsEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setJsEnabled(enabled)
    }

    fun setBrowserConsole(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setBrowserConsole(enabled)
    }

    fun setResourceCache(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setResourceCacheEnabled(enabled)
    }

    fun setDebugMode(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDebugMode(enabled)
        if (enabled) {
            // 开启时记录一条基准日志
            xyz.normalwindow.htmlviewer.data.debug.AppLog.force("Debug", "Debug 模式已开启")
        }
    }

    /** 生成调试报告并写入文件(完成后经 exportFile 通知 UI 分享) */
    fun exportLogs() {
        viewModelScope.launch {
            exportFileFlow.value = null
            val report = withContext(Dispatchers.IO) {
                LogExporter.buildReport(context, settingsRepository)
            }
            val file = withContext(Dispatchers.IO) {
                LogExporter.writeToFile(context, report)
            }
            exportFileFlow.value = file
        }
    }

    /** UI 消费导出文件后清除 */
    fun consumeExportFile() {
        exportFileFlow.value = null
    }

    /** 数据备份导出:工作目录压缩为 zip(含 meta 清单),完成后经 exportDataFile 通知 UI 分享 */
    fun exportData() {
        viewModelScope.launch {
            exportDataFileFlow.value = null
            val zip = withContext(Dispatchers.IO) {
                DataBackup.export(fileRootProvider.defaultRoot, context.cacheDir)
            }
            exportDataFileFlow.value = zip
        }
    }

    /** UI 消费备份导出文件后清除 */
    fun consumeExportDataFile() {
        exportDataFileFlow.value = null
    }

    /** 数据备份导入:把 SAF 选中的 zip 解压到工作目录,返回导入文件数 */
    fun importData(uri: android.net.Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "backup/import-tmp.zip")
                    tmp.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    DataBackup.import(tmp, fileRootProvider.defaultRoot)
                }.getOrDefault(0)
            }
            importFeedbackFlow.value = imported
        }
    }

    /** UI 消费导入反馈后清除 */
    fun consumeImportFeedback() {
        importFeedbackFlow.value = null
    }

    /** 刷新已导出日志文件统计 */
    fun refreshLogsInfo() {
        viewModelScope.launch {
            logsInfoFlow.value = withContext(Dispatchers.IO) {
                LogExporter.exportedLogsInfo(context)
            }
        }
    }

    /** 清理日志:清空内存缓冲 + 删除已导出的日志文件 */
    fun clearLogs() {
        viewModelScope.launch {
            AppLog.clear()
            val deleted = withContext(Dispatchers.IO) {
                LogExporter.clearExportedLogs(context)
            }
            logsInfoFlow.value = 0 to 0L
            clearLogsFeedbackFlow.value = deleted
        }
    }

    /** UI 消费清理反馈后清除 */
    fun consumeClearLogsFeedback() {
        clearLogsFeedbackFlow.value = null
    }

    /** 统计所有 HTML 目录下的固化缓存(IO 线程),并刷新位置明细 */
    fun refreshCacheStats() {
        viewModelScope.launch {
            val (stats, locations) = withContext(Dispatchers.IO) {
                val s = resourceCache.stats(fileRootProvider.defaultRoot)
                val l = resourceCache.listLocations(fileRootProvider.defaultRoot)
                s to l
            }
            statsFlow.value = if (stats.resourceCount > 0) stats else null
            cacheLocationsFlow.value = locations
        }
    }

    /** 手动清理全部固化缓存并重新统计 */
    fun clearResourceCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resourceCache.clearAll(fileRootProvider.defaultRoot)
            }
            statsFlow.value = null
            cacheLocationsFlow.value = emptyList()
            clearCacheFeedbackFlow.value = ClearCacheKind.ALL
        }
    }

    /** 选择清理:只清除勾选的缓存位置,其余保留 */
    fun clearCacheLocations(selected: List<File>) {
        if (selected.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resourceCache.clearLocations(selected)
            }
            // 重新统计与刷新位置明细
            val (stats, locations) = withContext(Dispatchers.IO) {
                resourceCache.stats(fileRootProvider.defaultRoot) to
                    resourceCache.listLocations(fileRootProvider.defaultRoot)
            }
            statsFlow.value = if (stats.resourceCount > 0) stats else null
            cacheLocationsFlow.value = locations
            clearCacheFeedbackFlow.value = ClearCacheKind.SELECTED(selected.size)
        }
    }

    /** 缓存清理反馈(UI 监听后 Snackbar 提示) */
    sealed interface ClearCacheKind {
        data object ALL : ClearCacheKind
        data class SELECTED(val count: Int) : ClearCacheKind
    }

    private val clearCacheFeedbackFlow = MutableStateFlow<ClearCacheKind?>(null)
    val clearCacheFeedback: StateFlow<ClearCacheKind?> = clearCacheFeedbackFlow.asStateFlow()

    fun consumeClearCacheFeedback() {
        clearCacheFeedbackFlow.value = null
    }

    fun setGridView(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setGridView(enabled)
    }

    fun setCustomColorSeed(seed: Long?) = viewModelScope.launch {
        settingsRepository.setCustomColorSeed(seed)
    }

    fun setColorStyle(style: ColorStyle) = viewModelScope.launch {
        settingsRepository.setColorStyle(style)
    }

    fun setLanguage(language: AppLanguage) = viewModelScope.launch {
        settingsRepository.setLanguage(language)
    }

    // ---------- 云同步 ----------

    /** 云盘切换:none/百度/WebDAV;各盘凭据独立保存,切回免重新登录 */
    fun setCloudProvider(type: CloudProviderType) = viewModelScope.launch {
        settingsRepository.setCloudProvider(type)
    }

    /** 用授权码换取令牌并持久化(百度授权 WebView 回调);结果经 baiduAuthFeedback 通知 UI */
    fun authorizeBaidu(code: String) {
        viewModelScope.launch {
            runCatching {
                val prefs = settingsRepository.preferences.first()
                val appKey = prefs.baiduAppKey.trim()
                val secretKey = prefs.baiduSecretKey.trim()
                exchangeBaiduCode(appKey, secretKey, code)
            }.fold(
                onSuccess = { tokens ->
                    settingsRepository.setBaiduTokens(
                        tokens.accessToken, tokens.refreshToken, tokens.expiresAt
                    )
                    baiduAuthFeedbackFlow.value = true
                },
                onFailure = {
                    AppLog.e("Cloud", "百度授权失败: ${it.message}", it)
                    baiduAuthFeedbackFlow.value = false
                }
            )
        }
    }

    /** 百度授权结果(true=成功 false=失败,null=无待处理反馈) */
    private val baiduAuthFeedbackFlow = MutableStateFlow<Boolean?>(null)
    val baiduAuthFeedback: StateFlow<Boolean?> = baiduAuthFeedbackFlow.asStateFlow()

    fun consumeBaiduAuthFeedback() {
        baiduAuthFeedbackFlow.value = null
    }

    /** 退出百度登录(清除令牌与同步快照) */
    fun baiduLogout() = viewModelScope.launch {
        settingsRepository.setBaiduTokens(null, null, 0)
        syncSnapshotStore.clear(CloudProviderType.BAIDU)
    }

    /** 保存百度远端根目录(根变更后清空同步快照,避免旧快照误判) */
    fun saveBaiduRemoteRoot(root: String) = viewModelScope.launch {
        settingsRepository.setBaiduRemoteRoot(root)
        syncSnapshotStore.clear(CloudProviderType.BAIDU)
        settingsRepository.setLastSyncAt(CloudProviderType.BAIDU, 0)
    }

    /** 云端目录选择器:列出远端目录下的子目录(仅目录;根传空串) */
    suspend fun listRemoteDirs(dir: String): Result<List<CloudFile>> {
        val prefs = settingsRepository.preferences.first()
        val provider = cloudManager.providerFromPrefs(prefs, prefs.cloudProvider)
            ?: return Result.failure(IllegalStateException("provider 不可用"))
        return provider.list(dir).map { files -> files.filter { it.isDir } }
    }

    /** 当前活动云盘类型(目录选择器判断用) */
    suspend fun activeProviderType(): CloudProviderType =
        settingsRepository.preferences.first().cloudProvider

    /** 保存百度开放平台凭据 */
    fun saveBaiduKeys(appKey: String, secretKey: String) = viewModelScope.launch {
        settingsRepository.setBaiduKeys(appKey, secretKey)
    }

    /** 保存 WebDAV 配置 */
    fun saveWebdavConfig(url: String, username: String, password: String, dir: String) =
        viewModelScope.launch {
            settingsRepository.setWebdavConfig(url, username, password, dir)
        }

    /** 测试 WebDAV 连接(用表单当前值,不先落盘) */
    fun testWebdav(url: String, username: String, password: String, dir: String) {
        if (url.isBlank() || username.isBlank()) {
            webdavTestFlow.value = WebDavTestResult.Failed("")
            return
        }
        webdavTestFlow.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val provider = WebDavProvider(
                        baseUrl = url,
                        username = username,
                        password = password,
                        remoteRoot = dir.trim().ifBlank { CloudManager.DEFAULT_WEBDAV_DIR }
                    )
                    provider.checkAuth()
                }
            }
            webdavTestFlow.value = result.fold(
                onSuccess = { WebDavTestResult.Success },
                onFailure = { WebDavTestResult.Failed(it.message ?: "") }
            )
        }
    }

    fun consumeWebdavTest() {
        webdavTestFlow.value = null
    }

    fun setSyncConflictPolicy(policy: SyncConflictPolicy) = viewModelScope.launch {
        settingsRepository.setSyncConflictPolicy(policy)
    }

    fun setSyncAutoUpload(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setSyncAutoUpload(enabled)
    }

    fun setSyncOnStart(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setSyncOnStart(enabled)
    }

    private companion object {
        /** 与 gradle/libs.versions.toml 中 geckoview 版本保持一致 */
        const val GECKO_VERSION = "153.0"
    }
}
