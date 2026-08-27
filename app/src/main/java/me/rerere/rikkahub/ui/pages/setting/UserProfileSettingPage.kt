package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.MAX_PROFILE_INFO_CHARS
import me.rerere.rikkahub.data.model.ResponseTonePreset
import me.rerere.rikkahub.data.model.UserProfileSetting
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import org.koin.androidx.compose.koinViewModel

/**
 * 个人资料设置页：用户基本信息 + 回复语气偏好的全局配置。
 *
 * 注入策略见 GenerationPrompts.buildUserProfilePrompt：作为 system 的稳定前缀段，
 * 只在设置变更时变化，对前缀缓存友好；助手级开关 useUserProfile 可单独关闭。
 *
 * 编辑采用本地草稿 [ProfileDraft] + 防抖提交：文本输入只改内存，静默期后经
 * SettingVM.updateUserProfile（基于最新 Settings 的 transform）整体落盘，
 * 避免逐键全量序列化 Settings 以及快照过期互相覆盖。
 */
private const val USER_PROFILE_COMMIT_DEBOUNCE_MS = 600L

/** 页面内所有可编辑字段的内存草稿；提交时整份写回 userProfile 与 userNickname */
private data class ProfileDraft(
    val enabled: Boolean,
    val nickname: String,
    val occupation: String,
    val language: String,
    val tonePreset: ResponseTonePreset,
    val toneCustom: String,
    val additionalInfo: String,
) {
    fun toSetting(): UserProfileSetting = UserProfileSetting(
        enabled = enabled,
        occupation = occupation,
        language = language,
        tonePreset = tonePreset,
        toneCustom = toneCustom,
        additionalInfo = additionalInfo,
    )

    companion object {
        fun from(profile: UserProfileSetting, nickname: String) = ProfileDraft(
            enabled = profile.enabled,
            nickname = nickname,
            occupation = profile.occupation,
            language = profile.language,
            tonePreset = profile.tonePreset,
            toneCustom = profile.toneCustom,
            additionalInfo = profile.additionalInfo,
        )
    }
}

@Composable
fun UserProfileSettingPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val displaySetting = settings.displaySetting
    val profile = settings.userProfile

    // key 取 !settings.init：加载完成瞬间用真实数据重建一次草稿；
    // 此后页面生命周期内不再重置，避免外部重组丢弃未提交输入。
    var draft by remember(!settings.init) {
        mutableStateOf(ProfileDraft.from(profile, displaySetting.userNickname))
    }
    // 最近一次确认与持久层一致的草稿快照，用于差量判定与外部变更跟随
    var synced by remember(!settings.init) {
        mutableStateOf(ProfileDraft.from(profile, displaySetting.userNickname))
    }

    // 外部修改（如聊天抽屉改昵称）到达且本地无未提交编辑时跟随显示，
    // 防止陈旧草稿在下次防抖提交时把他人修改回滚
    LaunchedEffect(settings) {
        val persisted = ProfileDraft.from(profile, displaySetting.userNickname)
        if (persisted != synced && draft == synced) {
            draft = persisted
            synced = persisted
        }
    }

    // 防抖提交：静默期后整体写回；期间继续输入会重启本 Effect 并取消旧延迟
    LaunchedEffect(draft) {
        if (draft == synced) return@LaunchedEffect
        delay(USER_PROFILE_COMMIT_DEBOUNCE_MS)
        vm.updateUserProfile(draft.toSetting(), draft.nickname)
        synced = draft
    }

    // 离开页面时立即冲刷未提交草稿（防抖协程随组合销毁取消，这里兜底防丢）
    DisposableEffect(Unit) {
        onDispose {
            val pending = draft
            if (!settings.init && pending != synced) {
                vm.updateUserProfile(pending.toSetting(), pending.nickname)
            }
        }
    }

    SettingListScaffold(
        title = stringResource(R.string.setting_user_profile_title),
        loading = settings.init,
    ) {
        // 开关
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_user_profile_title),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_user_profile_enable)) },
                    supportingContent = { Text(stringResource(R.string.setting_user_profile_enable_desc)) },
                    trailingContent = {
                        Switch(
                            checked = draft.enabled,
                            onCheckedChange = { value -> draft = draft.copy(enabled = value) }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_nickname)) },
                    supportingContent = { Text(stringResource(R.string.setting_user_profile_nickname_desc)) },
                    trailingContent = {},
                )
                item {
                    OutlinedTextField(
                        value = draft.nickname,
                        onValueChange = { value -> draft = draft.copy(nickname = value) },
                        placeholder = { Text(stringResource(R.string.setting_user_profile_nickname_placeholder)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // 基本信息
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_user_profile_basic_info),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = draft.occupation,
                            onValueChange = { value -> draft = draft.copy(occupation = value) },
                            label = { Text(stringResource(R.string.setting_user_profile_occupation)) },
                            placeholder = { Text(stringResource(R.string.setting_user_profile_occupation_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draft.language,
                            onValueChange = { value -> draft = draft.copy(language = value) },
                            label = { Text(stringResource(R.string.setting_user_profile_language)) },
                            placeholder = { Text(stringResource(R.string.setting_user_profile_language_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draft.additionalInfo,
                            onValueChange = { value ->
                                // 输入端截断到注入层上限，粘贴超长内容也不会被静默丢失
                                draft = draft.copy(additionalInfo = value.take(MAX_PROFILE_INFO_CHARS))
                            },
                            label = { Text(stringResource(R.string.setting_user_profile_additional)) },
                            placeholder = { Text(stringResource(R.string.setting_user_profile_additional_hint)) },
                            minLines = 2,
                            maxLines = 6,
                            supportingText = {
                                val atLimit = draft.additionalInfo.length >= MAX_PROFILE_INFO_CHARS
                                Text(
                                    text = "${draft.additionalInfo.length}/$MAX_PROFILE_INFO_CHARS",
                                    color = if (atLimit) MaterialTheme.colorScheme.error else Color.Unspecified,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // 回复语气
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_user_profile_tone),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_user_profile_tone_preset)) },
                    supportingContent = { Text(stringResource(R.string.setting_user_profile_tone_preset_desc)) },
                )
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Select(
                            options = ResponseTonePreset.entries.toList(),
                            selectedOption = draft.tonePreset,
                            onOptionSelected = { value -> draft = draft.copy(tonePreset = value) },
                            optionToString = { tonePresetLabel(it) },
                        )
                        if (draft.tonePreset == ResponseTonePreset.CUSTOM) {
                            OutlinedTextField(
                                value = draft.toneCustom,
                                onValueChange = { value -> draft = draft.copy(toneCustom = value) },
                                label = { Text(stringResource(R.string.setting_user_profile_tone_custom)) },
                                placeholder = { Text(stringResource(R.string.setting_user_profile_tone_custom_hint)) },
                                minLines = 2,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun tonePresetLabel(preset: ResponseTonePreset): String = stringResource(
    when (preset) {
        ResponseTonePreset.FOLLOW_ASSISTANT -> R.string.tone_follow_assistant
        ResponseTonePreset.CONCISE -> R.string.tone_concise
        ResponseTonePreset.DETAILED -> R.string.tone_detailed
        ResponseTonePreset.FORMAL -> R.string.tone_formal
        ResponseTonePreset.CASUAL -> R.string.tone_casual
        ResponseTonePreset.CUSTOM -> R.string.tone_custom
    }
)
