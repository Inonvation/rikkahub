package me.rerere.rikkahub.data.ai.tools.device

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.shizuku.ShizukuCommandExecutor
import me.rerere.rikkahub.data.shizuku.ShizukuCommandResult

internal fun buildDeviceDoctorTools(context: Context): List<Tool> = listOf(
    buildDeviceInfoTool(context),
    buildBatteryReportTool(context),
    buildStoragePressureTool(),
    buildDiagnoseReportTool(context),
)

private fun textResult(json: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(json.toString()))

/** 执行 Shizuku 只读命令，返回 stdout；失败返回 null */
internal fun runShizuku(vararg cmd: String): String? {
    val result: ShizukuCommandResult = ShizukuCommandExecutor.execute(cmd.toList())
    if (result.blocked || result.exitCode != 0 || result.stderr.isNotBlank() && result.stdout.isBlank()) {
        return null
    }
    return result.stdout.trim()
}

/** 解析 df -h 输出中挂载点匹配的行，返回 大小/已用/可用/使用率 */
private fun parseDfLine(output: String, mount: String): JsonObject? {
    for (line in output.lineSequence()) {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 6 && parts.last() == mount) {
            return buildJsonObject {
                put("filesystem", parts[0])
                put("size", parts[1])
                put("used", parts[2])
                put("available", parts[3])
                put("usePercent", parts[4])
                put("mountedOn", parts[5])
            }
        }
    }
    return null
}

private fun buildDeviceInfoTool(context: Context): Tool = Tool(
    name = "device_info",
    description = "获取设备基本信息：厂商、型号、Android 版本、内存、存储概览。只读工具。",
    parameters = { null },
    execute = { _ ->
        withContext(Dispatchers.IO) {
            val memInfo = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(memInfo)
            val df = runShizuku("df", "-h")
            val data = df?.let { parseDfLine(it, "/data") }
            textResult(
                buildJsonObject {
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("sdkInt", Build.VERSION.SDK_INT)
                    put("totalMemoryMb", memInfo.totalMem / 1024 / 1024)
                    put("availableMemoryMb", memInfo.availMem / 1024 / 1024)
                    put("lowMemory", memInfo.lowMemory)
                    put("dataPartition", data?.let { it as JsonElement } ?: JsonObject(emptyMap()))
                }
            )
        }
    },
)

private fun buildBatteryReportTool(context: Context): Tool = Tool(
    name = "battery_report",
    description = "获取电池状态：电量、充电状态、温度、电压，以及系统电量原始信息。只读工具。",
    parameters = { null },
    execute = { _ ->
        withContext(Dispatchers.IO) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val raw = runShizuku("dumpsys", "battery")
            textResult(
                buildJsonObject {
                    put("levelPercent", level)
                    put("charging", bm.isCharging)
                    put("rawDumpsys", raw ?: "无法读取 dumpsys battery")
                }
            )
        }
    },
)

private fun buildStoragePressureTool(): Tool = Tool(
    name = "storage_pressure",
    description = "分析存储压力：数据分区与外部存储的使用率，判断是否接近满载。只读工具。",
    parameters = { null },
    execute = { _ ->
        withContext(Dispatchers.IO) {
            val df = runShizuku("df", "-h")
            if (df == null) {
                return@withContext textResult(buildJsonObject { put("error", "无法读取存储信息，请确认 Shizuku 已授权") })
            }
            val data = parseDfLine(df, "/data")
            val emulated = parseDfLine(df, "/storage/emulated")
            textResult(
                buildJsonObject {
                    put("dataPartition", data?.let { it as JsonElement } ?: JsonObject(emptyMap()))
                    put("emulatedStorage", emulated?.let { it as JsonElement } ?: JsonObject(emptyMap()))
                }
            )
        }
    },
)

private fun buildDiagnoseReportTool(context: Context): Tool = Tool(
    name = "diagnose_report",
    description = "一键诊断：汇总设备信息、电池、存储、内存占用 Top 进程，输出结构化报告供 AI 判断手机状态。只读工具。",
    parameters = { null },
    execute = { _ ->
        withContext(Dispatchers.IO) {
            val memInfo = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(memInfo)
            val df = runShizuku("df", "-h")
            val ps = runShizuku("ps", "-A", "-o", "PID,RSS,NAME")
            textResult(
                buildJsonObject {
                    put("model", Build.MODEL)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("totalMemoryMb", memInfo.totalMem / 1024 / 1024)
                    put("availableMemoryMb", memInfo.availMem / 1024 / 1024)
                    put("lowMemory", memInfo.lowMemory)
                    put("dataPartition", df?.let { parseDfLine(it, "/data") }?.let { it as JsonElement } ?: JsonObject(emptyMap()))
                    put("topProcesses", ps ?: "")
                }
            )
        }
    },
)