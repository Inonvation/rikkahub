package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.icons.DiscordIcon
import me.rerere.rikkahub.ui.components.ui.icons.TencentQQIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.utils.joinQQGroup
import me.rerere.rikkahub.utils.openUrl
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()

    SettingListScaffold(
        title = stringResource(R.string.settings),
    ) {
        if (settings.isNotConfigured()) {
            item {
                ProviderConfigWarningCard(navController)
            }
        }

        // 外观
        item("appearance") {
                IosGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = stringResource(R.string.setting_page_appearance),
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingAppearance) },
                        leadingContent = { Icon(HugeIcons.Sun01, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_color_and_theme)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesUI) },
                        leadingContent = { Icon(HugeIcons.PaintBoard, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_ui)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesGeneral) },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_behavior)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesNotification) },
                        leadingContent = { Icon(HugeIcons.Notification01, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_notifications)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }

            // AI 服务
            item("aiServices") {
                IosGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = stringResource(R.string.setting_page_ai_services),
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { Icon(HugeIcons.LookTop, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { Icon(HugeIcons.AiMagic, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { Icon(HugeIcons.Brain02, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { Icon(HugeIcons.McpServer, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
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
                        onClick = { navController.navigate(Screen.SettingSpeech) },
                        leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeb) },
                        leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }

            // 数据
            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                IosGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = stringResource(R.string.setting_page_data_settings),
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { Icon(HugeIcons.Database02, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { Icon(HugeIcons.ImageUpload, null) },
                        headlineContent = {
                            if (storageState.first == -1) {
                                Text(stringResource(R.string.setting_page_chat_storage))
                            } else {
                                Text(
                                    stringResource(
                                        R.string.setting_page_chat_storage_desc,
                                        storageState.first,
                                        storageState.second / 1024 / 1024.0
                                    )
                                )
                            }
                        },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }

            // 关于
            item("aboutSettings") {
                val context = LocalContext.current
                IosGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = stringResource(R.string.setting_page_about_community),
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingAbout) },
                        leadingContent = { Icon(HugeIcons.Clapping01, null) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                var showQQGroupSheet by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showQQGroupSheet = true }
                                ) {
                                    Icon(
                                        imageVector = TencentQQIcon,
                                        contentDescription = "QQ",
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (showQQGroupSheet) {
                                    QQGroupBottomSheet(
                                        onDismiss = { showQQGroupSheet = false }
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        context.openUrl("https://discord.gg/9weBqxe5c4")
                                    }
                                ) {
                                    Icon(
                                        imageVector = DiscordIcon,
                                        contentDescription = "Discord",
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_about)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Log) },
                        leadingContent = { Icon(HugeIcons.Bookshelf01, null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_request_logs)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
    }
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.setting_page_config_api_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(HugeIcons.Alert01, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

private data class QQGroup(
    val name: String,
    val key: String,
)

private val QQ_GROUPS = listOf(
    QQGroup("RikkaHub 一群", "4POE46u9e_zoy1TkNfWdCvueR9CKFJdk"),
    QQGroup("RikkaHub 二群", "Qsm0whzbPsm1UyNpR683ulLyMZ2Pqrw0"),
    QQGroup("RikkaHub 三群", "Qc9oP-9tXioZeQEvEvI2_owWtBAIx3lS"),
)

@Composable
private fun QQGroupBottomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QQ_GROUPS.forEach { group ->
                ListItem(
                    headlineContent = { Text(group.name) },
                    leadingContent = {
                        Icon(
                            imageVector = TencentQQIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    },
                    modifier = Modifier.clickable {
                        context.joinQQGroup(group.key)
                        onDismiss()
                    }
                )
            }
        }
    }
}