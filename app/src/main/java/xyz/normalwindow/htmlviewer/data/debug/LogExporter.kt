package xyz.normalwindow.htmlviewer.data.debug

import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.first
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试报告导出:设备信息 + 版本 + 设置快照 + 应用日志 + 本进程 logcat。
 * 供设置页"导出运行日志"生成文本文件并分享。
 */
object LogExporter {

    /** 组装完整调试报告(IO 线程调用) */
    suspend fun buildReport(
        context: Context,
        settingsRepository: SettingsRepository
    ): String {
        val prefs = settingsRepository.preferences.first()
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val sb = StringBuilder()

        sb.appendLine("===== HTML Viewer 调试日志 =====")
        sb.appendLine("生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("应用版本: ${appVersion?.versionName} (${appVersion?.versionCode})")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        sb.appendLine("----- 设置快照 -----")
        sb.appendLine("主题: ${prefs.themeMode}")
        sb.appendLine("渲染内核: ${prefs.defaultEngine}")
        sb.appendLine("Debug 模式: ${prefs.debugMode}")
        sb.appendLine("控制台收集: ${prefs.browserConsole}")
        sb.appendLine("离线缓存: ${prefs.resourceCacheEnabled}")
        sb.appendLine("编辑器: 字号 ${prefs.editorFontSize} / 缩进 ${prefs.editorTabSize} / 换行 ${prefs.editorWrap} / 自动保存 ${prefs.editorAutoSave}")
        sb.appendLine()

        sb.appendLine("----- 应用日志(${AppLog.size()} 条) -----")
        sb.appendLine(if (AppLog.size() > 0) AppLog.dump() else "(debug 模式开启后才会记录)")
        sb.appendLine()

        sb.appendLine("----- logcat(本进程,最近片段) -----")
        sb.appendLine(readOwnLogcat())
        return sb.toString()
    }

    /** 写入文件(缓存目录 logs/,返回文件) */
    suspend fun writeToFile(context: Context, report: String): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "htmlviewer-log-$stamp.txt")
        file.writeText(report)
        return file
    }

    /** 日志文件所在目录(供设置页展示) */
    fun logsDir(context: Context): File = File(context.cacheDir, "logs")

    /** 已导出日志文件统计(数量/总字节) */
    fun exportedLogsInfo(context: Context): Pair<Int, Long> {
        val dir = logsDir(context)
        if (!dir.isDirectory) return 0 to 0L
        var count = 0
        var bytes = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                count += 1
                bytes += f.length()
            }
        }
        return count to bytes
    }

    /** 清理所有已导出的日志文件,返回删除的文件数 */
    fun clearExportedLogs(context: Context): Int {
        val dir = logsDir(context)
        if (!dir.isDirectory) return 0
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.delete()) count += 1
        }
        return count
    }

    /**
     * 读取本进程 logcat(无需权限,Android 允许应用读取自身日志)。
     * 失败(部分 ROM 限制)时返回说明。
     */
    private fun readOwnLogcat(): String {
        return try {
            val pid = Process.myPid()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "--pid=$pid")
            )
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) "(无 logcat 输出)" else text.takeLast(200_000)
        } catch (e: Exception) {
            "logcat 读取失败: ${e.message}"
        }
    }
}
