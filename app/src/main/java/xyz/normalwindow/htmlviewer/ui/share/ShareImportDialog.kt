package xyz.normalwindow.htmlviewer.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import xyz.normalwindow.htmlviewer.data.share.IncomingSaveFormat

/** 可选的文本编码(与 TextEncoding 支持的编码保持一致) */
private val ENCODINGS = listOf(
    TextEncoding.UTF_8,
    TextEncoding.GBK,
    TextEncoding.UTF_16LE,
    TextEncoding.UTF_16BE
)

/**
 * 外部分享导入对话框:选择保存格式 / 文本编码 / 保存位置 / 文件名。
 *
 * 只负责展示与回调,读写由 [ShareImportViewModel] 完成。
 */
@Composable
fun ShareImportDialog(
    state: ShareImportUiState,
    onBaseNameChange: (String) -> Unit,
    onFormatChange: (IncomingSaveFormat) -> Unit,
    onEncodingChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = state.items.firstOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.share_import_title))
                Text(
                    text = stringResource(R.string.share_import_subtitle, state.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.staging) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // 暂存失败:直接展示底层原因,便于用户/开发者定位(读取被拒、文件已删除等)
                state.stageError?.let { detail ->
                    Text(
                        text = stringResource(R.string.share_stage_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (detail != "stage_failed") {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 源文件信息 + 编码探测结果
                first?.let { item ->
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    item.detectedEncoding?.let { enc ->
                        Text(
                            text = stringResource(R.string.share_detected_encoding, enc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (!state.allText && state.items.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.share_binary_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    item.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                        Text(
                            text = stringResource(R.string.share_preview),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider()

                // 保存格式
                SectionTitle(R.string.share_section_format)
                Column(modifier = Modifier.selectableGroup()) {
                    FormatOption(
                        label = stringResource(R.string.share_format_original),
                        desc = stringResource(R.string.share_format_original_desc),
                        selected = state.format == IncomingSaveFormat.ORIGINAL,
                        enabled = true,
                        onSelect = { onFormatChange(IncomingSaveFormat.ORIGINAL) }
                    )
                    FormatOption(
                        label = stringResource(R.string.share_format_html),
                        desc = stringResource(R.string.share_format_html_desc),
                        selected = state.format == IncomingSaveFormat.HTML,
                        enabled = state.allText,
                        onSelect = { onFormatChange(IncomingSaveFormat.HTML) }
                    )
                    FormatOption(
                        label = stringResource(R.string.share_format_text),
                        desc = stringResource(R.string.share_format_text_desc),
                        selected = state.format == IncomingSaveFormat.TEXT,
                        enabled = state.allText,
                        onSelect = { onFormatChange(IncomingSaveFormat.TEXT) }
                    )
                }

                // 编码(仅"转为文本"需要选择目标编码)
                if (state.format == IncomingSaveFormat.TEXT) {
                    SectionTitle(R.string.share_section_encoding)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ENCODINGS.forEach { enc ->
                            FilterChip(
                                selected = state.encoding == enc,
                                onClick = { onEncodingChange(enc) },
                                label = { Text(enc) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 文件名
                SectionTitle(R.string.share_section_name)
                OutlinedTextField(
                    value = state.baseName,
                    onValueChange = onBaseNameChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 保存位置
                SectionTitle(R.string.share_section_location)
                Text(
                    text = state.targetDir?.name
                        ?: stringResource(R.string.share_no_target_dir),
                    style = MaterialTheme.typography.bodyMedium
                )
                state.targetDir?.parentFile?.let { parent ->
                    Text(
                        text = parent.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onPickFolder) {
                    Text(stringResource(R.string.share_pick_folder))
                }

                state.error?.let { err ->
                    Text(
                        text = stringResource(
                            when (err) {
                                "no_target_dir" -> R.string.share_no_target_dir
                                "stage_failed" -> R.string.share_stage_failed
                                else -> R.string.share_save_failed
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.canSave
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun SectionTitle(resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun FormatOption(
    label: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
