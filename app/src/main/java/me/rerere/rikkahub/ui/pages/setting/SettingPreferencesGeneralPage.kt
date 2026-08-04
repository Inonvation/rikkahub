package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import me.rerere.rikkahub.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    SettingListScaffold(
        title = stringResource(R.string.setting_page_preferences_general),
        loading = settings.init,
    ) {
        // Conversation
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_page_conversation),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.createNewConversationOnStart,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(createNewConversationOnStart = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.showMessageJumper,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                            }
                        )
                    },
                )
                if (displaySetting.showMessageJumper) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.messageJumperOnLeft,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                }
                            )
                        },
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableAutoScroll,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
                            }
                        )
                    },
                )
            }
        }

        // Document processing
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_page_document_processing),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_pdf_ocr)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_pdf_ocr_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.pdfOcrEnabled,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(pdfOcrEnabled = it))
                            }
                        )
                    },
                )
            }
        }

        // Input
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_page_input),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.sendOnEnter,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(sendOnEnter = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.pasteLongTextAsFile,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
                            }
                        )
                    },
                )
                if (displaySetting.pasteLongTextAsFile) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = displaySetting.pasteLongTextThreshold.toFloat(),
                                    onValueChange = {
                                        updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                    },
                                    valueRange = 100f..10000f,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${displaySetting.pasteLongTextThreshold}")
                            }
                        },
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.skipCropImage,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableVolumeKeyScroll,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it))
                            }
                        )
                    },
                )
                if (displaySetting.enableVolumeKeyScroll) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = displaySetting.volumeKeyScrollRatio,
                                    onValueChange = {
                                        updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                    },
                                    valueRange = 0.25f..1.0f,
                                    steps = 2,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%")
                            }
                        }
                    )
                }
            }
        }

        // Haptic Feedback
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_display_page_enable_haptic_feedback_title),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_haptic_feedback_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_haptic_feedback_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableHapticFeedback,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableHapticFeedback = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_ui_haptic_feedback_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_ui_haptic_feedback_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableUiHapticFeedback,
                            enabled = displaySetting.enableHapticFeedback,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableUiHapticFeedback = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_started_and_finished_haptic_effect_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_started_and_finished_haptic_effect_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableMessageGenerationStartedAndFinishedHapticEffect,
                            enabled = displaySetting.enableHapticFeedback,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableMessageGenerationStartedAndFinishedHapticEffect = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableMessageGenerationHapticEffect,
                            enabled = displaySetting.enableHapticFeedback,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                            }
                        )
                    },
                )
            }
        }

        // Display
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_page_display),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title)) },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.useAppIconStyleLoadingIndicator,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(useAppIconStyleLoadingIndicator = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableBlurEffect,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableBlurEffect = it))
                            }
                        )
                    },
                )
            }
        }
    }
}
