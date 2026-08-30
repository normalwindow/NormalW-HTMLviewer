package xyz.normalwindow.htmlviewer.ui.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.cloud.ConflictChoice
import xyz.normalwindow.htmlviewer.data.cloud.SyncProgress
import xyz.normalwindow.htmlviewer.data.cloud.SyncResult
import xyz.normalwindow.htmlviewer.data.cloud.SyncUiState

/**
 * 冲突决定收集器:同步引擎(挂起)与冲突对话框(Compose)之间的桥。
 * 引擎调用 [awaitDecisions] 挂起等待,对话框收集 request 展示并经 [submit] 回传决定。
 */
class ConflictDecider {

    data class Request(val files: List<String>)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private var deferred: CompletableDeferred<Map<String, ConflictChoice>>? = null

    /** 引擎侧:等待用户对每个冲突文件做出决定(取消时全部视为跳过) */
    suspend fun awaitDecisions(files: List<String>): Map<String, ConflictChoice> {
        val d = CompletableDeferred<Map<String, ConflictChoice>>()
        deferred = d
        _request.value = Request(files)
        return try {
            d.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            emptyMap()
        } finally {
            deferred = null
            _request.value = null
        }
    }

    /** 对话框侧:提交决定 */
    fun submit(choices: Map<String, ConflictChoice>) {
        deferred?.complete(choices)
    }
}

/** 同步进度对话框(进度条 + 当前上传/下载的文件 + 计数;"隐藏"后转通知栏展示) */
@Composable
fun SyncProgressDialog(
    progress: SyncProgress,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_running)) },
        text = {
            Column {
                if (progress.phase == SyncProgress.Phase.SCANNING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.sync_scanning))
                    }
                } else {
                    // 线性进度条(总体进度)
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total <= 0) 0f
                            else progress.done.toFloat() / progress.total
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    // 当前处理的文件(↑ 上传 / ↓ 下载 / ✕ 删除 / ≠ 冲突)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = progress.currentFile.substringBefore(' '),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = progress.currentFile.substringAfter(' ', missingDelimiterValue = ""),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.sync_progress_counts,
                            progress.done, progress.total,
                            progress.uploaded, progress.downloaded,
                            progress.failed
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sync_hide_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_hide)) }
        }
    )
}

/** 同步完成结果对话框(含失败文件与原因明细,便于定位问题) */
@Composable
fun SyncResultDialog(
    result: SyncResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_done_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.sync_result_counts,
                        result.uploaded, result.downloaded, result.deleted
                    )
                )
                if (result.skipped > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sync_result_skipped, result.skipped),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (result.failed > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sync_result_failed, result.failed),
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        result.failures.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) }
        }
    )
}

/**
 * 冲突处理对话框:逐个文件选择保留本地/云端版本,提供"全部本地/全部云端"快捷键。
 * 默认选保留本地(新者胜场景由引擎的策略分支直接处理,不进入本对话框)。
 */
@Composable
fun ConflictDialog(
    decider: ConflictDecider,
    onDismiss: () -> Unit
) {
    val request by decider.request.collectAsStateWithLifecycle()
    val req = request ?: return
    var choices by remember(req) {
        mutableStateOf(req.files.associateWith { ConflictChoice.USE_LOCAL })
    }
    var menuFor by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            // 点外部关闭 = 全部跳过(必须回传,否则同步引擎会一直挂起)
            onDismiss()
            decider.submit(emptyMap())
        },
        title = { Text(stringResource(R.string.sync_conflict_title, req.files.size)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sync_conflict_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    req.files.forEach { rel ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = rel.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = choiceLabel(choices[rel] ?: ConflictChoice.USE_LOCAL),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            BoxCompat {
                                TextButton(onClick = { menuFor = rel }) {
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = menuFor == rel,
                                    onDismissRequest = { menuFor = null }
                                ) {
                                    ConflictChoice.entries.forEach { c ->
                                        DropdownMenuItem(
                                            text = { Text(choiceLabel(c)) },
                                            onClick = {
                                                choices = choices.toMutableMap().apply { put(rel, c) }
                                                menuFor = null
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val submitted = choices
                    onDismiss()
                    decider.submit(submitted)
                }
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        choices = req.files.associateWith { ConflictChoice.USE_LOCAL }
                    }
                ) { Text(stringResource(R.string.sync_conflict_all_local)) }
                TextButton(
                    onClick = {
                        choices = req.files.associateWith { ConflictChoice.USE_REMOTE }
                    }
                ) { Text(stringResource(R.string.sync_conflict_all_remote)) }
            }
        }
    )
}

@Composable
private fun choiceLabel(choice: ConflictChoice): String = when (choice) {
    ConflictChoice.USE_LOCAL -> stringResource(R.string.sync_conflict_use_local)
    ConflictChoice.USE_REMOTE -> stringResource(R.string.sync_conflict_use_remote)
    ConflictChoice.SKIP -> stringResource(R.string.sync_conflict_skip)
}

/**
 * 远端目录选择对话框:手动输入路径 + 内嵌云端目录浏览器(仅列子目录)二选一。
 * 用于百度远端根目录等"同步/浏览的云端根"设置——不允许固定在根目录。
 */
@Composable
fun RemoteDirPickerDialog(
    title: String,
    initialPath: String,
    hint: String,
    /** 列出远端目录下的子目录(入参相对远端根的路径,空串 = 根) */
    listDirs: suspend (String) -> Result<List<xyz.normalwindow.htmlviewer.data.cloud.CloudFile>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialPath) }
    var browsing by remember { mutableStateOf(false) }
    var currentDir by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<List<xyz.normalwindow.htmlviewer.data.cloud.CloudFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun load(dir: String) {
        loading = true
        error = ""
        scope.launch {
            listDirs(dir).fold(
                onSuccess = {
                    dirs = it
                    currentDir = dir
                    loading = false
                },
                onFailure = {
                    loading = false
                    error = it.message ?: ""
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dir_picker_current)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    if (!browsing) {
                        browsing = true
                        load("")
                    } else {
                        browsing = false
                    }
                }) {
                    Text(
                        stringResource(
                            if (browsing) R.string.action_hide else R.string.dir_picker_browse
                        )
                    )
                }
                if (browsing) {
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sync_scanning), style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (error.isNotBlank()) {
                        Text(
                            error.ifBlank { stringResource(R.string.snack_cloud_error) },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            stringResource(R.string.dir_picker_current_label, currentDir.ifBlank { "/" }),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (currentDir.isNotBlank()) {
                            androidx.compose.material3.ListItem(
                                headlineContent = { Text("..") },
                                supportingContent = { Text(stringResource(R.string.dir_picker_parent)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        load(currentDir.substringBeforeLast('/', missingDelimiterValue = ""))
                                    }
                            )
                        }
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 240.dp)) {
                            dirs.forEach { d ->
                                androidx.compose.material3.ListItem(
                                    headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = {
                                        Icon(Icons.Filled.Folder, contentDescription = null)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val next = if (currentDir.isBlank()) d.name else "$currentDir/${d.name}"
                                            text = next
                                            load(next)
                                        }
                                )
                            }
                        }
                        if (dirs.isEmpty()) {
                            Text(
                                stringResource(R.string.dir_picker_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onPick(text.trim())
                }
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** 菜单锚点容器(避免与 Column 语义冲突的最小 Box 包装) */
@Composable
private fun BoxCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box { content() }
}
