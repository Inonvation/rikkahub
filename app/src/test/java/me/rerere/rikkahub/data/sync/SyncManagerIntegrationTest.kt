package me.rerere.rikkahub.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 内存版 SyncProvider：以内存 Map 模拟远端文件系统。
 * etag 基于内容 sha256（内容不变 etag 不变，内容变 etag 变——符合真实 WebDAV 语义）。
 */
class InMemorySyncProvider : SyncProvider {
    val remoteFiles = LinkedHashMap<String, ByteArray>()
    val remoteMtimes = HashMap<String, Long>()
    private var mtimeCounter = 1000L

    private fun etagOf(content: ByteArray): String =
        "etag-" + MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }.take(16)

    override suspend fun test(): Result<Unit> = Result.success(Unit)
    override suspend fun ensureBaseCollection(): Result<Unit> = Result.success(Unit)

    override suspend fun listRemote(): Result<List<RemoteFile>> = Result.success(
        remoteFiles.map { (relPath, content) ->
            RemoteFile(
                relPath = relPath,
                etag = etagOf(content),
                lastModifiedMs = remoteMtimes[relPath] ?: 0L,
                size = content.size.toLong(),
            )
        }
    )

    override suspend fun upload(relPath: String, content: ByteArray): Result<UploadResult> {
        remoteFiles[relPath] = content
        mtimeCounter += 1000
        remoteMtimes[relPath] = mtimeCounter
        return Result.success(
            UploadResult(
                etag = etagOf(content),
                lastModifiedMs = mtimeCounter,
            )
        )
    }

    override suspend fun downloadToFile(relPath: String, targetFile: File): Result<Unit> {
        val content = remoteFiles[relPath] ?: return Result.failure(IllegalStateException("not found: $relPath"))
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(content)
        return Result.success(Unit)
    }

    override suspend fun head(relPath: String): Result<RemoteFile> {
        val content = remoteFiles[relPath] ?: return Result.failure(IllegalStateException("not found: $relPath"))
        return Result.success(
            RemoteFile(relPath, etagOf(content), remoteMtimes[relPath] ?: 0L, content.size.toLong())
        )
    }

    override suspend fun delete(relPath: String): Result<Unit> {
        remoteFiles.remove(relPath)
        remoteMtimes.remove(relPath)
        return Result.success(Unit)
    }

    /** 测试辅助：种子远端文件（模拟其他设备已上传）。 */
    fun seedRemote(relPath: String, content: ByteArray, mtime: Long = 1000L) {
        remoteFiles[relPath] = content
        remoteMtimes[relPath] = mtime
    }

    /** 测试辅助：种子远端数据库（db + db.meta.json，模拟其他设备已上传的 DB）。 */
    fun seedDatabase(dbBytes: ByteArray, timestamp: Long) {
        val sha = MessageDigest.getInstance("SHA-256").digest(dbBytes)
            .joinToString("") { "%02x".format(it) }
        seedRemote("db/rikka_hub.db", dbBytes, mtime = timestamp)
        seedRemote(
            "db.meta.json",
            """{"sha256":"$sha","timestamp":$timestamp,"deviceId":"other","size":${dbBytes.size}}""".toByteArray(),
            mtime = timestamp,
        )
    }
}

/** 内存版 settings 访问。 */
class FakeSettingsAccess(private var settings: Settings = Settings()) : SyncSettingsAccess {
    var saveCount = 0
        private set

    override suspend fun currentSettings(): Settings = settings
    override suspend fun saveSettings(s: Settings) {
        settings = s
        saveCount++
    }
}

/** 临时文件版 DB 访问。 */
class FakeDbAccess(
    private val dbFile: File,
    private val localHasData: Boolean = false,
) : DbSyncAccess {
    override fun checkpoint() {}
    override fun dbFile(): File = dbFile
    override suspend fun hasLocalData(): Boolean = localHasData
}

class SyncManagerIntegrationTest {
    private val tmpDir: File = Files.createTempDirectory("sync-manager-it").toFile()
    private val filesRoot = File(tmpDir, "files")
    private val pendingDir = File(tmpDir, "pending")
    private var scope: CoroutineScope? = null

    private fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

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
        localHasData: Boolean = false,
    ): SyncManager {
        return SyncManager(
            settings = settings,
            stateStore = stateStore,
            dbAccess = FakeDbAccess(dbFile, localHasData),
            filesRoot = filesRoot,
            syncPendingDir = pendingDir,
        )
    }

    @Test
    fun `first sync pushes all local files`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("push.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("hello")
        val fontsDir = File(filesRoot, "fonts").apply { mkdirs() }
        File(fontsDir, "f.ttf").writeBytes(byteArrayOf(9, 9))

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)

        val result = manager.sync(provider, SyncConfig())

        assertTrue(result.success)
        assertTrue(result.pushed.contains("settings.json"))
        assertTrue(result.pushed.contains("db/rikka_hub.db"))
        assertTrue(result.pushed.contains("upload/a.txt"))
        assertTrue(result.pushed.contains("fonts/f.ttf"))
        assertEquals("hello", String(provider.remoteFiles["upload/a.txt"]!!))
        // settings.json + db/rikka_hub.db + db.meta.json + upload/a.txt + fonts/f.ttf
        assertEquals(5, provider.remoteFiles.size)

        // syncedFiles 记录建立
        val state = store.current()
        assertEquals(4, state.syncedFiles.size)
        assertTrue(state.lastSyncTime > 0)
    }

    @Test
    fun `second sync skips when nothing changed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("skip.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("hello")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)

        val first = manager.sync(provider, SyncConfig())
        assertTrue(first.success)
        val pushedCountAfterFirst = provider.remoteFiles.size

        val second = manager.sync(provider, SyncConfig())
        assertTrue(second.success)
        assertTrue(second.pushed.isEmpty())
        assertTrue(second.pulled.isEmpty())
        assertEquals(pushedCountAfterFirst, provider.remoteFiles.size)
    }

    @Test
    fun `local change pushes update`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("local-change.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 本地修改
        File(uploadDir, "a.txt").writeText("v2")
        val result = manager.sync(provider, SyncConfig())
        assertTrue(result.success)
        assertTrue(result.pushed.contains("upload/a.txt"))
        assertEquals("v2", String(provider.remoteFiles["upload/a.txt"]!!))
    }

    @Test
    fun `remote change pulls to local`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("remote-change.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 模拟其他设备改了远端
        provider.seedRemote("upload/a.txt", "remote-v2".toByteArray(), mtime = System.currentTimeMillis() + 10000)

        val result = manager.sync(provider, SyncConfig())
        assertTrue(result.success)
        assertTrue(result.pulled.contains("upload/a.txt"))
        assertEquals("remote-v2", File(uploadDir, "a.txt").readText())
    }

    @Test
    fun `pull records remoteTag so next sync skips`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("pull-tag.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 远端被改 → pull
        provider.seedRemote("upload/a.txt", "remote-v2".toByteArray(), mtime = System.currentTimeMillis() + 10000)
        val pullResult = manager.sync(provider, SyncConfig())
        assertTrue(pullResult.pulled.contains("upload/a.txt"))

        // 本地、远端都不再变 → 二次同步应 Skip（不再重复 pull）
        val skipResult = manager.sync(provider, SyncConfig())
        assertTrue(skipResult.success)
        assertTrue(skipResult.pulled.isEmpty())
        assertTrue(skipResult.pushed.isEmpty())
    }

    @Test
    fun `local delete propagates to remote`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("delete.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())
        assertTrue(provider.remoteFiles.containsKey("upload/a.txt"))

        // 本地删除
        File(uploadDir, "a.txt").delete()
        val result = manager.sync(provider, SyncConfig())
        assertTrue(result.success)
        assertTrue(result.deleted.contains("upload/a.txt"))
        assertFalse(provider.remoteFiles.containsKey("upload/a.txt"))
        assertFalse(store.current().syncedFiles.containsKey("upload/a.txt"))
    }

    @Test
    fun `both changed produces conflict copy locally`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("conflict.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 本地改 + 远端也改 → 冲突
        File(uploadDir, "a.txt").writeText("local-v2")
        provider.seedRemote("upload/a.txt", "remote-v2".toByteArray(), mtime = System.currentTimeMillis() + 20000)

        val result = manager.sync(provider, SyncConfig())
        assertTrue(result.success)
        assertTrue(result.conflicts.contains("upload/a.txt"))
        // 本地应留有 .conflict- 副本（冲突发生时本地保留在 .conflict 文件）
        val conflictFile = File(uploadDir, "a.txt.conflict-" + store.current().deviceId)
        assertTrue(conflictFile.exists())
        assertNotNull(conflictFile)
    }

    @Test
    fun `db pull sets dbRestorePending`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("db-pull.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val provider = InMemorySyncProvider()
        // 远端已有一个 db（timestamp 远大于本地，保证 pull 而非 push）
        provider.seedDatabase(byteArrayOf(5, 6, 7), timestamp = System.currentTimeMillis() + 100000)
        provider.seedRemote("settings.json", "{}".toByteArray(), mtime = System.currentTimeMillis() + 100000)

        val manager = createManager(settings, store, dbFile)
        val result = manager.sync(provider, SyncConfig())

        assertTrue(result.success)
        assertTrue(result.pulled.contains("db/rikka_hub.db"))
        val pending = store.current().dbRestorePending
        assertNotNull(pending)
        val pendingFile = File(pending!!)
        assertTrue(pendingFile.exists())
        assertEquals(3, pendingFile.readBytes().size)
    }

    @Test
    fun `db first sync with existing local data pushes instead of pulling remote`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("db-first-push.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val provider = InMemorySyncProvider()
        // 远端已有一份 db（本地从未同步过、但本地已有真实聊天数据）
        provider.seedDatabase(byteArrayOf(9, 9), timestamp = System.currentTimeMillis() + 100000)
        provider.seedRemote("settings.json", "{}".toByteArray(), mtime = System.currentTimeMillis() + 100000)

        // 本地已有数据：首次同步必须以本地为准（push），绝不 pull 覆盖本机数据
        val manager = createManager(settings, store, dbFile, localHasData = true)
        val result = manager.sync(provider, SyncConfig())

        assertTrue(result.success)
        assertTrue(result.pushed.contains("db/rikka_hub.db"))
        assertFalse(result.pulled.contains("db/rikka_hub.db"))
        // 不产生 dbRestorePending（不会在下次启动用远端库覆盖本地）
        assertNull(store.current().dbRestorePending)
        // 远端 db 已被本机内容覆盖
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), provider.remoteFiles["db/rikka_hub.db"]!!.toList())
    }

    @Test
    fun `db push writes meta with matching sha`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("db-push-meta.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)

        val result = manager.sync(provider, SyncConfig())
        assertTrue(result.success)
        assertTrue(result.pushed.contains("db/rikka_hub.db"))

        // 远端应有 db.meta.json 且 sha256 与 db 内容一致
        val metaBytes = provider.remoteFiles["db.meta.json"]
        assertNotNull(metaBytes)
        val meta = JsonInstant.decodeFromString<DbMeta>(String(metaBytes!!, Charsets.UTF_8))
        val expectedSha = sha256(byteArrayOf(1, 2, 3, 4))
        assertEquals(expectedSha, meta.sha256)
        assertTrue(meta.timestamp > 0)
    }

    @Test
    fun `db pull validates sha against meta`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("db-pull-validate.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val provider = InMemorySyncProvider()
        val timestamp = System.currentTimeMillis() + 100000
        // 先正常 seed 一次（meta 与内容匹配）
        provider.seedDatabase(byteArrayOf(9, 9, 9), timestamp)
        val manager = createManager(settings, store, dbFile)
        val result = manager.sync(provider, SyncConfig())
        assertTrue(result.success)
        assertTrue(result.pulled.contains("db/rikka_hub.db"))
    }

    @Test
    fun `db double change conflict keeps local old copy`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("db-conflict.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 本地改 db，远端另一设备也改了 db → 冲突
        dbFile.writeBytes(byteArrayOf(1, 1))
        provider.seedDatabase(byteArrayOf(2, 2, 2), timestamp = System.currentTimeMillis() + 200000)

        val result = manager.sync(provider, SyncConfig())
        assertTrue(result.success)
        assertTrue(result.conflicts.contains("db/rikka_hub.db"))

        // 冲突应写入 SyncStateStore.conflicts
        val conflicts = store.current().conflicts
        assertTrue(conflicts.any { it.relPath == "db/rikka_hub.db" })
    }

    @Test
    fun `settings pull merges into local`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("settings-pull.preferences_pb", scope)
        val local = Settings()
        val settings = FakeSettingsAccess(local)
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val provider = InMemorySyncProvider()
        // 远端有白名单字段 themeId
        provider.seedRemote("settings.json", """{"themeId":"material"}""".toByteArray(), mtime = System.currentTimeMillis() + 100000)

        val manager = createManager(settings, store, dbFile)
        val result = manager.sync(provider, SyncConfig())

        assertTrue(result.success)
        assertEquals("material", settings.currentSettings().themeId)
        assertTrue(settings.saveCount > 0)
    }

    @Test
    fun `disabling a data type keeps its remote files untouched`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("disable-type.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("v1")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        // 首次全量同步，建立远端
        manager.sync(provider, SyncConfig())
        assertTrue(provider.remoteFiles.containsKey("upload/a.txt"))

        // 关闭聊天附件同步：远端 upload 文件不应被误删，也不参与后续同步
        val config = SyncConfig().copy(includeChatFiles = false)
        val result = manager.sync(provider, config)
        assertTrue(result.success)
        assertTrue(result.deleted.isEmpty())
        assertTrue(provider.remoteFiles.containsKey("upload/a.txt"))
    }

    @Test
    fun `sync record stores local mtime and size for fast check`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("fastcheck-record.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("hello")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        val rec = store.current().syncedFiles["upload/a.txt"]
        assertTrue(rec != null)
        assertTrue(rec!!.localMtime > 0)
        assertEquals(5L, rec.localSize)
    }

    @Test
    fun `file content change with same size is still detected`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@SyncManagerIntegrationTest.scope = scope
        val store = createStore("fastcheck-content.preferences_pb", scope)
        val settings = FakeSettingsAccess()
        val dbFile = File(tmpDir, "rikka_hub.db")
        dbFile.writeBytes(byteArrayOf(1))

        val uploadDir = File(filesRoot, "upload").apply { mkdirs() }
        File(uploadDir, "a.txt").writeText("hello")

        val provider = InMemorySyncProvider()
        val manager = createManager(settings, store, dbFile)
        manager.sync(provider, SyncConfig())

        // 内容变但大小相同（"hello"→"world"），mtime 也变 → 快检失效回退完整 hash → 仍应检测到 push
        Thread.sleep(5)
        File(uploadDir, "a.txt").writeText("world")
        val result = manager.sync(provider, SyncConfig())
        assertTrue(result.success)
        assertTrue(result.pushed.contains("upload/a.txt"))
        assertEquals("world", String(provider.remoteFiles["upload/a.txt"]!!))
    }
}
