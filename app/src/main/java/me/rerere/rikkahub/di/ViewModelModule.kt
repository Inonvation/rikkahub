package me.rerere.rikkahub.di

import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerVM
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.ui.pages.chat.GroupDiscussionCreateVM
import me.rerere.rikkahub.ui.pages.chat.GroupDiscussionEditVM
import me.rerere.rikkahub.ui.pages.chat.GroupDiscussionListVM
import me.rerere.rikkahub.ui.pages.chat.GroupDiscussionVM
import me.rerere.rikkahub.ui.pages.chat.GroupDetailVM
import me.rerere.rikkahub.ui.pages.chat.PromptOptimizeVM
import me.rerere.rikkahub.ui.pages.debug.DebugVM
import me.rerere.rikkahub.ui.pages.favorite.FavoriteVM
import me.rerere.rikkahub.ui.pages.search.SearchVM
import me.rerere.rikkahub.ui.pages.history.HistoryVM
import me.rerere.rikkahub.ui.pages.stats.StatsVM
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeBasesVM
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeBaseDetailVM
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeBaseSettingsVM
import me.rerere.rikkahub.ui.pages.imggen.ImgGenVM
import me.rerere.rikkahub.ui.pages.extensions.PromptVM
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesVM
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillDetailVM
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillsVM
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailVM
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceVM
import me.rerere.rikkahub.ui.pages.recyclebin.RecycleBinVM
import me.rerere.rikkahub.ui.pages.setting.AgentConfigVM
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkahub.ui.pages.translator.TranslatorVM
import me.rerere.rikkahub.ui.pages.study.vocabulary.VocabularyPanelVM
import me.rerere.rikkahub.ui.pages.study.notes.NotesPanelVM
import me.rerere.rikkahub.ui.pages.study.wrongquestions.WrongQuestionPanelVM
import me.rerere.rikkahub.ui.pages.study.knowledgecards.KnowledgeCardPanelVM
import me.rerere.rikkahub.ui.pages.study.stats.StudyStatsVM
import me.rerere.rikkahub.ui.pages.trustedfolders.TrustedFolderDetailVM
import me.rerere.rikkahub.ui.pages.trustedfolders.TrustedFolderSettingsVM
import me.rerere.rikkahub.ui.pages.trustedfolders.TrustedFoldersVM
import me.rerere.rikkahub.data.DocumentProcessor
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            initialMode = runCatching { params.get<String>(1) }.getOrNull(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            filesManager = get(),
            favoriteRepository = get(),
            chatDraftStore = get(),
            contextCompositionRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::AgentConfigVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModelOf(::GroupDiscussionCreateVM)
    viewModel<GroupDiscussionListVM> {
        GroupDiscussionListVM(
            chatService = get(),
            groupRepository = get(),
        )
    }
    viewModel<GroupDetailVM> { params ->
        GroupDetailVM(
            id = params.get(),
            chatService = get(),
            conversationRepo = get(),
        )
    }
    viewModel<GroupDiscussionVM> { params ->
        GroupDiscussionVM(
            id = params.get(),
            chatService = get(),
            settingsStore = get(),
        )
    }
    viewModel<GroupDiscussionEditVM> { params ->
        GroupDiscussionEditVM(
            id = params.get(),
            chatService = get(),
            settingsStore = get(),
        )
    }
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModelOf(::PromptOptimizeVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModelOf(::RecycleBinVM)
    viewModel<WorkspaceDetailVM> {
        // 从聊天跳转定位时，parametersOf 传入 (areaName: String?, path: String)；
        // 旧调用只传 id，此时参数数量不足，get 会抛 NoSuchElementException → runCatching 回落默认值
        val initialAreaName = runCatching { it.get<String>(1) }.getOrNull()
        val initialPath = runCatching { it.get<String>(2) }.getOrNull() ?: ""
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            initialArea = initialAreaName?.let { name ->
                runCatching { WorkspaceStorageArea.valueOf(name) }.getOrNull()
            },
            initialPath = initialPath,
            terminalSessionManager = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
    viewModelOf(::KnowledgeBasesVM)
    viewModel<KnowledgeBaseDetailVM> { params ->
        val baseId = params.get<String>()
        KnowledgeBaseDetailVM(
            knowledgeManager = get(),
            settingsStore = get(),
            providerManager = get(),
            documentProcessor = DocumentProcessor(
                knowledgeManager = get(),
                settingsStore = get(),
                providerManager = get(),
                ftsManager = get(),
                baseId = baseId,
            ),
            baseId = baseId,
        )
    }
    viewModel<KnowledgeBaseSettingsVM> { params ->
        val baseId = params.get<String>()
        KnowledgeBaseSettingsVM(
            knowledgeManager = get(),
            documentProcessor = DocumentProcessor(
                knowledgeManager = get(),
                settingsStore = get(),
                providerManager = get(),
                ftsManager = get(),
                baseId = baseId,
            ),
            baseId = baseId,
        )
    }
    viewModelOf(::VocabularyPanelVM)
    viewModelOf(::NotesPanelVM)
    viewModelOf(::WrongQuestionPanelVM)
    viewModelOf(::KnowledgeCardPanelVM)
    viewModelOf(::StudyStatsVM)
    viewModelOf(::TrustedFoldersVM)
    viewModel<TrustedFolderDetailVM> { params ->
        TrustedFolderDetailVM(
            repository = get(),
            projectId = runCatching { params.get<String>(0) }.getOrNull() ?: "",
            initialPath = runCatching { params.get<String>(1) }.getOrNull() ?: "",
        )
    }
    viewModel<TrustedFolderSettingsVM> { params ->
        TrustedFolderSettingsVM(
            repository = get(),
            projectId = runCatching { params.get<String>(0) }.getOrNull() ?: "",
        )
    }
}
