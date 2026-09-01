package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.CompressedHistory
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.dropPresetMessages
import me.rerere.rikkahub.data.ai.hasRealMessages
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ContextCompositionTest {

    // ---- estimateTokensByChars ----

    @Test
    fun `empty text estimates zero`() {
        assertEquals(0, estimateTokensByChars(""))
        // 纯空白仍按普通字符计入（3/4 向上取整 = 1）
        assertEquals(1, estimateTokensByChars("   "))
    }

    @Test
    fun `ascii text uses four chars per token`() {
        assertEquals(1, estimateTokensByChars("abcd"))
        assertEquals(2, estimateTokensByChars("abcdefgh"))
        assertEquals(3, estimateTokensByChars("hello world")) // 11 chars -> ceil(11/4)
    }

    @Test
    fun `cjk text counts one token per char`() {
        assertEquals(4, estimateTokensByChars("你好世界"))
        assertEquals(7, estimateTokensByChars("上下文构成测试")) // 7 个 CJK 字符
    }

    @Test
    fun `mixed text sums cjk and ascii heuristics`() {
        // 4 个 CJK + 8 个 ASCII = 4 + 2
        assertEquals(6, estimateTokensByChars("你好世界abcdefgh"))
    }

    // ---- buildContextComposition ----

    private fun tool(name: String, description: String, schema: Boolean = true): Tool = Tool(
        name = name,
        description = description,
        parameters = {
            if (schema) InputSchema.Obj(buildJsonObject { }, required = null) else null
        },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    @Test
    fun `tool categories split by name prefix`() {
        val mcpTool = tool("mcp__server__read_file", "Read file over MCP")
        val skillTool = tool("use_skill", "Use a skill")
        val skillAdmin = tool("skill_admin_list", "List skills")
        val builtin = tool("search_web", "Search the web")
        val composition = buildContextComposition(
            systemText = "你是一个助手",
            tools = listOf(mcpTool, skillTool, skillAdmin, builtin),
            messages = emptyList(),
        )
        assertEquals(estimateTokensByChars("你是一个助手"), composition.systemTokens)
        assertEquals(0, composition.messageTokens)
        assertEquals(mcpTool.estimateSchemaTokens(), composition.mcpToolTokens)
        assertEquals(
            skillTool.estimateSchemaTokens() + skillAdmin.estimateSchemaTokens(),
            composition.skillToolTokens,
        )
        assertEquals(builtin.estimateSchemaTokens(), composition.builtinToolTokens)
    }

    @Test
    fun `synthetic system message is excluded from message tokens`() {
        val system = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text("系统内容系统内容系统内容")),
            isSynthetic = true,
        )
        val user = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("用户消息")),
        )
        val composition = buildContextComposition(
            systemText = "系统内容系统内容系统内容",
            tools = emptyList(),
            messages = listOf(system, user),
        )
        // system 内容只计入 systemTokens，合成 system 消息不重复计入 messageTokens
        assertEquals(estimateTokensByChars("系统内容系统内容系统内容"), composition.systemTokens)
        assertEquals(estimateTokensByChars("用户消息"), composition.messageTokens)
    }

    @Test
    fun `message estimates include tool parts and flat attachment estimate`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("思考过程"),
                UIMessagePart.Image("file:///tmp/a.png"),
                UIMessagePart.Tool(
                    toolCallId = "t1",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("搜索结果")),
                ),
            ),
        )
        val composition = buildContextComposition(
            systemText = "",
            tools = emptyList(),
            messages = listOf(msg),
        )
        assertEquals(
            estimateTokensByChars("思考过程") +
                ATTACHMENT_TOKEN_ESTIMATE +
                estimateTokensByChars("search_web") +
                estimateTokensByChars("{}") +
                estimateTokensByChars("搜索结果"),
            composition.messageTokens,
        )
    }

    // ---- ContextComposition ----

    @Test
    fun `total tokens is the sum of all categories`() {
        val composition = ContextComposition(
            systemTokens = 100,
            builtinToolTokens = 200,
            mcpToolTokens = 300,
            skillToolTokens = 400,
            messageTokens = 500,
        )
        assertEquals(1500, composition.totalTokens)
        assertEquals(100, composition.tokensOf(CompositionCategory.SYSTEM))
        assertEquals(500, composition.tokensOf(CompositionCategory.MESSAGES))
    }

    // ---- calibratedWith / lastRealPromptTokens ----

    @Test
    fun `calibrated scales proportions to real total`() {
        val composition = ContextComposition(100, 200, 300, 400, 500) // 估算合计 1500
        val calibrated = composition.calibratedWith(realTotal = 3000) // 比值 2
        assertEquals(200, calibrated.systemTokens)
        assertEquals(400, calibrated.builtinToolTokens)
        assertEquals(600, calibrated.mcpToolTokens)
        assertEquals(800, calibrated.skillToolTokens)
        assertEquals(1000, calibrated.messageTokens)
        assertEquals(3000, calibrated.totalTokens)
        // 比例保持不变（校准后与校准前一致：100/1500 = 200/3000 = 1/15）
        assertEquals(1.0 / 15.0, calibrated.systemTokens.toDouble() / calibrated.totalTokens, 1e-9)
    }

    @Test
    fun `calibrated returns itself without real anchor or on degenerate ratio`() {
        val composition = ContextComposition(100, 200, 300, 400, 500)
        assertEquals(composition, composition.calibratedWith(null))
        assertEquals(composition, composition.calibratedWith(0))
        // 比值超 [1/4, 4] 安全区间（20 倍）视为异常，不校准
        assertEquals(composition, composition.calibratedWith(30_000))
        // 空构成安全返回自身
        val empty = ContextComposition(0, 0, 0, 0, 0)
        assertEquals(empty, empty.calibratedWith(100))
    }

    @Test
    fun `last real prompt tokens takes the last usage carrying message`() {
        fun msg(prompt: Int?): UIMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("x")),
            usage = prompt?.let { TokenUsage(promptTokens = it) },
        )
        assertEquals(800, listOf(msg(500), msg(800)).lastRealPromptTokens())
        // 末条无 usage 时回退到上一条带 usage 的消息
        assertEquals(500, listOf(msg(500), msg(null)).lastRealPromptTokens())
        assertNull(listOf(msg(null), msg(null)).lastRealPromptTokens())
        assertNull(emptyList<UIMessage>().lastRealPromptTokens())
    }

    // ---- dropPresetMessages ----

    @Test
    fun `drop preset messages removes the id-aligned prefix only`() {
        val preset1 = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("嗨")))
        val preset2 = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("我是助手")))
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("你好")))

        // 完整前缀：剔除两条预设
        assertEquals(
            listOf(user),
            listOf(preset1, preset2, user).dropPresetMessages(listOf(preset1, preset2)),
        )
        // 第一条预设已被用户编辑（id 变化）：前缀失配，不剔除任何消息
        val editedFirst = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("改过了")))
        assertEquals(
            listOf(editedFirst, preset2),
            listOf(editedFirst, preset2).dropPresetMessages(listOf(preset1, preset2)),
        )
        // 空预设：原样返回（引用不变）
        val messages = listOf(preset1, user)
        assertEquals(messages, messages.dropPresetMessages(emptyList()))
        // 预设比消息多：只剔除实际对齐的部分
        assertEquals(
            listOf(user),
            listOf(preset1, user).dropPresetMessages(listOf(preset1, preset2)),
        )
        // 未以预设开头（旧会话）：不剔除
        val notPresetStart = listOf(user, preset1)
        assertEquals(notPresetStart, notPresetStart.dropPresetMessages(listOf(preset1, preset2)))
    }

    // ---- hasRealMessages ----

    @Test
    fun `preset-only or empty conversation has no real messages`() {
        val preset = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("嗨")))
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("你好")))
        fun conversationWith(vararg msgs: UIMessage) = Conversation(
            assistantId = Uuid.random(),
            messageNodes = msgs.map { MessageNode.of(it) },
        )
        // 仅预设开场展示：未开始，无可占用
        assertFalse(conversationWith(preset).hasRealMessages(listOf(preset)))
        assertFalse(conversationWith().hasRealMessages(listOf(preset)))
        // 已有真实消息（含预设后追加）：已开始
        assertTrue(conversationWith(preset, user).hasRealMessages(listOf(preset)))
        assertTrue(conversationWith(user).hasRealMessages(listOf(preset)))
    }

    // ---- estimateFallbackComposition ----

    @Test
    fun `fallback uses conversation-bound assistant preset not global current assistant`() {
        // 全局当前助手与会话绑定助手不同（用户在主界面切换助手后打开旧会话）：
        // 预设剔除必须按会话绑定的助手走，否则失配导致预设消息重新计入占用
        val presetA = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("我是A的开场欢迎")))
        val assistantA = Assistant(id = Uuid.random(), name = "A", presetMessages = listOf(presetA))
        val assistantB = Assistant(
            id = Uuid.random(),
            name = "B",
            presetMessages = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("B的预设")))
            ),
        )
        val settings = Settings.dummy().copy(
            assistantId = assistantB.id, // 全局当前助手 = B
            assistants = listOf(assistantA, assistantB),
        )
        val conversation = Conversation(
            assistantId = assistantA.id,
            messageNodes = listOf(MessageNode.of(presetA)),
        )
        // 会话绑定的 A 的预设被剔除 → 消息占 0；若误用全局 B 的预设会失配 → > 0
        assertEquals(0, estimateFallbackComposition(conversation, settings).messageTokens)
        assertFalse(conversation.hasRealMessages(listOf(presetA)))
        assertTrue(conversation.hasRealMessages(assistantB.presetMessages))
    }

    // ---- ContextCompositionStore ----

    @Test
    fun `store keeps snapshot per conversation`() {
        ContextCompositionStore.remove("ctx-a")
        ContextCompositionStore.remove("ctx-b")
        assertNull(ContextCompositionStore.get("ctx-a"))

        val snapshot = ContextComposition(1, 2, 3, 4, 5)
        ContextCompositionStore.update("ctx-a", snapshot)
        assertEquals(snapshot, ContextCompositionStore.get("ctx-a"))
        assertNull(ContextCompositionStore.get("ctx-b"))

        ContextCompositionStore.remove("ctx-a")
        assertNull(ContextCompositionStore.get("ctx-a"))
    }

    @Test
    fun `estimate schema tokens ignores nullable schema`() {
        val noSchema = tool("skill_admin_list", "List skills", schema = false)
        assertTrue(noSchema.estimateSchemaTokens() > 0)
    }

    // ---- hasStaleCalibrationAnchor ----

    private fun usageMessage(
        role: MessageRole,
        text: String,
        promptTokens: Int = 0,
    ): UIMessage = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        usage = TokenUsage(
            promptTokens = promptTokens,
            completionTokens = 0,
            cachedTokens = 0,
            cacheWriteTokens = 0,
        ),
    )

    @Test
    fun `anchor before compression point is stale`() {
        val oldUser = usageMessage(MessageRole.USER, "旧的", promptTokens = 100)
        val oldReply = usageMessage(MessageRole.ASSISTANT, "旧的回复")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(oldUser, oldReply).map { MessageNode.of(it) },
            compressedHistory = CompressedHistory(
                messages = listOf(
                    usageMessage(MessageRole.USER, "[Summary]"),
                    oldUser,
                    oldReply,
                ),
                lastOriginalMessageId = oldReply.id,
            ),
        )

        // 最后一条 usage（压缩前的旧请求）仍在压缩点（含）之前 → 校准锚点过时
        assertTrue(conversation.hasStaleCalibrationAnchor())
    }

    @Test
    fun `anchor on compression point message is stale`() {
        val oldUser = usageMessage(MessageRole.USER, "旧的")
        // 压缩点的最后一条原消息正是携带旧请求 usage 的助手回复
        val oldReply = usageMessage(MessageRole.ASSISTANT, "旧的回复", promptTokens = 100)
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(oldUser, oldReply).map { MessageNode.of(it) },
            compressedHistory = CompressedHistory(
                messages = listOf(
                    usageMessage(MessageRole.USER, "[Summary]"),
                    oldUser,
                    oldReply,
                ),
                lastOriginalMessageId = oldReply.id,
            ),
        )

        assertTrue(conversation.hasStaleCalibrationAnchor())
    }

    @Test
    fun `post compression anchor is fresh`() {
        val oldUser = usageMessage(MessageRole.USER, "旧的", promptTokens = 200)
        val oldReply = usageMessage(MessageRole.ASSISTANT, "旧的回复")
        // 压缩后又发生了一次生成：新消息携带压缩后请求的实测 usage
        val newUser = usageMessage(MessageRole.USER, "新的")
        val newReply = usageMessage(MessageRole.ASSISTANT, "新的回复", promptTokens = 30)
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(oldUser, oldReply, newUser, newReply).map { MessageNode.of(it) },
            compressedHistory = CompressedHistory(
                messages = listOf(
                    usageMessage(MessageRole.USER, "[Summary]"),
                    oldUser,
                    oldReply,
                ),
                lastOriginalMessageId = oldReply.id,
            ),
        )

        assertFalse(conversation.hasStaleCalibrationAnchor())
    }

    @Test
    fun `no compression or no anchor is always calibratable`() {
        val plain = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(usageMessage(MessageRole.USER, "普通会话", promptTokens = 50))),
        )
        // 无压缩快照 → 不视为过时
        assertFalse(plain.hasStaleCalibrationAnchor())

        // 有压缩但没有任何带 usage 的消息 → 无可定位锚点，保持可校准（fallback 路径自行兜底）
        val noAnchor = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(usageMessage(MessageRole.USER, "无 usage"))),
            compressedHistory = CompressedHistory(
                messages = listOf(usageMessage(MessageRole.USER, "[Summary]")),
                lastOriginalMessageId = Uuid.random(),
            ),
        )
        assertFalse(noAnchor.hasStaleCalibrationAnchor())
    }
}