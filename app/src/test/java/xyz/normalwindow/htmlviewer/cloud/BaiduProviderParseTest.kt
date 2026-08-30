package xyz.normalwindow.htmlviewer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.normalwindow.htmlviewer.data.cloud.blockMd5List
import xyz.normalwindow.htmlviewer.data.cloud.parseBaiduFilemetas
import xyz.normalwindow.htmlviewer.data.cloud.parseBaiduListResponse
import xyz.normalwindow.htmlviewer.data.cloud.parseBaiduTokenResponse
import java.io.File

/** 百度开放平台 JSON 响应解析测试 */
class BaiduProviderParseTest {

    @Test
    fun `令牌响应解析`() {
        val tokens = parseBaiduTokenResponse(
            """{"access_token":"tok","refresh_token":"ref","expires_in":2592000,"scope":"basic netdisk"}"""
        )
        assertEquals("tok", tokens.accessToken)
        assertEquals("ref", tokens.refreshToken)
        assertTrue(tokens.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `令牌响应业务错误抛异常`() {
        assertThrows(Exception::class.java) {
            parseBaiduTokenResponse("""{"error":"invalid_grant","error_description":"code 已使用"}""")
        }
    }

    @Test
    fun `列表响应解析 - 剥离根前缀`() {
        val files = parseBaiduListResponse(
            """{"errno":0,"list":[
                {"fs_id":1,"path":"/apps/HTMLviewer/a.html","isdir":0,"size":10,"server_mtime":100},
                {"fs_id":2,"path":"/apps/HTMLviewer/sub","isdir":1,"size":0,"server_mtime":99},
                {"fs_id":3,"path":"/apps/HTMLviewer/sub/b.html","isdir":0,"size":5,"server_mtime":98}
            ]}""",
            "/apps/HTMLviewer"
        )
        assertEquals(3, files.size)
        val a = files.first { it.name == "a.html" }
        assertEquals("a.html", a.path)
        assertFalse(a.isDir)
        assertEquals(10, a.size)
        assertEquals(100, a.mtime)
        val sub = files.first { it.name == "sub" }
        assertEquals("sub", sub.path)
        assertTrue(sub.isDir)
        assertEquals("sub/b.html", files.first { it.name == "b.html" }.path)
        // fs_id 用于 filemetas→dlink 下载链路
        assertEquals(1L, files.first { it.name == "a.html" }.fsId)
    }

    @Test
    fun `filemetas 响应解析取 dlink`() {
        val dlink = parseBaiduFilemetas(
            """{"errno":0,"list":[{"fs_id":9,"dlink":"https://data.d.pcs.baidu.com/rest/2.0/pcs/file?method=download&dlink=abc"}]}"""
        )
        assertEquals("https://data.d.pcs.baidu.com/rest/2.0/pcs/file?method=download&dlink=abc", dlink)
    }

    @Test
    fun `filemetas 空响应返回 null`() {
        assertEquals(null, parseBaiduFilemetas("""{"errno":0,"list":[]}"""))
    }

    @Test
    fun `空列表返回空集合`() {
        val files = parseBaiduListResponse("""{"errno":0,"list":[]}""", "/apps/HTMLviewer")
        assertEquals(0, files.size)
    }

    @Test
    fun `分片 MD5 - 大写十六进制且内容正确`() {
        val f = File.createTempFile("md5", ".txt")
        f.writeText("hello")
        val blocks = blockMd5List(f, partSize = 4 * 1024 * 1024)
        assertEquals(1, blocks.size)
        // echo -n hello | md5sum
        assertEquals("5D41402ABC4B2A76B9719D911017C592", blocks[0])
        f.delete()
    }

    @Test
    fun `分片 MD5 - 多分片切分正确`() {
        val f = File.createTempFile("md5big", ".bin")
        f.writeBytes(ByteArray(4 * 1024 * 1024 + 10))
        val blocks = blockMd5List(f, partSize = 4 * 1024 * 1024)
        assertEquals(2, blocks.size)
        f.delete()
    }
}
