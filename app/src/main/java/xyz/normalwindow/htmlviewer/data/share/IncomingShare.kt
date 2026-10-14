package xyz.normalwindow.htmlviewer.data.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 保存格式:原样 / 转为 HTML 页面 / 转为 UTF-8 纯文本 */
enum class IncomingSaveFormat {
    /** 字节级原样复制,保留原扩展名与内容(二进制安全) */
    ORIGINAL,

    /** 按检测到的编码读为文本,转义后包成带样式的 HTML 文档 */
    HTML,

    /** 按检测到的编码读为文本,统一写成 UTF-8(修正 GBK 等存量编码) */
    TEXT
}

/**
 * 外部应用分享进来的待处理文件(原始 uri,尚未落盘到工作区)。
 *
 * uri 的读取授权随接收 Activity 的 Intent 生效,因此这里只做短暂中转:
 * 进入界面后立即复制到应用缓存,后续读写全部基于本地缓存副本。
 */
data class IncomingPayload(
    val uris: List<Uri>,
    /** 与 [uris] 一一对应的 MIME(可能含 null) */
    val mimeTypes: List<String?> = emptyList()
)

/** 单个待导入条目的展示信息(名称/大小/编码探测结果等) */
data class IncomingItem(
    /** 应用缓存中的本地副本 */
    val cacheFile: File,
    /** 原始显示名(含扩展名) */
    val displayName: String,
    val sizeBytes: Long,
    /** 是否为可解码的文本(二进制文件不能转 HTML/文本) */
    val isText: Boolean,
    /** 探测到的编码名(仅文本有效) */
    val detectedEncoding: String?,
    /** 内容预览(仅文本有效,已截断) */
    val preview: String?
)

/**
 * 分享接收中转站(全局单例)。
 *
 * 由 MainActivity 在收到 ACTION_SEND / ACTION_SEND_MULTIPLE 时写入,
 * 由 AppNavHost 顶层的导入对话框消费。使用 StateFlow 而非一次性事件,
 * 保证 Activity 重建、ViewModel 晚于 Intent 创建等时序下都不丢数据。
 */
@Singleton
class IncomingShareStore @Inject constructor() {

    private val _payload = MutableStateFlow<IncomingPayload?>(null)
    val payload: StateFlow<IncomingPayload?> = _payload.asStateFlow()

    /** 成功保存的次数:主页据此刷新当前目录列表 */
    private val _savedTick = MutableStateFlow(0)
    val savedTick: StateFlow<Int> = _savedTick.asStateFlow()

    /** 主页最近浏览的目录:作为"保存位置"的默认值 */
    private val _lastBrowsedDir = MutableStateFlow<File?>(null)
    val lastBrowsedDir: StateFlow<File?> = _lastBrowsedDir.asStateFlow()

    fun submit(uris: List<Uri>, mimeTypes: List<String?>) {
        if (uris.isEmpty()) return
        _payload.value = IncomingPayload(uris, mimeTypes)
    }

    fun consume() {
        _payload.value = null
    }

    fun markSaved() {
        _savedTick.update { it + 1 }
    }

    fun setLastBrowsedDir(dir: File?) {
        _lastBrowsedDir.value = dir
    }
}
