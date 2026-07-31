package me.rerere.rikkahub.ui.pages.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.context.LocalNavController
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var showSearchSheet by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri -> vm.addDocument(uri, context) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(base?.name ?: "知识库") },
                navigationIcon = { BackButton() },
                actions = {
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
            FloatingActionButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
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
        }
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

                Spacer(Modifier.height(12.dp))

                if (searchResults.isEmpty() && !searchLoading) {
                    Text(
                        "输入关键词后点击搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(searchResults) { result ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Tag {
                                        Text(
                                            if (result.scoreKind == "relevance")
                                                "相关度: ${"%.1f".format(result.score * 100)}%"
                                            else
                                                "RRF: ${"%.4f".format(result.score)}"
                                        )
                                    }
                                    Tag { Text("Rank: ${result.rank}") }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    result.chunk.content,
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