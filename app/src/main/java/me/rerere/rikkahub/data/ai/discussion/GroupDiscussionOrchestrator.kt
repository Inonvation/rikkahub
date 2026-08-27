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
import me.rerere.rikkahub.data.ai.canonicalToolOrder
import me.rerere.rikkahub.data.ai.subagent.subAgentRunLoop
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.DISCUSSION_MODERATOR_ID
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

    /**
     * 用户指定下一位发言者（一次性 hint，生成中则本轮结束后消费），按会话隔离。
     * 注意：ConcurrentHashMap 不允许 null key / null value——
     * resumeDiscussion 的默认参数 nextSpeakerId 为 null（表示"不指定，走自然推导"），
     * 若直接 put null value 会抛 NullPointerException（崩溃根因）。这里统一用 remove 表达"无 hint"。
     */
    private val nextSpeakerHints = ConcurrentHashMap<Uuid, Uuid>()

    fun setNextSpeakerHint(conversationId: Uuid, memberId: Uuid?) {
        if (memberId == null) {
            nextSpeakerHints.remove(conversationId)
        } else {
            nextSpeakerHints[conversationId] = memberId
        }
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

        // 从 transcript 初始化已发言轮数：暂停→继续后轮数连续，不重复计数。
        // 只统计锚点（最新用户消息）之后的发言——用户发新消息 = 新一轮，历史轮次不占用本轮配额。
        var totalTurnCount = turnCount(conversation)
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
            // 本轮内因不可用被跳过的成员（助手被删/模型缺失等）。跳过消息无 speakerId，
            // 不计入 countTurns，若不排除会无限选中同一坏成员 → 死循环。
            val skippedMembers = mutableSetOf<Uuid>()
            while (true) {
                val current = read(conversationId) ?: break
                val hint = nextSpeakerHints[conversationId]
                nextSpeakerHints.remove(conversationId)
                val speaker = pickNextSpeaker(current, config, hint, skippedMembers) ?: break
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

                val skipped = generateMemberTurn(conversationId, speaker, config, groupName)
                if (skipped) skippedMembers.add(speaker.assistantId)
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
        skippedMembers: Set<Uuid>,
    ): DiscussionMember? {
        val enabled = config.enabledMembers
        if (hint != null) {
            return enabled.find { it.assistantId == hint } ?: enabled.firstOrNull()
        }

        return when (config.mode) {
            DiscussionMode.ROUND_ROBIN, DiscussionMode.ROUND_ROBIN_THEN_SUMMARY -> {
                // 其余成员都已发言满轮（或已被跳过）→ 结束本轮
                if (enabled.all { skippedMembers.contains(it.assistantId) || countTurns(conversation, it.assistantId) >= config.rounds }) {
                    return null
                }
                nextRoundRobin(enabled, lastSpeakerId(conversation), conversation, config, skippedMembers)
            }

            DiscussionMode.SELECTOR -> {
                // maxTurns 是全局硬上限，防止锚点前移（用户发新消息开新一轮）后失效
                if (globalTurnCount(conversation) >= config.maxTurns) return null
                // 只有主持人模型明确返回 action=="end" 才结束；
                // 模型不可用/解析失败/名字无效 → 降级轮流，绝不终止（防"主持人选不了"）
                val decision = runCatching { callSelector(conversation, config) }.getOrNull()
                if (decision?.action == "end") return null
                val member = decision?.speaker?.let { resolveMemberByNameOrId(enabled, it) }
                    ?.takeIf { !skippedMembers.contains(it.assistantId) }
                member ?: nextRoundRobin(enabled, lastSpeakerId(conversation), conversation, config, skippedMembers)
            }
        }
    }

    /**
     * 本轮锚点：最新一条 USER 消息在 nodes 中的下标。
     * 用户每次发消息（开题/插话）都前移锚点 → 讨论完成后再发消息即开启新一轮，
     * 成员发言计数从锚点之后重新累计，而不是把历史轮次也计入导致"只触发总结"。
     * 无 USER 消息时为 -1（全部历史计入）。
     */
    private fun roundAnchorIndex(conversation: Conversation): Int =
        conversation.messageNodes.indexOfLast { it.role == MessageRole.USER }

    /** 统计锚点（最新用户消息）之后的成员发言数——新一轮开始时历史轮次不计入 */
    private fun countTurns(conversation: Conversation, memberId: Uuid): Int {
        val anchor = roundAnchorIndex(conversation)
        return conversation.messageNodes.withIndex().count { (index, it) ->
            index > anchor && it.role == MessageRole.ASSISTANT && it.currentMessage.speakerId == memberId
        }
    }

    /** 锚点之后的最后一位发言人（决定轮转起点） */
    private fun lastSpeakerId(conversation: Conversation): Uuid? {
        val anchor = roundAnchorIndex(conversation)
        return conversation.messageNodes.withIndex()
            .filter { it.index > anchor && it.value.role == MessageRole.ASSISTANT }
            .lastOrNull()?.value?.currentMessage?.speakerId
    }

    /** 锚点之后的总成员发言数 */
    private fun turnCount(conversation: Conversation): Int {
        val anchor = roundAnchorIndex(conversation)
        return conversation.messageNodes.withIndex()
            .count { it.index > anchor && it.value.role == MessageRole.ASSISTANT && it.value.currentMessage.speakerId != null }
    }

    private fun nextRoundRobin(
        enabled: List<DiscussionMember>,
        last: Uuid?,
        conversation: Conversation,
        config: DiscussionConfig,
        skippedMembers: Set<Uuid>,
    ): DiscussionMember? {
        val lastIndex = enabled.indexOfFirst { it.assistantId == last }
        val start = if (lastIndex >= 0) lastIndex + 1 else 0
        for (i in 0 until enabled.size) {
            val member = enabled[(start + i) % enabled.size]
            if (skippedMembers.contains(member.assistantId)) continue
            if (countTurns(conversation, member.assistantId) < config.rounds) return member
        }
        return null
    }

    /** 全局成员发言总数（含历史，maxTurns 硬上限用，防止锚点前移后硬上限失效） */
    private fun globalTurnCount(conversation: Conversation): Int =
        conversation.messageNodes.count { it.role == MessageRole.ASSISTANT && it.currentMessage.speakerId != null }

    private fun shouldTerminate(conversation: Conversation, config: DiscussionConfig, totalTurnCount: Int): Boolean {
        // maxTurns 是全局硬上限（防死循环），必须基于含历史的全局发言数；
        // 轮次达标基于锚点后的 countTurns（新一轮从零累计）。
        if (globalTurnCount(conversation) >= config.maxTurns) return true
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
            .filter { it.role == MessageRole.ASSISTANT && it.currentMessage.speakerId != null && it.currentMessage.speakerId != DISCUSSION_MODERATOR_ID }
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
            val raw = chunk.message
                .parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
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

    /** 单成员单轮生成。返回是否因不可用被跳过（true = 本轮未发言，调用方需排除该成员避免死循环） */
    private suspend fun generateMemberTurn(
        conversationId: Uuid,
        member: DiscussionMember,
        config: DiscussionConfig,
        groupName: String,
    ): Boolean {
        val settings = settingsStore.settingsFlow.first()
        val conversation = read(conversationId) ?: return true
        val assistant = settings.getAssistantById(member.assistantId)
        if (assistant == null) {
            writeSkipNotice(conversationId, "成员「${member.name}」的助手已被删除，跳过本轮发言")
            return true
        }
        val model = resolveMemberModel(settings, assistant) ?: run {
            writeSkipNotice(conversationId, "成员「${member.name}」的模型不可用，跳过本轮发言")
            return true
        }
        val providerSetting = model.findProvider(settings.providers) ?: run {
            writeSkipNotice(conversationId, "成员「${member.name}」的 provider 不可用，跳过本轮发言")
            return true
        }
        val provider = providerManager.getProviderByType(providerSetting)

        val messages = DiscussionPrompts.buildMemberMessages(
            member = member,
            assistantSystemPrompt = assistant.systemPrompt,
            config = config,
            conversation = conversation,
            groupName = groupName,
        )
        val tools = toolAssembler.assembleForMember(assistant, settings, conversation).canonicalToolOrder()
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
        return false
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
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("")),
                        // 主持人标识：UI 据此渲染"主持人"折叠卡片（区别于普通成员发言）
                        speakerId = DISCUSSION_MODERATOR_ID,
                        speakerName = "主持人",
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
                tools = emptyList(),
                params = TextGenerationParams(model = model, temperature = 0.3f),
                maxSteps = 4,
                onStep = {},
                onMessagesUpdate = { msgs ->
                    val last = msgs.lastOrNull { it.role == MessageRole.ASSISTANT }
                    if (last != null) writeTurnMessage(
                        conversationId,
                        nodeIndex,
                        last.copy(speakerId = DISCUSSION_MODERATOR_ID, speakerName = "主持人"),
                    )
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
                    speakerId = DISCUSSION_MODERATOR_ID,
                    speakerName = "主持人",
                )
            )
        }
        read(conversationId)?.let { c ->
            persist(conversationId, c.copy(updateAt = Instant.now()))
        }
    }

    // ---- 会话写入辅助 ----

    /**
     * 成员不可用（助手被删/模型/provider 缺失）时，追加一条可见的系统提示消息。
     * 不带 speakerId——不占用成员轮次，也不被 countTurns 计入，用户能明确看到谁被跳过。
     */
    private suspend fun writeSkipNotice(conversationId: Uuid, text: String) {
        update(conversationId) { conv ->
            conv.copy(
                messageNodes = conv.messageNodes + MessageNode.of(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("（$text）")),
                    )
                )
            )
        }
        // 提示消息也要落库，否则重启后消失（用户看不到谁被跳过）
        read(conversationId)?.let { c ->
            persist(conversationId, c.copy(updateAt = Instant.now()))
        }
    }

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
