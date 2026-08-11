package xyz.normalwindow.htmlviewer.data.cache

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 缓存条目元数据 */
private data class CacheEntry(
    val fileName: String,
    val mime: String,
    val size: Long,
    val time: Long,
    val hash: String
)

/** 命中/下载成功的缓存响应(调用方负责关闭流) */
data class CachedResponse(
    val file: File,
    val mime: String
)

/**
 * 网络资源本地固化缓存:
 * 页面引用的 http(s) 资源(CDN/外部库)在首次下载时保存到 HTML 文件
 * 同目录下的隐藏文件夹 .htmlviewer_cache,URL 不变(即 hash 不变)时
 * 直接读本地文件,支持离线浏览。手动清理后重新下载刷新。
 *
 * 索引为每行一条的 TSV 文件(index.json):
 *   url\tfileName\tmime\tsize\ttime\thash
 * (URL 不含 TAB/换行,格式安全且不依赖 org.json,可单元测试)
 *
 * 线程安全:索引读写加锁;shouldInterceptRequest 在后台线程并发调用,
 * 下载不锁(重复下载概率低且幂等,后写覆盖)。
 */
class ResourceCache {

    private val lock = Any()

    /** 各缓存目录的索引内存镜像(避免每次拦截都读盘) */
    private val dirIndex = HashMap<String, MutableMap<String, CacheEntry>>()

    /** 缓存目录:HTML 所在目录下的隐藏文件夹 */
    fun cacheDirFor(htmlFile: File): File = File(htmlFile.parentFile, CACHE_DIR_NAME)

    /**
     * 命中检查:URL 有缓存且文件完整 → 返回本地响应;否则 null。
     * 命中即用(信任本地固化内容),强制刷新走 clearFor 后重新下载。
     */
    fun serve(url: String, cacheDir: File): CachedResponse? = synchronized(lock) {
        val entry = index(cacheDir)[url] ?: return null
        val file = File(cacheDir, entry.fileName)
        if (!file.isFile || file.length() != entry.size) {
            index(cacheDir).remove(url)
            saveIndex(cacheDir)
            return null
        }
        CachedResponse(file, entry.mime)
    }

    /**
     * 下载并固化(调用方必须在后台线程;shouldInterceptRequest 满足)。
     * 失败(超时/非 2xx/超限)返回 null,由 WebView 自行加载(不缓存)。
     */
    fun download(url: String, cacheDir: File): CachedResponse? {
        val resp = runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute()
        }.getOrNull() ?: return null
        resp.use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            // 流式读取 + 限量:超限立即放弃,避免大文件全量载入内存
            val bytes = runCatching {
                body.byteStream().use { input ->
                    val bos = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_CACHE_BYTES) return null
                        bos.write(buf, 0, n)
                    }
                    bos.toByteArray()
                }
            }.getOrNull() ?: return null
            if (bytes.isEmpty()) return null
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: mimeForUrl(url)
            val fileName = sha1Hex(url) + extFor(url)
            val cacheFile = File(cacheDir, fileName)
            runCatching {
                cacheDir.mkdirs()
                // 临时文件 + 原子重命名:并发重复下载时不会写坏缓存文件
                val tmp = File(cacheDir, ".tmp-" + fileName + "-" + System.nanoTime())
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(cacheFile)) {
                    cacheFile.writeBytes(bytes)
                    tmp.delete()
                }
            }.getOrElse { return null }
            synchronized(lock) {
                index(cacheDir)[url] = CacheEntry(fileName, mime, bytes.size.toLong(), System.currentTimeMillis(), sha1Hex(bytes))
                saveIndex(cacheDir)
            }
            return CachedResponse(cacheFile, mime)
        }
    }

    /** 清除单个 HTML 的缓存目录(手动刷新入口) */
    fun clearFor(cacheDir: File) {
        synchronized(lock) {
            runCatching { cacheDir.deleteRecursively() }
            dirIndex.remove(cacheDir.absolutePath)
        }
    }

    /** 递归清除文件根目录下所有缓存目录 */
    fun clearAll(root: File) {
        synchronized(lock) {
            root.walkTopDown().forEach { f ->
                if (f.isDirectory && f.name == CACHE_DIR_NAME) {
                    runCatching { f.deleteRecursively() }
                    dirIndex.remove(f.absolutePath)
                }
            }
        }
    }

    /** 统计所有缓存目录的资源数/总字节(设置页展示) */
    fun stats(root: File): CacheStats {
        var count = 0
        var bytes = 0L
        root.walkTopDown().forEach { f ->
            if (f.isDirectory && f.name == CACHE_DIR_NAME) {
                val idxFile = File(f, INDEX_NAME)
                if (idxFile.isFile) {
                    idxFile.readLines().forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size >= 4) {
                            count += 1
                            bytes += parts[3].toLongOrNull() ?: 0
                        }
                    }
                }
            }
        }
        return CacheStats(count, bytes)
    }

    private fun index(cacheDir: File): MutableMap<String, CacheEntry> {
        val key = cacheDir.absolutePath
        dirIndex[key]?.let { return it }
        val loaded = if (File(cacheDir, INDEX_NAME).isFile) {
            runCatching {
                File(cacheDir, INDEX_NAME).readLines().mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 6) return@mapNotNull null
                    parts[0] to CacheEntry(
                        parts[1],
                        parts[2],
                        parts[3].toLongOrNull() ?: 0,
                        parts[4].toLongOrNull() ?: 0,
                        parts[5]
                    )
                }.toMap().toMutableMap()
            }.getOrDefault(mutableMapOf())
        } else mutableMapOf()
        dirIndex[key] = loaded
        return loaded
    }

    private fun saveIndex(cacheDir: File) {
        runCatching {
            cacheDir.mkdirs()
            val text = index(cacheDir).entries.joinToString("\n") { (url, e) ->
                "$url\t${e.fileName}\t${e.mime}\t${e.size}\t${e.time}\t${e.hash}"
            }
            File(cacheDir, INDEX_NAME).writeText(text)
        }
    }

    private fun mimeForUrl(url: String): String {
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        return MIME_BY_EXT[ext] ?: "application/octet-stream"
    }

    private fun extFor(url: String): String {
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ".$ext" else ".bin"
    }

    private fun sha1Hex(input: String): String = sha1Hex(input.toByteArray(Charsets.UTF_8))

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private companion object {
        const val CACHE_DIR_NAME = ".htmlviewer_cache"
        /** 索引文件:每行一条 TSV(url\tfileName\tmime\tsize\ttime\thash) */
        const val INDEX_NAME = "index.tsv"
        /** 单资源缓存上限 20MB(超出不固化,避免内存/磁盘膨胀) */
        const val MAX_CACHE_BYTES = 20 * 1024 * 1024

        val MIME_BY_EXT = mapOf(
            "js" to "application/javascript",
            "mjs" to "application/javascript",
            "css" to "text/css",
            "html" to "text/html",
            "htm" to "text/html",
            "json" to "application/json",
            "map" to "application/json",
            "xml" to "application/xml",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "ico" to "image/x-icon",
            "bmp" to "image/bmp",
            "avif" to "image/avif",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "eot" to "application/vnd.ms-fontobject",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "txt" to "text/plain",
            "pdf" to "application/pdf",
            "wasm" to "application/wasm"
        )
    }
}

/** 缓存统计(设置页展示) */
data class CacheStats(val resourceCount: Int, val totalBytes: Long)
