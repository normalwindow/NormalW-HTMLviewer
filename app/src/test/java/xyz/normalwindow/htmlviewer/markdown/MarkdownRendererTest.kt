package xyz.normalwindow.htmlviewer.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.normalwindow.htmlviewer.data.markdown.MarkdownRenderer

/**
 * Markdown 渲染单测。
 *
 * 重点覆盖三类风险:
 * 1) 结构正确性(标题/列表/表格/代码块等块级元素)
 * 2) 行内标记与转义(标记不可互相污染、代码片段中的 * 不被当强调)
 * 3) 安全性(.md 中的 HTML/危险 URL 绝不能变成可执行内容)
 */
class MarkdownRendererTest {

    private fun body(md: String) = MarkdownRenderer.renderBody(md)

    // ---------- 块级 ----------

    @Test
    fun `ATX 标题按级别渲染`() {
        assertEquals("<h1>标题一</h1>\n", body("# 标题一"))
        assertEquals("<h3>三级</h3>\n", body("### 三级"))
        assertEquals("<h6>六级</h6>\n", body("###### 六级"))
    }

    @Test
    fun `井号后无空格不算标题`() {
        // #tag 是话题标签语义,不应变成 h1
        val html = body("#标签内容")
        assertFalse(html.contains("<h1>"))
        assertTrue(html.contains("<p>"))
    }

    @Test
    fun `七级井号不被识别为标题`() {
        val html = body("####### 太深了")
        assertFalse(html.contains("<h7"))
        assertTrue(html.contains("<p>"))
    }

    @Test
    fun `标题尾部井号被裁掉`() {
        assertEquals("<h2>闭合标题</h2>\n", body("## 闭合标题 ##"))
    }

    @Test
    fun `无序列表渲染为 ul`() {
        val html = body("- 甲\n- 乙\n- 丙")
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>甲</li>"))
        assertTrue(html.contains("<li>丙</li>"))
        assertEquals(3, Regex("<li>").findAll(html).count())
    }

    @Test
    fun `有序列表保留起始序号`() {
        val html = body("3. 第三项\n4. 第四项")
        assertTrue(html.contains("<ol start=\"3\">"))
        assertTrue(html.contains("<li>第三项</li>"))
    }

    @Test
    fun `任务列表渲染为禁用复选框`() {
        val html = body("- [x] 已完成\n- [ ] 待办")
        assertTrue(html.contains("type=\"checkbox\""))
        assertTrue(html.contains("checked"))
        assertTrue(html.contains("disabled"))
    }

    @Test
    fun `嵌套列表产生嵌套 ul`() {
        val html = body("- 外层\n  - 内层")
        // 至少要出现两个 ul(外层 + 内层)
        assertTrue(Regex("<ul>").findAll(html).count() >= 2)
        assertTrue(html.contains("外层"))
        assertTrue(html.contains("内层"))
    }

    @Test
    fun `围栏代码块保留原样并标注语言`() {
        val html = body("```kotlin\nval a = 1\n```")
        assertTrue(html.contains("<pre><code class=\"language-kotlin\">"))
        assertTrue(html.contains("val a = 1"))
    }

    @Test
    fun `波浪号围栏同样支持`() {
        val html = body("~~~\nplain\n~~~")
        assertTrue(html.contains("<pre><code>"))
        assertTrue(html.contains("plain"))
    }

    @Test
    fun `代码块内容不被当作 Markdown 解析`() {
        // 代码块里的 # 与 ** 必须原样保留,不能变成标题/粗体
        val html = body("```\n# 不是标题\n**不是粗体**\n```")
        assertFalse(html.contains("<h1>"))
        assertFalse(html.contains("<strong>"))
        assertTrue(html.contains("# 不是标题"))
        assertTrue(html.contains("**不是粗体**"))
    }

    @Test
    fun `水平线渲染为 hr`() {
        assertTrue(body("---").contains("<hr>"))
        assertTrue(body("***").contains("<hr>"))
        assertTrue(body("___").contains("<hr>"))
    }

    @Test
    fun `引用块渲染为 blockquote 且内部可含列表`() {
        val html = body("> 引用内容\n> - 引用内列表")
        assertTrue(html.contains("<blockquote>"))
        assertTrue(html.contains("引用内容"))
        assertTrue(html.contains("<li>引用内列表</li>"))
    }

    @Test
    fun `表格渲染含表头与对齐`() {
        val md = """
            | 名称 | 数量 | 备注 |
            |:-----|:----:|-----:|
            | 甲   | 1    | 左对齐 |
        """.trimIndent()
        val html = body(md)
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th"))
        assertTrue(html.contains("名称"))
        assertTrue(html.contains("<td"))
        // 对齐样式来自分隔行
        assertTrue(html.contains("text-align:center"))
        assertTrue(html.contains("text-align:right"))
    }

    @Test
    fun `表格列数以表头为准补齐`() {
        val md = "| A | B |\n|---|---|\n| 1 |"
        val html = body(md)
        // 第二行只有一列,应补出空 td 而不是错位
        assertEquals(2, Regex("<td").findAll(html).count())
    }

    @Test
    fun `缩进代码块被识别`() {
        val html = body("    缩进代码")
        assertTrue(html.contains("<pre><code>缩进代码"))
    }

    @Test
    fun `多行段落合并为单个 p`() {
        val html = body("第一行\n第二行")
        assertEquals(1, Regex("<p>").findAll(html).count())
        assertTrue(html.contains("第一行"))
        assertTrue(html.contains("第二行"))
    }

    @Test
    fun `空行分隔出两个段落`() {
        val html = body("甲\n\n乙")
        assertEquals(2, Regex("<p>").findAll(html).count())
    }

    // ---------- 行内 ----------

    @Test
    fun `粗体与斜体渲染`() {
        assertTrue(body("**粗**").contains("<strong>粗</strong>"))
        assertTrue(body("*斜*").contains("<em>斜</em>"))
        assertTrue(body("~~删~~").contains("<del>删</del>"))
    }

    @Test
    fun `粗体优先于斜体不被拆坏`() {
        // **x** 若被 * 先匹配会产出 <em>*x*</em> 之类错误结构
        val html = body("**重要**")
        assertTrue(html.contains("<strong>重要</strong>"))
        assertFalse(html.contains("<em>"))
    }

    @Test
    fun `行内代码中的星号不被当作强调`() {
        val html = body("`a * b * c`")
        assertTrue(html.contains("<code>a * b * c</code>"))
        assertFalse(html.contains("<em>"))
    }

    @Test
    fun `行内代码中的 HTML 被转义`() {
        val html = body("`<div>`")
        assertTrue(html.contains("&lt;div&gt;"))
        assertFalse(html.contains("<div>"))
    }

    @Test
    fun `链接渲染带 noopener`() {
        val html = body("[点我](https://example.com)")
        assertTrue(html.contains("href=\"https://example.com\""))
        assertTrue(html.contains(">点我</a>"))
        assertTrue(html.contains("noopener"))
    }

    @Test
    fun `图片渲染为 img`() {
        val html = body("![替代](https://example.com/a.png)")
        assertTrue(html.contains("<img src=\"https://example.com/a.png\""))
        assertTrue(html.contains("alt=\"替代\""))
    }

    @Test
    fun `自动链接识别裸 URL`() {
        val html = body("<https://example.com>")
        assertTrue(html.contains("<a href=\"https://example.com\""))
    }

    @Test
    fun `相对路径链接保持可用`() {
        val html = body("[同级](other.html)")
        assertTrue(html.contains("href=\"other.html\""))
    }

    @Test
    fun `URL 中的配对圆括号不被截断`() {
        // 维基百科等条目形如 /wiki/Foo_(bar),不能截断也不能把 ) 漏进正文
        val html = body("[条目](https://zh.wikipedia.org/wiki/Foo_(bar))")
        assertTrue(
            "URL 应保留括号: $html",
            html.contains("href=\"https://zh.wikipedia.org/wiki/Foo_(bar)\"")
        )
        assertFalse("不应残留多余右括号: $html", html.contains("</a>)"))
    }

    @Test
    fun `带标题的链接渲染 href 且忽略 title`() {
        val html = body("[点我](https://e.com \"悬停标题\")")
        assertTrue(html.contains("href=\"https://e.com\""))
        assertTrue(html.contains(">点我</a>"))
    }

    @Test
    fun `图片 URL 中的配对圆括号不被截断`() {
        val html = body("![图](https://e.com/a_(1).png)")
        assertTrue(html.contains("src=\"https://e.com/a_(1).png\""))
    }

    // ---------- 安全性(关键) ----------

    @Test
    fun `原文中的 script 标签被转义不执行`() {
        val html = body("<script>alert(1)</script>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `img onerror 注入被转义`() {
        val html = body("<img src=x onerror=alert(1)>")
        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;img"))
    }

    @Test
    fun `javascript 协议链接被拒绝`() {
        val html = body("[点我](javascript:alert(1))")
        assertFalse(html.contains("javascript:"))
        // 链接被降级为纯文本标签,不产生 a 标签
        assertFalse(html.contains("<a href=\"javascript"))
    }

    @Test
    fun `data 协议链接被拒绝`() {
        val html = body("[x](data:text/html;base64,PHNjcmlwdD4=)")
        assertFalse(html.contains("<a href=\"data:"))
    }

    @Test
    fun `vbscript 协议链接被拒绝`() {
        val html = body("[x](vbscript:msgbox)")
        assertFalse(html.contains("vbscript:"))
    }

    @Test
    fun `图片的 javascript 协议同样被拒绝`() {
        val html = body("![x](javascript:alert(1))")
        assertFalse(html.contains("<img src=\"javascript"))
    }

    @Test
    fun `属性中的引号被转义无法逃逸`() {
        // alt/title 里带引号不能闭合属性后注入新属性
        val html = body("![a\"onerror=\"alert(1)](https://e.com/x.png)")
        assertFalse(html.contains("onerror=\"alert(1)\""))
    }

    @Test
    fun `完整文档外壳包含 charset 与样式`() {
        val doc = MarkdownRenderer.renderDocument("# 标题", title = "我的文档")
        assertTrue(doc.contains("<!DOCTYPE html>"))
        assertTrue(doc.contains("<meta charset=\"UTF-8\">"))
        assertTrue(doc.contains("<title>我的文档</title>"))
        assertTrue(doc.contains("<h1>标题</h1>"))
    }

    @Test
    fun `文档标题中的 HTML 被转义`() {
        val doc = MarkdownRenderer.renderDocument("x", title = "<script>x</script>")
        assertFalse(doc.contains("<title><script>"))
        assertTrue(doc.contains("&lt;script&gt;"))
    }

    @Test
    fun `智能处理空输入与纯空白`() {
        assertEquals("", MarkdownRenderer.renderBody(""))
        assertEquals("", MarkdownRenderer.renderBody("   \n\n  "))
    }

    @Test
    fun `中文与 emoji 原样保留`() {
        val html = body("中文内容 🎉 与符号 & 符号")
        assertTrue(html.contains("中文内容 🎉"))
        // & 必须转义,不能产生裸 &
        assertTrue(html.contains("&amp;"))
    }

    // ---------- 跨平台正则语义守护(Android ICU vs 桌面 JVM) ----------

    /**
     * 回归守护:Android 的 java.util.regex 走 ICU,`\w` 是 Unicode 感知的,
     * 会把中文当作单词字符;桌面 JVM 的 `\w` 只匹配 ASCII。
     *
     * 曾因此出现「单元测试全绿、真机上斜体星号原样显示」的缺陷:
     * `*斜体*与` 的后向断言用 `\w` 时,桌面通过、设备失败。
     * 这里用 UNICODE_CHARACTER_CLASS 显式模拟 Android 语义,确保模式在
     * 两端行为一致(即:不得依赖 `\w` 的 ASCII 假设)。
     */
    @Test
    fun `斜体模式在 Unicode 感知语义下同样匹配中文`() {
        // 直接用 java.util.regex 打开 UNICODE_CHARACTER_CLASS,模拟 Android(ICU)语义
        val androidLike = java.util.regex.Pattern.compile(
            MarkdownRenderer.ITALIC_PATTERN,
            java.util.regex.Pattern.UNICODE_CASE or java.util.regex.Pattern.UNICODE_CHARACTER_CLASS
        )
        // 中文紧邻星号:Android 语义下也必须匹配
        val samples = listOf(
            "*斜体*与",
            "、*斜体*与",
            "这是**粗体**、*斜体*与~~删除线~~混排"
        )
        samples.forEach { s ->
            assertTrue(
                "Unicode 感知下未匹配到斜体: $s",
                androidLike.matcher(s).find()
            )
        }
        // snake_case 仍不应被当作斜体
        assertFalse(androidLike.matcher("a * b_c_d * e").find())
    }

    @Test
    fun `模式中不得使用 Unicode 感知的单词字符类`() {
        // 直接守护源码:出现 \w / \W / \b 即视为回归(两端语义不一致)
        val pattern = MarkdownRenderer.ITALIC_PATTERN
        assertFalse("斜体模式不应含 \\w: $pattern", pattern.contains("\\w"))
        assertFalse("斜体模式不应含 \\W: $pattern", pattern.contains("\\W"))
        assertFalse("斜体模式不应含 \\b: $pattern", pattern.contains("\\b"))
    }
}
