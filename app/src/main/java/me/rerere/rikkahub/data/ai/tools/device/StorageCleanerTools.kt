package me.rerere.rikkahub.data.ai.tools.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.device.DeviceToolPermission
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.data.shizuku.ShizukuCommandExecutor

internal fun buildStorageCleanerTools(permission: DeviceToolPermission, auditStore: ManagementAuditStore): List<Tool> = listOf(
    buildStorageOverviewTool(),
    buildScanLargeFilesTool(),
    buildScanCacheTool(),
    buildCleanCacheTool(permission, auditStore),
    buildCleanFilesTool(permission, auditStore),
)

private fun runShizuku(timeoutMillis: Long = 30_000L, vararg cmd: String): String? {
    val result = ShizukuCommandExecutor.execute(cmd.toList(), timeoutMillis = timeoutMillis)
    if (result.blocked || result.exitCode != 0) return null
    return result.stdout.trim()
}

private fun buildStorageOverviewTool(): Tool = Tool(
    name = "storage_overview",
    description = "存储总览：列出所有分区的大小、已用、可用、使用率。只读工具。",
    parameters = { null },
    execute = { _ ->
        withContext(Dispatchers.IO) {
            val output = runShizuku("df", "-h")
            if (output == null) {
                return@withContext listOf(UIMessagePart.Text(buildJsonObject { put("error", "无法读取存储信息，请确认 Shizuku 已授权") }.toString()))
            }
            val partitions = buildJsonArray {
                for (line in output.lineSequence().drop(1)) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 6) {
                        add(
                            buildJsonObject {
                                put("filesystem", parts[0])
                                put("size", parts[1])
                                put("used", parts[2])
                                put("available", parts[3])
                                put("usePercent", parts[4])
                                put("mountedOn", parts[5])
                            }
                        )
                    }
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject { put("partitions", partitions) }.toString()))
        }
    },
)

private fun buildScanLargeFilesTool(): Tool = Tool(
    name = "scan_large_files",
    description = "扫描指定目录下的大文件，返回路径列表。默认扫描 /sdcard 下大于 100MB 的文件。只读工具。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "扫描根目录，默认 /sdcard")
                })
                put("minSizeMb", buildJsonObject {
                    put("type", "integer")
                    put("description", "最小文件大小（MB），默认 100")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "最多返回条数，默认 20")
                })
            }
        )
    },
    execute = { params ->
        withContext(Dispatchers.IO) {
            val path = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                .ifBlank { "/sdcard" }
            val minSizeMb = params.jsonObject["minSizeMb"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100
            val limit = params.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20
            val sizeArg = "+${minSizeMb.coerceAtLeast(1)}M"
            val output = runShizuku(timeoutMillis = 60_000L, "find", path, "-type", "f", "-size", sizeArg)
            if (output == null) {
                return@withContext listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "扫描失败，请确认目录存在且 Shizuku 已授权")
                }.toString()))
            }
            val files = output.lineSequence().filter { it.isNotBlank() }.take(limit.coerceIn(1, 200)).toList()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("path", path)
                put("minSizeMb", minSizeMb)
                put("count", files.size)
                put("files", JsonArray(files.map { JsonPrimitive(it) }))
            }.toString()))
        }
    },
)

private fun buildScanCacheTool(): Tool = Tool(
    name = "scan_cache",
    description = "统计各应用缓存大小（来自 dumpsys diskstats），按缓存占用排序返回前 N 个。只读工具。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "最多返回条数，默认 15")
                })
            }
        )
    },
    execute = { params ->
        withContext(Dispatchers.IO) {
            val limit = params.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15
            val output = runShizuku(timeoutMillis = 30_000L, "dumpsys", "diskstats")
            if (output == null) {
                return@withContext listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "无法读取缓存统计，请确认 Shizuku 已授权或系统支持")
                }.toString()))
            }
            val regex = Regex("""^\s*([\w.]+): Code=(\d+), Data=(\d+), Cache=(\d+)""")
            val entries = output.lineSequence().mapNotNull { line ->
                regex.find(line)?.groupValues?.let { g ->
                    buildJsonObject {
                        put("package", g[1])
                        put("codeBytes", g[2].toLongOrNull() ?: 0L)
                        put("dataBytes", g[3].toLongOrNull() ?: 0L)
                        put("cacheBytes", g[4].toLongOrNull() ?: 0L)
                    }
                }
            }.sortedByDescending { it["cacheBytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L }
                .take(limit.coerceIn(1, 100))
                .toList()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("count", entries.size)
                put("topByCache", JsonArray(entries))
            }.toString()))
        }
    },
)
/** 允许删除的存储前缀 */
private val CLEANABLE_PREFIXES = listOf("/sdcard/", "/storage/emulated/0/")

/** 禁止删除的目录 */
private val FORBIDDEN_CLEAN_DIRS = listOf(
    "/sdcard/Android/data/",
    "/sdcard/Android/obb/",
    "/sdcard/Android/media/",
)

/** 校验文件路径是否允许删除，返回 null 表示允许 */
private fun validateCleanPath(path: String): String? {
    val normalized = path.trim().removeSuffix("/")
    if (normalized.isBlank()) return "路径为空"
    if (CLEANABLE_PREFIXES.none { normalized.startsWith(it) }) {
        return "只允许删除 /sdcard 下的文件"
    }
    if (FORBIDDEN_CLEAN_DIRS.any { normalized.startsWith(it) }) {
        return "禁止删除其他应用的私有数据目录"
    }
    return null
}

private fun buildCleanCacheTool(permission: DeviceToolPermission, auditStore: ManagementAuditStore): Tool = Tool(
    name = "clean_cache",
    description = "清理所有应用的缓存文件，释放存储空间。需用户确认。只清理 cache，不清数据。",
    parameters = { null },
    needsApproval = { _ -> permission.needsApproval("clean_cache") },
    execute = { _ ->
        withContext(Dispatchers.IO) {
            // trim-caches 参数为目标释放大小（字节），传大值以尽可能清理全部缓存
            if (permission.isForbidden("clean_cache")) return@withContext listOf(UIMessagePart.Text(buildJsonObject { put("error", "清理缓存已被禁止使用") }.toString()))
            val result = ShizukuCommandExecutor.execute(
                listOf("pm", "trim-caches", "107374182400"),
                allowWrite = true,
                timeoutMillis = 60_000L,
            )
            if (result.blocked || result.exitCode != 0) {
                auditStore.record("clean_cache", "", "failed")
                return@withContext listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "清理失败: ${result.stderr.ifBlank { result.stdout }}")
                }.toString()))
            }
            auditStore.record("clean_cache", "", "success")
            listOf(UIMessagePart.Text(buildJsonObject {
                put("cleaned", true)
                put("message", "缓存清理完成")
            }.toString()))
        }
    },
)

private fun buildCleanFilesTool(permission: DeviceToolPermission, auditStore: ManagementAuditStore): Tool = Tool(
    name = "clean_files",
    description = "删除用户确认过的大文件。路径必须来自 scan_large_files 的扫描结果，且只能删除 /sdcard 下的文件。需用户确认。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "要删除的文件绝对路径列表")
                })
            },
            required = listOf("paths")
        )
    },
    needsApproval = { _ -> permission.needsApproval("clean_files") },
    execute = { params ->
        withContext(Dispatchers.IO) {
            if (permission.isForbidden("clean_files")) return@withContext listOf(UIMessagePart.Text(buildJsonObject { put("error", "删除文件已被禁止使用") }.toString()))
            val paths = params.jsonObject["paths"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() } ?: emptyList()
            if (paths.isEmpty()) return@withContext listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "缺少 paths")
            }.toString()))
            val rejected = mutableListOf<String>()
            val deleted = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (path in paths) {
                val violation = validateCleanPath(path)
                if (violation != null) {
                    rejected.add("$path($violation)")
                    continue
                }
                val result = ShizukuCommandExecutor.execute(
                    listOf("rm", "-f", path),
                    allowWrite = true,
                )
                if (result.blocked || result.exitCode != 0) {
                    failed.add(path)
                } else {
                    deleted.add(path)
                }
            }
            auditStore.record("clean_files", deleted.joinToString(","), "deleted=${deleted.size}, rejected=${rejected.size}, failed=${failed.size}")
            listOf(UIMessagePart.Text(buildJsonObject {
                put("deleted", JsonArray(deleted.map { JsonPrimitive(it) }))
                put("rejected", JsonArray(rejected.map { JsonPrimitive(it) }))
                put("failed", JsonArray(failed.map { JsonPrimitive(it) }))
            }.toString()))
        }
    },
)
