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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.normalwindow.htmlviewer.BuildConfig
import xyz.normalwindow.htmlviewer.data.backup.DataBackup
import xyz.normalwindow.htmlviewer.data.cache.CacheStats
import xyz.normalwindow.htmlviewer.data.cache.ResourceCache
import xyz.normalwindow.htmlviewer.data.debug.AppLog
import xyz.normalwindow.htmlviewer.data.debug.LogExporter
import xyz.normalwindow.htmlviewer.data.file.FileRootProvider
import xyz.normalwindow.htmlviewer.data.settings.AppLanguage
import xyz.normalwindow.htmlviewer.data.settings.ColorStyle
import xyz.normalwindow.htmlviewer.data.settings.EngineType
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import xyz.normalwindow.htmlviewer.data.settings.ThemeMode
import xyz.normalwindow.htmlviewer.render.UserAgentPreset
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
    val appVersion: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val fileRootProvider: FileRootProvider
) : ViewModel() {

    private val resourceCache = ResourceCache()

    /** 缓存统计(IO 线程异步刷新,与偏好合并为 UI 状态) */
    private val statsFlow = MutableStateFlow<CacheStats?>(null)

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

    val state: StateFlow<SettingsUiState> = combine(settingsRepository.preferences, statsFlow) { prefs, stats ->
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
            }.getOrNull() ?: ""
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

    /** 统计所有 HTML 目录下的固化缓存(IO 线程) */
    fun refreshCacheStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                resourceCache.stats(fileRootProvider.defaultRoot)
            }
            statsFlow.value = if (stats.resourceCount > 0) stats else null
        }
    }

    /** 手动清理全部固化缓存并重新统计 */
    fun clearResourceCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resourceCache.clearAll(fileRootProvider.defaultRoot)
            }
            statsFlow.value = null
        }
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

    private companion object {
        /** 与 gradle/libs.versions.toml 中 geckoview 版本保持一致 */
        const val GECKO_VERSION = "153.0"
    }
}
