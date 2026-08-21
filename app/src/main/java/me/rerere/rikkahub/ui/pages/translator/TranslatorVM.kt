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
import java.util.Locale

private const val TAG = "TranslatorVM"

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
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
        val inputText = _inputText.value
        if (inputText.isBlank()) return

        // 取消当前任务
        currentJob?.cancel()

        // 设置翻译中状态
        _translating.value = true
        _translatedText.value = ""

        currentJob = viewModelScope.launch {
            runCatching {
                generationHandler.translateText(
                    settings = settings.value,
                    sourceText = inputText,
                    targetLanguage = targetLanguage.value
                ) { translatedText ->
                    // Update translation in real-time
                    _translatedText.value = translatedText.trim()
                }.collect { /* Final translation already handled in onStreamUpdate */ }
            }.onFailure {
                it.printStackTrace()
                errorFlow.emit(it)
            }

            _translating.value = false
        }
    }

    fun cancelTranslation() {
        currentJob?.cancel()
        _translating.value = false
    }
}
