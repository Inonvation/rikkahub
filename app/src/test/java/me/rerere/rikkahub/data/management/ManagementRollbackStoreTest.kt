package me.rerere.rikkahub.data.management

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagementRollbackStoreTest {

    @Test
    fun undoReturnsLatestSnapshot() {
        val store = ManagementRollbackStore(maxEntries = 10)
        val first = Settings(init = false)
        val second = Settings(init = false)
        store.record(first, "provider_update", "OpenAI")
        store.record(second, "assistant_update", "Assistant")

        val snapshot = store.undo()

        assertEquals("assistant_update", snapshot?.tool)
        assertEquals("Assistant", snapshot?.target)
        assertEquals(second, snapshot?.settings)
    }

    @Test
    fun undoIsEmptyWhenNothingRecorded() {
        val store = ManagementRollbackStore(maxEntries = 10)

        assertNull(store.undo())
    }

    @Test
    fun undoDrainsSnapshotsInReverseOrder() {
        val store = ManagementRollbackStore(maxEntries = 10)
        store.record(Settings(init = false), "settings_admin_set", "enable_sub_agent")
        store.record(Settings(init = false), "search_admin_add", "Tavily")

        assertEquals("search_admin_add", store.undo()?.tool)
        assertEquals("settings_admin_set", store.undo()?.tool)
        assertNull(store.undo())
    }
}
