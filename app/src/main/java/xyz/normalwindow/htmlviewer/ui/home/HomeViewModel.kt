package xyz.normalwindow.htmlviewer.ui.home

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.normalwindow.htmlviewer.data.db.FileMetaEntity
import xyz.normalwindow.htmlviewer.data.db.FavoriteGroupEntity
import xyz.normalwindow.htmlviewer.data.cloud.CloudFile
import xyz.normalwindow.htmlviewer.data.cloud.CloudManager
import xyz.normalwindow.htmlviewer.data.cloud.CloudProviderType
import xyz.normalwindow.htmlviewer.data.cloud.CloudSyncEngine
import xyz.normalwindow.htmlviewer.data.cloud.SyncSnapshotStore
import xyz.normalwindow.htmlviewer.data.cloud.SyncUiState
import xyz.normalwindow.htmlviewer.data.debug.AppLog
import xyz.normalwindow.htmlviewer.data.file.FileItem
import xyz.normalwindow.htmlviewer.data.file.FileRepository
import xyz.normalwindow.htmlviewer.data.file.FileRootProvider
import xyz.normalwindow.htmlviewer.data.file.TrashEntry
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import xyz.normalwindow.htmlviewer.data.settings.UserPreferences
import xyz.normalwindow.htmlviewer.data.template.TemplateRepository
import xyz.normalwindow.htmlviewer.ui.cloud.SyncController
import java.io.File
import javax.inject.Inject

enum class HomeTab { FILES, RECENT, FAVORITES, SETTINGS }

enum class ViewMode { LIST, GRID }

enum class BatchOp { MOVE, COPY }

/** 文件数据源:本地工作区 / 云端网盘(云本地切换) */
enum class DataSource { LOCAL, CLOUD }

/** 文件排序方式(名称/修改时间/大小/类型) */
enum class SortMode(val storageValue: String) {
    NAME("name"), TIME("time"), SIZE("size"), TYPE("type");

    companion object {
        fun fromStorage(v: String?): SortMode = entries.firstOrNull { it.storageValue == v } ?: NAME
    }
}

/** Snackbar 语义事件,文案由 UI 层按语言资源映射 */
enum class SnackKind {
    CREATED_FILE, CREATED_DIR, DELETED, UNDO_DELETED, RENAMED, MOVED, COPIED,
    FAV_ADDED, FAV_REMOVED, SHARED, ERROR_CREATE, ERROR_RENAME, ERROR_DELETE,
    ERROR_MOVE, ERROR_COPY, ERROR_IO, TRASH_EMPTY, GROUP_CREATED, GROUP_DELETED,
    IMPORTED, ERROR_IMPORT,
    CLOUD_DOWNLOADED, CLOUD_DELETED, ERROR_CLOUD
}

sealed interface HomeEvent {
    /** 打开浏览器预览(单击文件默认动作) */
    data class OpenBrowser(val path: String, val name: String) : HomeEvent
    data class OpenEditor(val path: String, val name: String) : HomeEvent
    data class Snackbar(val kind: SnackKind, val count: Int = 0) : HomeEvent

    /** 直接展示动态文本的提示(云端错误透传 errno 说明等;空白时 UI 显示通用文案) */
    data class SnackbarText(val message: String) : HomeEvent
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
    /** 文件排序方式与方向(主页菜单可切换,设置持久化) */
    val sortMode: SortMode = SortMode.NAME,
    val sortAscending: Boolean = true,
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
    val templates: List<xyz.normalwindow.htmlviewer.data.template.TemplateInfo> = emptyList(),
    /** 云本地切换:当前数据源 */
    val dataSource: DataSource = DataSource.LOCAL,
    /** 活动云盘类型 */
    val cloudProvider: CloudProviderType = CloudProviderType.NONE,
    /** 云端浏览:相对远端根目录的当前目录("" = 根) */
    val cloudDir: String = "",
    val cloudItems: List<CloudFile> = emptyList(),
    val cloudLoading: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileRepository: FileRepository,
    private val settingsRepository: SettingsRepository,
    private val templateRepository: TemplateRepository,
    private val rootProvider: FileRootProvider,
    private val cloudManager: CloudManager,
    private val cloudSyncEngine: CloudSyncEngine,
    syncSnapshotStore: SyncSnapshotStore
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 云同步流程(与设置页共用) */
    val sync = SyncController(
        context = context,
        scope = viewModelScope,
        settingsRepository = settingsRepository,
        fileRootProvider = rootProvider,
        cloudManager = cloudManager,
        cloudSyncEngine = cloudSyncEngine,
        syncSnapshotStore = syncSnapshotStore
    )

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
                it.copy(
                    viewMode = if (prefs.gridView) ViewMode.GRID else ViewMode.LIST,
                    sortMode = SortMode.fromStorage(prefs.sortMode),
                    sortAscending = prefs.sortAscending
                )
            }
        }
        viewModelScope.launch {
            // 活动云盘变化(设置页/本页切换)同步到浏览状态
            settingsRepository.preferences.map { it.cloudProvider }.distinctUntilChanged()
                .collect { type -> _state.update { it.copy(cloudProvider = type) } }
        }
        viewModelScope.launch {
            // 启动时自动同步(设置开启时):完成后由下方 collect 刷新两侧列表
            val prefs = settingsRepository.preferences.first()
            if (prefs.syncOnStart && prefs.cloudProvider != CloudProviderType.NONE) {
                sync.syncNow()
            }
        }
        viewModelScope.launch {
            // 百度令牌静默续期:距过期不足 7 天时启动即刷新
            // (access_token 单次有效期 30 天为平台限制,配合 10 年有效的 refresh_token 可无限续期)
            val prefs = settingsRepository.preferences.first()
            if (prefs.cloudProvider == CloudProviderType.BAIDU && !prefs.baiduAccessToken.isNullOrBlank()) {
                cloudManager.providerFromPrefs(prefs, CloudProviderType.BAIDU)?.let { provider ->
                    runCatching { provider.checkAuth() }
                }
            }
        }
        viewModelScope.launch {
            sync.syncState.collect { st ->
                if (st is SyncUiState.Done || st is SyncUiState.Failed) {
                    _state.value.currentDir?.let { loadDir(it) }
                    if (_state.value.dataSource == DataSource.CLOUD) {
                        loadCloudDir(_state.value.cloudDir)
                    }
                }
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

    /** 当前过滤 + 排序后的文件列表(目录恒在前) */
    val filteredItems: List<FileItem>
        get() {
            val s = _state.value
            val q = s.query.trim().lowercase()
            val base = if (q.isEmpty()) s.items
            else s.items.filter { it.name.lowercase().contains(q) }
            val dirFirst = base.sortedWith(compareByDescending<FileItem> { it.isDirectory })
            val comparator: Comparator<FileItem> = when (s.sortMode) {
                SortMode.NAME -> compareBy { it.name.lowercase() }
                SortMode.TIME -> compareBy { it.lastModified }
                SortMode.SIZE -> compareBy { it.size }
                SortMode.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
            }
            return if (s.sortAscending) dirFirst.sortedWith(comparator)
            else dirFirst.sortedWith(comparator.reversed())
        }

    /** 切换排序方式(持久化) */
    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
        viewModelScope.launch { settingsRepository.setSortMode(mode.storageValue) }
    }

    /** 切换升序/降序(持久化) */
    fun setSortAscending(ascending: Boolean) {
        _state.update { it.copy(sortAscending = ascending) }
        viewModelScope.launch { settingsRepository.setSortAscending(ascending) }
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

    // ---------- 云端浏览(云本地切换) ----------

    /** 切换本地/云端数据源;首次进入云端时自动加载根目录 */
    fun setDataSource(ds: DataSource) {
        _state.update {
            it.copy(
                dataSource = ds, selection = emptySet(), batchMode = null,
                batchSourceDir = null, showSearch = false, query = ""
            )
        }
        if (ds == DataSource.CLOUD) loadCloudDir(_state.value.cloudDir)
    }

    /** 云盘切换(主页快捷入口;与设置页共用持久化) */
    fun setCloudProvider(type: CloudProviderType) {
        viewModelScope.launch {
            settingsRepository.setCloudProvider(type)
            _state.update { it.copy(cloudDir = "", cloudItems = emptyList()) }
            if (_state.value.dataSource == DataSource.CLOUD) loadCloudDir("")
        }
    }

    /** 加载云端目录(相对远端根目录;"" = 根目录) */
    fun loadCloudDir(dir: String) {
        viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            val provider = cloudManager.providerFromPrefs(prefs, prefs.cloudProvider)
            if (provider == null) {
                _events.send(HomeEvent.Snackbar(SnackKind.ERROR_CLOUD))
                return@launch
            }
            _state.update { it.copy(cloudLoading = true, cloudDir = dir) }
            provider.list(dir)
                .onSuccess { items ->
                    _state.update {
                        it.copy(
                            cloudLoading = false,
                            cloudItems = items.sortedWith(
                                compareByDescending<CloudFile> { it.isDir }
                                    .thenBy { it.name.lowercase() }
                            )
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { s -> s.copy(cloudLoading = false) }
                    // 透传具体错误(含 errno 说明),便于定位权限/路径/令牌问题
                    _events.send(HomeEvent.SnackbarText(e.message ?: ""))
                }
        }
    }

    fun refreshCloud() = loadCloudDir(_state.value.cloudDir)

    /** 返回云端上级目录(根目录时不动作) */
    fun cloudGoUp() {
        val dir = _state.value.cloudDir
        if (dir.isBlank()) return
        loadCloudDir(dir.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    /**
     * 打开云端文件:下载到本地缓存(filesDir/cloud/<provider>/<相对路径>)后按设置
     * 路由到编辑器/预览;编辑器保存后由云缓存路径前缀自动上传回网盘。
     */
    fun openCloudFile(item: CloudFile) {
        if (item.isDir) {
            loadCloudDir(item.path)
            return
        }
        viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            val provider = cloudManager.providerFromPrefs(prefs, prefs.cloudProvider)
            if (provider == null) {
                _events.send(HomeEvent.Snackbar(SnackKind.ERROR_CLOUD))
                return@launch
            }
            _state.update { it.copy(cloudLoading = true) }
            val dest = cloudManager.localPathFor(prefs.cloudProvider, item.path)
            val ok = provider.download(item.path, dest).isSuccess
            _state.update { it.copy(cloudLoading = false) }
            if (!ok) {
                _events.send(HomeEvent.Snackbar(SnackKind.ERROR_CLOUD))
                return@launch
            }
            _events.send(
                if (prefs.clickOpensPreview) HomeEvent.OpenBrowser(dest.absolutePath, item.name)
                else HomeEvent.OpenEditor(dest.absolutePath, item.name)
            )
        }
    }

    /** 云端文件下载到本地工作区(保持相对路径结构,目录自动创建) */
    fun downloadCloudToLocal(item: CloudFile) {
        if (item.isDir) return
        viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            val provider = cloudManager.providerFromPrefs(prefs, prefs.cloudProvider)
            if (provider == null) {
                _events.send(HomeEvent.Snackbar(SnackKind.ERROR_CLOUD))
                return@launch
            }
            val dest = File(rootProvider.defaultRoot, item.path)
            val ok = provider.download(item.path, dest).isSuccess
            _events.send(
                if (ok) HomeEvent.Snackbar(SnackKind.CLOUD_DOWNLOADED)
                else HomeEvent.Snackbar(SnackKind.ERROR_CLOUD)
            )
            if (ok) _state.value.currentDir?.let { loadDir(it) }
        }
    }

    /** 删除云端文件/目录(本地缓存副本保留,不传播删除) */
    fun deleteCloudFile(item: CloudFile) {
        viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            val provider = cloudManager.providerFromPrefs(prefs, prefs.cloudProvider)
            if (provider == null) {
                _events.send(HomeEvent.Snackbar(SnackKind.ERROR_CLOUD))
                return@launch
            }
            provider.delete(item.path)
                .onSuccess {
                    _events.send(HomeEvent.Snackbar(SnackKind.CLOUD_DELETED))
                    loadCloudDir(_state.value.cloudDir)
                }
                .onFailure { _events.send(HomeEvent.Snackbar(SnackKind.ERROR_CLOUD)) }
        }
    }

    /** 打开云端缓存的本地文件(供编辑器"在预览中打开"等,不额外处理) */

    // ---------- 文件/文件夹导入(SAF) ----------

    /** 导入多个文件到当前目录(成功后刷新并提示数量) */
    fun importFiles(uris: List<android.net.Uri>) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                fileRepository.importFiles(context, uris, dir).getOrDefault(0)
            }
            _events.send(
                if (ok > 0) HomeEvent.Snackbar(SnackKind.IMPORTED, ok)
                else HomeEvent.Snackbar(SnackKind.ERROR_IMPORT)
            )
            loadDir(dir)
        }
    }

    /** 导入整个文件夹(递归)到当前目录 */
    fun importFolder(uri: android.net.Uri) {
        val dir = _state.value.currentDir ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                fileRepository.importFolder(context, uri, dir).getOrDefault(0)
            }
            _events.send(
                if (ok > 0) HomeEvent.Snackbar(SnackKind.IMPORTED, ok)
                else HomeEvent.Snackbar(SnackKind.ERROR_IMPORT)
            )
            loadDir(dir)
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
