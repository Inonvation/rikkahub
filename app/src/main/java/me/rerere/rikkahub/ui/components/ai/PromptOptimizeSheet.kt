package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeLevel
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeScene
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
    onConfirmReplace: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var scene by remember { mutableStateOf(PromptOptimizeScene.GENERAL) }
    var level by remember { mutableStateOf(PromptOptimizeLevel.STANDARD) }

    ModalBottomSheet(
        onDismissRequest = {
            vm.cancel()
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
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

                    // 程度选择
                    Text(
                        text = stringResource(R.string.prompt_optimize_level),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PromptOptimizeLevel.entries.forEach { l ->
                            FilterChip(
                                selected = level == l,
                                onClick = { level = l },
                                label = { Text(stringResource(levelLabelRes(l))) },
                            )
                        }
                    }

                    Button(
                        onClick = { vm.optimize(scene, level, state.textContent.text.toString()) },
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
                        TextButton(onClick = { onConfirmReplace(editable) }) {
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
}

private fun levelLabelRes(level: PromptOptimizeLevel): Int = when (level) {
    PromptOptimizeLevel.CONCISE -> R.string.prompt_optimize_level_concise
    PromptOptimizeLevel.STANDARD -> R.string.prompt_optimize_level_standard
    PromptOptimizeLevel.DETAILED -> R.string.prompt_optimize_level_detailed
}
