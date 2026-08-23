package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.device.DeviceToolPermission
import me.rerere.rikkahub.data.device.SafetyGuard
import me.rerere.rikkahub.data.shizuku.ShizukuService

/**
 * 设备工具族装配：模式开启 DEVICE_TOOLS 能力时注入全部设备工具。
 *
 * 模式层面的控制由 ChatMode 的 DEVICE_TOOLS 能力负责，不再依赖助手 localTools，
 * 保证管理模式（CREATIVE）下 AI 一定能拿到设备工具。写工具（冻结、清理）执行时
 * 经过 SafetyGuard 校验，并通过 DeviceToolPermission 三档审批。
 */
class DeviceTools(
    context: Context,
    private val safetyGuard: SafetyGuard,
    private val permission: DeviceToolPermission,
) {
    private val deviceDoctorTools = buildDeviceDoctorTools(context)
    private val storageCleanerTools = buildStorageCleanerTools(permission)
    private val freezeAppTools = buildFreezeAppTools(safetyGuard, permission)

    /** 模式驱动：返回全部设备工具；Shizuku 未就绪时不注入 */
    fun getAllTools(): List<Tool> {
        if (!ShizukuService.isReady()) return emptyList()
        return deviceDoctorTools + storageCleanerTools + freezeAppTools
    }
}