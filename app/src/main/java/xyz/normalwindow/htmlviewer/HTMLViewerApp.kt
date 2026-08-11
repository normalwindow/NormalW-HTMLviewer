package xyz.normalwindow.htmlviewer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import javax.inject.Inject

@HiltAndroidApp
class HTMLViewerApp : Application() {

    /** 供 Composable(如 AppNavHost)读取主题模式等全局设置 */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
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
