package xyz.normalwindow.htmlviewer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.file.FileItem

/** 文件列表行:图标 + 名称 + 元信息,支持长按多选与收藏 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavoriteClick: (() -> Unit)? = null,
    onMoreClick: () -> Unit = {},
    menuContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
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
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!selectionMode) {
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
}

/** 单文件操作菜单(由 FileRow 的更多按钮锚定弹出) */
@Composable
fun FileActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onFavorite: () -> Unit,
    onGroup: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    isFavorite: Boolean,
    /** 是否显示"附加文件"项(仅 HTML) */
    showAttachments: Boolean = false,
    onAttachments: () -> Unit = {},
    /** 是否显示"编辑"项(仅文件) */
    showEdit: Boolean = true,
    onEdit: () -> Unit = {},
    /** 是否显示"外部浏览器打开"项(仅 HTML) */
    showExternalOpen: Boolean = false,
    onExternalOpen: () -> Unit = {}
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_open)) },
            onClick = onOpen,
            leadingIcon = { Icon(Icons.Filled.Description, null) }
        )
        if (showExternalOpen) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_external)) },
                onClick = onExternalOpen,
                leadingIcon = { Icon(Icons.Filled.OpenInBrowser, null) }
            )
        }
        if (showEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit)) },
                onClick = onEdit,
                leadingIcon = { Icon(Icons.Filled.Code, null) }
            )
        }
        if (showAttachments) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_attachments)) },
                onClick = onAttachments,
                leadingIcon = { Icon(Icons.Filled.AttachFile, null) }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename)) },
            onClick = onRename,
            leadingIcon = { Icon(Icons.Filled.Edit, null) }
        )
        DropdownMenuItem(
            text = {
                Text(stringResource(if (isFavorite) R.string.action_unfavorite else R.string.action_favorite))
            },
            onClick = onFavorite,
            leadingIcon = { Icon(if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_add_to_group)) },
            onClick = onGroup,
            leadingIcon = { Icon(Icons.Filled.FolderSpecial, null) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            onClick = onShare,
            leadingIcon = { Icon(Icons.Outlined.Share, null) }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            onClick = onDelete,
            leadingIcon = { Icon(Icons.Filled.Delete, null) }
        )
    }
}
