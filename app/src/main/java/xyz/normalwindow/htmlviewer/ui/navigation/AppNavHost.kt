package xyz.normalwindow.htmlviewer.ui.navigation

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import xyz.normalwindow.htmlviewer.HTMLViewerApp
import xyz.normalwindow.htmlviewer.data.settings.ThemeMode
import xyz.normalwindow.htmlviewer.data.settings.UserPreferences
import xyz.normalwindow.htmlviewer.ui.browser.BrowserScreen
import xyz.normalwindow.htmlviewer.ui.browser.BrowserViewModel
import xyz.normalwindow.htmlviewer.ui.editor.EditorScreen
import xyz.normalwindow.htmlviewer.ui.editor.EditorViewModel
import xyz.normalwindow.htmlviewer.ui.home.HomeScreen
import xyz.normalwindow.htmlviewer.ui.home.HomeViewModel

/** 应用导航图:home(文件管理) / browser(浏览器预览) / editor(代码编辑器) */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    // 主题模式跟随应用设置(与 MainActivity 一致),供编辑器 CodeMirror 使用
    val editorDarkTheme = rememberEditorDarkTheme()
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
                }
            )
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
