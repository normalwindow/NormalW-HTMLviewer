package xyz.normalwindow.htmlviewer.data.cloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步快照持久化:每个云盘一份(filesDir/cloud_sync/snapshot_<provider>.json)。
 * 编辑器保存后自动上传时会单独更新单条记录,避免整份重算。
 */
@Singleton
class SyncSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private fun fileFor(type: CloudProviderType): File =
        File(File(context.filesDir, "cloud_sync"), "snapshot_${type.storageValue}.json")

    suspend fun load(type: CloudProviderType): SyncSnapshot = withContext(Dispatchers.IO) {
        val f = fileFor(type)
        if (f.isFile) SyncSnapshot.parse(runCatching { f.readText() }.getOrDefault(""))
        else SyncSnapshot.empty()
    }

    suspend fun save(type: CloudProviderType, snapshot: SyncSnapshot) = withContext(Dispatchers.IO) {
        runCatching {
            fileFor(type).parentFile?.mkdirs()
            fileFor(type).writeText(snapshot.toJson())
        }.isSuccess
    }

    /** 单条更新(自动上传/自动删除后调用,避免下次同步误判为改动) */
    suspend fun updateEntry(type: CloudProviderType, relPath: String, entry: SnapshotEntry?) =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot = load(type)
                if (entry == null) snapshot.remove(relPath) else snapshot.put(relPath, entry)
                save(type, snapshot)
            }
        }

    /** 切换/退出登录时清空对应云盘的快照 */
    suspend fun clear(type: CloudProviderType) = withContext(Dispatchers.IO) {
        runCatching { fileFor(type).delete() }.isSuccess
    }
}
