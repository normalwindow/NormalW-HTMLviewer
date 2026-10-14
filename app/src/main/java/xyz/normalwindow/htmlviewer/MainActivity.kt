package xyz.normalwindow.htmlviewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import xyz.normalwindow.htmlviewer.data.settings.AppLanguage
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import xyz.normalwindow.htmlviewer.data.settings.ThemeMode
import xyz.normalwindow.htmlviewer.data.settings.UserPreferences
import xyz.normalwindow.htmlviewer.data.share.IncomingShareStore
import xyz.normalwindow.htmlviewer.ui.navigation.AppNavHost
import xyz.normalwindow.htmlviewer.ui.theme.HTMLViewerTheme
import xyz.normalwindow.htmlviewer.util.LocaleManager
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** 分享接收中转站:onCreate / onNewIntent 写入,界面层消费 */
    @Inject
    lateinit var incomingShareStore: IncomingShareStore

    /** 应用内语言切换:把所选语言套到资源 Configuration 上(跟随系统时原样返回)。
     * 注意:attachBaseContext 阶段 Activity.application 尚未赋值(为 null),
     * 必须通过 newBase.applicationContext 取 Application 实例。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            LocaleManager.apply(
                newBase,
                (newBase.applicationContext as? HTMLViewerApp)?.currentLanguage ?: AppLanguage.SYSTEM
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动即由分享进入时先接收,再交给界面层处理
        handleShareIntent(intent)
        setContent {
            val prefs by settingsRepository.preferences
                .collectAsStateWithLifecycle(initialValue = UserPreferences())
            val darkTheme = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 状态栏字体颜色跟随应用主题(深色主题 → 浅色图标):
            // 部分 ROM(如 MIUI)上 enableEdgeToEdge 默认不切换,必须显式设置
            val window = LocalContext.current as? Activity
            LaunchedEffect(darkTheme, window) {
                val w = window ?: return@LaunchedEffect
                WindowCompat.getInsetsController(w.window, w.window.decorView)
                    .isAppearanceLightStatusBars = !darkTheme
            }
            HTMLViewerTheme(
                darkTheme = darkTheme,
                dynamicColor = prefs.dynamicColor,
                seedColor = prefs.customColorSeed?.let { Color(it) },
                colorStyle = prefs.colorStyle
            ) {
                AppNavHost()
            }
        }
    }

    /** singleTask 下再次分享会走这里,而不是新建 Activity 实例 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * 解析 ACTION_SEND / ACTION_SEND_MULTIPLE 的分享内容并投入中转站。
     *
     * 用 EXTRA_STREAM 优先、ClipData 兜底:部分应用(如某些文件管理器)只填 ClipData,
     * 只读 EXTRA_STREAM 会拿到空 uri。仅处理本应用已声明接收的文本类型。
     */
    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val uris = mutableListOf<Uri>()
        val mimes = mutableListOf<String?>()

        if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?.let { uris.addAll(it) }
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        }

        // EXTRA_STREAM 缺失时回退到 ClipData
        if (uris.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
        }
        if (uris.isEmpty()) return

        val type = intent.type
        uris.forEach { mimes.add(type) }

        // 确保读取授权覆盖本次分享的 uri(部分三方应用不主动授予)
        runCatching {
            uris.forEach { grantUriPermission(packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        incomingShareStore.submit(uris, mimes)
    }
}
