package xyz.normalwindow.htmlviewer

import android.app.Activity
import android.content.Context
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
import xyz.normalwindow.htmlviewer.ui.navigation.AppNavHost
import xyz.normalwindow.htmlviewer.ui.theme.HTMLViewerTheme
import xyz.normalwindow.htmlviewer.util.LocaleManager
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

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
}
