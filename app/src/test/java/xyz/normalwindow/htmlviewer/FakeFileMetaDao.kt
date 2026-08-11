package xyz.normalwindow.htmlviewer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import xyz.normalwindow.htmlviewer.data.db.FavoriteGroupEntity
import xyz.normalwindow.htmlviewer.data.db.FileMetaDao
import xyz.normalwindow.htmlviewer.data.db.FileMetaEntity

/** 内存版 FileMetaDao,供单元测试使用 */
class FakeFileMetaDao : FileMetaDao {

    private val store = LinkedHashMap<String, FileMetaEntity>()

    private val favorites = MutableStateFlow<List<FileMetaEntity>>(emptyList())
    private val recent = MutableStateFlow<List<FileMetaEntity>>(emptyList())
    private val groups = MutableStateFlow<List<FavoriteGroupEntity>>(emptyList())
    private var nextGroupId = 1L

    override fun observeGroups(): Flow<List<FavoriteGroupEntity>> = groups

    override fun observeFavorites(): Flow<List<FileMetaEntity>> = favorites

    override suspend fun insertGroup(group: FavoriteGroupEntity): Long {
        val id = if (group.id == 0L) nextGroupId++ else group.id
        val saved = group.copy(id = id)
        groups.value = groups.value + saved
        return id
    }

    override suspend fun renameGroup(id: Long, name: String) {
        groups.value = groups.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun clearGroupFiles(id: Long) {
        store.keys.toList().forEach { p ->
            val m = store[p] ?: return@forEach
            if (m.groupId == id) store[p] = m.copy(groupId = null)
        }
        refresh()
    }

    override suspend fun deleteGroup(id: Long) {
        clearGroupFiles(id)
        groups.value = groups.value.filterNot { it.id == id }
    }

    override suspend fun setFileGroup(path: String, groupId: Long?) {
        store[path]?.let { store[path] = it.copy(groupId = groupId) }
        refresh()
    }

    override fun observeRecent(limit: Int): Flow<List<FileMetaEntity>> =
        recent.map { it.take(limit) }

    override suspend fun get(path: String): FileMetaEntity? = store[path]

    override suspend fun upsert(meta: FileMetaEntity) {
        store[meta.path] = meta
        refresh()
    }

    override suspend fun setFavorite(path: String, fav: Boolean) {
        store[path]?.let { store[path] = it.copy(isFavorite = fav) }
        refresh()
    }

    override suspend fun touchOpened(path: String, time: Long) {
        store[path]?.let { store[path] = it.copy(lastOpenedAt = time) }
        refresh()
    }

    override suspend fun updateEncoding(path: String, encoding: String) {
        store[path]?.let { store[path] = it.copy(encoding = encoding) }
    }

    override suspend fun updateStats(path: String, lines: Int, chars: Int) {
        store[path]?.let { store[path] = it.copy(lineCount = lines, charCount = chars) }
    }

    override suspend fun delete(path: String) {
        store.remove(path)
        refresh()
    }

    fun snapshot(): Map<String, FileMetaEntity> = store.toMap()

    private fun refresh() {
        favorites.value = store.values
            .filter { it.isFavorite }
            .sortedByDescending { it.lastOpenedAt ?: 0L }
        recent.value = store.values
            .filter { it.lastOpenedAt != null }
            .sortedByDescending { it.lastOpenedAt ?: 0L }
    }
}
