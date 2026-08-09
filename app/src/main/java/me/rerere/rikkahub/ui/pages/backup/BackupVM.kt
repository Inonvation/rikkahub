package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.BackupPreviewAnalyzer
import me.rerere.rikkahub.data.sync.CloudSyncCoordinator
import me.rerere.rikkahub.data.sync.SyncConfig
import me.rerere.rikkahub.data.sync.SyncState
import me.rerere.rikkahub.data.sync.SyncStateStore
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val cloudSyncCoordinator: CloudSyncCoordinator,
    private val syncStateStore: SyncStateStore,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    /** 本地导入导出的大致进度提示（阶段文字），null 表示无进行中的操作 */
    val backupProgress = MutableStateFlow<String?>(null)

    /** 待导入备份的预览描述（在确认对话框中展示），null 表示无预览 */
    val backupPreview = MutableStateFlow<String?>(null)

    /** 云同步状态（来自 SyncStateStore，供 SyncTab 展示） */
    val syncState = syncStateStore.stateFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SyncState()
    )

    /** 云同步进行中标志（UI 按钮禁用/加载） */
    val syncRunning = MutableStateFlow(false)

    /** 云同步结果提示（成功/失败文案），null 表示无提示 */
    val syncMessage = MutableStateFlow<String?>(null)

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateSyncConfig(update: (SyncConfig) -> SyncConfig) {
        viewModelScope.launch {
            val current = settingsStore.settingsFlowRaw.first()
            settingsStore.update(current.copy(syncConfig = update(current.syncConfig)))
        }
    }

    /** 手动立即同步（force=true 忽略最小间隔）。 */
    fun syncNow() {
        viewModelScope.launch {
            if (syncRunning.value) return@launch
            syncRunning.value = true
            syncMessage.value = null
            runCatching {
                if (cloudSyncCoordinator.syncIfNeeded(force = true)) {
                    "同步完成"
                } else {
                    "未触发同步（未启用/离线/已在同步中）"
                }
            }.onSuccess { syncMessage.value = it }
                .onFailure { syncMessage.value = "同步失败: ${it.message}" }
            syncRunning.value = false
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        backupProgress.value = "正在准备备份…"
        webDavSync.backup(
            settings.value.webDavConfig,
            onProgress = { backupProgress.value = it }
        )
        backupProgress.value = "备份完成"
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(
            config = settings.value.webDavConfig,
            item = item,
            onProgress = { backupProgress.value = it }
        )
        backupProgress.value = "恢复完成"
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        backupProgress.value = "正在准备备份文件…"
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = WebDavConfig.BackupItem.entries),
            onProgress = { backupProgress.value = it }
        )
        backupProgress.value = "正在写入目标文件…"
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = WebDavConfig.BackupItem.entries),
            onProgress = { backupProgress.value = it }
        )
    }

    /** 解析待导入备份包的内容预览，结果写入 [backupPreview] */
    suspend fun analyzeBackupPreview(file: File, importType: String) {
        backupPreview.value = withContext(Dispatchers.IO) {
            BackupPreviewAnalyzer.analyze(file, importType)
        }
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult {
        var importedConversations = 0
        var skippedExistingConversations = 0
        // ChatboxImporter 内部是阻塞式 IO，切到 IO 线程避免卡主线程
        val result = withContext(Dispatchers.IO) {
            ChatboxImporter.importStreaming(
                file = file,
                assistantId = settings.value.assistantId,
                providers = settings.value.providers,
                onConversation = { conversation ->
                    backupProgress.value = "正在导入对话 (${importedConversations + skippedExistingConversations + 1})…"
                    if (conversationRepository.existsConversationById(conversation.id)) {
                        skippedExistingConversations++
                    } else {
                        conversationRepository.insertConversation(conversation)
                        importedConversations++
                    }
                }
            )
        }

        val targetAssistantId = settings.value.assistantId
        settingsStore.update(
            settings.value.copy(
                providers = result.providers + settings.value.providers,
                assistants = settings.value.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        )

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "drop ${result.skippedImageParts} images"
        )
        return ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
        )
    }

    suspend fun restoreFromCherryStudio(file: File) {
        backupProgress.value = "正在导入 Cherry Studio 配置…"
        // CherryStudioProviderImporter 内部是阻塞式 IO，切到 IO 线程避免卡主线程
        val importProviders = withContext(Dispatchers.IO) {
            CherryStudioProviderImporter.importProviders(file)
        }

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        // 只打数量，不打 provider 明细：ProviderSetting 是 data class，toString 会包含明文 apiKey
        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        backupProgress.value = "正在准备备份…"
        s3Sync.backupToS3(
            settings.value.s3Config,
            onProgress = { backupProgress.value = it }
        )
        backupProgress.value = "备份完成"
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(
            config = settings.value.s3Config,
            item = item,
            onProgress = { backupProgress.value = it }
        )
        backupProgress.value = "恢复完成"
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)
