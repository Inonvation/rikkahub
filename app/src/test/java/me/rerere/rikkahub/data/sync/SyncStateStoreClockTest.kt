package me.rerere.rikkahub.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 会话增量同步「单调时钟」单测。
 * 保证：本地产出版本始终递增（系统时间回拨也安全）、批量分配不重叠、观察远端后本地版本更大。
 */
class SyncStateStoreClockTest {
    private val tmpDir: File = Files.createTempDirectory("sync-clock").toFile()
    private val scope = CoroutineScope(SupervisorJob())
    private val store = SyncStateStore(
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tmpDir, "state.preferences_pb") }
        )
    )

    @After
    fun tearDown() {
        scope.cancel()
        tmpDir.deleteRecursively()
    }

    @Test
    fun `clock is monotonic across calls`() = runBlocking {
        val v1 = store.nextSyncClock()
        val v2 = store.nextSyncClock()
        val v3 = store.nextSyncClock()
        assertTrue(v2 > v1)
        assertTrue(v3 > v2)
    }

    @Test
    fun `batch allocation does not overlap next batch`() = runBlocking {
        val base = store.nextSyncClock(5) // 分配 base..base+4
        val next = store.nextSyncClock()
        assertTrue("next=$next base=$base", next >= base + 5)
    }

    @Test
    fun `observe absorbs remote timestamp`() = runBlocking {
        val remote = 999_000_000_000L
        store.observeSyncClock(remote)
        val v = store.nextSyncClock()
        assertTrue("v=$v remote=$remote", v >= remote)
    }

    @Test
    fun `observe smaller timestamp does not move clock backward`() = runBlocking {
        val v1 = store.nextSyncClock(10)
        store.observeSyncClock(1L) // 远小于当前时钟
        val v2 = store.nextSyncClock()
        assertTrue(v2 > v1)
    }
}
