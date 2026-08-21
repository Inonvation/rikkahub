package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.resolveModePolicy
import me.rerere.rikkahub.data.model.restrictedCapabilities
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSettings
import me.rerere.rikkahub.ui.components.ai.modeRefDisplayName
import me.rerere.rikkahub.ui.components.ai.note
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ManagementPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trustedFolderRepository: TrustedFolderRepository = koinInject()
    val trustedSettings by trustedFolderRepository.settingsFlow
        .collectAsState(initial = TrustedFolderSettings())
    val conversationRepository: ConversationRepository = koinInject()
    val mcpManager: McpManager = koinInject()
    val mcpStatus by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val auditStore: ManagementAuditStore = koinInject()
    val auditEntries by auditStore.entries.collectAsStateWithLifecycle()
    var conversationCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        conversationCount = conversationRepository.countConversations()
    }

    val currentAssistant = settings.getCurrentAssistant()
    val modelCount = settings.providers.sumOf { it.models.size }
    val knowledgeCount = settings.assistants.flatMap { it.knowledgeBaseIds }.distinct().size
    val workspaceCount = settings.assistants.count { it.workspaceId != null }
    val modeRef = currentAssistant.defaultMode ?: settings.defaultMode
    val modePolicy = resolveModePolicy(modeRef, settings) ?: ChatModePolicy.UNRESTRICTED
    val restricted = modePolicy.restrictedCapabilities(settings)
    val managementCapabilities = Capability.entries.filter {
        it.managementOnly && it in modePolicy.capabilities
    }
    val mcpConnected = settings.mcpServers.count { mcpStatus[it.id] is McpStatus.Connected }
    val mcpErrorCount = settings.mcpServers.count { mcpStatus[it.id] is McpStatus.Error }
    val auditFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    SettingListScaffold(
        title = stringResource(R.string.setting_page_management_console),
        loading = settings.init,
    ) {
        item("status") {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_page_console_status),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    supportingContent = {
                        Text("${settings.providers.size} 个提供商 · $modelCount 个模型")
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                    supportingContent = { Text("${settings.assistants.size} 个助手") },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                    supportingContent = {
                        Text(
                            if (settings.mcpServers.isEmpty()) {
                                "未配置"
                            } else if (mcpErrorCount > 0) {
                                "$mcpConnected/${settings.mcpServers.size} 已连接 · $mcpErrorCount 个异常"
                            } else {
                                "$mcpConnected/${settings.mcpServers.size} 已连接"
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    supportingContent = { Text("${settings.searchServices.size} 个服务") },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.extensions_page_agent_skills)) },
                    supportingContent = { Text("${currentAssistant.enabledSkills.size} 个已启用") },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_knowledge_bases)) },
                    supportingContent = { Text("$knowledgeCount 个已绑定") },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.extensions_page_workspace)) },
                    supportingContent = { Text("$workspaceCount 个已绑定") },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_trusted_folders)) },
                    supportingContent = {
                        Text("${trustedSettings.projects.size} 个项目" +
                            if (trustedSettings.activeProjectId != null) " · 已激活" else "")
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_conversation_history)) },
                    supportingContent = { Text((conversationCount?.toString() ?: "-") + " 个会话") },
                )
            }
        }

        item("permissions") {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_page_console_permissions),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_default_mode)) },
                    supportingContent = {
                        Text(
                            modeRefDisplayName(
                                modeRef,
                                settings.customModes,
                                settings.builtinModeOverrides,
                            )
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_console_management_capabilities)) },
                    supportingContent = {
                        Text(
                            if (managementCapabilities.isEmpty()) {
                                stringResource(R.string.setting_page_console_no_permission)
                            } else {
                                managementCapabilities.joinToString("、") { it.note() }
                            }
                        )
                    },
                )
                if (restricted.isNotEmpty()) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_console_restricted)) },
                        supportingContent = {
                            Text(restricted.sortedBy { it.name }.joinToString("、") { it.note() })
                        },
                    )
                }
            }
        }

        item("audit") {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = stringResource(R.string.setting_page_console_audit),
            ) {
                if (auditEntries.isEmpty()) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_console_no_audit)) },
                    )
                } else {
                    auditEntries.take(8).forEach { entry ->
                        item(
                            headlineContent = { Text(entry.tool) },
                            supportingContent = {
                                Text(
                                    "[${auditFormatter.format(Date(entry.timestamp))}] " +
                                        "${entry.target} · ${entry.result}"
                                )
                            },
                        )
                    }
                }
            }
        }

    }
}
