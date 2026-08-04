package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import org.koin.androidx.compose.koinViewModel

/** 可单独覆盖审批策略的 delete_* 工具 */
private val DELETE_TOOL_NAMES = listOf(
    "delete_vocabulary",
    "delete_note",
    "delete_wrong_question",
    "delete_knowledge_card",
)

private val DELETE_TOOL_LABELS = mapOf(
    "delete_vocabulary" to "删除生词",
    "delete_note" to "删除笔记",
    "delete_wrong_question" to "删除错题",
    "delete_knowledge_card" to "删除知识点",
)

/**
 * 学习工具权限设置页。
 * 控制 AI 对学习内容的修改/删除/统计能力，以及删除工具的按工具审批覆盖。
 */
@Composable
fun SettingStudyToolsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    SettingListScaffold(
        title = "学习工具",
        loading = settings.init,
    ) {
        // 权限
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = "权限",
            ) {
                item(
                    headlineContent = { Text("允许 AI 修改学习内容") },
                    supportingContent = { Text("开启后 AI 可使用更新工具修改已保存的生词/笔记/错题/知识点") },
                    trailingContent = {
                        Switch(
                            checked = settings.studyEditEnabled,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(studyEditEnabled = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text("允许 AI 删除学习内容") },
                    supportingContent = { Text("开启后 AI 可归档或彻底删除已保存的学习内容") },
                    trailingContent = {
                        Switch(
                            checked = settings.studyDeleteEnabled,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(studyDeleteEnabled = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text("删除需审批") },
                    supportingContent = { Text("删除操作默认需你手动确认") },
                    trailingContent = {
                        Switch(
                            checked = settings.studyDeleteApprovalEnabled,
                            enabled = settings.studyDeleteEnabled,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(studyDeleteApprovalEnabled = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text("允许 AI 统计/总结/思维导图") },
                    supportingContent = { Text("开启后 AI 可使用学习统计、总结与思维导图工具（只读）") },
                    trailingContent = {
                        Switch(
                            checked = settings.studyStatsEnabled,
                            onCheckedChange = {
                                vm.updateSettings(settings.copy(studyStatsEnabled = it))
                            }
                        )
                    },
                )
            }
        }

        // 按工具审批覆盖
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = "按工具审批覆盖",
            ) {
                DELETE_TOOL_NAMES.forEach { name ->
                    val overrideChecked = settings.studyToolApprovalOverrides[name]
                        ?: (settings.studyDeleteEnabled && settings.studyDeleteApprovalEnabled)
                    item(
                        headlineContent = { Text(DELETE_TOOL_LABELS[name] ?: name) },
                        supportingContent = { Text("开启后删除该类型内容需审批") },
                        trailingContent = {
                            Switch(
                                checked = overrideChecked,
                                onCheckedChange = { on ->
                                    vm.updateSettings(
                                        settings.copy(
                                            studyToolApprovalOverrides = settings.studyToolApprovalOverrides + (name to on)
                                        )
                                    )
                                }
                            )
                        },
                    )
                }
            }
        }

        // 说明
        item {
            Text(
                text = "覆盖后将以这里为准，不受上方开关影响",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
