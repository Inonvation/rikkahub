package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.IosGroupScope
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import org.koin.androidx.compose.koinViewModel
import java.io.File
import kotlin.math.roundToInt

/**
 * 界面偏好设置分组子页：接收 group 参数，渲染对应分组的设置项。
 * group 取值：layout / bubbles / info / thinking / font / render / code / tts / haptic
 */
@Composable
fun SettingDisplayGroupPage(group: String, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var ttsPlaybackSpeed by remember(settings) {
        mutableFloatStateOf(settings.defaultTTSPlaybackSpeed)
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    fun commitTtsPlaybackSpeed() {
        if (ttsPlaybackSpeed != settings.defaultTTSPlaybackSpeed) {
            vm.updateSettings(settings.copy(defaultTTSPlaybackSpeed = ttsPlaybackSpeed))
        }
    }

    val title = stringResource(
        when (group) {
            "layout" -> R.string.setting_page_layout
            "bubbles" -> R.string.setting_page_message_bubbles
            "info" -> R.string.setting_page_message_info
            "thinking" -> R.string.setting_page_thinking
            "font" -> R.string.setting_page_font
            "render" -> R.string.setting_page_rendering
            "code" -> R.string.setting_page_code_display_settings
            "tts" -> R.string.setting_page_tts_playback
            "haptic" -> R.string.setting_display_page_haptic_group_title
            else -> R.string.setting_page_preferences_ui
        }
    )

    // 字体组依赖（仅 group == "font" 时使用，提前创建以保持 IosGroup DSL 上下文纯净）
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    val importSuccessMsg = stringResource(R.string.setting_display_page_custom_font_import_success)
    val importFailedMsg = stringResource(R.string.setting_display_page_custom_font_import_failed)
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri)
                }
            }.onSuccess { importedFont ->
                updateDisplaySetting(
                    displaySetting.copy(
                        chatFontFamily = ChatFontFamily.CUSTOM,
                        chatCustomFontPath = importedFont.relativePath,
                        chatCustomFontName = importedFont.displayName,
                    )
                )
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    SettingListScaffold(
        title = title,
        loading = settings.init,
    ) {
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                when (group) {
                    "layout" -> LayoutGroupItems(displaySetting, ::updateDisplaySetting)
                    "bubbles" -> BubblesGroupItems(displaySetting, ::updateDisplaySetting)
                    "info" -> InfoGroupItems(displaySetting, ::updateDisplaySetting)
                    "thinking" -> ThinkingGroupItems(displaySetting, ::updateDisplaySetting)
                    "font" -> FontGroupItems(
                        displaySetting = displaySetting,
                        updateDisplaySetting = ::updateDisplaySetting,
                        fontPickerLauncher = fontPickerLauncher,
                        context = context,
                        toaster = toaster,
                        scope = scope,
                        chatFontFamily = chatFontFamily,
                        importSuccessMsg = importSuccessMsg,
                        importFailedMsg = importFailedMsg,
                    )
                    "render" -> RenderGroupItems(displaySetting, ::updateDisplaySetting)
                    "code" -> CodeGroupItems(displaySetting, ::updateDisplaySetting)
                    "tts" -> TtsGroupItems(
                        displaySetting = displaySetting,
                        updateDisplaySetting = ::updateDisplaySetting,
                        ttsPlaybackSpeed = ttsPlaybackSpeed,
                        onTtsPlaybackSpeedChange = { ttsPlaybackSpeed = (it * 10).roundToInt() / 10f },
                        onTtsPlaybackSpeedCommit = ::commitTtsPlaybackSpeed,
                    )
                    "haptic" -> HapticGroupItems(displaySetting, ::updateDisplaySetting)
                }
            }
        }
    }
}

private fun IosGroupScope.LayoutGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
) {
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_tablet_adaptation_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_tablet_adaptation_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.enableTabletAdaptation,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(enableTabletAdaptation = it))
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

private fun IosGroupScope.BubblesGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
) {
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.showUserAvatar,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(showUserAvatar = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.showAssistantBubble,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(showAssistantBubble = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_bubble_opacity_title)) },
        supportingContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Slider(
                    value = displaySetting.bubbleOpacity,
                    onValueChange = {
                        updateDisplaySetting(displaySetting.copy(bubbleOpacity = it))
                    },
                    valueRange = 0.1f..1.0f,
                    steps = 8,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "${(displaySetting.bubbleOpacity * 100).roundToInt()}%")
            }
        }
    )
}

private fun IosGroupScope.InfoGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
) {
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.showModelIcon,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(showModelIcon = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.showModelName,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(showModelName = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.showDateTimeInMessage,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(showDateTimeInMessage = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.showTokenUsage,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(showTokenUsage = it))
                }
            )
        },
    )
}

private fun IosGroupScope.ThinkingGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
) {
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.showThinkingContent,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(showThinkingContent = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.autoCloseThinking,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(autoCloseThinking = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_all_steps_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_all_steps_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.autoCollapseAllSteps,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(autoCollapseAllSteps = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_thinking_frozen_bar_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_thinking_frozen_bar_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.thinkingFrozenBar,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(thinkingFrozenBar = it))
                }
            )
        },
    )
}

private fun IosGroupScope.FontGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
    fontPickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    context: Context,
    toaster: com.dokar.sonner.ToasterState,
    scope: kotlinx.coroutines.CoroutineScope,
    chatFontFamily: FontFamily,
    importSuccessMsg: String,
    importFailedMsg: String,
) {
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
        supportingContent = {
            Select(
                options = ChatFontFamily.entries,
                selectedOption = displaySetting.chatFontFamily,
                onOptionSelected = { family ->
                    if (family == ChatFontFamily.CUSTOM && displaySetting.chatCustomFontPath.isBlank()) {
                        fontPickerLauncher.launch(CustomFontMimeTypesUI)
                    } else {
                        updateDisplaySetting(displaySetting.copy(chatFontFamily = family))
                    }
                },
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(),
                optionToString = { it.labelUI() },
                optionLeading = { family ->
                    Text(
                        text = "Aa",
                        fontFamily = family.toFontFamilyUI(chatFontFamily),
                    )
                }
            )
        }
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_custom_font_title)) },
        supportingContent = {
            Text(
                if (displaySetting.chatCustomFontName.isNotBlank()) {
                    displaySetting.chatCustomFontName
                } else {
                    stringResource(R.string.setting_display_page_custom_font_not_imported)
                }
            )
        },
        trailingContent = {
            Row {
                IconButton(
                    onClick = { fontPickerLauncher.launch(CustomFontMimeTypesUI) }
                ) {
                    Icon(
                        HugeIcons.FileImport,
                        contentDescription = stringResource(
                            R.string.setting_display_page_custom_font_import
                        )
                    )
                }
                if (displaySetting.chatCustomFontPath.isNotBlank()) {
                    IconButton(
                        onClick = {
                            deleteCustomChatFontInternal(context, displaySetting.chatCustomFontPath)
                            updateDisplaySetting(
                                displaySetting.copy(
                                    chatFontFamily = ChatFontFamily.DEFAULT,
                                    chatCustomFontPath = "",
                                    chatCustomFontName = "",
                                )
                            )
                        }
                    ) {
                        Icon(
                            HugeIcons.Delete02,
                            contentDescription = stringResource(
                                R.string.setting_display_page_custom_font_remove
                            )
                        )
                    }
                }
            }
        }
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
        supportingContent = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Slider(
                        value = displaySetting.fontSizeRatio,
                        onValueChange = {
                            updateDisplaySetting(displaySetting.copy(fontSizeRatio = it))
                        },
                        valueRange = 0.5f..2f,
                        steps = 11,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = "${(displaySetting.fontSizeRatio * 100).toInt()}%")
                }
                MarkdownBlock(
                    content = stringResource(R.string.setting_display_page_font_size_preview),
                    style = LocalTextStyle.current.copy(
                        fontSize = LocalTextStyle.current.fontSize * displaySetting.fontSizeRatio,
                        lineHeight = LocalTextStyle.current.lineHeight * displaySetting.fontSizeRatio,
                        fontFamily = chatFontFamily
                    )
                )
            }
        }
    )
}

private fun IosGroupScope.RenderGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
) {
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.enableLatexRendering,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(enableLatexRendering = it))
                }
            )
        },
    )
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

private fun IosGroupScope.CodeGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
) {
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.codeBlockAutoWrap,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(codeBlockAutoWrap = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.codeBlockAutoCollapse,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(codeBlockAutoCollapse = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.showLineNumbers,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(showLineNumbers = it))
                }
            )
        },
    )
}

private fun IosGroupScope.TtsGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
    ttsPlaybackSpeed: Float,
    onTtsPlaybackSpeedChange: (Float) -> Unit,
    onTtsPlaybackSpeedCommit: () -> Unit,
) {
    item(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_default_playback_speed)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.setting_tts_page_default_playback_speed_description))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Slider(
                        value = ttsPlaybackSpeed,
                        onValueChange = onTtsPlaybackSpeedChange,
                        onValueChangeFinished = onTtsPlaybackSpeedCommit,
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "x${"%.1f".format(ttsPlaybackSpeed)}")
                }
            }
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.ttsOnlyReadQuoted,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.ttsOnlyReadOutsideBrackets,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadOutsideBrackets = it))
                }
            )
        },
    )
    item(
        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
        trailingContent = {
            Switch(
                checked = displaySetting.autoPlayTTSAfterGeneration,
                onCheckedChange = {
                    updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it))
                }
            )
        },
    )
}

private fun IosGroupScope.HapticGroupItems(
    displaySetting: DisplaySetting,
    updateDisplaySetting: (DisplaySetting) -> Unit,
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
}

private val CustomFontMimeTypesUI = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

private val CustomFontExtensionsUI = setOf("ttf", "otf", "ttc")

private data class ImportedChatFontUI(
    val relativePath: String,
    val displayName: String,
)

@Composable
private fun ChatFontFamily.labelUI(): String = when (this) {
    ChatFontFamily.DEFAULT -> stringResource(R.string.setting_display_page_chat_font_family_default)
    ChatFontFamily.SERIF -> stringResource(R.string.setting_display_page_chat_font_family_serif)
    ChatFontFamily.MONOSPACE -> stringResource(R.string.setting_display_page_chat_font_family_monospace)
    ChatFontFamily.CUSTOM -> stringResource(R.string.setting_display_page_chat_font_family_custom)
}

private fun ChatFontFamily.toFontFamilyUI(customFontFamily: FontFamily): FontFamily = when (this) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> customFontFamily
}

private fun importCustomChatFontInternal(context: Context, uri: Uri): ImportedChatFontUI {
    val displayName = FileUtils.getFileNameFromUri(context, uri)?.takeIf { it.isNotBlank() } ?: "custom_font"
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in CustomFontExtensionsUI }
        ?: "ttf"
    val fontDir = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val targetFile = File(fontDir, "chat_font.${System.currentTimeMillis()}.$extension")
    val tempFile = File(fontDir, "chat_font_import.tmp")

    try {
        tempFile.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected font")

        runCatching {
            Typeface.createFromFile(tempFile)
        }.onFailure { error ->
            throw IllegalArgumentException(error.message ?: "Invalid font file", error)
        }

        replaceCustomChatFontInternal(fontDir, tempFile, targetFile)
    } catch (error: Throwable) {
        tempFile.delete()
        throw error
    }

    val relativePath = FileUtils.getRelativePathInFilesDir(context.filesDir, targetFile)
        ?: "${FileFolders.FONTS}/${targetFile.name}"
    return ImportedChatFontUI(relativePath = relativePath, displayName = displayName)
}

private fun replaceCustomChatFontInternal(fontDir: File, tempFile: File, targetFile: File) {
    val existingFiles = fontDir.listFiles { file ->
        file.isFile && file.name.startsWith("chat_font.") && file != tempFile
    }?.toList().orEmpty()
    val backups = existingFiles.map { file ->
        file to File(fontDir, "previous_${file.name}").also { it.delete() }
    }

    try {
        backups.forEach { (file, backup) ->
            check(file.renameTo(backup)) { "Unable to prepare existing font for replacement" }
        }
        check(tempFile.renameTo(targetFile)) { "Unable to save selected font" }
        backups.forEach { (_, backup) -> backup.delete() }
    } catch (error: Throwable) {
        tempFile.delete()
        backups.forEach { (file, backup) ->
            if (!file.exists() && backup.exists()) {
                backup.renameTo(file)
            }
        }
        throw error
    }
}

private fun deleteCustomChatFontInternal(context: Context, relativePath: String) {
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return
    val fontFile = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return
    if (fontFile.path.startsWith("${filesDir.path}${File.separator}")) {
        fontFile.delete()
    }
}
