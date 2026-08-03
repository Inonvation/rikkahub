package me.rerere.rikkahub.data.ai.discussion

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.subAgentRunLoop
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.DiscussionConfig
import me.rerere.rikkahub.data.model.DiscussionMember
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "GroupDiscussionOrchestrator"

/**
 * 群组讨论调度器。
 *
 * 状态机：IDLE → SCHEDULING → GENERATING(谁) → COMPLETED / PAUSED / ERROR
 * 三种模式：
 * - ROUND_ROBIN / ROUND_ROBIN_THEN_SUMMARY：轮流发言，聊满 rounds 轮终止
 * - SELECTOR：selector 模型观察 transcript 动态决定下一位
 *
 * 每名成员的"单轮生成"复用 [subAgentRunLoop]（无审批/自带上下文保护/取消上抛）。
 * 成员发言以带 speakerId/speakerName 的 UIMessage 写入 messageNodes。
 *
 * 由 ChatService 构造并注入三个会话访问器（操作内存 session 态，避免 DI 循环依赖）：
 * - read：读当前会话内存态（含流式中的实时内容）
 * - update：更新会话内存态
 * - persist：把内存态落库（saveConversation 语义）
 */
class GroupDiscussionOrchestrator(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val toolAssembler: DiscussionToolAssembler,
    private val json: Json,
    private val read: (Uuid) -> Conversation?,
    private val update: (Uuid, (Conversation) -> Conversation) -> Unit,
    private val persist: suspend (Uuid, Conversation) -> Unit,
    private val readGroup: suspend (Uuid) -> Group?,
) {
    private val stateHolder = DiscussionStateHolder()
    fun state(conversationId: Uuid): StateFlow<DiscussionState> = stateHolder.state(conversationId)

    /** 用户指定下一位发言者（一次性 hint，生成中则本轮结束后消费），按会话隔离 */
    private val nextSpeakerHints = ConcurrentHashMap<Uuid, Uuid?>()

    fun setNextSpeakerHint(conversationId: Uuid, memberId: Uuid?) {
        nextSpeakerHints[conversationId] = memberId
    }

    fun resetState(conversationId: Uuid) = stateHolder.reset(conversationId)

    /** 会话被删除/群组被删除时清理内存态（state + 未消费的 hint），避免残留脏数据 */
    fun clearConversation(conversationId: Uuid) {
        stateHolder.reset(conversationId)
        nextSpeakerHints.remove(conversationId)
    }

    suspend fun runDiscussion(conversationId: Uuid) {
        val conversation = read(conversationId) ?: return
        // 配置在 Group 上（groupId → Group），不再读 conversation.discussion
        val group = conversation.groupId?.let { readGroup(it) } ?: return
        val config = group.config ?: return
        val groupName = group.name

        if (config.enabledMembers.size < 2) {
            stateHolder.update(conversationId) {
                it.copy(phase = DiscussionPhase.ERROR, lastError = "群组成员少于 2 人，无法开始讨论")
            }
            return
        }

        // 从 transcript 初始化已发言轮数：暂停→继续后轮数连续，不重复计数
        var totalTurnCount = conversation.messageNodes.count {
            it.role == MessageRole.ASSISTANT && it.currentMessage.speakerId != null
        }
        stateHolder.update(conversationId) {
            it.copy(
                phase = DiscussionPhase.SCHEDULING,
                currentSpeakerId = null,
                currentSpeakerName = null,
                totalTurns = config.enabledMembers.size * config.rounds,
                lastError = null,
            )
        }

        try {
            while (true) {
                val current = read(conversationId) ?: break
                val hint = nextSpeakerHints[conversationId]
                nextSpeakerHints[conversationId] = null
                val speaker = pickNextSpeaker(current, config, hint) ?: break
                totalTurnCount++

                stateHolder.update(conversationId) {
                    it.copy(
                        phase = DiscussionPhase.GENERATING,
                        currentSpeakerId = speaker.assistantId,
                        currentSpeakerName = speaker.name,
                        turnIndex = totalTurnCount,
                        roundIndex = totalTurnCount / config.enabledMembers.size + 1,
                    )
                }

                generateMemberTurn(conversationId, speaker, config, groupName)
                val after = read(conversationId) ?: break
                if (shouldTerminate(after, config, totalTurnCount)) break
            }

            if (config.mode == DiscussionMode.ROUND_ROBIN_THEN_SUMMARY) {
                runSummaryTurn(conversationId, config)
            }

            stateHolder.update(conversationId) {
                it.copy(phase = DiscussionPhase.COMPLETED, currentSpeakerId = null, currentSpeakerName = null)
            }
        } catch (e: CancellationException) {
            // 用户插话/取消：保留已生成发言。
            // 若最后一条是"空发言占位"（刚占位还没产出文字），删掉它——空发言不占轮，
            // 否则重启时 countTurns 会把它算作一轮导致跳过该成员。
            val last = read(conversationId)?.messageNodes?.lastOrNull()
            val lastMsg = last?.currentMessage
            if (lastMsg?.role == MessageRole.ASSISTANT && lastMsg.speakerId != null && lastMsg.toText().isBlank()) {
                update(conversationId) { it.copy(messageNodes = it.messageNodes.dropLast(1)) }
            }
            stateHolder.update(conversationId) {
                it.copy(phase = DiscussionPhase.PAUSED, currentSpeakerId = null, currentSpeakerName = null)
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "runDiscussion failed", e)
            stateHolder.update(conversationId) {
                it.copy(phase = DiscussionPhase.ERROR, lastError = e.message ?: "讨论出错")
            }
        }
    }

    /** 决定下一位发言者；返回 null 表示讨论结束。用户指定优先（一次性）。 */
    private suspend fun pickNextSpeaker(
        conversation: Conversation,
        config: DiscussionConfig,
        hint: Uuid?,
    ): DiscussionMember? {
        val enabled = config.enabledMembers
        if (hint != null) {
            return enabled.find { it.assistantId == hint } ?: enabled.firstOrNull()
        }

        return when (config.mode) {
            DiscussionMode.ROUND_ROBIN, DiscussionMode.ROUND_ROBIN_THEN_SUMMARY -> {
                if (enabled.all { countTurns(conversation, it.assistantId) >= config.rounds }) return null
                nextRoundRobin(enabled, lastSpeakerId(conversation), conversation, config)
            }

            DiscussionMode.SELECTOR -> {
                if (turnCount(conversation) >= config.maxTurns) return null
                // 只有主持人模型明确返回 action=="end" 才结束；
                // 模型不可用/解析失败/名字无效 → 降级轮流，绝不终止（防"主持人选不了"）
                val decision = runCatching { callSelector(conversation, config) }.getOrNull()
                if (decision?.action == "end") return null
                val member = decision?.speaker?.let { resolveMemberByNameOrId(enabled, it) }
                member ?: nextRoundRobin(enabled, lastSpeakerId(conversation), conversation, config)
            }
        }
    }

    private fun countTurns(conversation: Conversation, memberId: Uuid): Int =
        conversation.messageNodes.count {
            it.role == MessageRole.ASSISTANT && it.currentMessage.speakerId == memberId
        }

    private fun lastSpeakerId(conversation: Conversation): Uuid? =
        conversation.messageNodes.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.currentMessage?.speakerId

    private fun turnCount(conversation: Conversation): Int =
        conversation.messageNodes.count { it.role == MessageRole.ASSISTANT && it.currentMessage.speakerId != null }

    private fun nextRoundRobin(
        enabled: List<DiscussionMember>,
        last: Uuid?,
        conversation: Conversation,
        config: DiscussionConfig,
    ): DiscussionMember? {
        val lastIndex = enabled.indexOfFirst { it.assistantId == last }
        val start = if (lastIndex >= 0) lastIndex + 1 else 0
        for (i in 0 until enabled.size) {
            val member = enabled[(start + i) % enabled.size]
            if (countTurns(conversation, member.assistantId) < config.rounds) return member
        }
        return null
    }

    private fun shouldTerminate(conversation: Conversation, config: DiscussionConfig, totalTurnCount: Int): Boolean {
        if (totalTurnCount >= config.maxTurns) return true
        if (config.mode != DiscussionMode.SELECTOR) {
            return config.enabledMembers.all { countTurns(conversation, it.assistantId) >= config.rounds }
        }
        return false
    }

    // ---- SELECTOR：主持人决定下一位 ----

    private data class SelectorDecision(val speaker: String?, val action: String)

    private suspend fun callSelector(conversation: Conversation, config: DiscussionConfig): SelectorDecision? {
        val settings = settingsStore.settingsFlow.first()
        // 主持人决策模型：全局默认聊天模型优先，兜底当前助手模型，最后兜底任一可用模型
        val model = settings.findModelById(settings.chatModelId)
            ?: settings.getCurrentChatModel()
            ?: settings.providers.asSequence().flatMap { it.models.asSequence() }.firstOrNull()
            ?: return null
        val providerSetting = model.findProvider(settings.providers) ?: return null
        val provider = providerManager.getProviderByType(providerSetting)

        val system = UIMessage.system(DiscussionPrompts.selectorSystemPrompt(config))
        val opening = conversation.messageNodes.firstOrNull { it.role == MessageRole.USER }
        val recent = conversation.messageNodes
            .filter { it.role == MessageRole.ASSISTANT && it.currentMessage.speakerId != null }
            .takeLast(DiscussionPrompts.MAX_TRANSCRIPT_MESSAGES)
        val transcript = buildString {
            opening?.let { appendLine("话题：「${it.currentMessage.toText()}」") }
            recent.forEach { node ->
                val msg = node.currentMessage
                appendLine("「${msg.speakerName ?: "?"}」: ${msg.toText().take(500)}")
            }
        }
        val messages = listOf(system, UIMessage.user(transcript))

        return runCatching {
            val chunk = provider.generateText(
                providerSetting = providerSetting,
                messages = messages,
                params = TextGenerationParams(model = model, temperature = 0.2f),
            )
            val raw = chunk.choices.getOrNull(0)?.message
                ?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("\n") { it.text }
                .orEmpty()
                .trim()
            parseSelectorJson(raw)
        }.getOrNull()
    }

    private fun parseSelectorJson(raw: String): SelectorDecision? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val obj = json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            val speaker = obj["speaker"]?.jsonPrimitive?.contentOrNull
            val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: "next"
            SelectorDecision(speaker, action)
        }.getOrNull()
    }

    private fun resolveMemberByNameOrId(enabled: List<DiscussionMember>, raw: String?): DiscussionMember? {
        if (raw.isNullOrBlank()) return null
        return enabled.firstOrNull { it.name == raw.trim() }
            ?: runCatching { Uuid.parse(raw.trim()) }.getOrNull()?.let { id ->
                enabled.firstOrNull { it.assistantId == id }
            }
    }

    // ---- 单成员单轮生成 ----

    private suspend fun generateMemberTurn(
        conversationId: Uuid,
        member: DiscussionMember,
        config: DiscussionConfig,
        groupName: String,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val conversation = read(conversationId) ?: return
        val assistant = settings.getAssistantById(member.assistantId)
        if (assistant == null) {
            stateHolder.update(conversationId) { it.copy(lastError = "成员「${member.name}」的助手已被删除，跳过") }
            return
        }
        val model = resolveMemberModel(settings, assistant) ?: run {
            stateHolder.update(conversationId) { it.copy(lastError = "成员「${member.name}」的模型不可用，跳过") }
            return
        }
        val providerSetting = model.findProvider(settings.providers) ?: run {
            stateHolder.update(conversationId) { it.copy(lastError = "成员「${member.name}」的 provider 不可用，跳过") }
            return
        }
        val provider = providerManager.getProviderByType(providerSetting)

        val messages = DiscussionPrompts.buildMemberMessages(
            member = member,
            assistantSystemPrompt = assistant.systemPrompt,
            config = config,
            conversation = conversation,
            groupName = groupName,
        )
        val tools = toolAssembler.assembleForMember(assistant, settings, conversation)
        val params = TextGenerationParams(
            model = model,
            temperature = member.temperature ?: assistant.temperature,
            topP = member.topP ?: assistant.topP,
            maxTokens = member.maxTokens ?: assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = assistant.customHeaders,
            customBody = assistant.customBodies,
        )

        // 追加占位节点（流式回写目标）
        val nodeIndex = conversation.messageNodes.size
        update(conversationId) { conv ->
            conv.copy(
                messageNodes = conv.messageNodes + MessageNode.of(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("")),
                        speakerId = member.assistantId,
                        speakerName = member.name,
                    )
                )
            )
        }

        try {
            subAgentRunLoop(
                json = json,
                providerImpl = provider,
                providerSetting = providerSetting,
                messages = messages,
                tools = tools,
                params = params,
                maxSteps = DiscussionPrompts.MEMBER_MAX_STEPS,
                onStep = { Log.d(TAG, "member ${member.name}: $it") },
                onMessagesUpdate = { msgs ->
                    val last = msgs.lastOrNull { it.role == MessageRole.ASSISTANT }
                    if (last != null) {
                        writeTurnMessage(conversationId, nodeIndex, last.copy(
                            speakerId = member.assistantId,
                            speakerName = member.name,
                        ))
                    }
                },
                onUsageUpdate = { usage ->
                    stateHolder.update(conversationId) { it.copy(usageTokens = it.usageTokens + usage.totalTokens) }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "member ${member.name} turn failed", e)
            // 保留已生成的部分文本；若完全空则写失败占位，不中断讨论
            val current = currentTurnMessage(conversationId, nodeIndex)
            if (current == null || current.toText().isBlank()) {
                writeTurnMessage(
                    conversationId,
                    nodeIndex,
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("（${member.name} 本轮发言失败）")),
                        speakerId = member.assistantId,
                        speakerName = member.name,
                    )
                )
            }
        }

        // 每轮结束落库
        read(conversationId)?.let { c ->
            persist(conversationId, c.copy(updateAt = Instant.now()))
        }
    }

    /** 收束主持人：总结全部发言为一条 ASSISTANT 消息 */
    private suspend fun runSummaryTurn(conversationId: Uuid, config: DiscussionConfig) {
        val settings = settingsStore.settingsFlow.first()
        val conversation = read(conversationId) ?: return
        val model = settings.getCurrentChatModel() ?: return
        val providerSetting = model.findProvider(settings.providers) ?: return
        val provider = providerManager.getProviderByType(providerSetting)

        val system = UIMessage.system(DiscussionPrompts.summarySystemPrompt(config))
        val transcript = buildString {
            conversation.messageNodes.forEach { node ->
                val msg = node.currentMessage
                when (node.role) {
                    MessageRole.USER -> appendLine("话题：「${msg.toText()}」")
                    MessageRole.ASSISTANT -> appendLine("「${msg.speakerName ?: "主持人"}」: ${msg.toText()}")
                    else -> {}
                }
            }
        }
        val messages = listOf(system, UIMessage.user(transcript))

        val nodeIndex = conversation.messageNodes.size
        update(conversationId) { conv ->
            conv.copy(
                messageNodes = conv.messageNodes + MessageNode.of(
                    UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("")))
                )
            )
        }

        try {
            subAgentRunLoop(
                json = json,
                providerImpl = provider,
                providerSetting = providerSetting,
                messages = messages,
                tools = emptyList(),
                params = TextGenerationParams(model = model, temperature = 0.3f),
                maxSteps = 4,
                onStep = {},
                onMessagesUpdate = { msgs ->
                    val last = msgs.lastOrNull { it.role == MessageRole.ASSISTANT }
                    if (last != null) writeTurnMessage(conversationId, nodeIndex, last)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "summary turn failed", e)
            writeTurnMessage(
                conversationId,
                nodeIndex,
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("（总结生成失败）")),
                )
            )
        }
        read(conversationId)?.let { c ->
            persist(conversationId, c.copy(updateAt = Instant.now()))
        }
    }

    // ---- 会话写入辅助 ----

    private fun writeTurnMessage(conversationId: Uuid, nodeIndex: Int, message: UIMessage) {
        update(conversationId) { conv ->
            val nodes = conv.messageNodes.toMutableList()
            if (nodeIndex >= nodes.size) {
                nodes.add(MessageNode.of(message))          // 占位建一次 id，之后流式复用同一 id
            } else {
                // 保留已有节点 id：LazyColumn 按 node.id 做 key，id 变 → 整条重组 → 思考状态丢失 → 闪烁
                val existing = nodes[nodeIndex]
                nodes[nodeIndex] = existing.copy(messages = listOf(message), selectIndex = 0)
            }
            conv.copy(messageNodes = nodes)
        }
    }

    private fun currentTurnMessage(conversationId: Uuid, nodeIndex: Int): UIMessage? =
        read(conversationId)?.messageNodes?.getOrNull(nodeIndex)?.currentMessage

    private fun resolveMemberModel(settings: Settings, assistant: Assistant): Model? {
        val modelId = assistant.chatModelId ?: settings.chatModelId ?: return null
        return settings.findModelById(modelId)
    }
}
