package me.rerere.rikkahub.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 会话增量上传集成测试（C1 出口验证）：
 * 用内存 provider + 内存 ConversationSyncAccess 驱动 SyncManager，
 * 验证「发一条消息只重传该会话文件」的增量语义。
 */
class ConversationSyncUploadTest {
    private val tmpDir: File = Files.createTempDirectory("conv-sync-it").toFile()
    private val filesRoot = File(tmpDir, "files")
    private val pendingDir = File(tmpDir, "pending")
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        tmpDir.deleteRecursively()
    }

    private fun config() = SyncConfig(
        enabled = true,
        includeSettings = false,
        includeDatabase = false,
        includeChatFiles = false,
        includeSkills = false,
        includeFonts = false,
        includeConversations = true,
    )

    private fun createStore(name: String, coroutineScope: CoroutineScope): SyncStateStore {
        return SyncStateStore(
            PreferenceDataStoreFactory.create(
                scope = coroutineScope,
                produceFile = { File(tmpDir, "$name.preferences_pb") }
            )
        )
    }

    private fun createManager(stateStore: SyncStateStore, access: ConversationSyncAccess): SyncManager {
        return SyncManager(
            settings = FakeSettingsAccess(),
            stateStore = stateStore,
            dbAccess = FakeDbAccess(File(tmpDir, "db")),
            filesRoot = filesRoot,
            syncPendingDir = pendingDir,
            logger = { println("SYNC-LOG: $it") },
            conversationAccess = access,
        )
    }

    private fun entry(id: String, syncUpdatedAt: Long, messageCount: Int = 0) = ConversationIndexEntry(
        id = id, syncUpdatedAt = syncUpdatedAt, title = "t", createAt = 0L, isPinned = false,
        messageCount = messageCount, path = conversationItemPath(id),
    )

    private fun item(id: String, syncUpdatedAt: Long) = ConversationSyncItem(
        id = id, syncUpdatedAt = syncUpdatedAt, title = "t", createAt = 0L, isPinned = false,
        assistantId = "a", customSystemPrompt = null, modeInjectionIds = emptySet(), lorebookIds = emptySet(),
        workspaceCwd = null, folderId = null, groupId = null, nodes = emptyList(),
    )

    /** 内存版 ConversationSyncAccess：本地会话清单 + 可导出条目 + 墓碑。 */
    class FakeConversationAccess : ConversationSyncAccess {
        val local = mutableListOf<ConversationIndexEntry>()
        val items = mutableMapOf<String, ConversationSyncItem>()
        val tombstones = mutableListOf<ConversationTombstone>()

        override suspend fun listLocalIndexEntries(): List<ConversationIndexEntry> = local.toList()
        override suspend fun exportConversation(id: String): ConversationSyncItem? = items[id]
        override suspend fun localDeletedTombstones(): List<ConversationTombstone> = tombstones.toList()
        override suspend fun conversationSize(id: String): Long? =
            items[id]?.let { encodeConversationItem(it).size.toLong() }

        var mergedCount = 0
        override suspend fun mergeConversation(item: ConversationSyncItem): Boolean {
            mergedCount++
            items[item.id] = item
            return true
        }

        override suspend fun deleteRemoteConversation(id: String) {
            local.removeAll { it.id == id }
            tombstones.removeAll { it.id == id }
        }

        override suspend fun clearLocalTombstones(ids: Set<String>) {
            tombstones.removeAll { it.id in ids }
        }
    }

    @Test
    fun `preview includes conversation diffs`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        access.local += entry("c1", 10L)
        access.items["c1"] = item("c1", 10L)
        val manager = createManager(createStore("s5", scope!!), access)

        val preview = manager.preview(provider, config())

        assertTrue(preview != null)
        val convItem = preview!!.uploads.firstOrNull { it.relPath == conversationItemPath("c1") }
        assertTrue(convItem != null)
        assertTrue(convItem!!.size > 0L)
        assertTrue(preview.totalSize > 0L)
    }

    @Test
    fun `fresh device downloads and merges remote conversations`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        // 本地为空（新设备），云端已有 index + item
        provider.seedRemote(conversationItemPath("c1"), encodeConversationItem(item("c1", 50L)))
        provider.seedRemote(
            CONVERSATION_INDEX_PATH,
            encodeConversationIndex(ConversationIndex(conversations = listOf(entry("c1", 50L)))),
        )

        val result = createManager(createStore("s6", scope!!), access).sync(provider, config())

        assertTrue(result.success)
        assertTrue(access.mergedCount >= 1)
    }

    @Test
    fun `upload merges before overwriting remote newer conversation`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        access.local += entry("c1", 10L)
        access.items["c1"] = item("c1", 10L)
        // 云端同一会话版本 50（其他设备已更新）
        provider.seedRemote(conversationItemPath("c1"), encodeConversationItem(item("c1", 50L)))
        provider.seedRemote(
            CONVERSATION_INDEX_PATH,
            encodeConversationIndex(ConversationIndex(conversations = listOf(entry("c1", 50L)))),
        )

        val result = createManager(createStore("s7", scope!!), access).sync(provider, config())

        assertTrue(result.success)
        assertTrue("expected merge-before-upload, mergedCount=${access.mergedCount}", access.mergedCount >= 1)
    }

    @Test
    fun `remote deletion is applied to local`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        access.local += entry("c1", 10L)
        access.items["c1"] = item("c1", 10L)
        // 云端 index 标记 c1 已删除
        provider.seedRemote(
            CONVERSATION_INDEX_PATH,
            encodeConversationIndex(
                ConversationIndex(
                    conversations = listOf(entry("c1", 10L)),
                    deleted = listOf(ConversationTombstone(id = "c1", deletedAt = 99L)),
                )
            ),
        )

        val result = createManager(createStore("s8", scope!!), access).sync(provider, config())

        assertTrue(result.success)
        assertTrue(access.local.none { it.id == "c1" })
    }

    @Test
    fun `incremental sync takes over database and skips whole db upload`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        // 本地 db 文件存在，且 includeDatabase=true；但聊天增量同步开启 → 整库应被跳过
        val dbFile = File(tmpDir, "db")
        dbFile.writeBytes("db-content".toByteArray())
        val cfg = config().copy(includeDatabase = true) // includeConversations 默认 true

        val result = createManager(createStore("s9", scope!!), access).sync(provider, cfg)

        assertTrue(result.success)
        assertFalse(provider.remoteFiles.containsKey("db/rikka_hub.db"))
    }

    @Test
    fun `closing incremental sync restores whole db sync`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        val dbFile = File(tmpDir, "db")
        dbFile.writeBytes("db-content".toByteArray())
        val cfg = config().copy(includeDatabase = true, includeConversations = false)

        val result = createManager(createStore("s10", scope!!), access).sync(provider, cfg)

        assertTrue(result.success)
        assertTrue(provider.remoteFiles.containsKey("db/rikka_hub.db"))
    }

    @Test
    fun `corrupt remote index is not overwritten`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        access.local += entry("c1", 10L)
        access.items["c1"] = item("c1", 10L)
        // 云端 index 存在但损坏（截断/未来版本）
        provider.seedRemote(CONVERSATION_INDEX_PATH, "not valid json".toByteArray())

        val result = createManager(createStore("s11", scope!!), access).sync(provider, config())

        assertTrue(result.success)
        // 损坏 index 未被覆盖，会话 item 也未上传（跳过整个会话同步）
        assertEquals("not valid json", provider.remoteFiles[CONVERSATION_INDEX_PATH]!!.decodeToString())
        assertFalse(provider.remoteFiles.containsKey(conversationItemPath("c1")))
    }

    @Test
    fun `first sync uploads all conversations and index`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        access.local += entry("c1", 10L)
        access.local += entry("c2", 20L)
        access.items["c1"] = item("c1", 10L)
        access.items["c2"] = item("c2", 20L)

        val result = createManager(createStore("s1", scope!!), access).sync(provider, config())

        assertTrue(result.success)
        assertTrue(provider.remoteFiles.containsKey(conversationItemPath("c1")))
        assertTrue(provider.remoteFiles.containsKey(conversationItemPath("c2")))
        val index = decodeConversationIndex(provider.remoteFiles[CONVERSATION_INDEX_PATH]!!)
        assertEquals(setOf("c1", "c2"), index?.conversations?.map { it.id }?.toSet())
    }

    @Test
    fun `no change does not reupload anything`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        access.local += entry("c1", 10L)
        access.items["c1"] = item("c1", 10L)
        val manager = createManager(createStore("s2", scope!!), access)

        manager.sync(provider, config())
        val c1Mtime = provider.remoteMtimes[conversationItemPath("c1")]
        val indexBytes = provider.remoteFiles[CONVERSATION_INDEX_PATH]!!.toList()

        manager.sync(provider, config())

        // 会话无变化：item 不重传、index 不重写
        assertEquals(c1Mtime, provider.remoteMtimes[conversationItemPath("c1")])
        assertEquals(indexBytes, provider.remoteFiles[CONVERSATION_INDEX_PATH]!!.toList())
    }

    @Test
    fun `changed conversation is the only one reuploaded`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        access.local += entry("c1", 10L)
        access.local += entry("c2", 20L)
        access.items["c1"] = item("c1", 10L)
        access.items["c2"] = item("c2", 20L)
        val manager = createManager(createStore("s3", scope!!), access)

        manager.sync(provider, config())
        val c1Mtime = provider.remoteMtimes[conversationItemPath("c1")]
        val c2Mtime = provider.remoteMtimes[conversationItemPath("c2")]

        // 只改动 c2（模拟发消息后版本推进）
        access.local.removeAll { it.id == "c2" }
        access.local += entry("c2", 99L)
        access.items["c2"] = item("c2", 99L)
        manager.sync(provider, config())

        // c1 未被重传（mtime 不变），c2 重传（mtime 前进），index 更新为 c2 新版本
        assertEquals(c1Mtime, provider.remoteMtimes[conversationItemPath("c1")])
        assertTrue(provider.remoteMtimes[conversationItemPath("c2")]!! > c2Mtime!!)
        val index = decodeConversationIndex(provider.remoteFiles[CONVERSATION_INDEX_PATH]!!)
        assertEquals(99L, index?.entryById("c2")?.syncUpdatedAt)
        assertEquals(10L, index?.entryById("c1")?.syncUpdatedAt)
    }

    @Test
    fun `deleted conversation tombstone propagates to index`() = runBlocking {
        scope = CoroutineScope(SupervisorJob())
        val provider = InMemorySyncProvider()
        val access = FakeConversationAccess()
        access.local += entry("c1", 10L)
        access.items["c1"] = item("c1", 10L)
        val manager = createManager(createStore("s4", scope!!), access)

        manager.sync(provider, config())

        // 本地删除 c1：从清单移除 + 记录墓碑
        access.local.removeAll { it.id == "c1" }
        access.tombstones += ConversationTombstone("c1", deletedAt = 30L)
        manager.sync(provider, config())

        val index = decodeConversationIndex(provider.remoteFiles[CONVERSATION_INDEX_PATH]!!)
        assertEquals(listOf("c1"), index?.deleted?.map { it.id })
        assertEquals(0, index?.activeEntries()?.size)
    }
}
