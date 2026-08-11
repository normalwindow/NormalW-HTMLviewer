package xyz.normalwindow.htmlviewer.render

import android.content.Context
import android.net.Uri
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.io.File

/**
 * GeckoRuntime 进程级单例:同一应用内所有 GeckoSession 共享一个运行时。
 *
 * GeckoView 153 无公共 profile API(GeckoProfile 已移除),默认即在应用
 * 内部存储使用持久 profile,HTTP 磁盘缓存默认开启并跨进程/重启保留——
 * 之前"每次打开网页都重新下载"的现象若复现,通常是服务器响应头
 * (Cache-Control: no-store/no-cache)禁止缓存所致,内核本身行为正确。
 * 此处通过 configFilePath 显式声明磁盘缓存参数,防御默认值变化。
 */
internal object GeckoRuntimeHolder {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun runtime(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: run {
                val settingsBuilder = GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(false)
                    // 关闭控制台输出:减少 logcat IO,提升加载性能
                    .consoleOutput(false)
                ensureConfigFile(context)?.let { settingsBuilder.configFilePath(it) }
                GeckoRuntime.create(
                    context.applicationContext,
                    settingsBuilder.build()
                ).also { runtime = it }
            }
        }

    /** 把打包的 gecko 配置复制到应用私有目录(GeckoView 的 configFilePath 需要真实文件路径) */
    private fun ensureConfigFile(context: Context): String? = runCatching {
        // 文件名带版本:应用升级后打包配置变化也能生效
        val target = File(context.cacheDir, "gecko-config-$GECKO_CONFIG_VERSION.yaml")
        if (!target.isFile) {
            context.assets.open("gecko/geckoview-config.yaml").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target.absolutePath
    }.getOrNull()

    private const val GECKO_CONFIG_VERSION = "v153"
}

/**
 * 兼容模式:GeckoView(Mozilla 独立内核,153.0)。
 * 渲染行为固定、不随系统 WebView 版本变化,适合需要稳定输出的场景。
 */
class GeckoRenderer(
    context: Context,
    private val callbacks: RendererCallbacks?
) : Renderer {

    override val view: android.view.View get() = geckoView

    /** GeckoView 无 canGoBack/canGoForward 公共 API,浏览器页隐藏前进/后退按钮 */
    override val supportsHistoryNav: Boolean get() = false

    /** GeckoView 无公共 JS 注入 API,触摸板模式降级为不支持 */
    override val touchpadSupported: Boolean get() = false

    /** GeckoView 无公共 console 回调 API,控制台收集降级为不支持 */
    override val consoleSupported: Boolean get() = false

    /** GeckoView 无请求拦截 API,资源本地固化缓存不支持 */
    override val resourceCacheSupported: Boolean get() = false

    private var stateListener: RendererStateListener? = null

    private var scrollListener: RendererScrollListener? = null

    private val runtime: GeckoRuntime = GeckoRuntimeHolder.runtime(context)
    private val geckoView: GeckoView = GeckoView(context)
    private val session: GeckoSession = GeckoSession()

    @Volatile
    private var currentUrl: String? = null

    init {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                currentUrl = url
                callbacks?.onPageStarted(url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                callbacks?.onPageFinished(currentUrl)
                stateListener?.onNavStateChanged(false, false)
                if (!success) {
                    callbacks?.onPageError("页面加载失败")
                }
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                stateListener?.onTitleChanged(title)
            }
        }
        // 页面滚动监听:内容滑过顶部时 header 变透明防止遮挡
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                scrollListener?.onScrollChanged(scrollX, scrollY)
            }
        }
        session.open(runtime)
        geckoView.setSession(session)
    }

    override fun loadHtml(html: String, baseUrl: String?) {
        // GeckoView 不支持 file:///android_asset/,内存 HTML 用 Loader.data()。
        // 用 byte[] 重载显式按 UTF-8 编码,避免 String 重载的平台默认编码
        // 导致中文/特殊字符渲染异常(乱码/方块)
        session.load(
            GeckoSession.Loader().data(
                html.toByteArray(Charsets.UTF_8),
                "text/html"
            )
        )
    }

    override fun loadFile(file: File) {
        // Uri.fromFile 正确处理空格/中文等特殊字符
        session.loadUri(Uri.fromFile(file).toString())
    }

    override fun executeJs(script: String) {
        // GeckoView 无公共 evaluateJavascript API(需 WebExtension 消息通道),
        // 当前预览场景无需执行 JS,预留空实现。
    }

    override fun reload() {
        session.reload()
    }

    override fun goBack() {
        session.goBack()
    }

    override fun goForward() {
        session.goForward()
    }

    override fun setUserAgent(ua: String?, desktopViewport: Boolean) {
        // null 时回退内核默认 UA
        session.settings.setUserAgentOverride(ua ?: GeckoSession.getDefaultUserAgent())
        // 桌面端 UA:切换为桌面视口模式(强制桌面分辨率布局),用户可手动放大查阅
        session.settings.setViewportMode(
            if (desktopViewport) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
    }

    override fun setJavaScriptEnabled(enabled: Boolean) {
        session.settings.setAllowJavascript(enabled)
    }

    override fun setStateListener(listener: RendererStateListener?) {
        stateListener = listener
    }

    override fun setScrollListener(listener: RendererScrollListener?) {
        scrollListener = listener
    }

    override fun setTouchpadMode(enabled: Boolean) {
        // GeckoView 无 evaluateJavascript/WebExtension 消息通道,不支持模拟鼠标
    }

    override fun setConsoleListener(listener: RendererConsoleListener?) {
        // GeckoView 无公共 console 回调 API,不支持控制台收集(页面加载错误仍经 RendererCallbacks 上报)
    }

    override fun setResourceCache(enabled: Boolean) {
        // GeckoView 无请求拦截 API,不支持资源固化缓存(磁盘缓存修复见 GeckoRuntimeSettings profile)
    }

    override fun destroy() {
        geckoView.releaseSession()
        session.close()
    }
}
