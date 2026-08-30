package xyz.normalwindow.htmlviewer.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 百度 OAuth 令牌(换码/刷新接口返回) */
data class BaiduTokens(
    val accessToken: String,
    val refreshToken: String,
    /** 有效期(epoch 毫秒) */
    val expiresAt: Long
)

/**
 * 百度网盘开放平台(xpan REST API)Provider。
 *
 * - OAuth2:授权码模式,回调地址 oob(授权页直接显示授权码,由设置页 WebView 提取后换取令牌);
 *   access_token 约 30 天有效,过期/返回 errno -6 时用 refresh_token 自动续期并重试一次。
 * - 文件 API:list(列目录)/ meta / download / precreate→superfile2→create(三步上传,支持秒传)/
 *   create&isdir=1(建目录)/ filemanager&opera=delete(删除)。
 * - 沙箱应用只能访问 /apps/<应用名>/ 目录,远端根目录固定为其下(由 CloudManager 传入)。
 * - 所有请求 User-Agent 含 "pan.baidu.com"(下载接口强校验)。
 */
class BaiduProvider(
    private val appKey: String,
    private val secretKey: String,
    /** 远端根目录(绝对路径,如 /apps/HTMLviewer) */
    private val remoteRoot: String,
    accessToken: String?,
    refreshToken: String?,
    expiresAt: Long,
    /** 令牌刷新后持久化回调(null 表示清除登录态) */
    private val onTokenRefreshed: suspend (String?, String?, Long) -> Unit
) : CloudProvider {

    override val type: CloudProviderType get() = CloudProviderType.BAIDU

    @Volatile private var accessToken: String? = accessToken
    @Volatile private var refreshToken: String? = refreshToken
    @Volatile private var expiresAt: Long = expiresAt

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ---------- OAuth ----------

    /**
     * 令牌有效(且距过期 > 7 天)则直接返回,否则用 refresh_token 刷新。
     * access_token 单次有效期固定 30 天(平台限制,无法延长);
     * refresh_token 有效期 10 年,只要 7 天内打开过应用即可无限续期。
     */
    private suspend fun ensureToken(): String {
        val token = accessToken
        if (!token.isNullOrBlank() && expiresAt - System.currentTimeMillis() > RENEW_AHEAD_MS) {
            return token
        }
        return refresh()
    }

    /** 用 refresh_token 换新令牌;失败清除登录态并抛错(提示重新授权) */
    private suspend fun refresh(): String {
        val rt = refreshToken
        if (rt.isNullOrBlank()) {
            clearTokens()
            throw CloudException("尚未授权或授权已失效,请重新登录", -6)
        }
        val json = try {
            httpJson(
                buildTokenUrl(
                    "refresh_token", mapOf(
                        "refresh_token" to rt,
                        "client_id" to appKey,
                        "client_secret" to secretKey
                    )
                )
            )
        } catch (e: CloudException) {
            clearTokens()
            throw CloudException("授权已过期,请重新登录(刷新失败)", -6)
        }
        val tokens = parseBaiduTokenResponse(json.toString())
        accessToken = tokens.accessToken
        refreshToken = tokens.refreshToken
        expiresAt = tokens.expiresAt
        onTokenRefreshed(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
        return tokens.accessToken
    }

    private suspend fun clearTokens() {
        accessToken = null
        refreshToken = null
        expiresAt = 0
        onTokenRefreshed(null, null, 0)
    }

    /** 鉴权检查:令牌有效 + 远端根目录存在(沙箱目录首次使用时可能尚未创建,自动建立) */
    override suspend fun checkAuth() {
        ensureToken()
        ensureRemoteRoot()
    }

    /** 确保远端根目录存在(不存在则创建;已存在/有权限时静默) */
    private suspend fun ensureRemoteRoot() {
        // 注意:listUrl 内部会做 absPath 拼接,必须传相对根的空串而非 remoteRoot,
        // 否则路径变成 <root>/<root> 恒报"不存在",每次同步都会重复建目录(百度同名自动重命名产生垃圾目录)
        val json = withAuthRetry {
            val j = httpJson(listUrl("", page = 1, num = 1))
            if (j.optInt("errno", 0) !in setOf(0, ERRNO_NOT_EXIST, ERRNO_NOAUTH)) {
                checkErrno(j, tolerate = emptySet())
            }
            j
        }
        val errno = json.optInt("errno", 0)
        if (errno == ERRNO_NOT_EXIST || errno == ERRNO_NOAUTH) {
            runCatching { mkdirRoot() }
        }
    }

    /** 构造带 access_token 的列目录 URL(必须在重试 lambda 内调用,保证刷新后的新令牌被使用) */
    private suspend fun listUrl(dir: String, page: Int, num: Int): String =
        "$API_FILE?method=list&web=1&order=name&desc=0&num=$num&page=$page" +
            "&access_token=" + baiduEnc(ensureToken()) +
            "&dir=" + baiduEnc(absPath(dir))

    /** 创建远端根目录 */
    private suspend fun mkdirRoot() {
        withAuthRetry {
            val j = postForm(
                "$API_FILE?method=create",
                mapOf("path" to remoteRoot, "isdir" to "1", "rtype" to "0")
            )
            checkErrno(j, tolerate = setOf(ERRNO_EXISTS, ERRNO_NOT_EXIST))
            j
        }
    }

    // ---------- 目录与元数据 ----------

    override suspend fun list(dir: String): Result<List<CloudFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = mutableListOf<CloudFile>()
            // 注意:page 从 1 开始(0 会返回错误/空结果),每页数量参数为 num(上限 1000)
            var page = 1
            while (true) {
                val json = withAuthRetry {
                    val j = httpJson(listUrl(dir, page = page, num = PAGE_SIZE))
                    checkErrno(j, tolerate = emptySet())
                    j
                }
                val list = parseBaiduListResponse(json.toString(), remoteRoot)
                result += list
                if (list.size < PAGE_SIZE || page >= MAX_PAGES) break
                page++
            }
            result
        }
    }

    override suspend fun meta(relPath: String): Result<CloudFile?> = withContext(Dispatchers.IO) {
        runCatching {
            // 通过父目录 list 查找(避免依赖不同版本文档中不一致的 method=meta 接口)
            val rel = relPath.trim('/')
            val parent = rel.substringBeforeLast('/', missingDelimiterValue = "")
            val name = rel.substringAfterLast('/')
            val children = list(parent).getOrThrow()
            children.firstOrNull { it.name == name }
        }
    }

    // ---------- 下载 ----------

    override suspend fun download(relPath: String, dest: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val abs = absPath(relPath)
                withAuthRetry {
                    // URL 在重试块内构造:刷新令牌后用新 token 重建。
                    // 下载走 filemetas→dlink(官方推荐,直连 method=download 对部分应用返回 403);
                    // fs_id 不可得时回退 method=download。
                    val fsId = meta(relPath).getOrNull()?.fsId ?: 0
                    val url = if (fsId > 0) {
                        val dlink = fetchDlinkByFsId(fsId)
                        dlink + (if ('?' in dlink) "&" else "?") + "access_token=" + baiduEnc(ensureToken())
                    } else {
                        "$API_FILE?method=download&access_token=" + baiduEnc(ensureToken()) +
                            "&path=" + baiduEnc(abs)
                    }
                    val request = Request.Builder().url(url).header("User-Agent", BAIDU_UA).get().build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            if (resp.code == 401 || resp.code == 403) {
                                throw CloudException("下载失败:访问令牌无效或已过期(HTTP ${resp.code})", -6)
                            }
                            throw CloudException("下载失败:HTTP ${resp.code}")
                        }
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
        }

    /** filemetas(multimedia 接口)取 dlink */
    private suspend fun fetchDlinkByFsId(fsId: Long): String = withContext(Dispatchers.IO) {
        val url = "$API_MULTIMEDIA?method=filemetas&dlink=1&thumb=0&extra=0" +
            "&access_token=" + baiduEnc(ensureToken()) +
            "&fs_ids=" + baiduEnc("[$fsId]")
        val json = httpJson(url)
        checkErrno(json, tolerate = emptySet())
        val dlink = parseBaiduFilemetas(json.toString())
            ?: throw CloudException("获取下载链接失败:响应缺少 dlink")
        dlink
    }

    // ---------- 上传(三步:precreate → superfile2 → create) ----------

    override suspend fun upload(relPath: String, src: File): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(src.isFile) { "本地文件不存在:${src.name}" }
                if (relPath.contains('/')) mkdirs(relPath.substringBeforeLast('/')).getOrThrow()
                val abs = absPath(relPath)
                val size = src.length()
                val blockList = blockMd5List(src)

                // 1. 预上传:return_type=2 表示秒传,可跳过分片上传
                val pre = withAuthRetry {
                    val j = postForm(
                        "$API_FILE?method=precreate",
                        mapOf(
                            "path" to abs,
                            "size" to size.toString(),
                            "isdir" to "0",
                            "autoinit" to "1",
                            // rtype=2:远端同名文件直接覆盖(同步语义);0=冲突报错会让更新上传永远失败
                            "rtype" to "2",
                            "block_list" to JSONArray(blockList).toString()
                        )
                    )
                    checkErrno(j, tolerate = emptySet())
                    j
                }
                val uploadId = pre.optString("uploadid")
                if (pre.optInt("return_type") != 2) {
                    // 2. 分片上传(4MB/片)
                    src.inputStream().use { input ->
                        val buf = ByteArray(PART_SIZE)
                        var seq = 0
                        while (true) {
                            val read = readUpTo(input, buf)
                            if (read <= 0) break
                            val url = "$API_UPLOAD?method=upload&type=tmpfile&path=" + baiduEnc(abs) +
                                "&uploadid=" + baiduEnc(uploadId) + "&partseq=" + seq
                            val partBody = MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart(
                                    "file", src.name,
                                    okhttp3.RequestBody.create(
                                        "application/octet-stream".toMediaType(), buf, 0, read
                                    )
                                )
                                .build()
                            val resp = withAuthRetry {
                                val j = postMultipart(url, partBody)
                                checkErrno(j, tolerate = emptySet())
                                j
                            }
                            seq++
                            if (read < PART_SIZE) break
                        }
                    }
                }
                // 3. 创建文件
                val create = withAuthRetry {
                    val j = postForm(
                        "$API_FILE?method=create",
                        mapOf(
                            "path" to abs,
                            "size" to size.toString(),
                            "isdir" to "0",
                            "rtype" to "2",
                            "uploadid" to uploadId,
                            "block_list" to JSONArray(blockList).toString()
                        )
                    )
                    checkErrno(j, tolerate = emptySet())
                    j
                }
                create.optLong("mtime").takeIf { it > 0 }
                    ?: (System.currentTimeMillis() / 1000)
            }
        }

    // ---------- 目录 / 删除 ----------

    override suspend fun mkdirs(relPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (relPath.isBlank()) return@runCatching
            val segments = relPath.split('/').filter { it.isNotBlank() }
            var current = ""
            segments.forEach { seg ->
                current = if (current.isEmpty()) seg else "$current/$seg"
                val abs = absPath(current)
                withAuthRetry {
                    val j = postForm(
                        "$API_FILE?method=create",
                        mapOf("path" to abs, "isdir" to "1", "rtype" to "0")
                    )
                    // -8 已存在视为成功
                    checkErrno(j, tolerate = setOf(ERRNO_EXISTS))
                    j
                }
            }
        }
    }

    override suspend fun delete(relPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val abs = absPath(relPath)
            withAuthRetry {
                val j = postForm(
                    "$API_FILEMANAGER?method=filemanager&opera=delete",
                    mapOf("filelist" to JSONArray(listOf(abs)).toString())
                )
                // 12 = 文件不存在,视为已删除
                checkErrno(j, tolerate = setOf(ERRNO_NOT_EXIST))
                j
            }
            Unit
        }
    }

    // ---------- HTTP 基础 ----------

    /** 包一层 -6(令牌失效)自动刷新并重试一次的逻辑 */
    private suspend fun <T> withAuthRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: CloudException) {
            if (e.code == ERRNO_BAD_TOKEN) {
                refresh()
                block()
            } else {
                throw e
            }
        }
    }

    private suspend fun httpJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", BAIDU_UA).get().build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && text.isBlank()) throw CloudException("HTTP ${resp.code}")
            JSONObject(text)
        }
    }

    /** 带 access_token 的表单 POST(xpan 文件接口) */
    private suspend fun postForm(endpoint: String, fields: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val token = ensureToken()
            val url = endpoint + "&access_token=" + baiduEnc(token)
            val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder().url(url).header("User-Agent", BAIDU_UA).post(body).build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                JSONObject(text)
            }
        }

    private suspend fun postMultipart(url: String, body: MultipartBody): JSONObject =
        withContext(Dispatchers.IO) {
            val token = ensureToken()
            val full = url + "&access_token=" + baiduEnc(token)
            val request = Request.Builder().url(full).header("User-Agent", BAIDU_UA).post(body).build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                JSONObject(text)
            }
        }

    private fun checkErrno(json: JSONObject, tolerate: Set<Int>) {
        val errno = json.optInt("errno", 0)
        if (errno == 0 || errno in tolerate) return
        throw CloudException(errnoMessage(errno), errno)
    }

    private fun absPath(rel: String): String =
        if (rel.isBlank()) remoteRoot else remoteRoot.trimEnd('/') + "/" + rel.trimStart('/')

    /** 读满整个缓冲或到文件尾,返回实际读取字节数(分片上传用) */
    private fun readUpTo(input: java.io.InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    companion object {
        const val API_FILE = "https://pan.baidu.com/rest/2.0/xpan/file"
        const val API_MULTIMEDIA = "https://pan.baidu.com/rest/2.0/xpan/multimedia"
        const val API_FILEMANAGER = "https://pan.baidu.com/rest/2.0/xpan/filemanager"
        const val API_UPLOAD = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2"
        const val BAIDU_UA = "pan.baidu.com"

        /** 下载/列表单页条数上限 */
        const val PAGE_SIZE = 1000
        const val MAX_PAGES = 200

        /** 分片大小 4MB(百度分片上传限制) */
        const val PART_SIZE = 4 * 1024 * 1024

        /** 提前 7 天续期(应用启动/每次操作时自动刷新,配合 10 年有效的 refresh_token 实际可无限续期) */
        const val RENEW_AHEAD_MS = 7L * 24 * 3600_000L

        const val ERRNO_BAD_TOKEN = -6
        const val ERRNO_EXISTS = -8
        const val ERRNO_NOAUTH = -9
        const val ERRNO_NOT_EXIST = 12

        fun errnoMessage(errno: Int): String = when (errno) {
            -6 -> "访问令牌无效,请重新授权"
            -7 -> "文件或目录名非法(沙箱应用仅可访问 /apps/应用名/ 目录)"
            -8 -> "文件或目录已存在"
            -9 -> "文件或目录不存在(沙箱应用仅可访问 /apps/应用名/ 目录)"
            12 -> "文件或目录不存在"
            31034 -> "请求过于频繁,请稍后再试"
            else -> "百度网盘返回错误码 $errno"
        }

        fun buildTokenUrl(grantType: String, extra: Map<String, String>): String {
            val params = StringBuilder("grant_type=").append(baiduEnc(grantType))
            extra.forEach { (k, v) -> params.append('&').append(k).append('=').append(baiduEnc(v)) }
            return "https://openapi.baidu.com/oauth/2.0/token?$params"
        }

        /** OAuth 授权页 URL(WebView 加载;redirect_uri=oob 授权后页面直接显示授权码) */
        fun authorizeUrl(appKey: String): String =
            "https://openapi.baidu.com/oauth/2.0/authorize?response_type=code" +
                "&client_id=" + baiduEnc(appKey) +
                "&redirect_uri=oob&scope=basic,netdisk&display=mobile"
    }
}

// ---------- 可单测的解析函数 ----------

internal fun baiduEnc(v: String): String =
    java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

/**
 * 用授权码换取令牌(设置页授权完成后调用)。
 * 独立顶级函数:不依赖 Provider 实例的登录态。
 */
internal suspend fun exchangeBaiduCode(appKey: String, secretKey: String, code: String): BaiduTokens =
    withContext(Dispatchers.IO) {
        val url = "https://openapi.baidu.com/oauth/2.0/token?grant_type=authorization_code" +
            "&code=" + baiduEnc(code) +
            "&client_id=" + baiduEnc(appKey) +
            "&client_secret=" + baiduEnc(secretKey) +
            "&redirect_uri=oob"
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "pan.baidu.com").get().build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            parseBaiduTokenResponse(text)
        }
    }

/** 解析 OAuth 换码/刷新响应;业务错误(error 字段)抛 CloudException */
internal fun parseBaiduTokenResponse(json: String): BaiduTokens {
    val obj = JSONObject(json)
    val error = obj.optString("error")
    if (error.isNotBlank()) {
        throw CloudException(obj.optString("error_description", error))
    }
    val access = obj.optString("access_token")
    if (access.isBlank()) throw CloudException("授权响应缺少 access_token")
    val expiresIn = obj.optLong("expires_in", 2592000L)
    return BaiduTokens(
        accessToken = access,
        refreshToken = obj.optString("refresh_token"),
        expiresAt = System.currentTimeMillis() + expiresIn * 1000
    )
}

/** 解析 method=list 响应为相对路径条目(根前缀被剥掉) */
internal fun parseBaiduListResponse(json: String, rootPrefix: String): List<CloudFile> {
    val obj = JSONObject(json)
    val arr = obj.optJSONArray("list") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { cloudFileFrom(it, rootPrefix) }
    }
}

private fun cloudFileFrom(item: JSONObject, rootPrefix: String): CloudFile? {
    val abs = item.optString("path")
    if (abs.isBlank()) return null
    val rel = abs.removePrefix(rootPrefix.trimEnd('/')).trim('/')
    if (rel.isBlank()) return null
    val name = rel.substringAfterLast('/')
    return CloudFile(
        path = rel,
        name = name,
        isDir = item.optInt("isdir") == 1,
        size = item.optLong("size"),
        mtime = item.optLong("server_mtime", item.optLong("mtime", 0)),
        fsId = item.optLong("fs_id")
    )
}

/** 解析 filemetas 响应,取首个 dlink(官方下载直链,需追加 access_token 使用) */
internal fun parseBaiduFilemetas(json: String): String? {
    val obj = JSONObject(json)
    val arr = obj.optJSONArray("list") ?: return null
    val first = arr.optJSONObject(0) ?: return null
    return first.optString("dlink").takeIf { it.isNotBlank() }
}

/** 流式计算 4MB 分片 MD5(百度 precreate 要求大写十六进制) */
internal fun blockMd5List(file: File, partSize: Int = 4 * 1024 * 1024): List<String> {
    val result = mutableListOf<String>()
    file.inputStream().use { input ->
        val buf = ByteArray(partSize)
        while (true) {
            var read = 0
            while (read < partSize) {
                val n = input.read(buf, read, partSize - read)
                if (n < 0) break
                read += n
            }
            if (read <= 0) break
            val digest = MessageDigest.getInstance("MD5").digest(buf.copyOf(read))
            result.add(digest.joinToString("") { "%02X".format(it) })
            if (read < partSize) break
        }
    }
    if (result.isEmpty()) result.add(MessageDigest.getInstance("MD5").digest(ByteArray(0)).joinToString("") { "%02X".format(it) })
    return result
}
