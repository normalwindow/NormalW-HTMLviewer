package xyz.normalwindow.htmlviewer.ui.home

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.cloud.CloudProviderType
import xyz.normalwindow.htmlviewer.data.cloud.SyncUiState
import xyz.normalwindow.htmlviewer.ui.cloud.ConflictDialog
import xyz.normalwindow.htmlviewer.ui.cloud.SyncProgressDialog
import xyz.normalwindow.htmlviewer.ui.cloud.SyncResultDialog
import xyz.normalwindow.htmlviewer.ui.settings.SettingsScreen

/** 主界面:顶部栏 + 四个页签 + Snackbar/导航事件分发 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenBrowser: (String, String) -> Unit,
    onOpenEditor: (String, String) -> Unit,
    onOpenAbout: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // SAF 文件/文件夹导入(系统文档选择器,无需存储权限)
    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.importFolder(uri)
    }

    // 后台同步通知权限(Android 13+)
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is HomeEvent.OpenBrowser -> onOpenBrowser(event.path, event.name)
                is HomeEvent.OpenEditor -> onOpenEditor(event.path, event.name)
                is HomeEvent.Snackbar -> {
                    val message = context.string(event.kind, event.count)
                    val isDelete = event.kind == SnackKind.DELETED
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = if (isDelete) context.getString(R.string.action_undo) else null,
                        duration = if (isDelete) SnackbarDuration.Long else SnackbarDuration.Short,
                        withDismissAction = !isDelete
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
                }
                is HomeEvent.SnackbarText -> {
                    snackbarHostState.showSnackbar(
                        message = event.message.ifBlank {
                            context.getString(R.string.snack_cloud_error)
                        },
                        duration = SnackbarDuration.Long
                    )
                }
            }
        }
    }

    // 云同步进度/结果/失败对话框(云端菜单"立即同步"或启动自动同步触发)
    val syncState by vm.sync.syncState.collectAsStateWithLifecycle()
    val progressHidden by vm.sync.progressHidden.collectAsStateWithLifecycle()
    when (val s = syncState) {
        is SyncUiState.Running ->
            if (!progressHidden) {
                                SyncProgressDialog(progress = s.progress, onDismiss = {
                    // 隐藏后转通知栏展示:Android 13+ 首次隐藏时请求通知权限
                    if (!vm.sync.hasNotificationPermission() && android.os.Build.VERSION.SDK_INT >= 33) {
                        notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.sync.hideProgress()
                })
            }
        is SyncUiState.Done -> SyncResultDialog(result = s.result) { vm.sync.consumeState() }
        is SyncUiState.Failed -> AlertDialog(
            onDismissRequest = { vm.sync.consumeState() },
            title = { Text(stringResource(R.string.sync_failed_title)) },
            text = {
                Text(
                    if (s.message.isBlank()) stringResource(R.string.sync_failed_generic)
                    else s.message
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.sync.consumeState() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
        SyncUiState.Idle -> Unit
    }
    // 冲突处理对话框(由 ConflictDecider 的请求驱动,无请求时不渲染)
    ConflictDialog(decider = vm.sync.conflictDecider, onDismiss = {})

    Scaffold(
        topBar = {
            HomeTopBar(
                state = state,
                vm = vm,
                onImportFiles = { importFilesLauncher.launch(arrayOf("*/*")) },
                onImportFolder = { importFolderLauncher.launch(null) }
            )
        },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.tab == tab,
                        onClick = { vm.selectTab(tab) },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    HomeTab.FILES -> Icons.Filled.Folder
                                    HomeTab.RECENT -> Icons.Filled.History
                                    HomeTab.FAVORITES -> Icons.Filled.Star
                                    HomeTab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = null
                            )
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (tab) {
                                        HomeTab.FILES -> R.string.tab_files
                                        HomeTab.RECENT -> R.string.tab_recent
                                        HomeTab.FAVORITES -> R.string.tab_favorites
                                        HomeTab.SETTINGS -> R.string.tab_settings
                                    }
                                )
                            )
                        }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (state.tab) {
                HomeTab.FILES -> FilesTab(vm, onOpenBrowser, onOpenEditor)
                HomeTab.RECENT, HomeTab.FAVORITES ->
                    RecentFavoritesTab(state.tab, vm, onOpenBrowser, onOpenEditor)
                HomeTab.SETTINGS -> SettingsScreen(onOpenAbout = onOpenAbout)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    state: HomeUiState,
    vm: HomeViewModel,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    val batchTitle = when (state.batchMode) {
        BatchOp.MOVE -> stringResource(R.string.batch_move_title)
        BatchOp.COPY -> stringResource(R.string.batch_copy_title)
        null -> null
    }
    val title = when {
        // 批量选择目标时显示当前浏览目录,便于确认目标位置
        state.batchMode != null -> state.currentDir?.name ?: batchTitle.orEmpty()
        state.selection.isNotEmpty() ->
            stringResource(R.string.selection_count, state.selection.size)
        // 云端模式:标题为当前云端目录名(根目录显示"云端")
        state.tab == HomeTab.FILES && state.dataSource == DataSource.CLOUD ->
            state.cloudDir.substringAfterLast('/').ifBlank {
                stringResource(R.string.cloud_toggle_cloud)
            }
        state.tab == HomeTab.FILES -> state.currentDir?.name ?: stringResource(R.string.app_name)
        state.tab == HomeTab.RECENT -> stringResource(R.string.tab_recent)
        state.tab == HomeTab.FAVORITES -> stringResource(R.string.tab_favorites)
        else -> stringResource(R.string.tab_settings)
    }

    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            when {
                // 批量选目标:可返回上级目录继续浏览(保留选中项),根目录时取消入口在 actions 区
                state.batchMode != null && !state.isRoot -> IconButton(onClick = vm::goUpInBatch) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
                state.selection.isNotEmpty() -> IconButton(onClick = vm::clearSelection) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                // 云端模式:返回上级云端目录
                state.tab == HomeTab.FILES &&
                    state.dataSource == DataSource.CLOUD && state.cloudDir.isNotEmpty() ->
                    IconButton(onClick = vm::cloudGoUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                state.tab == HomeTab.FILES && !state.isRoot -> IconButton(onClick = vm::goUp) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            }
        },
        actions = {
            if (state.batchMode != null) {
                // 批量选目标时提供关闭入口(取消批量操作)
                IconButton(onClick = vm::cancelBatch) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            } else if (state.tab == HomeTab.FILES && state.selection.isEmpty()) {
                if (state.dataSource == DataSource.LOCAL && !state.showSearch) {
                    IconButton(onClick = { vm.setSearchVisible(true) }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                    }
                }
                // 云本地切换:本地 ⇄ 云端
                IconButton(
                    onClick = {
                        vm.setDataSource(
                            if (state.dataSource == DataSource.LOCAL) DataSource.CLOUD
                            else DataSource.LOCAL
                        )
                    }
                ) {
                    Icon(
                        imageVector = if (state.dataSource == DataSource.LOCAL) {
                            Icons.Filled.Cloud
                        } else {
                            Icons.Filled.Smartphone
                        },
                        contentDescription = stringResource(
                            if (state.dataSource == DataSource.LOCAL) {
                                R.string.cloud_toggle_cloud
                            } else {
                                R.string.cloud_toggle_local
                            }
                        )
                    )
                }
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    if (state.dataSource == DataSource.CLOUD) {
                        // 云端模式菜单:云盘切换 / 立即同步 / 刷新
                        listOf(
                            CloudProviderType.BAIDU to R.string.cloud_baidu,
                            CloudProviderType.WEBDAV to R.string.cloud_webdav
                        ).forEach { (type, labelRes) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes)) },
                                onClick = {
                                    showMoreMenu = false
                                    vm.setCloudProvider(type)
                                },
                                leadingIcon = { Icon(Icons.Filled.Cloud, null) },
                                trailingIcon = {
                                    if (state.cloudProvider == type) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_menu_sync)) },
                            onClick = {
                                showMoreMenu = false
                                vm.sync.syncNow()
                            },
                            leadingIcon = { Icon(Icons.Filled.Sync, null) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_import_files)) },
                            onClick = {
                                showMoreMenu = false
                                onImportFiles()
                            },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_import_folder)) },
                            onClick = {
                                showMoreMenu = false
                                onImportFolder()
                            },
                            leadingIcon = { Icon(Icons.Filled.DriveFolderUpload, null) }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (state.viewMode == ViewMode.LIST) R.string.action_view_grid
                                    else R.string.action_view_list
                                )
                            )
                        },
                        onClick = {
                            showMoreMenu = false
                            vm.setViewMode(
                                if (state.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (state.viewMode == ViewMode.LIST) Icons.Filled.GridView
                                else Icons.Filled.ViewList,
                                null
                            )
                        }
                    )
                    // 排序方式(仅本地文件列表;目录恒在前)
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_by)) },
                        onClick = {},
                        enabled = false,
                        leadingIcon = { Icon(Icons.Filled.Sort, null) }
                    )
                    listOf(
                        SortMode.NAME to R.string.sort_by_name,
                        SortMode.TIME to R.string.sort_by_time,
                        SortMode.SIZE to R.string.sort_by_size,
                        SortMode.TYPE to R.string.sort_by_type
                    ).forEach { (mode, labelRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            onClick = {
                                vm.setSortMode(mode)
                                showMoreMenu = false
                            },
                            trailingIcon = {
                                if (state.sortMode == mode) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (state.sortAscending) R.string.sort_ascending
                                    else R.string.sort_descending
                                )
                            )
                        },
                        onClick = {
                            vm.setSortAscending(!state.sortAscending)
                            showMoreMenu = false
                        },
                        leadingIcon = { Icon(Icons.Filled.SwapVert, null) },
                        trailingIcon = {
                            Text(
                                if (state.sortAscending) "↑" else "↓",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_refresh)) },
                        onClick = {
                            showMoreMenu = false
                            if (state.dataSource == DataSource.CLOUD) vm.refreshCloud() else vm.refresh()
                        },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) }
                    )
                }
            }
        }
    )
}

/** SnackKind → 字符串资源(支持计数参数) */
private fun Context.string(kind: SnackKind, count: Int): String {
    val res = when (kind) {
        SnackKind.CREATED_FILE -> R.string.snack_created_file
        SnackKind.CREATED_DIR -> R.string.snack_created_dir
        SnackKind.DELETED -> R.string.snack_deleted
        SnackKind.UNDO_DELETED -> R.string.snack_undo_deleted
        SnackKind.RENAMED -> R.string.snack_renamed
        SnackKind.MOVED -> R.string.snack_moved
        SnackKind.COPIED -> R.string.snack_copied
        SnackKind.FAV_ADDED -> R.string.snack_fav_added
        SnackKind.FAV_REMOVED -> R.string.snack_fav_removed
        SnackKind.SHARED -> R.string.snack_error_io // 分享无提示,占位不会用到
        SnackKind.ERROR_CREATE -> R.string.snack_error_create
        SnackKind.ERROR_RENAME -> R.string.snack_error_rename
        SnackKind.ERROR_DELETE -> R.string.snack_error_delete
        SnackKind.ERROR_MOVE -> R.string.snack_error_move
        SnackKind.ERROR_COPY -> R.string.snack_error_copy
        SnackKind.ERROR_IO -> R.string.snack_error_io
        SnackKind.TRASH_EMPTY -> R.string.snack_trash_empty
        SnackKind.GROUP_CREATED -> R.string.snack_group_created
        SnackKind.GROUP_DELETED -> R.string.snack_group_deleted
        SnackKind.IMPORTED -> R.string.snack_imported
        SnackKind.ERROR_IMPORT -> R.string.snack_error_import
        SnackKind.CLOUD_DOWNLOADED -> R.string.snack_cloud_downloaded
        SnackKind.CLOUD_DELETED -> R.string.snack_cloud_deleted
        SnackKind.ERROR_CLOUD -> R.string.snack_cloud_error
    }
    // 始终传 count:含 %1$d 占位符的资源正常格式化,无占位符的资源会忽略多余参数,
    // 避免 count=0 时走无参分支而把 "已删除 %1$d 项" 之类字面量直接显示出来
    return getString(res, count)
}
