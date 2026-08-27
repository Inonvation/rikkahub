package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 回复语气的预设风格，CUSTOM 时使用自定义描述 */
@Serializable
enum class ResponseTonePreset {
    @SerialName("follow_assistant")
    FOLLOW_ASSISTANT,

    @SerialName("concise")
    CONCISE,

    @SerialName("detailed")
    DETAILED,

    @SerialName("formal")
    FORMAL,

    @SerialName("casual")
    CASUAL,

    @SerialName("custom")
    CUSTOM,
}

/**
 * 用户基本资料（全局个人资料层）。
 *
 * 与「情景记忆」（MemoryEntity，模型自动沉淀）分层：这里是用户显式维护的稳定档案，
 * 以固定前缀注入 system prompt —— 内容只在设置变更时变化，对 provider 前缀缓存友好。
 * 称呼不在此处：复用 [DisplaySetting.userNickname]（{{user}}/{{nickname}} 的单一事实源）。
 */
@Serializable
data class UserProfileSetting(
    /** 全局注入总开关（助手级还有 useUserProfile 叠加控制） */
    val enabled: Boolean = false,
    val occupation: String = "",
    /** 语言偏好，如"中文""英文技术讨论" */
    val language: String = "",
    val tonePreset: ResponseTonePreset = ResponseTonePreset.FOLLOW_ASSISTANT,
    /** tonePreset == CUSTOM 时的具体语气要求 */
    val toneCustom: String = "",
    /** 其他希望模型了解的背景信息 */
    val additionalInfo: String = "",
) {
    /** 是否有任一实质内容可注入 */
    fun hasContent(): Boolean = occupation.isNotBlank() ||
        language.isNotBlank() ||
        (tonePreset != ResponseTonePreset.FOLLOW_ASSISTANT &&
            (tonePreset != ResponseTonePreset.CUSTOM || toneCustom.isNotBlank())) ||
        additionalInfo.isNotBlank()
}
