package xyz.normalwindow.htmlviewer.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import xyz.normalwindow.htmlviewer.ui.settings.SettingsScreen

/** 主界面:顶部栏 + 四个页签 + Snackbar/导航事件分发 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenBrowser: (String, String) -> Unit,
    onOpenEditor: (String, String) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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
            }
        }
    }

    Scaffold(
        topBar = { HomeTopBar(state = state, vm = vm) },
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
                HomeTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    state: HomeUiState,
    vm: HomeViewModel
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
                if (!state.showSearch) {
                    IconButton(onClick = { vm.setSearchVisible(true) }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                    }
                }
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_refresh)) },
                        onClick = {
                            showMoreMenu = false
                            vm.refresh()
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
    }
    // 始终传 count:含 %1$d 占位符的资源正常格式化,无占位符的资源会忽略多余参数,
    // 避免 count=0 时走无参分支而把 "已删除 %1$d 项" 之类字面量直接显示出来
    return getString(res, count)
}
