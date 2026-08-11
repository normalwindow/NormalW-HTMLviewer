package xyz.normalwindow.htmlviewer.data.file

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.normalwindow.htmlviewer.data.db.FileMetaDao
import xyz.normalwindow.htmlviewer.data.db.FileMetaEntity
import xyz.normalwindow.htmlviewer.data.db.FavoriteGroupEntity
import xyz.normalwindow.htmlviewer.data.debug.AppLog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 文件系统条目 + 元数据展示模型 */
data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isFavorite: Boolean,
    val encoding: String?,
    val lineCount: Int?
) {
    companion object {
        fun of(file: File, meta: FileMetaEntity?): FileItem = FileItem(
            path = file.absolutePath,
            name = file.name,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            lastModified = file.lastModified(),
            isFavorite = meta?.isFavorite == true,
            encoding = meta?.encoding,
            lineCount = meta?.lineCount
        )
    }
}

/** 回收站条目:用于删除撤销 */
data class TrashEntry(
    val trashFile: File,
    val originalPath: String
)

/**
 * 文件仓库:目录扫描、CRUD、重命名/复制/移动、编码读写、收藏与最近打开。
 * 所有 IO 均在 [Dispatchers.IO] 执行;依赖 [FileMetaDao] 提供元数据。
 */
@Singleton
class FileRepository @Inject constructor(
    private val metaDao: FileMetaDao
) {

    /** 串行化文件写入(自动保存与手动保存可能并发) */
    private val writeMutex = Mutex()

    fun observeRecent(limit: Int = 50): Flow<List<FileMetaEntity>> = metaDao.observeRecent(limit)

    fun observeFavorites(): Flow<List<FileMetaEntity>> = metaDao.observeFavorites()

    fun observeGroups(): Flow<List<FavoriteGroupEntity>> = metaDao.observeGroups()

    // ---------- 收藏分组 ----------

    suspend fun createGroup(name: String): Long =
        metaDao.insertGroup(FavoriteGroupEntity(name = name))
    suspend fun renameGroup(id: Long, name: String) {
        metaDao.renameGroup(id, name)
    }

    /** 删除分组并解除组内文件的归属 */
    suspend fun deleteGroup(id: Long) {
        metaDao.clearGroupFiles(id)
        metaDao.deleteGroup(id)
    }

    /** 将文件加入分组(自动转为收藏) */
    suspend fun ensureFavoriteInGroup(path: String, groupId: Long) {
        val existing = metaDao.get(path)
        metaDao.upsert(
            (existing ?: FileMetaEntity(path = path)).copy(
                isFavorite = true,
                groupId = groupId
            )
        )
    }

    /** 列出目录内容(目录在前,再按名称字典序),元数据实时合并 */
    suspend fun list(dir: File): List<FileItem> = withContext(Dispatchers.IO) {
        val files = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        val metas = mutableMapOf<String, FileMetaEntity>()
        files.forEach { file ->
            metaDao.get(file.absolutePath)?.let { metas[file.absolutePath] = it }
        }
        files.sortedWith(
            compareBy<File> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        ).map { FileItem.of(it, metas[it.absolutePath]) }
    }

    /** 确保默认根目录存在 */
    suspend fun ensureRoot(root: File): File = withContext(Dispatchers.IO) {
        root.mkdirs()
        root
    }

    /** 新建 HTML 文件(自动补 .html 后缀去重),返回创建结果 */
    suspend fun createHtmlFile(dir: File, baseName: String, content: String = ""): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = uniqueName(dir, baseName.removeSuffix(".html") + ".html")
                val file = File(dir, name)
                check(file.createNewFile()) { "创建失败:$name" }
                file.writeBytes(TextEncoding.encode(content, TextEncoding.UTF_8))
                file
            }
        }

    /** 新建目录(自动去重),返回创建结果 */
    suspend fun createDirectory(dir: File, baseName: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = uniqueName(dir, baseName, isDir = true)
                val file = File(dir, name)
                check(file.mkdirs()) { "创建失败:$name" }
                file
            }
        }

    // ---------- 附加文件(HTML 的关联资源:js/css/ts 等) ----------

    companion object {
        /** 视为附加资源的扩展名 */
        val ATTACHMENT_EXTS = setOf(
            "js", "mjs", "cjs", "css", "ts", "tsx", "json", "map", "txt"
        )
    }

    /** 列出 HTML 文件的附加资源(同目录同名、不同扩展名) */
    fun listAttachments(htmlPath: String): List<File> {
        val html = File(htmlPath)
        val dir = html.parentFile ?: return emptyList()
        val stem = html.name.substringBeforeLast('.')
        return (dir.listFiles { f ->
            f.isFile && f.name != html.name &&
                f.name.startsWith("$stem.") &&
                f.extension.lowercase() in ATTACHMENT_EXTS
        } ?: emptyArray()).sortedBy { it.name }
    }

    /** 是否为附加资源扩展名 */
    fun isAttachmentExt(ext: String): Boolean = ext.lowercase() in ATTACHMENT_EXTS

    /** 创建附加文件;js/css 可自动引用到 HTML */
    suspend fun createAttachment(
        htmlPath: String,
        fileName: String,
        autoReference: Boolean
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val html = File(htmlPath)
            val dir = html.parentFile
            requireNotNull(dir) { "文件无父目录" }
            require(fileName.isNotBlank() && !fileName.contains(File.separatorChar)) { "名称非法" }
            val ext = fileName.substringAfterLast('.', "").lowercase()
            require(ext in ATTACHMENT_EXTS) { "不支持的资源类型:$ext" }
            val target = File(dir, uniqueName(dir, fileName))
            check(target.createNewFile()) { "创建失败:$fileName" }
            target.writeBytes(TextEncoding.encode(attachmentTemplate(fileName, ext), TextEncoding.UTF_8))

            // 自动引用(仅 js/css):在 HTML 中插入 link/script
            if (autoReference && (ext == "js" || ext == "css")) {
                runCatching {
                    val decoded = TextEncoding.decode(html.readBytes())
                    val head = "<link rel=\"stylesheet\" href=\"${target.name}\">"
                    val body = "<script src=\"${target.name}\"></script>"
                    val newContent = when (ext) {
                        "css" -> {
                            val anchor = Regex("(?i)</head\\s*>").find(decoded.content)
                            if (anchor != null) decoded.content.replaceRange(
                                anchor.range.first, anchor.range.first, head + "\n    "
                            ) else decoded.content + "\n$head\n"
                        }
                        else -> {
                            val anchor = Regex("(?i)</body\\s*>").find(decoded.content)
                            if (anchor != null) decoded.content.replaceRange(
                                anchor.range.first, anchor.range.first, "    $body\n"
                            ) else decoded.content + "\n$body\n"
                        }
                    }
                    if (newContent != decoded.content) {
                        html.writeBytes(TextEncoding.encode(newContent, decoded.encoding))
                    }
                }
            }
            target
        }
    }

    private fun attachmentTemplate(fileName: String, ext: String): String = when (ext) {
        "js" -> "// ${fileName}\n// 附加脚本(由 HTML 预览页加载)\n\n"
        "css" -> "/* ${fileName} */\n/* 附加样式(由 HTML 预览页加载) */\n\n"
        else -> ""
    }

    /** 读取文本:自动检测编码并解码 */
    suspend fun readText(file: File): Result<DecodedText> = withContext(Dispatchers.IO) {
        runCatching {
            check(file.isFile) { "不是文件:${file.name}" }
            TextEncoding.decode(file.readBytes())
        }
    }

    suspend fun writeText(file: File, content: String, encoding: String = TextEncoding.UTF_8): Result<Unit> {
        val result = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    file.writeBytes(TextEncoding.encode(content, encoding))
                }
            }
        }
        // 保存成功:同步刷新元数据(行数/字符数),文件列表显示新值
        result.onSuccess {
            val lines = if (content.isEmpty()) 1 else content.count { it == '\n' } + 1
            runCatching { metaDao.updateStats(file.absolutePath, lines, content.length) }
        }
        return result
    }

    suspend fun rename(file: File, newName: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(newName.isNotBlank() && !newName.contains(File.separatorChar)) { "名称非法" }
            // 人性化:用户未输入扩展名时保留原扩展名
            val effective = if (file.isFile && !newName.contains('.')) {
                val ext = file.extension
                if (ext.isNotEmpty()) "$newName.$ext" else newName
            } else {
                newName
            }
            val target = File(file.parentFile, uniqueName(file.parentFile!!, effective))
            check(file.renameTo(target)) { "重命名失败" }
            metaDao.get(file.absolutePath)?.let { meta ->
                metaDao.upsert(meta.copy(path = target.absolutePath))
                metaDao.delete(file.absolutePath)
            }
            target
        }
    }

    /** 移动文件到目标目录(同名自动去重),同步元数据 */
    suspend fun move(file: File, destDir: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(destDir, uniqueName(destDir, file.name))
            check(file.renameTo(target)) { "移动失败" }
            metaDao.get(file.absolutePath)?.let { meta ->
                metaDao.upsert(meta.copy(path = target.absolutePath))
                metaDao.delete(file.absolutePath)
            }
            target
        }
    }

    /** 复制文件到目标目录,返回新文件 */
    suspend fun copy(file: File, destDir: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "只能复制文件" }
            val target = File(destDir, uniqueName(destDir, file.name))
            file.copyTo(target)
            metaDao.get(file.absolutePath)?.let { meta ->
                metaDao.upsert(meta.copy(path = target.absolutePath))
            }
            target
        }
    }

    /** 删除文件(同步清理元数据);目录递归删除 */
    suspend fun delete(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(file.deleteRecursively()) { "删除失败:${file.name}" }
            metaDao.delete(file.absolutePath)
        }.onFailure {
            AppLog.e("FileRepo", "delete 失败: ${file.absolutePath}", it)
            Log.e("FileRepo", "delete 失败: ${file.absolutePath}", it)
        }
    }

    /**
     * 仅清理元数据记录(最近历史/收藏残留)。
     * 用于文件已不存在(外部删除/移动等)时清除幽灵条目,文件存在与否均可调用。
     */
    suspend fun deleteMeta(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { metaDao.delete(path) }
    }

    /** 清空回收站(进程重启后撤销栈已失效,清除上次残留) */
    suspend fun clearTrash(trashDir: File) = withContext(Dispatchers.IO) {
        runCatching { trashDir.deleteRecursively() }
    }

    /** 删除撤销:先移入回收站(记录原路径),而非立即删除 */
    suspend fun moveToTrash(file: File, trashDir: File): Result<TrashEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                trashDir.mkdirs()
                val target = File(trashDir, uniqueName(trashDir, file.name))
                check(file.renameTo(target)) {
                    "删除失败:${file.name}(源存在=${file.exists()}, 目标目录=$trashDir)"
                }
                metaDao.delete(file.absolutePath)
                TrashEntry(trashFile = target, originalPath = file.absolutePath)
            }.onFailure {
                AppLog.e("FileRepo", "moveToTrash 失败: ${file.absolutePath}", it)
                Log.e("FileRepo", "moveToTrash 失败: ${file.absolutePath}", it)
            }
        }

    /** 撤销删除:从回收站移回原路径 */
    suspend fun restoreFromTrash(entry: TrashEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val original = File(entry.originalPath)
            original.parentFile?.mkdirs()
            check(entry.trashFile.renameTo(original)) { "恢复失败:${entry.trashFile}" }
        }.onFailure {
            AppLog.e("FileRepo", "restoreFromTrash 失败: ${entry.trashFile}", it)
            Log.e("FileRepo", "restoreFromTrash 失败: ${entry.trashFile}", it)
        }
    }

    suspend fun toggleFavorite(path: String): Boolean = withContext(Dispatchers.IO) {
        val meta = metaDao.get(path)
        val next = meta?.isFavorite != true
        metaDao.upsert(
            meta?.copy(isFavorite = next) ?: FileMetaEntity(path = path, isFavorite = true)
        )
        next
    }

    suspend fun touchOpened(path: String, encoding: String?, lines: Int?, chars: Int?) =
        withContext(Dispatchers.IO) {
            val existing = metaDao.get(path)
            metaDao.upsert(
                (existing ?: FileMetaEntity(path = path)).copy(
                    lastOpenedAt = System.currentTimeMillis(),
                    encoding = encoding ?: existing?.encoding,
                    lineCount = lines ?: existing?.lineCount,
                    charCount = chars ?: existing?.charCount
                )
            )
        }

    /** 同名文件自动加 (1)、(2) 后缀 */
    private fun uniqueName(dir: File, requested: String, isDir: Boolean = false): String {
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var candidate = requested
        var i = 1
        while (File(dir, candidate).exists()) {
            candidate = if (isDir) "$base ($i)" else "$base ($i)$ext"
            i++
        }
        return candidate
    }
}
