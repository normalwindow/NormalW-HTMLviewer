package xyz.normalwindow.htmlviewer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 收藏分组 */
@Entity(tableName = "favorite_group")
data class FavoriteGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
