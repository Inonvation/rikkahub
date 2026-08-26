package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.ai.KnowledgeBasePickerButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.SwitchSize
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Composable
fun AssistantToolsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    SettingScaffold(title = "工具与服务") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                IosGroup(
                    title = "联网与知识库",
                    subtitle = "控制助手是否可以使用外部信息来源",
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Globe02, contentDescription = null) },
                        headlineContent = { Text("联网搜索") },
                        supportingContent = {
                            Text(
                                text = if (assistant.enableWebSearch) "允许助手主动搜索网页内容" else "助手不会主动调用联网搜索",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = assistant.enableWebSearch,
                                onCheckedChange = { enabled ->
                                    vm.update(assistant.copy(enableWebSearch = enabled))
                                },
                                size = SwitchSize.Small,
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Bookshelf01, contentDescription = null) },
                        headlineContent = { Text("知识库") },
                        supportingContent = {
                            Text(
                                text = if (assistant.knowledgeBaseIds.isEmpty()) "未绑定知识库" else "已绑定 ${assistant.knowledgeBaseIds.size} 个知识库",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                KnowledgeBasePickerButton(
                                    selectedIds = assistant.knowledgeBaseIds,
                                    onSelectionChange = { ids ->
                                        vm.update(assistant.copy(knowledgeBaseIds = ids))
                                    },
                                )
                                SettingChevron()
                            }
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Code, contentDescription = null) },
                        headlineContent = { Text("查询改写") },
                        supportingContent = {
                            Text("在知识库检索前使用 LLM 改写查询", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            Switch(
                                checked = assistant.enableKnowledgeQueryRewrite,
                                onCheckedChange = { enabled ->
                                    vm.update(assistant.copy(enableKnowledgeQueryRewrite = enabled))
                                },
                                enabled = assistant.knowledgeBaseIds.isNotEmpty(),
                                size = SwitchSize.Small,
                            )
                        },
                    )
                }
            }

            item {
                IosGroup(
                    title = "工作区",
                    subtitle = "为新建对话提供默认工作目录和文件工具",
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Settings03, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_workspace)) },
                        supportingContent = {
                            Text(
                                text = if (assistant.workspaceId == null) {
                                    "未绑定工作区，工作区工具不可用"
                                } else {
                                    "已绑定工作区${assistant.defaultWorkspaceCwd?.let { " · 默认目录 $it" } ?: ""}"
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            val selectedWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
                            Select(
                                options = listOf<WorkspaceEntity?>(null) + workspaces,
                                selectedOption = selectedWorkspace,
                                onOptionSelected = { workspace ->
                                    vm.update(
                                        assistant.copy(
                                            workspaceId = workspace?.id?.let { Uuid.parse(it) },
                                            defaultWorkspaceCwd = if (workspace == null) null else assistant.defaultWorkspaceCwd,
                                        )
                                    )
                                },
                                modifier = Modifier.width(160.dp),
                                optionToString = { workspace ->
                                    workspace?.name ?: stringResource(R.string.workspace_no_binding)
                                },
                            )
                        },
                    )
                }
            }

            item {
                IosGroup(
                    title = "工具绑定",
                    subtitle = "进入对应页面配置工具明细",
                ) {
                    item(
                        onClick = { navController.navigate(Screen.AssistantLocalTool(id)) },
                        leadingContent = { Icon(HugeIcons.Code, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_local_tools)) },
                        supportingContent = { Text("JavaScript、时间信息、询问用户等本地工具") },
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AssistantMcp(id)) },
                        leadingContent = { Icon(HugeIcons.Wrench01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_mcp)) },
                        supportingContent = {
                            Text(if (settings.enableMcpManager) "绑定外部 MCP 服务" else "全局 MCP 管理器未启用")
                        },
                        trailingContent = { SettingChevron() },
                    )
                }
            }

            item {
                IosGroup(
                    title = "扩展与学习",
                    subtitle = "技能、快捷消息、注入与学习工具",
                ) {
                    item(
                        onClick = { navController.navigate(Screen.AssistantInjections(id)) },
                        leadingContent = { Icon(HugeIcons.Puzzle, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.assistant_extensions_page_title)) },
                        supportingContent = {
                            Text(
                                "Skills ${assistant.enabledSkills.size} · 快捷 ${assistant.quickMessageIds.size} · 注入 ${assistant.modeInjectionIds.size + assistant.lorebookIds.size}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { SettingChevron() },
                    )

                    val studyToolLabels = listOf(
                        "save_vocabulary" to "生词本",
                        "save_note" to "笔记",
                        "save_wrong_question" to "错题本",
                        "save_knowledge_card" to "知识点",
                        "quiz_user" to "抽背",
                    )
                    studyToolLabels.forEach { (toolName, label) ->
                        item(
                            leadingContent = { Icon(HugeIcons.Code, contentDescription = null) },
                            headlineContent = { Text(label) },
                            supportingContent = { Text("允许助手在学习模式下调用「$label」") },
                            trailingContent = {
                                Switch(
                                    checked = assistant.enabledStudyTools.contains(toolName),
                                    onCheckedChange = { enabled ->
                                        val newTools = if (enabled) {
                                            assistant.enabledStudyTools + toolName
                                        } else {
                                            assistant.enabledStudyTools - toolName
                                        }
                                        vm.update(assistant.copy(enabledStudyTools = newTools))
                                    },
                                    size = SwitchSize.Small,
                                )
                            },
                        )
                    }

                    val subjects = listOf(
                        "", "english", "math", "politics", "mechanics"
                    )
                    val subjectLabels = mapOf(
                        "" to "未指定",
                        "english" to "英语",
                        "math" to "数学",
                        "politics" to "政治",
                        "mechanics" to "机械",
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Settings03, contentDescription = null) },
                        headlineContent = { Text("学科") },
                        supportingContent = { Text("用于错题本与知识点卡片的科目分类") },
                        trailingContent = {
                            Select(
                                options = subjects,
                                selectedOption = assistant.studySubject,
                                onOptionSelected = { subject ->
                                    vm.update(assistant.copy(studySubject = subject))
                                },
                                modifier = Modifier.width(120.dp),
                                optionToString = { it -> subjectLabels[it] ?: it },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingChevron() {
    Icon(HugeIcons.ArrowRight01, contentDescription = null)
}
