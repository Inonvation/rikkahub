package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.model.ModeRefs

@Composable
fun ChatMode.displayName(): String = stringResource(displayNameRes())

fun ChatMode.displayNameRes(): Int = when (this) {
    ChatMode.STANDARD -> R.string.chat_mode_standard
    ChatMode.PTC -> R.string.chat_mode_ptc
    ChatMode.MINIMAL -> R.string.chat_mode_minimal
    ChatMode.CREATIVE -> R.string.chat_mode_creative
}

@Composable
fun ChatMode.description(): String = stringResource(descriptionRes())

fun ChatMode.descriptionRes(): Int = when (this) {
    ChatMode.STANDARD -> R.string.chat_mode_standard_desc
    ChatMode.PTC -> R.string.chat_mode_ptc_desc
    ChatMode.MINIMAL -> R.string.chat_mode_minimal_desc
    ChatMode.CREATIVE -> R.string.chat_mode_creative_desc
}

/** 把模式引用显示为名字：内置枚举名、自定义模式名或原始引用。 */
@Composable
fun modeRefDisplayName(
    ref: String?,
    customModes: List<CustomModeConfig>,
    builtinModeOverrides: Map<ChatMode, ChatModePolicy> = emptyMap(),
): String {
    if (ref.isNullOrBlank()) return stringResource(R.string.chat_mode_follow_global)
    if (ref.startsWith(ModeRefs.CUSTOM_PREFIX)) {
        return customModes.find { it.id == ref.removePrefix(ModeRefs.CUSTOM_PREFIX) }
            ?.name
            ?.ifBlank { ref }
            ?: stringResource(R.string.mode_deleted)
    }
    return ModeRefs.parseBuiltin(ref)?.let { mode ->
        val name = mode.displayName()
        if (mode in builtinModeOverrides) name + stringResource(R.string.mode_modified_suffix) else name
    } ?: ref
}

/** 能力项中文注释：设置页「组装内容」与管理模式展示用。 */
fun Capability.note(): String = when (this) {
    Capability.LOCAL_TOOLS -> "本地工具族（时间/剪贴板/JS 等）"
    Capability.SEARCH -> "联网搜索工具族"
    Capability.DOCUMENT -> "附件文档解析与 OCR 注入"
    Capability.WORKSPACE -> "workspace 工具族 + AGENTS.md/工作区环境说明注入"
    Capability.TRUSTED_FOLDER -> "信任文件夹工具族 + 环境说明注入"
    Capability.SKILL_USE -> "use_skill（已启用 skill 的使用）"
    Capability.SKILL_ADMIN -> "skill_admin_*（感知与配置 skill）"
    Capability.MCP_USE -> "外部 MCP 工具（mcp__* 直接注入）"
    Capability.MCP_ADMIN -> "mcp_admin_*（感知与配置 MCP）"
    Capability.MEMORY -> "记忆工具与记忆提示词"
    Capability.TODO -> "todo 工具"
    Capability.SUBAGENT -> "子代理工具"
    Capability.DEVICE_TOOLS -> "设备工具族（诊断/存储/冻结，依赖 Shizuku）"
    Capability.STUDY -> "学习工具（生词/笔记/错题/知识卡/测验）"
    Capability.HISTORY -> "历史对话引用/会话搜索"
    Capability.KNOWLEDGE -> "知识库检索"
    Capability.PROMPT_INJECTION -> "模式注入/lorebook 提示词注入"
    Capability.REMINDERS -> "时间提醒/todo 提醒"
    Capability.TOOL_SYSTEM_PROMPT -> "tool.systemPrompt 循环"
    Capability.AGENT_BEHAVIOR_PROMPT -> "agent behavior 行为层提示词"
    Capability.CREATIVE_TOOLS -> "env_inspect/app_logs/provider_add/mode_create/mode_update/mode_delete"
    Capability.PROVIDER_ADMIN -> "provider 增删改查与连通性测试"
    Capability.ASSISTANT_ADMIN -> "助手增删改查与配置修改"
    Capability.SETTINGS_ADMIN -> "全局设置读写"
    Capability.DATA_ADMIN -> "搜索服务管理与配置盘点"
}

/**
 * 模式选择底部弹窗：内置四模式 + 管理模式生成的自定义模式（尾部）。
 * [selectedRef] 为会话内模式引用（枚举名 / custom:<id> / null=跟随全局）。
 */
@Composable
fun ModePickerSheet(
    selectedRef: String?,
    customModes: List<CustomModeConfig>,
    builtinModeOverrides: Map<ChatMode, ChatModePolicy> = emptyMap(),
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    showFollowGlobal: Boolean = false,
    followAssistantSummary: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.mode_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (showFollowGlobal) {
                ModeOptionRow(
                    name = stringResource(R.string.chat_mode_follow_global),
                    description = stringResource(R.string.chat_mode_follow_global_desc),
                    summary = followAssistantSummary,
                    selected = selectedRef == null,
                    onClick = { onSelect(null) },
                )
            }
            ChatMode.entries.forEach { mode ->
                val modified = mode in builtinModeOverrides
                ModeOptionRow(
                    name = if (modified) {
                        mode.displayName() + stringResource(R.string.mode_modified_suffix)
                    } else {
                        mode.displayName()
                    },
                    description = mode.description(),
                    selected = selectedRef == mode.name,
                    onClick = { onSelect(ModeRefs.builtin(mode)) },
                )
            }
            customModes.forEach { custom ->
                ModeOptionRow(
                    name = custom.name.ifBlank { custom.id },
                    description = custom.description,
                    selected = selectedRef == ModeRefs.custom(custom.id),
                    onClick = { onSelect(ModeRefs.custom(custom.id)) },
                )
            }
        }
    }
}

@Composable
private fun ModeOptionRow(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = HugeIcons.CheckmarkCircle01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}
