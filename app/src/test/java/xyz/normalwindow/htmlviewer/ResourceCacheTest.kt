package xyz.normalwindow.htmlviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.normalwindow.htmlviewer.data.cache.ResourceCache
import java.io.File

/**
 * ResourceCache 本地固化缓存核心逻辑测试(不依赖网络):
 * 索引读写 / 命中 / 损坏回退 / 清理 / 统计。
 */
class ResourceCacheTest {

    private fun tempDir(name: String): File {
        val dir = File.createTempFile(name, "").apply { delete() }
        dir.mkdirs()
        return dir
    }

    /** 手工构造缓存目录:index.json(TSV 行式)+ 资源文件 */
    private fun seedCache(cacheDir: File, url: String, fileName: String, content: ByteArray) {
        cacheDir.mkdirs()
        File(cacheDir, fileName).writeBytes(content)
        val line = "$url\t$fileName\tapplication/javascript\t${content.size}\t1\tabc"
        File(cacheDir, "index.tsv").appendText(line + "\n")
    }

    @Test
    fun `未命中返回 null`() {
        val cache = ResourceCache()
        val htmlDir = tempDir("hv-cache-miss")
        val resp = cache.serve("https://cdn.example.com/a.js", File(htmlDir, ".htmlviewer_cache"))
        assertNull(resp)
        htmlDir.deleteRecursively()
    }

    @Test
    fun `命中返回本地文件`() {
        val cache = ResourceCache()
        val htmlDir = tempDir("hv-cache-hit")
        val cacheDir = File(htmlDir, ".htmlviewer_cache")
        seedCache(cacheDir, "https://cdn.example.com/a.js", "a.js", "var a=1;".toByteArray())

        val resp = cache.serve("https://cdn.example.com/a.js", cacheDir)
        assertNotNull(resp)
        assertEquals("application/javascript", resp!!.mime)
        assertEquals("var a=1;", resp.file.readText())

        // 二次命中(内存索引)
        val resp2 = cache.serve("https://cdn.example.com/a.js", cacheDir)
        assertNotNull(resp2)
        htmlDir.deleteRecursively()
    }

    @Test
    fun `缓存文件缺失或大小不符时回退并移除索引`() {
        val cache = ResourceCache()
        val htmlDir = tempDir("hv-cache-corrupt")
        val cacheDir = File(htmlDir, ".htmlviewer_cache")
        seedCache(cacheDir, "https://cdn.example.com/a.js", "a.js", "var a=1;".toByteArray())

        // 文件被删 → 回退 null
        File(cacheDir, "a.js").delete()
        assertNull(cache.serve("https://cdn.example.com/a.js", cacheDir))
        // 索引条目已被移除,再 serve 仍为 null
        assertNull(cache.serve("https://cdn.example.com/a.js", cacheDir))
        htmlDir.deleteRecursively()
    }

    @Test
    fun `清理删除整个缓存目录`() {
        val cache = ResourceCache()
        val htmlDir = tempDir("hv-cache-clear")
        val cacheDir = File(htmlDir, ".htmlviewer_cache")
        seedCache(cacheDir, "https://cdn.example.com/a.js", "a.js", "var a=1;".toByteArray())

        cache.clearFor(cacheDir)
        assertTrue(!cacheDir.exists())
        assertNull(cache.serve("https://cdn.example.com/a.js", cacheDir))
        htmlDir.deleteRecursively()
    }

    @Test
    fun `统计资源数与字节数`() {
        val cache = ResourceCache()
        val htmlDir = tempDir("hv-cache-stats")
        seedCache(File(htmlDir, ".htmlviewer_cache"), "https://cdn.example.com/a.js", "a.js", "var a=1;".toByteArray())
        seedCache(File(htmlDir, "sub/.htmlviewer_cache"), "https://cdn.example.com/b.css", "b.css", "body{}".toByteArray())

        val stats = cache.stats(htmlDir)
        assertEquals(2, stats.resourceCount)
        assertEquals(("var a=1;".length + "body{}".length).toLong(), stats.totalBytes)
        htmlDir.deleteRecursively()
    }

    @Test
    fun `clearAll 递归清理全部缓存目录`() {
        val cache = ResourceCache()
        val htmlDir = tempDir("hv-cache-all")
        seedCache(File(htmlDir, ".htmlviewer_cache"), "https://cdn.example.com/a.js", "a.js", "var a=1;".toByteArray())
        seedCache(File(htmlDir, "sub/.htmlviewer_cache"), "https://cdn.example.com/b.css", "b.css", "body{}".toByteArray())

        cache.clearAll(htmlDir)
        assertTrue(!File(htmlDir, ".htmlviewer_cache").exists())
        assertTrue(!File(htmlDir, "sub/.htmlviewer_cache").exists())
        // 普通目录不受影响
        assertTrue(File(htmlDir, "sub").exists())
        htmlDir.deleteRecursively()
    }

    @Test
    fun `损坏索引行被忽略`() {
        val cache = ResourceCache()
        val htmlDir = tempDir("hv-cache-badidx")
        val cacheDir = File(htmlDir, ".htmlviewer_cache")
        cacheDir.mkdirs()
        File(cacheDir, "index.tsv").writeText("garbage line without tabs\n")
        val resp = cache.serve("https://cdn.example.com/a.js", cacheDir)
        assertNull(resp)
        htmlDir.deleteRecursively()
    }
}
