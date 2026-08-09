package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import me.rerere.rikkahub.data.sync.SyncItemStatus
import me.rerere.rikkahub.data.sync.SyncPreview
import me.rerere.rikkahub.data.sync.SyncProgress
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

    /** 手动同步前的差异预览（非 null 即显示确认弹窗），null 表示无弹窗 */
    val syncPreview = MutableStateFlow<SyncPreview?>(null)

    /** 同步执行进度（确认后弹窗从预览切换为进度视图），null 表示无进度弹窗 */
    val syncProgress = MutableStateFlow<SyncProgress?>(null)

    /** 当前同步协程，用于用户取消 */
    private var syncJob: Job? = null

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

    /** 手动同步：先只读检测本地与云端差异并弹出确认清单，用户确认后才执行（阶段一）。 */
    fun syncNow() {
        viewModelScope.launch {
            if (syncRunning.value) return@launch
            syncRunning.value = true
            syncMessage.value = null
            syncPreview.value = null
            runCatching { cloudSyncCoordinator.preview() }
                .onSuccess { preview ->
                    when {
                        preview == null -> syncMessage.value = "无法检测差异（未启用/离线/配置不完整）"
                        preview.isEmpty -> syncMessage.value = "无需同步，本地与云端一致"
                        else -> syncPreview.value = preview
                    }
                }
                .onFailure { e ->
                    syncMessage.value =
                        if (isThrottleError(e)) THROTTLE_MESSAGE else "检测差异失败: ${e.message}"
                }
            syncRunning.value = false
        }
    }

    /**
     * 用户在确认弹窗中点击"确认同步"后执行（阶段二，force=true 忽略最小间隔）。
     * 确认后弹窗不关闭，而是从差异预览切换为实时进度视图（[syncProgress]）。
     */
    fun confirmSync() {
        syncJob = viewModelScope.launch {
            // 用确认时的预览清单初始化进度项（全部为「等待中」）
            val preview = syncPreview.value
            syncPreview.value = null
            if (preview != null) {
                syncProgress.value = SyncProgress.fromPreview(preview)
            }

            if (syncRunning.value) return@launch
            syncRunning.value = true
            syncMessage.value = null
            runCatching {
                cloudSyncCoordinator.syncIfNeeded(
                    force = true,
                    onProgress = { relPath, status ->
                        syncProgress.value?.let { cur ->
                            val newMap = cur.statusByPath + (relPath to status)
                            syncProgress.value = cur.copy(
                                statusByPath = newMap,
                                done = newMap.values.count {
                                    it == SyncItemStatus.DONE || it == SyncItemStatus.FAILED
                                },
                            )
                        }
                    },
                )
            }.onSuccess { success ->
                syncProgress.value?.let { cur ->
                    syncProgress.value = cur.copy(
                        finished = true,
                        success = success,
                        error = if (success) null else "同步未完成（可能未触发，或同步执行失败）",
                    )
                }
                syncMessage.value =
                    if (success) "同步完成" else "同步未完成（可能未触发，或同步执行失败）"
            }.onFailure { e ->
                if (e is CancellationException) {
                    // 用户取消：进度视图显示「已取消」。注意不能重抛（否则跳过下方 syncRunning=false，
                    // 导致按钮永久显示"同步中"无法再触发），与侧边栏一致用 return@onFailure。
                    syncProgress.value?.let { cur ->
                        syncProgress.value = cur.copy(finished = true, success = null, error = "已取消")
                    }
                    return@onFailure
                }
                val errorMsg = if (isThrottleError(e)) THROTTLE_MESSAGE else "同步失败: ${e.message}"
                syncProgress.value?.let { cur ->
                    syncProgress.value = cur.copy(finished = true, success = false, error = errorMsg)
                }
                syncMessage.value = errorMsg
            }
            syncRunning.value = false
        }
    }

    /** 取消进行中的同步（取消传播到 SyncManager，正在上传/下载的项随之中断）。 */
    fun cancelSync() {
        syncJob?.cancel()
    }

    /** 关闭进度弹窗（进行中时收起后同步仍在后台继续，完成后可点击「完成」关闭）。 */
    fun dismissSyncProgress() {
        syncProgress.value = null
    }

    /** 关闭差异确认弹窗（不执行同步）。 */
    fun dismissSyncPreview() {
        syncPreview.value = null
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

    /** 是否服务端限流错误（WebDAV 429/503），提示用户稍后再试而非误报失败。 */
    private fun isThrottleError(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return msg.contains("Throttl", ignoreCase = true) ||
            msg.contains("429") || msg.contains("503")
    }

    companion object {
        private const val THROTTLE_MESSAGE = "同步请求太频繁，服务器限流中，请稍等几分钟再试"
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)
