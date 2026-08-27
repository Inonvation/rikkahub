package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
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
 */
@Composable
fun UserProfileSettingPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val displaySetting = settings.displaySetting
    val profile = settings.userProfile

    fun updateProfile(block: (UserProfileSetting) -> UserProfileSetting) {
        vm.updateSettings(settings.copy(userProfile = block(profile)))
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        vm.updateSettings(settings.copy(displaySetting = setting))
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
                            checked = profile.enabled,
                            onCheckedChange = { value -> updateProfile { p -> p.copy(enabled = value) } }
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
                        value = displaySetting.userNickname,
                        onValueChange = {
                            updateDisplaySetting(displaySetting.copy(userNickname = it))
                        },
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
                            value = profile.occupation,
                            onValueChange = { value -> updateProfile { p -> p.copy(occupation = value) } },
                            label = { Text(stringResource(R.string.setting_user_profile_occupation)) },
                            placeholder = { Text(stringResource(R.string.setting_user_profile_occupation_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = profile.language,
                            onValueChange = { value -> updateProfile { p -> p.copy(language = value) } },
                            label = { Text(stringResource(R.string.setting_user_profile_language)) },
                            placeholder = { Text(stringResource(R.string.setting_user_profile_language_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = profile.additionalInfo,
                            onValueChange = { value -> updateProfile { p -> p.copy(additionalInfo = value) } },
                            label = { Text(stringResource(R.string.setting_user_profile_additional)) },
                            placeholder = { Text(stringResource(R.string.setting_user_profile_additional_hint)) },
                            minLines = 2,
                            maxLines = 6,
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
                            selectedOption = profile.tonePreset,
                            onOptionSelected = { value ->
                                updateProfile { it.copy(tonePreset = value) }
                            },
                            optionToString = { tonePresetLabel(it) },
                        )
                        if (profile.tonePreset == ResponseTonePreset.CUSTOM) {
                            OutlinedTextField(
                                value = profile.toneCustom,
                                onValueChange = { value -> updateProfile { p -> p.copy(toneCustom = value) } },
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
