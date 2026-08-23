package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.shizuku.ShizukuService

/**
 * 设备工具族装配：只返回当前助手启用的设备工具。
 *
 * 注入闸门：Shizuku 未就绪时不注入任何设备工具声明，避免模型拿到不可用的工具。
 * 模式层面的控制由 ChatMode 的 DEVICE_TOOLS 能力负责，助手层面的控制由
 * Assistant.localTools 负责。
 */
class DeviceTools(context: Context) {
    private val deviceDoctorTools = buildDeviceDoctorTools(context)
    private val storageCleanerTools = buildStorageCleanerTools()

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        if (!ShizukuService.isReady()) return emptyList()
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.DeviceDoctor)) {
            tools.addAll(deviceDoctorTools)
        }
        if (options.contains(LocalToolOption.StorageCleaner)) {
            tools.addAll(storageCleanerTools)
        }
        // FreezeApps 在 Phase 2 接入
        return tools
    }
}