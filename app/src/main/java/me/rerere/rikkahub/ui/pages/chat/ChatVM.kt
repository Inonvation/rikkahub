package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ContextCompositionRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.PendingGuidanceItem
import me.rerere.rikkahub.service.PendingSendItem
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatDraftStore
import me.rerere.rikkahub.ui.hooks.ChatInputState
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val initialMode: String? = null,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val chatDraftStore: ChatDraftStore,
    private val contextCompositionRepository: ContextCompositionRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 侧边栏展开状态 - 保存在 ViewModel 中，导航到子页面返回后仍保持
    var leftDrawerOpen by mutableStateOf(false)
    var rightDrawerOpen by mutableStateOf(false)

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    /**
     * 当前草稿槽：未落库的会话 = 尚未发送过的新对话，共用 [ChatDraftStore.NEW_CHAT_KEY]
     * 一个槽（新建对话的 id 每次都重新随机生成，按 id 存会随旧 id 失联，导致
     * “新建对话打字 → 切历史会话 → 再新建切回”丢字）；已落库会话按各自 id 存取。
     * 发送成功后（[clearDraft]）会话即将落库，槽位切到会话 id。
     */
    private var draftSlot: Uuid = _conversationId

    /** 输入草稿恢复完成信号：ChatPage 的 initText/initFiles 注入需等它，保证预填内容覆盖草稿 */
    private val inputReadyState = MutableStateFlow(false)
    val inputReady: StateFlow<Boolean> = inputReadyState.asStateFlow()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // 子代理完成气泡由 ChatService 在子代理完成时落库（合并进派发消息框架），
    // 无需在 VM 层收集。

    init {
        // 恢复输入草稿需先知道会话是否已落库（决定草稿槽），因此在 initializeConversation
        // 完成后进行；ChatPage 的 initText/initFiles 注入会等待 inputReady，分享预填内容
        // 最终覆盖草稿（顺序由 inputReady 保证，避免竞态）
        viewModelScope.launch {
            try {
                chatService.initializeConversation(_conversationId, initialMode)
                val persisted = conversationRepo.getConversationById(_conversationId) != null
                draftSlot = if (persisted) _conversationId else ChatDraftStore.NEW_CHAT_KEY
                chatDraftStore.load(draftSlot)?.let { draft ->
                    if (draft.text.isNotEmpty()) inputState.setMessageText(draft.text)
                    if (draft.parts.isNotEmpty()) inputState.messageContent = draft.parts
                }
            } finally {
                inputReadyState.value = true
            }
        }
        // 输入防抖落盘：进程被杀时 onCleared 不会执行，靠持续保存把丢失窗口压到 800ms。
        // drop(1) 跳过恢复值的首帧发射：恢复为空时不写空槽，避免把共享 NEW_CHAT_KEY 槽里
        // 其他未发送新会话的草稿误清掉；只有用户真实编辑才触发保存。
        viewModelScope.launch {
            snapshotFlow { inputState.textContent.text.toString() }
                .drop(1)
                .debounce(800)
                .collect { text ->
                    chatDraftStore.save(draftSlot, text, inputState.messageContent)
                }
        }

        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 恢复该会话最近一次生成的上下文构成快照（进程重启后进程级 store 为空）：
        // 浮窗构成详情 / 顶栏圆圈 / 自动压缩因此不回落兜底估算；本进程已生成过
        // （store 有值）时 restoreIfAbsent 直接跳过。
        viewModelScope.launch {
            contextCompositionRepository.restoreIfAbsent(_conversationId.toString())
        }

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId, initialMode)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 保存输入草稿（文本 + 附件）：ChatVM 随导航栈清理（cleanupChatPages）被销毁前，
        // 把未发送的输入暂存到草稿槽（未落库新会话为共享 NEW_CHAT_KEY 槽），重新进入该
        // 会话或再次新建对话时恢复
        chatDraftStore.save(draftSlot, inputState.textContent.text.toString(), inputState.messageContent)
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    /**
     * 发送成功后清除草稿：本次发送消费了当前草稿槽（未落库新会话为共享 NEW_CHAT_KEY 槽，
     * 已有历史会话为会话 id 槽），避免已发送内容在下次进入时被误恢复。
     * 此后会话已（即将）落库，后续草稿归位到会话 id 槽。
     */
    fun clearDraft() {
        chatDraftStore.remove(draftSlot)
        draftSlot = _conversationId
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    /** 只展示当前会话的错误；无会话归属的全局错误仍显示 */
    val conversationErrors: StateFlow<List<ChatError>> =
        chatService.errors
            .map { list ->
                list.filter { it.conversationId == null || it.conversationId == _conversationId }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFilesPermanently(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        chatService.sendMessage(_conversationId, content, answer)
    }

    /** 生成中发送消息（带附件走此路径）：正在生成则排队，回合正常结束后自动发送，不打断当前流式（#17） */
    fun sendMessageQueued(content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        chatService.sendMessageQueued(_conversationId, content, answer = answer)
    }

    /** 取消排队中的待发送消息（附件卡片点叉） */
    fun cancelPendingSend(itemId: Uuid) {
        chatService.cancelPendingSend(_conversationId, itemId)
    }

    /** 向主 AI 发送引导消息（生成中主输入框发送走此路径）：作为普通用户消息注入。
     *  默认排队，等当前回合输出完成后自动发送。文本与附件均支持。 */
    fun sendGuidance(content: List<UIMessagePart>) {
        if (content.isEmptyInputMessage()) return
        chatService.sendGuidance(_conversationId, content, immediate = false)
    }

    /** 排队引导旁的「打断并发送」：清空排队，中断当前生成，立即注入该引导 */
    fun sendGuidanceInterrupt(content: List<UIMessagePart>) {
        if (content.isEmptyInputMessage()) return
        chatService.sendGuidanceInterrupt(_conversationId, content)
    }

    /** 取消排队中的引导：从队列移除指定项 */
    fun cancelPendingGuidance(itemId: Uuid) {
        chatService.cancelPendingGuidance(_conversationId, itemId)
    }

    /** 点击气泡编辑：取消该条排队引导并把内容（文本+附件）回填输入框，编辑后重新发送 */
    fun editPendingGuidance(itemId: Uuid, parts: List<UIMessagePart>) {
        chatService.cancelPendingGuidance(_conversationId, itemId)
        inputState.setContents(parts)
    }

    /** 排队中的引导消息列表（订阅会话 steering 队列，逐条渲染气泡） */
    val pendingGuidance: StateFlow<List<PendingGuidanceItem>> =
        chatService.getPendingGuidanceFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 排队中的待发送消息列表（订阅会话 pendingSendQueue，逐条渲染卡片） */
    val pendingSends: StateFlow<List<PendingSendItem>> =
        chatService.getPendingSendFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentTokens: Int? = null,
    ): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentTokens
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun approveAllRelatedApprovals(toolCallId: String) {
        chatService.approveAllRelatedToolApprovals(_conversationId, toolCallId)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
