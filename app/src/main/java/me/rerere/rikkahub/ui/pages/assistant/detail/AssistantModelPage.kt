package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.modeRefDisplayName
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt

/**
 * 助手设置 · ② 模型与生成。
 * 承载：聊天模型 / 默认能力模式 / 思考预算 / 温度 / topP / 最大输出 Token / 流式输出 / 上下文条数 / 上下文 Token 上限。
 */
@Composable
fun AssistantModelPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("模型与生成")
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantModelContent(
            innerPadding = innerPadding,
            assistant = assistant,
            providers = providers,
            customModes = settings.customModes,
            builtinModeOverrides = settings.builtinModeOverrides,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantModelContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    customModes: List<CustomModeConfig>,
    builtinModeOverrides: Map<ChatMode, ChatModePolicy>,
    onUpdate: (Assistant) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = innerPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CustomColors.cardColorsOnSurfaceContainer
            ) {
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_chat_model))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_chat_model_desc))
                    },
                    content = {
                        ModelSelector(
                            modelId = assistant.chatModelId,
                            providers = providers,
                            type = ModelType.CHAT,
                            onSelect = {
                                onUpdate(
                                    assistant.copy(
                                        chatModelId = it.id
                                    )
                                )
                            },
                        )
                    }
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_default_mode))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_default_mode_desc))
                    },
                ) {
                    Select(
                        options = listOf<String?>(null) +
                            ChatMode.entries.map { it.name } +
                            customModes.map { ModeRefs.custom(it.id) },
                        selectedOption = assistant.defaultMode,
                        onOptionSelected = { mode ->
                            onUpdate(assistant.copy(defaultMode = mode))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        optionToString = { mode ->
                            modeRefDisplayName(mode, customModes, builtinModeOverrides)
                        },
                    )
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_temperature))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_temperature_warning))
                    },
                    tail = {
                        Switch(
                            checked = assistant.temperature != null,
                            onCheckedChange = { enabled ->
                                onUpdate(
                                    assistant.copy(
                                        temperature = if (enabled) 1.0f else null
                                    )
                                )
                            }
                        )
                    }
                ) {
                    if (assistant.temperature != null) {
                        var temperatureInput by remember(assistant.id) {
                            mutableStateOf(assistant.temperature.toString())
                        }
                        val temperatureValue = temperatureInput.toFloatOrNull()
                        OutlinedTextField(
                            value = temperatureInput,
                            onValueChange = { value ->
                                temperatureInput = value
                                value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { temperature ->
                                    onUpdate(
                                        assistant.copy(
                                            temperature = temperature
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = temperatureValue == null || temperatureValue !in 0f..2f,
                        )
                    }
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_top_p))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_top_p_warning))
                    },
                    tail = {
                        Switch(
                            checked = assistant.topP != null,
                            onCheckedChange = { enabled ->
                                onUpdate(
                                    assistant.copy(
                                        topP = if (enabled) 1.0f else null
                                    )
                                )
                            }
                        )
                    }
                ) {
                    assistant.topP?.let { topP ->
                        var topPInput by remember(assistant.id) {
                            mutableStateOf(topP.toString())
                        }
                        val topPValue = topPInput.toFloatOrNull()
                        OutlinedTextField(
                            value = topPInput,
                            onValueChange = { value ->
                                topPInput = value
                                value.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { nextTopP ->
                                    onUpdate(
                                        assistant.copy(
                                            topP = nextTopP
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = topPValue == null || topPValue !in 0f..1f,
                        )
                    }
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_context_message_limit))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_context_message_limit_desc))
                    },
                ) {
                    Slider(
                        value = assistant.contextMessageLimit.toFloat(),
                        onValueChange = { value ->
                            onUpdate(
                                assistant.copy(
                                    contextMessageLimit = snapContextMessageLimit(value)
                                )
                            )
                        },
                        valueRange = 0f..512f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = if (assistant.contextMessageLimit > 0) stringResource(
                            R.string.assistant_page_context_message_limit_count,
                            assistant.contextMessageLimit
                        ) else stringResource(R.string.assistant_page_context_message_limit_unlimited),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_stream_output))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_stream_output_desc))
                    },
                    tail = {
                        Switch(
                            checked = assistant.streamOutput,
                            onCheckedChange = {
                                onUpdate(
                                    assistant.copy(
                                        streamOutput = it
                                    )
                                )
                            }
                        )
                    }
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_thinking_budget))
                    },
                ) {
                    ReasoningButton(
                        reasoningLevel = assistant.reasoningLevel,
                        onUpdateReasoningLevel = { level ->
                            onUpdate(assistant.copy(reasoningLevel = level))
                        }
                    )
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_max_tokens))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_max_tokens_desc))
                    },
                ) {
                    OutlinedTextField(
                        value = assistant.maxTokens?.toString() ?: "",
                        onValueChange = { text ->
                            val tokens = if (text.isBlank()) {
                                null
                            } else {
                                text.toIntOrNull()?.takeIf { it > 0 }
                            }
                            onUpdate(
                                assistant.copy(
                                    maxTokens = tokens
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                        },
                    )
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text("上下文 Token 上限")
                    },
                    description = {
                        Text("用于顶栏上下文窗口圆环显示，修改仅影响展示")
                    },
                ) {
                    OutlinedTextField(
                        value = assistant.contextTokenLimit.toString(),
                        onValueChange = { text ->
                            val tokens = text.toIntOrNull()?.takeIf { it > 0 }
                            if (tokens != null) {
                                onUpdate(
                                    assistant.copy(
                                        contextTokenLimit = tokens
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("128000")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }
        }
    }
}

/** 上下文限制的最小有效值 */
private const val MIN_CONTEXT_MESSAGE_LIMIT = 20

/** 把滑块取值吸附到 0(不限制) 或不低于 [MIN_CONTEXT_MESSAGE_LIMIT] 的有效档位 */
private fun snapContextMessageLimit(value: Float): Int {
    val raw = value.roundToInt()
    return when {
        raw < MIN_CONTEXT_MESSAGE_LIMIT / 2 -> 0
        raw < MIN_CONTEXT_MESSAGE_LIMIT -> MIN_CONTEXT_MESSAGE_LIMIT
        else -> raw
    }
}
