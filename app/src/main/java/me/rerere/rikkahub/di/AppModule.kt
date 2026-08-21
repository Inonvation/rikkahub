package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.retrieval.KeywordSearcher
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.TodoStorage
import me.rerere.rikkahub.data.db.fts.FtsKeywordSearcher
import me.rerere.rikkahub.data.db.fts.KnowledgeChunkFtsManager
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.data.management.ManagementRollbackStore
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.ChatDraftStore
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        TodoStorage(get())
    }

    // 会话级输入草稿缓存：切换会话/助手时保存未发送的输入，重新进入时恢复
    single {
        ChatDraftStore()
    }

    single {
        KnowledgeChunkFtsManager(get())
    }

    single<KeywordSearcher> {
        FtsKeywordSearcher(get(), get())
    }

    single {
        KnowledgeManager(
            knowledgeBaseDao = get(),
            knowledgeDocumentDao = get(),
            chunkDao = get(),
            keywordSearcher = get(),
        )
    }

    // 子代理
    single {
        me.rerere.rikkahub.data.ai.subagent.SubAgentToolAssembler(
            mcpManager = get(),
            settingsStore = get(),
            providerManager = get(),
            knowledgeManager = get(),
            workspaceRepository = get(),
        )
    }

    // 群组讨论
    single {
        me.rerere.rikkahub.data.ai.discussion.DiscussionToolAssembler(
            mcpManager = get(),
            knowledgeManager = get(),
            workspaceRepository = get(),
            localTools = get(),
            providerManager = get(),
        )
    }

    single {
        me.rerere.rikkahub.data.ai.subagent.SubAgentRunner(
            appScope = get(),
            providerManager = get(),
            settingsStore = get(),
            json = get(),
            toolAssembler = get(),
            conversationRepo = get(),
            usageDao = get(),
            taskDao = get(),
            todoStorage = get(),
        )
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    // createdAtStart：ChatService 启动即创建——内部订阅子代理完成流（异步唤醒母代理）
    // 必须在任何任务完成前就绪，否则完成事件会因无订阅者而丢失（同 ChatNotificationManager 模式）
    single(createdAtStart = true) {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            trustedFolderRepository = get(),
            folderRepository = get(),
            knowledgeManager = get(),
            todoStorage = get(),
            studyTools = get(),
            subAgentRunner = get(),
            discussionToolAssembler = get(),
            groupRepository = get(),
            json = get(),
            managementAuditStore = get(),
            managementRollbackStore = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
