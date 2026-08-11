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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.render.ConsoleLevel
import xyz.normalwindow.htmlviewer.render.UserAgentPreset

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

    /** 格式化单条日志(复制用,级别用英文名) */
    fun formatEntry(entry: ConsoleEntry): String = buildString {
        append("[").append(entry.level.name).append("] ").append(entry.message)
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
                                            Badge(
                                                containerColor = consoleLevelColor(peak),
                                                // 数字用白色:在红/橙/蓝等彩色圆点上保持可读
                                                contentColor = Color.White
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
                                            text = { Text(preset.displayName) },
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

/** 控制台级别颜色(icon 圆点与日志行共用;ERROR 最严重,优先级最高) */
@Composable
private fun consoleLevelColor(level: ConsoleLevel): Color = when (level) {
    ConsoleLevel.ERROR -> MaterialTheme.colorScheme.error
    ConsoleLevel.WARN -> Color(0xFFF57C00)
    ConsoleLevel.INFO -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 控制台单条日志行(按级别着色;长按复制单条) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleEntryRow(entry: ConsoleEntry, onCopy: (ConsoleEntry) -> Unit) {
    val label = consoleLevelLabel(entry.level)
    val labelColor = consoleLevelColor(entry.level)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { onCopy(entry) }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.width(44.dp)
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
        if (!entry.source.isNullOrBlank()) {
            Text(
                text = "${entry.source}:${entry.lineNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 44.dp)
            )
        }
    }
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
