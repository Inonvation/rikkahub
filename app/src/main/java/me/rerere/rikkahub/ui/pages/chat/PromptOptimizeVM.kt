package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeDepth
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeScene
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeTone
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.UiState

class PromptOptimizeVM(
    private val chatService: ChatService,
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val uiState: StateFlow<UiState<String>> = _uiState

    private var currentJob: Job? = null

    internal fun optimize(
        scene: PromptOptimizeScene,
        tone: PromptOptimizeTone,
        depth: PromptOptimizeDepth,
        inputText: String,
        extraNote: String = "",
    ) {
        if (inputText.isBlank()) return
        currentJob?.cancel()
        _uiState.value = UiState.Loading
        currentJob = viewModelScope.launch {
            chatService.optimizePrompt(text = inputText, scene = scene, tone = tone, depth = depth, extraNote = extraNote)
                .onSuccess { _uiState.value = UiState.Success(it) }
                .onFailure { e ->
                    e.printStackTrace()
                    _uiState.value = UiState.Error(e)
                }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        _uiState.value = UiState.Idle
    }
}
