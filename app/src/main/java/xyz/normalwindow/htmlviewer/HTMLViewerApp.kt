package xyz.normalwindow.htmlviewer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import xyz.normalwindow.htmlviewer.data.settings.AppLanguage
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import javax.inject.Inject

@HiltAndroidApp
class HTMLViewerApp : Application() {

    /** 供 Composable(如 AppNavHost)读取主题模式等全局设置 */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /**
     * 当前界面语言(内存缓存)。
     * attachBaseContext 是同步调用,无法异步读 DataStore,因此:
     * - 启动时在 onCreate 同步预取一次;
     * - 设置页切换语言时先更新此缓存,再 recreate 即时生效;
     * - 跟随系统时保持 SYSTEM。
     */
    @Volatile
    var currentLanguage: AppLanguage = AppLanguage.SYSTEM

    override fun onCreate() {
        super.onCreate()
        // 预取语言设置(DataStore 首读极小,一次性同步开销可忽略)
        runCatching {
            currentLanguage = runBlocking { settingsRepository.preferences.first().language }
        }
        // AppLog 跟随 Debug 模式设置(IO 线程订阅,不阻塞启动)
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ).launch {
            settingsRepository.preferences.collect { prefs ->
                xyz.normalwindow.htmlviewer.data.debug.AppLog.enabled = prefs.debugMode
            }
        }
    }
}
