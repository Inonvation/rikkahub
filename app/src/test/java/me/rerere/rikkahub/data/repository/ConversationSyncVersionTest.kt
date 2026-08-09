package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 会话增量同步「版本地基」纯函数单测。
 * 覆盖：内容签名（排除 syncUpdatedAt）、消息版本分配（保留/推进）、待推进计数、JSON 解析。
 */
class ConversationSyncVersionTest {
    private val nodeId: Uuid = Uuid.random()

    private fun msg(text: String, id: Uuid = Uuid.random(), version: Long = 0L): UIMessage =
        UIMessage(
            id = id,
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(text)),
            syncUpdatedAt = version,
        )

    private fun node(messages: List<UIMessage>): MessageNode =
        MessageNode(id = nodeId, messages = messages)

    private fun oldState(vararg pairs: Pair<String, Pair<Long, String>>): Map<String, Map<String, Pair<Long, String>>> =
        mapOf(nodeId.toString() to pairs.toMap())

    // ---- contentKeyOf ----

    @Test
    fun `contentKey ignores syncUpdatedAt but reflects content`() {
        val a = msg("hello")
        val b = a.copy(syncUpdatedAt = 123456L)
        assertEquals(ConversationRepository.contentKeyOf(a), ConversationRepository.contentKeyOf(b))
        assertNotEquals(
            ConversationRepository.contentKeyOf(msg("hello")),
            ConversationRepository.contentKeyOf(msg("world")),
        )
    }

    // ---- assignSyncVersions ----

    @Test
    fun `new messages get increasing versions from base`() {
        val nodes = listOf(node(messages = listOf(msg("a"), msg("b"))))
        val keys = ConversationRepository.messageContentKeys(nodes)
        val result = ConversationRepository.assignSyncVersions(nodes, emptyMap(), keys, base = 100L)
        assertEquals(listOf(101L, 102L), result[0].map { it.syncUpdatedAt })
    }

    @Test
    fun `unchanged message keeps its version`() {
        val m = msg("a", version = 50L)
        val nodes = listOf(node(messages = listOf(m)))
        val keys = ConversationRepository.messageContentKeys(nodes)
        val state = oldState(m.id.toString() to (50L to ConversationRepository.contentKeyOf(m)))
        val result = ConversationRepository.assignSyncVersions(nodes, state, keys, base = 100L)
        assertEquals(50L, result[0][0].syncUpdatedAt)
    }

    @Test
    fun `changed content advances version`() {
        val old = msg("a", version = 50L)
        val changed = old.copy(parts = listOf(UIMessagePart.Text("changed")))
        val nodes = listOf(node(messages = listOf(changed)))
        val keys = ConversationRepository.messageContentKeys(nodes)
        val state = oldState(old.id.toString() to (50L to ConversationRepository.contentKeyOf(old)))
        val result = ConversationRepository.assignSyncVersions(nodes, state, keys, base = 100L)
        assertEquals(101L, result[0][0].syncUpdatedAt)
    }

    @Test
    fun `legacy zero version is treated as pending`() {
        val m = msg("a", version = 0L) // 存量未参与同步
        val nodes = listOf(node(messages = listOf(m)))
        val keys = ConversationRepository.messageContentKeys(nodes)
        val state = oldState(m.id.toString() to (0L to ConversationRepository.contentKeyOf(m)))
        val result = ConversationRepository.assignSyncVersions(nodes, state, keys, base = 100L)
        assertEquals(101L, result[0][0].syncUpdatedAt)
    }

    @Test
    fun `mixed unchanged and new`() {
        val keep = msg("keep", version = 50L)
        val fresh = msg("new")
        val nodes = listOf(node(messages = listOf(keep, fresh)))
        val keys = ConversationRepository.messageContentKeys(nodes)
        val state = oldState(keep.id.toString() to (50L to ConversationRepository.contentKeyOf(keep)))
        val result = ConversationRepository.assignSyncVersions(nodes, state, keys, base = 100L)
        assertEquals(listOf(50L, 101L), result[0].map { it.syncUpdatedAt })
    }

    @Test
    fun `whole conversation rewrite keeps untouched versions`() {
        // 模拟「整会话重插」：两个节点，一个未变一个追加消息
        val n1keep = msg("n1 old", version = 10L)
        val n2old = msg("n2 old", version = 20L)
        val n2new = msg("n2 new")
        val nodes = listOf(
            MessageNode(id = Uuid.random(), messages = listOf(n1keep)),
            MessageNode(id = Uuid.random(), messages = listOf(n2old, n2new)),
        )
        val keys = ConversationRepository.messageContentKeys(nodes)
        val state = mapOf(
            nodes[0].id.toString() to mapOf(n1keep.id.toString() to (10L to ConversationRepository.contentKeyOf(n1keep))),
            nodes[1].id.toString() to mapOf(n2old.id.toString() to (20L to ConversationRepository.contentKeyOf(n2old))),
        )
        val result = ConversationRepository.assignSyncVersions(nodes, state, keys, base = 100L)
        assertEquals(10L, result[0][0].syncUpdatedAt) // 未变保留
        assertEquals(20L, result[1][0].syncUpdatedAt) // 未变保留
        assertEquals(101L, result[1][1].syncUpdatedAt) // 新消息推进
    }

    // ---- countPendingSyncVersions ----

    @Test
    fun `countPending counts only changed and new`() {
        val keep = msg("keep", version = 50L)
        val fresh = msg("new")
        val old = msg("changed")
        val changed = old.copy(parts = listOf(UIMessagePart.Text("changed2")))
        val nodes = listOf(node(messages = listOf(keep, fresh, changed)))
        val keys = ConversationRepository.messageContentKeys(nodes)
        val state = oldState(
            keep.id.toString() to (50L to ConversationRepository.contentKeyOf(keep)),
            old.id.toString() to (50L to ConversationRepository.contentKeyOf(old)),
        )
        // keep 保留(0) + fresh 新(1) + changed 变(1) = 2
        assertEquals(2, ConversationRepository.countPendingSyncVersions(nodes, state, keys))
    }

    // ---- parseMessageSyncState ----

    @Test
    fun `parse corrupt json returns empty`() {
        assertTrue(ConversationRepository.parseMessageSyncState("{not json").isEmpty())
    }

    @Test
    fun `parse json roundtrip with version`() {
        val m = msg("a", version = 77L)
        val json = JsonInstant.encodeToString(listOf(m))
        val parsed = ConversationRepository.parseMessageSyncState(json)
        assertEquals(77L, parsed[m.id.toString()]?.first)
    }
}
