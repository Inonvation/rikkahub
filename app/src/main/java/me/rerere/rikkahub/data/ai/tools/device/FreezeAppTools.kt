package me.rerere.rikkahub.data.ai.tools.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.device.DeviceToolPermission
import me.rerere.rikkahub.data.device.SafetyGuard
import me.rerere.rikkahub.data.shizuku.ShizukuCommandExecutor

internal fun buildFreezeAppTools(
    safetyGuard: SafetyGuard,
    permission: DeviceToolPermission,
): List<Tool> = listOf(
    buildFreezeAppTool(safetyGuard, permission),
    buildUnfreezeAppTool(safetyGuard, permission),
    buildListFrozenAppsTool(),
    buildFreezeBatchTool(safetyGuard, permission),
)

private fun errorResult(message: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", message) }.toString()))

private fun successResult(body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject(body).toString()))

private fun buildFreezeAppTool(safetyGuard: SafetyGuard, permission: DeviceToolPermission): Tool = Tool(
    name = "freeze_app",
    description = "冻结指定应用：图标消失、无法运行、不占后台，数据保留，解冻后恢复。需用户确认。系统关键应用与受保护应用（微信/QQ/支付宝等）会被拒绝。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("packageName", buildJsonObject {
                    put("type", "string")
                    put("description", "要冻结的应用包名")
                })
            },
            required = listOf("packageName")
        )
    },
    needsApproval = { _ -> permission.needsApproval("freeze_app") },
    execute = { params ->
        withContext(Dispatchers.IO) {
            val pkg = params.jsonObject["packageName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (pkg.isBlank()) return@withContext errorResult("缺少 packageName")
            if (permission.isForbidden("freeze_app")) return@withContext errorResult("冻结应用已被禁止使用")
            val reason = safetyGuard.checkFreeze(pkg)
            if (reason != null) return@withContext errorResult(reason)
            val result = ShizukuCommandExecutor.execute(
                listOf("pm", "disable-user", "--user", "0", pkg),
                allowWrite = true,
            )
            if (result.blocked || result.exitCode != 0) {
                return@withContext errorResult("冻结失败: ${result.stderr.ifBlank { result.stdout }}")
            }
            successResult {
                put("package", pkg)
                put("frozen", true)
                put("message", "已冻结 $pkg，解冻后数据恢复")
            }
        }
    },
)

private fun buildUnfreezeAppTool(safetyGuard: SafetyGuard, permission: DeviceToolPermission): Tool = Tool(
    name = "unfreeze_app",
    description = "解冻指定应用，恢复运行。需用户确认。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("packageName", buildJsonObject {
                    put("type", "string")
                    put("description", "要解冻的应用包名")
                })
            },
            required = listOf("packageName")
        )
    },
    needsApproval = { _ -> permission.needsApproval("unfreeze_app") },
    execute = { params ->
        withContext(Dispatchers.IO) {
            val pkg = params.jsonObject["packageName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (pkg.isBlank()) return@withContext errorResult("缺少 packageName")
            if (permission.isForbidden("unfreeze_app")) return@withContext errorResult("解冻应用已被禁止使用")
            val result = ShizukuCommandExecutor.execute(
                listOf("pm", "enable", pkg),
                allowWrite = true,
            )
            if (result.blocked || result.exitCode != 0) {
                return@withContext errorResult("解冻失败: ${result.stderr.ifBlank { result.stdout }}")
            }
            successResult {
                put("package", pkg)
                put("frozen", false)
                put("message", "已解冻 $pkg")
            }
        }
    },
)

private fun buildListFrozenAppsTool(): Tool = Tool(
    name = "list_frozen_apps",
    description = "列出当前已冻结（停用）的应用包名。只读工具。",
    parameters = { null },
    execute = { _ ->
        withContext(Dispatchers.IO) {
            val result = ShizukuCommandExecutor.execute(listOf("pm", "list", "packages", "-d"))
            if (result.blocked || result.exitCode != 0) {
                return@withContext errorResult("查询失败: ${result.stderr.ifBlank { result.stdout }}")
            }
            val packages = result.stdout.lineSequence()
                .mapNotNull { it.removePrefix("package:").trim().takeIf { p -> p.isNotEmpty() } }
                .toList()
            successResult {
                put("count", packages.size)
                put("packages", JsonArray(packages.map { JsonPrimitive(it) }))
            }
        }
    },
)

private fun buildFreezeBatchTool(safetyGuard: SafetyGuard, permission: DeviceToolPermission): Tool = Tool(
    name = "freeze_batch",
    description = "批量冻结多个应用。需用户确认，受保护应用会被跳过。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("packages", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "要冻结的应用包名列表")
                })
            },
            required = listOf("packages")
        )
    },
    needsApproval = { _ -> permission.needsApproval("freeze_batch") },
    execute = { params ->
        withContext(Dispatchers.IO) {
            val packages = params.jsonObject["packages"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() } ?: emptyList()
            if (packages.isEmpty()) return@withContext errorResult("缺少 packages")
            if (permission.isForbidden("freeze_batch")) return@withContext errorResult("批量冻结已被禁止使用")
            val frozen = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (pkg in packages) {
                val reason = safetyGuard.checkFreeze(pkg)
                if (reason != null) {
                    skipped.add("$pkg($reason)")
                    continue
                }
                val result = ShizukuCommandExecutor.execute(
                    listOf("pm", "disable-user", "--user", "0", pkg),
                    allowWrite = true,
                )
                if (result.blocked || result.exitCode != 0) {
                    failed.add(pkg)
                } else {
                    frozen.add(pkg)
                }
            }
            successResult {
                put("frozen", JsonArray(frozen.map { JsonPrimitive(it) }))
                put("skipped", JsonArray(skipped.map { JsonPrimitive(it) }))
                put("failed", JsonArray(failed.map { JsonPrimitive(it) }))
            }
        }
    },
)