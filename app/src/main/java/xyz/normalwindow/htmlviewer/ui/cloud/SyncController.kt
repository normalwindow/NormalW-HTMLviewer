package xyz.normalwindow.htmlviewer.ui.cloud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.cloud.CloudManager
import xyz.normalwindow.htmlviewer.data.cloud.CloudSyncEngine
import xyz.normalwindow.htmlviewer.data.cloud.SyncProgress
import xyz.normalwindow.htmlviewer.data.cloud.SyncSnapshotStore
import xyz.normalwindow.htmlviewer.data.cloud.SyncUiState
import xyz.normalwindow.htmlviewer.data.debug.AppLog
import xyz.normalwindow.htmlviewer.data.file.FileRootProvider
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository

/**
 * 云同步流程控制器(设置页与主页共用):
 * 持有同步进度状态与冲突决定收集器,"立即同步"/"启动时自动同步"共用同一入口。
 * 进度对话框被隐藏时,在通知栏展示后台同步进度(需通知权限,Android 13+ 动态请求)。
 */
class SyncController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val fileRootProvider: FileRootProvider,
    private val cloudManager: CloudManager,
    private val cloudSyncEngine: CloudSyncEngine,
    private val syncSnapshotStore: SyncSnapshotStore
) {
    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _progressHidden = MutableStateFlow(false)

    /** 进度对话框被用户隐藏(同步在后台继续,通知栏显示进度,完成后仍弹结果) */
    val progressHidden: StateFlow<Boolean> = _progressHidden.asStateFlow()

    /** 冲突处理决定收集器(同步引擎挂起等待,对话框展示) */
    val conflictDecider = ConflictDecider()

    private val notificationManager = NotificationManagerCompat.from(context)

    /**
     * 立即双向同步:本地工作区 ↔ 活动云盘。
     * 冲突策略为"每次询问"时经 conflictDecider 弹窗收集决定。
     */
    fun syncNow() {
        if (_syncState.value is SyncUiState.Running) return
        _progressHidden.value = false
        scope.launch {
            val prefs = settingsRepository.preferences.first()
            val type = prefs.cloudProvider
            val provider = cloudManager.providerFromPrefs(prefs, type)
            if (provider == null) {
                _syncState.value = SyncUiState.Failed("")
                return@launch
            }
            _syncState.value = SyncUiState.Running(SyncProgress(SyncProgress.Phase.SCANNING))
            try {
                val snapshot = syncSnapshotStore.load(type)
                val outcome = cloudSyncEngine.sync(
                    localRoot = fileRootProvider.defaultRoot,
                    trashDir = fileRootProvider.trashDir,
                    provider = provider,
                    snapshot = snapshot,
                    policy = prefs.syncConflictPolicy,
                    onProgress = { p ->
                        _syncState.value = SyncUiState.Running(p)
                        updateBackgroundNotification(p)
                    },
                    resolveConflicts = { files -> conflictDecider.awaitDecisions(files) }
                )
                syncSnapshotStore.save(type, outcome.snapshot)
                settingsRepository.setLastSyncAt(type, System.currentTimeMillis())
                _syncState.value = SyncUiState.Done(outcome.result)
                cancelBackgroundNotification()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Cloud", "同步失败: ${e.message}", e)
                _syncState.value = SyncUiState.Failed(e.message ?: "")
                cancelBackgroundNotification()
            }
        }
    }

    fun consumeState() {
        if (_syncState.value !is SyncUiState.Running) {
            _syncState.value = SyncUiState.Idle
            _progressHidden.value = false
            cancelBackgroundNotification()
        }
    }

    fun hideProgress() {
        _progressHidden.value = true
        // 隐藏对话框后改用通知栏展示进度
        (_syncState.value as? SyncUiState.Running)?.let { updateBackgroundNotification(it.progress) }
    }

    /** 是否有通知权限(Android 13+ 需要 POST_NOTIFICATIONS 动态授权) */
    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_sync),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateBackgroundNotification(progress: SyncProgress) {
        if (!_progressHidden.value) return
        if (!hasNotificationPermission()) return
        ensureChannel()
        val text = when (progress.phase) {
            SyncProgress.Phase.SCANNING -> context.getString(R.string.sync_scanning)
            else -> "${progress.currentFile}  (${progress.done}/${progress.total})"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.notif_sync_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(progress.total, progress.done, progress.phase == SyncProgress.Phase.SCANNING)
            .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelBackgroundNotification() {
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
    }

    companion object {
        private const val CHANNEL_ID = "cloud_sync"
        private const val NOTIFICATION_ID = 1001
    }
}
