package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RerankingGenerationParams
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import org.koin.compose.koinInject

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
) {
    var showSheet by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hapticController = rememberHaptic()

    IconButton(onClick = {
        hapticController.lightTap()
        showSheet = true
    }) {
        Icon(HugeIcons.Connect, null)
    }

    if (showSheet) {
        val allModels = internalProvider.models
        var selectedModel by remember { mutableStateOf(allModels.firstOrNull()) }
        var chatNonStreaming by remember { mutableStateOf<UiState<String>>(UiState.Idle) }
        var chatStreamingText by remember { mutableStateOf("") }
        var chatStreaming by remember { mutableStateOf<UiState<String>>(UiState.Idle) }
        var chatTools by remember { mutableStateOf<UiState<String>>(UiState.Idle) }
        var embeddingState by remember { mutableStateOf<UiState<String>>(UiState.Idle) }
        var rerankingState by remember { mutableStateOf<UiState<String>>(UiState.Idle) }

        fun resetStates() {
            chatNonStreaming = UiState.Idle
            chatStreamingText = ""
            chatStreaming = UiState.Idle
            chatTools = UiState.Idle
            embeddingState = UiState.Idle
            rerankingState = UiState.Idle
        }

        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Expanded, SheetValue.Hidden),
        )
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.setting_provider_page_test_connection),
                    style = MaterialTheme.typography.titleLarge,
                )

                if (allModels.isEmpty()) {
                    Text("暂无模型", modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    Select(
                        options = allModels,
                        selectedOption = selectedModel ?: allModels.first(),
                        onOptionSelected = {
                            selectedModel = it
                            resetStates()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        leading = {
                            (selectedModel ?: allModels.firstOrNull())?.let { m ->
                                AutoAIIcon(name = m.displayName, modifier = Modifier.size(24.dp))
                            }
                        },
                        optionLeading = { model ->
                            AutoAIIcon(name = model.displayName, modifier = Modifier.size(24.dp))
                        },
                        optionToString = { model ->
                            val typeLabel = when (model.type) {
                                ModelType.CHAT -> "对话"
                                ModelType.EMBEDDING -> "向量"
                                ModelType.RERANKING -> "重排序"
                                ModelType.IMAGE -> "图像"
                            }
                            "${model.displayName}  [$typeLabel]"
                        },
                    )
                }

                val model = selectedModel
                if (model != null) {
                    when (model.type) {
                        ModelType.CHAT -> {
                            TestResultItem(stringResource(R.string.setting_provider_page_test_non_streaming), chatNonStreaming, (chatNonStreaming as? UiState.Success)?.data ?: "")
                            TestResultItem(stringResource(R.string.setting_provider_page_test_streaming), chatStreaming, chatStreamingText)
                            TestResultItem(stringResource(R.string.setting_provider_page_test_tool_call), chatTools, (chatTools as? UiState.Success)?.data ?: "")
                        }
                        ModelType.EMBEDDING -> {
                            TestResultItem("嵌入", embeddingState, (embeddingState as? UiState.Success)?.data ?: "")
                        }
                        ModelType.RERANKING -> {
                            TestResultItem("重排序", rerankingState, (rerankingState as? UiState.Success)?.data ?: "")
                        }
                        else -> {}
                    }

                    Button(
                        onClick = {
                            hapticController.heavyTap()
                            resetStates()
                            scope.launch {
                                val provider = providerManager.getProviderByType(internalProvider)
                                when (model.type) {
                                    ModelType.CHAT -> {
                                        launch {
                                            runCatching {
                                                chatNonStreaming = UiState.Loading
                                                val result = provider.generateText(
                                                    providerSetting = internalProvider,
                                                    messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user("hello")),
                                                    params = TextGenerationParams(model = model, customHeaders = model.customHeaders, customBody = model.customBodies)
                                                )
                                                val text = result.message.parts
                                                    .filterIsInstance<UIMessagePart.Text>()
                                                    .joinToString("") { it.text }
                                                chatNonStreaming = UiState.Success(text)
                                            }.onFailure { chatNonStreaming = UiState.Error(it) }
                                        }
                                        launch {
                                            runCatching {
                                                chatStreaming = UiState.Loading
                                                val flow = provider.streamText(
                                                    providerSetting = internalProvider,
                                                    messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user("hello")),
                                                    params = TextGenerationParams(model = model, customHeaders = model.customHeaders, customBody = model.customBodies)
                                                )
                                                flow.collect { chunk ->
                                                    if (chunk is StreamChunk.TextDelta) {
                                                        chatStreamingText += chunk.text
                                                    }
                                                }
                                                chatStreaming = UiState.Success(chatStreamingText)
                                            }.onFailure { chatStreaming = UiState.Error(it) }
                                        }
                                        launch {
                                            runCatching {
                                                chatTools = UiState.Loading
                                                val testTool = Tool(name = "get_current_time", description = "Get the current date and time.", execute = { emptyList() })
                                                val result = provider.generateText(
                                                    providerSetting = internalProvider,
                                                    messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user("Use the get_current_time tool.")),
                                                    params = TextGenerationParams(model = model, tools = listOf(testTool), customHeaders = model.customHeaders, customBody = model.customBodies)
                                                )
                                                val message = result.message
                                                val toolCall = message.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull()
                                                val resultText = if (toolCall != null) context.getString(
                                                    R.string.setting_provider_page_test_tool_called,
                                                    toolCall.toolName,
                                                    toolCall.input,
                                                )
                                                else context.getString(
                                                    R.string.setting_provider_page_test_tool_not_called,
                                                    message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text },
                                                )
                                                chatTools = UiState.Success(resultText)
                                            }.onFailure { chatTools = UiState.Error(it) }
                                        }
                                    }
                                    ModelType.EMBEDDING -> {
                                        runCatching {
                                            embeddingState = UiState.Loading
                                            val result = provider.generateEmbedding(
                                                providerSetting = internalProvider,
                                                params = EmbeddingGenerationParams(model = model, input = listOf("Hello world", "Test embedding"))
                                            )
                                            val dim = result.embeddings.firstOrNull()?.size ?: 0
                                            val firstValues = result.embeddings.firstOrNull()?.take(3)?.joinToString(", ") { "%.4f".format(it) } ?: ""
                                            embeddingState = UiState.Success("${result.embeddings.size} 向量, 维度=$dim, 前3: [$firstValues...]")
                                        }.onFailure { embeddingState = UiState.Error(it) }
                                    }
                                    ModelType.RERANKING -> {
                                        runCatching {
                                            rerankingState = UiState.Loading
                                            val result = provider.rerank(
                                                providerSetting = internalProvider,
                                                params = RerankingGenerationParams(
                                                    model = model,
                                                    query = "What is the capital of France?",
                                                    documents = listOf("Paris is the capital of France.", "London is the capital of England.", "Tokyo is the capital of Japan."),
                                                    topN = 3,
                                                )
                                            )
                                            val top = result.results.firstOrNull()
                                            val summary = if (top != null) "Top: doc[${top.index}] ${"%.1f".format(top.relevanceScore * 100)}%" else "无结果"
                                            rerankingState = UiState.Success("${result.results.size} 结果, $summary")
                                        }.onFailure { rerankingState = UiState.Error(it) }
                                    }
                                    else -> {}
                                }
                            }
                        },
                        enabled = !(chatNonStreaming is UiState.Loading || chatStreaming is UiState.Loading || embeddingState is UiState.Loading || rerankingState is UiState.Loading || chatTools is UiState.Loading),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setting_provider_page_test))
                    }
                }
            }
        }
    }
}

@Composable
private fun TestResultItem(
    label: String,
    state: UiState<String>,
    resultText: String,
) {
    var showErrorSheet by remember { mutableStateOf(false) }
    val hapticController = rememberHaptic()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp)
        )
        when (state) {
            is UiState.Idle -> Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
            is UiState.Success -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.green6
                )
                if (resultText.isNotBlank()) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.red6,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        hapticController.lightTap()
                        showErrorSheet = true
                    }
            )
        }
    }

    if (showErrorSheet && state is UiState.Error) {
        val errorSheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        val stackTrace = remember(state.error) { state.error.stackTraceToString() }
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = errorSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = state.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.red6,
                )
                Text(
                    text = stackTrace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
