package xyz.normalwindow.htmlviewer.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import xyz.normalwindow.htmlviewer.data.settings.AppLanguage
import xyz.normalwindow.htmlviewer.data.settings.ColorStyle
import xyz.normalwindow.htmlviewer.data.settings.EngineType
import xyz.normalwindow.htmlviewer.data.settings.ThemeMode
import xyz.normalwindow.htmlviewer.ui.components.RightAlignedMenu
import xyz.normalwindow.htmlviewer.ui.components.uaPresetLabel
import xyz.normalwindow.htmlviewer.render.UserAgentPreset

/** 设置中心:内核选择 / 外观 / 编辑器 / 浏览器与预览 / 开发者 / 关于 */
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
        SectionTitle(stringResource(R.string.settings_section_engine))

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

        // ---------- 外观 ----------
        SectionTitle(stringResource(R.string.settings_section_appearance))

        listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark
        ).forEach { (mode, labelRes) ->
            ListItem(
                headlineContent = { Text(stringResource(labelRes)) },
                trailingContent = {
                    RadioButton(
                        selected = state.themeMode == mode,
                        onClick = { vm.setThemeMode(mode) }
                    )
                },
                modifier = Modifier.clickableRow { vm.setThemeMode(mode) }
            )
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

        // ---------- 编辑器 ----------
        SectionTitle(stringResource(R.string.settings_section_editor))

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

        // ---------- 浏览器与预览 ----------
        SectionTitle(stringResource(R.string.settings_section_preview))

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

        // ---------- 资源缓存 ----------
        SectionTitle(stringResource(R.string.settings_section_cache))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_resource_cache)) },
            supportingContent = { Text(stringResource(R.string.settings_resource_cache_desc)) },
            trailingContent = {
                Switch(checked = state.resourceCacheEnabled, onCheckedChange = vm::setResourceCache)
            }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_cache_cleanup)) },
            supportingContent = {
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
            },
            trailingContent = {
                TextButton(
                    onClick = vm::clearResourceCache,
                    enabled = state.cacheStats != null
                ) {
                    Text(stringResource(R.string.settings_cache_clear_btn))
                }
            }
        )
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

        // ---------- 数据备份 ----------
        SectionTitle(stringResource(R.string.settings_section_backup))
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

        // ---------- 开发者 ----------
        SectionTitle(stringResource(R.string.settings_section_debug))
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

        // ---------- 关于 ----------
        SectionTitle(stringResource(R.string.settings_section_about))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_about_version)) },
            supportingContent = { Text(state.appVersion.ifBlank { "-" }) },
            leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
            modifier = Modifier.clickableRow(onOpenAbout)
        )
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
    // 应用内提示(清空日志等反馈)
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
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
