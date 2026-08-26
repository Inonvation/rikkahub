package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.IosGroupScope
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.heroAnimation
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 助手设置 · 总览入口页。
 * 采用「单页分组入口」2 层结构：顶部为身份卡片，下方列出 6 大功能分组，
 * 每组直达对应页面，最多两级导航，避免旧的「基本信息/工具与服务」三级嵌套。
 *
 * 分组：① 身份与外观 / ② 模型与生成 / ③ 提示词与内容 / ④ 记忆与上下文 /
 * ⑤ 能力与工具 / ⑥ 高级与请求。每行展示实时摘要。
 */
@Composable
fun AssistantDetailPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpServers by vm.mcpServerConfigs.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    var searchQuery by remember { mutableStateOf("") }

    val enabledMcpCount = remember(assistant.mcpServers, mcpServers) {
        assistant.mcpServers.count { serverId -> mcpServers.any { it.id == serverId } }
    }

    data class GroupEntry(
        val title: String,
        val summary: String,
        val icon: ImageVector,
        val section: String,
        val onClick: () -> Unit,
    )
    val groups = listOf(
        GroupEntry("模型与生成", modelSummary(assistant), HugeIcons.Settings03, "core") {
            navController.navigate(Screen.AssistantModel(id))
        },
        GroupEntry("提示词与内容", promptSummary(assistant), HugeIcons.Message02, "core") {
            navController.navigate(Screen.AssistantPrompt(id))
        },
        GroupEntry("记忆与上下文", memorySummary(assistant), HugeIcons.Brain02, "core") {
            navController.navigate(Screen.AssistantMemory(id))
        },
        GroupEntry("身份与外观", basicSummary(assistant), HugeIcons.Puzzle, "more") {
            navController.navigate(Screen.AssistantIdentity(id))
        },
        GroupEntry("能力与工具", toolsSummary(assistant, settings, enabledMcpCount), HugeIcons.Wrench01, "more") {
            navController.navigate(Screen.AssistantTools(id))
        },
        GroupEntry("高级与请求", requestSummary(assistant), HugeIcons.Code, "more") {
            navController.navigate(Screen.AssistantRequest(id))
        },
    )
    val filtered = groups.filter {
        searchQuery.isBlank() ||
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.summary.contains(searchQuery, ignoreCase = true)
    }

    SettingListScaffold(
        title = "助手设置",
        loading = settings.init,
    ) {
        item {
            AssistantHeader(
                assistant = assistant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("搜索设置项") },
                leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
        }
        item {
            IosGroup(
                title = "核心设置",
                subtitle = "最常调整的能力",
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                filtered.filter { it.section == "core" }.forEach { entry ->
                    summaryItem(entry.title, entry.summary, entry.icon, entry.onClick)
                }
            }
        }
        item {
            IosGroup(
                title = "更多",
                subtitle = "身份外观、能力与高级项",
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                filtered.filter { it.section == "more" }.forEach { entry ->
                    summaryItem(entry.title, entry.summary, entry.icon, entry.onClick)
                }
            }
        }
    }
}

@Composable
private fun AssistantHeader(
    assistant: Assistant,
    modifier: Modifier = Modifier,
) {
    val name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UIAvatar(
            value = assistant.avatar,
            name = name,
            onUpdate = null,
            modifier = Modifier
                .size(56.dp)
                .heroAnimation("assistant_${assistant.id}"),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (assistant.systemPrompt.isNotBlank()) {
                Text(
                    text = assistant.systemPrompt.take(60) + if (assistant.systemPrompt.length > 60) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- 分组摘要（供总览页与二级入口页共用） ----

internal fun basicSummary(assistant: Assistant): String =
    "标签 ${assistant.tags.size} 个 · ${appearanceSummary(assistant)}"

internal fun modelSummary(assistant: Assistant): String {
    val model = if (assistant.chatModelId == null) "跟随全局聊天模型" else "已指定聊天模型"
    val mode = assistant.defaultMode?.let { "模式：$it" } ?: "跟随全局默认模式"
    return "$model · $mode"
}

internal fun promptSummary(assistant: Assistant): String =
    if (assistant.systemPrompt.isBlank()) {
        "未设置系统提示词 · 预置消息 ${assistant.presetMessages.size} 条"
    } else {
        "已设置系统提示词 · ${assistant.systemPrompt.length} 字"
    }

internal fun memorySummary(assistant: Assistant): String = when {
    !assistant.enableMemory -> "记忆未启用"
    assistant.useGlobalMemory -> "已启用 · 使用全局记忆"
    else -> "已启用 · 助手独立记忆"
}

internal fun appearanceSummary(assistant: Assistant): String = when {
    assistant.background != null && assistant.useGradientBackground -> "图片背景 · 渐变已启用"
    assistant.background != null -> "已设置背景图片"
    assistant.useGradientBackground -> "渐变背景已启用"
    else -> "使用默认聊天外观"
}

internal fun toolsSummary(
    assistant: Assistant,
    settings: Settings,
    enabledMcpCount: Int,
): String = buildString {
    append(if (assistant.enableWebSearch) "联网已启用" else "联网未启用")
    append(" · ")
    append(if (assistant.knowledgeBaseIds.isEmpty()) "知识库 0" else "知识库 ${assistant.knowledgeBaseIds.size}")
    append(" · ")
    append(if (assistant.workspaceId == null) "工作区未绑定" else "工作区已绑定")
    if (settings.enableMcpManager && enabledMcpCount > 0) append(" · MCP $enabledMcpCount")
}

internal fun localToolsSummary(assistant: Assistant): String =
    if (assistant.localTools.isEmpty()) "未启用本地工具" else "已启用 ${assistant.localTools.size} 项"

internal fun mcpSummary(settings: Settings, enabledMcpCount: Int): String =
    if (!settings.enableMcpManager) "全局 MCP 管理器未启用" else "已绑定 $enabledMcpCount 个 MCP 服务"

internal fun extensionsSummary(assistant: Assistant, enabledSkillCount: Int): String =
    "Skills $enabledSkillCount 个 · 快捷消息 ${assistant.quickMessageIds.size} 条 · 注入 ${assistant.modeInjectionIds.size + assistant.lorebookIds.size} 项"

internal fun requestSummary(assistant: Assistant): String =
    "请求头 ${assistant.customHeaders.size} 项 · 请求体 ${assistant.customBodies.size} 项"

// ---- 列表项 DSL ----

internal fun IosGroupScope.settingItem(
    headline: String,
    supporting: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    item(
        onClick = onClick,
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(headline) },
        supportingContent = {
            Text(
                text = supporting,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
    )
}

internal fun IosGroupScope.summaryItem(
    headline: String,
    supporting: String,
    icon: ImageVector,
    onClick: () -> Unit,
) = settingItem(headline, supporting, icon, onClick)
