package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.controller.TtsController
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "TTS"

/**
 * 根据当前 TTS provider 返回适合朗读英文单词的音色（voice）覆盖值。
 * 为空表示无需覆盖（该 provider 默认音色即可读英文，如 OpenAI alloy）。
 */
fun englishWordVoiceFor(provider: TTSProviderSetting?): String? = when (provider) {
    // OpenAI alloy 是英文音色，读单词强制用它（不受用户改过音色影响）
    is TTSProviderSetting.OpenAI -> "alloy"
    is TTSProviderSetting.Gemini -> "Kore" // Gemini 内置英文 voice
    // qwen-audio-3.0 系列为多语言模型，已无独立英文音色；不覆盖，用配置音色即可读英文
    is TTSProviderSetting.Qwen -> null
    is TTSProviderSetting.MiniMax -> "male-qn-qingse" // MiniMax 英文音色
    is TTSProviderSetting.Groq -> provider.voice.ifBlank { null } // 本来就是英文语音
    is TTSProviderSetting.XAI -> provider.voiceId.ifBlank { null } // 英文语音
    is TTSProviderSetting.MiMo -> provider.voice.ifBlank { null }
    is TTSProviderSetting.Step -> provider.voice.ifBlank { null }
    is TTSProviderSetting.ElevenLabs -> provider.voiceId.ifBlank { null }
    is TTSProviderSetting.FishAudio -> provider.referenceId.ifBlank { null }
    is TTSProviderSetting.SystemTTS -> null // SystemTTS 用语言引擎，不走 voice 字段
    null -> null
}

/**
 * Composable function to remember and manage custom TTS state.
 * Uses user-configured TTS providers instead of system TTS.
 */
@Composable
fun rememberCustomTtsState(): CustomTtsState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    // Remember the CustomTtsState instance across recompositions
    val ttsState = remember {
        CustomTtsStateImpl(
            context = context.applicationContext,
            settingsStore = settingsStore
        )
    }

    // Update the provider when settings change
    DisposableEffect(
        settings.selectedTTSProviderId,
        settings.ttsProviders,
        settings.defaultTTSPlaybackSpeed,
    ) {
        ttsState.updateProvider(settings.getSelectedTTSProvider())
        ttsState.setSpeed(settings.defaultTTSPlaybackSpeed)
        onDispose { }
    }

    // Cleanup resources when the state is disposed
    DisposableEffect(ttsState) {
        onDispose {
            ttsState.cleanup()
        }
    }

    return ttsState
}

/**
 * Interface defining the public API of our custom TTS state holder.
 */
interface CustomTtsState {
    /** Flow indicating if the TTS provider is available and ready. */
    val isAvailable: StateFlow<Boolean>

    /** Flow indicating if the TTS is currently speaking. */
    val isSpeaking: StateFlow<Boolean>

    /** Flow holding any error message. */
    val error: StateFlow<String?>

    /** Flow indicating current chunk being processed (index) */
    val currentChunk: StateFlow<Int>

    /** Flow indicating total chunks in queue */
    val totalChunks: StateFlow<Int>

    /** Unified playback state (status, position, duration, speed, etc.) */
    val playbackState: StateFlow<PlaybackState>

    /**
     * Speaks the given text using the selected TTS provider.
     * Long texts will be automatically chunked and queued.
     *
     * @param voiceOverride 发音人（voice）覆盖；为空时用 provider 自己配置的音色。
     *                      用于学习面板单词朗读强制英文音色，保证英文发音标准稳定。
     * @param pronunciation IPA 音标提示（如 /riːd/）；非空时传给支持发音指令的 provider。
     */
    fun speak(text: String, flushCalled: Boolean = true, voiceOverride: String? = null, pronunciation: String? = null)

    /**
     * 朗读英文单词：自动按当前 provider 选择英文音色，保证单词发音标准、稳定。
     *
     * @param pronunciation 单词的 IPA 音标（如 /riːd/），非空时传给 TTS 提示按音标发音，
     *                      提升多音字/生僻词的发音准确性。
     */
    fun speakWord(text: String, pronunciation: String? = null)

    /**
     * 朗读英文单词并附带语境（释义/例句）。
     * 孤立单词发音不稳定，把单词放进完整英文语境里，TTS 能按语境判定多音字读音。
     */
    fun speakWordWithContext(text: String, pronunciation: String? = null, contextText: String? = null)

    /** Stops the current speech and clears the queue. */
    fun stop()

    /** Pauses the current playback. */
    fun pause()

    /** Resumes the paused playback. */
    fun resume()

    /** Skips to the next chunk in the queue. */
    fun skipNext()

    /** Fast forward current playback by [ms]. */
    fun fastForward(ms: Long = 5_000)

    /** Set playback [speed]. */
    fun setSpeed(speed: Float)

    /** Cleanup resources. */
    fun cleanup()

    /** 清空磁盘发音缓存（下次朗读会重新合成）。 */
    fun clearDiskCache()
}

/**
 * Internal implementation of CustomTtsState.
 */
private class CustomTtsStateImpl(
    private val context: Context,
    private val settingsStore: SettingsStore
) : CustomTtsState, KoinComponent {

    private val ttsManager by inject<TTSManager>()
    private val controller by lazy { me.rerere.tts.controller.TtsController(context, ttsManager) }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentJob: Job? = null

    override val isAvailable: StateFlow<Boolean> get() = controller.isAvailable
    override val isSpeaking: StateFlow<Boolean> get() = controller.isSpeaking
    override val error: StateFlow<String?> get() = controller.error
    override val currentChunk: StateFlow<Int> get() = controller.currentChunk
    override val totalChunks: StateFlow<Int> get() = controller.totalChunks
    override val playbackState: StateFlow<PlaybackState> get() = controller.playbackState

    fun updateProvider(provider: TTSProviderSetting?) {
        controller.setProvider(provider)
    }

    override fun speak(text: String, flushCalled: Boolean, voiceOverride: String?, pronunciation: String?) {
        val processed = text.stripMarkdown()
        controller.speak(processed, flushCalled, voiceOverride, pronunciation)
    }

    override fun speakWord(text: String, pronunciation: String?) {
        val provider = settingsStore.settingsFlow.value.getSelectedTTSProvider()
        val voice = englishWordVoiceFor(provider)
        val hint = pronunciation?.takeIf { it.isNotBlank() }
        speak(text, flushCalled = true, voiceOverride = voice, pronunciation = hint)
    }

    override fun speakWordWithContext(text: String, pronunciation: String?, contextText: String?) {
        val provider = settingsStore.settingsFlow.value.getSelectedTTSProvider()
        val voice = englishWordVoiceFor(provider)
        val hint = pronunciation?.takeIf { it.isNotBlank() }
        val payload = buildString {
            append(text)
            // 追加英文语境，让 TTS 按上下文判定读音；只取英文部分，中文释义不参与发音
            if (contextText != null && contextText.isNotBlank()) {
                append(". ")
                append(contextText)
            }
        }
        speak(payload, flushCalled = true, voiceOverride = voice, pronunciation = hint)
    }

    override fun stop() {
        controller.stop()
    }

    override fun pause() {
        controller.pause()
        Log.d("CustomTtsState", "TTS paused")
    }

    override fun resume() {
        controller.resume()
        Log.d("CustomTtsState", "TTS resumed")
    }

    override fun skipNext() {
        controller.skipNext()
    }

    override fun fastForward(ms: Long) {
        controller.fastForward(ms)
    }

    override fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
    }

    override fun cleanup() {
        controller.dispose()
        currentJob = null
    }

    override fun clearDiskCache() {
        controller.clearDiskCache()
    }
}
