package xyz.normalwindow.htmlviewer.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.normalwindow.htmlviewer.data.cloud.CloudFile
import xyz.normalwindow.htmlviewer.data.cloud.CloudProvider
import xyz.normalwindow.htmlviewer.data.cloud.CloudProviderType
import xyz.normalwindow.htmlviewer.data.cloud.CloudSyncEngine
import xyz.normalwindow.htmlviewer.data.cloud.ConflictChoice
import xyz.normalwindow.htmlviewer.data.cloud.SyncConflictPolicy
import xyz.normalwindow.htmlviewer.data.cloud.SyncSnapshot
import java.io.File

/**
 * 双向同步引擎计划与执行测试:用内存 FakeProvider + 临时目录,
 * 覆盖 上传/下载/传播删除/冲突策略/快照判断 等核心场景。
 */
class CloudSyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = CloudSyncEngine()

    /** 内存版 Provider:remote 保存文件内容与 mtime */
    private class FakeProvider : CloudProvider {
        override val type = CloudProviderType.BAIDU
        val remote = mutableMapOf<String, ByteArray>()
        val remoteMtime = mutableMapOf<String, Long>()
        val dirs = mutableSetOf<String>()

        fun put(path: String, content: ByteArray, mtime: Long) {
            remote[path] = content
            remoteMtime[path] = mtime
        }

        override suspend fun checkAuth() {}

        override suspend fun list(dir: String): Result<List<CloudFile>> {
            val prefix = if (dir.isBlank()) "" else "$dir/"
            val children = (remote.keys + dirs)
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .filter { it.isNotBlank() && !it.contains('/') }
            return Result.success(children.map { seg ->
                val full = prefix + seg
                CloudFile(
                    path = full, name = seg, isDir = dirs.contains(full),
                    size = remote[full]?.size?.toLong() ?: 0L,
                    mtime = remoteMtime[full] ?: 0L
                )
            })
        }

        override suspend fun download(relPath: String, dest: File): Result<Unit> =
            runCatching {
                dest.parentFile?.mkdirs()
                dest.writeBytes(remote.getValue(relPath))
            }

        override suspend fun upload(relPath: String, src: File): Result<Long> =
            runCatching {
                remote[relPath] = src.readBytes()
                val now = System.currentTimeMillis() / 1000
                remoteMtime[relPath] = now
                now
            }

        override suspend fun mkdirs(relPath: String): Result<Unit> =
            runCatching { dirs.add(relPath) }

        override suspend fun delete(relPath: String): Result<Unit> = runCatching {
            remote.remove(relPath)
            remoteMtime.remove(relPath)
            dirs.remove(relPath)
        }

        override suspend fun meta(relPath: String): Result<CloudFile?> = Result.success(null)
    }

    private fun localFile(root: File, rel: String, content: String, mtimeSec: Long? = null): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        mtimeSec?.let { f.setLastModified(it * 1000) }
        return f
    }

    @Test
    fun `首次同步 - 本地新文件上传`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        localFile(root, "a.html", "hello")

        val outcome = engine.sync(
            root, tmp.newFolder("trash"), provider,
            SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS,
            onProgress = {}
        )

        assertEquals(1, outcome.result.uploaded)
        assertEquals("hello", String(provider.remote.getValue("a.html")))
        assertNotNull(outcome.snapshot.get("a.html"))
    }

    @Test
    fun `首次同步 - 云端新文件下载`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        provider.put("b.html", "cloud".toByteArray(), 1000)

        val outcome = engine.sync(
            root, tmp.newFolder("trash"), provider,
            SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS,
            onProgress = {}
        )

        assertEquals(1, outcome.result.downloaded)
        assertEquals("cloud", File(root, "b.html").readText())
    }

    @Test
    fun `首次同步 - 两侧存在且大小一致视为未改动`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        localFile(root, "same.html", "abc")
        provider.put("same.html", "abc".toByteArray(), 500)

        val outcome = engine.sync(
            root, tmp.newFolder("trash"), provider,
            SyncSnapshot.empty(), SyncConflictPolicy.ASK,
            onProgress = {}
        )

        assertEquals(0, outcome.result.uploaded)
        assertEquals(0, outcome.result.downloaded)
        assertEquals(0, outcome.result.conflicts.size)
        assertNotNull(outcome.snapshot.get("same.html"))
    }

    @Test
    fun `同步后本地修改 - 触发上传`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        val trash = tmp.newFolder("trash")
        val f = localFile(root, "a.html", "v1")
        val first = engine.sync(root, trash, provider, SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS, onProgress = {})

        // 模拟本地修改(mtime 变化)
        val baseMtime = first.snapshot.get("a.html")!!.localMtime
        f.writeText("v2")
        f.setLastModified((baseMtime + 120) * 1000)

        val second = engine.sync(
            root, trash, provider, first.snapshot, SyncConflictPolicy.NEWER_WINS,
            onProgress = {}
        )

        assertEquals(1, second.result.uploaded)
        assertEquals("v2", String(provider.remote.getValue("a.html")))
    }

    @Test
    fun `同步后云端修改 - 触发下载`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        val trash = tmp.newFolder("trash")
        provider.put("a.html", "v1".toByteArray(), 1000)
        val first = engine.sync(root, trash, provider, SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS, onProgress = {})

        // 模拟云端修改(mtime 变化)
        provider.put("a.html", "v2".toByteArray(), first.snapshot.get("a.html")!!.remoteMtime + 120)

        val second = engine.sync(
            root, trash, provider, first.snapshot, SyncConflictPolicy.NEWER_WINS,
            onProgress = {}
        )

        assertEquals(1, second.result.downloaded)
        assertEquals("v2", File(root, "a.html").readText())
    }

    @Test
    fun `同步后本地删除 - 传播删除云端`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        val trash = tmp.newFolder("trash")
        localFile(root, "a.html", "v1")
        val first = engine.sync(root, trash, provider, SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS, onProgress = {})

        File(root, "a.html").delete()

        val second = engine.sync(
            root, trash, provider, first.snapshot, SyncConflictPolicy.NEWER_WINS,
            onProgress = {}
        )

        assertEquals(1, second.result.deleted)
        assertFalse(provider.remote.containsKey("a.html"))
    }

    @Test
    fun `同步后云端删除 - 本地文件移入回收站目录`() = runBlocking {
        val root = tmp.newFolder("local")
        val trash = tmp.newFolder("trash")
        val provider = FakeProvider()
        provider.put("a.html", "v1".toByteArray(), 1000)
        val first = engine.sync(root, trash, provider, SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS, onProgress = {})

        provider.delete("a.html")

        val second = engine.sync(root, trash, provider, first.snapshot, SyncConflictPolicy.NEWER_WINS, onProgress = {})

        assertEquals(1, second.result.deleted)
        assertFalse(File(root, "a.html").exists())
        assertTrue(trash.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `冲突 - 新者胜本地较新则上传`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        localFile(root, "a.html", "local-version", mtimeSec = 2000)
        provider.put("a.html", "remote-version".toByteArray(), 1000)

        val outcome = engine.sync(
            root, tmp.newFolder("trash"), provider,
            SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS,
            onProgress = {}
        )

        assertEquals(1, outcome.result.uploaded)
        assertEquals("local-version", String(provider.remote.getValue("a.html")))
    }

    @Test
    fun `冲突 - 新者胜云端较新则下载`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        localFile(root, "a.html", "local-version", mtimeSec = 1000)
        provider.put("a.html", "remote-version".toByteArray(), 2000)

        val outcome = engine.sync(
            root, tmp.newFolder("trash"), provider,
            SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS,
            onProgress = {}
        )

        assertEquals(1, outcome.result.downloaded)
        assertEquals("remote-version", File(root, "a.html").readText())
    }

    @Test
    fun `冲突 - 每次询问遵循用户选择(保留云端)`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        localFile(root, "a.html", "local-version", mtimeSec = 2000)
        provider.put("a.html", "remote-version".toByteArray(), 3000)

        val outcome = engine.sync(
            root, tmp.newFolder("trash"), provider,
            SyncSnapshot.empty(), SyncConflictPolicy.ASK,
        ) { mapOf("a.html" to ConflictChoice.USE_REMOTE) }

        assertEquals(1, outcome.result.downloaded)
        assertEquals("remote-version", File(root, "a.html").readText())
    }

    @Test
    fun `冲突 - 保留双方生成冲突副本且本地版本覆盖云端`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        localFile(root, "a.html", "local-version", mtimeSec = 1000)
        provider.put("a.html", "remote-version".toByteArray(), 2000)

        val outcome = engine.sync(
            root, tmp.newFolder("trash"), provider,
            SyncSnapshot.empty(), SyncConflictPolicy.KEEP_BOTH,
            onProgress = {}
        )

        // 本地出现云端版本的冲突副本
        val copies = File(root, ".").listFiles()!!
            .filter { it.name != "a.html" && it.name.contains("冲突") }
        assertEquals(1, copies.size)
        assertEquals("remote-version", copies[0].readText())
        // 原路径上传本地版本到云端
        assertEquals("local-version", String(provider.remote.getValue("a.html")))
        assertEquals(1, outcome.result.uploaded)
    }

    @Test
    fun `隐藏目录与回收站不参与同步`() = runBlocking {
        val root = tmp.newFolder("local")
        val provider = FakeProvider()
        localFile(root, ".htmlviewer_cache/x.js", "cache")
        localFile(root, "normal.html", "ok")

        val outcome = engine.sync(
            root, tmp.newFolder("trash"), provider,
            SyncSnapshot.empty(), SyncConflictPolicy.NEWER_WINS,
            onProgress = {}
        )

        assertEquals(1, outcome.result.uploaded)
        assertFalse(provider.remote.containsKey(".htmlviewer_cache/x.js"))
    }

    @Test
    fun `引擎隐藏路径判断`() {
        assertTrue(CloudSyncEngine.isHidden("a/.htmlviewer_cache/x"))
        assertFalse(CloudSyncEngine.isHidden("a/normal.html"))
    }
}
