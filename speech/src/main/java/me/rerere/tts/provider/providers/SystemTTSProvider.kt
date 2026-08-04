package me.rerere.tts.provider.providers

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.common.android.appTempFolder
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SystemTTSProvider"

/** 判断文本是否以英文为主（用于选择朗读语言引擎）。 */
internal fun isMostlyEnglish(text: String): Boolean {
    if (text.isBlank()) return false
    // CJK 统一表意文字 + 日文假名
    val cjk = text.count {
        (it.code in 0x4E00..0x9FFF) || (it.code in 0x3040..0x30FF) || (it.code in 0x3400..0x4DBF)
    }
    val asciiLetters = text.count { it.isLetter() && it.code < 0x80 }
    return asciiLetters > cjk && asciiLetters >= 3
}

class SystemTTSProvider : TTSProvider<TTSProviderSetting.SystemTTS> {
    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.SystemTTS,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val audioData = suspendCancellableCoroutine<ByteArray> { continuation ->
            var tts: TextToSpeech? = null
            val listener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val ttsInstance = tts ?: error("TextToSpeech instance is null")

                // Set language
                // 文本以英文为主时优先用英文引擎，避免中文设备用默认 locale 读英文带口音
                val locale = if (isMostlyEnglish(request.text)) Locale.US else Locale.getDefault()
                val langResult = ttsInstance.setLanguage(locale)

                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "generateSpeech: Language $locale not supported")
                }

                // Set speech parameters
                ttsInstance.setSpeechRate(providerSetting.speechRate)
                ttsInstance.setPitch(providerSetting.pitch)

                // Create temporary file for audio output using temp directory like RikkaHubApp
                val tempDir = context.appTempFolder
                val audioFile = File(tempDir, "tts_${System.currentTimeMillis()}.wav")

                val utteranceId = UUID.randomUUID().toString()

                ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(TAG, "onStart: TTS engine started!")
                    }

                    override fun onDone(utteranceId: String?) {
                        try {
                            if (audioFile.exists()) {
                                val audioData = audioFile.readBytes()
                                audioFile.delete()

                                if (continuation.isActive) continuation.resume(audioData)
                            } else {
                                if (continuation.isActive) continuation.resumeWithException(
                                    Exception("Failed to generate audio file")
                                )
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        } finally {
                            ttsInstance.shutdown()
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "onError: TTS synthesis failed!")
                        audioFile.delete()
                        if (continuation.isActive) continuation.resumeWithException(
                            Exception("TTS synthesis failed")
                        )
                        ttsInstance.shutdown()
                    }
                })

                val result = ttsInstance.synthesizeToFile(
                    request.text,
                    null,
                    audioFile,
                    utteranceId
                )

                if (result != TextToSpeech.SUCCESS) {
                    if (continuation.isActive) continuation.resumeWithException(
                        Exception("Failed to start TTS synthesis")
                    )
                    ttsInstance.shutdown()
                }

            } else {
                if (continuation.isActive) continuation.resumeWithException(
                    Exception("Failed to initialize TextToSpeech engine")
                )
            }
        }
        tts = TextToSpeech(context, listener)

        continuation.invokeOnCancellation {
            tts?.shutdown()
        }
    }

        emit(
            AudioChunk(
                data = audioData,
                format = me.rerere.tts.model.AudioFormat.WAV,
                isLast = true,
                metadata = mapOf(
                    "provider" to "system",
                    "speechRate" to providerSetting.speechRate.toString(),
                    "pitch" to providerSetting.pitch.toString()
                )
            )
        )
    }
}
