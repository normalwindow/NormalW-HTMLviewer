package xyz.normalwindow.htmlviewer.ui.components

import androidx.compose.ui.graphics.Color

/** 文件类型:用于列表/网格的图标着色与徽标区分 */
enum class FileType(
    val color: Color,
    /** 图标容器底色(固定 alpha,明暗主题下均清晰) */
    val container: Color,
    val badge: String
) {
    HTML(Color(0xFFE64A19), Color(0x26E64A19), "HTML"),
    CSS(Color(0xFF1E88E5), Color(0x261E88E5), "CSS"),
    JS(Color(0xFFF9A825), Color(0x26F9A825), "JS"),
    TS(Color(0xFF00897B), Color(0x2600897B), "TS"),
    OTHER(Color(0xFF5C6BC0), Color(0x265C6BC0), "FILE")
}

fun fileTypeOf(name: String): FileType = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> FileType.HTML
    "css" -> FileType.CSS
    "js", "mjs", "cjs" -> FileType.JS
    "ts", "tsx" -> FileType.TS
    else -> FileType.OTHER
}
