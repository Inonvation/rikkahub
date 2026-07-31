package me.rerere.rikkahub.ui.pages.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val CHUNK_STRATEGIES = listOf("fixed_size", "paragraph", "sentence")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseSettingsPage(baseId: String) {
    val navController = LocalNavController.current
    val vm = koinViewModel<KnowledgeBaseSettingsVM> { parametersOf(baseId) }
    val base by vm.base.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 用知识库自己的配置，没有则回退到全局默认
    val effectiveEmbeddingModelId = vm.embeddingModelId ?: settings.embeddingModelId?.toString()
    val effectiveRerankModelId = vm.rerankModelId ?: settings.rerankModelId?.toString()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("知识库设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup(
                title = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    OutlinedTextField(
                        value = vm.name,
                        onValueChange = { vm.updateName(it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }

            CardGroup(
                title = { Text("描述") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    OutlinedTextField(
                        value = vm.description,
                        onValueChange = { vm.updateDescription(it) },
                        placeholder = { Text("描述知识库的内容和用途，AI 检索时会参考此信息") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }

            CardGroup(
                title = { Text("模型") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text("Embedding 模型") },
                            description = { Text(if (vm.embeddingModelId == null && settings.embeddingModelId != null) "使用全局默认 · 把文本转成向量，语义检索用" else "向量化模型 · 把文本转成向量，语义检索用") },
                        ) {
                            ModelSelector(
                                modelId = effectiveEmbeddingModelId?.let { kotlin.uuid.Uuid.parse(it) },
                                providers = settings.providers,
                                type = ModelType.EMBEDDING,
                                allowClear = true,
                                onSelect = { vm.updateEmbeddingModelId(it?.id?.toString()) },
                            )
                        }
                    }
                )
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text("Reranking 模型") },
                            description = { Text(if (vm.rerankModelId == null && settings.rerankModelId != null) "使用全局默认 · 可选，精排结果但会多耗一次模型调用" else "重排序模型（可选）· 精排结果，但会多耗一次模型调用") },
                        ) {
                            ModelSelector(
                                modelId = effectiveRerankModelId?.let { kotlin.uuid.Uuid.parse(it) },
                                providers = settings.providers,
                                type = ModelType.RERANKING,
                                allowClear = true,
                                onSelect = { vm.updateRerankModelId(it?.id?.toString()) },
                            )
                        }
                    }
                )
            }

            CardGroup(
                title = { Text("分块设置") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text("分块策略") },
                            description = { Text("文档切块方式：固定大小 / 按段落 / 按句子；改动保存后会自动重新处理全部文档") },
                        ) {
                            Select(
                                options = CHUNK_STRATEGIES,
                                selectedOption = vm.chunkStrategy,
                                onOptionSelected = { vm.updateChunkStrategy(it) },
                                optionToString = {
                                    when (it) {
                                        "fixed_size" -> "固定大小"
                                        "paragraph" -> "段落"
                                        "sentence" -> "句子"
                                        else -> it
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                )
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text("Chunk Size") },
                            description = { Text("每块文本的字符数，越小检索越精准但碎片多；改动保存后会自动重新处理全部文档") },
                        ) {
                            OutlinedTextField(
                                value = vm.chunkSize.toString(),
                                onValueChange = { vm.updateChunkSize(it.toIntOrNull() ?: 1024) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                )
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text("Chunk Overlap") },
                            description = { Text("相邻块的重叠字符数，避免切断语义；改动保存后会自动重新处理全部文档") },
                        ) {
                            OutlinedTextField(
                                value = vm.chunkOverlap.toString(),
                                onValueChange = { vm.updateChunkOverlap(it.toIntOrNull() ?: 200) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                )
            }

            CardGroup(
                title = { Text("检索设置") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text("Top K") },
                            description = { Text("每次检索最多返回几条结果，越大越全但可能混入不相关的") },
                        ) {
                            OutlinedTextField(
                                value = vm.topK.toString(),
                                onValueChange = { vm.updateTopK(it.toIntOrNull() ?: 10) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                )
            }

            TextButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Delete01, contentDescription = null)
                Text("  删除知识库", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除知识库") },
            text = { Text("确定要删除「${base?.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete()
                    navController.popBackStack()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}