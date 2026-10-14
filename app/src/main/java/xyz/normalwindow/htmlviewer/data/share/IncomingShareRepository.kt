package xyz.normalwindow.htmlviewer.data.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.normalwindow.htmlviewer.data.debug.AppLog
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 暂存结果:[items] 为成功条目,[error] 为最后一次失败原因(null 表示无失败)。
 * 全部失败时 items 为空且 error 非空,界面据此展示具体原因而非"0 个文件"。
 */
data class StageResult(
    val items: List<IncomingItem>,
    val error: String? = null
)

/**
 * 外部分享文件的落盘服务。
 *
 * 处理链路:uri → 应用缓存副本(读取授权立即用完即弃)→ 解析编码 → 按所选格式写入工作区。
 * 缓存副本保证后续操作不依赖 uri 授权生命周期,也便于失败时清理。
 */
@Singleton
class IncomingShareRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 缓存副本存放目录(filesDir,非工作区,不进文件列表;导入完成即清理) */
    private val stageDir: File
        get() = File(context.cacheDir, "incoming").apply { mkdirs() }

    /**
     * 把分享进来的 uri 复制到缓存并解析出展示信息。
     * 单个 uri 失败不影响其它条目(部分成功优于整体失败);
     * 全部失败时返回具体原因,避免界面只显示"0 个文件"这种无信息的失败。
     */
    suspend fun stage(payload: IncomingPayload): StageResult = withContext(Dispatchers.IO) {
        val items = mutableListOf<IncomingItem>()
        val errors = mutableListOf<String>()

        payload.uris.forEachIndexed { index, uri ->
            runCatching {
                val name = displayName(uri) ?: "shared_${System.currentTimeMillis()}_$index"
                val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                val target = File(stageDir, uniqueStaged(safeName))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取分享内容:$safeName")

                val bytes = target.readBytes()
                val isText = isProbablyText(bytes)
                val decoded = if (isText) runCatching { TextEncoding.decode(bytes) }.getOrNull() else null
                items += IncomingItem(
                    cacheFile = target,
                    displayName = safeName,
                    sizeBytes = target.length(),
                    isText = isText,
                    detectedEncoding = decoded?.encoding,
                    preview = decoded?.content?.take(PREVIEW_CHARS)
                )
            }.onFailure { e ->
                val detail = "${e::class.simpleName}: ${e.message}"
                errors += detail
                AppLog.force("Share", "暂存分享文件失败: $uri -> $detail")
            }
        }
        StageResult(items, errors.lastOrNull())
    }

    /**
     * 按所选格式与编码保存到目标目录。
     *
     * @param item 缓存中的待导入条目
     * @param destDir 工作区目标目录
     * @param format 原样 / HTML / 文本
     * @param baseName 用户确认的文件名(不含扩展名;原样格式忽略扩展名改写)
     */
    suspend fun save(
        item: IncomingItem,
        destDir: File,
        format: IncomingSaveFormat,
        baseName: String,
        encoding: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseName.isNotBlank()) { "文件名不能为空" }
            require(!baseName.contains(File.separatorChar)) { "文件名不能包含路径分隔符" }
            destDir.mkdirs()

            val bytes = item.cacheFile.readBytes()
            val sourceExt = item.displayName.substringAfterLast('.', "")

            // 目标扩展名:原样保留源扩展名;HTML 转 .html;文本转 .txt
            val ext = when (format) {
                IncomingSaveFormat.ORIGINAL -> sourceExt
                IncomingSaveFormat.HTML -> "html"
                IncomingSaveFormat.TEXT -> "txt"
            }
            val requested = if (ext.isEmpty()) baseName else "$baseName.$ext"
            val target = File(destDir, uniqueName(destDir, requested))
            check(target.createNewFile()) { "创建失败:${target.name}" }

            when (format) {
                IncomingSaveFormat.ORIGINAL -> target.writeBytes(bytes)

                IncomingSaveFormat.HTML -> {
                    // 先按源编码解码,再以 UTF-8 写出 HTML(meta 声明同步为 UTF-8)
                    val text = TextEncoding.decode(bytes).content
                    val html = buildHtmlDocument(title = baseName, body = text)
                    target.writeBytes(TextEncoding.encode(html, TextEncoding.UTF_8))
                }

                IncomingSaveFormat.TEXT -> {
                    // 源编码 → 文本 → 目标编码(默认 UTF-8),修正 GBK 等存量编码
                    val text = TextEncoding.decode(bytes).content
                    target.writeBytes(TextEncoding.encode(text, encoding))
                }
            }
            AppLog.d("Share", "保存分享文件: ${target.absolutePath}")
            target
        }.onFailure {
            AppLog.e("Share", "保存分享文件失败: ${item.displayName}", it)
        }
    }

    /** 清理缓存副本(导入结束或用户取消时调用) */
    suspend fun clearStaged(items: List<IncomingItem>) = withContext(Dispatchers.IO) {
        items.forEach { runCatching { it.cacheFile.delete() } }
        runCatching { stageDir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /**
     * 把 SAF 目录树 uri 解析为应用内可写的 [File] 路径。
     *
     * 仅在目录位于应用专属外部目录(Android/data/<pkg>/files/...)时可用:
     * 工作区的文件读写与数据库都基于 java.io.File,沙箱外目录无法直接用 File 访问。
     * 返回 null 表示该目录不可作为工作区保存位置,由 UI 提示用户改选。
     */
    fun resolveTreeUriToDir(uri: Uri): File? {
        val docId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        // docId 形如 "primary:Android/data/<pkg>/files/HTMLviewer"
        val relative = docId.substringAfter(':', missingDelimiterValue = "")
        if (relative.isBlank()) return null
        val external = android.os.Environment.getExternalStorageDirectory()
        val candidate = File(external, relative)
        val appScoped = context.getExternalFilesDir(null)?.parentFile?.parentFile // .../Android/data/<pkg>
        val allowed = appScoped != null &&
            (candidate.absolutePath == appScoped.absolutePath ||
                candidate.absolutePath.startsWith(appScoped.absolutePath + File.separator))
        return if (allowed && candidate.isDirectory) candidate else null
    }

    /**
     * 把纯文本转成自带样式的 HTML 文档。
     * 用 white-space: pre-wrap 保留原有换行与空格,同时转义 &<> 防止内容被当作标签解析
     * (txt 里出现 <div> 之类文本时不会破坏结构)。
     */
    private fun buildHtmlDocument(title: String, body: String): String {
        val escapedTitle = escapeHtml(title)
        val escapedBody = escapeHtml(body)
        return """
            |<!DOCTYPE html>
            |<html lang="zh-CN">
            |<head>
            |    <meta charset="UTF-8">
            |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
            |    <title>$escapedTitle</title>
            |    <style>
            |        body {
            |            font-family: system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
            |            max-width: 720px;
            |            margin: 0 auto;
            |            padding: 2rem 1.25rem;
            |            color: #1b1b1f;
            |            background: #fbf8ff;
            |            line-height: 1.7;
            |        }
            |        pre {
            |            white-space: pre-wrap;
            |            word-wrap: break-word;
            |            margin: 0;
            |            font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
            |            font-size: 0.95rem;
            |        }
            |    </style>
            |</head>
            |<body>
            |<pre>$escapedBody</pre>
            |</body>
            |</html>
            |
        """.trimMargin()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 内容中不含 NUL 字节即视为文本(二进制文件几乎必然含 NUL) */
    private fun isProbablyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val probe = bytes.take(8192)
        // UTF-16 文本含大量 NUL,先按 BOM 排除误判
        if (probe.size >= 2) {
            val b0 = probe[0].toInt() and 0xFF
            val b1 = probe[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return true
        }
        return probe.none { it == 0.toByte() }
    }

    /** 读取 uri 的显示名(含扩展名) */
    private fun displayName(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    /** 缓存副本去重 */
    private fun uniqueStaged(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var i = 1
        while (File(stageDir, candidate).exists()) {
            candidate = "$base ($i)$ext"
            i++
        }
        return candidate
    }

    /** 工作区同名文件自动加 (1)、(2) 后缀(与 FileRepository 规则一致) */
    private fun uniqueName(dir: File, requested: String): String {
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var candidate = requested
        var i = 1
        while (File(dir, candidate).exists()) {
            candidate = "$base ($i)$ext"
            i++
        }
        return candidate
    }

    private companion object {
        const val PREVIEW_CHARS = 400
    }
}
