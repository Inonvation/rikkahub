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
import me.rerere.rikkahub.data.model.effectiveCategory
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

    /** 删除分类：该分类下的助手退回「其他」（未分类），助手本身不受影响 */
    fun deleteCategory(id: Uuid) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistantTags = settings.assistantTags.filter { it.id != id },
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.effectiveCategory == id) assistant.copy(category = null) else assistant
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

    /** 设置单个助手的归属分类（单分类语义：null = 未分类）；categories 为编辑后的完整分类列表 */
    fun setAssistantCategory(assistant: Assistant, categoryId: Uuid?, categories: List<Tag>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistantTags = categories,
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) it.copy(category = categoryId) else it
                    }
                )
            )
        }
    }

    /** 把一批助手移动到某分类（单分类语义：覆盖其原有归属，null 用于移回「其他」） */
    fun moveAssistantsToCategory(categoryId: Uuid?, assistantIds: Collection<Uuid>) {
        if (assistantIds.isEmpty()) return
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.id in assistantIds) assistant.copy(category = categoryId) else assistant
                    }
                )
            )
        }
    }

    /**
     * 组内排序落盘：把某个分组（某分类的成员 或 未分类成员）的新顺序投影回全局助手列表。
     *
     * 根因：全局只有一个 assistants 顺序（同步/备份/导入都只认这一份），不能为每个分类再存一份顺序；
     * 组内顺序 = 全局顺序中该组成员的相对顺序。投影时保持非本组成员的相对位置不变，
     * 仅按新顺序回填本组成员原先占据的槽位，避免组内排序意外打乱其它组的相对顺序。
     */
    fun reorderGroupMembers(orderedMemberIds: List<Uuid>) {
        viewModelScope.launch {
            val settings = settings.value
            val global = settings.assistants
            val byId = global.associateBy { it.id }
            val memberIdSet = orderedMemberIds.toSet()
            // 该组成员在全局列表中占据的槽位（按原全局顺序）
            val slotIndexes = global.indices.filter { index ->
                global[index].id in memberIdSet
            }
            val ordered = orderedMemberIds.mapNotNull { byId[it] }
            // 成员集合与槽位不一致（并发删除等）时放弃，避免写坏全局顺序
            if (ordered.size != slotIndexes.size) return@launch
            val result = global.toMutableList()
            slotIndexes.forEachIndexed { index, slot -> result[slot] = ordered[index] }
            settingsStore.update(settings.copy(assistants = result))
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
