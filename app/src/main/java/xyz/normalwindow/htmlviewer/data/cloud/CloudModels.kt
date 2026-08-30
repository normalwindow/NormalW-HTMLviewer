package xyz.normalwindow.htmlviewer.data.cloud

/**
 * 云盘类型:未启用 / 百度网盘 / WebDAV。
 * storageValue 持久化到 DataStore,切换云盘即切换本值(各盘凭据独立保存,切回免重新登录)。
 */
enum class CloudProviderType(val storageValue: String) {
    NONE("none"),
    BAIDU("baidu"),
    WEBDAV("webdav");

    companion object {
        fun fromStorage(v: String?): CloudProviderType =
            entries.firstOrNull { it.storageValue == v } ?: NONE
    }
}

/** 双向同步冲突处理策略 */
enum class SyncConflictPolicy(val storageValue: String) {
    /** 每次弹窗逐个询问(默认) */
    ASK("ask"),

    /** 修改时间新者胜 */
    NEWER_WINS("newer_wins"),

    /** 保留双方:云端版本以"名称 (冲突-时间戳).扩展名"保存到本地 */
    KEEP_BOTH("keep_both");

    companion object {
        fun fromStorage(v: String?): SyncConflictPolicy =
            entries.firstOrNull { it.storageValue == v } ?: ASK
    }
}

/** 云端文件条目(路径为相对远端根目录的相对路径,统一以 / 分隔,如 "docs/a.html") */
data class CloudFile(
    val path: String,
    val name: String,
    val isDir: Boolean,
    /** 字节数(目录恒为 0) */
    val size: Long,
    /** 修改时间 epoch 秒 */
    val mtime: Long,
    /** 平台文件 id(百度 fs_id,下载走 filemetas→dlink 必需;WebDAV 等无此概念恒为 0) */
    val fsId: Long = 0
)

/** 单个同步动作 */
sealed interface SyncAction {
    /** 本地 → 云端 */
    data class Upload(val relPath: String) : SyncAction

    /** 云端 → 本地 */
    data class Download(val relPath: String) : SyncAction

    /** 快照中存在但本地已消失 → 删除云端(本地删除传播) */
    data class DeleteRemote(val relPath: String) : SyncAction

    /** 快照中存在但云端已消失 → 删除本地(云端删除传播,本地移入回收站目录) */
    data class DeleteLocal(val relPath: String) : SyncAction

    /** 两侧都有改动(或首同步内容不一致) → 冲突待处理 */
    data class Conflict(val relPath: String) : SyncAction
}

/** 同步进度(执行阶段回调,驱动进度对话框) */
data class SyncProgress(
    val phase: Phase,
    /** 当前处理的相对路径 */
    val currentFile: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0
) {
    enum class Phase { SCANNING, RUNNING, DONE }
}

/** 同步结果统计 */
data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    /** 跳过(冲突选了跳过) */
    val skipped: Int = 0,
    val failed: Int = 0,
    /** 发生冲突的相对路径列表 */
    val conflicts: List<String> = emptyList(),
    /** 失败明细:"文件: 原因"(结果对话框展示,便于定位) */
    val failures: List<String> = emptyList()
)

/** 云端操作业务错误(带平台错误码,如百度 errno) */
class CloudException(message: String, val code: Int = 0) : Exception(message)

/** 冲突处理决定(冲突对话框回传给同步引擎) */
enum class ConflictChoice { USE_LOCAL, USE_REMOTE, SKIP }

/** 同步流程 UI 状态(设置页/主页共用) */
sealed interface SyncUiState {
    data object Idle : SyncUiState
    data class Running(val progress: SyncProgress) : SyncUiState
    data class Done(val result: SyncResult) : SyncUiState
    data class Failed(val message: String) : SyncUiState
}
