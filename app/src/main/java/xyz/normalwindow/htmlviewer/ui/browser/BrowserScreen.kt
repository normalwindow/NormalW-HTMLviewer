package xyz.normalwindow.htmlviewer.ui.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.render.ConsoleArg
import xyz.normalwindow.htmlviewer.render.ConsoleArgType
import xyz.normalwindow.htmlviewer.render.ConsoleLevel
import xyz.normalwindow.htmlviewer.render.UserAgentPreset
import xyz.normalwindow.htmlviewer.ui.components.uaPresetLabel

/**
 * 浏览器预览页:页面完全可交互(滚动/链接/表单),
 * 顶部工具栏提供后退/前进/刷新/UA/JS 开关/沉浸切换,并可跳转编辑器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    path: String,
    name: String,
    vm: BrowserViewModel,
    onEdit: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var uaMenu by remember { mutableStateOf(false) }
    var showConsoleSheet by remember { mutableStateOf(false) }
    // header 背景透明度:页面内容滑过顶部时更透明,带平滑过渡避免跳变
    val headerAlpha by animateFloatAsState(
        targetValue = if (state.pageScrolled) 0.55f else 0.96f,
        label = "headerAlpha"
    )

    /** 复制文本到剪贴板并 Toast 提示(抽屉打开时 Snackbar 会被遮挡,用系统 Toast) */
    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("console", text))
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.browser_console_copied),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** 格式化单条日志(复制用,级别用英文名;参数取展开文本) */
    fun formatEntry(entry: ConsoleEntry): String = buildString {
        append("[").append(entry.level.name).append("] ")
        if (entry.args.isEmpty()) {
            append(entry.message)
        } else {
            append(
                entry.args.joinToString(" ") { arg ->
                    if (!arg.pretty.isNullOrBlank()) arg.pretty else arg.text
                }
            )
        }
        if (!entry.source.isNullOrBlank()) {
            append("\n  ").append(entry.source).append(":").append(entry.lineNumber)
        }
    }

    LaunchedEffect(Unit) { vm.initialize(path) }

    // 沉浸模式:隐藏/恢复系统栏
    LaunchedEffect(state.immersive) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (state.immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 沉浸模式由菜单/顶部细条显式切换(不再自动隐藏):菜单"沉浸模式"开启,\n    // 点击顶部细条退出沉浸并召唤 header\n\n    // 退出页面时恢复系统栏(沉浸模式曾隐藏)
    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        if (state.ready && !state.immersive && state.canGoBack) {
            vm.goBack()
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.ready) {
            AndroidView(
                factory = { vm.rendererView() ?: android.view.View(context) },
                modifier = Modifier.fillMaxSize(),
                onRelease = { vm.onRendererReleased() }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        // 沉浸模式:屏幕顶部细条,点击退出沉浸并召唤 header(不拦截滚动)
        if (state.immersive && !state.toolbarVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clickable { vm.toggleImmersive() }
            )
        }

        // 顶部工具栏(沉浸模式下滑入滑出)
        AnimatedVisibility(
            visible = state.toolbarVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp,
                    // 页面内容滑过顶部时 header 更透明,避免遮挡被滚动到 header 下方的正文
                    color = MaterialTheme.colorScheme.surface.copy(alpha = headerAlpha),
                    // 显式内容色:防止 icon 使用错误的深色 tint 与 header 背景重叠
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 非沉浸时避开系统状态栏(沉浸时 insets 为 0,自动不偏移)
                                .statusBarsPadding()
                                .height(52.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back)
                                )
                            }
                            // 返回主页(系统返回键已承担网页后退;前进键使用率低,此处改为回主页入口)
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.Filled.Home,
                                    contentDescription = stringResource(R.string.browser_home)
                                )
                            }
                            IconButton(onClick = vm::reload) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.browser_refresh)
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = state.title.ifBlank { name },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (state.loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(2.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                            // 沉浸模式(与编辑模式对调:编辑移入菜单,此处显示沉浸切换)
                            IconButton(onClick = vm::toggleImmersive) {
                                Icon(
                                    if (state.immersive) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                    contentDescription = stringResource(R.string.browser_immersive)
                                )
                            }
                            // 控制台/报错/警告抽屉入口(彩色圆点角标:颜色按最严重级别,保留未读数字)
                            IconButton(onClick = {
                                showConsoleSheet = true
                                vm.markConsoleRead()
                            }) {
                                BadgedBox(
                                    badge = {
                                        val peak = state.consolePeakLevel
                                        if (peak != null) {
                                            val (badgeColor, badgeContent) = consoleLevelColors(peak)
                                            Badge(
                                                containerColor = badgeColor,
                                                // 数字用内容色:在彩色圆点上保持可读
                                                contentColor = badgeContent
                                            ) {
                                                Text(
                                                    if (state.consoleUnread > 99) "99+" else state.consoleUnread.toString()
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.Terminal,
                                        contentDescription = stringResource(R.string.browser_console)
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { uaMenu = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.browser_more)
                                    )
                                }
                                DropdownMenu(
                                    expanded = uaMenu,
                                    onDismissRequest = { uaMenu = false }
                                ) {
                                    Text(
                                        text = stringResource(R.string.browser_ua_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                    UserAgentPreset.entries.forEach { preset ->
                                        DropdownMenuItem(
                                            text = { Text(uaPresetLabel(preset)) },
                                            onClick = {
                                                uaMenu = false
                                                vm.setUaPreset(preset)
                                            },
                                            trailingIcon = {
                                                if (state.uaPreset == preset) {
                                                    Text(
                                                        "✓",
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.browser_js),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Switch(
                                            checked = state.jsEnabled,
                                            onCheckedChange = { vm.toggleJs() }
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.browser_console),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = stringResource(
                                                    if (state.consoleSupported) R.string.browser_console_desc
                                                    else R.string.browser_console_gecko_note
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = state.consoleEnabled,
                                            onCheckedChange = { vm.toggleConsole() }
                                        )
                                    }
                                    // 编辑(与 header 沉浸模式对调:从 header 移入菜单)
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_edit)) },
                                        leadingIcon = { Icon(Icons.Filled.Code, null) },
                                        onClick = {
                                            uaMenu = false
                                            onEdit(path, name)
                                        }
                                    )
                                    HorizontalDivider()
                                    // 模拟鼠标(触摸板模式);GeckoView 内核无 JS 注入 API,禁用
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.browser_touchpad),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (state.touchpadSupported) {
                                                    MaterialTheme.colorScheme.onSurface
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                            Text(
                                                text = stringResource(R.string.browser_touchpad_desc),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = state.touchpadEnabled,
                                            enabled = state.touchpadSupported,
                                            onCheckedChange = { vm.toggleTouchpad() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 控制台/报错/警告抽屉(内容撑满全屏高度,拖拽把手可上拉至全屏)
    if (showConsoleSheet) {
        ModalBottomSheet(onDismissRequest = { showConsoleSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.browser_console_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        copyToClipboard(
                            state.consoleEntries.joinToString("\n\n") { formatEntry(it) }
                        )
                    }, enabled = state.consoleEntries.isNotEmpty()) {
                        Text(stringResource(R.string.browser_console_copy_all))
                    }
                    TextButton(onClick = vm::clearConsole) {
                        Text(stringResource(R.string.browser_console_clear))
                    }
                }
                HorizontalDivider()
                if (state.consoleEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.browser_console_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(state.consoleEntries) { entry ->
                            ConsoleEntryRow(entry, onCopy = { copyToClipboard(formatEntry(it)) })
                        }
                    }
                }
            }
        }
    }
}

/** 控制台级别配色(标签/角标共用):返回 (容器色, 内容色) 对,更多元素随主题色变化 */
@Composable
private fun consoleLevelColors(level: ConsoleLevel): Pair<Color, Color> = when (level) {
    ConsoleLevel.ERROR -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
    ConsoleLevel.WARN -> Color(0xFFE65100) to Color.White
    ConsoleLevel.INFO -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    ConsoleLevel.LOG -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    ConsoleLevel.DEBUG -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}

/** 控制台单条日志行:左侧彩色级别胶囊 + 多参数/样式消息;长按复制单条 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleEntryRow(entry: ConsoleEntry, onCopy: (ConsoleEntry) -> Unit) {
    val label = consoleLevelLabel(entry.level)
    val (labelColor, labelContent) = consoleLevelColors(entry.level)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { onCopy(entry) }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // 级别标签:彩色胶囊(底色 + 反色文字),强化种类区分度
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(labelColor)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelContent,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
            if (entry.args.isEmpty()) {
                // 旧式单文本消息(Gecko 报错/注入前日志等)
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    ConsoleArgsText(entry.args)
                }
            }
        }
        if (!entry.source.isNullOrBlank()) {
            Text(
                text = "${entry.source}:${entry.lineNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

/** 多参数消息渲染:按类型着色,支持 %c 内联样式与对象点击展开 */
@Composable
private fun ConsoleArgsText(args: List<ConsoleArg>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        args.forEachIndexed { index, arg ->
            if (index > 0) Spacer(Modifier.width(6.dp))
            ConsoleArgView(arg)
        }
    }
}

@Composable
private fun ConsoleArgView(arg: ConsoleArg) {
    // 类型默认色(无 %c 样式时;对象/数字等使用代码风格配色)
    val baseColor = when (arg.type) {
        ConsoleArgType.NUMBER, ConsoleArgType.BOOLEAN -> MaterialTheme.colorScheme.primary
        ConsoleArgType.STRING -> MaterialTheme.colorScheme.onSurface
        ConsoleArgType.NULL, ConsoleArgType.UNDEFINED -> MaterialTheme.colorScheme.onSurfaceVariant
        ConsoleArgType.OBJECT, ConsoleArgType.ARRAY, ConsoleArgType.ERROR ->
            MaterialTheme.colorScheme.tertiary
        ConsoleArgType.FUNCTION -> MaterialTheme.colorScheme.secondary
        ConsoleArgType.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // %c 内联样式优先(颜色/背景/粗细/斜体/下划线)
    val cssStyle = arg.style?.let { cssToTextStyle(it, baseColor) }
    val mono = arg.type == ConsoleArgType.NUMBER || arg.type == ConsoleArgType.BOOLEAN ||
        arg.type == ConsoleArgType.OBJECT || arg.type == ConsoleArgType.ARRAY ||
        arg.type == ConsoleArgType.ERROR
    val textStyle = (cssStyle ?: MaterialTheme.typography.bodySmall).let { base ->
        base.copy(
            fontFamily = if (mono) FontFamily.Monospace else base.fontFamily
        )
    }

    if (arg.expandable && !arg.pretty.isNullOrBlank()) {
        var expanded by remember { mutableStateOf(false) }
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 2.dp, vertical = 1.dp)
            ) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = arg.text,
                    style = textStyle,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (expanded) {
                Text(
                    text = arg.pretty,
                    style = textStyle.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp)
                )
            }
        }
    } else {
        Text(
            text = arg.text,
            style = textStyle,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** CSS 内联样式 → Compose TextStyle(仅取受支持字段,解析失败保持默认) */
private fun cssToTextStyle(css: String, fallbackColor: Color): TextStyle {
    var parsedColor: Color? = null
    var parsedBackground: Color? = null
    var fontWeight: FontWeight? = null
    var fontStyle: FontStyle? = null
    var textDecoration: TextDecoration? = null
    css.split(";").forEach { decl ->
        val idx = decl.indexOf(':')
        if (idx <= 0) return@forEach
        val key = decl.substring(0, idx).trim().lowercase()
        val value = decl.substring(idx + 1).trim()
        when (key) {
            "color" -> parsedColor = parseCssColor(value)
            "background", "background-color" -> parsedBackground = parseCssColor(value)
            "font-weight" -> fontWeight = when (value.lowercase()) {
                "bold", "bolder", "700", "800", "900" -> FontWeight.Bold
                "600", "500" -> FontWeight.SemiBold
                "normal", "400" -> FontWeight.Normal
                "lighter", "300", "200", "100" -> FontWeight.Light
                else -> null
            }
            "font-style" -> fontStyle = when (value.lowercase()) {
                "italic", "oblique" -> FontStyle.Italic
                else -> null
            }
            "text-decoration" -> textDecoration = when (value.lowercase()) {
                "underline" -> TextDecoration.Underline
                "line-through" -> TextDecoration.LineThrough
                "underline line-through" -> TextDecoration.combine(
                    listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                )
                else -> null
            }
        }
    }
    return TextStyle(
        color = parsedColor ?: fallbackColor,
        background = parsedBackground ?: Color.Unspecified,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        textDecoration = textDecoration
    )
}

/** CSS 颜色解析:hex(#RGB/#RRGGBB/#AARRGGBB/#RRGGBBAA)/ rgb()/rgba()/命名色 */
private fun parseCssColor(value: String): Color? {
    val v = value.trim()
    // 3/4 位 hex 展开为 6/8 位(android.graphics.Color 不直接支持)
    if (v.startsWith("#") && (v.length == 4 || v.length == 5)) {
        val hex = v.substring(1)
        val expanded = hex.map { "$it$it" }.joinToString("")
        return runCatching { Color(android.graphics.Color.parseColor("#$expanded")) }.getOrNull()
    }
    return runCatching { Color(android.graphics.Color.parseColor(v)) }.getOrNull()
}

/** 级别中文标签(抽屉显示与复制共用) */
@Composable
private fun consoleLevelLabel(level: ConsoleLevel): String = stringResource(
    when (level) {
        ConsoleLevel.ERROR -> R.string.console_level_error
        ConsoleLevel.WARN -> R.string.console_level_warn
        ConsoleLevel.LOG -> R.string.console_level_log
        ConsoleLevel.INFO -> R.string.console_level_info
        ConsoleLevel.DEBUG -> R.string.console_level_debug
    }
)
