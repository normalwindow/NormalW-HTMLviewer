package xyz.normalwindow.htmlviewer.ui.share

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.normalwindow.htmlviewer.data.file.FileRepository
import xyz.normalwindow.htmlviewer.data.file.FileRootProvider
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import xyz.normalwindow.htmlviewer.data.share.IncomingItem
import xyz.normalwindow.htmlviewer.data.share.IncomingPayload
import xyz.normalwindow.htmlviewer.data.share.IncomingSaveFormat
import xyz.normalwindow.htmlviewer.data.share.IncomingShareRepository
import xyz.normalwindow.htmlviewer.data.share.IncomingShareStore
import java.io.File
import javax.inject.Inject

/** 导入对话框状态 */
data class ShareImportUiState(
    /** 是否正在暂存(读取 uri → 缓存副本) */
    val staging: Boolean = false,
    /** 暂存结果(空表示读取失败) */
    val items: List<IncomingItem> = emptyList(),
    /** 首个条目的文件名(不含扩展名),其余条目沿用同一格式 */
    val baseName: String = "",
    val format: IncomingSaveFormat = IncomingSaveFormat.ORIGINAL,
    val encoding: String = TextEncoding.UTF_8,
    /** 保存位置:null 表示尚未确定 */
    val targetDir: File? = null,
    val saving: Boolean = false,
    /** 上一次操作的错误提示(null = 无错误) */
    val error: String? = null,
    /** 暂存阶段失败的具体原因(如 content resolver 异常详情) */
    val stageError: String? = null
) {
    /** 全部条目均可作为文本处理时才允许转 HTML/文本 */
    val allText: Boolean get() = items.isNotEmpty() && items.all { it.isText }

    /** 多文件时只有"原样保存"语义明确(各自保留扩展名) */
    val multiple: Boolean get() = items.size > 1

    val canSave: Boolean
        get() = !saving && !staging && items.isNotEmpty() && targetDir != null &&
            baseName.isNotBlank() && (format == IncomingSaveFormat.ORIGINAL || allText)
}

/**
 * 分享导入流程:暂存 → 用户选择格式/编码/位置 → 落盘到工作区。
 *
 * 目标目录默认取"当前浏览目录",回退到工作区根目录,用户也可通过 SAF 另选文件夹。
 */
@HiltViewModel
class ShareImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shareStore: IncomingShareStore,
    private val shareRepository: IncomingShareRepository,
    private val fileRepository: FileRepository,
    private val rootProvider: FileRootProvider
) : ViewModel() {

    private val _state = MutableStateFlow(ShareImportUiState())
    val state: StateFlow<ShareImportUiState> = _state.asStateFlow()

    /** 暂存完成后回调(用于把对话框切到"已就绪") */
    private var staged: List<IncomingItem> = emptyList()

    fun start(payload: IncomingPayload) {
        viewModelScope.launch {
            _state.value = ShareImportUiState(staging = true)
            val result = shareRepository.stage(payload)
            val items = result.items
            staged = items
            if (items.isEmpty()) {
                // 展示真实失败原因(如读取被拒/文件不存在),而不是笼统的"0 个文件"
                _state.update {
                    it.copy(staging = false, stageError = result.error ?: "stage_failed")
                }
                return@launch
            }
            val first = items.first()
            // 默认名取源文件名去掉扩展名;转 HTML/文本时扩展名由保存格式决定
            val suggested = first.displayName.substringBeforeLast('.', first.displayName)
            // 默认位置:当前浏览目录 → 工作区根目录
            val defaultDir = shareStore.lastBrowsedDir.value
                ?.takeIf { it.isDirectory }
                ?: fileRepository.ensureRoot(rootProvider.defaultRoot)
            _state.update {
                it.copy(
                    staging = false,
                    items = items,
                    baseName = suggested,
                    targetDir = defaultDir,
                    // 二进制文件强制回落到原样保存
                    format = if (items.all { i -> i.isText }) it.format else IncomingSaveFormat.ORIGINAL
                )
            }
        }
    }

    fun setBaseName(name: String) = _state.update { it.copy(baseName = name, error = null) }

    fun setFormat(format: IncomingSaveFormat) {
        _state.update { s ->
            if (format != IncomingSaveFormat.ORIGINAL && !s.allText) s
            else s.copy(format = format, error = null)
        }
    }

    fun setEncoding(encoding: String) = _state.update { it.copy(encoding = encoding, error = null) }

    /** 用户通过 SAF 选定目录后写入(转换为应用内可访问路径) */
    fun setTargetDir(dir: File) = _state.update { it.copy(targetDir = dir, error = null) }

    /** 用户通过 SAF 选定目录树 uri 后调用 */
    fun setTargetDirFromUri(uri: Uri) {
        viewModelScope.launch {
            val dir = shareRepository.resolveTreeUriToDir(uri)
            if (dir != null) {
                _state.update { it.copy(targetDir = dir, error = null) }
            } else {
                // 非应用可访问路径(如其它应用私有目录)时保持原选择并提示
                _state.update { it.copy(error = "no_target_dir") }
            }
        }
    }

    /** 保存全部条目;成功返回保存数量,由 UI 决定后续跳转 */
    fun save(onDone: (savedCount: Int, firstPath: String?) -> Unit) {
        val s = _state.value
        val dir = s.targetDir ?: run {
            _state.update { it.copy(error = "no_target_dir") }
            return
        }
        if (!s.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            var ok = 0
            var firstPath: String? = null
            s.items.forEachIndexed { index, item ->
                // 多文件时逐个追加序号,避免同名互相覆盖;扩展名由格式决定
                val name = if (s.items.size == 1) s.baseName else "${s.baseName}_${index + 1}"
                val result = shareRepository.save(
                    item = item,
                    destDir = dir,
                    format = s.format,
                    baseName = name,
                    encoding = s.encoding
                )
                result.onSuccess {
                    ok++
                    if (firstPath == null) firstPath = it.absolutePath
                }
            }
            shareRepository.clearStaged(s.items)
            _state.update { it.copy(saving = false) }
            if (ok > 0) {
                shareStore.markSaved()
                shareStore.consume()
            } else {
                _state.update { it.copy(error = "save_failed") }
            }
            onDone(ok, firstPath)
        }
    }

    /** 用户取消:清理缓存副本并消费掉待处理负载 */
    fun cancel() {
        val items = staged
        viewModelScope.launch {
            shareRepository.clearStaged(items)
            shareStore.consume()
        }
    }

    /** 暂存失败时的关闭(无缓存可清理) */
    fun dismissError() {
        shareStore.consume()
    }
}
