package xyz.normalwindow.htmlviewer.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.file.FileItem
import xyz.normalwindow.htmlviewer.ui.components.fileTypeOf
import java.io.File

/**
 * 附加文件对话框:管理 HTML 文件的关联资源(js/css/ts 等),
 * 支持打开编辑、删除、新建(可自动引用到 HTML)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentDialog(
    htmlPath: String,
    vm: HomeViewModel,
    onEdit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val htmlName = File(htmlPath).name
    var attachments by remember { mutableStateOf<List<FileItem>>(vm.listAttachments(htmlPath)) }
    var autoReference by remember { mutableStateOf(true) }
    var newNameDialog by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val createFailedMsg = stringResource(R.string.snack_error_create)

    fun refresh() {
        attachments = vm.listAttachments(htmlPath)
        errorMsg = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_attachments_title, htmlName)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                errorMsg?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                if (attachments.isEmpty()) {
                    Text(
                        stringResource(R.string.attachments_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(attachments, key = { it.path }) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = {
                                    val time = DateUtils.getRelativeTimeSpanString(
                                        item.lastModified,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS
                                    )
                                    Text("${fileTypeOf(item.name).badge} · $time · ${formatSizeShort(item.size)}")
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Filled.Description,
                                        null,
                                        tint = fileTypeOf(item.name).color
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = {
                                        vm.deleteAttachment(item.path)
                                        refresh()
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.action_delete)
                                        )
                                    }
                                },
                                modifier = Modifier.clickableRow { onEdit(item.path) }
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // 新建附加文件:统一按钮样式,FlowRow 自动换行对齐
                Text(
                    stringResource(R.string.attachments_new_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AttachmentNewButton(R.string.attachments_new_js) {
                        newNameDialog = defaultAttachmentName(htmlPath, "js")
                    }
                    AttachmentNewButton(R.string.attachments_new_css) {
                        newNameDialog = defaultAttachmentName(htmlPath, "css")
                    }
                    AttachmentNewButton(R.string.attachments_new_ts) {
                        newNameDialog = defaultAttachmentName(htmlPath, "ts")
                    }
                    AttachmentNewButton(R.string.attachments_new_json) {
                        newNameDialog = defaultAttachmentName(htmlPath, "json")
                    }
                    AttachmentNewButton(R.string.attachments_new_other) {
                        newNameDialog = defaultAttachmentName(htmlPath, "txt")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoReference, onCheckedChange = { autoReference = it })
                    Text(
                        stringResource(R.string.attachments_auto_ref),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )

    newNameDialog?.let { initial ->
        NameInputDialog(
            title = stringResource(R.string.attachments_new_title),
            confirmLabel = stringResource(R.string.action_confirm),
            initialName = initial,
            onConfirm = { name ->
                newNameDialog = null
                if (name.isNotBlank()) {
                    vm.createAttachment(
                        htmlPath = htmlPath,
                        fileName = name.trim(),
                        autoReference = autoReference
                    ) { result ->
                        result.onSuccess { refresh() }
                            .onFailure { errorMsg = createFailedMsg }
                    }
                }
            },
            onDismiss = { newNameDialog = null }
        )
    }
}

/** 新建附加文件按钮(统一样式:加号图标 + 文本,间距由 Spacer 控制) */
@Composable
private fun AttachmentNewButton(labelRes: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(labelRes))
    }
}

/** 默认附加文件名:同名不同扩展名(重复时加序号) */
private fun defaultAttachmentName(htmlPath: String, ext: String): String {
    val html = File(htmlPath)
    val stem = html.name.substringBeforeLast('.')
    var candidate = "$stem.$ext"
    var i = 1
    while (File(html.parentFile, candidate).exists()) {
        i++
        candidate = "$stem-$i.$ext"
    }
    return candidate
}
