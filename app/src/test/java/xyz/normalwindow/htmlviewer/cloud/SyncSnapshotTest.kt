package xyz.normalwindow.htmlviewer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.normalwindow.htmlviewer.data.cloud.SnapshotEntry
import xyz.normalwindow.htmlviewer.data.cloud.SyncSnapshot

/** 同步快照 JSON 序列化往返测试 */
class SyncSnapshotTest {

    @Test
    fun `序列化与解析往返一致`() {
        val snapshot = SyncSnapshot()
        snapshot.put("a.html", SnapshotEntry(localMtime = 100, remoteMtime = 200, size = 5))
        snapshot.put("dir/b c.html", SnapshotEntry(localMtime = 1, remoteMtime = 2, size = 0))

        val parsed = SyncSnapshot.parse(snapshot.toJson())

        assertEquals(2, parsed.entries.size)
        assertEquals(SnapshotEntry(100, 200, 5), parsed.get("a.html"))
        assertEquals(SnapshotEntry(1, 2, 0), parsed.get("dir/b c.html"))
    }

    @Test
    fun `非法 JSON 返回空快照`() {
        val parsed = SyncSnapshot.parse("not json")
        assertEquals(0, parsed.entries.size)
    }

    @Test
    fun `删除条目`() {
        val snapshot = SyncSnapshot()
        snapshot.put("a.html", SnapshotEntry(1, 2, 3))
        snapshot.remove("a.html")
        assertNull(snapshot.get("a.html"))
        assertTrue(snapshot.toJson().contains("entries"))
    }
}
