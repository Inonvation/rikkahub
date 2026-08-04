package me.rerere.tts.model

import kotlinx.serialization.Serializable

@Serializable
data class TTSRequest(
    val text: String,
    /** 发音人（voice）覆盖。为空时由各 provider 回退到自己的 providerSetting 配置。 */
    val voice: String? = null,
    /** IPA 音标提示（如 /riːd/）。非空时由支持发音指令的 provider 用于提升发音准确性。 */
    val pronunciation: String? = null,
)

@Serializable
enum class AudioFormat {
    MP3,
    WAV,
    OGG,
    AAC,
    OPUS,
    PCM
}