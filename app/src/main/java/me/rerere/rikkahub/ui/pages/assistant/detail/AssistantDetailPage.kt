package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.IdentityCard
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.Message02
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
 * 顶部为身份卡片，下方为一个并列目录直达各设置页，最多两级导航。
 *
 * 目录：模型与生成 / 提示词与内容 / 记忆与上下文 / 身份与外观 /
 * 能力与工具 / 本地工具 / 高级与请求。每行展示实时摘要。
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

    val enabledMcpCount = remember(assistant.mcpServers, mcpServers) {
        assistant.mcpServers.count { serverId -> mcpServers.any { it.id == serverId } }
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
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                settingItem("模型与生成", modelSummary(assistant), HugeIcons.Settings03) {
                    navController.navigate(Screen.AssistantModel(id))
                }
                settingItem("提示词与内容", promptSummary(assistant), HugeIcons.Message02) {
                    navController.navigate(Screen.AssistantPrompt(id))
                }
                settingItem("记忆与上下文", memorySummary(assistant), HugeIcons.Brain02) {
                    navController.navigate(Screen.AssistantMemory(id))
                }
                settingItem("身份与外观", basicSummary(assistant), HugeIcons.IdentityCard) {
                    navController.navigate(Screen.AssistantIdentity(id))
                }
                settingItem("能力与工具", toolsSummary(assistant, settings, enabledMcpCount), HugeIcons.Wrench01) {
                    navController.navigate(Screen.AssistantTools(id))
                }
                settingItem("本地工具", localToolsSummary(assistant), HugeIcons.JavaScript) {
                    navController.navigate(Screen.AssistantLocalTool(id))
                }
                settingItem("高级与请求", requestSummary(assistant), HugeIcons.Code) {
                    navController.navigate(Screen.AssistantRequest(id))
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
    append(" · ")
    append(if (assistant.trustedFolderProjectId == null) "信任文件夹未绑定" else "信任文件夹已绑定")
    // MCP 使用只看助手绑定（mcpServers），与全局管理开关解耦
    if (enabledMcpCount > 0) append(" · MCP $enabledMcpCount")
}

internal fun localToolsSummary(assistant: Assistant): String =
    if (assistant.localTools.isEmpty()) "未启用本地工具" else "已启用 ${assistant.localTools.size} 项"

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
