package me.rerere.rikkahub.ui.pages.recyclebin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.KnowledgeCardDao
import me.rerere.rikkahub.data.db.dao.NoteDao
import me.rerere.rikkahub.data.db.dao.VocabularyDao
import me.rerere.rikkahub.data.db.dao.WrongQuestionDao
import me.rerere.rikkahub.data.db.entity.KnowledgeCardEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.NoteEntity
import me.rerere.rikkahub.data.db.entity.VocabularyEntity
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

/**
 * 统一回收站: 聚合聊天附件、学习归档(生词/笔记/错题/卡片)、工作区文件三块,
 * 分区展示并提供"恢复 / 永久删除".
 */
class RecycleBinVM(
    private val vocabularyDao: VocabularyDao,
    private val noteDao: NoteDao,
    private val wrongQuestionDao: WrongQuestionDao,
    private val knowledgeCardDao: KnowledgeCardDao,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RecycleBinState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // 分区加载，互不拖累：某个工作区 manifest 损坏 / 某个 DAO 失败时降级为空，
            // 避免"一个来源失败导致整页报错、其余内容全部不可见不可恢复"。
            val workspaces = runCatching { workspaceRepository.listFlow().first() }
                .getOrDefault(emptyList())
            val workspaceFiles = workspaces.flatMap { ws ->
                WorkspaceStorageArea.entries.flatMap { area ->
                    runCatching { workspaceRepository.listTrash(ws.id, area) }
                        .getOrDefault(emptyList())
                        .map { entry ->
                            WorkspaceTrashItem(
                                workspaceId = ws.id,
                                workspaceName = ws.name,
                                area = area,
                                entry = entry,
                            )
                        }
                }
            }
            _state.value = RecycleBinState(
                loading = false,
                chatFiles = runCatching { filesManager.listTrashChatFiles() }.getOrDefault(emptyList()),
                vocabularies = runCatching { vocabularyDao.getArchived() }.getOrDefault(emptyList()),
                notes = runCatching { noteDao.getArchived() }.getOrDefault(emptyList()),
                wrongQuestions = runCatching { wrongQuestionDao.getArchived() }.getOrDefault(emptyList()),
                knowledgeCards = runCatching { knowledgeCardDao.getArchived() }.getOrDefault(emptyList()),
                workspaceFiles = workspaceFiles,
            )
        }
    }

    fun restoreChatFile(id: Long) = launch { filesManager.restoreChatFile(id) }

    fun deleteChatFilePermanently(id: Long) = launch { filesManager.deleteTrashChatFilePermanently(id) }

    fun restoreVocabulary(id: String) = launch { vocabularyDao.restore(id); true }

    fun deleteVocabularyPermanently(id: String) = launch { vocabularyDao.deleteById(id); true }

    fun restoreNote(id: String) = launch { noteDao.restore(id); true }

    fun deleteNotePermanently(id: String) = launch { noteDao.deleteById(id); true }

    fun restoreWrongQuestion(id: String) = launch { wrongQuestionDao.restore(id); true }

    fun deleteWrongQuestionPermanently(id: String) = launch { wrongQuestionDao.deleteById(id); true }

    fun restoreKnowledgeCard(id: String) = launch { knowledgeCardDao.restore(id); true }

    fun deleteKnowledgeCardPermanently(id: String) = launch { knowledgeCardDao.deleteById(id); true }

    fun restoreWorkspaceFile(item: WorkspaceTrashItem) = launch {
        workspaceRepository.restoreFile(item.workspaceId, item.area, item.entry.path)
    }

    fun deleteWorkspaceFilePermanently(item: WorkspaceTrashItem) = launch {
        workspaceRepository.deleteTrashFile(item.workspaceId, item.area, item.entry.path)
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * 执行一个返回 Boolean 的回收站操作：返回 false 或抛异常都视为失败并提示用户。
     * DAO 的 restore/deleteById 返回 Unit（Room 无返回值），视为成功。
     */
    private fun launch(block: suspend () -> Boolean) {
        viewModelScope.launch {
            val ok = runCatching { block() }.getOrElse { e ->
                e.printStackTrace()
                _state.update { it.copy(error = e.message ?: "操作失败") }
                false
            }
            if (ok) {
                refresh()
            } else {
                _state.update { it.copy(error = "操作失败，请重试") }
            }
        }
    }
}

data class RecycleBinState(
    val loading: Boolean = true,
    val chatFiles: List<ManagedFileEntity> = emptyList(),
    val vocabularies: List<VocabularyEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val wrongQuestions: List<WrongQuestionEntity> = emptyList(),
    val knowledgeCards: List<KnowledgeCardEntity> = emptyList(),
    val workspaceFiles: List<WorkspaceTrashItem> = emptyList(),
    val error: String? = null,
)

data class WorkspaceTrashItem(
    val workspaceId: String,
    val workspaceName: String,
    val area: WorkspaceStorageArea,
    val entry: WorkspaceFileEntry,
)
