package xyz.normalwindow.htmlviewer.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FileMetaEntity::class, FavoriteGroupEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileMetaDao(): FileMetaDao
}
