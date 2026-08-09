package me.rerere.rikkahub.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SyncManager.preview()（只读差异检测）单测。
 * 复用 SyncManagerIntegrationTest 里的 InMemorySyncProvider / FakeSettingsAccess / FakeDbAccess。
 */
class SyncManagerPreviewTest {
    private val tmpDir: File = Files.createTempDirectory("sync-manager-preview").toFile()
    private val filesRoot = File(tmpDir, "files")
    private val pendingDir = File(tmpDir, "pending")
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        tmpDir.deleteRecursively()
    }

    private fun createStore(fileName: String, coroutineScope: CoroutineScope): SyncStateStore {
        return SyncStateStore(
            PreferenceDataStoreFactory.create(
                scope = coroutineScope,
                produceFile = { File(tmpDir, fileName) }
            )
        )
    }

    private fun createManager(
        settings: FakeSettingsAccess,
        stateStore: SyncStateStore,
        dbFile: File,
    ): SyncManager {
        return SyncManager(
            settings = settings,
            stateStore = stateStore,
            dbAccess = FakeDbAccess(dbFile),
            filesRoot = filesRoot,
            syncPendingDir = pendingDir,
        )
    }

    private fun newScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
    }

    @Test
    fun `preview on first sync lists all local units as uploads`() = runBlocking {
        val store = createStore("preview-first.preferences_pb", newScope())
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        File(filesRoot, "upload").apply { mkdirs() }
        File(filesRoot, "upload/a.txt").writeText("hello")
        File(filesRoot, "fonts").apply { mkdirs() }
        File(filesRoot, "fonts/f.ttf").writeBytes(byteArrayOf(9, 9))

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)

        val preview = manager.preview(provider, SyncConfig())!!

        assertTrue(preview.uploads.any { it.relPath == "settings.json" })
        assertTrue(preview.uploads.any { it.relPath == "db/rikka_hub.db" })
        assertTrue(preview.uploads.any { it.relPath == "upload/a.txt" })
        assertTrue(preview.uploads.any { it.relPath == "fonts/f.ttf" })
        assertTrue(preview.updates.isEmpty())
        assertTrue(preview.deletions.isEmpty())
        assertTrue(preview.conflicts.isEmpty())
        // 首次同步 settings 无本地文件 → size 0；附件带大小
        assertEquals(5L, preview.uploads.first { it.relPath == "upload/a.txt" }.size)
        assertEquals(4L, preview.uploads.first { it.relPath == "db/rikka_hub.db" }.size)
    }

    @Test
    fun `preview is read-only - does not change sync state or remote`() = runBlocking {
        val store = createStore("preview-ro.preferences_pb", newScope())
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db").apply { writeBytes(byteArrayOf(1)) }
        File(filesRoot, "upload").apply { mkdirs() }
        File(filesRoot, "upload/a.txt").writeText("hello")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)

        // 先建立同步记录
        manager.sync(provider, SyncConfig())
        val remoteCountBefore = provider.remoteFiles.size
        val stateBefore = store.current()
        val syncedFilesBefore = stateBefore.syncedFiles
        val lastSyncBefore = stateBefore.lastSyncTime

        // 调用两次 preview，均不应改任何状态或远端
        val p1 = manager.preview(provider, SyncConfig())
        val p2 = manager.preview(provider, SyncConfig())
        assertTrue(p1 != null && p1.isEmpty)
        assertTrue(p2 != null && p2.isEmpty)

        val stateAfter = store.current()
        assertEquals(syncedFilesBefore, stateAfter.syncedFiles)
        assertEquals(lastSyncBefore, stateAfter.lastSyncTime)
        assertEquals(remoteCountBefore, provider.remoteFiles.size)
        assertTrue(stateAfter.conflicts.isEmpty())
    }

    @Test
    fun `preview maps remote-only change to updates`() = runBlocking {
        val store = createStore("preview-updates.preferences_pb", newScope())
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db").apply { writeBytes(byteArrayOf(1)) }
        File(filesRoot, "upload").apply { mkdirs() }
        File(filesRoot, "upload/a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 模拟其他设备改了远端
        provider.seedRemote("upload/a.txt", "remote-v2".toByteArray(), mtime = System.currentTimeMillis() + 10000)
        val preview = manager.preview(provider, SyncConfig())!!

        assertTrue(preview.updates.any { it.relPath == "upload/a.txt" })
        assertTrue(preview.uploads.isEmpty())
        // 远端文件大小应带入预览
        assertEquals("remote-v2".length.toLong(), preview.updates.first { it.relPath == "upload/a.txt" }.size)
    }

    @Test
    fun `preview maps local delete to deletions`() = runBlocking {
        val store = createStore("preview-delete.preferences_pb", newScope())
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db").apply { writeBytes(byteArrayOf(1)) }
        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 本地删除已同步过的文件
        File(uploadDir, "a.txt").delete()
        val preview = manager.preview(provider, SyncConfig())!!

        assertTrue(preview.deletions.any { it.relPath == "upload/a.txt" })
        assertTrue(preview.uploads.isEmpty())
    }

    @Test
    fun `preview maps both changed to conflicts`() = runBlocking {
        val store = createStore("preview-conflict.preferences_pb", newScope())
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db").apply { writeBytes(byteArrayOf(1)) }
        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 本地改 + 远端也改 → 冲突
        File(uploadDir, "a.txt").writeText("local-v2")
        provider.seedRemote("upload/a.txt", "remote-v2".toByteArray(), mtime = System.currentTimeMillis() + 20000)

        val preview = manager.preview(provider, SyncConfig())!!
        assertTrue(preview.conflicts.any { it.relPath == "upload/a.txt" })
        assertTrue(preview.uploads.isEmpty())
    }

    @Test
    fun `preview after failed list returns null`() = runBlocking {
        val store = createStore("preview-fail.preferences_pb", newScope())
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db").apply { writeBytes(byteArrayOf(1)) }
        val manager = createManager(settings, store, dbFile)

        val failingProvider = object : SyncProvider {
            override suspend fun test(): Result<Unit> = Result.success(Unit)
            override suspend fun ensureBaseCollection(): Result<Unit> = Result.success(Unit)
            override suspend fun listRemote(): Result<List<RemoteFile>> =
                Result.failure(IllegalStateException("boom"))

            override suspend fun upload(relPath: String, content: ByteArray): Result<UploadResult> =
                Result.success(UploadResult(null, null))

            override suspend fun downloadToFile(relPath: String, targetFile: File): Result<Unit> =
                Result.success(Unit)

            override suspend fun head(relPath: String): Result<RemoteFile> =
                Result.failure(IllegalStateException("not found"))

            override suspend fun delete(relPath: String): Result<Unit> = Result.success(Unit)
        }
        val preview = manager.preview(failingProvider, SyncConfig())
        assertNull(preview)
    }

    @Test
    fun `humanLabel maps sync paths to readable names`() {
        assertEquals("设置", SyncManager.humanLabel("settings.json"))
        assertEquals("聊天数据库", SyncManager.humanLabel("db/rikka_hub.db"))
        assertEquals("a.txt", SyncManager.humanLabel("upload/a.txt"))
        assertEquals("f.ttf", SyncManager.humanLabel("fonts/f.ttf"))
        assertEquals("tool/run.kt", SyncManager.humanLabel("skills/tool/run.kt"))
        assertEquals("unknown/path", SyncManager.humanLabel("unknown/path"))
    }
}
