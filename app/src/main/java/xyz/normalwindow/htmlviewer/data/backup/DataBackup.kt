package xyz.normalwindow.htmlviewer.data.backup

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据备份:把工作目录下的用户文件压缩为 zip(含 meta.txt 清单详情),
 * 支持导入解压恢复。排除缓存目录(.htmlviewer_cache)与隐藏文件。
 */
object DataBackup {

    private const val META_NAME = "meta.txt"

    /**
     * 导出:根目录所有用户文件 → zip。返回 zip 文件(失败返回 null)。
     * meta.txt 置于 zip 首位:版本/时间/文件数/总大小/逐文件清单。
     * 目录(含空目录)也写入 zip,保证导入后文件夹结构完整还原。
     */
    fun export(root: File, cacheDir: File): File? {
        val files = root.walkTopDown()
            .filter { it.isFile && !it.path.contains(CACHE_DIR) && !it.name.startsWith(".") }
            .toList()
        // 空目录:zip 目录 entry(名称以 / 结尾),避免"导出再导入后空文件夹消失"
        val dirs = root.walkTopDown()
            .filter {
                it.isDirectory && it != root &&
                    !it.path.contains(CACHE_DIR) && !it.name.startsWith(".")
            }
            .toList()
        // 无任何用户文件且无目录(含空目录)时无可导出
        if (files.isEmpty() && dirs.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(cacheDir, "backup").apply { mkdirs() }
        val zip = File(dir, "htmlviewer-backup-$stamp.zip")

        val meta = buildString {
            appendLine("HTML Viewer 数据备份")
            appendLine("备份时间: $stamp")
            appendLine("文件数: ${files.size}")
            appendLine("总大小: ${files.sumOf { it.length() }} 字节")
            appendLine()
            appendLine("----- 文件清单 -----")
            files.forEach { appendLine("${it.relativeTo(root).path.replace('\\', '/')}\t${it.length()}") }
        }

        return runCatching {
            ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry(META_NAME))
                zos.write(meta.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                dirs.forEach { d ->
                    zos.putNextEntry(ZipEntry(d.relativeTo(root).path.replace('\\', '/') + "/"))
                    zos.closeEntry()
                }
                files.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.relativeTo(root).path.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zip
        }.getOrNull()
    }

    /**
     * 导入:解压 zip 到根目录(跳过 meta.txt),返回导入的文件数。
     * 防路径穿越(双保险):
     * 1. 拒绝绝对路径与空名 entry;
     * 2. canonicalPath 必须等于根目录或位于其下(前缀须带分隔符,
     *    否则同前缀兄弟目录如 "<root>X/evil" 会绕过校验写出根目录)。
     */
    fun import(zipFile: File, root: File): Int {
        val rootPath = root.canonicalPath
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val safeName = !name.isBlank() && !name.startsWith("/") && name != META_NAME
                if (safeName) {
                    val target = File(root, name)
                    val canonical = target.canonicalPath
                    val inside = canonical == rootPath ||
                        canonical.startsWith(rootPath + File.separator)
                    if (inside) {
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zis.copyTo(it) }
                            count++
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        return count
    }

    private const val CACHE_DIR = ".htmlviewer_cache"
}
