package xyz.normalwindow.htmlviewer.data.file

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用文件根目录提供者。
 * 默认根目录位于外部存储的应用专属目录(无需任何存储权限,卸载自动清理)。
 */
@Singleton
class FileRootProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 默认工作目录:Android/data/<pkg>/files/HTMLviewer */
    val defaultRoot: File
        get() = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "HTMLviewer"
        )

    /** 删除撤销的临时回收站。
     * 必须与 [defaultRoot] 位于同一挂载点:File.renameTo 底层为 rename(2),
     * 跨文件系统(如外部存储 → 内部 cacheDir)会返回 EXDEV 导致删除/恢复失败。
     * 点前缀目录位于应用专属外部目录,不在应用内文件列表显示。 */
    val trashDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, ".htmlviewer-trash")
}
