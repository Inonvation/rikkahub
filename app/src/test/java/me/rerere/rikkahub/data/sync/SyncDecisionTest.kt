package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDecisionTest {

    private fun rec(
        sha: String = "abc",
        tag: String = "etag-1",
    ) = FileSyncRecord(sha256 = sha, remoteTag = tag, updatedAtMs = 1000L, deviceId = "d1")

    private fun input(
        localExists: Boolean = true,
        localSha: String? = "abc",
        localUpdatedAtMs: Long = 1000L,
        record: FileSyncRecord? = rec(),
        remoteExists: Boolean = true,
        remoteTag: String? = "etag-1",
        remoteLastModifiedMs: Long? = 1000L,
    ) = SyncDecision.Input(
        localExists = localExists,
        localSha = localSha,
        localUpdatedAtMs = localUpdatedAtMs,
        record = record,
        remoteExists = remoteExists,
        remoteTag = remoteTag,
        remoteLastModifiedMs = remoteLastModifiedMs,
    )

    // 远端不存在 → push
    @Test
    fun `remote missing pushes`() {
        assertEquals(
            SyncDecision.Action.Push,
            SyncDecision.decide(input(remoteExists = false, remoteTag = null))
        )
    }

    // 本地 sha == 记录 sha，远端 tag 不同 → pull（本地没变、远端被改）
    @Test
    fun `local unchanged remote changed pulls`() {
        assertEquals(
            SyncDecision.Action.Pull,
            SyncDecision.decide(input(localSha = "abc", remoteTag = "etag-NEW"))
        )
    }

    // 本地 sha != 记录 sha，远端 tag == 记录 tag → push（只有本地改）
    @Test
    fun `local changed remote unchanged pushes`() {
        assertEquals(
            SyncDecision.Action.Push,
            SyncDecision.decide(input(localSha = "xyz", remoteTag = "etag-1"))
        )
    }

    // 本地 sha != 记录 sha，远端 tag != 记录 tag → conflict
    @Test
    fun `both changed conflicts`() {
        assertEquals(
            SyncDecision.Action.Conflict,
            SyncDecision.decide(input(localSha = "xyz", remoteTag = "etag-NEW"))
        )
    }

    // 本地 == 记录 sha，远端 tag == 记录 tag → skip
    @Test
    fun `nothing changed skips`() {
        assertEquals(
            SyncDecision.Action.Skip,
            SyncDecision.decide(input(localSha = "abc", remoteTag = "etag-1"))
        )
    }

    // 删除传播：本地不存在 && 有记录 && 远端存在 → deleteRemote
    @Test
    fun `local deleted propagates deleteRemote`() {
        assertEquals(
            SyncDecision.Action.DeleteRemote,
            SyncDecision.decide(input(localExists = false, localSha = null))
        )
    }

    // 首见远端文件：本地不存在 && 无记录 && 远端存在 → pull
    @Test
    fun `new remote file pulls`() {
        assertEquals(
            SyncDecision.Action.Pull,
            SyncDecision.decide(input(localExists = false, localSha = null, record = null))
        )
    }

    // 两边都没有 → skip
    @Test
    fun `both missing skips`() {
        assertEquals(
            SyncDecision.Action.Skip,
            SyncDecision.decide(input(localExists = false, localSha = null, remoteExists = false, remoteTag = null))
        )
    }

    // 首次同步双方都有、无记录：远端较新 → pull
    @Test
    fun `first sync remote newer pulls`() {
        assertEquals(
            SyncDecision.Action.Pull,
            SyncDecision.decide(
                input(
                    localSha = "abc",
                    localUpdatedAtMs = 1000L,
                    record = null,
                    remoteTag = "etag-1",
                    remoteLastModifiedMs = 2000L,
                )
            )
        )
    }

    // 首次同步双方都有、无记录：本地较新 → push
    @Test
    fun `first sync local newer pushes`() {
        assertEquals(
            SyncDecision.Action.Push,
            SyncDecision.decide(
                input(
                    localSha = "abc",
                    localUpdatedAtMs = 3000L,
                    record = null,
                    remoteTag = "etag-1",
                    remoteLastModifiedMs = 2000L,
                )
            )
        )
    }

    // 记录存在但本地删除、远端也存在 → 删除传播 DeleteRemote（无论远端 tag 是否变）
    @Test
    fun `local deleted with record deletes remote`() {
        assertEquals(
            SyncDecision.Action.DeleteRemote,
            SyncDecision.decide(
                input(
                    localExists = false,
                    localSha = null,
                    record = rec(tag = "etag-OLD"),
                    remoteTag = "etag-NEW",
                )
            )
        )
    }
}
