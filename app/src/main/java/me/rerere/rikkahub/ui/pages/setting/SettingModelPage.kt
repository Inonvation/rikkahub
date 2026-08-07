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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
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
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.UNSET_MODEL_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.promptOptimizeDepthForScene
import me.rerere.rikkahub.data.datastore.promptOptimizePromptForScene
import me.rerere.rikkahub.data.datastore.promptOptimizeThinkingBudgetForScene
import me.rerere.rikkahub.data.datastore.withPromptOptimizeDepth
import me.rerere.rikkahub.data.datastore.withPromptOptimizePrompt
import me.rerere.rikkahub.data.datastore.withPromptOptimizeThinkingBudget
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ModelListState
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    SettingScaffold(
        title = stringResource(R.string.setting_model_page_title),
        loading = settings.init,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                GeneralGroup(settings = settings, vm = vm)
            }
            item {
                ModelPromptGroup(
                    groupTitle = stringResource(R.string.setting_model_page_group_translate),
                    modelTitle = stringResource(R.string.setting_model_page_translate_model),
                    modelDesc = stringResource(R.string.setting_model_page_translate_model_desc),
                    modelId = settings.translateModeId,
                    onSelectModel = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
                    promptSheetTitle = stringResource(R.string.setting_model_page_prompt_translation),
                    promptDescription = stringResource(R.string.setting_model_page_translate_prompt_vars),
                    promptValue = settings.translatePrompt,
                    onPromptChange = { vm.updateSettings(settings.copy(translatePrompt = it)) },
                    onResetPrompt = { vm.updateSettings(settings.copy(translatePrompt = DEFAULT_TRANSLATION_PROMPT)) },
                    providers = settings.providers,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                    onUpdateReasoningLevel = { vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens)) },
                )
            }
            item {
                ModelPromptGroup(
                    groupTitle = stringResource(R.string.setting_model_page_group_title),
                    modelTitle = stringResource(R.string.setting_model_page_title_model),
                    modelDesc = stringResource(R.string.setting_model_page_title_model_desc),
                    modelId = settings.titleModelId,
                    onSelectModel = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
                    onClearModel = { vm.updateSettings(settings.copy(titleModelId = null)) },
                    promptSheetTitle = stringResource(R.string.setting_model_page_prompt_title),
                    promptDescription = stringResource(R.string.setting_model_page_title_prompt_vars),
                    promptValue = settings.titlePrompt,
                    onPromptChange = { vm.updateSettings(settings.copy(titlePrompt = it)) },
                    onResetPrompt = { vm.updateSettings(settings.copy(titlePrompt = DEFAULT_TITLE_PROMPT)) },
                    providers = settings.providers,
                )
            }
            item {
                SuggestionGroup(settings = settings, vm = vm)
            }
            item {
                ModelPromptGroup(
                    groupTitle = stringResource(R.string.setting_model_page_group_ocr),
                    modelTitle = stringResource(R.string.setting_model_page_ocr_model),
                    modelDesc = stringResource(R.string.setting_model_page_ocr_model_desc),
                    modelId = settings.ocrModelId,
                    onSelectModel = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
                    onClearModel = { vm.updateSettings(settings.copy(ocrModelId = UNSET_MODEL_ID)) },
                    promptSheetTitle = stringResource(R.string.setting_model_page_prompt_ocr),
                    promptDescription = stringResource(R.string.setting_model_page_ocr_prompt_vars),
                    promptValue = settings.ocrPrompt,
                    onPromptChange = { vm.updateSettings(settings.copy(ocrPrompt = it)) },
                    onResetPrompt = { vm.updateSettings(settings.copy(ocrPrompt = DEFAULT_OCR_PROMPT)) },
                    providers = settings.providers,
                )
            }
            item {
                ModelPromptGroup(
                    groupTitle = stringResource(R.string.setting_model_page_group_compress),
                    modelTitle = stringResource(R.string.setting_model_page_compress_model),
                    modelDesc = stringResource(R.string.setting_model_page_compress_model_desc),
                    modelId = settings.compressModelId,
                    onSelectModel = { vm.updateSettings(settings.copy(compressModelId = it.id)) },
                    onClearModel = { vm.updateSettings(settings.copy(compressModelId = DEFAULT_AUTO_MODEL_ID)) },
                    promptSheetTitle = stringResource(R.string.setting_model_page_prompt_compress),
                    promptDescription = stringResource(R.string.setting_model_page_compress_prompt_vars),
                    promptValue = settings.compressPrompt,
                    onPromptChange = { vm.updateSettings(settings.copy(compressPrompt = it)) },
                    onResetPrompt = { vm.updateSettings(settings.copy(compressPrompt = DEFAULT_COMPRESS_PROMPT)) },
                    providers = settings.providers,
                )
            }
            item {
                PromptOptimizeGroup(settings = settings, vm = vm)
            }
            item {
                KnowledgeGroup(settings = settings, vm = vm)
            }
            if (settings.enableSubAgent) {
                item {
                    SubAgentGroup(settings = settings, vm = vm)
                }
            }
        }
    }
}

@Composable
private fun GeneralGroup(settings: Settings, vm: SettingVM) {
    val chatModelState = rememberModelListState(settings.chatModelId, settings.providers, ModelType.CHAT)
    val fastModelState = rememberModelListState(settings.fastModelId, settings.providers, ModelType.CHAT)

    IosGroup(title = stringResource(R.string.setting_model_page_group_general)) {
        item(
            onClick = { chatModelState.open() },
            headlineContent = { Text(stringResource(R.string.setting_model_page_chat_model)) },
            trailingContent = { ModelItemTrailing(state = chatModelState) },
        )
        item(
            onClick = { fastModelState.open() },
            headlineContent = { Text(stringResource(R.string.setting_model_page_fast_model)) },
            trailingContent = { ModelItemTrailing(state = fastModelState) },
        )
    }

    ModelListSheet(state = chatModelState, onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) })
    ModelListSheet(state = fastModelState, onSelect = { vm.updateSettings(settings.copy(fastModelId = it.id)) })
}

@Composable
private fun SuggestionGroup(settings: Settings, vm: SettingVM) {
    val modelState = rememberModelListState(settings.suggestionModelId, settings.providers, ModelType.CHAT)
    var showPromptEditor by remember { mutableStateOf(false) }

    IosGroup(
        title = stringResource(R.string.setting_model_page_group_suggestion),
        subtitle = stringResource(R.string.setting_model_page_suggestion_model_desc),
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
            trailingContent = {
                Switch(
                    checked = settings.enableSuggestion,
                    onCheckedChange = { vm.updateSettings(settings.copy(enableSuggestion = it)) },
                )
            },
        )
        if (settings.enableSuggestion) {
            item(
                onClick = { modelState.open() },
                headlineContent = { Text(stringResource(R.string.setting_model_page_suggestion_model)) },
                trailingContent = { ModelItemTrailing(state = modelState) },
            )
            item(
                onClick = { showPromptEditor = true },
                headlineContent = { Text(stringResource(R.string.setting_model_page_prompt)) },
                trailingContent = { PromptItemTrailing() },
            )
        }
    }

    ModelListSheet(
        state = modelState,
        onSelect = { vm.updateSettings(settings.copy(suggestionModelId = it.id)) },
        onClear = { vm.updateSettings(settings.copy(suggestionModelId = null)) },
    )
    if (showPromptEditor) {
        PromptEditorSheet(
            title = stringResource(R.string.setting_model_page_prompt_suggestion),
            promptDescription = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
            promptValue = settings.suggestionPrompt,
            onPromptChange = { vm.updateSettings(settings.copy(suggestionPrompt = it)) },
            onResetPrompt = { vm.updateSettings(settings.copy(suggestionPrompt = DEFAULT_SUGGESTION_PROMPT)) },
            onDismiss = { showPromptEditor = false },
        )
    }
}

@Composable
private fun PromptOptimizeGroup(settings: Settings, vm: SettingVM) {
    val modelState = rememberModelListState(settings.promptOptimizeModelId, settings.providers, ModelType.CHAT)
    var showSheet by remember { mutableStateOf(false) }
    var selectedScene by remember { mutableStateOf(PromptOptimizeScene.GENERAL) }

    IosGroup(
        title = stringResource(R.string.setting_model_page_group_prompt_optimize),
        subtitle = stringResource(R.string.setting_model_page_prompt_optimize_desc),
    ) {
        item(
            onClick = { modelState.open() },
            headlineContent = { Text(stringResource(R.string.setting_model_page_prompt_optimize_model)) },
            trailingContent = { ModelItemTrailing(state = modelState) },
        )
        item(
            onClick = { showSheet = true },
            headlineContent = { Text(stringResource(R.string.setting_model_page_prompt_optimize)) },
            trailingContent = { PromptItemTrailing() },
        )
    }

    ModelListSheet(
        state = modelState,
        onSelect = { vm.updateSettings(settings.copy(promptOptimizeModelId = it.id)) },
        onClear = { vm.updateSettings(settings.copy(promptOptimizeModelId = null)) },
    )

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
private fun KnowledgeGroup(settings: Settings, vm: SettingVM) {
    val embeddingState = rememberModelListState(settings.embeddingModelId, settings.providers, ModelType.EMBEDDING)
    val rerankState = rememberModelListState(settings.rerankModelId, settings.providers, ModelType.RERANKING)

    IosGroup(title = stringResource(R.string.setting_model_page_group_knowledge)) {
        item(
            onClick = { embeddingState.open() },
            headlineContent = { Text(stringResource(R.string.setting_model_page_embedding_model)) },
            trailingContent = { ModelItemTrailing(state = embeddingState) },
        )
        item(
            onClick = { rerankState.open() },
            headlineContent = { Text(stringResource(R.string.setting_model_page_rerank_model)) },
            trailingContent = { ModelItemTrailing(state = rerankState) },
        )
    }

    ModelListSheet(
        state = embeddingState,
        onSelect = { vm.updateSettings(settings.copy(embeddingModelId = it.id)) },
        onClear = { vm.updateSettings(settings.copy(embeddingModelId = null)) },
    )
    ModelListSheet(
        state = rerankState,
        onSelect = { vm.updateSettings(settings.copy(rerankModelId = it.id)) },
        onClear = { vm.updateSettings(settings.copy(rerankModelId = null)) },
    )
}

@Composable
private fun SubAgentGroup(settings: Settings, vm: SettingVM) {
    val modelState = rememberModelListState(settings.subAgentModelId, settings.providers, ModelType.CHAT)

    IosGroup(
        title = stringResource(R.string.setting_model_page_group_sub_agent),
        subtitle = stringResource(R.string.setting_model_page_sub_agent_model_desc),
    ) {
        item(
            onClick = { modelState.open() },
            headlineContent = { Text(stringResource(R.string.setting_model_page_sub_agent_model)) },
            trailingContent = { ModelItemTrailing(state = modelState) },
        )
    }

    ModelListSheet(
        state = modelState,
        onSelect = { vm.updateSettings(settings.copy(subAgentModelId = it.id)) },
        onClear = { vm.updateSettings(settings.copy(subAgentModelId = null)) },
    )
}

@Composable
private fun ModelPromptGroup(
    groupTitle: String,
    modelTitle: String,
    modelDesc: String,
    modelId: Uuid?,
    onSelectModel: (Model) -> Unit,
    onClearModel: (() -> Unit)? = null,
    promptSheetTitle: String,
    promptDescription: String,
    promptValue: String,
    onPromptChange: (String) -> Unit,
    onResetPrompt: () -> Unit,
    providers: List<ProviderSetting>,
    modelType: ModelType = ModelType.CHAT,
    reasoningLevel: ReasoningLevel? = null,
    onUpdateReasoningLevel: ((ReasoningLevel) -> Unit)? = null,
) {
    val modelState = rememberModelListState(modelId, providers, modelType)
    var showPromptEditor by remember { mutableStateOf(false) }

    IosGroup(title = groupTitle, subtitle = modelDesc) {
        item(
            onClick = { modelState.open() },
            headlineContent = { Text(modelTitle) },
            trailingContent = { ModelItemTrailing(state = modelState) },
        )
        item(
            onClick = { showPromptEditor = true },
            headlineContent = { Text(stringResource(R.string.setting_model_page_prompt)) },
            trailingContent = { PromptItemTrailing() },
        )
        if (reasoningLevel != null && onUpdateReasoningLevel != null) {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                trailingContent = {
                    ReasoningButton(
                        reasoningLevel = reasoningLevel,
                        onUpdateReasoningLevel = onUpdateReasoningLevel,
                        onlyIcon = true,
                        compact = true,
                    )
                },
            )
        }
    }

    ModelListSheet(state = modelState, onSelect = onSelectModel, onClear = onClearModel)
    if (showPromptEditor) {
        PromptEditorSheet(
            title = promptSheetTitle,
            promptDescription = promptDescription,
            promptValue = promptValue,
            onPromptChange = onPromptChange,
            onResetPrompt = onResetPrompt,
            onDismiss = { showPromptEditor = false },
        )
    }
}

@Composable
private fun ModelItemTrailing(state: ModelListState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = state.currentModel?.displayName ?: stringResource(R.string.model_list_select_model),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PromptItemTrailing() {
    Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
}

@Composable
private fun PromptEditorSheet(
    title: String,
    promptDescription: String,
    promptValue: String,
    onPromptChange: (String) -> Unit,
    onResetPrompt: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
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
