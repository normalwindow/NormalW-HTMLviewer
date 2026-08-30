package xyz.normalwindow.htmlviewer.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 一次同步的产出:统计结果 + 应写回的新快照 */
data class SyncOutcome(
    val result: SyncResult,
    val snapshot: SyncSnapshot
)

/**
 * 双向同步引擎(纯逻辑,无 Android 依赖,可单测):
 *
 * 1. 扫描本地工作区与云端目录,载入上次同步快照;
 * 2. 按路径并集生成动作计划(上传/下载/传播删除/冲突);
 * 3. 依次执行,冲突按策略处理(弹窗询问/新者胜/保留双方);
 * 4. 成功后回写新快照(两侧 mtime),下次同步据此判断"哪一侧改过"。
 *
 * 本地删除传播到云端、云端删除传播到本地都以快照为判据:
 * 一侧存在、快照中也有 → 对方被删,同步删除;快照中没有 → 本侧新增,同步上传/下载。
 */
@Singleton
class CloudSyncEngine @Inject constructor() {

    /**
     * 执行一次双向同步。失败抛异常(鉴权失败等全局错误);单个文件失败计入 failed 不中断。
     *
     * @param localRoot 本地工作区根目录
     * @param trashDir 本地删除的暂存目录(同步删除不直接物理删除,可恢复)
     * @param provider 云盘 Provider
     * @param snapshot 上次同步快照(首次同步传空)
     * @param policy 冲突策略(ASK 时通过 [resolveConflicts] 收集决定)
     * @param resolveConflicts 冲突弹窗回调:入参冲突路径列表,返回每个文件的决定(缺失视为跳过)
     */
    suspend fun sync(
        localRoot: File,
        trashDir: File?,
        provider: CloudProvider,
        snapshot: SyncSnapshot,
        policy: SyncConflictPolicy,
        onProgress: suspend (SyncProgress) -> Unit = {},
        resolveConflicts: suspend (List<String>) -> Map<String, ConflictChoice> = { emptyMap() }
    ): SyncOutcome = withContext(Dispatchers.IO) {
        provider.checkAuth()
        onProgress(SyncProgress(SyncProgress.Phase.SCANNING))

        val local = scanLocal(localRoot)
        val remote = flattenRemote(provider).associateBy { it.path }
        val working = SyncSnapshot(snapshot.entries.toMutableMap())

        // ---------- 生成动作计划 ----------
        val uploads = mutableListOf<SyncAction.Upload>()
        val downloads = mutableListOf<SyncAction.Download>()
        val deleteRemotes = mutableListOf<SyncAction.DeleteRemote>()
        val deleteLocals = mutableListOf<SyncAction.DeleteLocal>()
        val conflicts = mutableListOf<SyncAction.Conflict>()
        val relPaths = (local.keys + remote.keys).distinct().sorted()

        relPaths.forEach { rel ->
            if (isHidden(rel)) return@forEach
            val l = local[rel]
            val r = remote[rel]
            val s = snapshot.get(rel)
            when {
                // 仅本地存在:快照中也有 → 云端在上次同步后被删,传播删除到本地;否则本地新增
                l != null && r == null ->
                    if (s != null) deleteLocals.add(SyncAction.DeleteLocal(rel))
                    else uploads.add(SyncAction.Upload(rel))
                // 仅云端存在:快照中也有 → 本地在上次同步后被删,传播删除到云端;否则云端新增
                l == null && r != null ->
                    if (s != null) deleteRemotes.add(SyncAction.DeleteRemote(rel))
                    else downloads.add(SyncAction.Download(rel))
                else -> {
                    val lm = l!!.mtime
                    val rm = r!!.mtime
                    val localChanged = s == null || lm != s.localMtime
                    val remoteChanged = s == null || rm != s.remoteMtime
                    when {
                        // 首次同步(无快照)且大小一致:视为同一内容,直接纳入快照
                        s == null && l.size == r.size ->
                            working.put(rel, SnapshotEntry(lm, rm, l.size))
                        !localChanged && !remoteChanged -> Unit
                        localChanged && !remoteChanged -> uploads.add(SyncAction.Upload(rel))
                        !localChanged && remoteChanged -> downloads.add(SyncAction.Download(rel))
                        else -> conflicts.add(SyncAction.Conflict(rel))
                    }
                }
            }
        }

        val total = uploads.size + downloads.size + deleteRemotes.size +
            deleteLocals.size + conflicts.size
        var done = 0
        var uploaded = 0
        var downloaded = 0
        var deleted = 0
        var failed = 0
        var skipped = 0
        val conflictPaths = conflicts.map { it.relPath }
        val failures = mutableListOf<String>()

        fun fail(rel: String, e: Throwable?) {
            failed++
            failures += "$rel: ${e?.message ?: "未知错误"}"
        }

        fun progress(currentFile: String) = SyncProgress(
            phase = SyncProgress.Phase.RUNNING,
            currentFile = currentFile,
            done = done, total = total,
            uploaded = uploaded, downloaded = downloaded,
            deleted = deleted, failed = failed
        )

        // ---------- 冲突预决策(ASK 一次性收集,弹窗逐个选择) ----------
        val conflictChoices: Map<String, ConflictChoice> = when {
            conflicts.isEmpty() -> emptyMap()
            policy == SyncConflictPolicy.ASK ->
                runCatching { resolveConflicts(conflictPaths) }.getOrDefault(emptyMap())
            else -> emptyMap() // NEWER_WINS/KEEP_BOTH 在执行阶段按策略就地决定
        }

        // ---------- 执行:下载 → 上传 → 删除 → 冲突 ----------

        // 下载(云端 → 本地)
        downloads.sortedBy { it.relPath }.forEach { action ->
            onProgress(progress("↓ ${action.relPath}"))
            val r = remote[action.relPath]!!
            val dest = File(localRoot, action.relPath)
            provider.download(action.relPath, dest)
                .onSuccess {
                    downloaded++
                    working.put(
                        action.relPath,
                        SnapshotEntry(dest.lastModified() / 1000, r.mtime, r.size)
                    )
                }
                .onFailure { fail(action.relPath, it) }
            done++
        }
        // 上传(本地 → 云端)
        uploads.sortedBy { it.relPath }.forEach { action ->
            onProgress(progress("↑ ${action.relPath}"))
            val l = local[action.relPath]!!
            val src = File(localRoot, action.relPath)
            provider.upload(action.relPath, src)
                .onSuccess { rm ->
                    uploaded++
                    working.put(action.relPath, SnapshotEntry(l.mtime, rm, l.size))
                }
                .onFailure { fail(action.relPath, it) }
            done++
        }
        // 传播删除
        deleteRemotes.sortedBy { it.relPath }.forEach { action ->
            onProgress(progress("✕ ${action.relPath}"))
            provider.delete(action.relPath)
                .onSuccess {
                    deleted++
                    working.remove(action.relPath)
                }
                .onFailure { fail(action.relPath, it) }
            done++
        }
        deleteLocals.sortedBy { it.relPath }.forEach { action ->
            onProgress(progress("✕ ${action.relPath}"))
            val f = File(localRoot, action.relPath)
            val ok = moveToTrash(f, trashDir)
            if (ok) {
                deleted++
                working.remove(action.relPath)
            } else fail(action.relPath, CloudException("本地文件移入回收站失败"))
            done++
        }
        // 冲突
        conflicts.sortedBy { it.relPath }.forEach { action ->
            onProgress(progress("≠ ${action.relPath}"))
            val rel = action.relPath
            val l = local[rel]!!
            val r = remote[rel]!!
            val choice = when (policy) {
                SyncConflictPolicy.NEWER_WINS ->
                    if (l.mtime >= r.mtime) ConflictChoice.USE_LOCAL else ConflictChoice.USE_REMOTE
                // KEEP_BOTH 走 SKIP 分支的"保留双方"逻辑
                SyncConflictPolicy.KEEP_BOTH -> ConflictChoice.SKIP
                SyncConflictPolicy.ASK -> conflictChoices[rel] ?: ConflictChoice.SKIP
            }
            when (choice) {
                ConflictChoice.USE_LOCAL -> {
                    provider.upload(rel, File(localRoot, rel))
                        .onSuccess { rm ->
                            uploaded++
                            working.put(rel, SnapshotEntry(l.mtime, rm, l.size))
                        }
                        .onFailure { fail(rel, it) }
                }
                ConflictChoice.USE_REMOTE -> {
                    val dest = File(localRoot, rel)
                    provider.download(rel, dest)
                        .onSuccess {
                            downloaded++
                            working.put(rel, SnapshotEntry(dest.lastModified() / 1000, r.mtime, r.size))
                        }
                        .onFailure { fail(rel, it) }
                }
                ConflictChoice.SKIP -> {
                    if (policy == SyncConflictPolicy.KEEP_BOTH) {
                        // 保留双方:云端版本以冲突副本名下载到本地,本地版本覆盖上传
                        val copy = conflictCopyFile(localRoot, rel)
                        val dl = provider.download(rel, copy)
                        val up = provider.upload(rel, File(localRoot, rel)).map { rm ->
                            working.put(rel, SnapshotEntry(l.mtime, rm, l.size))
                        }
                        if (dl.isSuccess && up.isSuccess) {
                            // 副本不写快照:下次同步作为新文件上传,云端同时保留两个版本
                            uploaded++
                            downloaded++
                        } else {
                            fail(rel, dl.exceptionOrNull() ?: up.exceptionOrNull())
                        }
                    } else {
                        // 用户选择跳过:不改快照,下次同步会再次询问
                        skipped++
                    }
                }
            }
            done++
        }

        onProgress(
            SyncProgress(
                phase = SyncProgress.Phase.DONE, done = total, total = total,
                uploaded = uploaded, downloaded = downloaded,
                deleted = deleted, failed = failed
            )
        )
        SyncOutcome(
            SyncResult(
                uploaded = uploaded, downloaded = downloaded,
                deleted = deleted, skipped = skipped, failed = failed,
                conflicts = conflictPaths,
                failures = failures
            ),
            working
        )
    }

    /** 冲突副本命名:名称 (冲突-20260830-1530).扩展名 */
    private fun conflictCopyFile(localRoot: File, rel: String): File {
        val dir = File(localRoot, rel).parentFile ?: localRoot
        val name = rel.substringAfterLast('/')
        val base = name.substringBeforeLast('.', missingDelimiterValue = name)
        val ext = name.substringAfterLast('.', "").takeIf { name.contains('.') }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val copyName = if (ext.isNullOrEmpty()) "$base (冲突-$stamp)" else "$base (冲突-$stamp).$ext"
        return File(dir, copyName)
    }

    private fun relOf(action: SyncAction): String = when (action) {
        is SyncAction.Upload -> action.relPath
        is SyncAction.Download -> action.relPath
        is SyncAction.DeleteRemote -> action.relPath
        is SyncAction.DeleteLocal -> action.relPath
        is SyncAction.Conflict -> action.relPath
    }

    /** 本地删除:移入回收站目录(同挂载点 rename),无回收站时物理删除 */
    private fun moveToTrash(file: File, trashDir: File?): Boolean {
        if (!file.exists()) return true
        val dir = trashDir
        return if (dir != null) {
            dir.mkdirs()
            val target = File(dir, file.name)
            file.renameTo(target)
        } else {
            file.deleteRecursively()
        }
    }

    /** 递归扫描本地工作区(跳过点前缀隐藏项/回收站/资源缓存) */
    internal fun scanLocal(root: File): Map<String, LocalFile> {
        val out = mutableMapOf<String, LocalFile>()
        if (!root.isDirectory) return out
        root.walkTopDown().forEach { f ->
            if (f.isDirectory) return@forEach
            val rel = f.toRelativeString(root).replace('\\', '/')
            if (isHidden(rel)) return@forEach
            out[rel] = LocalFile(mtime = f.lastModified() / 1000, size = f.length())
        }
        return out
    }

    /** 递归拉平云端目录(深度保护 20 层) */
    private suspend fun flattenRemote(
        provider: CloudProvider,
        dir: String = "",
        depth: Int = 0
    ): List<CloudFile> {
        if (depth > MAX_DEPTH) return emptyList()
        val result = mutableListOf<CloudFile>()
        provider.list(dir).getOrThrow().forEach { cf ->
            if (isHidden(cf.path)) return@forEach
            if (cf.isDir) result += flattenRemote(provider, cf.path, depth + 1)
            else result += cf
        }
        return result
    }

    companion object {
        const val MAX_DEPTH = 20

        /** 任一路径段以点开头视为隐藏(回收站/资源缓存等不参与同步) */
        fun isHidden(relPath: String): Boolean =
            relPath.split('/').any { it.startsWith(".") }
    }
}

/** 本地文件信息(同步扫描用) */
data class LocalFile(
    /** mtime epoch 秒 */
    val mtime: Long,
    val size: Long
)
