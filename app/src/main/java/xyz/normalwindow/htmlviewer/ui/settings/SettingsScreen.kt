package xyz.normalwindow.htmlviewer.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.normalwindow.htmlviewer.BuildConfig
import xyz.normalwindow.htmlviewer.HTMLViewerApp
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.cloud.CloudProviderType
import xyz.normalwindow.htmlviewer.data.cloud.SyncConflictPolicy
import xyz.normalwindow.htmlviewer.data.cloud.SyncUiState
import xyz.normalwindow.htmlviewer.data.settings.AppLanguage
import xyz.normalwindow.htmlviewer.data.settings.ColorStyle
import xyz.normalwindow.htmlviewer.data.settings.EngineType
import xyz.normalwindow.htmlviewer.data.settings.ThemeMode
import xyz.normalwindow.htmlviewer.ui.cloud.BaiduAuthDialog
import xyz.normalwindow.htmlviewer.ui.cloud.ConflictDialog
import xyz.normalwindow.htmlviewer.ui.cloud.RemoteDirPickerDialog
import xyz.normalwindow.htmlviewer.ui.cloud.SyncProgressDialog
import xyz.normalwindow.htmlviewer.ui.cloud.SyncResultDialog
import xyz.normalwindow.htmlviewer.ui.components.RightAlignedMenu
import xyz.normalwindow.htmlviewer.ui.components.uaPresetLabel
import xyz.normalwindow.htmlviewer.render.UserAgentPreset

/** 设置中心:内核选择 / 外观 / 编辑器 / 浏览器与预览 / 开发者 / 关于 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel(),
    onOpenAbout: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var uaMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 后台同步通知权限(Android 13+,隐藏进度对话框时请求)
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /**
     * 切换界面语言并即时生效:
     * 先同步更新 Application 内存缓存(attachBaseContext 同步读取),再异步持久化,
     * 等下拉菜单收起动画播完再重建 Activity,避免旧界面(含菜单)在窗口过渡中残留。
     */
    fun switchLanguage(lang: AppLanguage) {
        (context.applicationContext as? HTMLViewerApp)?.currentLanguage = lang
        vm.setLanguage(lang)
        scope.launch {
            delay(300)
            val activity = context as? Activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                activity.recreate()
            }
        }
    }

    // 日志导出完成后启动系统分享
    val exportFile by vm.exportFile.collectAsStateWithLifecycle()
    LaunchedEffect(exportFile) {
        exportFile?.let { file ->
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_export_logs))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.settings_export_logs)))
            }
            vm.consumeExportFile()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ---------- 渲染内核 ----------
        SectionTitle(stringResource(R.string.settings_section_engine)) {

        ListItem(
            headlineContent = { Text(stringResource(R.string.engine_light)) },
            supportingContent = { Text(stringResource(R.string.engine_light_desc)) },
            leadingContent = {
                Icon(Icons.Filled.Language, contentDescription = null)
            },
            trailingContent = {
                RadioButton(
                    selected = state.defaultEngine == EngineType.WEBVIEW,
                    onClick = { vm.setDefaultEngine(EngineType.WEBVIEW) }
                )
            },
            modifier = Modifier.clickableRow {
                vm.setDefaultEngine(EngineType.WEBVIEW)
            }
        )
        // lite 变体未打包 GeckoView,隐藏兼容内核选项
        if (BuildConfig.GECKO_ENABLED) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.engine_gecko)) },
            supportingContent = { Text(stringResource(R.string.engine_gecko_desc)) },
            leadingContent = {
                Icon(Icons.Outlined.Storage, contentDescription = null)
            },
            trailingContent = {
                RadioButton(
                    selected = state.defaultEngine == EngineType.GECKO,
                    onClick = { vm.setDefaultEngine(EngineType.GECKO) }
                )
            },
            modifier = Modifier.clickableRow {
                vm.setDefaultEngine(EngineType.GECKO)
            }
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = stringResource(R.string.engine_gecko_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        }

        }
        // ---------- 外观 ----------
        SectionTitle(stringResource(R.string.settings_section_appearance)) {

        // 主题模式:浅色/跟随系统/深色 三选项一行展示
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            listOf(
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.DARK to R.string.theme_dark
            ).forEachIndexed { index, (mode, labelRes) ->
                SegmentedButton(
                    selected = state.themeMode == mode,
                    onClick = { vm.setThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                ) {
                    Text(stringResource(labelRes), maxLines = 1)
                }
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
            supportingContent = { Text(stringResource(R.string.settings_dynamic_color_desc)) },
            trailingContent = {
                Switch(checked = state.dynamicColor, onCheckedChange = vm::setDynamicColor)
            }
        )
        // 自定义主题色:预设色板(优先于动态取色)
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_custom_color)) },
            supportingContent = { Text(stringResource(R.string.settings_custom_color_desc)) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ColorSwatch(
                color = null,
                selected = state.customColorSeed == null,
                onClick = { vm.setCustomColorSeed(null) }
            )
            CUSTOM_COLOR_SEEDS.forEach { seed ->
                ColorSwatch(
                    color = seed,
                    selected = state.customColorSeed == seed,
                    onClick = { vm.setCustomColorSeed(seed) }
                )
            }
        }
        // 配色方案:8 种 Material3 色调方案(选中后整套配色随主题色变化)
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_color_scheme)) },
            supportingContent = { Text(stringResource(R.string.settings_color_scheme_desc)) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ColorStyle.entries.forEach { style ->
                FilterChip(
                    selected = state.colorStyle == style,
                    onClick = { vm.setColorStyle(style) },
                    label = {
                        Text(
                            if (style == ColorStyle.SYSTEM) {
                                stringResource(R.string.color_scheme_system)
                            } else {
                                style.displayName
                            }
                        )
                    }
                )
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_default_view)) },
            supportingContent = { Text(stringResource(R.string.settings_default_view_desc)) },
            trailingContent = {
                Switch(checked = state.gridView, onCheckedChange = vm::setGridView)
            }
        )
        // 语言选择(即时生效:切换后重建 Activity)
        // 语言选择(点击弹出右对齐菜单,切换后重建 Activity 即时生效)
        var languageMenu by remember { mutableStateOf(false) }
        var languageAnchorH by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { languageAnchorH = it.height }
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = {
                    Text(
                        when (state.language) {
                            AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                            AppLanguage.ZH -> "简体中文"
                            AppLanguage.EN -> "English"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Filled.Language, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableRow { languageMenu = true }
            )
            RightAlignedMenu(
                expanded = languageMenu,
                onDismissRequest = { languageMenu = false },
                anchorHeightPx = languageAnchorH
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.language_system)) },
                    onClick = {
                        languageMenu = false
                        switchLanguage(AppLanguage.SYSTEM)
                    },
                    trailingIcon = {
                        if (state.language == AppLanguage.SYSTEM) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("简体中文") },
                    onClick = {
                        languageMenu = false
                        switchLanguage(AppLanguage.ZH)
                    },
                    trailingIcon = {
                        if (state.language == AppLanguage.ZH) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("English") },
                    onClick = {
                        languageMenu = false
                        switchLanguage(AppLanguage.EN)
                    },
                    trailingIcon = {
                        if (state.language == AppLanguage.EN) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }

        }
        // ---------- 编辑器 ----------
        SectionTitle(stringResource(R.string.settings_section_editor)) {

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_font_size)) },
            supportingContent = {
                Text(stringResource(R.string.settings_value, state.editorFontSize.toInt()))
            },
            trailingContent = {
                Slider(
                    value = state.editorFontSize,
                    onValueChange = vm::setEditorFontSize,
                    valueRange = 10f..24f,
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
            }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_tab_size)) },
            supportingContent = {
                Text(stringResource(R.string.settings_value, state.editorTabSize))
            },
            trailingContent = {
                Slider(
                    value = state.editorTabSize.toFloat(),
                    onValueChange = { vm.setEditorTabSize(it.toInt()) },
                    valueRange = 2f..8f,
                    steps = 5,
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
            }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_auto_save)) },
            trailingContent = {
                Switch(checked = state.editorAutoSave, onCheckedChange = vm::setEditorAutoSave)
            }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_wrap)) },
            supportingContent = { Text(stringResource(R.string.settings_wrap_desc)) },
            trailingContent = {
                Switch(checked = state.editorWrap, onCheckedChange = vm::setEditorWrap)
            }
        )

        }
        // ---------- 浏览器与预览 ----------
        SectionTitle(stringResource(R.string.settings_section_preview)) {

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_click_opens)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (state.clickOpensPreview) R.string.settings_click_opens_browser
                        else R.string.settings_click_opens_editor
                    )
                )
            },
            trailingContent = {
                Switch(checked = state.clickOpensPreview, onCheckedChange = vm::setClickOpensPreview)
            }
        )
        // 默认 UA 预设(点击弹出右对齐选择菜单)
        var uaMenuAnchorH by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { uaMenuAnchorH = it.height }
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_default_ua)) },
                supportingContent = { Text(uaPresetLabel(state.uaPreset)) },
                leadingContent = { Icon(Icons.Filled.Language, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableRow { uaMenu = true }
            )
            RightAlignedMenu(
                expanded = uaMenu,
                onDismissRequest = { uaMenu = false },
                anchorHeightPx = uaMenuAnchorH
            ) {
                UserAgentPreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(uaPresetLabel(preset)) },
                        onClick = {
                            uaMenu = false
                            vm.setUaPreset(preset)
                        },
                        trailingIcon = {
                            if (state.uaPreset == preset) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_browser_js)) },
            supportingContent = { Text(stringResource(R.string.settings_browser_js_desc)) },
            trailingContent = {
                Switch(checked = state.jsEnabled, onCheckedChange = vm::setJsEnabled)
            }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_browser_console)) },
            supportingContent = { Text(stringResource(R.string.settings_browser_console_desc)) },
            trailingContent = {
                Switch(checked = state.browserConsole, onCheckedChange = vm::setBrowserConsole)
            }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_fullscreen_immersive)) },
            supportingContent = { Text(stringResource(R.string.settings_fullscreen_immersive_desc)) },
            trailingContent = {
                Switch(checked = state.fullscreenImmersive, onCheckedChange = vm::setFullscreenImmersive)
            }
        )

        }
        // ---------- 资源缓存 ----------
        SectionTitle(stringResource(R.string.settings_section_cache)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_resource_cache)) },
            supportingContent = { Text(stringResource(R.string.settings_resource_cache_desc)) },
            trailingContent = {
                Switch(checked = state.resourceCacheEnabled, onCheckedChange = vm::setResourceCache)
            }
        )
        // 清理缓存资源:单击=选择清理位置;长按=全部清除
        var showCachePicker by remember { mutableStateOf(false) }
        var cacheChecked by remember { mutableStateOf(setOf<String>()) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_cache_cleanup)) },
            supportingContent = {
                Column {
                    val stats = state.cacheStats
                    Text(
                        if (stats != null) {
                            stringResource(
                                R.string.settings_cache_size,
                                stats.resourceCount,
                                formatBytes(stats.totalBytes)
                            )
                        } else {
                            stringResource(R.string.settings_cache_empty)
                        }
                    )
                    Text(
                        stringResource(R.string.settings_cache_long_press),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = state.cacheStats != null,
                    onClick = {
                        // 单击:刷新位置明细并弹出选择对话框
                        vm.refreshCacheStats()
                        cacheChecked = emptySet()
                        showCachePicker = true
                    },
                    onLongClick = {
                        // 长按:一键全部清除
                        vm.clearResourceCache()
                    }
                )
        )
        // 缓存位置选择对话框(仅列出非空位置)
        if (showCachePicker) {
            AlertDialog(
                onDismissRequest = { showCachePicker = false },
                title = { Text(stringResource(R.string.settings_cache_select_title)) },
                text = {
                    if (state.cacheLocations.isEmpty()) {
                        Text(stringResource(R.string.settings_cache_select_empty))
                    } else {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            state.cacheLocations.forEach { loc ->
                                val key = loc.cacheDir.absolutePath
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            cacheChecked = if (key in cacheChecked) {
                                                cacheChecked - key
                                            } else {
                                                cacheChecked + key
                                            }
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Checkbox(
                                        checked = key in cacheChecked,
                                        onCheckedChange = {
                                            cacheChecked = if (it) cacheChecked + key else cacheChecked - key
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = loc.cacheDir.parentFile?.name ?: loc.cacheDir.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.cache_location_label,
                                                loc.resourceCount,
                                                formatBytes(loc.totalBytes)
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCachePicker = false
                            val selected = state.cacheLocations
                                .filter { it.cacheDir.absolutePath in cacheChecked }
                                .map { it.cacheDir }
                            vm.clearCacheLocations(selected)
                        },
                        enabled = cacheChecked.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.settings_cache_select_clear, cacheChecked.size))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCachePicker = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        // 缓存清理完成反馈(Snackbar)
        val clearCacheFeedback by vm.clearCacheFeedback.collectAsStateWithLifecycle()
        LaunchedEffect(clearCacheFeedback) {
            clearCacheFeedback?.let { kind ->
                snackbarHostState.showSnackbar(
                    when (kind) {
                        is SettingsViewModel.ClearCacheKind.ALL ->
                            context.getString(R.string.settings_cache_cleared_all)
                        is SettingsViewModel.ClearCacheKind.SELECTED ->
                            context.getString(R.string.settings_cache_cleared_selected, kind.count)
                    }
                )
                vm.consumeClearCacheFeedback()
            }
        }
        // 进入设置页时刷新缓存统计
        LaunchedEffect(Unit) {
            vm.refreshCacheStats()
            vm.refreshLogsInfo()
        }

        // 清理日志完成反馈(应用内 Snackbar)
        val clearLogsFeedback by vm.clearLogsFeedback.collectAsStateWithLifecycle()
        LaunchedEffect(clearLogsFeedback) {
            clearLogsFeedback?.let { deleted ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_clear_logs_done, deleted)
                )
                vm.consumeClearLogsFeedback()
            }
        }

        }
        // ---------- 数据备份 ----------
        SectionTitle(stringResource(R.string.settings_section_backup)) {
        // 导出完成 → 系统分享
        val exportDataFile by vm.exportDataFile.collectAsStateWithLifecycle()
        LaunchedEffect(exportDataFile) {
            exportDataFile?.let { file ->
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_data_export))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.settings_data_export))
                    )
                }
                vm.consumeExportDataFile()
            }
        }
        // 导入完成 → 应用内提示
        val importFeedback by vm.importFeedback.collectAsStateWithLifecycle()
        LaunchedEffect(importFeedback) {
            importFeedback?.let { count ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_data_import_done, count)
                )
                vm.consumeImportFeedback()
            }
        }
        // SAF 文件选择(导入 zip)
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) vm.importData(uri)
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_data_export)) },
            supportingContent = { Text(stringResource(R.string.settings_data_export_desc)) },
            leadingContent = { Icon(Icons.Filled.Upload, contentDescription = null) },
            modifier = Modifier.clickableRow { vm.exportData() }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_data_import)) },
            supportingContent = { Text(stringResource(R.string.settings_data_import_desc)) },
            leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
            modifier = Modifier.clickableRow {
                importLauncher.launch(arrayOf("application/zip"))
            }
        )

        }
        // ---------- 云同步 ----------
        SectionTitle(stringResource(R.string.settings_section_cloud)) {

        // 云盘切换(活动数据源:未启用/百度网盘/WebDAV,各盘凭据独立保存)
        // 未启用时仅显示本行;选择某云盘后仅显示对应云盘的设置与同步设置
        var cloudMenu by remember { mutableStateOf(false) }
        var cloudMenuAnchorH by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { cloudMenuAnchorH = it.height }
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.cloud_provider)) },
                supportingContent = { Text(stringResource(R.string.cloud_provider_desc)) },
                leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableRow { cloudMenu = true }
            )
            RightAlignedMenu(
                expanded = cloudMenu,
                onDismissRequest = { cloudMenu = false },
                anchorHeightPx = cloudMenuAnchorH
            ) {
                listOf(
                    CloudProviderType.NONE to R.string.cloud_none,
                    CloudProviderType.BAIDU to R.string.cloud_baidu,
                    CloudProviderType.WEBDAV to R.string.cloud_webdav
                ).forEach { (type, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            cloudMenu = false
                            vm.setCloudProvider(type)
                        },
                        trailingIcon = {
                            if (state.cloudProvider == type) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
            }
        }

        // ================= 百度网盘组(仅选择百度网盘时显示) =================
        if (state.cloudProvider == CloudProviderType.BAIDU) {
        GroupLabel(stringResource(R.string.cloud_group_baidu))
        // 百度网盘账号:授权登录(WebView oob 授权码)与登录状态
        var showBaiduAuth by remember { mutableStateOf(false) }
        var showLogoutConfirm by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.baidu_account)) },
            supportingContent = {
                Text(
                    if (state.baiduLoggedIn) {
                        stringResource(R.string.baidu_logged_in, formatSyncTime(state.baiduTokenExpiresAt))
                    } else {
                        stringResource(R.string.baidu_not_logged_in)
                    }
                )
            },
            leadingContent = { Icon(Icons.Filled.CloudQueue, contentDescription = null) },
            modifier = Modifier.clickableRow {
                if (state.baiduLoggedIn) showLogoutConfirm = true else showBaiduAuth = true
            }
        )
        // 登出二次确认(清除令牌与同步快照,不可撤销)
        if (showLogoutConfirm) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirm = false },
                title = { Text(stringResource(R.string.baidu_logout_title)) },
                text = { Text(stringResource(R.string.baidu_logout_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutConfirm = false
                        vm.baiduLogout()
                    }) {
                        Text(
                            stringResource(R.string.baidu_logout_action),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        // 百度远端目录(同步与云端浏览的根;可手动输入或从云端选择,避免固定在根目录)
        var showBaiduRoot by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.baidu_remote_root)) },
            supportingContent = { Text(state.baiduRemoteRoot) },
            leadingContent = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
            modifier = Modifier.clickableRow { showBaiduRoot = true }
        )
        // 百度开放平台凭据(AppKey/SecretKey,用户自行申请填写,不内置)
        var showBaiduKeys by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.baidu_keys)) },
            supportingContent = { Text(stringResource(R.string.baidu_keys_desc)) },
            leadingContent = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
            modifier = Modifier.clickableRow { showBaiduKeys = true }
        )

        // 百度授权对话框(WebView)+ 授权结果提示
        if (showBaiduAuth) {
            BaiduAuthDialog(
                authorizeUrl = state.baiduAuthorizeUrl,
                onCode = { vm.authorizeBaidu(it) },
                onDismiss = { showBaiduAuth = false }
            )
        }
        val baiduAuthFeedback by vm.baiduAuthFeedback.collectAsStateWithLifecycle()
        LaunchedEffect(baiduAuthFeedback) {
            baiduAuthFeedback?.let { ok ->
                showBaiduAuth = false
                snackbarHostState.showSnackbar(
                    context.getString(
                        if (ok) R.string.baidu_auth_success else R.string.baidu_auth_failed
                    )
                )
                vm.consumeBaiduAuthFeedback()
            }
        }

        // 百度凭据编辑对话框
        if (showBaiduKeys) {
            var keyApp by remember { mutableStateOf(state.baiduAppKey) }
            var keySecret by remember { mutableStateOf(state.baiduSecretKey) }
            AlertDialog(
                onDismissRequest = { showBaiduKeys = false },
                title = { Text(stringResource(R.string.baidu_keys)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = keyApp,
                            onValueChange = { keyApp = it },
                            label = { Text(stringResource(R.string.baidu_app_key)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keySecret,
                            onValueChange = { keySecret = it },
                            label = { Text(stringResource(R.string.baidu_secret_key)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.saveBaiduKeys(keyApp, keySecret)
                            showBaiduKeys = false
                        }
                    ) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showBaiduKeys = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        // 百度远端目录选择对话框(输入 + 云端浏览双入口)
        if (showBaiduRoot) {
            RemoteDirPickerDialog(
                title = stringResource(R.string.baidu_remote_root),
                initialPath = state.baiduRemoteRoot,
                hint = stringResource(R.string.baidu_remote_root_hint),
                listDirs = { dir -> vm.listRemoteDirs(dir) },
                onPick = {
                    vm.saveBaiduRemoteRoot(it)
                    showBaiduRoot = false
                },
                onDismiss = { showBaiduRoot = false }
            )
        }
        }

        // ================= WebDAV 组(仅选择 WebDAV 时显示) =================
        if (state.cloudProvider == CloudProviderType.WEBDAV) {
        GroupLabel(stringResource(R.string.cloud_group_webdav))
        // WebDAV 配置(服务器/账号/密码/远端目录 + 测试连接)
        var showWebdav by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.webdav_config)) },
            supportingContent = {
                Text(
                    if (state.webdavUrl.isNotBlank()) state.webdavUrl
                    else stringResource(R.string.webdav_config_desc)
                )
            },
            leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
            modifier = Modifier.clickableRow { showWebdav = true }
        )

        // WebDAV 配置对话框(含测试连接)
        if (showWebdav) {
            var wUrl by remember { mutableStateOf(state.webdavUrl) }
            var wUser by remember { mutableStateOf(state.webdavUsername) }
            var wPass by remember { mutableStateOf(state.webdavPassword) }
            var wDir by remember { mutableStateOf(state.webdavDir) }
            AlertDialog(
                onDismissRequest = { showWebdav = false },
                title = { Text(stringResource(R.string.webdav_config)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = wUrl,
                            onValueChange = { wUrl = it },
                            label = { Text(stringResource(R.string.webdav_server)) },
                            placeholder = { Text(stringResource(R.string.webdav_server_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = wUser,
                            onValueChange = { wUser = it },
                            label = { Text(stringResource(R.string.webdav_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = wPass,
                            onValueChange = { wPass = it },
                            label = { Text(stringResource(R.string.webdav_password)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = wDir,
                            onValueChange = { wDir = it },
                            label = { Text(stringResource(R.string.webdav_dir)) },
                            placeholder = { Text("NW'HTMLviewer") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            enabled = wUrl.isNotBlank() && wUser.isNotBlank(),
                            onClick = { vm.testWebdav(wUrl, wUser, wPass, wDir) }
                        ) { Text(stringResource(R.string.webdav_test)) }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.saveWebdavConfig(wUrl, wUser, wPass, wDir)
                            showWebdav = false
                        },
                        enabled = wUrl.isNotBlank() && wUser.isNotBlank()
                    ) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showWebdav = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        // WebDAV 测试结果提示
        val webdavTest by vm.webdavTest.collectAsStateWithLifecycle()
        LaunchedEffect(webdavTest) {
            webdavTest?.let { result ->
                snackbarHostState.showSnackbar(
                    when (result) {
                        is WebDavTestResult.Success ->
                            context.getString(R.string.webdav_test_ok)
                        is WebDavTestResult.Failed ->
                            context.getString(R.string.webdav_test_fail, result.message)
                    }
                )
                vm.consumeWebdavTest()
            }
        }
        }

        // ================= 同步组(启用任意云盘后显示) =================
        if (state.cloudProvider != CloudProviderType.NONE) {
        GroupLabel(stringResource(R.string.cloud_group_sync))
        // 立即同步(主操作,高亮展示;进度/结果/冲突对话框见下方)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_now)) },
                supportingContent = {
                    Text(
                        if (state.lastSyncAt > 0) {
                            stringResource(R.string.sync_last_time, formatSyncTime(state.lastSyncAt))
                        } else {
                            stringResource(R.string.sync_last_never)
                        }
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Filled.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickableRow { vm.sync.syncNow() }
            )
        }
        // 冲突处理策略
        var policyMenu by remember { mutableStateOf(false) }
        var policyMenuAnchorH by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { policyMenuAnchorH = it.height }
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_conflict_policy)) },
                supportingContent = { Text(stringResource(R.string.sync_conflict_desc)) },
                leadingContent = { Icon(Icons.Filled.CallSplit, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableRow { policyMenu = true }
            )
            RightAlignedMenu(
                expanded = policyMenu,
                onDismissRequest = { policyMenu = false },
                anchorHeightPx = policyMenuAnchorH
            ) {
                listOf(
                    SyncConflictPolicy.ASK to R.string.sync_policy_ask,
                    SyncConflictPolicy.NEWER_WINS to R.string.sync_policy_newer,
                    SyncConflictPolicy.KEEP_BOTH to R.string.sync_policy_keep_both
                ).forEach { (policy, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            policyMenu = false
                            vm.setSyncConflictPolicy(policy)
                        },
                        trailingIcon = {
                            if (state.syncConflictPolicy == policy) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.sync_auto_upload)) },
            supportingContent = { Text(stringResource(R.string.sync_auto_upload_desc)) },
            trailingContent = {
                Switch(checked = state.syncAutoUpload, onCheckedChange = vm::setSyncAutoUpload)
            }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.sync_on_start)) },
            supportingContent = { Text(stringResource(R.string.sync_on_start_desc)) },
            trailingContent = {
                Switch(checked = state.syncOnStart, onCheckedChange = vm::setSyncOnStart)
            }
        )

        // 云同步进度/结果/失败对话框
        val syncState by vm.sync.syncState.collectAsStateWithLifecycle()
        val syncProgressHidden by vm.sync.progressHidden.collectAsStateWithLifecycle()
        when (val s = syncState) {
            is SyncUiState.Running ->
                if (!syncProgressHidden) {
                    SyncProgressDialog(progress = s.progress, onDismiss = {
                        // 隐藏后转通知栏展示:Android 13+ 首次隐藏时请求通知权限
                        if (!vm.sync.hasNotificationPermission() && android.os.Build.VERSION.SDK_INT >= 33) {
                            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        vm.sync.hideProgress()
                    })
                }
            is SyncUiState.Done -> SyncResultDialog(result = s.result) { vm.sync.consumeState() }
            is SyncUiState.Failed -> AlertDialog(
                onDismissRequest = { vm.sync.consumeState() },
                title = { Text(stringResource(R.string.sync_failed_title)) },
                text = {
                    Text(
                        if (s.message.isBlank()) stringResource(R.string.sync_failed_generic)
                        else s.message
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.sync.consumeState() }) {
                        Text(stringResource(R.string.action_confirm))
                    }
                }
            )
            SyncUiState.Idle -> Unit
        }
        // 冲突处理对话框(由 ConflictDecider 的请求驱动,无请求时不渲染)
        ConflictDialog(decider = vm.sync.conflictDecider, onDismiss = {})
        }

        }
        // ---------- 开发者 ----------
        SectionTitle(stringResource(R.string.settings_section_debug)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_debug_mode)) },
            supportingContent = { Text(stringResource(R.string.settings_debug_mode_desc)) },
            leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
            trailingContent = {
                Switch(checked = state.debugMode, onCheckedChange = vm::setDebugMode)
            }
        )
        val logsInfo by vm.logsInfo.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_export_logs)) },
            supportingContent = {
                Text(
                    if (logsInfo != null && logsInfo!!.first > 0) {
                        stringResource(
                            R.string.settings_export_logs_info,
                            logsInfo!!.first,
                            formatBytes(logsInfo!!.second.toLong())
                        )
                    } else {
                        stringResource(R.string.settings_export_logs_desc)
                    }
                )
            },
            leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
            modifier = Modifier.clickableRow { vm.exportLogs() }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_clear_logs)) },
            supportingContent = { Text(stringResource(R.string.settings_clear_logs_desc)) },
            leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
            modifier = Modifier.clickableRow { vm.clearLogs() }
        )

        }
        // ---------- 关于 ----------
        SectionTitle(stringResource(R.string.settings_section_about)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_about_version)) },
            supportingContent = { Text(state.appVersion.ifBlank { "-" }) },
            leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
            modifier = Modifier.clickableRow(onOpenAbout)
        )
        // 检查更新:仅手动触发,查询 GitHub Releases latest
        val updateState by vm.updateState.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_check_update)) },
            supportingContent = {
                Text(
                    if (updateState == UpdateUiState.Checking) {
                        stringResource(R.string.update_checking_title)
                    } else {
                        stringResource(R.string.settings_check_update_desc)
                    }
                )
            },
            leadingContent = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
            modifier = Modifier.clickableRow { vm.checkForUpdate() }
        )
        // 更新检测结果对话框
        when (val us = updateState) {
            is UpdateUiState.Checking -> {
                AlertDialog(
                    onDismissRequest = { vm.consumeUpdateState() },
                    title = { Text(stringResource(R.string.update_checking_title)) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(R.string.settings_check_update_desc))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.consumeUpdateState() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
            is UpdateUiState.Found -> {
                AlertDialog(
                    onDismissRequest = { vm.consumeUpdateState() },
                    title = { Text(stringResource(R.string.update_found_title)) },
                    text = {
                        Column {
                            Text(
                                text = stringResource(R.string.update_found_version, us.info.version),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.update_current_version, state.appVersion.ifBlank { "-" }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val asset = us.info.findAsset(vm.isLiteEdition, vm.primaryAbi)
                            // Atom 源无资产大小(0)时省略该行
                            if (asset != null && asset.size > 0) {
                                Text(
                                    text = stringResource(R.string.update_package_size, formatBytes(asset.size)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (us.info.publishedAt.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.update_published, formatUpdateTime(us.info.publishedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = us.info.body,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                vm.consumeUpdateState()
                                val url = us.info.findAsset(vm.isLiteEdition, vm.primaryAbi)
                                    ?.browserDownloadUrl ?: us.info.htmlUrl
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                }
                            }
                        ) {
                            Text(stringResource(R.string.update_download))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { vm.consumeUpdateState() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
            is UpdateUiState.UpToDate -> {
                AlertDialog(
                    onDismissRequest = { vm.consumeUpdateState() },
                    title = { Text(stringResource(R.string.update_latest_title)) },
                    text = { Text(stringResource(R.string.update_latest_desc, us.currentVersion)) },
                    confirmButton = {
                        TextButton(onClick = { vm.consumeUpdateState() }) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                )
            }
            is UpdateUiState.Failed -> {
                AlertDialog(
                    onDismissRequest = { vm.consumeUpdateState() },
                    title = { Text(stringResource(R.string.update_check_failed)) },
                    text = { Text(stringResource(R.string.update_check_failed_desc)) },
                    confirmButton = {
                        TextButton(onClick = { vm.consumeUpdateState() }) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                )
            }
            UpdateUiState.Idle -> Unit
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_engine_versions)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.settings_engine_versions_value,
                        state.webViewVersion.ifBlank { "-" },
                        state.geckoVersion
                    )
                )
            }
        )
    }
        }
    // 应用内提示(清空日志等反馈)
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }
}

/** 分区标题 + 圆角卡片容器:各分区内容归入卡片,提升分组观感 */
/** 分区内部的小组标签(如"百度网盘/WebDAV/同步"):胶囊状,置于组前 */
@Composable
private fun GroupLabel(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SectionTitle(
    text: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp)
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )

/** 字节数人性化显示 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** GitHub ISO-8601 发布时间(UTC)→ 本地时区 "yyyy-MM-dd HH:mm"(解析失败原样返回) */
private fun formatUpdateTime(iso: String): String = runCatching {
    java.time.Instant.parse(iso)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(iso)

/** epoch 毫秒 → 本地时间 "yyyy-MM-dd HH:mm"(云同步时间展示) */
private fun formatSyncTime(ms: Long): String = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
}.getOrDefault("-")

/** 预设主题色种子(ARGB) */
private val CUSTOM_COLOR_SEEDS = listOf(
    0xFF3D5AFE, // 靛蓝
    0xFF7C4DFF, // 紫罗兰
    0xFF1E88E5, // 蓝
    0xFF00897B, // 青
    0xFF43A047, // 绿
    0xFFFB8C00, // 橙
    0xFFE53935, // 红
    0xFFD81B60, // 粉
    0xFF8D6E63, // 棕
    0xFF546E7A  // 蓝灰
)

/** 主题色选择色块;color=null 表示"跟随动态取色/默认" */
@Composable
private fun ColorSwatch(color: Long?, selected: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(shape)
            .background(if (color != null) Color(color) else Color.Transparent)
            .then(
                if (color == null) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.outline, shape)
                } else {
                    Modifier
                }
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (color == null) {
            Text(
                text = "◌",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (selected) {
            val swatch = Color(color)
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleSmall,
                color = if (swatch.luminance() > 0.5f) Color.Black else Color.White
            )
        }
    }
}
