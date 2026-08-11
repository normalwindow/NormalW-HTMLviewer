package xyz.normalwindow.htmlviewer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 文件元数据(与文件系统分离存储,支持收藏/最近打开/编码记忆等"人性化"功能)。
 * path 为绝对路径主键,文件本身存于应用专属目录或用户授权目录。
 */
@Entity(tableName = "file_meta")
data class FileMetaEntity(
    @PrimaryKey val path: String,
    /** 是否收藏 */
    val isFavorite: Boolean = false,
    /** 所属收藏分组(可空) */
    val groupId: Long? = null,
    /** 最近打开时间戳(毫秒),null 表示从未打开 */
    val lastOpenedAt: Long? = null,
    /** 上次读取/保存检测到的文本编码(如 UTF-8 / GBK / UTF-16LE) */
    val encoding: String? = null,
    /** 行数统计(打开时更新,用于列表展示) */
    val lineCount: Int? = null,
    /** 字符数统计 */
    val charCount: Int? = null,
    /** 首次记录时间 */
    val createdAt: Long = System.currentTimeMillis()
)
