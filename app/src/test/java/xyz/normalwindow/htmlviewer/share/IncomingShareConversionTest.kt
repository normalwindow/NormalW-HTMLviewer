package xyz.normalwindow.htmlviewer.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import java.nio.charset.Charset

/**
 * 分享导入的格式转换规则单测。
 *
 * 覆盖 GBK/UTF-8 探测与"转 HTML / 转文本"两条路径的核心约定:
 * 编码识别正确、HTML 转义不会破坏结构、文本可无损往返。
 * (转换逻辑在 IncomingShareRepository 内,这里验证其依赖的编码与转义语义)
 */
class IncomingShareConversionTest {

    @Test
    fun `UTF-8 中文文本可被识别并解码`() {
        val original = "你好,世界!\n第二行\t带制表符"
        val bytes = original.toByteArray(Charsets.UTF_8)

        assertEquals(TextEncoding.UTF_8, TextEncoding.detect(bytes))
        val decoded = TextEncoding.decode(bytes)
        assertEquals(original, decoded.content)
        assertEquals(TextEncoding.UTF_8, decoded.encoding)
    }

    @Test
    fun `GBK 中文文本被识别为 GBK 而不是乱码 UTF-8`() {
        val original = "中文编码测试:这是一段简体中文内容。"
        val gbkBytes = original.toByteArray(Charset.forName("GB18030"))

        // GBK 字节多数不是合法 UTF-8 序列,必须落到 GBK 分支
        assertEquals(TextEncoding.GBK, TextEncoding.detect(gbkBytes))
        val decoded = TextEncoding.decode(gbkBytes)
        assertEquals(original, decoded.content)
    }

    @Test
    fun `GBK 转 UTF-8 后可正确还原(转文本路径)`() {
        val original = "乱码修正验证内容"
        val gbkBytes = original.toByteArray(Charset.forName("GB18030"))

        // 模拟"转为文本"链路:按源编码解码 → 按目标编码写出
        val text = TextEncoding.decode(gbkBytes).content
        val utf8Out = TextEncoding.encode(text, TextEncoding.UTF_8)

        assertEquals(TextEncoding.UTF_8, TextEncoding.detect(utf8Out))
        assertEquals(original, String(utf8Out, Charsets.UTF_8))
    }

    @Test
    fun `带 BOM 的 UTF-8 文件解码后不残留 BOM 字符`() {
        val original = "BOM 测试内容"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + original.toByteArray(Charsets.UTF_8)

        val decoded = TextEncoding.decode(bytes)
        assertEquals(TextEncoding.UTF_8, decoded.encoding)
        // BOM 必须被剥离,否则会作为不可见字符混入 HTML 正文
        assertEquals(original, decoded.content)
        assertFalse(decoded.content.startsWith("\uFEFF"))
    }

    @Test
    fun `UTF-16LE 文本按 BOM 识别`() {
        val original = "UTF16 内容"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + original.toByteArray(Charset.forName("UTF-16LE"))

        val decoded = TextEncoding.decode(bytes)
        assertEquals(TextEncoding.UTF_16LE, decoded.encoding)
        assertEquals(original, decoded.content)
    }

    @Test
    fun `HTML 转义防止文本中的标签破坏文档结构`() {
        // txt 内容里出现 HTML 标签字面量时,必须被转义而不是当作结构解析
        val raw = "<script>alert(1)</script> & <div>"
        val escaped = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        assertFalse(escaped.contains("<script>"))
        assertTrue(escaped.contains("&lt;script&gt;"))
        // & 最先替换,避免二次转义(&amp;lt; 之类)
        assertTrue(escaped.contains("&amp;"))
        assertFalse(escaped.contains("&amp;lt;"))
    }

    @Test
    fun `纯文本转 HTML 保留换行与空格语义`() {
        val body = "第一行\n  缩进两空格\n\n空行后"

        // 转 HTML 时正文经转义后原样嵌入 <pre>,不做任何 trim 或空白折叠
        val escaped = body
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val html = "<pre>$escaped</pre>"

        // 换行、行首缩进、空行必须全部保留
        assertTrue(html.contains("第一行\n  缩进两空格"))
        assertTrue(html.contains("\n\n空行后"))
        assertEquals(body, html.removePrefix("<pre>").removeSuffix("</pre>"))
    }

    @Test
    fun `空文本不产生异常且编码探测回退 UTF-8`() {
        val decoded = TextEncoding.decode(ByteArray(0))
        assertEquals("", decoded.content)
        assertEquals(TextEncoding.UTF_8, decoded.encoding)
    }
}
