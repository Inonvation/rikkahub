package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Bot
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

/**
 * 扩展管理二级页：聚合工作区、知识库、快捷消息、提示词、技能、Agent 动作、
 * 学习工具、信任文件夹、设备能力 9 个入口，逐条跳转到现有页面。
 */
@Composable
fun SettingExtensionsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    SettingListScaffold(
        title = stringResource(R.string.setting_page_extensions),
        loading = settings.init,
    ) {
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                item(
                    onClick = { navController.navigate(Screen.Workspaces) },
                    leadingContent = { Icon(HugeIcons.Folder01, null) },
                    headlineContent = { Text(stringResource(R.string.extensions_page_workspace)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.KnowledgeBases) },
                    leadingContent = { Icon(HugeIcons.Bookshelf01, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_knowledge_bases)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.QuickMessages) },
                    leadingContent = { Icon(HugeIcons.Zap, null) },
                    headlineContent = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.Prompts) },
                    leadingContent = { Icon(HugeIcons.Book03, null) },
                    headlineContent = { Text(stringResource(R.string.extensions_page_prompts)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.Skills) },
                    leadingContent = { Icon(HugeIcons.Puzzle, null) },
                    headlineContent = { Text(stringResource(R.string.extensions_page_agent_skills)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingAgentAction) },
                    leadingContent = { Icon(HugeIcons.Bot, null) },
                    headlineContent = { Text(stringResource(R.string.setting_agent_action)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingStudyTools) },
                    leadingContent = { Icon(HugeIcons.Settings03, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_study_tools)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.TrustedFolders) },
                    leadingContent = { Icon(HugeIcons.Folder01, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_trusted_folders)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDevice) },
                    leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_device_capability)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
    }
}
