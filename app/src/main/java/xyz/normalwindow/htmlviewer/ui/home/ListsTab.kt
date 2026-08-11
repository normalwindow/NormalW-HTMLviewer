package xyz.normalwindow.htmlviewer.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.db.FavoriteGroupEntity
import xyz.normalwindow.htmlviewer.data.db.FileMetaEntity
import xyz.normalwindow.htmlviewer.data.file.FileItem
import xyz.normalwindow.htmlviewer.ui.components.EmptyState
import xyz.normalwindow.htmlviewer.ui.components.FileActionMenu
import xyz.normalwindow.htmlviewer.ui.components.FileRow
import xyz.normalwindow.htmlviewer.ui.components.fileTypeOf
import java.io.File
import java.util.Locale

/** 最近打开 / 收藏页(收藏页含分组筛选与管理) */
@Composable
fun RecentFavoritesTab(
    tab: HomeTab,
    vm: HomeViewModel,
    onOpenBrowser: (String, String) -> Unit,
    onOpenEditor: (String, String) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isFavTab = tab == HomeTab.FAVORITES
    val metas = if (isFavTab) {
        val groupId = state.selectedGroupId
        if (groupId == null) state.favorites
        else state.favorites.filter { it.groupId == groupId }
    } else {
        state.recent
    }

    var menuPath by remember { mutableStateOf<String?>(null) }
    var renamePath by remember { mutableStateOf<String?>(null) }
    var groupPickerPath by remember { mutableStateOf<String?>(null) }
    var attachmentPath by remember { mutableStateOf<String?>(null) }
    var showGroupManage by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isFavTab) {
            GroupChipsRow(
                groups = state.groups,
                selectedGroupId = state.selectedGroupId,
                onSelect = vm::selectGroup,
                onManage = { showGroupManage = true }
            )
        }

        if (metas.isEmpty()) {
            EmptyState(
                icon = if (isFavTab) Icons.Filled.Star else Icons.Filled.History,
                title = stringResource(
                    if (isFavTab) R.string.empty_fav_title else R.string.empty_recent_title
                ),
                hint = stringResource(
                    if (isFavTab) R.string.empty_fav_hint else R.string.empty_recent_hint
                )
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(metas, key = { it.path }) { meta ->
                    val file = File(meta.path)
                    val exists = file.exists()
                    val item = FileItem.of(file, meta)
                    FileRow(
                        item = item,
                        selected = false,
                        selectionMode = false,
                        subtitle = if (exists) filesSubtitleShort(meta)
                        else stringResource(R.string.snack_error_io),
                        onClick = {
                            if (exists) vm.openItem(item) else vm.onMissingFile()
                        },
                        onLongClick = { vm.toggleFavorite(meta.path) },
                        onFavoriteClick = { vm.toggleFavorite(meta.path) },
                        onMoreClick = { menuPath = meta.path },
                        menuContent = {
                            FileActionMenu(
                                expanded = menuPath == meta.path,
                                onDismiss = { menuPath = null },
                                onOpen = {
                                    menuPath = null
                                    if (exists) vm.openItem(item) else vm.onMissingFile()
                                },
                                onEdit = {
                                    menuPath = null
                                    if (exists) vm.openEditor(item) else vm.onMissingFile()
                                },
                                onRename = {
                                    menuPath = null
                                    renamePath = meta.path
                                },
                                onFavorite = {
                                    menuPath = null
                                    vm.toggleFavorite(meta.path)
                                },
                                onGroup = {
                                    menuPath = null
                                    groupPickerPath = meta.path
                                },
                                onAttachments = {
                                    menuPath = null
                                    attachmentPath = meta.path
                                },
                                showAttachments = !file.isDirectory && file.name.substringAfterLast('.', "")
                                    .lowercase() in setOf("html", "htm"),
                                onShare = {
                                    menuPath = null
                                    vm.share(meta.path)
                                },
                                onDelete = {
                                    menuPath = null
                                    vm.deleteOne(meta.path)
                                },
                                isFavorite = meta.isFavorite
                            )
                        }
                    )
                }
            }
        }
    }

    renamePath?.let { path ->
        NameInputDialog(
            title = stringResource(R.string.dialog_rename_title),
            confirmLabel = stringResource(R.string.action_confirm),
            initialName = File(path).name,
            onConfirm = {
                vm.rename(path, it)
                renamePath = null
            },
            onDismiss = { renamePath = null }
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

    if (showGroupManage) {
        GroupManageDialog(
            groups = state.groups,
            onCreate = vm::createGroup,
            onRename = vm::renameGroup,
            onDelete = vm::deleteGroup,
            onDismiss = { showGroupManage = false }
        )
    }
}

/** 收藏分组筛选条:全部 + 各分组 + 管理入口 */
@Composable
private fun GroupChipsRow(
    groups: List<FavoriteGroupEntity>,
    selectedGroupId: Long?,
    onSelect: (Long?) -> Unit,
    onManage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selectedGroupId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.group_all)) }
        )
        groups.forEach { group ->
            FilterChip(
                selected = selectedGroupId == group.id,
                onClick = { onSelect(group.id) },
                label = { Text(group.name) }
            )
        }
        IconButton(onClick = onManage) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.action_manage_groups),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 分组选择对话框(将文件加入分组) */
@Composable
internal fun GroupPickerDialog(
    groups: List<FavoriteGroupEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_group_picker_title)) },
        text = {
            Column {
                if (groups.isEmpty()) {
                    Text(
                        stringResource(R.string.group_picker_empty),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                    )
                }
                groups.forEach { group ->
                    ListItem(
                        headlineContent = { Text(group.name) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableRow { onPick(group.id) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** 分组管理对话框:新建 / 重命名 / 删除 */
@Composable
private fun GroupManageDialog(
    groups: List<FavoriteGroupEntity>,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FavoriteGroupEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_manage_groups)) },
        text = {
            Column {
                groups.forEach { group ->
                    ListItem(
                        headlineContent = { Text(group.name) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { renameTarget = group }) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.action_rename_group)
                                    )
                                }
                                IconButton(onClick = { onDelete(group.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.action_delete_group)
                                    )
                                }
                            }
                        }
                    )
                }
                TextButton(
                    onClick = { showCreate = true },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp)
                ) {
                    Text(stringResource(R.string.action_new_group))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )

    if (showCreate) {
        NameInputDialog(
            title = stringResource(R.string.action_new_group),
            confirmLabel = stringResource(R.string.action_confirm),
            onConfirm = {
                if (it.isNotBlank()) onCreate(it)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }
    renameTarget?.let { group ->
        NameInputDialog(
            title = stringResource(R.string.action_rename_group),
            confirmLabel = stringResource(R.string.action_confirm),
            initialName = group.name,
            onConfirm = {
                if (it.isNotBlank()) onRename(group.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
}

/** 最近/收藏页副标题:徽标 + 相对时间 + 大小 */
internal fun filesSubtitleShort(meta: FileMetaEntity): String {
    val time = DateUtils.getRelativeTimeSpanString(
        meta.lastOpenedAt ?: meta.createdAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
    val size = File(meta.path).takeIf { it.isFile }?.length() ?: 0L
    val badge = if (size > 0) "${fileTypeOf(File(meta.path).name).badge} · " else ""
    return if (size > 0) "$badge$time · ${formatSizeShort(size)}" else time
}

internal fun formatSizeShort(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}

@Composable
internal fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
