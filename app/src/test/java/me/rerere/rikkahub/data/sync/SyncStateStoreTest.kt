package me.rerere.rikkahub.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SyncStateStoreTest {
    private val tmpDir: File = Files.createTempDirectory("sync-state-store-test").toFile()

    private fun createStore(fileName: String, scope: CoroutineScope): SyncStateStore {
        return SyncStateStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(tmpDir, fileName) }
            )
        )
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `default state is empty`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = createStore("default.preferences_pb", scope)
            val state = store.current()
            assertEquals("", state.deviceId)
            assertFalse(state.syncInProgress)
            assertFalse(state.pendingSync)
            assertTrue(state.syncedFiles.isEmpty())
            assertTrue(state.conflicts.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `device id is generated once and stable`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = createStore("device-id.preferences_pb", scope)
            val id1 = store.getOrCreateDeviceId()
            assertTrue(id1.isNotBlank())
            val id2 = store.getOrCreateDeviceId()
            assertEquals(id1, id2)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sync lock is mutually exclusive`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = createStore("lock.preferences_pb", scope)
            assertTrue(store.tryAcquireSyncLock())
            assertFalse(store.tryAcquireSyncLock())
            store.releaseSyncLock()
            assertFalse(store.current().syncInProgress)
            assertTrue(store.tryAcquireSyncLock())
            assertTrue(store.current().syncInProgress)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `update is atomic read modify write`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = createStore("update.preferences_pb", scope)
            val result = store.update {
                it.copy(pendingSync = true, lastSyncTime = 123456L)
            }
            assertTrue(result.pendingSync)
            assertEquals(123456L, result.lastSyncTime)
            val persisted = store.current()
            assertTrue(persisted.pendingSync)
            assertEquals(123456L, persisted.lastSyncTime)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `syncedFiles map survives persistence`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = createStore("persist.preferences_pb", scope)
        store.update {
            it.copy(
                syncedFiles = mapOf(
                    "settings.json" to FileSyncRecord(
                        sha256 = "abc123",
                        remoteTag = "wifi-etag",
                        updatedAtMs = 1000L,
                        deviceId = "device-1",
                    )
                )
            )
        }
        scope.cancel()

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reloaded = createStore("persist.preferences_pb", scope2)
            val record = reloaded.current().syncedFiles["settings.json"]
            assertNotEquals(null, record)
            assertEquals("abc123", record?.sha256)
            assertEquals("wifi-etag", record?.remoteTag)
        } finally {
            scope2.cancel()
        }
    }
}
