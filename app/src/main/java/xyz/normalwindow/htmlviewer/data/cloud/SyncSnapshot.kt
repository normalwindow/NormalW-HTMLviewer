package xyz.normalwindow.htmlviewer.data.cloud

import org.json.JSONObject

/** 快照条目:上次同步成功时该文件两侧的修改时间(秒)与大小 */
data class SnapshotEntry(
    /** 本地 mtime(秒) */
    val localMtime: Long,
    /** 远端 mtime(秒) */
    val remoteMtime: Long,
    val size: Long
)

/**
 * 双向同步快照(纯数据 + JSON 序列化,可单测):
 * 记录上次同步后每个文件的两侧状态,用于区分"哪一侧在上次同步之后发生过改动",
 * 这是双向同步区别于简单 mtime 比对的关键。
 * JSON 结构:{"entries":{"a.html":{"lm":123,"rm":456,"sz":100}, ...}}
 */
data class SyncSnapshot(val entries: MutableMap<String, SnapshotEntry> = mutableMapOf()) {

    fun get(relPath: String): SnapshotEntry? = entries[relPath]

    fun put(relPath: String, entry: SnapshotEntry) {
        entries[relPath] = entry
    }

    fun remove(relPath: String) {
        entries.remove(relPath)
    }

    fun toJson(): String {
        val obj = JSONObject()
        val entriesJson = JSONObject()
        entries.forEach { (path, e) ->
            entriesJson.put(
                path,
                JSONObject().put("lm", e.localMtime).put("rm", e.remoteMtime).put("sz", e.size)
            )
        }
        obj.put("entries", entriesJson)
        return obj.toString()
    }

    companion object {
        fun parse(text: String): SyncSnapshot = runCatching {
            val obj = JSONObject(text)
            val entriesJson = obj.optJSONObject("entries") ?: JSONObject()
            val entries = mutableMapOf<String, SnapshotEntry>()
            entriesJson.keys().forEach { path ->
                val e = entriesJson.optJSONObject(path) ?: return@forEach
                entries[path] = SnapshotEntry(
                    localMtime = e.optLong("lm"),
                    remoteMtime = e.optLong("rm"),
                    size = e.optLong("sz")
                )
            }
            SyncSnapshot(entries)
        }.getOrDefault(SyncSnapshot())

        fun empty(): SyncSnapshot = SyncSnapshot()
    }
}
