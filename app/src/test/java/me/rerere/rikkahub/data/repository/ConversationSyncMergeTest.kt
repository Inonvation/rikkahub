package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.ConversationNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 会话下载合并纯函数单测（C2）：
 * 双端各改不丢消息、同消息 LWW 大者胜、墓碑删除、远端/本地独有节点保留与追加。
 */
class ConversationSyncMergeTest {
    private fun msg(text: String, id: Uuid = Uuid.random(), version: Long = 0L): UIMessage =
        UIMessage(
            id = id,
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(text)),
            syncUpdatedAt = version,
        )

    private fun localNode(id: Uuid, messages: List<UIMessage>): MessageNode =
        MessageNode(id = id, messages = messages)

    private fun remoteNode(id: String, messages: List<UIMessage>): ConversationNode =
        ConversationNode(id = id, selectIndex = 0, messages = messages)

    private fun merge(
        local: List<MessageNode>,
        remote: List<ConversationNode>,
        deleted: Set<String> = emptySet(),
    ) = ConversationRepository.mergeConversationMessages(local, remote, deleted)

    @Test
    fun `merge keeps messages from both sides`() {
        val m1 = msg("local msg", version = 1L)
        val m2 = msg("remote msg", version = 2L)
        val nodeId = Uuid.random()
        val merged = merge(
            listOf(localNode(nodeId, listOf(m1))),
            listOf(remoteNode(nodeId.toString(), listOf(m2))),
        )
        assertEquals(setOf(m1.id, m2.id), merged[0].messages.map { it.id }.toSet())
        assertEquals(2, merged[0].messages.size)
    }

    @Test
    fun `same message LWW picks newer version`() {
        val id = Uuid.random()
        val local = msg("old", id = id, version = 1L)
        val remote = msg("new", id = id, version = 5L)
        val nodeId = Uuid.random()
        val merged = merge(
            listOf(localNode(nodeId, listOf(local))),
            listOf(remoteNode(nodeId.toString(), listOf(remote))),
        )
        assertEquals(1, merged[0].messages.size)
        assertEquals("new", merged[0].messages[0].toText())
        assertEquals(5L, merged[0].messages[0].syncUpdatedAt)
    }

    @Test
    fun `tombstone removes message from both sides`() {
        val m = msg("gone", version = 1L)
        val nodeId = Uuid.random()
        val merged = merge(
            listOf(localNode(nodeId, listOf(m))),
            emptyList(),
            deleted = setOf(m.id.toString()),
        )
        assertTrue(merged.isEmpty()) // 空节点被剔除
    }

    @Test
    fun `remote-only node is appended`() {
        val localNodeId = Uuid.random()
        val remoteNodeId = Uuid.random()
        val remoteMsg = msg("from remote", version = 3L)
        val merged = merge(
            listOf(localNode(localNodeId, listOf(msg("local", version = 1L)))),
            listOf(remoteNode(remoteNodeId.toString(), listOf(remoteMsg))),
        )
        assertEquals(2, merged.size)
        assertEquals(setOf(localNodeId, remoteNodeId), merged.map { it.id }.toSet())
        assertTrue(merged.any { n -> n.messages.any { it.toText() == "from remote" } })
    }

    @Test
    fun `local-only node is preserved`() {
        val nodeId = Uuid.random()
        val localMsg = msg("only local", version = 1L)
        val merged = merge(
            listOf(localNode(nodeId, listOf(localMsg))),
            emptyList(), // 云端没有该会话的节点
        )
        assertEquals(1, merged.size)
        assertEquals(localMsg.id, merged[0].messages[0].id)
    }

    @Test
    fun `mergeMessageLists tie-breaks by content signature`() {
        val id = Uuid.random()
        val a = msg("a", id = id, version = 7L)
        val b = msg("b", id = id, version = 7L) // 同版本，内容更大者胜
        val merged = ConversationRepository.mergeMessageLists(listOf(a), listOf(b))
        assertEquals("b", merged[0].toText())
    }
}
