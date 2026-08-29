package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(assistant)
                )
            )
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            cleanupAssistantFiles(assistant)

            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.filter { it.id != assistant.id }
                )
            )
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFilesPermanently(uris)
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(copiedAssistant)
                )
            )
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }

    // ---------- 分类管理（分类即 Tag，零引用不自动清理，删除只走显式入口） ----------

    fun addCategory(name: String) {
        viewModelScope.launch {
            val settings = settings.value
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@launch
            if (settings.assistantTags.any { it.name.equals(trimmed, ignoreCase = true) }) return@launch
            settingsStore.update(
                settings.copy(
                    assistantTags = settings.assistantTags + Tag(id = Uuid.random(), name = trimmed)
                )
            )
        }
    }

    fun renameCategory(id: Uuid, newName: String) {
        viewModelScope.launch {
            val settings = settings.value
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) return@launch
            // 与其他分类重名（忽略大小写）时放弃重命名
            if (settings.assistantTags.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) return@launch
            settingsStore.update(
                settings.copy(
                    assistantTags = settings.assistantTags.map { tag ->
                        if (tag.id == id) tag.copy(name = trimmed) else tag
                    }
                )
            )
        }
    }

    /** 删除分类并移除所有助手中对该分类的引用，助手本身不受影响 */
    fun deleteCategory(id: Uuid) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistantTags = settings.assistantTags.filter { it.id != id },
                    assistants = settings.assistants.map { assistant ->
                        if (id in assistant.tags) assistant.copy(tags = assistant.tags - id) else assistant
                    }
                )
            )
        }
    }

    fun reorderCategories(categories: List<Tag>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(settings.copy(assistantTags = categories))
        }
    }

    /** 全量编辑某个助手的分类归属；categories 为编辑后的完整分类列表（可能含对话框里新建的分类） */
    fun updateAssistantTags(assistant: Assistant, tagIds: List<Uuid>, categories: List<Tag>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistantTags = categories,
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) it.copy(tags = tagIds) else it
                    }
                )
            )
        }
    }

    fun addAssistantsToCategory(categoryId: Uuid, assistantIds: Collection<Uuid>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.id in assistantIds && categoryId !in assistant.tags) {
                            assistant.copy(tags = assistant.tags + categoryId)
                        } else {
                            assistant
                        }
                    }
                )
            )
        }
    }

    fun moveAssistantToTop(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = buildList {
                        add(assistant)
                        addAll(settings.assistants.filter { it.id != assistant.id })
                    }
                )
            )
        }
    }
}
