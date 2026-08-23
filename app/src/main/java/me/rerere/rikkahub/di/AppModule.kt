package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.retrieval.KeywordSearcher
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.device.DeviceTools
import me.rerere.rikkahub.data.device.DeviceSafetyStore
import me.rerere.rikkahub.data.device.SafetyGuard
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
        DeviceSafetyStore(get())
    }

    single {
        SafetyGuard(get(), get())
    }

    single {
        DeviceTools(get(), get())
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

    // 浼氳瘽绾ц緭鍏ヨ崏绋跨紦瀛橈細鍒囨崲浼氳瘽/鍔╂墜鏃朵繚瀛樻湭鍙戦€佺殑杈撳叆锛岄噸鏂拌繘鍏ユ椂鎭㈠
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

    // 瀛愪唬鐞?
    single {
        me.rerere.rikkahub.data.ai.subagent.SubAgentToolAssembler(
            mcpManager = get(),
            settingsStore = get(),
            providerManager = get(),
            knowledgeManager = get(),
            workspaceRepository = get(),
        )
    }

    // 缇ょ粍璁ㄨ
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

    // 鐢熸垚閫氱煡涓庝笟鍔¤В鑰︼細ChatService 鍙彂浜嬩欢锛岄€氱煡鐢辫繖閲屾秷璐癸紱
    // createdAtStart 淇濊瘉杩涚▼鍚姩鍗宠闃咃紝鍚﹀垯鍚庡彴鐢熸垚鐨勪簨浠朵細鍥犳棤璁㈤槄鑰呰€屼涪澶?
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    // createdAtStart锛欳hatService 鍚姩鍗冲垱寤衡€斺€斿唴閮ㄨ闃呭瓙浠ｇ悊瀹屾垚娴侊紙寮傛鍞ら啋姣嶄唬鐞嗭級
    // 蹇呴』鍦ㄤ换浣曚换鍔″畬鎴愬墠灏辩华锛屽惁鍒欏畬鎴愪簨浠朵細鍥犳棤璁㈤槄鑰呰€屼涪澶憋紙鍚?ChatNotificationManager 妯″紡锛?
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
            deviceTools = get(),
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
