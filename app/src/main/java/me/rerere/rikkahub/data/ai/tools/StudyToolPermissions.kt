package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.datastore.Settings

/**
 * 学习工具的运行时权限配置。
 *
 * @param editEnabled 是否允许 AI 修改学习内容
 * @param deleteEnabled 是否允许 AI 删除学习内容
 * @param deleteApprovalEnabled 删除操作是否需要用户审批
 * @param statsEnabled 是否允许 AI 使用统计/总结/思维导图工具
 * @param approvalOverrides 按工具名覆盖默认审批策略
 *
 * 注意：学科隔离（subjectScope）不在本类存储，由 [StudyTools.getTools] 根据助手 studySubject 计算后
 * 直接传给各工具工厂函数（search/read/update/delete/stats），避免本类承载两份不一致的学科值。
 */
data class StudyToolPermissions(
    val editEnabled: Boolean = true,
    val deleteEnabled: Boolean = false,
    val deleteApprovalEnabled: Boolean = true,
    val statsEnabled: Boolean = true,
    val approvalOverrides: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        fun fromSettings(settings: Settings): StudyToolPermissions = StudyToolPermissions(
            editEnabled = settings.studyEditEnabled,
            deleteEnabled = settings.studyDeleteEnabled,
            deleteApprovalEnabled = settings.studyDeleteApprovalEnabled,
            statsEnabled = settings.studyStatsEnabled,
            approvalOverrides = settings.studyToolApprovalOverrides,
        )
    }
}

/**
 * 判断指定学习工具是否需要用户审批。
 */
fun StudyToolPermissions.needsApproval(name: String): Boolean {
    approvalOverrides[name]?.let { return it }
    return when {
        name.startsWith("delete_") -> deleteEnabled && deleteApprovalEnabled
        else -> false
    }
}
