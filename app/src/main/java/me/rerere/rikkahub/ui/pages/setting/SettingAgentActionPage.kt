package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAgentActionPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    SettingListScaffold(
        title = stringResource(R.string.setting_agent_action),
    ) {
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_agent_action_behavior_group),
            ) {
                item(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.setting_agent_action_behavior_prompt_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.setting_agent_action_behavior_prompt_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.enableAgentBehaviorPrompt,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(enableAgentBehaviorPrompt = it))
                            },
                        )
                    },
                )
            }
        }

        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_agent_action_todo_group),
            ) {
                item(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.setting_agent_action_todo_list_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.setting_agent_action_todo_list_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.enableTodoList,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(enableTodoList = it))
                            },
                        )
                    },
                )
            }
        }

        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_agent_action_sub_agent_group),
            ) {
                item(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.setting_agent_action_sub_agent_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.setting_agent_action_sub_agent_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.enableSubAgent,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(enableSubAgent = it))
                            },
                        )
                    },
                )

                item(
                    headlineContent = {
                        NumberSettingContent(
                            value = settings.subAgentTimeoutSeconds,
                            title = stringResource(R.string.setting_agent_action_timeout_title),
                            description = stringResource(R.string.setting_agent_action_timeout_desc),
                            placeholder = "600",
                            onValueChange = {
                                vm.updateSettings(settings.copy(subAgentTimeoutSeconds = it))
                            },
                        )
                    },
                )

                item(
                    headlineContent = {
                        NumberSettingContent(
                            value = settings.subAgentMaxConcurrent.toLong(),
                            title = stringResource(R.string.setting_agent_action_max_concurrent_title),
                            description = stringResource(R.string.setting_agent_action_max_concurrent_desc),
                            placeholder = "5",
                            onValueChange = {
                                vm.updateSettings(
                                    settings.copy(
                                        subAgentMaxConcurrent = it?.toInt()?.coerceIn(1, 64) ?: 5
                                    )
                                )
                            },
                        )
                    },
                )

                item(
                    headlineContent = {
                        NumberSettingContent(
                            value = settings.subAgentMaxRetries.toLong(),
                            title = stringResource(R.string.setting_agent_action_max_retries_title),
                            description = stringResource(R.string.setting_agent_action_max_retries_desc),
                            placeholder = "1",
                            onValueChange = {
                                vm.updateSettings(
                                    settings.copy(
                                        subAgentMaxRetries = it?.toInt()?.coerceIn(0, 3) ?: 1
                                    )
                                )
                            },
                        )
                    },
                )

                item(
                    headlineContent = {
                        NumberSettingContent(
                            value = settings.subAgentMaxTokens,
                            title = stringResource(R.string.setting_agent_action_max_tokens_title),
                            description = stringResource(R.string.setting_agent_action_max_tokens_desc),
                            placeholder = stringResource(R.string.setting_agent_action_max_tokens_placeholder),
                            onValueChange = {
                                vm.updateSettings(
                                    settings.copy(
                                        subAgentMaxTokens = it?.takeIf { v -> v > 0 }
                                    )
                                )
                            },
                        )
                    },
                )

                item(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.setting_agent_action_guidance_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.setting_agent_action_guidance_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.subAgentAllowGuidance,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(subAgentAllowGuidance = it))
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberSettingContent(
    value: Long?,
    title: String,
    description: String,
    placeholder: String,
    onValueChange: (Long?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = value?.toString() ?: "",
            onValueChange = { text ->
                if (text.isBlank()) {
                    onValueChange(null)
                } else {
                    text.toLongOrNull()?.let(onValueChange)
                }
            },
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}
