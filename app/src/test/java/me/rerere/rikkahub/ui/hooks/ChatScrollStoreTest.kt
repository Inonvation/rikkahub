package me.rerere.rikkahub.ui.hooks

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatScrollStore（会话级滚动位置缓存）的行为契约测试。
 * 纯内存 JVM 类，无 Android 依赖，直接构造即可。
 */
class ChatScrollStoreTest {

    private val c1 = Uuid.random()
    private val c2 = Uuid.random()
    private val c3 = Uuid.random()
    private val anchor = Uuid.random()

    @Test
    fun `save then load round-trips index offset and anchor`() {
        val store = ChatScrollStore()
        store.save(c1, index = 3, offset = 40, anchorMessageId = anchor)

        val loaded = store.load(c1)
        assertEquals(3, loaded?.firstVisibleItemIndex)
        assertEquals(40, loaded?.firstVisibleItemScrollOffset)
        assertEquals(anchor, loaded?.anchorMessageId)
    }

    @Test
    fun `save without anchor stores null anchor`() {
        val store = ChatScrollStore()
        store.save(c1, index = 1, offset = 2)

        assertNull(store.load(c1)?.anchorMessageId)
    }

    @Test
    fun `save coerces negative index and offset to zero`() {
        // 视口滚动参数理论非负，防御负值（异常状态机等）时存 0 而非负坐标
        val store = ChatScrollStore()
        store.save(c1, index = -5, offset = -3)

        val loaded = store.load(c1)!!
        assertEquals(0, loaded.firstVisibleItemIndex)
        assertEquals(0, loaded.firstVisibleItemScrollOffset)
    }

    @Test
    fun `load absent conversation returns null`() {
        assertNull(ChatScrollStore().load(c1))
    }

    @Test
    fun `remove deletes only target conversation`() {
        val store = ChatScrollStore()
        store.save(c1, 0, 0)
        store.save(c2, 1, 1)

        store.remove(c1)

        assertNull(store.load(c1))
        assertEquals(1, store.load(c2)?.firstVisibleItemIndex)
    }

    @Test
    fun `prune keeps only listed conversations`() {
        val store = ChatScrollStore()
        store.save(c1, 0, 0)
        store.save(c2, 0, 0)
        store.save(c3, 0, 0)

        val removed = store.prune(setOf(c1, c3))

        assertEquals(1, removed)
        assertTrue(store.load(c1) != null)
        assertNull(store.load(c2))
        assertTrue(store.load(c3) != null)
    }

    @Test
    fun `prune with all conversations is no-op`() {
        val store = ChatScrollStore()
        store.save(c1, 0, 0)
        store.save(c2, 0, 0)

        assertEquals(0, store.prune(setOf(c1, c2)))
        assertTrue(store.load(c1) != null)
        assertTrue(store.load(c2) != null)
    }

    @Test
    fun `prune with empty keep clears everything`() {
        val store = ChatScrollStore()
        store.save(c1, 0, 0)
        store.save(c2, 0, 0)

        assertEquals(2, store.prune(emptySet()))
        assertNull(store.load(c1))
        assertNull(store.load(c2))
    }
}
