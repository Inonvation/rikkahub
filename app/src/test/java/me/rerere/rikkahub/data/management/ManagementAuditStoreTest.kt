package me.rerere.rikkahub.data.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementAuditStoreTest {

    @Test
    fun recentReturnsNewestFirstWithinLimit() {
        val store = ManagementAuditStore(maxEntries = 3)

        store.record("provider_update", "OpenAI", "ok")
        store.record("assistant_update", "Assistant", "ok")
        store.record("search_admin_add", "Tavily", "ok")
        store.record("settings_admin_set", "enable_sub_agent", "ok")

        val recent = store.recent(limit = 10)

        assertEquals(3, recent.size)
        assertEquals("settings_admin_set", recent[0].tool)
        assertEquals("search_admin_add", recent[1].tool)
        assertEquals("assistant_update", recent[2].tool)
    }

    @Test
    fun recentHonorsLimit() {
        val store = ManagementAuditStore(maxEntries = 10)
        repeat(5) { index ->
            store.record("tool_$index", "target_$index", "ok")
        }

        val recent = store.recent(limit = 2)

        assertEquals(2, recent.size)
        assertEquals("tool_4", recent[0].tool)
        assertEquals("tool_3", recent[1].tool)
    }

    @Test
    fun clearRemovesAllEntries() {
        val store = ManagementAuditStore(maxEntries = 10)
        store.record("provider_update", "OpenAI", "ok")

        store.clear()

        assertTrue(store.recent().isEmpty())
    }
}
