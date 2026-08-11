package xyz.normalwindow.htmlviewer.render

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.normalwindow.htmlviewer.BuildConfig
import xyz.normalwindow.htmlviewer.data.settings.EngineType
import javax.inject.Inject
import javax.inject.Singleton

/** 渲染器工厂:按设置创建轻量(WebView)/兼容(GeckoView)内核 */
@Singleton
class RendererFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun create(
        engine: EngineType,
        callbacks: RendererCallbacks? = null
    ): Renderer = when {
        // lite 变体未打包 GeckoView,Gecko 选择降级为系统 WebView
        engine == EngineType.GECKO && BuildConfig.GECKO_ENABLED ->
            GeckoRenderer(context.applicationContext, callbacks)
        else -> WebViewRenderer(context.applicationContext, callbacks)
    }
}
