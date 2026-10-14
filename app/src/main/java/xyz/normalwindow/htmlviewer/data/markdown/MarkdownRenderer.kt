package xyz.normalwindow.htmlviewer.data.markdown

/**
 * 轻量 Markdown → HTML 渲染器(离线自实现,无第三方依赖)。
 *
 * 为什么自实现而不是引入 commonmark-java:
 * - 本应用是「无网络也能看的 HTML 预览器」,预览链路不应依赖额外运行时;
 * - 需求集中在文档阅读(标题/列表/表格/代码/链接/强调),CommonMark 全量
 *   规范中的边缘语法(HTML 块、引用式脚注等)对移动端阅读收益很低;
 * - 自实现可完全掌控**转义策略**:所有文本先转义再拼装,默认不放行内联 HTML,
 *   从根上避免 .md 里写 `<script>` 造成预览页 XSS(见 [escapeHtml])。
 *
 * 支持:ATX 标题(#~######)、无序/有序列表(含嵌套)、任务列表、引用块、
 * 围栏代码块(```/~~~,带语言标记)、缩进代码块、表格、水平线、链接、图片、
 * 行内代码、粗体/斜体/删除线、自动链接、硬换行(行尾两空格)。
 */
object MarkdownRenderer {

    /** 渲染入口:[source] 为 Markdown 原文,返回完整 HTML 文档 */
    fun renderDocument(source: String, title: String = "Markdown"): String {
        val body = renderBody(source)
        return """
            |<!DOCTYPE html>
            |<html lang="zh-CN">
            |<head>
            |<meta charset="UTF-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1.0">
            |<title>${escapeHtml(title)}</title>
            |<style>$DOC_CSS</style>
            |</head>
            |<body>
            |<article class="markdown-body">
            |$body
            |</article>
            |</body>
            |</html>
            |
        """.trimMargin()
    }

    /** 渲染正文片段(不含 html/head/body 外壳) */
    fun renderBody(source: String): String {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // 围栏代码块:``` 或 ~~~,直到同种围栏闭合
            val fence = matchFence(line)
            if (fence != null) {
                val buf = StringBuilder()
                i++
                while (i < lines.size && !isFenceClose(lines[i], fence.first)) {
                    buf.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // 跳过闭合围栏
                out.append("<pre><code")
                if (fence.second.isNotEmpty()) {
                    out.append(" class=\"language-").append(escapeHtml(fence.second)).append('"')
                }
                out.append('>').append(escapeHtml(buf.toString())).append("</code></pre>\n")
                continue
            }

            // 水平线:--- / *** / ___ (三个及以上,允许中间空格)
            if (isHorizontalRule(line)) {
                out.append("<hr>\n")
                i++
                continue
            }

            // ATX 标题
            val heading = matchHeading(line)
            if (heading != null) {
                val (level, text) = heading
                out.append("<h").append(level).append('>')
                    .append(renderInline(text)).append("</h").append(level).append(">\n")
                i++
                continue
            }

            // 引用块:> 连续行合并(内部递归渲染,支持引用中套列表/代码)
            if (line.trimStart().startsWith(">")) {
                val buf = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    val t = lines[i].trimStart().removePrefix(">")
                    // 去掉引用标记后的一个前导空格(CommonMark 语义)
                    buf.add(if (t.startsWith(" ")) t.substring(1) else t)
                    i++
                }
                out.append("<blockquote>\n").append(renderBody(buf.joinToString("\n")))
                    .append("</blockquote>\n")
                continue
            }

            // 表格:表头 + 分隔行(| --- | --- |)
            if (i + 1 < lines.size && line.contains('|') && isTableSeparator(lines[i + 1])) {
                val header = splitTableRow(line)
                val aligns = parseAlignments(lines[i + 1])
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank() &&
                    !isTableSeparator(lines[i])
                ) {
                    rows.add(splitTableRow(lines[i]))
                    i++
                }
                out.append("<table>\n<thead>\n<tr>")
                header.forEachIndexed { idx, cell ->
                    out.append("<th").append(alignAttr(aligns, idx)).append('>')
                        .append(renderInline(cell)).append("</th>")
                }
                out.append("</tr>\n</thead>\n<tbody>\n")
                rows.forEach { row ->
                    out.append("<tr>")
                    // 以表头列数为准补齐/截断,避免错列
                    for (idx in header.indices) {
                        out.append("<td").append(alignAttr(aligns, idx)).append('>')
                            .append(renderInline(row.getOrElse(idx) { "" })).append("</td>")
                    }
                    out.append("</tr>\n")
                }
                out.append("</tbody>\n</table>\n")
                continue
            }

            // 列表(无序/有序,支持嵌套与任务列表)
            if (matchListItem(line) != null) {
                i = renderList(lines, i, out, 0)
                continue
            }

            // 缩进代码块(4 空格 / Tab),未被列表消费时
            if (line.startsWith("    ") || line.startsWith("\t")) {
                val buf = StringBuilder()
                while (i < lines.size &&
                    (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())
                ) {
                    if (lines[i].isBlank()) {
                        // 代码块内的空行保留(尾随空行在收尾时裁掉)
                        buf.append('\n')
                    } else {
                        buf.append(lines[i].removePrefix("\t").removePrefix("    ")).append('\n')
                    }
                    i++
                }
                out.append("<pre><code>").append(escapeHtml(buf.toString().trimEnd('\n')))
                    .append("</code></pre>\n")
                continue
            }

            // 空行:段落分隔
            if (line.isBlank()) {
                i++
                continue
            }

            // 段落:连续非空行合并(行内硬换行由 renderInline 处理)
            val para = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() &&
                matchFence(lines[i]) == null && matchHeading(lines[i]) == null &&
                !isHorizontalRule(lines[i]) &&
                !lines[i].trimStart().startsWith(">") &&
                matchListItem(lines[i]) == null
            ) {
                para.add(lines[i])
                i++
            }
            out.append("<p>").append(renderInline(para.joinToString("\n"))).append("</p>\n")
        }
        return out.toString()
    }

    // ---------- 块级识别 ----------

    /** @return (围栏字符, 语言标记) 或 null */
    private fun matchFence(line: String): Pair<Char, String>? {
        val t = line.trimStart()
        if (t.length < 3) return null
        val ch = t[0]
        if (ch != '`' && ch != '~') return null
        var count = 0
        while (count < t.length && t[count] == ch) count++
        if (count < 3) return null
        // 反引号围栏的信息串不能含反引号(CommonMark)
        val info = t.substring(count).trim()
        if (ch == '`' && info.contains('`')) return null
        return ch to info.split(Regex("\\s+")).firstOrNull().orEmpty()
    }

    private fun isFenceClose(line: String, ch: Char): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        var count = 0
        while (count < t.length && t[count] == ch) count++
        return count >= 3 && t.substring(count).isBlank()
    }

    private fun isHorizontalRule(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        val c = t[0]
        if (c != '-' && c != '*' && c != '_') return false
        var n = 0
        for (ch in t) {
            when {
                ch == c -> n++
                ch == ' ' || ch == '\t' -> Unit
                else -> return false
            }
        }
        return n >= 3
    }

    /** @return (级别, 标题文本) 或 null */
    private fun matchHeading(line: String): Pair<Int, String>? {
        val t = line.trimStart()
        if (!t.startsWith("#")) return null
        var level = 0
        while (level < t.length && t[level] == '#') level++
        if (level > 6) return null
        // # 后必须是空格或行尾(避免把 #tag 当标题)
        if (level < t.length && t[level] != ' ' && t[level] != '\t') return null
        val text = t.substring(level).trim().trimEnd('#').trim()
        return level to text
    }

    /** 列表项:[缩进宽度, 标记, 内容, 是否有序] */
    private data class ListItem(val indent: Int, val marker: String, val content: String, val ordered: Boolean)

    private fun matchListItem(line: String): ListItem? {
        if (line.isBlank()) return null
        val indent = line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) return null else it }
        // 缩进 >= 4 视为代码块而非列表(顶层判定)
        if (indent >= 4) return null
        val rest = line.substring(indent)
        val bulletMatch = Regex("^([-*+])(\\s+|$)").find(rest)
        if (bulletMatch != null) {
            return ListItem(indent, bulletMatch.value.trim(), rest.substring(bulletMatch.value.length), false)
        }
        val orderedMatch = Regex("^(\\d{1,9})([.)])(\\s+|$)").find(rest)
        if (orderedMatch != null) {
            return ListItem(indent, orderedMatch.value.trim(), rest.substring(orderedMatch.value.length), true)
        }
        return null
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (!t.contains('-') || !t.contains('|')) return false
        // 每个单元格只能是 :-: 形式
        val cells = splitTableRow(t)
        if (cells.isEmpty()) return false
        return cells.all { c -> Regex("^:?-{1,}:?$").matches(c.trim()) }
    }

    private fun splitTableRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        // 按未转义的 | 切分
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var escaped = false
        for (ch in t) {
            when {
                escaped -> { sb.append(ch); escaped = false }
                ch == '\\' -> { sb.append(ch); escaped = true }
                ch == '|' -> { cells.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        cells.add(sb.toString().trim())
        return cells
    }

    private fun parseAlignments(sep: String): List<String> =
        splitTableRow(sep).map { c ->
            val t = c.trim()
            when {
                t.startsWith(":") && t.endsWith(":") -> "center"
                t.endsWith(":") -> "right"
                else -> "left"
            }
        }

    private fun alignAttr(aligns: List<String>, idx: Int): String {
        val a = aligns.getOrNull(idx) ?: return ""
        return " style=\"text-align:$a\""
    }

    // ---------- 列表渲染(递归处理嵌套) ----------

    private fun renderList(
        lines: List<String>,
        start: Int,
        out: StringBuilder,
        depth: Int
    ): Int {
        var i = start
        val first = matchListItem(lines[i]) ?: return i + 1
        val baseIndent = first.indent
        val ordered = first.ordered
        val tag = if (ordered) "ol" else "ul"
        // 有序列表起始序号
        val startNum = if (ordered) {
            Regex("^(\\d+)").find(first.marker)?.groupValues?.get(1)?.toIntOrNull()
        } else null
        out.append('<').append(tag)
        if (startNum != null && startNum != 1) out.append(" start=\"").append(startNum).append('"')
        out.append(">\n")

        while (i < lines.size) {
            val item = matchListItem(lines[i])
            if (item == null) {
                // 列表项内的续行(缩进对齐的内容行)并入上一项
                if (lines[i].isBlank()) break
                if (lines[i].startsWith(" ") || lines[i].startsWith("\t")) {
                    break
                }
                break
            }
            // 缩进小于基准:当前列表结束;大于基准:嵌套(交给递归)
            if (item.indent < baseIndent) break
            if (item.indent > baseIndent) break
            if (item.ordered != ordered) break

            // 收集该项的续行(更深缩进且不是同级新项)
            val contentLines = mutableListOf(item.content)
            var j = i + 1
            while (j < lines.size) {
                val nxt = lines[j]
                if (nxt.isBlank()) {
                    // 空行后若仍是本列表内容则继续,否则结束
                    val after = lines.getOrNull(j + 1) ?: break
                    val afterItem = matchListItem(after)
                    if (afterItem != null && afterItem.indent >= baseIndent) { j++; continue }
                    if (after.startsWith(" ") || after.startsWith("\t")) { j++; continue }
                    break
                }
                val nested = matchListItem(nxt)
                val indentWidth = nxt.indexOfFirst { it != ' ' && it != '\t' }
                    .let { if (it < 0) 0 else it }
                if (nested == null && indentWidth > baseIndent) {
                    contentLines.add(nxt.trim())
                    j++
                } else if (nested != null && nested.indent > baseIndent) {
                    break // 嵌套列表在下方单独处理
                } else {
                    break
                }
            }

            // 任务列表:[ ] / [x]
            val taskMatch = Regex("^\\[([ xX])]\\s+(.*)$").find(contentLines.first())
            out.append("<li>")
            if (taskMatch != null) {
                val checked = taskMatch.groupValues[1].equals("x", ignoreCase = true)
                out.append("<input type=\"checkbox\" disabled")
                if (checked) out.append(" checked")
                out.append("> ")
                out.append(renderInline(taskMatch.groupValues[2]))
            } else {
                out.append(renderInline(contentLines.first()))
            }

            // 嵌套子列表
            var k = j
            if (k < lines.size) {
                val child = matchListItem(lines[k])
                if (child != null && child.indent > baseIndent) {
                    out.append('\n')
                    k = renderList(lines, k, out, depth + 1)
                }
            }
            out.append("</li>\n")
            i = k
        }

        out.append("</").append(tag).append(">\n")
        return i
    }

    // ---------- 行内 ----------

    /**
     * 行内渲染。
     *
     * 安全策略:先把整段文本按 HTML 转义,再做行内标记替换——因此原文中的
     * `<script>` 已变成 `&lt;script&gt;`,不可能被解析为标签;链接 URL 额外
     * 校验协议(拒绝 javascript:/data: 等),双重防线。
     */
    fun renderInline(text: String): String {
        // 1) 先抽出代码片段占位,避免其中的 * _ 等被当作强调解析
        val codeSpans = mutableListOf<String>()
        var s = text.replace(CODE_SPAN_RE) { m ->
            val code = m.groupValues[1]
            codeSpans.add("<code>" + escapeHtml(code) + "</code>")
            "\u0000CODE${codeSpans.size - 1}\u0000"
        }

        // 2) 整体转义(此后文本中不含可解析标签)
        s = escapeHtml(s)

        // 3) 行内标记(转义后的文本上操作,产物是可信标签)
        // 图片先于链接(语法更具体)
        s = IMAGE_RE.replace(s) { m ->
            val alt = m.groupValues[1]
            val url = sanitizeUrl(m.groupValues[2])
            if (url == null) alt else "<img src=\"$url\" alt=\"$alt\">"
        }
        s = LINK_RE.replace(s) { m ->
            val label = m.groupValues[1]
            val url = sanitizeUrl(m.groupValues[2])
            if (url == null) label
            else "<a href=\"$url\" target=\"_blank\" rel=\"noopener noreferrer\">$label</a>"
        }
        // 自动链接 <https://...>(转义后尖括号已是 &lt; &gt;)
        s = AUTOLINK_RE.replace(s) { m ->
            val url = sanitizeUrl(m.groupValues[1])
            if (url == null) m.value
            else "<a href=\"$url\" target=\"_blank\" rel=\"noopener noreferrer\">$url</a>"
        }
        // 粗体 / 斜体 / 删除线(先长后短,避免 ** 被 * 抢先匹配)
        s = BOLD_RE.replace(s) { m -> "<strong>${m.groupValues[1]}</strong>" }
        s = ITALIC_RE.replace(s) { m -> "<em>${m.groupValues[1]}</em>" }
        s = STRIKE_RE.replace(s) { m -> "<del>${m.groupValues[1]}</del>" }

        // 4) 还原代码片段
        codeSpans.forEachIndexed { idx, html ->
            s = s.replace("\u0000CODE$idx\u0000", html)
        }

        // 5) 硬换行:行尾两空格或反斜杠 → <br>;段内单个换行按软换行处理为空格
        s = HARD_BREAK_RE.replace(s) { "<br>\n" }
        s = s.replace("\n", "\n")
        return s
    }

    /** 仅放行 http/https/mailto/相对路径与锚点;其余(如 javascript:)返回 null */
    private fun sanitizeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val lower = url.lowercase()
        // 协议相对与相对路径安全
        if (lower.startsWith("//")) return escapeHtml(url)
        val schemeMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(url)
        if (schemeMatch == null) return escapeHtml(url) // 相对路径/锚点
        val scheme = schemeMatch.groupValues[1].lowercase()
        return when (scheme) {
            "http", "https", "mailto", "tel", "ftp" -> escapeHtml(url)
            else -> null // javascript: / data: / vbscript: 等一律拒绝
        }
    }

    /** HTML 转义(文本先转义再拼装,预览页不会执行 .md 中的标签/脚本) */
    fun escapeHtml(s: String): String = buildString(s.length + 16) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    // ---------- 正则 ----------

    private val CODE_SPAN_RE = Regex("`([^`]+)`")
    // URL 允许一层配对的圆括号:维基百科等链接形如 /wiki/Foo_(bar),
    // 若用 [^)\s]+ 会在第一个 ) 处截断,并残留多余的 ) 到正文里。
    // 可选 title 已在 escapeHtml 之后匹配,故引号写作 &quot;(title 本身不使用,
    // 仅需正确吞掉以免污染 URL 或残留字符)。
    private val IMAGE_RE =
        Regex("!\\[([^\\]]*)]\\(((?:[^()\\s]|\\([^()]*\\))+)(?:\\s+&quot;.*?&quot;)?\\)")
    private val LINK_RE =
        Regex("\\[([^\\]]*)]\\(((?:[^()\\s]|\\([^()]*\\))+)(?:\\s+&quot;.*?&quot;)?\\)")
    private val AUTOLINK_RE = Regex("&lt;(https?://[^\\s&]+)&gt;")
    private val BOLD_RE = Regex("\\*\\*(?!\\s)(.+?)(?<!\\s)\\*\\*", RegexOption.DOT_MATCHES_ALL)
    private val STRIKE_RE = Regex("~~(?!\\s)(.+?)(?<!\\s)~~", RegexOption.DOT_MATCHES_ALL)

    /**
     * 斜体:`*文字*`,两侧不能紧邻单词字符(避免 snake_case 被误判)。
     *
     * 注意:这里刻意用显式的 `[A-Za-z0-9_]` 而**不是** `\w`。
     * Android 的 java.util.regex 基于 ICU,`\w` 是 Unicode 感知的——会把中文等
     * CJK 字母也算作单词字符,于是 `*斜体*与` 中的后向断言 `(?![*\w])` 因后接
     * 汉字「与」而失败,整段斜体不渲染(设备上表现为星号原样显示);
     * 而桌面 JVM 的 `\w` 仅匹配 ASCII,单元测试因此恒为通过,无法暴露该问题。
     * 用 ASCII 字符类可同时满足两端语义与预期。
     *
     * 该陷阱由 [MarkdownRendererTest] 中用 UNICODE_CHARACTER_CLASS 编译的用例守护。
     */
    internal const val ITALIC_PATTERN =
        "(?<![*A-Za-z0-9_])\\*(?!\\s)([^*]+?)(?<!\\s)\\*(?![*A-Za-z0-9_])"

    private val ITALIC_RE = Regex(ITALIC_PATTERN)
    private val HARD_BREAK_RE = Regex(" {2,}\n")

    /** 文档样式:跟随系统深浅色,排版面向移动端阅读 */
    private val DOC_CSS = """
        :root { color-scheme: light dark; }
        * { box-sizing: border-box; }
        body {
          margin: 0;
          font-family: system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
          line-height: 1.75;
          color: #1b1b1f;
          background: #ffffff;
          -webkit-text-size-adjust: 100%;
        }
        .markdown-body { max-width: 760px; margin: 0 auto; padding: 20px 18px 56px; }
        h1, h2, h3, h4, h5, h6 { line-height: 1.3; margin: 1.4em 0 .6em; font-weight: 600; }
        h1 { font-size: 1.7em; padding-bottom: .3em; border-bottom: 1px solid #e5e5ea; }
        h2 { font-size: 1.4em; padding-bottom: .3em; border-bottom: 1px solid #ececf1; }
        h3 { font-size: 1.2em; }
        h4 { font-size: 1.05em; }
        h5, h6 { font-size: 1em; color: #55555f; }
        p { margin: .8em 0; }
        a { color: #4355b9; text-decoration: none; }
        a:active { text-decoration: underline; }
        code {
          font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
          font-size: .9em;
          background: #f2f2f7;
          padding: .15em .4em;
          border-radius: 5px;
          word-break: break-word;
        }
        pre {
          background: #f6f6fa;
          border: 1px solid #e8e8ef;
          border-radius: 10px;
          padding: 14px;
          overflow-x: auto;
          line-height: 1.6;
        }
        pre code { background: none; padding: 0; font-size: .86em; white-space: pre; }
        blockquote {
          margin: .9em 0;
          padding: .2em 1em;
          color: #55555f;
          border-left: 4px solid #d5d5e0;
          background: #fafafd;
          border-radius: 0 8px 8px 0;
        }
        blockquote > :first-child { margin-top: .4em; }
        blockquote > :last-child { margin-bottom: .4em; }
        ul, ol { padding-left: 1.6em; margin: .7em 0; }
        li { margin: .3em 0; }
        li > ul, li > ol { margin: .3em 0; }
        input[type=checkbox] { margin-right: .4em; }
        table {
          border-collapse: collapse;
          width: 100%;
          margin: .9em 0;
          display: block;
          overflow-x: auto;
          font-size: .95em;
        }
        th, td { border: 1px solid #e2e2ea; padding: 7px 11px; }
        th { background: #f5f5fa; font-weight: 600; }
        hr { border: none; border-top: 1px solid #e5e5ea; margin: 1.6em 0; }
        img { max-width: 100%; height: auto; border-radius: 8px; }
        del { color: #77777f; }
        @media (prefers-color-scheme: dark) {
          body { color: #e6e6ea; background: #16161a; }
          h1, h2 { border-bottom-color: #2e2e36; }
          h5, h6 { color: #a0a0ab; }
          a { color: #9fb0ff; }
          code { background: #26262e; }
          pre { background: #1d1d23; border-color: #2e2e36; }
          blockquote { color: #a8a8b3; border-left-color: #3a3a46; background: #1c1c22; }
          th, td { border-color: #2e2e36; }
          th { background: #22222a; }
          hr { border-top-color: #2e2e36; }
          del { color: #8a8a95; }
        }
    """.trimIndent()
}
