package xyz.normalwindow.htmlviewer.render

import android.content.Context
import android.net.Uri
import android.view.View
import java.io.File

/**
 * Lite 版 Gecko 渲染器桩:lite 变体不打包 GeckoView 内核,
 * 仅保留类型以编译 RendererFactory 分支(lite 下 GECKO_ENABLED=false,
 * 实际创建时永远走 WebViewRenderer,不会实例化本类)。
 */
class GeckoRenderer(
    context: Context,
    callbacks: RendererCallbacks?
) : Renderer {

    private val emptyView: View = View(context)

    override val view: View get() = emptyView
    override val supportsHistoryNav: Boolean get() = false
    override val touchpadSupported: Boolean get() = false

    override val pageMetricsSupported: Boolean get() = false
    override val consoleSupported: Boolean get() = false
    override val resourceCacheSupported: Boolean get() = false

    override fun loadHtml(html: String, baseUrl: String?) = Unit
    override fun loadFile(file: File) = Unit
    override fun executeJs(script: String) = Unit
    override fun reload() = Unit
    override fun goBack() = Unit
    override fun goForward() = Unit
    override fun setUserAgent(ua: String?, desktopViewport: Boolean) = Unit
    override fun setJavaScriptEnabled(enabled: Boolean) = Unit
    override fun setStateListener(listener: RendererStateListener?) = Unit
    override fun setScrollListener(listener: RendererScrollListener?) = Unit

    override fun queryPageMetrics(callback: (scrollHeight: Int, clientHeight: Int) -> Unit) = Unit

    override fun setPageMetricsListener(listener: ((scrollHeight: Int, clientHeight: Int) -> Unit)?) = Unit
    override fun setTouchpadMode(enabled: Boolean) = Unit
    override fun setConsoleListener(listener: RendererConsoleListener?) = Unit
    override fun setResourceCache(enabled: Boolean) = Unit
    override fun destroy() = Unit
}
