package xyz.normalwindow.htmlviewer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import xyz.normalwindow.htmlviewer.render.UserAgentPreset

/**
 * 右对齐下拉菜单:菜单右边缘与触发项右边缘对齐,从触发项下方弹出。
 * 用于设置页"默认 UA 标识"/"语言"等选项选择(替代默认左侧对齐的 DropdownMenu)。
 *
 * @param anchorHeightPx 触发项高度(px,用 Modifier.onSizeChanged 获取),
 *   用于把菜单放到触发项下方。
 */
@Composable
fun RightAlignedMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorHeightPx: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return
    Popup(
        onDismissRequest = onDismissRequest,
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, anchorHeightPx),
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            // IntrinsicSize.Min:菜单宽度取内容宽度,绕过 Popup 传播的锚点约束
            // (否则菜单会撑满锚点宽度,右对齐失效)
            modifier = modifier.width(IntrinsicSize.Min),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) { content() }
        }
    }
}

/** UA 预设的本地化显示名(英文名如 "Android Chrome" 保持原文,中文项走资源翻译) */
@Composable
fun uaPresetLabel(preset: UserAgentPreset): String =
    if (preset.labelRes != 0) stringResource(preset.labelRes) else preset.displayName
