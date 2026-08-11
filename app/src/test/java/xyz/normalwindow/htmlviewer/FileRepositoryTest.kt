package xyz.normalwindow.htmlviewer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.normalwindow.htmlviewer.data.file.FileRepository
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import java.io.File

class FileRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeFileMetaDao()
    private val repo = FileRepository(dao)

    @Test
    fun `创建 HTML 文件并写入 UTF-8 内容`() = runBlocking {
        val dir = tmp.newFolder("root")
        val file = repo.createHtmlFile(dir, "index").getOrThrow()

        assertEquals("index.html", file.name)
        val text = repo.readText(file).getOrThrow()
        assertEquals("", text.content)
        assertEquals(TextEncoding.UTF_8, text.encoding)
    }

    @Test
    fun `同名文件自动去重`() = runBlocking {
        val dir = tmp.newFolder("root")
        val first = repo.createHtmlFile(dir, "page").getOrThrow()
        val second = repo.createHtmlFile(dir, "page").getOrThrow()

        assertEquals("page.html", first.name)
        assertEquals("page (1).html", second.name)
    }

    @Test
    fun `读取 GBK 编码文件并正确解码`() = runBlocking {
        val dir = tmp.newFolder("root")
        val file = repo.createHtmlFile(dir, "gbk").getOrThrow()
        val content = "<title>中文标题</title>"
        repo.writeText(file, content, TextEncoding.GBK).getOrThrow()

        val decoded = repo.readText(file).getOrThrow()
        assertEquals(content, decoded.content)
        assertEquals(TextEncoding.GBK, decoded.encoding)
    }

    @Test
    fun `列出目录时目录优先并按名称排序`() = runBlocking {
        val dir = tmp.newFolder("root")
        repo.createHtmlFile(dir, "b").getOrThrow()
        repo.createHtmlFile(dir, "a").getOrThrow()
        repo.createDirectory(dir, "zzz").getOrThrow()
        repo.createDirectory(dir, "aaa").getOrThrow()

        val items = repo.list(dir)
        assertEquals(listOf("aaa", "zzz", "a.html", "b.html"), items.map { it.name })
    }

    @Test
    fun `隐藏文件不显示`() = runBlocking {
        val dir = tmp.newFolder("root")
        File(dir, ".hidden.html").writeText("x")
        repo.createHtmlFile(dir, "visible").getOrThrow()

        val items = repo.list(dir)
        assertEquals(listOf("visible.html"), items.map { it.name })
    }

    @Test
    fun `重命名迁移元数据`() = runBlocking {
        val dir = tmp.newFolder("root")
        val file = repo.createHtmlFile(dir, "old").getOrThrow()
        repo.toggleFavorite(file.absolutePath)
        assertTrue(dao.get(file.absolutePath)!!.isFavorite)

        val renamed = repo.rename(file, "new").getOrThrow()
        assertEquals("new.html", renamed.name)
        assertNull(dao.get(file.absolutePath))           // 旧路径元数据已清理
        assertTrue(dao.get(renamed.absolutePath)!!.isFavorite) // 新路径保留收藏
    }

    @Test
    fun `删除同步清理元数据`() = runBlocking {
        val dir = tmp.newFolder("root")
        val file = repo.createHtmlFile(dir, "gone").getOrThrow()
        repo.touchOpened(file.absolutePath, null, null, null)

        repo.delete(file).getOrThrow()
        assertFalse(file.exists())
        assertNull(dao.get(file.absolutePath))
    }

    @Test
    fun `文件已不存在时删除元数据清理最近历史残留`() = runBlocking {
        val dir = tmp.newFolder("root")
        val file = repo.createHtmlFile(dir, "ghost").getOrThrow()
        repo.touchOpened(file.absolutePath, null, null, null)
        assertTrue(dao.observeRecent(30).first().any { it.path == file.absolutePath })

        // 模拟文件已被外部删除:仅清理元数据,最近历史条目随之消失
        assertTrue(file.delete())
        repo.deleteMeta(file.absolutePath).getOrThrow()

        assertNull(dao.get(file.absolutePath))
        assertFalse(dao.observeRecent(30).first().any { it.path == file.absolutePath })
    }

    @Test
    fun `收藏切换与收藏流`() = runBlocking {
        val dir = tmp.newFolder("root")
        val file = repo.createHtmlFile(dir, "fav").getOrThrow()

        val nowFav = repo.toggleFavorite(file.absolutePath)
        assertTrue(nowFav)
        assertEquals(listOf(file.absolutePath), dao.observeFavorites().first().map { it.path })

        val nowUnfav = repo.toggleFavorite(file.absolutePath)
        assertFalse(nowUnfav)
        assertEquals(0, dao.observeFavorites().first().size)
    }

    @Test
    fun `复制文件保留收藏标记`() = runBlocking {
        val dir = tmp.newFolder("root")
        val file = repo.createHtmlFile(dir, "src").getOrThrow()
        repo.toggleFavorite(file.absolutePath)

        val copy = repo.copy(file, dir).getOrThrow()
        assertEquals("src (1).html", copy.name)
        assertTrue(dao.get(copy.absolutePath)!!.isFavorite)
    }

    @Test
    fun `并发写入不产生交错内容`() = runBlocking {
        val dir = tmp.newFolder("root")
        val file = repo.createHtmlFile(dir, "concurrent").getOrThrow()
        val a = "A".repeat(200_000)
        val b = "B".repeat(200_000)

        // 双协程并发写同一文件,串行化后最终内容必须是完整的 a 或 b 之一
        val jobs = listOf(
            launch { repo.writeText(file, a).getOrThrow() },
            launch { repo.writeText(file, b).getOrThrow() }
        )
        jobs.forEach { it.join() }

        val result = repo.readText(file).getOrThrow().content
        assertTrue("内容被交错损坏:${result.length}", result == a || result == b)
    }
}
