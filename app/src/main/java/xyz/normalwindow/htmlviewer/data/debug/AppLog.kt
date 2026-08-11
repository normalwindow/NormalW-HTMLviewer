package xyz.normalwindow.htmlviewer.data.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志缓冲(环形队列):
 * debug 模式开启时记录关键事件(编辑器加载/桥异常/页面错误等),
 * 供设置页导出分析。线程安全。
 */
object AppLog {

    data class Entry(
        val time: Long,
        val tag: String,
        val message: String
    )

    /** debug 开关(由设置订阅,见 HTMLViewerApp) */
    @Volatile
    var enabled: Boolean = false

    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()

    fun d(tag: String, message: String) {
        if (enabled) append(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val detail = throwable?.stackTraceToString()
            ?.lineSequence()
            ?.take(MAX_STACK_LINES)
            ?.joinToString("\n    ")
        append(tag, if (detail != null) "$message\n    $detail" else message)
    }

    /** 强制记录(不依赖开关,仅用于关键错误) */
    fun force(tag: String, message: String) {
        append(tag, message)
    }

    private fun append(tag: String, message: String) {
        synchronized(lock) {
            buffer.addLast(Entry(System.currentTimeMillis(), tag, message))
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
    }

    /** 格式化导出:按时间升序 */
    fun dump(): String = synchronized(lock) {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        buffer.joinToString("\n") { "[${fmt.format(Date(it.time))}][${it.tag}] ${it.message}" }
    }

    fun clear() = synchronized(lock) { buffer.clear() }

    fun size(): Int = synchronized(lock) { buffer.size }

    private const val MAX_ENTRIES = 500
    private const val MAX_STACK_LINES = 12
}
