package xyz.normalwindow.htmlviewer.data.update

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases 上的一个 APK 资产。
 * @param name 文件名,如 app-lite-arm64-v8a-release.apk
 * @param browserDownloadUrl 浏览器直链(点击即可下载)
 * @param size 字节数(0 = 未知,如 Atom 源无法获取大小时)
 */
data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long
)

/**
 * GitHub 最新 Release 信息。
 * 来源:GitHub Releases Atom(主,github.com 域,国内直连较稳定)
 *      或 GitHub Releases API(备,api.github.com,部分网络环境不可用)
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
    /** 更新说明 */
    val body: String,
    /** 是否预发布 */
    val isPrerelease: Boolean,
    /** 本次发布包含的 APK 资产(API 源提供;Atom 源为空,由构造模式生成) */
    val assets: List<ReleaseAsset>,
    /** 资产下载基址(如 https://github.com/{owner}/{repo}/releases/download/) */
    val baseDownloadUrl: String = ""
) {
    /** 版本号(去 v 前缀与 -lite/-full 后缀),如 v1.1.3-lite → 1.1.3 */
    val version: String
        get() = tagName.removePrefix("v").substringBefore("-")

    /**
     * 按发行版与 ABI 匹配下载资产(命名约定 app-{edition}-{abi}-release.apk)。
     * API 源直接匹配资产列表;Atom 源按 GitHub 固定下载 URL 模式构造直链:
     *   {baseDownloadUrl}/{tag}/{assetName}
     * 构造失败(缺基址)返回 null,由调用方回退到 Release 页面。
     */
    fun findAsset(isLite: Boolean, abi: String): ReleaseAsset? {
        val edition = if (isLite) "lite" else "full"
        val name = "app-$edition-$abi-release.apk"
        assets.firstOrNull { it.name == name }?.let { return it }
        if (baseDownloadUrl.isBlank()) return null
        return ReleaseAsset(
            name = name,
            browserDownloadUrl = "${baseDownloadUrl.trimEnd('/')}/${tagName.trimStart('/')}/$name",
            size = 0
        )
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
 * 应用内置更新检测。
 * 主源:GitHub Releases Atom(`github.com/{owner}/{repo}/releases.atom`),
 * 域名走 github.com,国内网络直连可用性远好于 api.github.com;
 * 备源:官方 Releases API(提供资产大小等完整信息,部分网络可用)。
 */
class UpdateChecker @javax.inject.Inject constructor() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 主源:GitHub Releases Atom(最新 release 的 RSS,含标题/链接/时间/说明) */
    suspend fun checkLatestAtom(atomUrl: String, downloadBaseUrl: String): Result<UpdateInfo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(atomUrl)
                    .header("User-Agent", "NW-HTMLviewer")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    val bodyText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.e("UpdateCheck", "Atom HTTP ${resp.code}, body=${bodyText.take(200)}")
                        check(false) { "HTTP ${resp.code}" }
                    }
                    parseAtom(bodyText, downloadBaseUrl)
                }
            }
        }

    /** 备源:GitHub Releases API(资产大小等完整信息) */
    suspend fun checkLatestApi(apiUrl: String): Result<UpdateInfo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "NW-HTMLviewer")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    val bodyText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.e("UpdateCheck", "API HTTP ${resp.code}, body=${bodyText.take(200)}")
                        check(false) { "HTTP ${resp.code}" }
                    }
                    val json = JSONObject(bodyText)
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

    /** 解析 Atom feed,取第一个 entry 的标题/链接/时间/说明 */
    private fun parseAtom(xml: String, downloadBaseUrl: String): UpdateInfo {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var entryIndex = 0
        var title = ""
        var link = ""
        var published = ""
        var updated = ""
        var content = ""
        var inFirstEntry = false
        var done = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && !done) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        entryIndex++
                        inFirstEntry = entryIndex == 1
                    }
                    "title" -> if (inFirstEntry) title = parser.nextText()
                    "link" -> if (inFirstEntry && link.isEmpty()) {
                        link = parser.getAttributeValue(null, "href") ?: ""
                    }
                    "published" -> if (inFirstEntry) published = parser.nextText()
                    "updated" -> if (inFirstEntry) updated = parser.nextText()
                    "content" -> if (inFirstEntry) content = parser.nextText()
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry" && inFirstEntry) {
                    inFirstEntry = false
                    done = true
                }
            }
            event = parser.next()
        }
        check(title.isNotBlank() || link.isNotBlank()) { "Atom 无条目" }
        // 版本标签:优先从页面链接 /tag/{tag} 提取,其次从标题提取
        val tag = Regex("/tag/([^/\"\\s]+)").find(link)?.groupValues?.get(1)
            ?: Regex("v?\\d+\\.\\d+\\.\\d+[\\w.-]*").find(title)?.value
            ?: title
        return UpdateInfo(
            tagName = tag,
            name = title,
            htmlUrl = link,
            // GitHub Atom 无 published 标签,用 updated 作为发布时间
            publishedAt = published.ifBlank { updated },
            body = stripHtml(content).ifBlank { "(无更新说明)" },
            isPrerelease = false,
            assets = emptyList(),
            baseDownloadUrl = downloadBaseUrl
        )
    }

    /** 去除 HTML 标签,保留可读文本(Atom content 为 HTML) */
    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
