@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package xyz.normalwindow.htmlviewer.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.cloud.CloudFile
import xyz.normalwindow.htmlviewer.data.file.FileItem
import xyz.normalwindow.htmlviewer.ui.components.EmptyState
import xyz.normalwindow.htmlviewer.ui.components.FileActionMenu
import xyz.normalwindow.htmlviewer.ui.components.FileRow
import xyz.normalwindow.htmlviewer.ui.components.SkeletonList
import xyz.normalwindow.htmlviewer.ui.components.fileTypeOf
import java.io.File
import java.util.Locale

/** 文件浏览页:目录导航 + 多选批量操作 + 搜索 + 列表/网格切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesTab(
    vm: HomeViewModel,
    onOpenBrowser: (String, String) -> Unit,
    onOpenEditor: (String, String) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val items = vm.filteredItems

    var showCreateSheet by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewDirDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var menuPath by remember { mutableStateOf<String?>(null) }
    var groupPickerPath by remember { mutableStateOf<String?>(null) }
    var attachmentPath by remember { mutableStateOf<String?>(null) }
    var pendingTemplate by remember { mutableStateOf<xyz.normalwindow.htmlviewer.data.template.TemplateInfo?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 批量操作的目标选择提示条
            AnimatedVisibility(
                visible = state.batchMode != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                state.batchMode?.let { op ->
                    BatchTargetBar(
                        op = op,
                        dirName = state.currentDir?.name ?: "",
                        selectionCount = state.selection.size,
                        canConfirm = state.currentDir?.let { dir ->
                            state.selection.none { path ->
                                val f = File(path)
                                // 不能移到文件所在目录,也不能把目录移入自身或其子目录
                                f.parentFile == dir || dir == f ||
                                    dir.path.startsWith(f.path + File.separator)
                            }
                        } == true,
                        onConfirm = { state.currentDir?.let { vm.confirmBatchTo(it) } },
                        onCancel = vm::cancelBatch
                    )
                }
            }

            // 搜索栏
            AnimatedVisibility(
                visible = state.showSearch,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                SearchBar(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    onClose = {
                        vm.setQuery("")
                        vm.setSearchVisible(false)
                    }
                )
            }

            // 内容区:云端浏览(云本地切换)/ 本地文件
            if (state.dataSource == DataSource.CLOUD) {
                CloudContent(state = state, vm = vm)
            } else when {
                state.loading -> SkeletonList(modifier = Modifier.padding(top = 8.dp))
                items.isEmpty() -> EmptyState(
                    icon = if (state.query.isNotEmpty()) Icons.Outlined.Code else Icons.Filled.FolderOpen,
                    title = stringResource(
                        if (state.query.isNotEmpty()) R.string.empty_search_title
                        else R.string.empty_dir_title
                    ),
                    hint = stringResource(
                        if (state.query.isNotEmpty()) R.string.empty_search_hint
                        else R.string.empty_dir_hint
                    )
                )
                state.viewMode == ViewMode.GRID -> FileGrid(
                    items = items,
                    selection = state.selection,
                    onItemClick = { item ->
                        when {
                            state.batchMode != null && item.isDirectory -> vm.enterDirInBatch(item)
                            state.batchMode != null -> Unit // 批量选目标中,文件不可作为目标
                            state.selection.isNotEmpty() -> vm.toggleSelect(item.path)
                            else -> vm.openItem(item)
                        }
                    },
                    onItemLongClick = { item ->
                        if (state.batchMode == null) vm.toggleSelect(item.path)
                    },
                    onFavoriteClick = { vm.toggleFavorite(it) }
                )
                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(items, key = { it.path }) { item ->
                            FileRow(
                                item = item,
                                selected = item.path in state.selection,
                                selectionMode = state.selection.isNotEmpty(),
                                subtitle = fileSubtitle(item),
                                onClick = {
                                    when {
                                        state.batchMode != null && item.isDirectory -> vm.enterDirInBatch(item)
                                        state.batchMode != null -> Unit // 批量选目标中,文件不可作为目标
                                        state.selection.isNotEmpty() -> vm.toggleSelect(item.path)
                                        else -> vm.openItem(item)
                                    }
                                },
                                onLongClick = {
                                    if (state.batchMode == null) vm.toggleSelect(item.path)
                                },
                                onFavoriteClick = { vm.toggleFavorite(item.path) },
                                onMoreClick = { menuPath = item.path },
                                menuContent = {
                                    FileActionMenu(
                                        expanded = menuPath == item.path,
                                        onDismiss = { menuPath = null },
                                        onOpen = {
                                            menuPath = null
                                            vm.openItem(item)
                                        },
                                        onEdit = {
                                            menuPath = null
                                            vm.openEditor(item)
                                        },
                                        onRename = {
                                            menuPath = null
                                            renameTarget = item
                                        },
                                        onFavorite = {
                                            menuPath = null
                                            vm.toggleFavorite(item.path)
                                        },
                                        onGroup = {
                                            menuPath = null
                                            groupPickerPath = item.path
                                        },
                                        onAttachments = {
                                            menuPath = null
                                            attachmentPath = item.path
                                        },
                                        showAttachments = !item.isDirectory && item.name.substringAfterLast('.', "")
                                            .lowercase() in setOf("html", "htm"),
                                        showExternalOpen = !item.isDirectory && item.name.substringAfterLast('.', "")
                                            .lowercase() in setOf("html", "htm"),
                                        onExternalOpen = {
                                            menuPath = null
                                            vm.openExternalBrowser(item.path)
                                        },
                                        onShare = {
                                            menuPath = null
                                            vm.share(item.path)
                                        },
                                        onDelete = {
                                            menuPath = null
                                            vm.deleteOne(item.path)
                                        },
                                        isFavorite = item.isFavorite
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // 底部多选操作栏(云端模式无多选)
        AnimatedVisibility(
            visible = state.selection.isNotEmpty() && state.batchMode == null &&
                state.dataSource == DataSource.LOCAL,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            SelectionActionBar(
                count = state.selection.size,
                onDelete = vm::deleteSelected,
                onMove = { vm.startBatch(BatchOp.MOVE) },
                onCopy = { vm.startBatch(BatchOp.COPY) },
                onFavorite = { state.selection.toList().forEach { vm.toggleFavorite(it) } },
                onShare = { state.selection.firstOrNull()?.let(vm::share) }
            )
        }

        // 新建 FAB(多选/批量/云端模式时隐藏,避免遮挡底部操作栏)
        if (state.batchMode == null && state.selection.isEmpty() &&
            state.dataSource == DataSource.LOCAL
        ) {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_new_file))
            }
        }
    }

    // 返回页面时自动刷新文件列表(编辑器/预览保存后大小/内容立即更新)
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        if (state.dataSource == DataSource.CLOUD) vm.refreshCloud()
        onPauseOrDispose { }
    }

    // 新建方式选择(底部弹层)
    if (showCreateSheet) {
        ModalBottomSheet(onDismissRequest = { showCreateSheet = false }) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_new_file)) },
                leadingContent = { Icon(Icons.Filled.Description, null) },
                modifier = Modifier.clickableItem {
                    showCreateSheet = false
                    showNewFileDialog = true
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_new_dir)) },
                leadingContent = { Icon(Icons.Filled.CreateNewFolder, null) },
                modifier = Modifier.clickableItem {
                    showCreateSheet = false
                    showNewDirDialog = true
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_from_template)) },
                leadingContent = { Icon(Icons.Outlined.Code, null) },
                modifier = Modifier.clickableItem {
                    showCreateSheet = false
                    showTemplateDialog = true
                }
            )
            Spacer(Modifier.size(24.dp))
        }
    }

    // 对话框
    if (showNewFileDialog) {
        NameInputDialog(
            title = stringResource(R.string.dialog_new_file_title),
            confirmLabel = stringResource(R.string.action_confirm),
            onConfirm = {
                vm.createHtmlFile(it, pendingTemplate?.fileName)
                pendingTemplate = null
                showNewFileDialog = false
            },
            onDismiss = {
                pendingTemplate = null
                showNewFileDialog = false
            }
        )
    }
    if (showNewDirDialog) {
        NameInputDialog(
            title = stringResource(R.string.dialog_new_dir_title),
            confirmLabel = stringResource(R.string.action_confirm),
            onConfirm = {
                vm.createDirectory(it)
                showNewDirDialog = false
            },
            onDismiss = { showNewDirDialog = false }
        )
    }
    groupPickerPath?.let { path ->
        GroupPickerDialog(
            groups = state.groups,
            onPick = { groupId ->
                groupPickerPath = null
                vm.addToGroup(path, groupId)
            },
            onDismiss = { groupPickerPath = null }
        )
    }
    attachmentPath?.let { path ->
        AttachmentDialog(
            htmlPath = path,
            vm = vm,
            onEdit = { vm.openEditor(FileItem.of(File(it), null)) },
            onDismiss = { attachmentPath = null }
        )
    }
    if (showTemplateDialog) {
        TemplatePickerDialog(
            templates = state.templates,
            onPick = { template ->
                showTemplateDialog = false
                pendingTemplate = template
                showNewFileDialog = true
            },
            onDismiss = { showTemplateDialog = false }
        )
    }
    renameTarget?.let { target ->
        NameInputDialog(
            title = stringResource(R.string.dialog_rename_title),
            confirmLabel = stringResource(R.string.action_confirm),
            initialName = target.name,
            onConfirm = {
                vm.rename(target.path, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
}

/** 点击反馈的 ListItem 包装 */
@Composable
internal fun Modifier.clickableItem(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    )

// ---------- 子组件 ----------

@Composable
private fun BatchTargetBar(
    op: BatchOp,
    dirName: String,
    selectionCount: Int,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (op == BatchOp.MOVE) Icons.Filled.DriveFileMove else Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (op == BatchOp.MOVE) R.string.batch_move_title else R.string.batch_copy_title
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = dirName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onConfirm, enabled = canConfirm) {
                Text(
                    stringResource(
                        if (op == BatchOp.MOVE) R.string.batch_confirm_move else R.string.batch_confirm_copy,
                        selectionCount
                    )
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_close_search)
            )
        }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.action_search)) },
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    count: Int,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.12f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton(Icons.Filled.Delete, R.string.action_delete, onDelete, count)
                ActionButton(Icons.Filled.DriveFileMove, R.string.action_move, onMove, count)
                ActionButton(Icons.Filled.ContentCopy, R.string.action_copy, onCopy, count)
                ActionButton(Icons.Filled.Star, R.string.action_favorite, onFavorite, count)
                ActionButton(Icons.Outlined.Share, R.string.action_share, onShare, count)
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
    count: Int
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.inverseOnSurface
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface
        )
    }
}

@Composable
private fun FileGrid(
    items: List<FileItem>,
    selection: Set<String>,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit,
    onFavoriteClick: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.path }) { item ->
            val selected = item.path in selection
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) }
                    )
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                selected -> MaterialTheme.colorScheme.secondaryContainer
                                item.isDirectory -> MaterialTheme.colorScheme.primaryContainer
                                else -> fileTypeOf(item.name).container
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                        contentDescription = null,
                        tint = if (item.isDirectory) MaterialTheme.colorScheme.primary
                        else fileTypeOf(item.name).color
                    )
                }
                // 类型徽标(文件夹不显示)
                if (!item.isDirectory) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(fileTypeOf(item.name).color)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = fileTypeOf(item.name).badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = formatSize(item.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.isFavorite && !item.isDirectory) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ---------- 云端浏览 ----------

/** 云端目录列表:点击进入目录/下载打开,行内菜单提供打开/下载到本地/删除 */
@Composable
private fun CloudContent(state: HomeUiState, vm: HomeViewModel) {
    when {
        state.cloudLoading -> SkeletonList(modifier = Modifier.padding(top = 8.dp))
        state.cloudItems.isEmpty() -> EmptyState(
            icon = Icons.Filled.CloudQueue,
            title = stringResource(R.string.cloud_empty_title),
            hint = stringResource(R.string.cloud_empty_hint)
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(state.cloudItems, key = { it.path }) { item ->
                var menuOpen by remember { mutableStateOf(false) }
                CloudFileRow(
                    item = item,
                    onClick = { vm.openCloudFile(item) },
                    onMoreClick = { menuOpen = true },
                    menuContent = {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!item.isDir) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cloud_open)) },
                                    onClick = {
                                        menuOpen = false
                                        vm.openCloudFile(item)
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Description, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cloud_download)) },
                                    onClick = {
                                        menuOpen = false
                                        vm.downloadCloudToLocal(item)
                                    },
                                    leadingIcon = { Icon(Icons.Filled.FileDownload, null) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cloud_delete_remote)) },
                                onClick = {
                                    menuOpen = false
                                    vm.deleteCloudFile(item)
                                },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) }
                            )
                        }
                    }
                )
            }
        }
    }
}

/** 云端文件行(结构同 FileRow,图标固定云朵底色区分) */
@Composable
private fun CloudFileRow(
    item: CloudFile,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    menuContent: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (item.isDir) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (item.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (item.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (item.isDir) {
                    stringResource(R.string.cloud_toggle_cloud)
                } else {
                    "${cloudFormatSize(item.size)} · ${cloudFormatTime(item.mtime)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            menuContent()
        }
    }
}

private fun cloudFormatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** epoch 秒 → "yyyy-MM-dd HH:mm" */
private fun cloudFormatTime(epochSec: Long): String {
    if (epochSec <= 0) return "-"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(java.util.Date(epochSec * 1000))
}

// ---------- 工具 ----------

private fun fileSubtitle(item: FileItem): String {
    val time = DateUtils.getRelativeTimeSpanString(
        item.lastModified,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
    return if (item.isDirectory) time
    else "${fileTypeOf(item.name).badge} · $time · ${formatSize(item.size)}"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1024.0)
}
