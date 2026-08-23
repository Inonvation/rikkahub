package me.rerere.rikkahub.data.ai.tools.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.shizuku.ShizukuCommandExecutor

internal fun buildStorageCleanerTools(): List<Tool> = listOf(
    buildStorageOverviewTool(),
    buildScanLargeFilesTool(),
    buildScanCacheTool(),
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