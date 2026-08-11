package xyz.normalwindow.htmlviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import java.nio.charset.Charset

class TextEncodingTest {

    private val chinese = "你好,世界!Hello 世界"

    @Test
    fun `UTF-8 中文内容应检测为 UTF-8`() {
        val bytes = chinese.toByteArray(Charsets.UTF_8)
        assertEquals(TextEncoding.UTF_8, TextEncoding.detect(bytes))
    }

    @Test
    fun `GBK 中文内容应检测为 GBK 并正确解码`() {
        val bytes = chinese.toByteArray(Charset.forName("GBK"))
        assertEquals(TextEncoding.GBK, TextEncoding.detect(bytes))
        val decoded = TextEncoding.decode(bytes)
        assertEquals(chinese, decoded.content)
        assertEquals(TextEncoding.GBK, decoded.encoding)
    }

    @Test
    fun `纯 ASCII 内容应检测为 UTF-8`() {
        val bytes = "<html><body>hello</body></html>".toByteArray()
        assertEquals(TextEncoding.UTF_8, TextEncoding.detect(bytes))
    }

    @Test
    fun `UTF-8 BOM 应被识别并剥离`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + chinese.toByteArray(Charsets.UTF_8)
        assertEquals(TextEncoding.UTF_8, TextEncoding.detect(bytes))
        assertEquals(chinese, TextEncoding.decode(bytes).content)
    }

    @Test
    fun `UTF-16LE BOM 应被识别并剥离`() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + chinese.toByteArray(Charsets.UTF_16LE)
        assertEquals(TextEncoding.UTF_16LE, TextEncoding.detect(bytes))
        assertEquals(chinese, TextEncoding.decode(bytes).content)
    }

    @Test
    fun `GBK 编码回写往返一致`() {
        val encoded = TextEncoding.encode(chinese, TextEncoding.GBK)
        assertEquals(chinese, TextEncoding.decode(encoded).content)
    }

    @Test
    fun `UTF-16LE 编码回写往返一致且无多余尾部字节`() {
        val encoded = TextEncoding.encode(chinese, TextEncoding.UTF_16LE)
        // 每个字符 2 字节,无 BOM;长度必须精确,不能含 ByteBuffer 尾部垃圾
        assertEquals(chinese.length * 2, encoded.size)
        assertEquals(chinese, String(encoded, Charset.forName("UTF-16LE")))
    }

    @Test
    fun `乱码字节流应安全回退而不崩溃`() {
        // 非法 UTF-8 且非法 GBK 的字节
        val bytes = byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x00, 0x01)
        val decoded = TextEncoding.decode(bytes)
        assertNotEquals("", decoded.content) // 至少有兜底输出
    }
}
