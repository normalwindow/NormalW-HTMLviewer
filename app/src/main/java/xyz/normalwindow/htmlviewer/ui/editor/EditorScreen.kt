package xyz.normalwindow.htmlviewer.ui.editor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import android.net.Uri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import xyz.normalwindow.htmlviewer.render.RendererCallbacks
import xyz.normalwindow.htmlviewer.render.RendererFactory
import java.io.File

/**
 * 代码编辑器页:CodeMirror 6(WebView 内)编辑 HTML,
 * 支持自动/手动保存、查找替换、编码显示、全屏预览入口。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    path: String,
    name: String,
    darkTheme: Boolean,
    onBack: () -> Unit,
    onOpenPreview: (String, String) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showSaveDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSplitPreview by remember { mutableStateOf(false) }

    // 编辑器 WebView 引用(供保存时拉取内容)
    val editorWebView = remember { mutableStateOf<WebView?>(null) }
    val initialized = remember { mutableStateOf(false) }
    val pageReady = remember { mutableStateOf(false) }
    val loadFailed = remember { mutableStateOf(false) }
    // 初始内容是否已完整载入(分片加载未完成时禁止保存,防止部分内容覆盖文件)
    var contentLoaded by remember { mutableStateOf(false) }
    var savingNow by remember { mutableStateOf(false) }
    // 当前保存的写盘完成回调(桥 saveCommit 写盘完成后调用,驱动"保存并退出")
    var pendingSaveDone by remember { mutableStateOf<(() -> Unit)?>(null) }
    // JavascriptInterface 回调在 WebView JavaBridge 后台线程执行,统一切回主线程
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // WebView 重建纪元(渲染进程崩溃时 +1 强制重建,避免白屏)
    var webViewEpoch by remember { mutableStateOf(0) }
    // 加载失败可见覆盖层(替代静默白屏,含重试按钮)
    var showLoadError by remember { mutableStateOf(false) }
    // 加载失败具体原因(展示在覆盖层,便于定位/反馈)
    var loadErrorDetail by remember { mutableStateOf("") }
    // 渲染进程崩溃节流:短时间内重复崩溃停止自动重建,避免死循环
    var lastRenderGoneAt by remember { mutableStateOf(0L) }

    // 最新主题值:onLoadFailed 等一次性闭包中读取到最新值(避免陈旧 darkTheme)
    val latestDarkTheme by rememberUpdatedState(darkTheme)

    // 编辑器深色模式:应用/系统主题变化时实时切换 CodeMirror 主题与页面背景。
    // 直接调用不比较(幂等);初始化前的变化由 initEditor 的 latestDarkTheme 兜底
    LaunchedEffect(darkTheme) {
        if (initialized.value) {
            executeEditorJs(editorWebView.value, "HVEditor.setDark($darkTheme)")
        }
    }

    // 自定义滚动条显示开关:初始化前的变化由 initEditor 的 opts.scrollbar 兜底
    LaunchedEffect(state.scrollbar) {
        if (initialized.value) {
            executeEditorJs(editorWebView.value, "HVEditor.setScrollbar(${state.scrollbar})")
        }
    }

    // 状态栏字体颜色跟随编辑器主题:深色主题 → 浅色图标(否则深色工具栏上看不清)。
    // 退出时无需恢复——MainActivity 全局按应用主题管理,恢复逻辑此前读取
    // Activity 缓存的 configuration 会得到错误值(黑字),已移除
    val statusBarActivity = context as? Activity
    val statusBarWindow = statusBarActivity?.window
    LaunchedEffect(darkTheme, statusBarWindow) {
        val window = statusBarWindow ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !darkTheme
    }

    // 保存事件:拉取 JS 内容 → 回写 VM(编辑器未就绪时忽略,避免空内容覆盖文件)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is EditorEvent.Snackbar -> {
                    val msg = context.getString(
                        when (event.kind) {
                            EditorSnack.SAVED -> R.string.snack_saved
                            EditorSnack.SAVING_FAILED -> R.string.snack_error_save
                            EditorSnack.LOAD_FAILED -> R.string.snack_error_io
                            EditorSnack.CONVERTED_UTF8 -> R.string.snack_converted_utf8
                            EditorSnack.MISSING_FILE -> R.string.snack_missing_file
                            EditorSnack.FORMATTED -> R.string.snack_formatted
                            EditorSnack.FORMAT_FAILED -> R.string.snack_format_failed
                            EditorSnack.UPLOADED -> R.string.editor_snack_uploaded
                            EditorSnack.UPLOAD_FAILED -> R.string.editor_snack_upload_failed
                        }
                    )
                    snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
                }
                EditorEvent.RequestSave -> {
                    if (initialized.value && contentLoaded) {
                        // 分块推送保存:大文件经 evaluateJavascript 返回会超 Binder 限制
                        editorWebView.value?.evaluateJavascript("HVEditor.saveContent()", null)
                    }
                }
            }
        }
    }

    fun doBack() {
        if (state.dirty) showSaveDialog = true else onBack()
    }

    /** 拉取编辑器内容并保存(用于"保存并退出"等需要确认落盘的场景) */
    fun saveNow(thenBack: Boolean) {
        if (savingNow) return // 保存进行中,忽略重复触发(防双重导航)
        // 初始内容未完整载入时禁止保存(避免部分内容覆盖文件)
        if (!initialized.value || !contentLoaded) {
            if (thenBack) onBack()
            return
        }
        savingNow = true
        var finished = false
        // 异常路径(超时/WebView 已销毁)置位:即使迟到写盘完成也不再退出
        var timedOut = false
        fun finish() {
            if (finished) return
            finished = true
            savingNow = false
            pendingSaveDone = null
            if (thenBack && !timedOut) onBack()
        }
        // 身份守卫:超时协程只处理属于自己的保存(失败重试后旧超时不得
        // 清除新保存的回调/重复提示)
        val myDone = { finish() }
        pendingSaveDone = myDone
        // 兜底:JS 回调可能永不触发(WebView 已销毁等),超时后提示并恢复,不自动退出
        scope.launch {
            delay(SAVE_CALLBACK_TIMEOUT_MS)
            if (pendingSaveDone === myDone) {
                timedOut = true
                finish() // 先恢复保存能力,再提示(避免被 snackbar 时长阻塞)
                snackbarHostState.showSnackbar(
                    context.getString(R.string.snack_error_save),
                    duration = SnackbarDuration.Long
                )
            }
        }
        // 分块推送保存:大文件经 evaluateJavascript 返回会超 Binder 限制
        val ok = runCatching {
            editorWebView.value?.evaluateJavascript("HVEditor.saveContent()", null)
        }
        if (ok.isFailure) {
            timedOut = true
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.snack_error_save),
                    duration = SnackbarDuration.Long
                )
            }
            finish()
        }
    }

    androidx.activity.compose.BackHandler(onBack = ::doBack)

    // 加载文件
    LaunchedEffect(Unit) {
        vm.load(path, name)
    }

    // 内容传输失败重试计数(上限 3 次,防止失败时无限重试)
    val loadRetryCount = remember { mutableStateOf(0) }

    // 初始化 CodeMirror:先建空编辑器立即渲染界面,再分块拉取内容(大文件安全,失败可重试)
    fun initEditor() {
        val webView = editorWebView.value ?: return
        if (initialized.value) return
        val s = vm.state.value
        xyz.normalwindow.htmlviewer.data.debug.AppLog.d(
            "Editor",
            "initEditor: 内容长度=${s.editorContent.length} 重试=${loadRetryCount.value}"
        )
        val opts = JSONObject()
            .put("fontSize", s.fontSize)
            .put("tabSize", s.tabSize)
            .put("wrap", s.wrap)
            .put("dark", latestDarkTheme)
            .put("scrollbar", s.scrollbar)
        initialized.value = true
        contentLoaded = false
        // 合并为一条 evaluateJavascript:两条异步消息之间 init 与 loadContent
        // 存在竞态(loadContent 先执行时 view 未就绪会静默跳过,内容永不加载)
        webView.evaluateJavascript("HVEditor.init($opts); HVEditor.loadContent();", null)
    }

    // 重建编辑器 WebView 并重试(渲染进程崩溃/加载失败后的兜底路径)
    fun retryLoad() {
        showLoadError = false
        loadErrorDetail = ""
        loadFailed.value = false
        initialized.value = false
        pageReady.value = false
        contentLoaded = false
        loadRetryCount.value = 0
        webViewEpoch += 1 // 触发 AndroidView 重建,重新加载 editor.html
    }

    // 编辑器初始化:文件加载完成 && 页面就绪后注入内容(两种时序均覆盖)
    LaunchedEffect(state.readyForInit, pageReady.value) {
        if (state.readyForInit && pageReady.value) initEditor()
    }

    // WebView 释放(每个调用单独兜底:渲染进程重建时旧 WebView 可能已销毁)
    DisposableEffect(Unit) {
        onDispose {
            editorWebView.value?.let { wv ->
                runCatching { wv.stopLoading() }
                runCatching { wv.removeJavascriptInterface("HVBridge") }
                runCatching { wv.destroy() }
            }
        }
    }

    // 物理键盘快捷键:Ctrl+S 保存 / Ctrl+F 查找 / Esc 返回
    val keyboardShortcuts = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
            when (event.key) {
                Key.S -> {
                    vm.requestManualSave()
                    true
                }
                Key.F -> {
                    executeEditorJs(editorWebView.value, "HVEditor.openSearch()")
                    true
                }
                else -> false
            }
        } else {
            false
        }
    }

    // 分屏预览渲染器(跟随内核设置)
    val previewFactory = remember { RendererFactory(context.applicationContext) }
    val previewCallbacks = remember {
        object : RendererCallbacks {
            override fun onPageStarted(url: String?) {}
            override fun onPageFinished(url: String?) {}
            override fun onPageError(description: String?) {}
        }
    }
    val previewRenderer = remember(state.engine) {
        previewFactory.create(state.engine, previewCallbacks)
    }
    DisposableEffect(previewRenderer) {
        onDispose { previewRenderer.destroy() }
    }

    // 分屏预览:编辑变化防抖刷新(编辑即所见)
    LaunchedEffect(state.engine, state.dirty, showSplitPreview) {
        if (showSplitPreview && state.dirty) {
            delay(700)
            editorWebView.value?.evaluateJavascript("HVEditor.getContent()") { result ->
                val content = decodeJsString(result)
                val baseUrl = File(state.path).parentFile?.let { Uri.fromFile(it).toString() + "/" }
                // 页面退出后渲染器可能已销毁,忽略迟到的刷新(防崩溃)
                runCatching { previewRenderer.loadHtml(content, baseUrl) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.fileName.ifBlank { stringResource(R.string.app_name) }, maxLines = 1)
                        if (state.dirty) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::doBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSplitPreview = !showSplitPreview }) {
                        Icon(
                            Icons.Filled.VerticalSplit,
                            contentDescription = stringResource(R.string.action_split_preview),
                            tint = if (showSplitPreview) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { executeEditorJs(editorWebView.value, "HVEditor.openSearch()") }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_find))
                    }
                    IconButton(onClick = {
                        onOpenPreview(state.path, state.fileName)
                    }) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = stringResource(R.string.action_preview))
                    }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_undo)) },
                            onClick = {
                                showMoreMenu = false
                                executeEditorJs(editorWebView.value, "HVEditor.undo()")
                            },
                            leadingIcon = { Icon(Icons.Filled.Undo, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_redo)) },
                            onClick = {
                                showMoreMenu = false
                                executeEditorJs(editorWebView.value, "HVEditor.redo()")
                            },
                            leadingIcon = { Icon(Icons.Filled.Redo, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_auto_save)) },
                            onClick = { vm.setAutoSave(!state.autoSave) },
                            trailingIcon = {
                                Switch(checked = state.autoSave, onCheckedChange = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_scrollbar)) },
                            onClick = { vm.setScrollbar(!state.scrollbar) },
                            trailingIcon = {
                                Switch(checked = state.scrollbar, onCheckedChange = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_format)) },
                            onClick = {
                                showMoreMenu = false
                                val lang = when (state.fileName.substringAfterLast('.', "").lowercase()) {
                                    "css" -> "css"
                                    "js", "mjs", "cjs", "ts" -> "js"
                                    else -> "html"
                                }
                                editorWebView.value?.evaluateJavascript(
                                    "HVEditor.format('$lang').then(r => HVBridge.onFormatDone(r))",
                                    null
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.FormatAlignLeft, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_convert_utf8)) },
                            enabled = state.encoding != TextEncoding.UTF_8,
                            onClick = {
                                showMoreMenu = false
                                editorWebView.value?.evaluateJavascript("HVEditor.getContent()") { result ->
                                    vm.convertToUtf8(decodeJsString(result))
                                }
                            },
                            leadingIcon = { Icon(Icons.Outlined.Save, null) }
                        )
                    }
                }
            )
        },
        bottomBar = {
            EditorStatusBar(
                line = state.cursorLine,
                col = state.cursorCol,
                encoding = state.encoding,
                autoSave = state.autoSave
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(keyboardShortcuts)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                // key(webViewEpoch):渲染进程崩溃/重试时重建 WebView
                key(webViewEpoch) {
                AndroidView(
                    factory = { ctx ->
                        object : WebView(ctx) {
                            // ---- 触摸滚动兜底 ----
                            // JS 触摸事件缺失(部分 WebView/ROM 吞掉 contenteditable
                            // 的触摸派发)时,由本层直接注入 HVEditor.scrollBy 驱动
                            // .cm-scroller 滚动,保证编辑模式一定能上下滑动。
                            // 防双重滚动:每个手势 DOWN 时异步查询 JS 侧
                            // window.__hvTouchSeen(触摸事件是否到达)——JS 接管
                            // 有效时绝不注入;仅事件缺失时接管。
                            // 轻点(位移<touchSlop)不注入,光标定位/文本选择
                            // 仍走 super.onTouchEvent 交给 WebView。
                            private var fbLastX = 0f
                            private var fbLastY = 0f
                            private var fbStartX = 0f
                            private var fbStartY = 0f
                            private var fbActive = false      // 本手势已由兜底接管
                            private var fbChecked = false     // __hvTouchSeen 查询已返回
                            private var fbJsSeen = false      // JS 触摸事件可见
                            private var fbLastInject = 0L     // 注入节流(每帧一次)
                            private var fbPrevPointers = 0    // 上一帧触点数(多指恢复判定)
                            private val fbSlop = ViewConfiguration.get(ctx).scaledTouchSlop

                            override fun onTouchEvent(event: MotionEvent): Boolean {
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        fbStartX = event.x
                                        fbStartY = event.y
                                        fbLastX = event.x
                                        fbLastY = event.y
                                        fbActive = false
                                        fbChecked = false
                                        fbJsSeen = false
                                        fbPrevPointers = 1
                                        // 延迟查询:等渲染进程把本手势的 touchstart
                                        // 派发完(置 __hvTouchSeen)再读,避免查询先于
                                        // touchstart 执行的竞态导致 JS 接管与注入
                                        // 同时滚动(双重滚动)。查询返回前不注入。
                                        postDelayed({
                                            runCatching {
                                                evaluateJavascript("window.__hvTouchSeen === true") { r ->
                                                    // 回调在 UI 线程执行,与 onTouchEvent 同线程
                                                    fbChecked = true
                                                    fbJsSeen = r == "true"
                                                }
                                            }
                                        }, TOUCH_SEEN_QUERY_DELAY_MS)
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        // 单指手势才兜底(双指缩放/多指交还给 WebView)
                                        if (event.pointerCount == 1) {
                                            // 从多指恢复单指:丢弃本帧位移(仅同步坐标),
                                            // 避免缩放期间的位置差被一次性注入(内容跳跃)
                                            if (fbPrevPointers != 1) {
                                                fbLastX = event.x
                                                fbLastY = event.y
                                            }
                                            // last 坐标无条件更新:查询回调未返回期间
                                            // 的位移也不丢失、不积压(注入恒为增量)
                                            val dx = event.x - fbLastX
                                            val dy = event.y - fbLastY
                                            fbLastX = event.x
                                            fbLastY = event.y
                                            if (fbChecked && !fbJsSeen) {
                                                if (!fbActive) {
                                                    // 相对手势起点的累计位移,防轻点抖动
                                                    val dist = Math.hypot(
                                                        (event.x - fbStartX).toDouble(),
                                                        (event.y - fbStartY).toDouble()
                                                    )
                                                    if (dist < fbSlop * 2) return super.onTouchEvent(event)
                                                    fbActive = true
                                                }
                                                val now = SystemClock.uptimeMillis()
                                                if (now - fbLastInject >= 16) {
                                                    fbLastInject = now
                                                    // 方向与 JS 接管一致:手指上滑(y 减小)
                                                    // → scrollTop 增加(内容下移)
                                                    evaluateJavascript(
                                                        "HVEditor.scrollBy(${-dy}, ${-dx})",
                                                        null
                                                    )
                                                }
                                            }
                                        }
                                        fbPrevPointers = event.pointerCount
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        fbActive = false
                                    }
                                }
                                return super.onTouchEvent(event)
                            }
                        }.apply {
                            // 使用默认不透明背景,避免透明合成导致 CodeMirror 白屏
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false
                            settings.allowFileAccess = true
                            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                            // 固定 textZoom=100:系统字体缩放会破坏 CodeMirror 的
                            // 行高/视口测量(表现为滚动后内容不渲染/文字错位)
                            settings.textZoom = 100
                            // 开启双指缩放(CM6 视口重测由 JS 侧 visualViewport 监听兜底);
                            // 注:API 36 已移除 WebView.setSupportZoom,缩放设置走 WebSettings;
                            // 不使用 LAYER_TYPE_SOFTWARE——v1.4.4 证实"文字不可见"
                            // 真因是 onContentLoaded 递归崩溃而非合成 bug,软渲染只拖慢滚动
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            // 禁用 WebView 自动深色:避免与 CodeMirror 主题叠加导致白底白字/文字不可见
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                WebSettingsCompat.setForceDark(
                                    settings, WebSettingsCompat.FORCE_DARK_OFF
                                )
                            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                                @Suppress("DEPRECATION")
                                settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                            }
                            addJavascriptInterface(
                                EditorJsBridge(
                                    vm,
                                    onLoadFailed = { msg ->
                                        // JavaBridge 后台线程回调,切主线程再操作 Compose 状态
                                        mainHandler.post {
                                            loadErrorDetail = msg
                                            // 内容已完整加载(用户已看到代码)后的迟到失败回调:
                                            // 不再重建/弹错误——偶发的桥调用失败不应打扰已就绪的编辑器
                                            if (contentLoaded) return@post
                                            // 内容传输失败:有限重试(最多 3 次),仍失败显示错误覆盖层
                                            if (loadRetryCount.value < 3) {
                                                loadRetryCount.value += 1
                                                initialized.value = false
                                                contentLoaded = false
                                                initEditor()
                                            } else {
                                                // 分块拉取通道反复失败:降级为一次性注入(中小文件),
                                                // 避免"明明有内容却报加载失败"。注入成功后由 JS 的
                                                // onContentLoaded 回调置位;大文件或注入无效才显示覆盖层。
                                                val content = vm.state.value.editorContent
                                                val webView = editorWebView.value
                                                val json = runCatching {
                                                    if (content.isNotEmpty() &&
                                                        content.length <= DEGRADE_INJECT_LIMIT
                                                    ) org.json.JSONObject.quote(content) else null
                                                }.getOrNull()
                                                if (webView != null && json != null) {
                                                    webView.evaluateJavascript(
                                                        "HVEditor.setContentChunked($json)",
                                                        null
                                                    )
                                                    // 延迟确认:注入成功会触发 onContentLoaded,
                                                    // 未触发(如 view 未就绪)则显示覆盖层
                                                    mainHandler.postDelayed({
                                                        if (!contentLoaded) {
                                                            showLoadError = true
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    context.getString(R.string.snack_editor_load_failed)
                                                                )
                                                            }
                                                        }
                                                    }, DEGRADE_CONFIRM_MS)
                                                } else {
                                                    showLoadError = true
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            context.getString(R.string.snack_editor_load_failed)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onContentLoadedCallback = { mainHandler.post { contentLoaded = true } },
                                    // 桥回调在 JavaBridge 后台线程,必须切主线程再操作 Compose/导航
                                    onSaveDone = { mainHandler.post { pendingSaveDone?.invoke() } },
                                    onSaveFailed = {
                                        // 传输不完整:提示已由 VM 发出,恢复保存能力但不退出页面
                                        mainHandler.post {
                                            pendingSaveDone = null
                                            savingNow = false
                                        }
                                    }
                                ),
                                "HVBridge"
                            )
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    xyz.normalwindow.htmlviewer.data.debug.AppLog.d("Editor", "onPageFinished: $url 失败标记=${loadFailed.value}")
                                    // 主框架加载失败时 error 页面也会回调 onPageFinished,不能置就绪
                                    if (!loadFailed.value) pageReady.value = true
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?
                                ) {
                                    // 主动中断(重建/销毁时的 stopLoading)不是真实失败,忽略
                                    if (error?.errorCode == ERROR_ABORTED_CODE) return
                                    xyz.normalwindow.htmlviewer.data.debug.AppLog.d(
                                        "Editor",
                                        "onReceivedError: ${error?.errorCode} ${error?.description} ${request?.url} 主框架=${request?.isForMainFrame}"
                                    )
                                    if (request?.isForMainFrame == true) {
                                        loadErrorDetail = error?.description?.toString() ?: ""
                                        loadFailed.value = true
                                        pageReady.value = false // 兜底:回调顺序不保证时也阻止初始化
                                        showLoadError = true
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.snack_editor_load_failed)
                                            )
                                        }
                                    }
                                }

                                @Suppress("DEPRECATION")
                                override fun onReceivedError(
                                    view: WebView,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?
                                ) {
                                    // 旧签名:部分错误(如 file 协议)仍只走此回调,不重写会漏报主框架失败
                                    if (errorCode == ERROR_ABORTED_CODE) return
                                    xyz.normalwindow.htmlviewer.data.debug.AppLog.d(
                                        "Editor",
                                        "onReceivedError(legacy): code=$errorCode desc=$description url=$failingUrl"
                                    )
                                    if (loadFailed.value) return // 新签名已处理,避免重复提示
                                    if (failingUrl == null) {
                                        // 无 URL 的内部错误(JS 执行异常等,描述常为
                                        // "Java exception was raised...")不是主框架加载失败,
                                        // 只记录日志,不弹覆盖层(此前误判导致"已加载成功却报失败")
                                        xyz.normalwindow.htmlviewer.data.debug.AppLog.force(
                                            "Editor",
                                            "收到无 URL 错误(不弹覆盖层): $description"
                                        )
                                        return
                                    }
                                    if (failingUrl.startsWith("file:///android_asset/editor/editor.html")) {
                                        loadErrorDetail = "$description ($failingUrl)"
                                        loadFailed.value = true
                                        pageReady.value = false
                                        showLoadError = true
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.snack_editor_load_failed)
                                            )
                                        }
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView,
                                    detail: android.webkit.RenderProcessGoneDetail
                                ): Boolean {
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    loadErrorDetail = if (detail.didCrash()) "渲染进程崩溃(内存不足?)" else "渲染进程被系统回收"
                                    xyz.normalwindow.htmlviewer.data.debug.AppLog.force(
                                        "Editor",
                                        "onRenderProcessGone: crash=${detail.didCrash()} 距上次=${now - lastRenderGoneAt}ms"
                                    )
                                    if (now - lastRenderGoneAt < 30_000L) {
                                        // 短时间内再次崩溃:停止自动重建,显示错误覆盖层等用户重试
                                        initialized.value = false
                                        showLoadError = true
                                        return true
                                    }
                                    lastRenderGoneAt = now
                                    // 自动重建:清除失败标记,让新 WebView 的 onPageFinished 可触发初始化
                                    loadFailed.value = false
                                    pageReady.value = false
                                    initialized.value = false
                                    webViewEpoch += 1
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.snack_editor_load_failed)
                                        )
                                    }
                                    return true // 已处理,避免应用崩溃
                                }
                            }
                            loadUrl("file:///android_asset/editor/editor.html")
                            editorWebView.value = this
                        }
                    },
                    // 重建(epoch 变化)/离开组合时销毁旧 WebView,避免渲染进程泄漏
                    onRelease = { view -> runCatching { (view as? WebView)?.destroy() } },
                    modifier = Modifier.fillMaxSize()
                )

                // 加载失败覆盖层:替代静默白屏,提供可见错误与重试
                if (showLoadError) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.snack_editor_load_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (loadErrorDetail.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = loadErrorDetail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { retryLoad() }) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
                }
            }

            // 分屏预览窗格(底部 45%)
            AnimatedVisibility(
                visible = showSplitPreview,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.45f)
                ) {
                    AndroidView(
                        factory = { previewRenderer.view },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // 未保存退出确认
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.dialog_unsaved_title)) },
            text = { Text(stringResource(R.string.dialog_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    saveNow(thenBack = true)
                }) {
                    Text(stringResource(R.string.action_save_and_exit))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    onBack()
                }) {
                    Text(stringResource(R.string.action_discard))
                }
            }
        )
    }
}

/** Android ↔ CodeMirror JS 桥 */
private class EditorJsBridge(
    private val vm: EditorViewModel,
    /** 内容传输失败时通知 UI(显示提示并重置,允许重试) */
    private val onLoadFailed: (String) -> Unit,
    /** 初始内容完整载入时通知 UI(解锁保存);命名避开成员函数 onContentLoaded,
     *  否则方法体内调用会解析为自身导致无限递归(StackOverflowError →
     *  "Java exception was raised during method invocation") */
    private val onContentLoadedCallback: () -> Unit,
    /** 保存写盘完成(成功或失败)后通知 UI;注意:在 JavaBridge 后台线程执行 */
    private val onSaveDone: () -> Unit,
    /** 保存内容传输失败后通知 UI(恢复保存能力但不退出);JavaBridge 后台线程 */
    private val onSaveFailed: () -> Unit
) {
    /** 分块保存收集缓冲(大文件经 evaluateJavascript 返回会超 Binder 限制) */
    private var saveBuffer: StringBuilder? = null
    private var saveExpected = -1

    /** 桥方法异常统一处理:记录日志供诊断,并按方法语义安全降级 */
    private fun logBridgeError(method: String, e: Exception) {
        android.util.Log.e(TAG, "$method failed", e)
        xyz.normalwindow.htmlviewer.data.debug.AppLog.force(TAG, "$method failed: $e")
    }

    private companion object {
        const val TAG = "HVEditorBridge"
    }

    @JavascriptInterface
    fun saveBegin(total: Int) {
        try {
            if (total > MAX_SAVE_BYTES) {
                // 超限文档:直接失败,避免每次保存都命中长度校验永久失败
                saveBuffer = null
                saveExpected = -1
                vm.onSaveChunkError()
                onSaveFailed()
                return
            }
            saveBuffer = StringBuilder(total)
            saveExpected = total
        } catch (e: Exception) {
            logBridgeError("saveBegin", e)
            saveBuffer = null
            saveExpected = -1
            vm.onSaveChunkError()
            onSaveFailed()
        }
    }

    @JavascriptInterface
    fun saveChunk(offset: Int, part: String) {
        try {
            val buf = saveBuffer ?: return
            if (buf.length == offset) buf.append(part)
            // offset 不连续(乱序/重复)时丢弃本次保存,由 saveCommit 长度校验拦截
        } catch (e: Exception) {
            logBridgeError("saveChunk", e)
            saveBuffer = null // 本次保存作废,由 saveCommit 长度校验拦截
        }
    }

    @JavascriptInterface
    fun saveCommit() {
        try {
            val buf = saveBuffer ?: return
            saveBuffer = null
            if (saveExpected >= 0 && buf.length == saveExpected) {
                // 写盘完成回调(成功或失败)驱动 UI 的 finish;vm.saveWithContent 的
                // onDone 在 viewModelScope(主线程)执行,线程安全
                vm.saveWithContent(buf.toString()) { onSaveDone() }
            } else {
                // 长度不符:内容传输不完整,拒绝覆盖文件;提示并留在页面(不退出)
                vm.onSaveChunkError()
                onSaveFailed()
            }
        } catch (e: Exception) {
            logBridgeError("saveCommit", e)
            vm.onSaveChunkError()
            onSaveFailed()
        }
    }
    @JavascriptInterface
    fun onEditorReady() {
        try {
            vm.onEditorReady()
        } catch (e: Exception) {
            logBridgeError("onEditorReady", e)
        }
    }

    @JavascriptInterface
    fun onEditorChanged() {
        // 必须兜底:此方法在 CM6 每次变更的 updateListener 中同步调用,
        // 抛异常会中断 CM6 的 dispatch(表现为加载中断/编辑器卡死)
        try {
            vm.onEditorChanged()
        } catch (e: Exception) {
            logBridgeError("onEditorChanged", e)
        }
    }

    @JavascriptInterface
    fun onCursorChanged(line: Int, col: Int) {
        try {
            vm.onCursorChanged(line, col)
        } catch (e: Exception) {
            logBridgeError("onCursorChanged", e)
        }
    }

    /** 初始内容由 JS 侧分块拉取:总量 + 分块,每块远小于 Binder 传输上限,大文件安全 */
    @JavascriptInterface
    fun getContentSize(): Int {
        return try {
            vm.state.value.editorContent.length
        } catch (e: Exception) {
            // 失败返回 -1:JS 侧识别为失败进入重试(返回 0 会被当成空文件)
            logBridgeError("getContentSize", e)
            -1
        }
    }

    /**
     * 内容分块(代理对安全)。失败返回 null 由 JS 侧重试:
     * Binder 传输偶发失败(TransactionTooLarge)时不能返回空串
     * (JS 拉取循环会死循环),也不能抛异常(JS 端无法区分错误类型)。
     */
    @JavascriptInterface
    fun getContentChunk(offset: Int, len: Int): String? {
        return try {
            val content = vm.state.value.editorContent
            if (offset < 0 || len <= 0 || offset >= content.length) return ""
            var to = minOf(offset + len, content.length)
            // 代理对安全:末尾若切在 UTF-16 代理对中间则并入后半(孤立代理项经
            // JSON 序列化可能损坏)。起始处不调整——JS 侧按返回长度推进,
            // 偏移永远落在完整代理对边界,不会出现重叠/遗漏。
            if (to < content.length && Character.isHighSurrogate(content[to - 1])) to += 1
            if (to <= offset) return ""
            content.substring(offset, to)
        } catch (e: Exception) {
            logBridgeError("getContentChunk", e)
            null // JS 侧收到 null 会抛错进入重试机制
        }
    }

    /** 格式化结果:true=成功,否则为错误信息字符串 */
    @JavascriptInterface
    fun onFormatDone(result: String) {
        try {
            vm.onFormatResult(result == "true", result)
        } catch (e: Exception) {
            logBridgeError("onFormatDone", e)
        }
    }

    /** 内容填充完成(可用于消除加载态;同时清除分片加载产生的脏标记) */
    @JavascriptInterface
    fun onContentLoaded() {
        try {
            vm.onContentLoaded()
        } catch (e: Exception) {
            logBridgeError("onContentLoaded", e)
        }
        // 此前此处调用同名成员导致无限递归(StackOverflowError 未被
        // catch(Exception) 捕获,传播到 JS 报 "Java exception was raised...")
        onContentLoadedCallback()
    }

    @JavascriptInterface
    fun onContentLoadError(msg: String) {
        // 完整错误(含调用栈)记日志取证;覆盖层只显示首行(栈信息过长)
        android.util.Log.e(TAG, "JS content load error: $msg")
        xyz.normalwindow.htmlviewer.data.debug.AppLog.force(TAG, "JS content load error: $msg")
        val firstLine = msg.lineSequence().firstOrNull()?.take(200) ?: msg
        onLoadFailed(firstLine)
    }

    /** JS 侧初始化诊断(白屏排查:CM6 状态/尺寸上报,由 init 后定时调用) */
    @JavascriptInterface
    fun onDiag(message: String) {
        xyz.normalwindow.htmlviewer.data.debug.AppLog.force(TAG, "diag: $message")
    }
}

private fun executeEditorJs(webView: WebView?, script: String) {
    webView?.evaluateJavascript(script, null)
}

/** 分块保存上限(64MB,防御异常文档) */
private const val MAX_SAVE_BYTES = 64 * 1024 * 1024

/** 保存回调超时兜底:JS/写盘回调不触发(WebView 已销毁等)时恢复保存能力;大文件写盘留足时间 */
private const val SAVE_CALLBACK_TIMEOUT_MS = 10_000L

/**
 * 触摸兜底查询延迟(ms):ACTION_DOWN 后等渲染进程派发完 touchstart
 * (JS 侧置 window.__hvTouchSeen)再查询,消除"查询先于 touchstart"
 * 的竞态——否则 JS 接管与 Kotlin 注入会双重滚动
 */
private const val TOUCH_SEEN_QUERY_DELAY_MS = 50L

/**
 * 主动中断错误码(WebViewClient 无公共常量):重建/销毁时 stopLoading
 * 触发的中断不是真实加载失败,不应显示错误覆盖层
 */
private const val ERROR_ABORTED_CODE = -2

/**
 * 降级注入上限(字符数):分块拉取反复失败时,通过 evaluateJavascript
 * 一次性传入内容——JSON 转义膨胀后仍须低于 Binder 事务上限(约 1MB)
 */
private const val DEGRADE_INJECT_LIMIT = 700_000

/** 降级注入确认等待:注入成功会触发 onContentLoaded,未触发则显示覆盖层 */
private const val DEGRADE_CONFIRM_MS = 800L

/** evaluateJavascript 返回带引号 JSON 字符串,还原为原始文本 */
internal fun decodeJsString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return runCatching {
        JSONTokener(raw).nextValue().toString()
    }.getOrDefault(raw.trim('"'))
}

@Composable
private fun EditorStatusBar(
    line: Int,
    col: Int,
    encoding: String,
    autoSave: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$line:$col",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = encoding,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (autoSave) stringResource(R.string.status_autosave_on)
            else stringResource(R.string.status_autosave_off),
            style = MaterialTheme.typography.labelMedium,
            color = if (autoSave) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
