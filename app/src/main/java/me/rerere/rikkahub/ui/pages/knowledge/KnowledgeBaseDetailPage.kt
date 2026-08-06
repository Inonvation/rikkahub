package me.rerere.rikkahub.ui.pages.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseDetailPage(baseId: String) {
    val navController = LocalNavController.current
    val vm = koinViewModel<KnowledgeBaseDetailVM> { parametersOf(baseId) }
    val base by vm.base.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val processingState by vm.processingState.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val searchLoading by vm.searchLoading.collectAsState()
    val searchTopK by vm.searchTopK.collectAsState()
    val searchThreshold by vm.searchThreshold.collectAsState()
    val searchRerankEnabled by vm.searchRerankEnabled.collectAsState()
    val searchKeywordWeight by vm.searchKeywordWeight.collectAsState()
    val searchDurationMs by vm.searchDurationMs.collectAsState()
    val searchError by vm.searchError.collectAsState()
    val semanticAvailable by vm.semanticAvailable.collectAsState()
    val routeDiagnostics by vm.routeDiagnostics.collectAsState()
    val documentNames by vm.documentNames.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    var searchQuery by remember { mutableStateOf("") }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showSearchParams by remember { mutableStateOf(false) }
    var showReprocessDialog by remember { mutableStateOf(false) }

    // 导入被拒绝等一次性通知
    LaunchedEffect(Unit) {
        vm.notices.collect { notice ->
            if (notice != null) {
                toaster.show(notice, type = ToastType.Error)
                vm.consumeNotice()
            }
        }
    }

    // 文档导入处理结果 toast
    LaunchedEffect(Unit) {
        vm.importResult.collect { result ->
            if (result != null) {
                if (result.success) {
                    toaster.show("已导入「${result.fileName}」", type = ToastType.Success)
                } else {
                    toaster.show(
                        "「${result.fileName}」导入失败：${result.message ?: "未知错误"}",
                        type = ToastType.Error,
                    )
                }
                vm.consumeImportResult()
            }
        }
    }

    // 实时搜索：输入后 300ms 防抖自动检索
    @OptIn(FlowPreview::class)
    LaunchedEffect(showSearchSheet) {
        if (!showSearchSheet) return@LaunchedEffect
        snapshotFlow { searchQuery }
            .debounce(300)
            .distinctUntilChanged()
            .filter { it.isNotBlank() }
            .collect { query -> vm.searchTest(query) }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri -> vm.addDocument(uri, context) }
    }

    // 支持导入的 MIME 类型，用于文件选择器过滤（*/* 仍会进来，VM 白名单兜底）
    val supportedMimeTypes = arrayOf(
        // 文档
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/epub+zip",
        // 纯文本
        "text/plain",
        "text/csv",
        "text/markdown",
        "text/html",
        "application/json",
        "application/xml",
        // 图片
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/bmp",
        "image/heic",
        "image/heif",
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(base?.name ?: "知识库") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showReprocessDialog = true }) {
                        Icon(HugeIcons.Refresh, contentDescription = "重新处理全部")
                    }
                    IconButton(onClick = { showSearchSheet = true }) {
                        Icon(HugeIcons.Search01, contentDescription = "检索测试")
                    }
                    IconButton(onClick = {
                        navController.navigate(Screen.KnowledgeBaseSettings(baseId))
                    }) {
                        Icon(HugeIcons.Settings03, contentDescription = "设置")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { filePicker.launch(supportedMimeTypes) }) {
                Icon(HugeIcons.Add01, contentDescription = "添加文档")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (documents.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有文档", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("点击右下角 + 按钮添加文档", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(documents, key = { it.id }) { doc ->
                var showDeleteDialog by remember { mutableStateOf(false) }
                val progress = processingState[doc.id]

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(doc.fileName, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Tag { Text(doc.fileType.uppercase()) }
                                    Tag { Text(formatFileSize(doc.fileSize)) }
                                    Tag { Text(
                                        when (doc.status) {
                                            "completed" -> "嵌入完成"
                                            "processing" -> "处理中"
                                            "failed" -> "失败"
                                            "pending" -> "等待中"
                                            else -> doc.status
                                        }
                                    ) }
                                    if (doc.chunkCount > 0) {
                                        Tag { Text("${doc.chunkCount} chunks") }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "导入于 ${formatImportTime(doc.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (doc.status == "failed") {
                                    val errorMsg = doc.error
                                    if (errorMsg != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            errorMsg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { vm.retryDocument(doc.id) }) {
                                    Icon(HugeIcons.Refresh, contentDescription = "重试")
                                }
                                IconButton(onClick = { showDeleteDialog = true }) {
                                    Icon(HugeIcons.Delete01, contentDescription = "删除")
                                }
                        }

                        if (progress != null) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text("删除文档") },
                        text = { Text("确定要删除「${doc.fileName}」吗？") },
                        confirmButton = {
                            TextButton(onClick = {
                                vm.deleteDocument(doc.id)
                                showDeleteDialog = false
                            }) { Text("删除") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
                        }
                    )
                }
            }

            // 底部导入说明：同时充当列表到底的提示
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "导入说明",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "支持格式：文档(PDF/DOCX/PPTX/EPUB/XLSX)、文本(TXT/CSV/MD/JSON/XML/HTML)、图片(JPG/PNG/GIF/WEBP/BMP，走 OCR 识别)。\n" +
                            "单个文件不超过 100MB，解析后文本不超过 100 万字符（约几十万字），超出请先分割。图片导入需配置 OCR 模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }

    // 重新处理全部文档确认框
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

    // Search test sheet
    if (showSearchSheet) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Expanded, SheetValue.Hidden),
        )
        ModalBottomSheet(
            onDismissRequest = { showSearchSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    "检索测试",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("输入关键词测试检索...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { vm.searchTest(searchQuery) },
                        enabled = searchQuery.isNotBlank() && !searchLoading,
                    ) {
                        if (searchLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(HugeIcons.Search01, contentDescription = "搜索")
                        }
                    }
                }

                // 检索参数面板（默认折叠，普通用户不关心）
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSearchParams = !showSearchParams }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "高级参数",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showSearchParams) HugeIcons.ArrowUp02 else HugeIcons.ArrowDown01,
                            contentDescription = if (showSearchParams) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    AnimatedVisibility(visible = showSearchParams) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // TopK
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "TopK: ${searchTopK}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.width(64.dp),
                                    )
                                    Slider(
                                        value = searchTopK.toFloat(),
                                        onValueChange = { vm.updateSearchTopK(it.roundToInt()) },
                                        valueRange = 1f..50f,
                                        steps = 8,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Text(
                                    "最多返回几条结果，越大越全但可能混入不相关的",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            // 阈值：仅配置了 embedding 模型（语义检索可用）时显示
                            if (semanticAvailable) {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            "阈值: ${(searchThreshold * 100).roundToInt()}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.width(64.dp),
                                        )
                                        Slider(
                                            value = searchThreshold,
                                            onValueChange = { vm.updateSearchThreshold(it) },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Text(
                                        "只对语义结果生效，低于该相似度的段落不显示（0% = 不过滤）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            } else {
                                Text(
                                    "未配置 Embedding 模型，检索为纯关键词匹配，不支持相似度阈值过滤",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            // Rerank
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("Rerank", style = MaterialTheme.typography.labelMedium)
                                    Switch(
                                        checked = searchRerankEnabled,
                                        onCheckedChange = { vm.updateSearchRerankEnabled(it) },
                                        modifier = Modifier.scale(0.8f),
                                    )
                                }
                                Text(
                                    "用模型二次精排，结果更准但每次检索更慢更耗",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            // 关键词权重
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "关键词: ${"%.1f".format(searchKeywordWeight)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.width(64.dp),
                                    )
                                    Slider(
                                        value = searchKeywordWeight,
                                        onValueChange = { vm.updateSearchKeywordWeight(it) },
                                        valueRange = 0f..2f,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Text(
                                    "关键词匹配的话语权：越大越偏向找包含输入词的段落（1 = 与语义平分，0 = 纯语义）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (searchDurationMs != null && searchResults.isNotEmpty()) {
                    Text(
                        "检索耗时 ${searchDurationMs}ms · ${searchResults.size} 条结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // 分路诊断：向量 / 关键词 / 融合 各自的命中数与耗时
                if (routeDiagnostics.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "分路诊断",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            routeDiagnostics.forEach { diag ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        diag.route,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(56.dp),
                                    )
                                    if (diag.available) {
                                        Text(
                                            "命中 ${diag.hitCount} 条",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "${diag.durationMs}ms",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                    } else {
                                        Text(
                                            diag.note ?: "不可用",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (searchError != null && !searchLoading) {
                    Text(
                        "检索失败：$searchError",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else if (searchResults.isEmpty() && !searchLoading) {
                    Text(
                        "输入关键词后自动搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(searchResults) { result ->
                        val docName = documentNames[result.chunk.id]
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // 来源 + 分数标签（文件名限宽，超长省略号截断，避免撑满整行）
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (docName != null) {
                                        Tag(
                                            modifier = Modifier.weight(1f, fill = false),
                                        ) {
                                            Text(
                                                docName,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Tag { Text("Rank: ${result.rank}") }
                                    Tag {
                                        // KEYWORD 结果显示真实匹配次数，不伪装成"相关度 %"
                                        Text(
                                            if (result.scoreSource == me.rerere.knowledge.retrieval.ScoreSource.KEYWORD)
                                                "匹配 ${result.matchCount} 处"
                                            else
                                                "相关度: ${"%.0f".format(result.normalizedScore * 100)}%"
                                        )
                                    }
                                }

                                // KEYWORD 结果进度条用匹配次数反映强度，其余用归一化相似度
                                val scoreForBar = if (result.scoreSource == me.rerere.knowledge.retrieval.ScoreSource.KEYWORD) {
                                    result.matchCount.toFloat() / 5f
                                } else {
                                    result.normalizedScore
                                }.coerceIn(0f, 1f)
                                val barColor = when {
                                    scoreForBar >= 0.7f -> Color(0xFF4CAF50)
                                    scoreForBar >= 0.3f -> Color(0xFFFFC107)
                                    else -> Color(0xFFF44336)
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { scoreForBar },
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Spacer(Modifier.height(6.dp))
                                // 统一展示完整 chunk + 高亮所有关键词出现处，避免 simple_snippet 只截一段 + "..."
                                Text(
                                    text = buildAnnotatedString { highlightQuery(result.chunk.content, searchQuery) },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 10,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 检索关键词黄色高亮：query 按空白分词，大小写不敏感子串匹配，合并重叠区间。
 * 无命中时原样输出全文。
 */
private fun AnnotatedString.Builder.highlightQuery(text: String, query: String) {
    if (query.isBlank()) {
        append(text)
        return
    }
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (terms.isEmpty()) {
        append(text)
        return
    }

    // 收集所有命中区间（大小写不敏感）
    val ranges = mutableListOf<IntRange>()
    for (term in terms) {
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(term, index, ignoreCase = true)
            if (start == -1) break
            ranges += start until (start + term.length)
            index = start + term.length
        }
    }
    // 合并重叠/相邻区间，避免嵌套 SpanStyle
    val merged = ranges.sortedBy { it.first }
        .fold(mutableListOf<IntRange>()) { acc, r ->
            if (acc.isEmpty() || r.first > acc.last().last + 1) {
                acc.add(r)
            } else {
                acc[acc.lastIndex] = acc.last().first..maxOf(acc.last().last, r.last)
            }
            acc
        }

    var cursor = 0
    for (range in merged) {
        if (range.first > cursor) append(text.substring(cursor, range.first))
        withStyle(SpanStyle(background = Color(0xFFFFEB3B).copy(alpha = 0.5f))) {
            append(text.substring(range.first, range.last + 1))
        }
        cursor = range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

/**
 * 命中片段高亮：解析 simple_snippet 的 [..] 标记，标记内的词用黄色背景。
 * 与 SearchPage 的 snippet 解析模式一致。
 */
private fun AnnotatedString.Builder.highlightSnippet(snippet: String) {
    val highlightColor = Color(0xFFFFEB3B).copy(alpha = 0.5f)
    var index = 0
    while (index < snippet.length) {
        val start = snippet.indexOf('[', index)
        if (start == -1) {
            append(snippet.substring(index))
            break
        }
        if (start > index) {
            append(snippet.substring(index, start))
        }
        val end = snippet.indexOf(']', start + 1)
        if (end == -1) {
            append(snippet.substring(start))
            break
        }
        val matched = snippet.substring(start + 1, end)
        withStyle(SpanStyle(background = highlightColor)) {
            append(matched)
        }
        index = end + 1
    }
}

/** 文件大小格式化：<1KB 显示 B，否则 KB/MB（1 位小数）。 */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${"%.1f".format(kb)} KB"
    return "${"%.1f".format(kb / 1024)} MB"
}

/** 导入时间格式化：MM-DD HH:mm（当天显示 HH:mm）。 */
private fun formatImportTime(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = timestamp
    val now = java.util.Calendar.getInstance()
    val mm = "%02d".format(cal.get(java.util.Calendar.MONTH) + 1)
    val dd = "%02d".format(cal.get(java.util.Calendar.DAY_OF_MONTH))
    val hh = "%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY))
    val mi = "%02d".format(cal.get(java.util.Calendar.MINUTE))
    val sameDay = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) "$hh:$mi" else "$mm-$dd $hh:$mi"
}