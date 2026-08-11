package xyz.normalwindow.htmlviewer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileMetaDao {

    // ---------- 收藏分组 ----------

    @Query("SELECT * FROM favorite_group ORDER BY sortOrder, createdAt")
    fun observeGroups(): Flow<List<FavoriteGroupEntity>>

    @Insert
    suspend fun insertGroup(group: FavoriteGroupEntity): Long

    @Query("UPDATE favorite_group SET name = :name WHERE id = :id")
    suspend fun renameGroup(id: Long, name: String)

    @Query("UPDATE file_meta SET groupId = NULL WHERE groupId = :id")
    suspend fun clearGroupFiles(id: Long)

    @Query("DELETE FROM favorite_group WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("UPDATE file_meta SET groupId = :groupId WHERE path = :path")
    suspend fun setFileGroup(path: String, groupId: Long?)

    // ---------- 收藏 / 最近 ----------

    @Query("SELECT * FROM file_meta WHERE isFavorite = 1 ORDER BY lastOpenedAt DESC, createdAt DESC")
    fun observeFavorites(): Flow<List<FileMetaEntity>>

    @Query(
        "SELECT * FROM file_meta WHERE lastOpenedAt IS NOT NULL " +
            "ORDER BY lastOpenedAt DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int = 50): Flow<List<FileMetaEntity>>

    @Query("SELECT * FROM file_meta WHERE path = :path")
    suspend fun get(path: String): FileMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: FileMetaEntity)

    @Query("UPDATE file_meta SET isFavorite = :fav WHERE path = :path")
    suspend fun setFavorite(path: String, fav: Boolean)

    @Query("UPDATE file_meta SET lastOpenedAt = :time WHERE path = :path")
    suspend fun touchOpened(path: String, time: Long)

    @Query("UPDATE file_meta SET encoding = :encoding WHERE path = :path")
    suspend fun updateEncoding(path: String, encoding: String)

    @Query("UPDATE file_meta SET lineCount = :lines, charCount = :chars WHERE path = :path")
    suspend fun updateStats(path: String, lines: Int, chars: Int)

    @Query("DELETE FROM file_meta WHERE path = :path")
    suspend fun delete(path: String)
}
