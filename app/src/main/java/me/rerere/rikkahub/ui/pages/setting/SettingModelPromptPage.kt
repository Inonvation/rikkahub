package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeDepth
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeScene
import me.rerere.rikkahub.data.ai.prompts.defaultPromptOptimizePromptForScene
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.promptOptimizeDepthForScene
import me.rerere.rikkahub.data.datastore.promptOptimizePromptForScene
import me.rerere.rikkahub.data.datastore.promptOptimizeThinkingBudgetForScene
import me.rerere.rikkahub.data.datastore.withPromptOptimizeDepth
import me.rerere.rikkahub.data.datastore.withPromptOptimizePrompt
import me.rerere.rikkahub.data.datastore.withPromptOptimizeThinkingBudget
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.utils.plus

@Composable
internal fun PromptSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_translation),
                promptDescription = stringResource(R.string.setting_model_page_translate_prompt_vars),
                promptValue = settings.translatePrompt,
                onPromptChange = { vm.updateSettings(settings.copy(translatePrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(translatePrompt = DEFAULT_TRANSLATION_PROMPT)) },
                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                onUpdateReasoningLevel = { vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_title),
                promptDescription = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
                promptValue = settings.titlePrompt,
                onPromptChange = { vm.updateSettings(settings.copy(titlePrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(titlePrompt = DEFAULT_TITLE_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_suggestion),
                promptDescription = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
                promptValue = settings.suggestionPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(suggestionPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(suggestionPrompt = DEFAULT_SUGGESTION_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_ocr),
                promptDescription = stringResource(R.string.setting_model_page_ocr_prompt_vars),
                promptValue = settings.ocrPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(ocrPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(ocrPrompt = DEFAULT_OCR_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_compress),
                promptDescription = stringResource(R.string.setting_model_page_compress_prompt_vars),
                promptValue = settings.compressPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(compressPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(compressPrompt = DEFAULT_COMPRESS_PROMPT)) },
            )
        }
        // 提示词优化：合并为单个入口，点击后弹窗内切换四个场景，各自独立编辑模板与思考预算
        item {
            PromptOptimizeSettingsItem(settings = settings, vm = vm)
        }
    }
}

/**
 * 提示词优化设置项：一个入口，弹窗内按场景（通用/写作/提问/编程）切换。
 * 每个场景可独立编辑模板、独立设置思考预算，互不覆盖。
 * 模板回显：优先按场景存储，fallback 旧版全局模板，再 fallback 内置模板（保证不出现空白）。
 * 思考预算同理：按场景存储优先，fallback 全局字段。
 */
@Composable
private fun PromptOptimizeSettingsItem(settings: Settings, vm: SettingVM) {
    var showSheet by remember { mutableStateOf(false) }
    var selectedScene by remember { mutableStateOf(PromptOptimizeScene.GENERAL) }

    IosGroup(title = stringResource(R.string.setting_model_page_prompt_optimize)) {
        item(
            onClick = { showSheet = true },
            headlineContent = { Text(stringResource(R.string.setting_model_page_prompt_optimize)) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.setting_model_page_prompt_optimize_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 场景选择
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PromptOptimizeScene.entries.forEach { scene ->
                        FilterChip(
                            selected = selectedScene == scene,
                            onClick = { selectedScene = scene },
                            label = { Text(promptOptimizeSceneName(scene)) },
                        )
                    }
                }

                // 深度选择（精简/中等/详细），按场景各自记忆
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.prompt_optimize_depth),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(4.dp))
                    PromptOptimizeDepth.entries.forEach { depth ->
                        FilterChip(
                            selected = settings.promptOptimizeDepthForScene(selectedScene) == depth,
                            onClick = {
                                vm.updateSettings(settings.withPromptOptimizeDepth(selectedScene, depth))
                            },
                            label = { Text(stringResource(depthLabelRes(depth))) },
                        )
                    }
                }

                // 当前场景标题 + 思考预算
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_model_page_prompt_optimize) + " · " + promptOptimizeSceneName(selectedScene),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ReasoningButton(
                        reasoningLevel = ReasoningLevel.fromBudgetTokens(
                            settings.promptOptimizeThinkingBudgetForScene(selectedScene)
                        ),
                        onUpdateReasoningLevel = { level ->
                            vm.updateSettings(settings.withPromptOptimizeThinkingBudget(selectedScene, level.budgetTokens))
                        },
                        onlyIcon = true,
                        compact = true,
                    )
                }

                Text(
                    text = stringResource(R.string.setting_model_page_prompt_optimize_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = settings.promptOptimizePromptForScene(selectedScene)
                        ?: defaultPromptOptimizePromptForScene(selectedScene),
                    onValueChange = { vm.updateSettings(settings.withPromptOptimizePrompt(selectedScene, it)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 15,
                )

                TextButton(
                    onClick = {
                        if (selectedScene == PromptOptimizeScene.GENERAL) {
                            // 通用场景重置时同步清掉旧版全局模板，避免老用户无法清空
                            vm.updateSettings(
                                settings.withPromptOptimizePrompt(selectedScene, "").copy(promptOptimizePrompt = null)
                            )
                        } else {
                            vm.updateSettings(settings.withPromptOptimizePrompt(selectedScene, ""))
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
            }
        }
    }
}

@Composable
private fun promptOptimizeSceneName(scene: PromptOptimizeScene): String = stringResource(
    when (scene) {
        PromptOptimizeScene.GENERAL -> R.string.prompt_optimize_scene_general
        PromptOptimizeScene.WRITING -> R.string.prompt_optimize_scene_writing
        PromptOptimizeScene.QUESTION -> R.string.prompt_optimize_scene_question
        PromptOptimizeScene.PROGRAMMING -> R.string.prompt_optimize_scene_programming
    }
)

@Composable
private fun depthLabelRes(depth: PromptOptimizeDepth): Int = when (depth) {
    PromptOptimizeDepth.CONCISE -> R.string.prompt_optimize_depth_concise
    PromptOptimizeDepth.MEDIUM -> R.string.prompt_optimize_depth_medium
    PromptOptimizeDepth.DETAILED -> R.string.prompt_optimize_depth_detailed
}

@Composable
private fun PromptSettingItem(
    title: String,
    promptDescription: String,
    promptValue: String,
    onPromptChange: (String) -> Unit,
    onResetPrompt: () -> Unit,
    reasoningLevel: ReasoningLevel? = null,
    onUpdateReasoningLevel: ((ReasoningLevel) -> Unit)? = null,
) {
    var showEditor by remember { mutableStateOf(false) }

    IosGroup(title = title) {
        item(
            onClick = { showEditor = true },
            headlineContent = { Text(stringResource(R.string.setting_model_page_prompt)) },
            trailingContent = {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        if (reasoningLevel != null && onUpdateReasoningLevel != null) {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                trailingContent = {
                    ReasoningButton(
                        reasoningLevel = reasoningLevel,
                        onUpdateReasoningLevel = onUpdateReasoningLevel,
                    )
                },
            )
        }
    }

    if (showEditor) {
        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = promptDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = promptValue,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 15,
                )
                TextButton(onClick = onResetPrompt) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
            }
        }
    }
}
