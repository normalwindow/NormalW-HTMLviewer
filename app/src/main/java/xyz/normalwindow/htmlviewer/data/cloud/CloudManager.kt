package xyz.normalwindow.htmlviewer.data.cloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云盘统一管理器:
 * - 按设置构建当前活动 CloudProvider(百度/WebDAV,凭据缺失返回 null);
 * - 云端文件的本地缓存路径采用确定性映射 filesDir/cloud/<provider>/<相对路径>,
 *   由本地路径即可反查云端来源(编辑器保存后自动上传依赖此映射,无需额外记录表)。
 */
@Singleton
class CloudManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    // ---------- 远端根目录 ----------

    /** 百度远端根目录(用户可配置;默认 /apps/<应用名>,沙箱应用仅能访问该目录) */
    fun baiduRemoteRoot(prefs: xyz.normalwindow.htmlviewer.data.settings.UserPreferences): String =
        prefs.baiduRemoteRoot.trim().ifBlank { DEFAULT_BAIDU_REMOTE_ROOT }
            .let { if (it.startsWith("/")) it else "/$it" }

    fun webdavRemoteRoot(prefs: xyz.normalwindow.htmlviewer.data.settings.UserPreferences): String =
        prefs.webdavDir.trim().ifBlank { DEFAULT_WEBDAV_DIR }
            .let { if (it.startsWith("/")) it else "/$it" }

    // ---------- Provider 构建 ----------

    suspend fun activeProvider(): CloudProvider? {
        val prefs = settingsRepository.preferences.first()
        return providerFromPrefs(prefs, prefs.cloudProvider)
    }

    /**
     * 按偏好构建指定类型的 Provider;凭据不完整(未配置/未登录)返回 null。
     * 百度凭据留空时回退 BuildConfig 默认值(构建时从 tool/baidu-key.txt 注入)。
     */
    fun providerFromPrefs(
        prefs: xyz.normalwindow.htmlviewer.data.settings.UserPreferences,
        type: CloudProviderType
    ): CloudProvider? = when (type) {
        CloudProviderType.NONE -> null
        CloudProviderType.BAIDU -> {
            // 凭据不内置:用户须在设置页填写自己的 AppKey/SecretKey
            val appKey = prefs.baiduAppKey.trim()
            val secretKey = prefs.baiduSecretKey.trim()
            if (appKey.isBlank() || secretKey.isBlank()) null
            else BaiduProvider(
                appKey = appKey,
                secretKey = secretKey,
                remoteRoot = baiduRemoteRoot(prefs),
                accessToken = prefs.baiduAccessToken,
                refreshToken = prefs.baiduRefreshToken,
                expiresAt = prefs.baiduTokenExpiresAt,
                onTokenRefreshed = { a, r, e ->
                    settingsRepository.setBaiduTokens(a, r, e)
                }
            )
        }
        CloudProviderType.WEBDAV -> {
            if (prefs.webdavUrl.isBlank() || prefs.webdavUsername.isBlank()) null
            else WebDavProvider(
                baseUrl = prefs.webdavUrl,
                username = prefs.webdavUsername,
                password = prefs.webdavPassword,
                remoteRoot = webdavRemoteRoot(prefs)
            )
        }
    }

    /** 指定云盘是否已具备可用凭据(设置页展示用) */
    fun hasCredentials(prefs: xyz.normalwindow.htmlviewer.data.settings.UserPreferences, type: CloudProviderType): Boolean =
        providerFromPrefs(prefs, type) != null

    /** 百度 OAuth 授权页 URL(设置页 WebView 加载) */
    fun baiduAuthorizeUrl(prefs: xyz.normalwindow.htmlviewer.data.settings.UserPreferences): String =
        BaiduProvider.authorizeUrl(prefs.baiduAppKey.trim())

    // ---------- 云端文件的本地缓存路径(确定性映射) ----------

    /** 云端相对路径 → 本地缓存文件(filesDir/cloud/<provider>/<rel>) */
    fun localPathFor(type: CloudProviderType, relPath: String): File =
        File(cloudCacheDir(type), relPath)

    /** 本地缓存路径 → (云盘类型, 云端相对路径);不在缓存目录下返回 null */
    fun cacheOriginFor(localPath: String): Pair<CloudProviderType, String>? {
        CloudProviderType.entries.forEach { type ->
            if (type == CloudProviderType.NONE) return@forEach
            val dir = cloudCacheDir(type)
            val absDir = dir.absolutePath.trimEnd('/')
            val absFile = File(localPath).absolutePath
            if (absFile == absDir) return type to ""
            if (absFile.startsWith(absDir + "/")) {
                return type to absFile.removePrefix(absDir + "/")
            }
        }
        return null
    }

    private fun cloudCacheDir(type: CloudProviderType): File =
        File(File(context.filesDir, "cloud"), type.storageValue)

    companion object {
        /** 百度默认远端根目录(沙箱应用注册的应用名;可在设置中改为其他目录) */
        const val DEFAULT_BAIDU_REMOTE_ROOT = "/apps/HTMLviewer"

        /** WebDAV 默认远端目录 */
        const val DEFAULT_WEBDAV_DIR = "/NW'HTMLviewer"
    }
}
