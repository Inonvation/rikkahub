package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ContextComposition
import me.rerere.rikkahub.data.ai.ContextCompositionStore
import me.rerere.rikkahub.data.db.dao.ContextCompositionDAO
import me.rerere.rikkahub.data.db.entity.ContextCompositionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ContextCompositionStore 是全局单例，测试后必须清理，避免串扰其它用例 */
private fun cleanupStore(id: String) {
    ContextCompositionStore.remove(id)
}

private class FakeContextCompositionDAO : ContextCompositionDAO {
    val map = mutableMapOf<String, ContextCompositionEntity>()

    override suspend fun upsert(entity: ContextCompositionEntity) {
        map[entity.conversationId] = entity
    }

    override suspend fun getById(conversationId: String): ContextCompositionEntity? = map[conversationId]

    override suspend fun deleteByConversationId(conversationId: String) {
        map.remove(conversationId)
    }
}

class ContextCompositionRepositoryTest {

    private val composition = ContextComposition(
        systemTokens = 100,
        builtinToolTokens = 50,
        mcpToolTokens = 30,
        skillToolTokens = 10,
        messageTokens = 200,
    )

    private fun fakeRepo(dao: FakeContextCompositionDAO = FakeContextCompositionDAO()) =
        // Unconfined：save 内 launch 的 dao 调用（伪 DAO 不真正挂起）同步执行完，断言确定
        ContextCompositionRepository(dao, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `save updates process store and persists to db`() {
        val dao = FakeContextCompositionDAO()
        fakeRepo(dao).save("conv-1", composition)
        assertEquals(composition, ContextCompositionStore.get("conv-1"))
        assertEquals(composition, dao.map["conv-1"]?.toComposition())
        cleanupStore("conv-1")
    }

    @Test
    fun `restoreIfAbsent backfills process store from db`() = runBlocking {
        val dao = FakeContextCompositionDAO().apply {
            map["conv-1"] = composition.toEntity("conv-1", updatedAt = 12345L)
        }
        val restored = fakeRepo(dao).restoreIfAbsent("conv-1")
        assertEquals(composition, restored)
        assertEquals(composition, ContextCompositionStore.get("conv-1"))
        cleanupStore("conv-1")
    }

    @Test
    fun `restoreIfAbsent keeps fresh in-memory snapshot over stale db record`() = runBlocking {
        val dao = FakeContextCompositionDAO().apply {
            map["conv-1"] = composition.toEntity("conv-1", updatedAt = 12345L)
        }
        val repo = fakeRepo(dao)
        val fresh = ContextComposition(1, 2, 3, 4, 5)
        repo.save("conv-1", fresh) // 进程内已有本次会话的新快照，不应被库中旧记录覆盖
        assertNull(repo.restoreIfAbsent("conv-1"))
        assertEquals(fresh, ContextCompositionStore.get("conv-1"))
        cleanupStore("conv-1")
    }

    @Test
    fun `restoreIfAbsent returns null when nothing persisted`() = runBlocking {
        assertNull(fakeRepo().restoreIfAbsent("conv-missing"))
        assertNull(ContextCompositionStore.get("conv-missing"))
    }

    @Test
    fun `delete removes process store and db record`() = runBlocking {
        val dao = FakeContextCompositionDAO()
        val repo = fakeRepo(dao)
        repo.save("conv-1", composition)
        repo.delete("conv-1")
        assertNull(ContextCompositionStore.get("conv-1"))
        assertNull(dao.map["conv-1"])
    }

    @Test
    fun `entity round trip preserves all five values`() {
        val entity = composition.toEntity("conv-1", updatedAt = 12345L)
        assertEquals(composition, entity.toComposition())
        assertEquals(12345L, entity.updatedAt)
    }
}