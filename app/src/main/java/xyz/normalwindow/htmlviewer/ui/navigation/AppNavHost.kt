package xyz.normalwindow.htmlviewer.ui.navigation

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import xyz.normalwindow.htmlviewer.HTMLViewerApp
import xyz.normalwindow.htmlviewer.R
import xyz.normalwindow.htmlviewer.data.settings.ThemeMode
import xyz.normalwindow.htmlviewer.data.settings.UserPreferences
import xyz.normalwindow.htmlviewer.ui.about.AboutScreen
import xyz.normalwindow.htmlviewer.ui.browser.BrowserScreen
import xyz.normalwindow.htmlviewer.ui.browser.BrowserViewModel
import xyz.normalwindow.htmlviewer.ui.editor.EditorScreen
import xyz.normalwindow.htmlviewer.ui.editor.EditorViewModel
import xyz.normalwindow.htmlviewer.ui.home.HomeScreen
import xyz.normalwindow.htmlviewer.ui.home.HomeViewModel
import xyz.normalwindow.htmlviewer.ui.share.ShareImportDialog
import xyz.normalwindow.htmlviewer.ui.share.ShareImportViewModel

/** 应用导航图:home(文件管理) / browser(浏览器预览) / editor(代码编辑器) */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    // 主题模式跟随应用设置(与 MainActivity 一致),供编辑器 CodeMirror 使用
    val editorDarkTheme = rememberEditorDarkTheme()
    // 外部分享导入:托管在导航图顶层,因此在任何页面(含编辑器/预览)收到分享都能弹出
    ShareImportHost()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val vm: HomeViewModel = hiltViewModel()
            HomeScreen(
                vm = vm,
                onOpenBrowser = { path, name ->
                    navController.navigate(
                        "browser/${Uri.encode(path)}?name=${Uri.encode(name)}"
                    )
                },
                onOpenEditor = { path, name ->
                    navController.navigate(
                        "editor/${Uri.encode(path)}?name=${Uri.encode(name)}"
                    )
                },
                onOpenAbout = { navController.navigate("about") }
            )
        }
        // 关于页(设置-关于-应用版本进入)
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = "browser/{path}?name={name}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            val name = Uri.decode(entry.arguments?.getString("name").orEmpty())
            val vm: BrowserViewModel = hiltViewModel()
            BrowserScreen(
                vm = vm,
                path = path,
                name = name,
                onEdit = { p, n ->
                    navController.navigate(
                        "editor/${Uri.encode(p)}?name=${Uri.encode(n)}"
                    )
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "editor/{path}?name={name}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            val name = Uri.decode(entry.arguments?.getString("name").orEmpty())
            val vm: EditorViewModel = hiltViewModel()
            EditorScreen(
                vm = vm,
                path = path,
                name = name,
                darkTheme = editorDarkTheme,
                onBack = { navController.popBackStack() },
                onOpenPreview = { p, n ->
                    navController.navigate("browser/${Uri.encode(p)}?name=${Uri.encode(n)}")
                }
            )
        }
    }
}

/** 与 MainActivity 一致的主题模式计算(编辑器 CodeMirror 跟随应用设置而非仅系统) */
@Composable
private fun rememberEditorDarkTheme(): Boolean {
    val app = LocalContext.current.applicationContext as HTMLViewerApp
    val prefs by app.settingsRepository.preferences
        .collectAsStateWithLifecycle(initialValue = UserPreferences())
    return when (prefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/**
 * 分享导入宿主:监听中转站,有待处理负载时弹出导入对话框。
 *
 * 选文件夹走 SAF OpenDocumentTree;由于工作区读写基于 java.io.File,
 * 只有应用专属外部目录可作保存位置,其余选择由 ViewModel 拒绝并提示。
 */
@Composable
private fun ShareImportHost() {
    val context = LocalContext.current
    val app = context.applicationContext as HTMLViewerApp
    val store = remember { app.incomingShareStore }
    val payload by store.payload.collectAsStateWithLifecycle()
    val vm: ShareImportViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.setTargetDirFromUri(uri)
    }

    // 收到新负载即启动暂存流程
    LaunchedEffect(payload) {
        payload?.let { vm.start(it) }
    }

    if (payload != null) {
        ShareImportDialog(
            state = state,
            onBaseNameChange = vm::setBaseName,
            onFormatChange = vm::setFormat,
            onEncodingChange = vm::setEncoding,
            onPickFolder = { folderPicker.launch(null) },
            onConfirm = {
                vm.save { saved, path ->
                    if (saved > 0) {
                        // 保存成功提示所在目录名,便于确认落点
                        val name = path?.let { java.io.File(it).parentFile?.name }.orEmpty()
                        Toast.makeText(
                            context,
                            context.getString(R.string.share_saved, name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onDismiss = {
                if (state.items.isEmpty()) vm.dismissError() else vm.cancel()
            }
        )
    }
}
