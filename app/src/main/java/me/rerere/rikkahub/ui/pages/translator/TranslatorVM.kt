package me.rerere.rikkahub.ui.pages.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.TranslationHistoryDao
import me.rerere.rikkahub.data.db.entity.TranslationRecordEntity
import java.util.Locale
import java.util.UUID

private const val TAG = "TranslatorVM"

/**
 * 历史记录上限：超过后裁剪最老记录。
 * 翻译历史只是低价值的 UI 记录（无外键引用），无界增长没有收益，限定条数后 DB 不再膨胀。
 */
private const val HISTORY_LIMIT = 200

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val translationHistoryDao: TranslationHistoryDao,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    // 翻译状态
    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating

    // 输入文本
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    // 翻译结果
    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText

    // 翻译目标语言
    private val _targetLanguage = MutableStateFlow(Locale.SIMPLIFIED_CHINESE)
    val targetLanguage: StateFlow<Locale> = _targetLanguage

    // 源语言，null 表示自动检测
    private val _sourceLanguage = MutableStateFlow<Locale?>(null)
    val sourceLanguage: StateFlow<Locale?> = _sourceLanguage

    // 翻译历史记录（新的在前）
    val records: StateFlow<List<TranslationRecordEntity>> = translationHistoryDao.listAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 错误流
    val errorFlow = MutableSharedFlow<Throwable>()

    // 当前任务
    private var currentJob: Job? = null

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateInputText(text: String) {
        if (_inputText.value == text) return
        currentJob?.cancel()
        _translating.value = false
        _inputText.value = text
        _translatedText.value = ""
    }

    fun updateTargetLanguage(language: Locale) {
        _targetLanguage.value = language
    }

    fun updateSourceLanguage(language: Locale?) {
        _sourceLanguage.value = language
    }

    fun swapLanguages() {
        val source = _sourceLanguage.value
        val target = _targetLanguage.value
        _sourceLanguage.value = target
        _targetLanguage.value = if (source != null) {
            source
        } else if (target.language == "zh") {
            Locale.ENGLISH
        } else {
            Locale.getDefault()
        }
    }

    fun clearInput() {
        updateInputText("")
    }

    fun translate() {
        val sourceText = _inputText.value
        if (sourceText.isBlank()) return

        // 取消当前任务
        currentJob?.cancel()

        // 设置翻译中状态
        _translating.value = true
        _translatedText.value = ""

        currentJob = viewModelScope.launch {
            var completed = false
            runCatching {
                generationHandler.translateText(
                    settings = settings.value,
                    sourceText = sourceText,
                    targetLanguage = targetLanguage.value
                ) { translatedText ->
                    // Update translation in real-time
                    _translatedText.value = translatedText.trim()
                }.collect { /* Final translation already handled in onStreamUpdate */ }
                completed = _translatedText.value.isNotBlank()
            }.onFailure {
                it.printStackTrace()
                errorFlow.emit(it)
            }

            _translating.value = false

            // 只把真正出结果的翻译写入历史；流中途失败/被取消时 _translatedText 为空或为残留文本。
            // 这里用完成后快照而非流回调里的临时值，保证入库文本与用户最终看到的一致。
            if (completed) {
                saveRecord(sourceText, _translatedText.value)
            }
        }
    }

    fun cancelTranslation() {
        currentJob?.cancel()
        _translating.value = false
    }

    // ---------- 历史记录 ----------

    /**
     * 把某条历史回填到当前翻译界面（源文、结果、语言对）。
     * 页面从历史模式切回翻译模式后直接看到内容，可继续复制/修改/重新翻译。
     */
    fun restoreRecord(recordId: String) {
        viewModelScope.launch {
            translationHistoryDao.getById(recordId)?.let { record ->
                currentJob?.cancel()
                _translating.value = false
                _inputText.value = record.sourceText
                _translatedText.value = record.translatedText
                _sourceLanguage.value = record.sourceLanguage?.let { Locale.forLanguageTag(it) }
                _targetLanguage.value = Locale.forLanguageTag(record.targetLanguage)
            }
        }
    }

    fun deleteRecords(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            translationHistoryDao.deleteByIds(ids)
        }
    }

    fun deleteAllRecords() {
        viewModelScope.launch {
            translationHistoryDao.deleteAll()
        }
    }

    /**
     * 单条滑动删除后的撤销：按原 id 原样插回。
     * createdAt 保持不变，重排后仍回到原时间位置；裁剪逻辑照常兜底。
     */
    fun undeleteRecord(record: TranslationRecordEntity) {
        viewModelScope.launch {
            translationHistoryDao.insertAndTrim(record, HISTORY_LIMIT)
        }
    }

    private fun saveRecord(sourceText: String, translatedText: String) {
        if (sourceText.isBlank() || translatedText.isBlank()) return
        viewModelScope.launch {
            translationHistoryDao.insertAndTrim(
                record = TranslationRecordEntity(
                    id = UUID.randomUUID().toString(),
                    sourceText = sourceText,
                    translatedText = translatedText,
                    sourceLanguage = _sourceLanguage.value?.toLanguageTag(),
                    targetLanguage = _targetLanguage.value.toLanguageTag(),
                    createdAt = System.currentTimeMillis(),
                ),
                keep = HISTORY_LIMIT
            )
        }
    }
}
