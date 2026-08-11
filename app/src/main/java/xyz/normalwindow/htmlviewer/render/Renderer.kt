package xyz.normalwindow.htmlviewer.render

import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import xyz.normalwindow.htmlviewer.data.cache.CachedResponse
import xyz.normalwindow.htmlviewer.data.cache.ResourceCache
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/** 页面渲染回调 */
interface RendererCallbacks {
    fun onPageStarted(url: String?)
    fun onPageFinished(url: String?)
    fun onPageError(description: String?)
}

/** 控制台日志级别 */
enum class ConsoleLevel { DEBUG, LOG, INFO, WARN, ERROR }

/** 控制台消息监听(报错/警告抽屉的数据源,回调于主线程) */
interface RendererConsoleListener {
    fun onConsoleMessage(level: ConsoleLevel, message: String, lineNumber: Int, source: String?)
}

/** 浏览器式导航/标题回调(浏览器预览页使用) */
interface RendererStateListener {
    fun onTitleChanged(title: String?)
    fun onNavStateChanged(canGoBack: Boolean, canGoForward: Boolean)
}

/** 页面滚动状态回调(主线程;用于 header 滚动透明化等 UI 联动) */
interface RendererScrollListener {
    fun onScrollChanged(scrollX: Int, scrollY: Int)
}

/** 预设 UA 标识 */
enum class UserAgentPreset(
    val displayName: String,
    val build: (Context) -> String?,
    /** 是否以桌面端分辨率(宽视口)强制渲染 */
    val desktopViewport: Boolean
) {
    /** 跟随内核默认 */
    DEFAULT("跟随内核默认", { null }, false),

    ANDROID_CHROME("Android Chrome", { ctx ->
        "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
            "${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${androidx.webkit.WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName ?: "120.0"}" +
            " Mobile Safari/537.36"
    }, false),

    DESKTOP_CHROME("桌面版 Chrome", { ctx ->
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/${androidx.webkit.WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName ?: "120.0"}" +
            " Safari/537.36"
    }, true),

    IPHONE_SAFARI("iPhone Safari", {
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }, false);

    companion object {
        fun fromName(name: String?): UserAgentPreset =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 浏览器内核抽象:轻量模式(系统 WebView)与兼容模式(GeckoView)
 * 通过同一接口提供页面渲染与浏览器级导航能力。
 */
interface Renderer {
    /** 渲染视图(供 AndroidView 挂载) */
    val view: android.view.View

    /** 是否支持前进/后退按钮(GeckoView 无 canGoBack API,不支持时 UI 隐藏按钮) */
    val supportsHistoryNav: Boolean

    /** 是否支持模拟鼠标/触摸板模式(GeckoView 无公共 JS 注入 API,不支持) */
    val touchpadSupported: Boolean

    /** 是否支持控制台消息收集(GeckoView 无公共 console API,不支持) */
    val consoleSupported: Boolean

    /** 设置控制台消息监听(传 null 停止收集) */
    fun setConsoleListener(listener: RendererConsoleListener?)

    /** 是否支持资源本地固化缓存(GeckoView 无请求拦截 API,不支持) */
    val resourceCacheSupported: Boolean

    /** 开启/关闭资源本地固化缓存(仅 WebView 内核支持;HTML 同目录 .htmlviewer_cache) */
    fun setResourceCache(enabled: Boolean)

    /** 渲染 HTML 内容(内存字符串),保留相对路径基准 */
    fun loadHtml(html: String, baseUrl: String? = null)

    /** 渲染本地文件 */
    fun loadFile(file: File)

    /** 执行 JS(仅轻量模式支持同步结果获取场景,统一为 void) */
    fun executeJs(script: String)

    /** 重新加载当前页面 */
    fun reload()

    /** 浏览器后退 */
    fun goBack()

    /** 浏览器前进 */
    fun goForward()

    /** 设置 UA 标识(传 null 恢复内核默认);desktopViewport 是否以桌面端分辨率强制渲染 */
    fun setUserAgent(ua: String?, desktopViewport: Boolean)

    /** 开关 JavaScript */
    fun setJavaScriptEnabled(enabled: Boolean)

    /** 设置导航/标题状态监听 */
    fun setStateListener(listener: RendererStateListener?)

    /** 设置页面滚动状态监听(GeckoView 无公共 API 时为 no-op) */
    fun setScrollListener(listener: RendererScrollListener?)

    /**
     * 模拟鼠标(触摸板模式):单指滑动移动光标并产生 hover,轻点=左键单击,
     * 双指上下滑动=页面滚动。关闭时恢复正常触摸交互。
     */
    fun setTouchpadMode(enabled: Boolean)

    /** 释放资源 */
    fun destroy()
}

/**
 * 轻量模式:系统 WebView(Chromium 内核,跟随系统更新)。
 */
class WebViewRenderer(
    context: Context,
    private val callbacks: RendererCallbacks? = null
) : Renderer {

    override val view: android.view.View get() = webView

    override val supportsHistoryNav: Boolean get() = true

    override val touchpadSupported: Boolean get() = true

    override val consoleSupported: Boolean get() = true

    override val resourceCacheSupported: Boolean get() = true

    private var stateListener: RendererStateListener? = null

    private var scrollListener: RendererScrollListener? = null

    /** 桌面 UA:主文档注入桌面宽度 viewport,真正按桌面分辨率布局(shouldInterceptRequest 子线程读取) */
    @Volatile
    private var desktopViewport = false

    @Volatile
    private var touchpadEnabled = false

    private var consoleListener: RendererConsoleListener? = null

    override fun setConsoleListener(listener: RendererConsoleListener?) {
        consoleListener = listener
    }

    /** 资源本地固化缓存开关 */
    @Volatile
    private var resourceCacheEnabled = false

    /** 当前主文档文件(决定缓存目录;内存 HTML 预览不启用缓存) */
    @Volatile
    private var currentHtmlFile: File? = null

    private val resourceCache = ResourceCache()

    override fun setResourceCache(enabled: Boolean) {
        resourceCacheEnabled = enabled
    }

    val webView: WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            // 安全:禁止任意 file 页面读取所有本地文件(仅允许同源 file 资源)
            allowUniversalAccessFromFileURLs = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                callbacks?.onPageStarted(url)
                stateListener?.onNavStateChanged(
                    view.canGoBack(), view.canGoForward()
                )
            }

            override fun onPageFinished(view: WebView, url: String?) {
                callbacks?.onPageFinished(url)
                stateListener?.onNavStateChanged(
                    view.canGoBack(), view.canGoForward()
                )
                // 触摸板模式跨页面保持:导航完成后重新注入(带确认重试)
                if (touchpadEnabled) {
                    injectTouchpadScript(true, attempt = 0)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    callbacks?.onPageError(error?.description?.toString())
                } else if (request != null) {
                    // 子资源加载失败(JS/CSS/图片等)记录进报错抽屉
                    consoleListener?.onConsoleMessage(
                        ConsoleLevel.ERROR,
                        "资源加载失败: ${request.url}",
                        0,
                        request.url.toString()
                    )
                }
            }

            /**
             * 资源本地固化缓存:http(s) 资源命中缓存直接返回本地文件(离线可用),
             * 未命中则下载并固化到 HTML 同目录隐藏文件夹后返回(仅下载一次)。
             * 本回调运行于后台线程,可阻塞执行网络请求。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // 桌面 UA:主文档强制注入桌面宽度 viewport meta(在页面布局前生效),
                // 否则页面自带的 width=device-width 会让布局仍是手机宽度,与手机 UA 无异
                if (desktopViewport && request.isForMainFrame) {
                    injectDesktopViewport(request.url)?.let { return it }
                }
                if (!resourceCacheEnabled) return null
                val scheme = request.url.scheme
                if (scheme != "http" && scheme != "https") return null
                val htmlFile = currentHtmlFile ?: return null
                val cacheDir = resourceCache.cacheDirFor(htmlFile)
                val url = request.url.toString()
                resourceCache.serve(url, cacheDir)?.let { return it.toWebResourceResponse() }
                // 未命中:下载并固化(失败返回 null,由 WebView 自行加载)
                resourceCache.download(url, cacheDir)?.let { return it.toWebResourceResponse() }
                return null
            }
        }
        // 页面滚动监听:内容滑过顶部时 header 变透明防止遮挡
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            scrollListener?.onScrollChanged(0, scrollY)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                stateListener?.onTitleChanged(title)
            }

            // 捕获页面 console 输出(报错/警告抽屉数据源,API 25+ 签名)
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                val level = when (consoleMessage.messageLevel()) {
                    android.webkit.ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.ERROR
                    android.webkit.ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.WARN
                    android.webkit.ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.DEBUG
                    android.webkit.ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.INFO
                    else -> ConsoleLevel.LOG
                }
                consoleListener?.onConsoleMessage(
                    level,
                    consoleMessage.message(),
                    consoleMessage.lineNumber(),
                    consoleMessage.sourceId()
                )
                return true
            }
        }
    }

    override fun loadHtml(html: String, baseUrl: String?) {
        // 内存 HTML(分屏预览等)无同目录缓存语义,不启用固化缓存
        currentHtmlFile = null
        webView.loadDataWithBaseURL(
            baseUrl ?: "file:///android_asset/",
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    override fun loadFile(file: File) {
        // 记录主文档,确定资源缓存目录
        currentHtmlFile = file
        // Uri.fromFile 正确处理空格/中文等特殊字符
        webView.loadUrl(Uri.fromFile(file).toString())
    }

    override fun executeJs(script: String) {
        webView.post { webView.evaluateJavascript(script, null) }
    }

    override fun reload() {
        webView.reload()
    }

    override fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    override fun goForward() {
        if (webView.canGoForward()) webView.goForward()
    }

    override fun setUserAgent(ua: String?, desktopViewport: Boolean) {
        webView.settings.userAgentString = ua
        this.desktopViewport = desktopViewport
        // 桌面端 UA:强制以桌面端分辨率(宽视口)渲染,加载时缩略到屏幕宽,
        // 用户可手动双指放大查阅;其余 UA 恢复窄视口避免移动页面被放大
        webView.settings.useWideViewPort = desktopViewport
        webView.settings.loadWithOverviewMode = desktopViewport
    }

    override fun setJavaScriptEnabled(enabled: Boolean) {
        webView.settings.javaScriptEnabled = enabled
    }

    override fun setStateListener(listener: RendererStateListener?) {
        stateListener = listener
    }

    override fun setScrollListener(listener: RendererScrollListener?) {
        scrollListener = listener
    }

    override fun setTouchpadMode(enabled: Boolean) {
        touchpadEnabled = enabled
        webView.post {
            // 触摸板模式接管双指手势:关闭 WebView 原生缩放手势,
            // 否则双指触摸被缩放消费,JS 收不到完整 touch 序列 → 双指拖动失效
            webView.settings.setSupportZoom(!enabled)
            webView.settings.builtInZoomControls = !enabled
            injectTouchpadScript(enabled, attempt = 0)
        }
    }

    /**
     * 注入触摸板脚本并确认结果(脚本返回启用状态布尔值)。
     * 页面未就绪/注入失败时有限重试;日志供 debug 模式取证。
     */
    private fun injectTouchpadScript(enabled: Boolean, attempt: Int) {
        val script = if (enabled) TOUCHPAD_ENABLE_JS else TOUCHPAD_DISABLE_JS
        webView.evaluateJavascript(script) { result ->
            val ok = result?.trim()?.trim('"') == "true"
            xyz.normalwindow.htmlviewer.data.debug.AppLog.d(
                "Touchpad",
                "注入结果: enabled=$enabled result=$result attempt=$attempt"
            )
            if (enabled && !ok && attempt < TOUCHPAD_RETRY_MAX) {
                webView.postDelayed({ injectTouchpadScript(true, attempt + 1) }, 300)
            }
        }
    }

    override fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }

    /** 缓存响应 → WebResourceResponse(长缓存头:资源 URL 不变即内容不变) */
    private fun CachedResponse.toWebResourceResponse(): WebResourceResponse = WebResourceResponse(
        mime,
        null,
        200,
        "OK",
        mapOf(
            "Cache-Control" to "max-age=31536000, immutable",
            "Access-Control-Allow-Origin" to "*"
        ),
        BufferedInputStream(FileInputStream(file))
    )

    /**
     * 桌面 UA:读取主文档 HTML 并强制注入桌面宽度(1280px)viewport meta,
     * 使页面真正按桌面分辨率布局(配合 loadWithOverviewMode 缩略显示整个桌面宽,
     * 用户可双指放大查看细节)。非 HTML 主文档返回 null 走 WebView 默认加载。
     */
    private fun injectDesktopViewport(url: Uri): WebResourceResponse? = runCatching {
        val bytes: ByteArray = when (url.scheme) {
            "file" -> {
                val path = url.path ?: return@runCatching null
                val file = File(path)
                if (!file.isFile) return@runCatching null
                file.readBytes()
            }
            "http", "https" -> fetchMainDoc(url.toString()) ?: return@runCatching null
            else -> return@runCatching null
        }
        val decoded = TextEncoding.decode(bytes)
        if (!looksLikeHtml(decoded.content)) return@runCatching null
        val injected = injectDesktopViewportMeta(decoded.content)
        if (injected == decoded.content) return@runCatching null
        WebResourceResponse(
            "text/html",
            decoded.encoding,
            ByteArrayInputStream(TextEncoding.encode(injected, decoded.encoding))
        )
    }.getOrNull()

    /** 下载 http(s) 主文档(桌面 UA 注入用;失败返回 null 由 WebView 自行加载) */
    private fun fetchMainDoc(url: String): ByteArray? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = DESKTOP_FETCH_TIMEOUT_MS
        conn.readTimeout = DESKTOP_FETCH_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", webView.settings.userAgentString ?: "")
        if (conn.responseCode in 200..299) {
            conn.inputStream.use { it.readBytes() }
        } else {
            null
        }
    }.getOrNull()

    /** 是否为 HTML 文档(二进制/图片等主文档跳过注入) */
    private fun looksLikeHtml(content: String): Boolean {
        val head = content.trimStart().take(1024)
        return Regex("(?is)<html[^>]*>|<head[^>]*>|<!doctype\\s+html").containsMatchIn(head)
    }

    /** 替换页面已有 viewport meta 为桌面宽度,没有则插入到 <head> 内 */
    private fun injectDesktopViewportMeta(html: String): String {
        val viewportRe = Regex("(?i)<meta\\b[^>]*\\bname\\s*=\\s*[\"']viewport[\"'][^>]*>")
        val injected = "<meta name=\"viewport\" content=\"width=1280\">"
        if (viewportRe.containsMatchIn(html)) {
            return viewportRe.replace(html, injected)
        }
        val headRe = Regex("(?i)<head[^>]*>")
        val head = headRe.find(html)
        return if (head != null) {
            html.replaceRange(head.range.last + 1, head.range.last + 1, "\n$injected\n")
        } else {
            "$injected\n$html"
        }
    }

    private companion object {
        /** 触摸板注入确认重试上限(页面未就绪等场景) */
        const val TOUCHPAD_RETRY_MAX = 5

        /** 桌面 UA 主文档下载超时(毫秒) */
        const val DESKTOP_FETCH_TIMEOUT_MS = 10_000

        /**
         * 触摸板模式注入脚本(幂等:可反复开启/关闭,由 __HV_TP_CLEANUP__ 完整拆卸),
         * 模拟真实电脑触摸板:
         * - 单指滑动:光标按相对位移移动(增量 × 灵敏度,非绝对定位),实时 hover 追踪
         * - 轻点(位移小于阈值):左键单击(链接/复选框/输入框手动处理,因合成事件不触发默认行为)
         * - 双指滑动:直接滚动页面(合成 wheel 在真机不触发默认滚动,必须操作 scrollTop)
         * - 光标为箭头图标(SVG data URI)
         * - 末尾返回启用状态布尔值,供 Android 侧确认注入(失败自动重试)
         */
        private val TOUCHPAD_ENABLE_JS = """
            (function () {
              // 先清理可能残留的旧安装,保证幂等且可修复半安装状态
              if (typeof window.__HV_TP_CLEANUP__ === 'function') {
                try { window.__HV_TP_CLEANUP__(); } catch (e) {}
              }
              if (window.__HV_TOUCHPAD__) return window.__HV_TOUCHPAD__ === true;
              window.__HV_TOUCHPAD__ = true;
              var c = document.createElement('div');
              c.id = '__hv_cursor';
              c.style.cssText = 'position:fixed;left:0;top:0;z-index:2147483647;width:28px;height:28px;pointer-events:none;display:block;background-repeat:no-repeat;background-image:url("data:image/svg+xml,%3Csvg%20xmlns=%27http://www.w3.org/2000/svg%27%20width=%2728%27%20height=%2728%27%20viewBox=%270%200%2028%2028%27%3E%3Cpath%20d=%27M4%202%20L4%2023%20L9.5%2017.5%20L13%2026%20L17.5%2024%20L14%2015.5%20L21.5%2016%20Z%27%20fill=%27white%27%20stroke=%27%23333%27%20stroke-width=%271.8%27%20stroke-linejoin=%27round%27/%3E%3C/svg%3E");filter:drop-shadow(0 1px 2px rgba(0,0,0,.5));';
              (document.body || document.documentElement).appendChild(c);
              // 悬停/按压可见反馈:合成事件无法触发 CSS :hover/:active 伪类,
              // 用 data 属性 + outline 高亮让用户看到光标悬停/按下的位置
              var st = document.createElement('style');
              st.id = '__hv_tp_style';
              st.textContent = '[data-hv-hover]{outline:2px dashed #4a9eff !important;outline-offset:-2px;}[data-hv-active]{outline:2px solid #4a9eff !important;outline-offset:-2px;}';
              (document.head || document.documentElement).appendChild(st);
              var SENS = 1.6; // 光标移动灵敏度
              var x = window.innerWidth / 2, y = window.innerHeight / 2;
              var curEl = null;
              function clamp(v, m) { return Math.max(0, Math.min(m - 1, v)); }
              // 同时派发 Mouse 与 Pointer 事件:现代页面多用 pointer 系列
              function fireAt(el, type, cx, cy) {
                if (!el) return;
                var opts = { bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy, button: 0, pointerId: 1, isPrimary: true };
                el.dispatchEvent(new MouseEvent(type, opts));
                var ptype = type === 'mouseover' ? 'pointerover' : type === 'mouseout' ? 'pointerout' : type === 'mousemove' ? 'pointermove' : type === 'mousedown' ? 'pointerdown' : type === 'mouseup' ? 'pointerup' : type === 'click' ? 'pointerup' : null;
                if (ptype && typeof PointerEvent === 'function') {
                  try { el.dispatchEvent(new PointerEvent(ptype, opts)); } catch (e2) {}
                }
              }
              function moveTo(nx, ny) {
                x = clamp(nx, window.innerWidth);
                y = clamp(ny, window.innerHeight);
                c.style.left = x + 'px';
                c.style.top = y + 'px';
                var el = document.elementFromPoint(x, y);
                if (el !== curEl) {
                  if (curEl) {
                    fireAt(curEl, 'mouseout', x, y);
                    curEl.removeAttribute('data-hv-hover');
                  }
                  curEl = el;
                  if (el) {
                    fireAt(el, 'mouseover', x, y);
                    el.setAttribute('data-hv-hover', '');
                  }
                }
                // 持续 mousemove:页面 tooltip/拖拽预览等跟随光标的交互可更新
                if (el) fireAt(el, 'mousemove', x, y);
              }
              // 查找可滚动容器(页面级回退到 scrollingElement)
              function scrollByPx(dy) {
                var n = document.elementFromPoint(x, y) || document.body;
                while (n && n !== document.body && n !== document.documentElement) {
                  if (n.scrollHeight > n.clientHeight + 1) { n.scrollTop += dy; return; }
                  n = n.parentElement;
                }
                var root = document.scrollingElement || document.documentElement;
                root.scrollTop += dy;
              }
              function clickAt() {
                var el = document.elementFromPoint(x, y);
                if (!el) return;
                // 诊断:控制台抽屉可观察模拟点击是否命中预期元素
                try {
                  if (window.console) console.log('[hv-touchpad] click target=' + el.tagName.toLowerCase());
                } catch (e3) {}
                el.setAttribute('data-hv-active', '');
                fireAt(el, 'mousedown', x, y);
                fireAt(el, 'mouseup', x, y);
                fireAt(el, 'click', x, y);
                el.removeAttribute('data-hv-active');
                var link = el.closest('a[href]');
                if (link && link.href) {
                  if (link.target === '_blank') { window.open(link.href); } else { window.location.href = link.href; }
                  return;
                }
                if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) {
                  el.checked = !el.checked;
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                  return;
                }
                var tag = el.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable) { el.focus(); }
              }
              var active = false, moved = false, twoFinger = false;
              var lastX = 0, lastY = 0, startX = 0, startY = 0;
              function ts(e) {
                if (!window.__HV_TOUCHPAD__) return;
                if (e.touches.length === 2) {
                  // 第二根手指落下:进入双指手势,取消单击判定(防双指轻点误触点击)
                  e.preventDefault(); // 阻止 WebView 启动双指手势
                  twoFinger = true;
                  active = false;
                  return;
                }
                if (e.touches.length === 1 && !twoFinger) {
                  e.preventDefault();
                  active = true; moved = false;
                  startX = lastX = e.touches[0].clientX;
                  startY = lastY = e.touches[0].clientY;
                }
              }
              function tm(e) {
                if (!window.__HV_TOUCHPAD__ || !active && !twoFinger) return;
                if (e.touches.length === 1 && !twoFinger && active) {
                  e.preventDefault();
                  var t = e.touches[0];
                  // 相对位移:手指增量 × 灵敏度,光标增量移动(触摸板风格,非绝对定位)
                  var dx = (t.clientX - lastX) * SENS;
                  var dy = (t.clientY - lastY) * SENS;
                  lastX = t.clientX; lastY = t.clientY;
                  if (!moved && Math.abs(t.clientX - startX) + Math.abs(t.clientY - startY) > 6) { moved = true; }
                  if (moved) moveTo(x + dx, y + dy);
                } else if (e.touches.length === 2) {
                  e.preventDefault();
                  moved = true; // 双指滚动:避免松手误判轻点
                  var f = e.touches[0];
                  if (!twoFinger) { twoFinger = true; lastY = f.clientY; return; }
                  var dy2 = (lastY - f.clientY) * 2.5;
                  lastY = f.clientY;
                  if (dy2 !== 0) scrollByPx(dy2);
                }
              }
              function te(e) {
                if (!window.__HV_TOUCHPAD__) return;
                var n = e.touches.length;
                if (n === 0) {
                  if (active && !moved && !twoFinger) clickAt();
                  active = false; moved = false; twoFinger = false;
                } else if (n === 1 && twoFinger) {
                  // 双指抬起一指:重置基准点并恢复单指模式
                  lastX = e.touches[0].clientX;
                  lastY = e.touches[0].clientY;
                  twoFinger = false;
                  active = true;
                  moved = true; // 已产生过手势,防止全部抬起时幽灵单击
                }
              }
              // touchcancel:系统手势(如 Android 10+ 边缘返回手势)会中断触摸
              // 序列并派发 touchcancel——不处理则手势状态冻结,触摸板失效
              function tc(e) {
                if (!window.__HV_TOUCHPAD__) return;
                active = false; moved = false; twoFinger = false;
              }
              // 保存引用:关闭时据此 removeEventListener,实现真正卸载
              var hs = ts, hm = tm, he = te, hc = tc;
              // capture 阶段监听:比冒泡更早介入,防止 WebView 抢先消费触摸序列
              document.addEventListener('touchstart', hs, { passive: false, capture: true });
              document.addEventListener('touchmove', hm, { passive: false, capture: true });
              document.addEventListener('touchend', he, { passive: false, capture: true });
              document.addEventListener('touchcancel', hc, { passive: false, capture: true });
              // touch-action: none 接管手势——WebView 手势系统(缩放/滚动)会
              // 抢占双指触摸序列并发送 touchcancel,导致双指滚动失效;
              // none 后所有触摸事件完整派发给 JS
              document.documentElement.style.touchAction = 'none';
              if (document.body) document.body.style.touchAction = 'none';
              window.__HV_TP_CLEANUP__ = function () {
                document.removeEventListener('touchstart', hs, { capture: true });
                document.removeEventListener('touchmove', hm, { capture: true });
                document.removeEventListener('touchend', he, { capture: true });
                document.removeEventListener('touchcancel', hc, { capture: true });
                var el = document.getElementById('__hv_cursor');
                if (el && el.parentNode) el.parentNode.removeChild(el);
                var st2 = document.getElementById('__hv_tp_style');
                if (st2 && st2.parentNode) st2.parentNode.removeChild(st2);
                // 恢复默认手势
                document.documentElement.style.touchAction = '';
                if (document.body) document.body.style.touchAction = '';
              };
              moveTo(x, y);
              return window.__HV_TOUCHPAD__ === true;
            })();
        """.trimIndent()

        private val TOUCHPAD_DISABLE_JS = """
            (function () {
              if (!window.__HV_TOUCHPAD__) return true;
              // 完整拆卸:移除事件监听器与光标元素(此前仅移除光标,残留监听器
              // 在重新开启时会双处理事件并因旧闭包引用已删除元素而崩溃)
              if (typeof window.__HV_TP_CLEANUP__ === 'function') {
                try { window.__HV_TP_CLEANUP__(); } catch (e) {}
              }
              window.__HV_TOUCHPAD__ = false;
              return true;
            })();
        """.trimIndent()
    }
}
