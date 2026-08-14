package xyz.normalwindow.htmlviewer.data.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases 上的一个 APK 资产。
 * @param name 文件名,如 app-lite-arm64-v8a-release.apk
 * @param browserDownloadUrl 浏览器直链(点击即可下载)
 * @param size 字节数
 */
data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long
)

/**
 * GitHub 最新 Release 信息(GitHub Releases API 核心字段)。
 * 来源:GET https://api.github.com/repos/{owner}/{repo}/releases/latest
 */
data class UpdateInfo(
    /** 版本标签,如 v1.1.3 / v1.1.2-lite */
    val tagName: String,
    /** Release 标题 */
    val name: String,
    /** Release 页面链接 */
    val htmlUrl: String,
    /** 发布时间(ISO-8601 UTC,如 2026-08-14T08:00:00Z) */
    val publishedAt: String,
    /** 更新说明(Markdown) */
    val body: String,
    /** 是否预发布 */
    val isPrerelease: Boolean,
    /** 本次发布包含的 APK 资产 */
    val assets: List<ReleaseAsset>
) {
    /** 版本号(去 v 前缀与 -lite/-full 后缀),如 v1.1.3-lite → 1.1.3 */
    val version: String
        get() = tagName.removePrefix("v").substringBefore("-")

    /**
     * 按发行版与 ABI 匹配下载资产:
     * 资产命名约定 app-{edition}-{abi}-release.apk(edition = full/lite)。
     * 匹配不到(如新 ABI)返回 null,由调用方回退到 Release 页面。
     */
    fun findAsset(isLite: Boolean, abi: String): ReleaseAsset? {
        val edition = if (isLite) "lite" else "full"
        return assets.firstOrNull { it.name == "app-$edition-$abi-release.apk" }
    }
}

/**
 * 语义化版本比较(支持 1.1.3 / v1.1.3 / 1.1.3-lite 形式)。
 * 仅比较主版本段,忽略 -lite 等预发布后缀(同一主版本号的 full/lite 视为同版本)。
 */
fun isNewerVersion(remote: String, current: String): Boolean {
    fun parse(v: String): List<Int> =
        v.removePrefix("v").substringBefore("-").split(".").mapNotNull { it.toIntOrNull() }
    val r = parse(remote)
    val c = parse(current)
    for (i in 0 until maxOf(r.size, c.size)) {
        val rv = r.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (rv != cv) return rv > cv
    }
    return false
}

/**
 * 应用内置更新检测:查询本仓库 GitHub Releases 最新版。
 * 使用官方 Releases API(未认证限 60 次/小时,足够个人应用使用):
 *   https://api.github.com/repos/normalwindow/NormlW-HTMLviewer/releases/latest
 */
class UpdateChecker @javax.inject.Inject constructor() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkLatest(repoUrl: String): Result<UpdateInfo> = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        runCatching {
            val apiUrl = repoUrl.trimEnd('/').removeSuffix(".git") + "/releases/latest"
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NW-HTMLviewer")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val json = JSONObject(resp.body?.string().orEmpty())
                val assets = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val a = arr.optJSONObject(i) ?: return@mapNotNull null
                        val name = a.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        ReleaseAsset(
                            name = name,
                            browserDownloadUrl = a.optString("browser_download_url"),
                            size = a.optLong("size", 0)
                        )
                    }
                } ?: emptyList()
                UpdateInfo(
                    tagName = json.optString("tag_name"),
                    name = json.optString("name").ifBlank { json.optString("tag_name") },
                    htmlUrl = json.optString("html_url"),
                    publishedAt = json.optString("published_at"),
                    body = json.optString("body").ifBlank { "(无更新说明)" },
                    isPrerelease = json.optBoolean("prerelease", false),
                    assets = assets
                )
            }
        }
    }
}
