package xyz.normalwindow.htmlviewer.ui.browser

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.normalwindow.htmlviewer.data.file.FileRepository
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import xyz.normalwindow.htmlviewer.render.ConsoleLevel
import xyz.normalwindow.htmlviewer.render.Renderer
import xyz.normalwindow.htmlviewer.render.RendererCallbacks
import xyz.normalwindow.htmlviewer.render.RendererConsoleListener
import xyz.normalwindow.htmlviewer.render.RendererFactory
import xyz.normalwindow.htmlviewer.render.RendererScrollListener
import xyz.normalwindow.htmlviewer.render.RendererStateListener
import xyz.normalwindow.htmlviewer.render.UserAgentPreset
import java.io.File
import javax.inject.Inject

/** 控制台单条日志(报错/警告抽屉条目) */
data class ConsoleEntry(
    val level: ConsoleLevel,
    val message: String,
    val lineNumber: Int,
    val source: String?,
    val time: Long
)

/**
 * 浏览器预览页:基础浏览器功能(前进/后退/刷新/UA/JS 开关/沉浸)。
 * 单击文件默认进入此页,页面完全可交互(滚动/链接/表单)。
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val fileRepository: FileRepository,
    private val settingsRepository: SettingsRepository,
    private val rendererFactory: RendererFactory
) : ViewModel() {

    data class UiState(
        val ready: Boolean = false,
        val title: String = "",
        val loading: Boolean = false,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val immersive: Boolean = true,
        val toolbarVisible: Boolean = true,
        /** 页面内容已滑过顶部(header 变透明防止遮挡) */
        val pageScrolled: Boolean = false,
        val uaPreset: UserAgentPreset = UserAgentPreset.DEFAULT,
        val jsEnabled: Boolean = true,
        /** 渲染内核类型(用于 UI 提示与前进/后退按钮显隐) */
        val supportsHistoryNav: Boolean = true,
        /** 模拟鼠标(触摸板模式)开关 */
        val touchpadEnabled: Boolean = false,
        /** 当前内核是否支持触摸板模式 */
        val touchpadSupported: Boolean = false,
        /** 控制台收集开关(设置持久化) */
        val consoleEnabled: Boolean = true,
        /** 当前内核是否支持 console 收集(Gecko 不支持) */
        val consoleSupported: Boolean = false,
        /** 控制台/错误日志(最多保留 200 条) */
        val consoleEntries: List<ConsoleEntry> = emptyList(),
        /** 未读日志计数(打开抽屉后清零) */
        val consoleUnread: Int = 0,
        /** 未读日志中最严重的级别(icon 圆点颜色用;未读为 0 时为 null) */
        val consolePeakLevel: ConsoleLevel? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var renderer: Renderer? = null
    private var currentPath: String? = null

    /** 初始化协程(退出时可取消,避免挂起期间释放视图后仍完成赋值导致渲染器泄漏) */
    private var initJob: Job? = null

    /** 读取设置并创建渲染器加载文件(幂等,页面重建时安全) */
    fun initialize(path: String) {
        if (currentPath == path && renderer != null) return
        currentPath = path
        initJob?.cancel()
        initJob = viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            val ua = UserAgentPreset.fromName(prefs.uaPreset)
            val r = rendererFactory.create(
                prefs.defaultEngine,
                object : RendererCallbacks {
                    override fun onPageStarted(url: String?) {
                        // 新页面从顶部开始,重置滚动状态
                        _state.update { it.copy(loading = true, pageScrolled = false) }
                    }

                    override fun onPageFinished(url: String?) {
                        _state.update { it.copy(loading = false) }
                    }

                    override fun onPageError(description: String?) {
                        _state.update { it.copy(loading = false) }
                        // 页面加载失败进入报错抽屉(Gecko 内核仅此来源)
                        appendConsole(
                            ConsoleLevel.ERROR,
                            description ?: "页面加载失败",
                            0,
                            null
                        )
                    }
                }
            )
            r.setConsoleListener(object : RendererConsoleListener {
                override fun onConsoleMessage(level: ConsoleLevel, message: String, lineNumber: Int, source: String?) {
                    appendConsole(level, message, lineNumber, source)
                }
            })
            r.setStateListener(object : RendererStateListener {
                override fun onTitleChanged(title: String?) {
                    _state.update { it.copy(title = title ?: "") }
                }

                override fun onNavStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
                    _state.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
                }
            })
            // 页面滚动:内容滑过顶部时 header 变透明防止遮挡
            r.setScrollListener(object : RendererScrollListener {
                override fun onScrollChanged(scrollX: Int, scrollY: Int) {
                    _state.update { it.copy(pageScrolled = scrollY > 0) }
                }
            })
            r.setJavaScriptEnabled(prefs.jsEnabled)
            if (ua != UserAgentPreset.DEFAULT) {
                r.setUserAgent(ua.build(appContext), ua.desktopViewport)
            }
            // 资源本地固化缓存(仅 WebView 内核生效;Gecko 为 no-op)
            r.setResourceCache(prefs.resourceCacheEnabled)
            _state.update {
                it.copy(
                    immersive = prefs.fullscreenImmersive,
                    uaPreset = ua,
                    jsEnabled = prefs.jsEnabled,
                    consoleEnabled = prefs.browserConsole,
                    supportsHistoryNav = r.supportsHistoryNav,
                    touchpadSupported = r.touchpadSupported,
                    consoleSupported = r.consoleSupported
                )
            }
            r.loadFile(File(path))
            // 触摸板开关跨渲染器保持:重建渲染器后重放开启状态(onPageFinished 会重新注入)
            if (_state.value.touchpadEnabled) {
                r.setTouchpadMode(true)
            }
            // 协程已被取消(视图已释放):放弃并销毁新建的渲染器
            if (!isActive) {
                r.destroy()
                return@launch
            }
            // 防御:重新赋值前销毁旧渲染器(当前调用方不会触发,防止未来路径变更)
            renderer?.destroy()
            renderer = r
            // 先置就绪并挂载视图,再异步记录打开时间(避免后退时协程取消导致渲染器泄漏)
            _state.update { it.copy(ready = true, toolbarVisible = !it.immersive) }
            fileRepository.touchOpened(path, null, null, null)
        }
    }

    fun rendererView(): android.view.View? = renderer?.view

    fun goBack() = renderer?.goBack()

    fun goForward() = renderer?.goForward()

    fun reload() = renderer?.reload()

    fun toggleToolbar() = _state.update { it.copy(toolbarVisible = !it.toolbarVisible) }

    /** 切换模拟鼠标(触摸板模式) */
    fun toggleTouchpad() {
        val next = !_state.value.touchpadEnabled
        _state.update { it.copy(touchpadEnabled = next) }
        renderer?.setTouchpadMode(next)
    }

    /** 切换控制台收集开关(持久化到设置) */
    fun toggleConsole() {
        val next = !_state.value.consoleEnabled
        _state.update { it.copy(consoleEnabled = next) }
        viewModelScope.launch { settingsRepository.setBrowserConsole(next) }
    }

    /** 清空控制台日志 */
    fun clearConsole() {
        _state.update { it.copy(consoleEntries = emptyList(), consoleUnread = 0, consolePeakLevel = null) }
    }

    /** 打开抽屉后清零未读计数 */
    fun markConsoleRead() {
        if (_state.value.consoleUnread != 0) {
            _state.update { it.copy(consoleUnread = 0, consolePeakLevel = null) }
        }
    }

    /** 追加控制台条目(开关关闭/空消息时忽略;上限 200 条防内存膨胀) */
    private fun appendConsole(level: ConsoleLevel, message: String, lineNumber: Int, source: String?) {
        if (!_state.value.consoleEnabled) return
        val msg = message.trim().take(500)
        if (msg.isEmpty()) return
        _state.update { s ->
            val entries = (s.consoleEntries + ConsoleEntry(level, msg, lineNumber, source, System.currentTimeMillis()))
                .takeLast(CONSOLE_LIMIT)
            // 圆点颜色取未读中最严重的级别(级别枚举按严重度升序:DEBUG < LOG < INFO < WARN < ERROR)
            val peak = when {
                s.consolePeakLevel == null -> level
                level.ordinal > s.consolePeakLevel!!.ordinal -> level
                else -> s.consolePeakLevel
            }
            s.copy(consoleEntries = entries, consoleUnread = s.consoleUnread + 1, consolePeakLevel = peak)
        }
    }

    /**
     * 切换沉浸模式(会话性,不持久化):开启=隐藏 header 与系统栏;
     * 关闭=召唤 header(顶部细条点击)。设置页开关只决定进入时是否默认沉浸。
     */
    fun toggleImmersive() {
        val next = !_state.value.immersive
        _state.update { it.copy(immersive = next, toolbarVisible = !next) }
    }

    fun setImmersive(immersive: Boolean) {
        _state.update { it.copy(immersive = immersive, toolbarVisible = !immersive) }
        viewModelScope.launch { settingsRepository.setFullscreenImmersive(immersive) }
    }

    fun setUaPreset(preset: UserAgentPreset) {
        _state.update { it.copy(uaPreset = preset) }
        renderer?.setUserAgent(
            if (preset == UserAgentPreset.DEFAULT) null else preset.build(appContext),
            preset.desktopViewport
        )
        // UA 需重新加载页面才生效
        renderer?.reload()
        viewModelScope.launch { settingsRepository.setUaPreset(preset.name) }
    }

    fun toggleJs() {
        val next = !_state.value.jsEnabled
        _state.update { it.copy(jsEnabled = next) }
        renderer?.setJavaScriptEnabled(next)
        // JS 开关需重新加载页面才完整生效
        renderer?.reload()
        viewModelScope.launch { settingsRepository.setJsEnabled(next) }
    }

    /** 视图销毁时释放渲染器 */
    fun onRendererReleased() {
        initJob?.cancel()
        renderer?.destroy()
        renderer = null
        currentPath = null
        _state.update { it.copy(ready = false) }
    }

    /** 兜底:页面在加载完成前退出时释放渲染器(幂等) */
    override fun onCleared() {
        initJob?.cancel()
        renderer?.destroy()
        renderer = null
        super.onCleared()
    }

    private companion object {
        /** 控制台日志保留上限 */
        const val CONSOLE_LIMIT = 200
    }
}
