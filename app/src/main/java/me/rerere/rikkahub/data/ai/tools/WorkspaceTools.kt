package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.MAX_OUTPUT_CHARS
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceSearchMatch
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

/** 输出被截断时追加在 stdout 末尾的提示, 让 AI 明确知道并改用分片读取 */
private val TRUNCATED_OUTPUT_MARKER =
    "\n\n... [output truncated at ${MAX_OUTPUT_CHARS / 1024}KB, use head/tail or workspace_grep to read parts] ..."

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    "workspace_list_files" to false,
    "workspace_glob" to false,
    "workspace_grep" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    forceNoApproval: Boolean = false,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    // MED-7: forceNoApproval 只跳过"用户审批门"，不跳过工具的路径越界检查（后者在工具 needsApproval lambda 的 || 后半段）
    fun needsApproval(name: String) =
        if (forceNoApproval) false else resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createListFilesTool(workspaceId, ::needsApproval, workspaceRepository),
        createGlobTool(workspaceId, ::needsApproval, workspaceRepository),
        createGrepTool(workspaceId, ::needsApproval, workspaceRepository),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Cannot list a directory - use workspace_list_files instead.
        For files larger than 8MB, use workspace_shell with head/tail/grep to read parts.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        // 路径落在沙盒可写区（/workspace、/tmp）之外时，直接报错并引导到真实文件工具，
        // 避免模型把"真实文件"误当沙盒文件写、造成"文件到底改没改"的混乱
        if (path.isOutsideWritableRoots()) {
            error(
                "路径 $path 位于工作区沙盒的可写区之外（沙盒内只能用 /workspace 或 /tmp）。" +
                    "如果你要操作设备上的真实文件（笔记/错题/信任文件夹等），请改用 trusted_folder_* 工具。"
            )
        }
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val existedBefore = runCatching {
            workspaceRepository.rootfsFileSize(workspaceId, path) > 0
        }.getOrDefault(false)
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", entry.path)
                    put("name", entry.name)
                    put("isDirectory", entry.isDirectory)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                    put("changeStatus", if (existedBefore) "edited" else "added")
                }.toString()
            )
        )
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        // 同上：沙盒可写区之外直接引导到真实文件工具
        if (path.isOutsideWritableRoots()) {
            error(
                "路径 $path 位于工作区沙盒的可写区之外（沙盒内只能用 /workspace 或 /tmp）。" +
                    "如果你要操作设备上的真实文件（笔记/错题/信任文件夹等），请改用 trusted_folder_* 工具。"
            )
        }
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                    put("changeStatus", "edited")
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the workspace's Linux rootfs (bash — not Windows; use Unix commands). /workspace = persistent files. ")
        append("Each call is a fresh process: cd/export don't persist; use absolute paths or 'cd /path && cmd'. ")
        append("cwd is relative to the workspace root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Output capped at 128KB. Timeout default 30s, max $SHELL_TIMEOUT_MAX_SECONDS s. Changed files under /workspace are reported.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val beforeSnapshot = runCatching {
            workspaceRepository.listAllFiles(workspaceId, WorkspaceStorageArea.FILES)
        }.getOrDefault(emptyList())
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        val afterSnapshot = runCatching {
            workspaceRepository.listAllFiles(workspaceId, WorkspaceStorageArea.FILES)
        }.getOrDefault(emptyList())
        val (addedFiles, modifiedFiles, removedFiles) = computeWorkspaceFileDiff(
            before = beforeSnapshot,
            after = afterSnapshot,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", if (result.truncated) result.stdout + TRUNCATED_OUTPUT_MARKER else result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                    if (addedFiles.isNotEmpty()) putJsonArray("addedFiles") {
                        addedFiles.forEach { add(JsonPrimitive(it)) }
                    }
                    if (modifiedFiles.isNotEmpty()) putJsonArray("modifiedFiles") {
                        modifiedFiles.forEach { add(JsonPrimitive(it)) }
                    }
                    if (removedFiles.isNotEmpty()) putJsonArray("removedFiles") {
                        removedFiles.forEach { add(JsonPrimitive(it)) }
                    }
                }.toString()
            )
        )
    },
)

private fun createListFilesTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_files",
    description = """
        List a directory in the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area. Defaults to /workspace.
        Returns file entries with name, path, isDirectory and sizeBytes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false)
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_list_files") },
    execute = {
        val path = it.jsonObject.optionalRootfsPath("path") ?: "/workspace"
        val entries = workspaceRepository.listFilesInRootfs(workspaceId, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    putJsonArray("entries") {
                        entries.forEach { add(it.toJson()) }
                    }
                }.toString()
            )
        )
    },
)

private fun createGlobTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = """
        Glob-match files under a directory in the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area. Defaults to /workspace.
        pattern is relative to the search path: e.g. with path /workspace use "ai-output/txt/*.txt" or "**/*.txt".
        A leading /workspace prefix in pattern is tolerated and stripped. Returns matching entries with absolute Rootfs paths.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false)
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern, relative to the workspace files root")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_glob") },
    execute = {
        val params = it.jsonObject
        val path = params.optionalRootfsPath("path") ?: "/workspace"
        val rawPattern = params.string("pattern") ?: error("pattern is required")
        // 容错: AI 常把 Rootfs 绝对前缀(如 /workspace/...)写进 pattern, 剥离到相对基准, 避免匹配落空
        val pattern = rawPattern.removePrefix(path.trimEnd('/')).removePrefix("/")
        val entries = workspaceRepository.globInRootfs(workspaceId, pattern, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("pattern", pattern)
                    putJsonArray("matches") {
                        entries.forEach { add(it.toJson()) }
                    }
                }.toString()
            )
        )
    },
)

private fun createGrepTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = """
        Search file contents under a directory in the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area. Defaults to /workspace.
        Returns matching lines with absolute Rootfs path, line number and text.
        Searching large directories (e.g. the whole rootfs) can be slow.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false)
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Text or regex to search for")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Treat query as a regular expression. Defaults to false.")
                })
                put("ignoreCase", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Case-insensitive search. Defaults to true.")
                })
                put("includeGlob", buildJsonObject {
                    put("type", "string")
                    put("description", "Only search files matching this glob (relative to the workspace files root). Optional.")
                })
            },
            required = listOf("query"),
        )
    },
    needsApproval = { needsApproval("workspace_grep") },
    execute = {
        val params = it.jsonObject
        val path = params.optionalRootfsPath("path") ?: "/workspace"
        val query = params.string("query") ?: error("query is required")
        val regex = params["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val ignoreCase = params["ignoreCase"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val includeGlob = params.string("includeGlob")
        val matches = workspaceRepository.grepInRootfs(workspaceId, query, path, regex, ignoreCase, includeGlob)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("query", query)
                    putJsonArray("matches") {
                        matches.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("path", m.path)
                                    put("line", m.line)
                                    put("text", m.text)
                                }
                            )
                        }
                    }
                }.toString()
            )
        )
    },
)

/** 可选 Rootfs 绝对路径: 缺省或空返回 null */
private fun kotlinx.serialization.json.JsonObject.optionalRootfsPath(name: String): String? {
    val path = string(name)?.replace('\\', '/')?.trim() ?: return null
    if (path.isBlank()) return null
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(path.none { it.code == 0 }) { "$name contains invalid character" }
    return path
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}

/**
 * 比较 shell 执行前后的工作区文件快照，返回新增和修改的 Rootfs 绝对路径列表。
 * 路径统一以 /workspace/ 前缀输出，与 write/edit 工具的 path 保持一致。
 */
private fun computeWorkspaceFileDiff(
    before: List<WorkspaceFileEntry>,
    after: List<WorkspaceFileEntry>,
): Triple<List<String>, List<String>, List<String>> {
    val beforeMap = before.associateBy { it.path }
    val afterMap = after.associateBy { it.path }
    val added = afterMap.keys
        .filter { it !in beforeMap }
        .sorted()
        .map { "/workspace/$it" }
    val modified = afterMap.entries
        .filter { (path, entry) ->
            val beforeEntry = beforeMap[path]
            beforeEntry != null && (beforeEntry.sizeBytes != entry.sizeBytes || beforeEntry.updatedAt != entry.updatedAt)
        }
        .map { "/workspace/${it.key}" }
        .sorted()
    val removed = beforeMap.keys
        .filter { it !in afterMap }
        .sorted()
        .map { "/workspace/$it" }
    return Triple(added, modified, removed)
}
