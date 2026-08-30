package xyz.normalwindow.htmlviewer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.normalwindow.htmlviewer.data.cloud.parseHttpDate
import xyz.normalwindow.htmlviewer.data.cloud.parseMultistatus
import java.io.ByteArrayInputStream

/** WebDAV multistatus 解析与 HTTP 日期解析测试 */
class WebDavParserTest {

    private val multistatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/NormalW-HTMLviewer/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
              <D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/NormalW-HTMLviewer/a%20b.html</D:href>
            <D:propstat><D:prop>
              <D:resourcetype/>
              <D:getcontentlength>123</D:getcontentlength>
              <D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/NormalW-HTMLviewer/sub/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun `解析 multistatus - 命名空间前缀无关`() {
        val entries = parseMultistatus(ByteArrayInputStream(multistatus.toByteArray(Charsets.UTF_8)))
        assertEquals(3, entries.size)
        // 目录条目
        assertTrue(entries[0].isDir)
        // 文件条目:URL 编码 href 原样保留,大小与时间解析正确
        assertFalse(entries[1].isDir)
        assertEquals(123L, entries[1].size)
        assertEquals(1445412480L, entries[1].mtime)
        // 无 getlastmodified 的目录 mtime = 0
        assertTrue(entries[2].isDir)
        assertEquals(0L, entries[2].mtime)
    }

    @Test
    fun `非法 XML 返回空集合而非抛异常`() {
        val entries = parseMultistatus(ByteArrayInputStream("not xml".toByteArray()))
        assertEquals(0, entries.size)
    }

    @Test
    fun `RFC1123 日期解析`() {
        assertEquals(1445412480L, parseHttpDate("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(0L, parseHttpDate("garbage"))
    }
}
