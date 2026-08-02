package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeDepth
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeScene
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeTone
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.promptOptimizeDepthForScene
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.pages.chat.PromptOptimizeVM
import me.rerere.rikkahub.utils.UiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptOptimizeSheet(
    state: ChatInputState,
    vm: PromptOptimizeVM,
    settings: Settings,
    onConfirmReplace: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var scene by remember { mutableStateOf(PromptOptimizeScene.GENERAL) }
    var tone by remember { mutableStateOf(PromptOptimizeTone.NORMAL) }
    // 深度默认跟随设置页按场景的记忆值；切换场景时重置为该场景的设置值
    var depth by remember(scene) { mutableStateOf(settings.promptOptimizeDepthForScene(scene)) }
    var extraNote by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = {
            vm.cancel()
            onDismiss()
        },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.prompt_optimize),
                style = MaterialTheme.typography.titleMedium,
            )

            when (val current = uiState) {
                is UiState.Idle -> {
                    // 场景选择
                    Text(
                        text = stringResource(R.string.prompt_optimize_scene),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PromptOptimizeScene.entries.forEach { s ->
                            FilterChip(
                                selected = scene == s,
                                onClick = { scene = s },
                                label = { Text(stringResource(sceneLabelRes(s))) },
                            )
                        }
                    }

                    // 语气选择
                    Text(
                        text = stringResource(R.string.prompt_optimize_tone),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PromptOptimizeTone.entries.forEach { t ->
                            FilterChip(
                                selected = tone == t,
                                onClick = { tone = t },
                                label = { Text(stringResource(toneLabelRes(t))) },
                            )
                        }
                    }

                    // 深度选择（精简/中等/详细），默认跟随设置页按场景的记忆值
                    Text(
                        text = stringResource(R.string.prompt_optimize_depth),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PromptOptimizeDepth.entries.forEach { d ->
                            FilterChip(
                                selected = depth == d,
                                onClick = { depth = d },
                                label = { Text(stringResource(depthLabelRes(d))) },
                            )
                        }
                    }

                    // 附加说明（可选）
                    OutlinedTextField(
                        value = extraNote,
                        onValueChange = { extraNote = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.prompt_optimize_extra_note)) },
                        minLines = 1,
                        maxLines = 3,
                    )

                    Button(
                        onClick = { vm.optimize(scene, tone, depth, state.textContent.text.toString(), extraNote) },
                        enabled = state.textContent.text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.prompt_optimize_button))
                    }
                }

                UiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RabbitLoadingIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.prompt_optimize_loading))
                    }
                }

                is UiState.Success -> {
                    var editable by remember { mutableStateOf(current.data) }
                    Text(
                        text = stringResource(R.string.prompt_optimize_result_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedTextField(
                        value = editable,
                        onValueChange = { editable = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 10,
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { vm.cancel(); onDismiss() }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = {
                            // 先复位 VM 状态，否则下次打开弹窗会残留上次的优化结果而非场景选择表单
                            vm.cancel()
                            onConfirmReplace(editable)
                        }) {
                            Text(stringResource(R.string.prompt_optimize_apply))
                        }
                    }
                }

                is UiState.Error -> {
                    Text(
                        text = stringResource(R.string.prompt_optimize_failed) + (current.error.message ?: ""),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { vm.cancel(); onDismiss() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

private fun sceneLabelRes(scene: PromptOptimizeScene): Int = when (scene) {
    PromptOptimizeScene.GENERAL -> R.string.prompt_optimize_scene_general
    PromptOptimizeScene.WRITING -> R.string.prompt_optimize_scene_writing
    PromptOptimizeScene.QUESTION -> R.string.prompt_optimize_scene_question
    PromptOptimizeScene.PROGRAMMING -> R.string.prompt_optimize_scene_programming
}

private fun toneLabelRes(tone: PromptOptimizeTone): Int = when (tone) {
    PromptOptimizeTone.SERIOUS -> R.string.prompt_optimize_tone_serious
    PromptOptimizeTone.HUMOROUS -> R.string.prompt_optimize_tone_humorous
    PromptOptimizeTone.NORMAL -> R.string.prompt_optimize_tone_normal
}

private fun depthLabelRes(depth: PromptOptimizeDepth): Int = when (depth) {
    PromptOptimizeDepth.CONCISE -> R.string.prompt_optimize_depth_concise
    PromptOptimizeDepth.MEDIUM -> R.string.prompt_optimize_depth_medium
    PromptOptimizeDepth.DETAILED -> R.string.prompt_optimize_depth_detailed
}
