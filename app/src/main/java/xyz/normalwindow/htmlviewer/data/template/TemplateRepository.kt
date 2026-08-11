package xyz.normalwindow.htmlviewer.data.template

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 内置模板信息 */
data class TemplateInfo(
    val fileName: String,
    val displayName: String,
    val description: String
)

/**
 * 内置 HTML 模板库(assets/templates/ 下的 .html 文件)。
 * 展示名/描述硬编码映射;未知模板回退为文件名。
 */
@Singleton
class TemplateRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val known: Map<String, Pair<String, String>> = mapOf(
        "html5-skeleton.html" to ("HTML5 基础骨架" to "标准文档结构与移动端适配"),
        "css-modern.html" to ("现代 CSS 布局" to "响应式卡片、深色模式与动效"),
        "js-interactive.html" to ("JavaScript 交互" to "事件监听与 DOM 操作示例")
    )

    /** 列出全部模板 */
    fun list(): List<TemplateInfo> =
        context.assets.list(TEMPLATE_DIR)
            ?.filter { it.endsWith(".html") }
            ?.sorted()
            ?.map { fileName ->
                val (display, desc) = known[fileName] ?: (fileName.removeSuffix(".html") to "")
                TemplateInfo(fileName, display, desc)
            }
            ?: emptyList()

    /** 读取模板内容;不存在返回 null */
    fun read(fileName: String): String? = runCatching {
        context.assets.open("$TEMPLATE_DIR/$fileName").bufferedReader().use { it.readText() }
    }.getOrNull()

    private companion object {
        const val TEMPLATE_DIR = "templates"
    }
}
