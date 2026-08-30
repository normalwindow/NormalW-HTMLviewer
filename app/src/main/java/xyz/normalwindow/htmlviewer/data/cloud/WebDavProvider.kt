package xyz.normalwindow.htmlviewer.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * 通用 WebDAV Provider(坚果云/Nextcloud/群晖等)。
 * 基础认证(坚果云/Nextcloud 使用应用密码),PROPFIND 列目录、GET 下载、PUT 上传、
 * MKCOL 建目录、DELETE 删除;修改时间取 getlastmodified(PUT 后取响应头 Last-Modified)。
 */
class WebDavProvider(
    baseUrl: String,
    private val username: String,
    private val password: String,
    /** 远端根目录(相对 baseUrl 的路径,如 /NW'HTMLviewer) */
    private val remoteRoot: String
) : CloudProvider {

    override val type: CloudProviderType get() = CloudProviderType.WEBDAV

    /** 规范化后的 baseUrl(无尾斜杠) */
    private val base = baseUrl.trim().trimEnd('/')

    private val authHeader: String = Credentials.basic(username, password, Charsets.UTF_8)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun url(relPath: String): String {
        val path = remoteRoot.trimEnd('/') + if (relPath.isBlank()) "" else "/" + relPath.trim('/')
        return base + encodePath(path)
    }

    override suspend fun checkAuth() {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(""))
                .header("Authorization", authHeader)
                .header("Depth", "0")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA)).build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) throw CloudException("WebDAV 账号或密码错误(HTTP 401)")
                if (!resp.isSuccessful && resp.code != 207) {
                    throw CloudException("WebDAV 连接失败(HTTP ${resp.code})")
                }
            }
        }
    }

    override suspend fun list(dir: String): Result<List<CloudFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val dirUrl = url(dir)
            val request = Request.Builder().url(dirUrl)
                .header("Authorization", authHeader)
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA)).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw CloudException("列目录失败(HTTP ${resp.code})")
                val entries = parseMultistatus(resp.body?.byteStream() ?: return@use emptyList())
                entries.mapNotNull { e ->
                    val rel = hrefToRel(e.href, dirUrl) ?: return@mapNotNull null
                    CloudFile(
                        path = rel,
                        name = rel.substringAfterLast('/'),
                        isDir = e.isDir,
                        size = e.size,
                        mtime = e.mtime
                    )
                }.sortedWith(compareByDescending<CloudFile> { it.isDir }.thenBy { it.name.lowercase() })
            }
        }
    }

    override suspend fun meta(relPath: String): Result<CloudFile?> = withContext(Dispatchers.IO) {
        runCatching {
            val fileUrl = url(relPath)
            val request = Request.Builder().url(fileUrl)
                .header("Authorization", authHeader)
                .header("Depth", "0")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA)).build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) return@runCatching null
                if (!resp.isSuccessful) throw CloudException("读取元数据失败(HTTP ${resp.code})")
                val entries = parseMultistatus(resp.body?.byteStream() ?: return@runCatching null)
                val e = entries.firstOrNull() ?: return@runCatching null
                CloudFile(
                    path = relPath.trim('/'),
                    name = relPath.substringAfterLast('/'),
                    isDir = e.isDir,
                    size = e.size,
                    mtime = e.mtime
                )
            }
        }
    }

    override suspend fun download(relPath: String, dest: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url(relPath))
                    .header("Authorization", authHeader).get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw CloudException("下载失败(HTTP ${resp.code})")
                    val body = resp.body ?: throw CloudException("下载失败:空响应")
                    dest.parentFile?.mkdirs()
                    val tmp = File(dest.parentFile, ".dl-" + dest.name + "-" + System.nanoTime())
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                }
            }
        }

    override suspend fun upload(relPath: String, src: File): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(src.isFile) { "本地文件不存在:${src.name}" }
                if (relPath.contains('/')) mkdirs(relPath.substringBeforeLast('/')).getOrThrow()
                val request = Request.Builder().url(url(relPath))
                    .header("Authorization", authHeader)
                    .put(src.asRequestBody("application/octet-stream".toMediaType())).build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw CloudException("上传失败(HTTP ${resp.code})")
                    resp.header("Last-Modified")?.let { parseHttpDate(it) }
                        ?.takeIf { it > 0 }
                        ?: (System.currentTimeMillis() / 1000)
                }
            }
        }

    override suspend fun mkdirs(relPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (relPath.isBlank()) return@runCatching
            val segments = relPath.split('/').filter { it.isNotBlank() }
            var current = ""
            segments.forEach { seg ->
                current = if (current.isEmpty()) seg else "$current/$seg"
                val request = Request.Builder().url(url(current))
                    .header("Authorization", authHeader)
                    .method("MKCOL", null).build()
                client.newCall(request).execute().use { resp ->
                    // 405 = 已存在;301 = 服务器对已存在目录重定向,均视为成功
                    val ok = resp.isSuccessful || resp.code == 405 || resp.code == 301
                    if (!ok) throw CloudException("创建目录失败(HTTP ${resp.code})")
                }
            }
        }
    }

    override suspend fun delete(relPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url(relPath))
                .header("Authorization", authHeader)
                .method("DELETE", null).build()
            client.newCall(request).execute().use { resp ->
                // 404 = 已不存在,视为成功
                if (!resp.isSuccessful && resp.code != 404) {
                    throw CloudException("删除失败(HTTP ${resp.code})")
                }
            }
        }
    }

    /**
     * 把响应中的 href 映射为相对远端根的路径;根目录自身条目返回 null。
     * href 可能是完整 URL 或绝对路径,统一取路径部分并 URL 解码后按请求路径剥前缀。
     */
    private fun hrefToRel(href: String, requestUrl: String): String? {
        val decodedHref = decodePath(href)
        val decodedReq = decodePath(requestUrl)
        val reqPath = decodedReq.substringAfter("//", "").substringAfter('/')
        var hrefPath = decodedHref.substringAfter("//", "").substringAfter('/')
        if (hrefPath == reqPath) return null // 目录自身
        val rootPath = reqPath.substringBeforeLast('/')
        if (hrefPath.startsWith(rootPath)) hrefPath = hrefPath.removePrefix(rootPath)
        return hrefPath.trim('/').takeIf { it.isNotBlank() }
    }

    private fun decodePath(url: String): String = runCatching {
        URLDecoder.decode(url, "UTF-8")
    }.getOrDefault(url)

    /** 按段编码路径(空格编码为 %20 而非 +,目录分隔符保留) */
    private fun encodePath(path: String): String =
        path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }.let { "/$it" }

    private companion object {
        const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:allprop/></d:propfind>"
        val XML_MEDIA = "application/xml".toMediaType()
    }
}

/** multistatus 解析出的原始条目(href 未做相对路径映射) */
internal data class WebDavEntry(
    val href: String,
    val isDir: Boolean,
    val size: Long,
    /** epoch 秒 */
    val mtime: Long
)

// ---------- 可单测的解析/工具函数 ----------

/**
 * 解析 WebDAV 207 multistatus 响应(DOM 解析,命名空间前缀无关,
 * JVM 单测与 Android 运行时均可执行;禁用 DTD 防 XXE)。
 */
internal fun parseMultistatus(input: InputStream): List<WebDavEntry> {
    val dbf = DocumentBuilderFactory.newInstance()
    dbf.isNamespaceAware = true
    runCatching { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    val doc = runCatching {
        dbf.newDocumentBuilder().parse(InputSource(input.reader(Charsets.UTF_8)))
    }.getOrNull() ?: return emptyList()
    val root = doc.documentElement ?: return emptyList()
    val nodes = root.getElementsByTagNameNS("*", "response")
    val result = mutableListOf<WebDavEntry>()
    for (i in 0 until nodes.length) {
        val node = nodes.item(i) as? Element ?: continue
        val href = node.firstChildElement("href")?.textContent?.trim().orEmpty()
        if (href.isEmpty()) continue
        val prop = node.firstChildElement("propstat")?.firstChildElement("prop")
        val isDir = prop?.firstChildElement("resourcetype")
            ?.firstChildElement("collection") != null || href.endsWith("/")
        val size = prop?.firstChildElement("getcontentlength")
            ?.textContent?.trim()?.toLongOrNull() ?: 0L
        val mtime = prop?.firstChildElement("getlastmodified")
            ?.textContent?.trim()?.let { parseHttpDate(it) } ?: 0L
        result.add(WebDavEntry(href, isDir, size, mtime))
    }
    return result
}

/** 按局部名查找第一个同名子元素(命名空间前缀无关) */
private fun Element.firstChildElement(localName: String): Element? {
    val children = childNodes
    for (i in 0 until children.length) {
        val c = children.item(i) as? Element ?: continue
        if (c.localName == localName) return c
    }
    return null
}

/** 解析 HTTP 日期(RFC 1123,如 "Wed, 21 Oct 2015 07:28:00 GMT")为 epoch 秒;失败返回 0 */
internal fun parseHttpDate(value: String): Long {
    return try {
        java.time.ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .withZoneSameInstant(ZoneId.of("UTC")).toEpochSecond()
    } catch (e: Exception) {
        runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.parse(value)?.time?.div(1000) ?: 0
        }.getOrDefault(0)
    }
}
