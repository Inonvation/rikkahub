package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("Source conversation(1)", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `fork title increments existing numeric suffix instead of stacking`() {
        assertEquals("Chat(2)", forkConversationTitle("Chat(1)", emptySet()))
        assertEquals("Chat(4)", forkConversationTitle("Chat(1)", setOf("Chat(2)", "Chat(3)")))
        assertEquals("Chat(1)", forkConversationTitle("Chat", emptySet()))
        assertEquals("Chat(2)", forkConversationTitle("Chat", setOf("Chat(1)")))
        assertEquals("Chat(abc)(1)", forkConversationTitle("Chat(abc)", emptySet()))
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val conversationId = Uuid.random()
        val params = backgroundTextGenerationParams(model, conversationId)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
        assertEquals(conversationId.toString(), params.sessionId)
    }

    @Test
    fun `display messages skip compressed summary and append new assistant reply`() {
        val oldUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("old")),
        )
        val summary = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            isSynthetic = true,
            parts = listOf(UIMessagePart.Text("[Summary]")),
        )
        val newUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("new")),
        )
        val assistantReply = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
        )

        val result = displayMessagesForChunk(
            displayMessages = listOf(oldUser, newUser),
            chunkMessages = listOf(summary, oldUser, newUser, assistantReply),
        )

        assertEquals(listOf(oldUser, newUser, assistantReply), result)
    }

    @Test
    fun `display messages append real user guidance injected at step boundary`() {
        val assistantReply = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("working...")),
        )
        // steering 在轮边界注入的真实用户引导（非合成）必须进入显示列表
        val guidance = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("聚焦在性能问题上继续")),
        )

        val result = displayMessagesForChunk(
            displayMessages = listOf(assistantReply),
            chunkMessages = listOf(assistantReply, guidance),
        )

        assertEquals(listOf(assistantReply, guidance), result)
    }

    @Test
    fun `display messages update existing message by id`() {
        val id = Uuid.random()
        val before = UIMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("partial")),
        )
        val after = before.copy(parts = listOf(UIMessagePart.Text("partial done")))

        val result = displayMessagesForChunk(
            displayMessages = listOf(before),
            chunkMessages = listOf(after),
        )

        assertEquals(listOf(after), result)
    }

    @Test
    fun `display messages duplicate id updates first occurrence and keeps order`() {
        val id = Uuid.random()
        val first = UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("first")))
        val second = UIMessage(id = id, role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("second")))
        val updated = first.copy(parts = listOf(UIMessagePart.Text("first updated")))

        val result = displayMessagesForChunk(
            displayMessages = listOf(first, second),
            chunkMessages = listOf(updated),
        )

        // 与 indexOfFirst 语义一致：只替换首个匹配，且不移动位置、不追加
        assertEquals(listOf(updated, second), result)
    }

    @Test
    fun `context checkpoint is inserted after the anchor node without touching other nodes`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

        val result = insertContextCheckpoint(source, afterNodeId = nodes[1].id, summary = "summary")!!

        assertEquals(nodes.subList(0, 2), result.messageNodes.subList(0, 2))
        assertEquals(nodes.subList(2, 4), result.messageNodes.subList(3, 5))
        val checkpoint = result.messageNodes[2].currentMessage
        assertTrue(checkpoint.isContextCheckpoint)
        assertEquals("summary", checkpoint.toText())
        // 只有检查点之后的消息会继续发送给模型
        assertEquals(result.currentMessages.subList(2, 5), result.currentMessages.limitContext(0))
    }

    @Test
    fun `context checkpoint is not inserted when the anchor node is gone`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("message").toMessageNode()),
        )

        assertNull(insertContextCheckpoint(source, afterNodeId = Uuid.random(), summary = "summary"))
    }

    @Test
    fun `parse compact command extracts optional instruction`() {
        assertEquals("", parseCompactCommand("/compact"))
        assertEquals("", parseCompactCommand("  /compact  "))
        assertEquals("Focus on API changes", parseCompactCommand("/compact Focus on API changes"))
        assertEquals("多行说明\n第二行", parseCompactCommand("/compact 多行说明\n第二行"))

        // 非 /compact 一律拒绝：前缀撞车、其他命令、正文提及、空输入
        assertNull(parseCompactCommand("/compactfoo"))
        assertNull(parseCompactCommand("/clear"))
        assertNull(parseCompactCommand("先看看 /compact 再说"))
        assertNull(parseCompactCommand(null))
    }

    @Test
    fun `compress cut index keeps tail within token budget`() {
        // 每条消息 40 个 ASCII 字符 → 估算 10 token
        fun node() = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("m".repeat(40)))).toMessageNode()

        val nodes = List(10) { node() }

        // 预算 25：尾部 2 条（10+10）落在窗口内，第 3 条会超预算 → 切点在 8（摘要前 8 条）
        assertEquals(8, compressCutIndex(nodes, startIndex = 0, keepRecentTokens = 25))

        // 预算 0（非法/极小）：仍保留最后一条，保证当前轮上下文连续
        assertEquals(9, compressCutIndex(nodes, startIndex = 0, keepRecentTokens = 0))

        // 全部消息都落在保留窗口内 → null（无需压缩）
        assertNull(compressCutIndex(nodes, startIndex = 0, keepRecentTokens = 10_000))

        // 起点即末尾（无可摘要内容）→ null
        assertNull(compressCutIndex(nodes, startIndex = nodes.lastIndex, keepRecentTokens = 100))
    }

    @Test
    fun `compress cut index keeps oversized last message and skips the covered prefix`() {
        // 单条 2000 字符 → 估算 500 token，远超预算 100：依旧保留该条，其余进摘要
        val oversized = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("x".repeat(2000)))).toMessageNode()
        val small = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("y".repeat(40)))).toMessageNode()
        val nodes = listOf(small, small, oversized)

        assertEquals(2, compressCutIndex(nodes, startIndex = 0, keepRecentTokens = 100))

        // 会话只有一条消息：窗口覆盖全部 → null
        assertNull(compressCutIndex(listOf(oversized), startIndex = 0, keepRecentTokens = 100))

        // 起点之前的消息（预设开场展示 / 已被上一检查点覆盖的历史）不参与切分
        val withCoveredPrefix = listOf(oversized, oversized) + nodes
        assertEquals(4, compressCutIndex(withCoveredPrefix, startIndex = 2, keepRecentTokens = 100))
    }

    @Test
    fun `preset prefix nodes are counted by message id alignment`() {
        val preset = UIMessage.user("opening")
        val real = UIMessage.user("real")
        val nodes = listOf(preset, real).map { it.toMessageNode() }

        assertEquals(1, countPresetPrefixNodes(nodes, listOf(preset)))
        assertEquals(0, countPresetPrefixNodes(nodes, emptyList()))
        // 内容相同但 id 已变（用户编辑过）→ 不再算预设
        assertEquals(0, countPresetPrefixNodes(nodes, listOf(UIMessage.user("opening"))))
    }

    @Test
    fun `chunk target tokens distribute global budget by char share`() {
        // 300 字符总量、9000 预算：100 字符的块分到 1/3
        assertEquals(3000, chunkTargetTokens(chunkChars = 100, totalChars = 300, summaryBudget = 9000))
        assertEquals(6000, chunkTargetTokens(chunkChars = 200, totalChars = 300, summaryBudget = 9000))

        // 占比过小的块保底 256，不为节省几十 token 牺牲摘要结构
        assertEquals(256, chunkTargetTokens(chunkChars = 1, totalChars = 100_000, summaryBudget = 9000))

        // 退化输入：预算整体兜底
        assertEquals(9000, chunkTargetTokens(chunkChars = 0, totalChars = 0, summaryBudget = 9000))
    }

    @Test
    fun `default keep recent tokens derives from target`() {
        assertEquals(16_000, defaultKeepRecentTokens(64_000))
        // 目标过小时保底 1024，避免保留窗口小到只剩最后一条
        assertEquals(1024, defaultKeepRecentTokens(2000))
    }

    @Test
    fun `split by char budget preserves order and isolates oversized item`() {
        val items = listOf("a".repeat(30), "b".repeat(30), "c".repeat(30))
        // 30+30=60 不超过预算 60：[a,b] + [c] 两块
        val chunks = splitByCharBudget(items, budget = 60)
        assertEquals(2, chunks.size)
        assertEquals(listOf(items[0], items[1]), chunks[0])
        assertEquals(listOf(items[2]), chunks[1])

        // 预算 50 装不下两条 30：逐条成块
        assertEquals(3, splitByCharBudget(items, budget = 50).size)

        // 单条超预算：独占一块，不与其余合并
        val oversized = listOf("x".repeat(100), "y".repeat(5))
        val oversizedChunks = splitByCharBudget(oversized, budget = 10)
        assertEquals(listOf(oversized[0]), oversizedChunks[0])
        assertEquals(listOf(oversized[1]), oversizedChunks[1])

        assertTrue(splitByCharBudget(emptyList(), budget = 100).isEmpty())
    }
}
