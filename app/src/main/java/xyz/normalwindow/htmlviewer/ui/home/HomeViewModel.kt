package xyz.normalwindow.htmlviewer.ui.home

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.normalwindow.htmlviewer.data.db.FileMetaEntity
import xyz.normalwindow.htmlviewer.data.db.FavoriteGroupEntity
import xyz.normalwindow.htmlviewer.data.debug.AppLog
import xyz.normalwindow.htmlviewer.data.file.FileItem
import xyz.normalwindow.htmlviewer.data.file.FileRepository
import xyz.normalwindow.htmlviewer.data.file.FileRootProvider
import xyz.normalwindow.htmlviewer.data.file.TrashEntry
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import xyz.normalwindow.htmlviewer.data.template.TemplateRepository
import java.io.File
import javax.inject.Inject

enum class HomeTab { FILES, RECENT, FAVORITES, SETTINGS }

enum class ViewMode { LIST, GRID }

enum class BatchOp { MOVE, COPY }

/** Snackbar 语义事件,文案由 UI 层按语言资源映射 */
enum class SnackKind {
    CREATED_FILE, CREATED_DIR, DELETED, UNDO_DELETED, RENAMED, MOVED, COPIED,
    FAV_ADDED, FAV_REMOVED, SHARED, ERROR_CREATE, ERROR_RENAME, ERROR_DELETE,
    ERROR_MOVE, ERROR_COPY, ERROR_IO, TRASH_EMPTY, GROUP_CREATED, GROUP_DELETED
}

sealed interface HomeEvent {
    /** 打开浏览器预览(单击文件默认动作) */
    data class OpenBrowser(val path: String, val name: String) : HomeEvent
    data class OpenEditor(val path: String, val name: String) : HomeEvent
    data class Snackbar(val kind: SnackKind, val count: Int = 0) : HomeEvent
}

data class HomeUiState(
    val tab: HomeTab = HomeTab.FILES,
    val currentDir: File? = null,
    val items: List<FileItem> = emptyList(),
    val isRoot: Boolean = true,
    val query: String = "",
    val loading: Boolean = false,
    /** 下拉刷新指示 */
    val refreshing: Boolean = false,
    val selection: Set<String> = emptySet(),
    val viewMode: ViewMode = ViewMode.LIST,
    val showSearch: Boolean = false,
    val recent: List<FileMetaEntity> = emptyList(),
    val favorites: List<FileMetaEntity> = emptyList(),
    val groups: List<FavoriteGroupEntity> = emptyList(),
    /** 收藏页当前筛选的分组(null = 全部) */
    val selectedGroupId: Long? = null,
    /** 正在选择批量操作的目标目录 */
    val batchMode: BatchOp? = null,
    /** 批量操作开始时所在的目录(操作完成后返回刷新) */
    val batchSourceDir: File? = null,
    val templates: List<xyz.normalwindow.htmlviewer.data.template.TemplateInfo> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileRepository: FileRepository,
    private val settingsRepository: SettingsRepository,
    private val templateRepository: TemplateRepository,
    private val rootProvider: FileRootProvider
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 撤销删除栈:<条目, 入栈时间> */
    private val trashStack = ArrayDeque<Pair<TrashEntry, Long>>()

    init {
        viewModelScope.launch {
            val root = fileRepository.ensureRoot(rootProvider.defaultRoot)
            _state.update { it.copy(currentDir = root, isRoot = true) }
            loadDir(root)
        }
        viewModelScope.launch {
            // 进程重启后撤销栈已失效,清理上次残留的回收站(避免外部存储空间被占)
            fileRepository.clearTrash(rootProvider.trashDir)
        }
        viewModelScope.launch {
            fileRepository.observeRecent(30).collect { meta ->
                _state.update { it.copy(recent = meta) }
            }
        }
        viewModelScope.launch {
            fileRepository.observeFavorites().collect { meta ->
                _state.update { it.copy(favorites = meta) }
            }
        }
        viewModelScope.launch {
            fileRepository.observeGroups().collect { groups ->
                _state.update { it.copy(groups = groups) }
            }
        }
        _state.update { it.copy(templates = templateRepository.list()) }
        viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            _state.update {
                it.copy(viewMode = if (prefs.gridView) ViewMode.GRID else ViewMode.LIST)
            }
        }
    }

    // ---------- 导航与浏览 ----------

    fun selectTab(tab: HomeTab) {
        _state.update {
            it.copy(tab = tab, selection = emptySet(), batchMode = null, batchSourceDir = null)
        }
    }

    fun refresh() {
        _state.value.currentDir?.let { dir ->
            // 发起时快照批量状态,避免 delay 期间状态变化导致刷新行为漂移
            val keepContext = _state.value.batchMode != null
            viewModelScope.launch {
                _state.update { it.copy(refreshing = true) }
                // 短暂延迟让下拉指示器可见(本地扫描很快)
                delay(400)
                // 批量选目标时刷新保留选中项与批量上下文
                if (keepContext) loadDirKeepContext(dir) else loadDir(dir)
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun enterDir(item: FileItem) {
        val dir = File(item.path)
        _state.update { it.copy(selection = emptySet(), batchMode = null, batchSourceDir = null) }
        loadDir(dir)
    }

    fun goUp() {
        val parent = _state.value.currentDir?.parentFile ?: return
        loadDir(parent)
    }

    /** 批量移动/复制:进入目标目录(保留选中项与批量上下文) */
    fun enterDirInBatch(item: FileItem) {
        loadDirKeepContext(File(item.path))
    }

    /** 批量移动/复制:返回上级目录(保留选中项与批量上下文) */
    fun goUpInBatch() {
        val parent = _state.value.currentDir?.parentFile ?: return
        loadDirKeepContext(parent)
    }

    /** 加载目录但保留 selection/batchMode(供批量目标导航使用) */
    private fun loadDirKeepContext(dir: File) {
        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, currentDir = dir, isRoot = dir == rootProvider.defaultRoot)
            }
            val items = fileRepository.list(dir)
            _state.update { it.copy(items = items, loading = false, query = "") }
        }
    }

    /** 打开文件(单击默认动作):按设置决定浏览器预览或编辑器 */
    fun openItem(item: FileItem) {
        if (item.isDirectory) {
            enterDir(item)
        } else {
            viewModelScope.launch {
                fileRepository.touchOpened(item.path, item.encoding, item.lineCount, null)
                val prefs = settingsRepository.preferences.first()
                _events.send(
                    if (prefs.clickOpensPreview) HomeEvent.OpenBrowser(item.path, item.name)
                    else HomeEvent.OpenEditor(item.path, item.name)
                )
            }
        }
    }

    /** 直接用编辑器打开文件 */
    fun openEditor(item: FileItem) {
        if (item.isDirectory) return
        viewModelScope.launch {
            fileRepository.touchOpened(item.path, item.encoding, item.lineCount, null)
            _events.send(HomeEvent.OpenEditor(item.path, item.name))
        }
    }

    private fun loadDir(dir: File) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, currentDir = dir, isRoot = dir == rootProvider.defaultRoot) }
            val items = fileRepository.list(dir)
            _state.update {
                it.copy(items = items, loading = false, selection = emptySet(), query = "")
            }
        }
    }

    // ---------- 搜索 / 视图 ----------

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setViewMode(mode: ViewMode) {
        _state.update { it.copy(viewMode = mode) }
        viewModelScope.launch { settingsRepository.setGridView(mode == ViewMode.GRID) }
    }

    fun setSearchVisible(visible: Boolean) = _state.update { it.copy(showSearch = visible) }

    /** 当前过滤后的文件列表 */
    val filteredItems: List<FileItem>
        get() {
            val q = _state.value.query.trim().lowercase()
            return if (q.isEmpty()) _state.value.items
            else _state.value.items.filter { it.name.lowercase().contains(q) }
        }

    // ---------- 多选 ----------

    fun toggleSelect(path: String) {
        _state.update { s ->
            val sel = s.selection.toMutableSet()
            if (!sel.add(path)) sel.remove(path)
            s.copy(selection = sel)
        }
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // ---------- 新建 / 重命名 ----------

    fun createHtmlFile(baseName: String, templateFileName: String? = null) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            val content = templateFileName?.let { templateRepository.read(it) } ?: ""
            val result = fileRepository.createHtmlFile(dir, baseName, content ?: "")
            result.onSuccess {
                _events.send(HomeEvent.Snackbar(SnackKind.CREATED_FILE))
                loadDir(dir)
            }.onFailure {
                _events.send(HomeEvent.Snackbar(SnackKind.ERROR_CREATE))
            }
        }
    }

    fun createDirectory(name: String) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            fileRepository.createDirectory(dir, name)
                .onSuccess {
                    _events.send(HomeEvent.Snackbar(SnackKind.CREATED_DIR))
                    loadDir(dir)
                }.onFailure { _events.send(HomeEvent.Snackbar(SnackKind.ERROR_CREATE)) }
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            val dir = _state.value.currentDir
            fileRepository.rename(File(path), newName)
                .onSuccess {
                    _events.send(HomeEvent.Snackbar(SnackKind.RENAMED))
                    dir?.let { loadDir(it) }
                }.onFailure { _events.send(HomeEvent.Snackbar(SnackKind.ERROR_RENAME)) }
        }
    }

    // ---------- 删除与撤销 ----------

    fun deleteSelected() {
        val dir = _state.value.currentDir ?: return
        val selected = _state.value.items.filter { it.path in _state.value.selection }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entries = mutableListOf<TrashEntry>()
            var cleanedMeta = 0
            selected.forEach { item ->
                val file = File(item.path)
                if (file.exists()) {
                    fileRepository.moveToTrash(file, rootProvider.trashDir)
                        .onSuccess { entries.add(it) }
                } else {
                    // 列表中的幽灵条目(文件已被外部删除/移动):仅清理元数据,
                    // 避免最近历史/收藏残留无法删除(不可撤销,不计入回收站)
                    if (fileRepository.deleteMeta(item.path).isSuccess) cleanedMeta++
                }
            }
            val okCount = entries.size + cleanedMeta
            AppLog.d("Delete", "删除请求 ${selected.size} 项, 成功 $okCount 项")
            trashStack.addAll(entries.map { it to now })
            clearSelection()
            _events.send(
                if (okCount == 0) HomeEvent.Snackbar(SnackKind.ERROR_DELETE)
                else HomeEvent.Snackbar(SnackKind.DELETED, okCount)
            )
            loadDir(dir)
            scheduleTrashCleanup()
        }
    }

    /**
     * 单文件删除(复用回收站撤销机制)。
     * 不依赖当前目录列表:最近/收藏页中的文件不在当前目录,原实现会因选中项
     * 过滤为空而报"操作失败,请重试"。文件已不存在时仅清理元数据残留。
     */
    fun deleteOne(path: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val file = File(path)
            var movedToTrash = false
            val ok = if (file.exists()) {
                fileRepository.moveToTrash(file, rootProvider.trashDir)
                    .onSuccess {
                        movedToTrash = true
                        trashStack.addLast(it to now)
                    }
                    .isSuccess
            } else {
                // 文件已不存在(外部删除/移动):清理元数据,最近历史/收藏条目随之消失
                fileRepository.deleteMeta(path).isSuccess
            }
            // 刷新当前目录列表(元数据变更后最近/收藏由 Flow 自动更新)
            _state.value.currentDir?.let { loadDir(it) }
            // 仅实际移入回收站时调度延迟清理,避免单删产生回收站垃圾累积
            if (movedToTrash) scheduleTrashCleanup()
            _events.send(
                if (ok) HomeEvent.Snackbar(SnackKind.DELETED, 1)
                else HomeEvent.Snackbar(SnackKind.ERROR_DELETE)
            )
        }
    }

    /** 目标文件已不存在(最近/收藏列表中的失效项) */
    fun onMissingFile() {
        viewModelScope.launch { _events.send(HomeEvent.Snackbar(SnackKind.ERROR_IO)) }
    }

    fun undoDelete() {
        viewModelScope.launch {
            val pair = trashStack.removeLastOrNull() ?: run {
                _events.send(HomeEvent.Snackbar(SnackKind.TRASH_EMPTY))
                return@launch
            }
            val result = fileRepository.restoreFromTrash(pair.first)
            _events.send(
                if (result.isSuccess) HomeEvent.Snackbar(SnackKind.UNDO_DELETED)
                else HomeEvent.Snackbar(SnackKind.ERROR_IO)
            )
            refresh()
        }
    }

    /** 延迟清理回收站:仅清理已超过 5 秒的条目(基于入栈时间,而非文件 mtime) */
    private fun scheduleTrashCleanup() {
        viewModelScope.launch {
            delay(TRASH_TTL_MS + 1_000)
            val cutoff = System.currentTimeMillis() - TRASH_TTL_MS
            val expired = trashStack.filter { it.second < cutoff }
            trashStack.removeAll(expired.toSet())
            expired.forEach { (entry, _) ->
                if (entry.trashFile.exists()) entry.trashFile.deleteRecursively()
            }
        }
    }

    // ---------- 批量移动 / 复制 ----------

    fun startBatch(op: BatchOp) {
        if (_state.value.selection.isEmpty()) return
        _state.update {
            it.copy(batchMode = op, batchSourceDir = _state.value.currentDir)
        }
    }

    fun cancelBatch() = _state.update { it.copy(batchMode = null, batchSourceDir = null) }

    fun confirmBatchTo(dir: File) {
        val op = _state.value.batchMode ?: return
        // 按选中路径直接构造,不依赖当前目录列表(批量导航后列表已是目标目录)
        val selected = _state.value.selection.map { File(it) }
        val origin = _state.value.batchSourceDir ?: _state.value.currentDir ?: return
        viewModelScope.launch {
            var ok = 0
            selected.forEach { file ->
                val result = when (op) {
                    BatchOp.MOVE -> fileRepository.move(file, dir)
                    BatchOp.COPY -> fileRepository.copy(file, dir)
                }
                if (result.isSuccess) ok++
            }
            _state.update { it.copy(batchMode = null, selection = emptySet(), batchSourceDir = null) }
            _events.send(
                if (ok == 0) {
                    HomeEvent.Snackbar(if (op == BatchOp.MOVE) SnackKind.ERROR_MOVE else SnackKind.ERROR_COPY)
                } else {
                    HomeEvent.Snackbar(if (op == BatchOp.MOVE) SnackKind.MOVED else SnackKind.COPIED, ok)
                }
            )
            loadDir(origin)
        }
    }

    // ---------- 收藏 / 分享 ----------

    fun toggleFavorite(path: String) {
        viewModelScope.launch {
            val fav = fileRepository.toggleFavorite(path)
            _events.send(HomeEvent.Snackbar(if (fav) SnackKind.FAV_ADDED else SnackKind.FAV_REMOVED))
        }
    }

    // ---------- 收藏分组 ----------

    fun selectGroup(groupId: Long?) = _state.update { it.copy(selectedGroupId = groupId) }

    fun createGroup(name: String) {
        viewModelScope.launch {
            fileRepository.createGroup(name)
            _events.send(HomeEvent.Snackbar(SnackKind.GROUP_CREATED))
        }
    }

    fun renameGroup(id: Long, name: String) {
        viewModelScope.launch {
            fileRepository.renameGroup(id, name)
        }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch {
            fileRepository.deleteGroup(id)
            if (_state.value.selectedGroupId == id) selectGroup(null)
            _events.send(HomeEvent.Snackbar(SnackKind.GROUP_DELETED))
        }
    }

    /** 文件加入分组(自动转为收藏) */
    fun addToGroup(path: String, groupId: Long) {
        viewModelScope.launch {
            fileRepository.ensureFavoriteInGroup(path, groupId)
            _events.send(HomeEvent.Snackbar(SnackKind.FAV_ADDED))
        }
    }

    // ---------- 附加文件 ----------

    fun listAttachments(htmlPath: String): List<FileItem> =
        fileRepository.listAttachments(htmlPath).map { FileItem.of(it, null) }

    fun createAttachment(
        htmlPath: String,
        fileName: String,
        autoReference: Boolean,
        onDone: (Result<File>) -> Unit
    ) {
        viewModelScope.launch {
            val result = fileRepository.createAttachment(htmlPath, fileName, autoReference)
            onDone(result)
        }
    }

    fun deleteAttachment(path: String) {
        viewModelScope.launch {
            fileRepository.delete(File(path))
            _events.send(HomeEvent.Snackbar(SnackKind.DELETED, 1))
        }
    }

    fun share(path: String) {
        viewModelScope.launch {
            val file = File(path)
            if (!file.isFile) {
                _events.send(HomeEvent.Snackbar(SnackKind.ERROR_IO))
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { _events.send(HomeEvent.Snackbar(SnackKind.ERROR_IO)) }
        }
    }

    /** 用系统外部浏览器打开 HTML 文件(FileProvider 授权读取) */
    fun openExternalBrowser(path: String) {
        viewModelScope.launch {
            val file = File(path)
            if (!file.isFile) {
                _events.send(HomeEvent.Snackbar(SnackKind.ERROR_IO))
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { _events.send(HomeEvent.Snackbar(SnackKind.ERROR_IO)) }
        }
    }

    private companion object {
        const val TRASH_TTL_MS = 5_000L
    }
}
