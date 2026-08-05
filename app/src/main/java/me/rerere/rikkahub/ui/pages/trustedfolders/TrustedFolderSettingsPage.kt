package me.rerere.rikkahub.ui.pages.trustedfolders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FolderLocked
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.analyzeMarkdownHealth
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderBrokenLink
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderHealthReport
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderProject
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedOp
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * 单个信任文件夹项目的设置页：AI 操作审批 + 配置目录保护，全部只对 [projectId] 项目生效，
 * 与其它项目完全隔离。从文件浏览器右上角「设置」进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedFolderSettingsPage(projectId: String) {
    val vm: TrustedFolderSettingsVM = koinViewModel(parameters = { parametersOf(projectId) })
    val project by vm.project.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 断链体检
    val repository = koinInject<TrustedFolderRepository>()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var healthReport by remember { mutableStateOf<TrustedFolderHealthReport?>(null) }
    var healthLoading by remember { mutableStateOf(false) }
    var healthError by remember { mutableStateOf<String?>(null) }

    fun runHealthScan() {
        if (healthLoading) return
        healthLoading = true
        healthError = null
        scope.launch {
            runCatching {
                // 扫文件是 IO；断链/空笔记分析是 CPU 密集，放到后台线程避免卡 UI
                val files = repository.scanMarkdownFiles(projectId)
                withContext(Dispatchers.Default) { analyzeMarkdownHealth(files) }
            }.onSuccess { report ->
                healthReport = report
                healthLoading = false
            }.onFailure { e ->
                healthError = e.message ?: "扫描失败"
                healthLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.message.collect { toaster.show(it, type = ToastType.Error) }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("项目设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        val p = project
        if (p == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "项目不存在或已被删除",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = HugeIcons.FolderLocked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = p.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                item {
                    Text(
                        text = "AI 操作审批",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp),
                    )
                }
                item {
                    IosGroup(title = "开启后 AI 对本项目执行对应操作前需你手动确认") {
                        item(
                            headlineContent = { Text("读取需审批") },
                            supportingContent = { Text("AI 读取文件、列目录前需确认。默认关闭，不打扰") },
                            trailingContent = {
                                Switch(
                                    checked = p.approvalRead,
                                    onCheckedChange = { vm.updateApproval(TrustedOp.READ, it) },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text("新建需审批") },
                            supportingContent = { Text("AI 新建文件/文件夹前需确认") },
                            trailingContent = {
                                Switch(
                                    checked = p.approvalCreate,
                                    onCheckedChange = { vm.updateApproval(TrustedOp.CREATE, it) },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text("编辑需审批") },
                            supportingContent = { Text("AI 修改内容、重命名、移动前需确认") },
                            trailingContent = {
                                Switch(
                                    checked = p.approvalEdit,
                                    onCheckedChange = { vm.updateApproval(TrustedOp.EDIT, it) },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text("删除需审批") },
                            supportingContent = { Text("AI 删除文件前需确认。默认开启，建议保持") },
                            trailingContent = {
                                Switch(
                                    checked = p.approvalDelete,
                                    onCheckedChange = { vm.updateApproval(TrustedOp.DELETE, it) },
                                )
                            },
                        )
                    }
                }

                item {
                    Text(
                        text = "文件夹显示与保护",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp),
                    )
                }
                item {
                    IosGroup(title = "管理配置目录（.obsidian 等）") {
                        item(
                            headlineContent = { Text("显示配置文件夹") },
                            supportingContent = { Text("在文件列表中显示 .obsidian 等点开头目录。默认隐藏") },
                            trailingContent = {
                                Switch(
                                    checked = p.showConfigFolders,
                                    onCheckedChange = { vm.updateShowConfigFolders(it) },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text("允许 AI 修改配置目录") },
                            supportingContent = { Text("开启后 AI 才可修改 .obsidian 等配置（每次仍弹确认）。默认关闭保护配置") },
                            trailingContent = {
                                Switch(
                                    checked = p.allowEditConfigFolders,
                                    onCheckedChange = { vm.updateAllowEditConfigFolders(it) },
                                )
                            },
                        )
                    }
                }

                item {
                    Text(
                        text = "以上设置只对「${p.name}」生效；审批只在 AI 使用信任文件夹工具时生效，你手动编辑文件无需审批。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ---- 断链体检 ----
                item {
                    Text(
                        text = "断链体检",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp),
                    )
                }
                item {
                    FilledTonalButton(
                        onClick = { runHealthScan() },
                        enabled = !healthLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (healthLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("扫描断链")
                        }
                    }
                }
                item {
                    Text(
                        text = "检查本项目笔记中的失效双链与空笔记",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                healthError?.let { error ->
                    item {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                healthReport?.let { report ->
                    item {
                        Text(
                            text = "共 ${report.totalNotes} 篇笔记 · ${report.brokenLinks.size} 条断链 · ${report.emptyNotes.size} 篇空笔记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    if (report.brokenLinks.isEmpty() && report.emptyNotes.isEmpty()) {
                        item {
                            Text(
                                text = "✅ 没有发现断链或空笔记",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (report.brokenLinks.isNotEmpty()) {
                        item {
                            Text(
                                text = "断链（点击打开来源笔记）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp),
                            )
                        }
                        items(report.brokenLinks, key = { "${it.source}:${it.target}" }) { broken ->
                            ListItem(
                                headlineContent = { Text(broken.link, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text("${broken.source} → ${broken.target}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(HugeIcons.File01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable {
                                    navController.navigate(Screen.TrustedFolderEditor(projectId, broken.source))
                                },
                            )
                        }
                    }
                    if (report.emptyNotes.isNotEmpty()) {
                        item {
                            Text(
                                text = "空笔记",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp),
                            )
                        }
                        items(report.emptyNotes, key = { it }) { note ->
                            ListItem(
                                headlineContent = { Text(note, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable {
                                    navController.navigate(Screen.TrustedFolderEditor(projectId, note))
                                },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
