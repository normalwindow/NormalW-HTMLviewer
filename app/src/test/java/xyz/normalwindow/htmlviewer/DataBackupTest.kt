package xyz.normalwindow.htmlviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.normalwindow.htmlviewer.data.backup.DataBackup
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 构造工作目录:嵌套子目录 + 文件 + 空目录 + 隐藏文件 + 缓存目录 */
    private fun buildWorkRoot(root: File) {
        fun write(rel: String, text: String) {
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        write("index.html", "<html>首页</html>")
        write("css/style.css", "body{color:red}")
        write("js/app.js", "console.log(1)")
        write("笔记/子目录/说明.txt", "深层文件")
        File(root, "空目录").mkdirs()                     // 空目录:应保留
        File(root, "笔记/空子目录").mkdirs()               // 嵌套空目录:应保留
        write(".hidden", "隐藏")
        write(".htmlviewer_cache/cache.bin", "缓存")
    }

    private fun collectTree(root: File): List<String> =
        root.walkTopDown()
            .filter { it != root }
            .map { it.relativeTo(root).path.replace('\\', '/') + if (it.isDirectory) "/" else "" }
            .sorted()
            .toList()

    @Test
    fun `导出导入后文件与目录结构完整保持`() {
        val root = tmp.newFolder("work")
        buildWorkRoot(root)

        val zip = DataBackup.export(root, tmp.newFolder("cache"))!!
        val restored = tmp.newFolder("restored")
        val count = DataBackup.import(zip, restored)

        // 4 个业务文件(meta 不计入) → 4
        assertEquals(4, count)
        // 隐藏文件与缓存不导出
        assertFalse(File(restored, ".hidden").exists())
        assertFalse(File(restored, ".htmlviewer_cache").exists())

        // 结构与内容一致(含空目录)
        val expected = listOf(
            "css/",
            "css/style.css",
            "index.html",
            "js/",
            "js/app.js",
            "空目录/",
            "笔记/",
            "笔记/子目录/",
            "笔记/子目录/说明.txt",
            "笔记/空子目录/",
        )
        assertEquals(expected, collectTree(restored))
        assertEquals("<html>首页</html>", File(restored, "index.html").readText())
        assertEquals("深层文件", File(restored, "笔记/子目录/说明.txt").readText())
    }

    @Test
    fun `导入拒绝路径穿越条目`() {
        val zip = File(tmp.newFolder("cache"), "evil.zip")
        // 构造恶意 zip:绝对路径 / 上级目录逃逸 / 逃逸后进入同前缀兄弟目录
        // (最后一种:canonicalPath 若用纯前缀 startsWith 校验,
        // "<root>X/..." 以 "<root>" 开头会被误放行,写出根目录外)
        ZipOutputStream(zip.outputStream()).use { zos ->
            listOf(
                "/etc/evil.txt",
                "../evil-outside.txt",
                "../HTMLviewerX/evil-sibling.txt",
            ).forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write("evil".toByteArray())
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("ok/正常.txt"))
            zos.write("fine".toByteArray())
            zos.closeEntry()
        }

        val restored = tmp.newFolder("HTMLviewer")
        DataBackup.import(zip, restored)

        // 合法条目正常导入
        assertEquals("fine", File(restored, "ok/正常.txt").readText())
        // 没有东西写到 restored 之外(同前缀兄弟 / 上级目录逃逸)
        assertFalse(File(restored.parentFile, "HTMLviewerX").exists())
        assertFalse(File(restored.parentFile, "evil-outside.txt").exists())
    }

    @Test
    fun `导入重复执行幂等且覆盖同名文件`() {
        val root = tmp.newFolder("work")
        buildWorkRoot(root)
        val zip = DataBackup.export(root, tmp.newFolder("cache"))!!

        val restored = tmp.newFolder("restored")
        DataBackup.import(zip, restored)
        // 修改文件后再次导入:应覆盖为备份内容
        File(restored, "index.html").writeText("changed")
        DataBackup.import(zip, restored)
        assertEquals("<html>首页</html>", File(restored, "index.html").readText())
    }
}
