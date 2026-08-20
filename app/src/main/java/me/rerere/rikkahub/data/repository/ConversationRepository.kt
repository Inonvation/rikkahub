package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.SubAgentUsageEntity
import me.rerere.rikkahub.data.db.dao.SubAgentUsageDAO
import me.rerere.rikkahub.data.db.dao.SubAgentTaskDAO
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.ai.tools.TodoStorage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.DiscussionConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.ConversationIndexEntry
import me.rerere.rikkahub.data.sync.ConversationNode
import me.rerere.rikkahub.data.sync.ConversationSyncAccess
import me.rerere.rikkahub.data.sync.ConversationSyncItem
import me.rerere.rikkahub.data.sync.ConversationTombstone
import me.rerere.rikkahub.data.sync.SyncStateStore
import me.rerere.rikkahub.data.sync.buildConversationItem
import me.rerere.rikkahub.data.sync.conversationItemPath
import me.rerere.rikkahub.data.sync.encodeConversationItem
import me.rerere.rikkahub.data.sync.mapRemoteUrlsToLocal
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val todoStorage: TodoStorage,
    private val subAgentUsageDAO: SubAgentUsageDAO,
    private val subAgentTaskDAO: SubAgentTaskDAO,
    /** 会话增量同步的单调时钟来源（版本号生成）。 */
    private val syncStateStore: SyncStateStore,
    /** Lazy 注入打破循环依赖：SubAgentRunner 依赖本 Repository，本类依赖它（删会话级联取消子代理）。
     *  lazy 延迟解析，首次访问时才从 Koin 取实例，避免构造期死锁。 */
    private val subAgentRunner: kotlin.Lazy<me.rerere.rikkahub.data.ai.subagent.SubAgentRunner>,
) : ConversationSyncAccess {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40

        /** 解析一段 messages JSON，得到每条消息 (syncUpdatedAt, contentKey)；损坏则跳过该段。 */
        internal fun parseMessageSyncState(json: String): Map<String, Pair<Long, String>> =
            runCatching {
                JsonInstant.decodeFromString<List<UIMessage>>(json)
                    .associate { message ->
                        message.id.toString() to (message.syncUpdatedAt to contentKeyOf(message))
                    }
            }.getOrElse { emptyMap() }

        /** 排除 syncUpdatedAt 的内容签名：统一置 0 再序列化，确保版本字段不参与内容比对。 */
        internal fun contentKeyOf(message: UIMessage): String =
            JsonInstant.encodeToString(message.copy(syncUpdatedAt = 0L))

        /** 每条消息的内容签名，与 [nodes] 逐节点对应。 */
        internal fun messageContentKeys(nodes: List<MessageNode>): List<List<String>> =
            nodes.map { node -> node.messages.map { message -> contentKeyOf(message) } }

        /** 需要推进版本的消息数：新消息 / 内容变化 / 旧版本为 0（存量未参与同步）。 */
        internal fun countPendingSyncVersions(
            nodes: List<MessageNode>,
            oldState: Map<String, Map<String, Pair<Long, String>>>,
            messageKeys: List<List<String>>,
        ): Int = nodes.indices.sumOf { i ->
            val oldMessages = oldState[nodes[i].id.toString()] ?: emptyMap()
            nodes[i].messages.indices.count { j ->
                val messageId = nodes[i].messages[j].id.toString()
                val old = oldMessages[messageId]
                old == null || old.first <= 0L || old.second != messageKeys[i][j]
            }
        }

        /**
         * 为每个节点分配消息版本（纯函数，决策与入库分离便于单测）：
         * 内容未变且旧版本有效 → 保留原版本；否则从 [base] 起递增分配新版本。
         */
        internal fun assignSyncVersions(
            nodes: List<MessageNode>,
            oldState: Map<String, Map<String, Pair<Long, String>>>,
            messageKeys: List<List<String>>,
            base: Long,
        ): List<List<UIMessage>> {
            var next = base
            return nodes.mapIndexed { index, node ->
                val oldMessages = oldState[node.id.toString()] ?: emptyMap()
                node.messages.mapIndexed { j, message ->
                    val messageId = message.id.toString()
                    val old = oldMessages[messageId]
                    val syncUpdatedAt = if (old != null && old.first > 0L && old.second == messageKeys[index][j]) {
                        old.first
                    } else {
                        next++
                        next
                    }
                    message.copy(syncUpdatedAt = syncUpdatedAt)
                }
            }
        }

        /**
         * 消息级合并（纯函数，下载方向）：墓碑优先 + 按消息 id union + syncUpdatedAt 大者胜（平局按内容签名）。
         * 本地节点顺序为骨架，同 id 远端节点消息并入；远端独有节点追加；空节点剔除。不丢任何一方的消息。
         */
        internal fun mergeConversationMessages(
            localNodes: List<MessageNode>,
            remoteNodes: List<ConversationNode>,
            deletedMessageIds: Set<String>,
        ): List<MessageNode> {
            val remoteByNodeId = remoteNodes.associateBy { it.id }
            val result = mutableListOf<MessageNode>()
            val usedNodeIds = mutableSetOf<String>()

            for (localNode in localNodes) {
                val nodeId = localNode.id.toString()
                usedNodeIds += nodeId
                val remoteNode = remoteByNodeId[nodeId]
                val localMessages = filterDeleted(localNode.messages, deletedMessageIds)
                val messages = if (remoteNode == null) {
                    localMessages
                } else {
                    mergeMessageLists(localMessages, filterDeleted(remoteNode.messages, deletedMessageIds))
                }
                result += localNode.copy(messages = messages)
            }
            for (remoteNode in remoteNodes) {
                if (remoteNode.id in usedNodeIds) continue
                val nodeId = runCatching { Uuid.parse(remoteNode.id) }.getOrNull() ?: continue
                result += MessageNode(
                    id = nodeId,
                    messages = filterDeleted(remoteNode.messages, deletedMessageIds),
                    selectIndex = remoteNode.selectIndex,
                )
            }
            return result.filter { it.messages.isNotEmpty() }
        }

        /** 两条消息列表按 id union，syncUpdatedAt 大者胜；平局按内容签名比较取大者。 */
        internal fun mergeMessageLists(a: List<UIMessage>, b: List<UIMessage>): List<UIMessage> {
            val byId = LinkedHashMap<String, UIMessage>()
            for (m in a) byId[m.id.toString()] = m
            for (m in b) {
                val id = m.id.toString()
                val existing = byId[id]
                if (existing == null || m.syncUpdatedAt > existing.syncUpdatedAt) {
                    byId[id] = m
                } else if (m.syncUpdatedAt == existing.syncUpdatedAt) {
                    if (contentKeyOf(m) > contentKeyOf(existing)) byId[id] = m
                }
            }
            return byId.values.toList()
        }

        private fun filterDeleted(messages: List<UIMessage>, deleted: Set<String>): List<UIMessage> =
            messages.filterNot { it.id.toString() in deleted }
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    /** 某群组下的全部会话（不含消息节点），供群组详情历史列表用 */
    fun getConversationsOfGroup(groupId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfGroup(groupId.toString())
            .map { flow ->
                flow.map { entity -> conversationEntityToConversation(entity, emptyList()) }
            }
    }

    /** 某群组下的全部会话（一次性查询，供删除前停生成等用） */
    suspend fun getConversationsOfGroupOnce(groupId: Uuid): List<Conversation> {
        return conversationDAO
            .getConversationsOfGroupOnce(groupId.toString())
            .map { entity -> conversationEntityToConversation(entity, emptyList()) }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun countConversationsByMode(modeRef: String): Int {
        return conversationDAO.countByMode(modeRef)
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            // 先建会话（FK：节点引用会话必须先存在），用临时时钟值，随后统一为消息最大版本
            val initialSyncAt = syncStateStore.nextSyncClock()
            conversationDAO.insert(
                conversationToConversationEntity(conversation).copy(syncUpdatedAt = initialSyncAt)
            )
            val maxVersion = saveMessageNodes(
                conversation.id.toString(),
                conversation.messageNodes,
                deleteExisting = false,
            )
            if (maxVersion > initialSyncAt) {
                conversationDAO.update(
                    conversationToConversationEntity(conversation).copy(syncUpdatedAt = maxVersion)
                )
            }
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            // 防呆：调用方误传空 nodes 时不得清空已有历史（否则聊天记录被整段删光）。
            // 当前调用方都先读完整 Conversation 再保存，不会触发；此守卫是「定时炸弹」防御。
            if (conversation.messageNodes.isEmpty() &&
                messageNodeDAO.getNodesOfConversationPaged(conversation.id.toString(), 1, 0).isNotEmpty()
            ) {
                throw IllegalStateException(
                    "updateConversation: refusing to wipe ${conversation.id} history with empty messageNodes"
                )
            }
            val newEntity = conversationToConversationEntity(conversation)
            val oldEntity = conversationDAO.getConversationById(conversation.id.toString())
            // 删除旧的节点，插入新的节点（saveMessageNodes 内部处理，并维护消息/会话版本）
            val maxMsgVersion = saveMessageNodes(
                conversation.id.toString(),
                conversation.messageNodes,
                deleteExisting = true,
            )
            // 会话版本 = max(消息版本, 元数据变化时的时钟)。仅消息变化而元数据未变时不额外推进，
            // 避免「整会话重插」把没变化的会话也标记为需要重传。
            val syncUpdatedAt = if (oldEntity != null && conversationMetaChanged(oldEntity, newEntity)) {
                maxOf(maxMsgVersion, syncStateStore.nextSyncClock())
            } else {
                maxMsgVersion
            }
            conversationDAO.update(newEntity.copy(syncUpdatedAt = syncUpdatedAt))
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 记录会话墓碑（增量同步传播删除到云端），幂等去重。失败不阻断删除本身。
        runCatching {
            val deletedAt = syncStateStore.nextSyncClock()
            syncStateStore.update { state ->
                if (state.deletedConversations.any { it.id == conversation.id.toString() }) {
                    state
                } else {
                    state.copy(
                        deletedConversations = state.deletedConversations +
                            ConversationTombstone(conversation.id.toString(), deletedAt)
                    )
                }
            }
        }
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        // 先停掉该会话运行中的子代理（B3）：否则它们跑完还会 persistTask 把记录写回，产生僵尸数据。
        // cancel 是同步的（置 CANCELLED + job.cancel），CANCELLED 不触发异步唤醒，无副作用。
        subAgentRunner.value.cancelByConversation(conversation.id)
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
            // 同步清理该会话的子代理 token 统计与任务历史，避免残留计入全局统计/历史
            subAgentUsageDAO.deleteByConversation(conversation.id.toString())
            subAgentTaskDAO.deleteByConversation(conversation.id.toString())
        }
        filesManager.deleteChatFilesPermanently(fullConversation.files)
        todoStorage.delete(conversation.id.toString())
    }

    // ------------------------------------------------------------------
    // ConversationSyncAccess（会话增量同步）
    // ------------------------------------------------------------------

    override suspend fun listLocalIndexEntries(): List<ConversationIndexEntry> {
        return conversationDAO.getSyncEntries().map { e ->
            ConversationIndexEntry(
                id = e.id,
                syncUpdatedAt = e.syncUpdatedAt,
                title = e.title,
                createAt = e.createAt,
                isPinned = e.isPinned,
                messageCount = e.messageCount,
                path = conversationItemPath(e.id),
            )
        }
    }

    override suspend fun exportConversation(id: String): ConversationSyncItem? {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return null
        ensureConversationVersion(id)
        val conversation = getConversationById(uuid) ?: return null
        return buildConversationItem(conversation, filesRoot = deviceFilesRoot())
    }

    override suspend fun localDeletedTombstones(): List<ConversationTombstone> =
        syncStateStore.current().deletedConversations

    override suspend fun conversationSize(id: String): Long? {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return null
        val conversation = getConversationById(uuid) ?: return null
        return encodeConversationItem(
            buildConversationItem(conversation, filesRoot = deviceFilesRoot())
        ).size.toLong()
    }

    /** 本机 files 目录绝对路径，用于导出时脱敏设备路径。 */
    private fun deviceFilesRoot(): String =
        filesManager.getUploadDir().parentFile?.absolutePath ?: ""

    override suspend fun mergeConversation(item: ConversationSyncItem): Boolean {
        val uuid = runCatching { Uuid.parse(item.id) }.getOrNull() ?: return false
        // 读 local + 合并计算 + 写 放在同一个事务内：避免与 updateConversation（用户发消息）并发时，
        // 基于过期快照整会话覆盖，丢失用户刚入库的新消息（TOCTOU）。
        val merged = database.withTransaction {
            val localEntity = conversationDAO.getConversationById(item.id)
            val localNodes = if (localEntity != null) loadMessageNodes(item.id) else emptyList()
            val local = localEntity?.let { conversationEntityToConversation(it, localNodes) }
            val deletedIds = item.deletedMessageIds.map { it.id }.toSet()

            // 消息级合并：墓碑优先 + 按消息 id union + LWW（syncUpdatedAt 大者胜）
            val mergedNodes = mergeConversationMessages(localNodes, item.nodes, deletedIds)

            // 附件引用下载方向映射：upload/<name> -> 本地 file:// 路径
            val uploadDir = filesManager.getUploadDir().absolutePath
            val nodesWithLocalRefs = mergedNodes.map { node ->
                node.copy(
                    messages = node.messages.map { message ->
                        message.copy(parts = mapRemoteUrlsToLocal(message.parts, uploadDir))
                    },
                )
            }

            // 会话元数据 LWW：syncUpdatedAt 大者胜
            val remoteWins = local == null || item.syncUpdatedAt >= local.syncUpdatedAt
            val maxMsgVersion = nodesWithLocalRefs.maxOfOrNull { n ->
                n.messages.maxOfOrNull { it.syncUpdatedAt } ?: 0L
            } ?: 0L
            // 合并结果版本：全新会话取云端版本；参与合并则推进 +1，保证下次同步把合并结果上传回云端
            val newVersion = if (local == null) {
                item.syncUpdatedAt
            } else {
                maxOf(item.syncUpdatedAt, local.syncUpdatedAt, maxMsgVersion) + 1L
            }

            val mergedConversation = Conversation(
                id = uuid,
                assistantId = runCatching { Uuid.parse(item.assistantId) }
                    .getOrElse { local?.assistantId ?: DEFAULT_ASSISTANT_ID },
                title = if (remoteWins) item.title else local!!.title,
                messageNodes = nodesWithLocalRefs,
                createAt = local?.createAt ?: Instant.ofEpochMilli(item.createAt),
                updateAt = local?.updateAt ?: Instant.now(),
                isPinned = if (remoteWins) item.isPinned else local!!.isPinned,
                chatSuggestions = local?.chatSuggestions ?: emptyList(),
                customSystemPrompt = if (remoteWins) item.customSystemPrompt else local!!.customSystemPrompt,
                modeInjectionIds = local?.modeInjectionIds ?: emptySet(),
                lorebookIds = local?.lorebookIds ?: emptySet(),
                workspaceCwd = if (remoteWins) item.workspaceCwd else local!!.workspaceCwd,
                // 模式本地优先（与 modeInjectionIds 一致），云端仅在本地缺失时补位
                mode = local?.mode ?: item.mode,
                folderId = local?.folderId,
                discussion = local?.discussion,
                groupId = local?.groupId,
                syncUpdatedAt = newVersion,
            )

            val entity = conversationToConversationEntity(mergedConversation)
            if (local == null) {
                conversationDAO.insert(entity)
            } else {
                conversationDAO.update(entity)
            }
            messageNodeDAO.deleteByConversation(item.id)
            messageNodeDAO.insertAll(
                nodesWithLocalRefs.mapIndexed { index, node ->
                    MessageNodeEntity(
                        id = node.id.toString(),
                        conversationId = item.id,
                        nodeIndex = index,
                        messages = JsonInstant.encodeToString(node.messages),
                        selectIndex = node.selectIndex,
                    )
                }
            )
            mergedConversation
        }
        messageFtsManager.indexConversation(merged)
        // 吸收远端版本进本地单调时钟：设备时钟落后远端时，本地下一次编辑仍能产出更大版本（LWW 不输）
        runCatching { syncStateStore.observeSyncClock(item.syncUpdatedAt) }
        return true
    }

    override suspend fun deleteRemoteConversation(id: String) {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return
        val conversation = getConversationById(uuid) ?: return
        // 应用云端删除：与本地删除不同，不重复记录墓碑（云端 index.deleted 已是权威）
        messageFtsManager.deleteConversation(id)
        database.withTransaction {
            conversationDAO.delete(conversationToConversationEntity(conversation))
            subAgentUsageDAO.deleteByConversation(id)
            subAgentTaskDAO.deleteByConversation(id)
        }
        filesManager.deleteChatFilesPermanently(conversation.files)
        todoStorage.delete(id)
        // 从本地待传播墓碑中移除（已确认删除）
        syncStateStore.update { state ->
            state.copy(deletedConversations = state.deletedConversations.filterNot { it.id == id })
        }
    }

    override suspend fun clearLocalTombstones(ids: Set<String>) {
        if (ids.isEmpty()) return
        syncStateStore.update { state ->
            state.copy(deletedConversations = state.deletedConversations.filterNot { it.id in ids })
        }
    }

    /** 存量会话（syncUpdatedAt==0）初始化版本：给消息补版本并写回，保证导出条目版本稳定。 */
    private suspend fun ensureConversationVersion(conversationId: String): Long {
        val entity = conversationDAO.getConversationById(conversationId) ?: return 0L
        if (entity.syncUpdatedAt > 0L) return entity.syncUpdatedAt
        val conversation = getConversationById(Uuid.parse(conversationId)) ?: return 0L
        return database.withTransaction {
            val maxMsgVersion = saveMessageNodes(conversationId, conversation.messageNodes, deleteExisting = false)
            val newVersion = maxOf(maxMsgVersion, syncStateStore.nextSyncClock())
            conversationDAO.update(conversationToConversationEntity(conversation).copy(syncUpdatedAt = newVersion))
            newVersion
        }
    }

    /** 某会话的子代理用量明细流（任务终态落库后触发），供聊天底部栏并入缓存/费用统计。 */
    fun observeSubAgentUsage(conversationId: String): Flow<List<SubAgentUsageEntity>> =
        subAgentUsageDAO.observeByConversation(conversationId)

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageFtsManager.search(keyword, sort)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            syncUpdatedAt = conversation.syncUpdatedAt,
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            mode = conversation.mode ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            discussionJson = conversation.discussion?.let { JsonInstant.encodeToString(it) } ?: "",
            groupId = conversation.groupId?.toString() ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            syncUpdatedAt = conversationEntity.syncUpdatedAt,
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            mode = conversationEntity.mode.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            discussion = conversationEntity.discussionJson.ifEmpty { null }
                ?.let { runCatching { JsonInstant.decodeFromString<DiscussionConfig>(it) }.getOrNull() },
            groupId = conversationEntity.groupId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    // 整页查询失败：记日志并跳页（比静默丢整页好，至少可追踪）
                    Log.e("ConversationRepository", "loadMessageNodes: page query failed at offset=$offset", e)
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    Log.e("ConversationRepository", "loadMessageNodes: page query failed at offset=$offset", e)
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    // 单条节点损坏只跳过该条，不整页丢弃——避免聊天记录莫名变短
                    runCatching {
                        val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                        val nodeId = Uuid.parse(entity.id)
                        nodes.add(
                            MessageNode(
                                id = nodeId,
                                messages = messages,
                                selectIndex = entity.selectIndex,
                                isFavorite = favoriteNodeIds.contains(nodeId)
                            )
                        )
                    }.onFailure { e ->
                        Log.e("ConversationRepository", "loadMessageNodes: skipping corrupted node ${entity.id}", e)
                    }
                }
                offset += page.size
            }
            nodes
        }
    }

    /**
     * 保存会话的全部节点，并维护会话增量同步的消息版本号。
     *
     * 关键约束：`updateConversation` 是「整会话删旧重插」。若每次重插都推进所有消息版本，
     * 同步引擎会误判「全部消息都变了」导致整会话重传（增量失效）。因此这里：
     * - 消息 id 已存在、旧版本有效（>0）且内容（排除 syncUpdatedAt 的签名）未变 → 保留原版本
     * - 新消息 / 内容变化 / 旧版本为 0（存量未参与同步）→ 分配单调时钟新版本
     *
     * @param deleteExisting 是否先删除该会话旧节点（update 场景 true；insert 场景 false，无旧节点）
     * @return 本会话新的 syncUpdatedAt = max(所有消息版本)，供会话列写入
     */
    private suspend fun saveMessageNodes(
        conversationId: String,
        nodes: List<MessageNode>,
        deleteExisting: Boolean,
    ): Long {
        // 读取旧节点的消息版本与内容签名：nodeId -> messageId -> (version, contentKey)
        val oldState: Map<String, Map<String, Pair<Long, String>>> = runCatching {
            messageNodeDAO.getNodesOfConversation(conversationId)
                .associate { node -> node.id to parseMessageSyncState(node.messages) }
        }.getOrElse { emptyMap() }

        // 版本分配为纯函数（可单测）：内容签名只算一遍，推进数量一次批量分配时钟
        val messageKeys = messageContentKeys(nodes)
        val pendingCount = countPendingSyncVersions(nodes, oldState, messageKeys)
        val base = if (pendingCount > 0) syncStateStore.nextSyncClock(pendingCount) else 0L
        val messageLists = assignSyncVersions(nodes, oldState, messageKeys, base)

        var maxVersion = 0L
        for (list in messageLists) for (m in list) if (m.syncUpdatedAt > maxVersion) maxVersion = m.syncUpdatedAt

        val entities = messageLists.mapIndexed { index, messages ->
            MessageNodeEntity(
                id = nodes[index].id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(messages),
                selectIndex = nodes[index].selectIndex
            )
        }

        if (deleteExisting) {
            messageNodeDAO.deleteByConversation(conversationId)
        }
        messageNodeDAO.insertAll(entities)
        return maxVersion
    }

    /** 会话元数据（除消息节点与 sync 版本外）是否有变化，驱动会话版本推进。 */
    private fun conversationMetaChanged(old: ConversationEntity, new: ConversationEntity): Boolean =
        old.assistantId != new.assistantId ||
            old.title != new.title ||
            old.chatSuggestions != new.chatSuggestions ||
            old.isPinned != new.isPinned ||
            old.customSystemPrompt != new.customSystemPrompt ||
            old.mode != new.mode ||
            old.modeInjectionIds != new.modeInjectionIds ||
            old.lorebookIds != new.lorebookIds ||
            old.workspaceCwd != new.workspaceCwd ||
            old.folderId != new.folderId ||
            old.discussionJson != new.discussionJson ||
            old.groupId != new.groupId
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
