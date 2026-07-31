package me.rerere.rikkahub.ui.pages.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.knowledge.data.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
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
    var showReprocessDialog by remember { mutableStateOf(false) }

    // 数字输入框用本地 String 状态，允许清空编辑；仅在提交时校验。
    // 以已加载实体为 key（顺带修复：原先实体异步加载前就初始化，自定义值从不回显）。
    // 等于默认值则显示为空，placeholder 提示默认值，表示"用默认"。
    var chunkSizeText by rememberDefaultableIntState(
        value = base?.chunkSize ?: KnowledgeBaseEntity.DEFAULT_CHUNK_SIZE,
        defaultValue = KnowledgeBaseEntity.DEFAULT_CHUNK_SIZE,
    )
    var chunkOverlapText by rememberDefaultableIntState(
        value = base?.chunkOverlap ?: KnowledgeBaseEntity.DEFAULT_CHUNK_OVERLAP,
        defaultValue = KnowledgeBaseEntity.DEFAULT_CHUNK_OVERLAP,
    )
    var topKText by rememberDefaultableIntState(
        value = base?.topK ?: KnowledgeBaseEntity.DEFAULT_TOP_K,
        defaultValue = KnowledgeBaseEntity.DEFAULT_TOP_K,
    )

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
                            description = { Text("文档切块方式：固定大小 / 按段落 / 按句子；改动保存后需手动重新处理全部文档生效") },
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
                            description = { Text("每块文本的字符数，越小检索越精准但碎片多；默认 ${KnowledgeBaseEntity.DEFAULT_CHUNK_SIZE} · 不建议修改") },
                        ) {
                            OutlinedTextField(
                                value = chunkSizeText,
                                onValueChange = {
                                    chunkSizeText = it
                                    // 清空 = 恢复默认；非空则防抖保存（钳制在落库时统一做）
                                    it.applyDefaultableInt(KnowledgeBaseEntity.DEFAULT_CHUNK_SIZE, vm::updateChunkSize)
                                },
                                placeholder = { Text("默认 ${KnowledgeBaseEntity.DEFAULT_CHUNK_SIZE}，不建议修改") },
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
                            description = { Text("相邻块的重叠字符数，避免切断语义；默认 ${KnowledgeBaseEntity.DEFAULT_CHUNK_OVERLAP}（约 chunk size 10%）· 不建议修改") },
                        ) {
                            OutlinedTextField(
                                value = chunkOverlapText,
                                onValueChange = {
                                    chunkOverlapText = it
                                    it.applyDefaultableInt(KnowledgeBaseEntity.DEFAULT_CHUNK_OVERLAP, vm::updateChunkOverlap)
                                },
                                placeholder = { Text("默认 ${KnowledgeBaseEntity.DEFAULT_CHUNK_OVERLAP}，不建议修改") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                )
                if (vm.hasPendingReprocess) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "分块设置已修改，文档索引尚未按新设置重建",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ReprocessButton(
                                reprocessing = vm.reprocessing,
                                idleText = "立即重新处理全部文档",
                                onClick = { showReprocessDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            CardGroup(
                title = { Text("检索设置") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text("Top K") },
                            description = { Text("每次检索最多返回几条结果，越大越全但可能混入不相关的；默认 ${KnowledgeBaseEntity.DEFAULT_TOP_K}") },
                        ) {
                            OutlinedTextField(
                                value = topKText,
                                onValueChange = {
                                    topKText = it
                                    it.applyDefaultableInt(KnowledgeBaseEntity.DEFAULT_TOP_K, vm::updateTopK)
                                },
                                placeholder = { Text("默认 ${KnowledgeBaseEntity.DEFAULT_TOP_K}") },
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
                            label = { Text("HyDE 查询改写") },
                            description = { Text("用 LLM 先生成一段假设答案，再用假设答案的向量做检索。对口吻化问题召回更准，但会多耗一次模型调用") },
                        ) {
                            Switch(
                                checked = vm.useHyde,
                                onCheckedChange = { vm.updateUseHyde(it) },
                            )
                        }
                    }
                )
            }

            // 常驻手动重处理入口：分块设置或 embedding 模型改动后，需手动重建索引
            ReprocessButton(
                reprocessing = vm.reprocessing,
                idleText = "重新处理全部文档",
                onClick = { showReprocessDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Delete01, contentDescription = null)
                Text("  删除知识库", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    RikkaConfirmDialog(
        show = showReprocessDialog,
        title = "重新处理全部文档",
        confirmText = "重新处理",
        dismissText = "取消",
        onConfirm = {
            showReprocessDialog = false
            vm.reprocessAll()
        },
        onDismiss = { showReprocessDialog = false },
    ) {
        Text("将删除「${base?.name}」下所有文档的现有索引并按当前设置重建，耗时取决于文档数量，是否继续？")
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

/**
 * 可回退默认值的数字输入框状态：等于默认值时显示为空（placeholder 提示默认值），
 * 表示"用默认"；非默认值才回显实际值。以 value 为 key，实体加载后自动刷新。
 */
@Composable
private fun rememberDefaultableIntState(value: Int, defaultValue: Int): MutableState<String> =
    remember(value) { mutableStateOf(if (value == defaultValue) "" else value.toString()) }

/** 数字输入框 onValueChange 统一处理：空 = 恢复默认；非空解析为 Int 交给 onValue。 */
private fun String.applyDefaultableInt(defaultValue: Int, onValue: (Int) -> Unit) {
    if (isBlank()) {
        onValue(defaultValue)
    } else {
        toIntOrNull()?.let(onValue)
    }
}

/** 重新处理按钮：reprocessing 时显示转圈 + "正在重新处理..."，空闲时显示图标 + idleText。 */
@Composable
private fun ReprocessButton(
    reprocessing: Boolean,
    idleText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !reprocessing,
        modifier = modifier,
    ) {
        if (reprocessing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(HugeIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(if (reprocessing) "正在重新处理..." else idleText)
    }
}