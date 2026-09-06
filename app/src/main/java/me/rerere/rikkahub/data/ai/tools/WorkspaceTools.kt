package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
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
import me.rerere.rikkahub.data.repository.AsyncTaskState
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceSearchMatch
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

/** workspace_shell 的 stdin 输入上限：喂给命令的文本内容，避免超大输入撑爆 StreamWriter 与内存 */
private const val STDIN_MAX_BYTES = 512 * 1024

// shell 单流输出的展示上限（head+tail 保留）。三层预算对齐：
// 执行层 StreamCollector 128K/流(防 OOM) → 本层 10K/流 → GenerationHandler 32K 兜底头尾截断，
// 正常路径下整体 JSON 不超 32K, 兜底不触发。
private const val SHELL_STREAM_MAX_CHARS = 10 * 1024
private const val SHELL_STREAM_HEAD_CHARS = 7 * 1024
private const val SHELL_STREAM_TAIL_CHARS = 3 * 1024
/** 单条 shell 命令的文件变更列表上限，防止批量操作（如装依赖）撑爆 JSON；超限以 *Total 字段上报真实总数 */
private const val SHELL_FILE_DIFF_MAX_ENTRIES = 50

/** 输出被截断时追加在流末尾的提示, 让 AI 明确知道并改用分片读取 */
private val TRUNCATED_OUTPUT_MARKER =
    "\n\n... [output truncated, use head/tail or workspace_grep to read specific parts] ..."

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    "workspace_list_files" to false,
    "workspace_glob" to false,
    "workspace_grep" to false,
    "workspace_move" to false,
    "workspace_delete" to false,
    "workspace_restore" to false,
    "workspace_shell_async" to true,
    "workspace_task_status" to false,
    "workspace_set_env" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

/**
 * 写入/编辑工具的动态审批判定（每次工具调用按输入路径执行）：
 * 1. 输入缺失/无法解析 → 审批（fail-closed，宁误批不放行）
 * 2. 落在可写安全区（/workspace、/tmp）之外 → 强制审批。路径越界检查独立于覆写与
 *    forceNoApproval（execute 阶段会再报错引导 trusted_folder_*）
 * 3. forceNoApproval（子代理/讨论可信委派）→ 免审批
 * 4. per-workspace 覆写要求审批 → 审批
 * 5. cwd 审批域：/workspace 子树内、cwd 子树外的写入需审批。安全边界，不受覆写关闭；
 *    读取类工具不设 cwd 边界（信息只进不出）；/tmp 为临时暂存不在此域
 *
 * [cwd] 须为 [normalizeWorkspaceCwd] 归一化后的绝对路径（null = /workspace 根）。
 */
internal fun workspaceWriteNeedsApproval(
    rawPath: String?,
    toolName: String,
    approvalOverrides: Map<String, Boolean>,
    cwd: String?,
    forceNoApproval: Boolean,
): Boolean {
    val resolved = rawPath?.takeIf { it.isNotBlank() }
        ?.let { runCatching { resolveWorkspaceToolPath(it, cwd) }.getOrNull() }
    if (resolved == null || isOutsideWorkspaceWritableRoots(resolved)) return true
    if (forceNoApproval) return false
    if (resolveWorkspaceToolApproval(toolName, approvalOverrides)) return true
    if (isUnderWorkspaceTree(resolved, WORKSPACE_ROOT) &&
        !isUnderWorkspaceTree(resolved, cwd ?: WORKSPACE_ROOT)
    ) {
        return true
    }
    return false
}

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    forceNoApproval: Boolean = false,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    // 工具链工作目录：文件工具的缺省目录与相对路径解析基准（Rootfs 绝对路径；null = /workspace 根）
    val cwdAbsolute = normalizeWorkspaceCwd(cwd)
    val shellCwd = cwdAbsolute?.removePrefix("$WORKSPACE_ROOT/")?.takeIf { it.isNotEmpty() }
    // MED-7: forceNoApproval 只跳过"用户审批门"，不跳过工具的路径越界检查（见 workspaceWriteNeedsApproval 第 2 条）
    fun needsApproval(name: String) =
        if (forceNoApproval) false else resolveWorkspaceToolApproval(name, approvalOverrides)

    fun writeApproval(toolName: String): (JsonElement) -> Boolean = { input ->
        val rawPath = runCatching { input.jsonObject.string("path") }.getOrNull()
        workspaceWriteNeedsApproval(rawPath, toolName, approvalOverrides, cwdAbsolute, forceNoApproval)
    }

    /** 按指定参数名取路径的审批判定（workspace_move 的目标参数名为 target） */
    fun writeApprovalOn(toolName: String, key: String): (JsonElement) -> Boolean = { input ->
        val rawPath = runCatching { input.jsonObject.string(key) }.getOrNull()
        workspaceWriteNeedsApproval(rawPath, toolName, approvalOverrides, cwdAbsolute, forceNoApproval)
    }

    // GitHub 凭据注入总闸状态（开 + 已绑定）→ shell 工具描述里告知 AI 可用 GITHUB_TOKEN
    val githubTokenHint = workspaceRepository.isGithubShellTokenEnabled()

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, cwdAbsolute, workspaceRepository),
        createWriteFileTool(workspaceId, writeApproval("workspace_write_file"), cwdAbsolute, workspaceRepository),
        createEditFileTool(workspaceId, writeApproval("workspace_edit_file"), cwdAbsolute, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd, githubTokenHint),
        createListFilesTool(workspaceId, ::needsApproval, cwdAbsolute, workspaceRepository),
        createGlobTool(workspaceId, ::needsApproval, cwdAbsolute, workspaceRepository),
        createGrepTool(workspaceId, ::needsApproval, cwdAbsolute, workspaceRepository),
        createMoveFileTool(workspaceId, writeApprovalOn("workspace_move", "target"), cwdAbsolute, workspaceRepository),
        createDeleteFileTool(workspaceId, writeApproval("workspace_delete"), cwdAbsolute, workspaceRepository),
        createRestoreFileTool(workspaceId, writeApproval("workspace_restore"), cwdAbsolute, workspaceRepository),
        createShellAsyncTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createTaskStatusTool(workspaceId, workspaceRepository),
        createSetEnvTool(workspaceId, workspaceRepository),
    )
}

/** cwd 展示值：内嵌进工具描述与 schema，模型无需从上下文猜测当前目录 */
private fun cwdDisplay(cwd: String?): String = cwd ?: WORKSPACE_ROOT

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file (UTF-8 text or image) from the assistant's bound workspace Rootfs.
        path is relative to the current working directory ${cwdDisplay(cwd)} unless it starts with '/'.
        Cannot list a directory - use workspace_list_files instead.
        Supports ranged reads: pass start (byte offset) and/or maxBytes to read any part of a file; for files larger than the single-read limit, read successive ranges (start = previous start + bytes read).
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true, cwd = cwd)
                put("start", buildJsonObject {
                    put("type", "integer")
                    put("description", "Byte offset to start reading from. Defaults to 0.")
                })
                put("maxBytes", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum bytes to read (defaults to the single-read limit ${WorkspaceManager.MAX_RANGE_READ_BYTES / 1024 / 1024}MB). " +
                            "For large files pass a smaller value and read successive ranges."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val params = it.jsonObject
        val path = params.resolveRequiredToolPath("path", cwd)
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val start = params["start"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            require(start >= 0) { "start must be a non-negative byte offset, got $start" }
            val maxBytes = params["maxBytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: WorkspaceManager.MAX_RANGE_READ_BYTES
            require(maxBytes > 0) { "maxBytes must be positive, got $maxBytes" }
            require(maxBytes <= WorkspaceManager.MAX_RANGE_READ_BYTES) {
                "maxBytes ${maxBytes / 1024}KB exceeds the single-read limit ${WorkspaceManager.MAX_RANGE_READ_BYTES / 1024 / 1024}MB; read in smaller ranges"
            }
            val range = workspaceRepository.readRootfsTextRange(workspaceId, path, start, maxBytes)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("start", start)
                        put("sizeBytes", range.sizeBytes)
                        put("text", range.text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (JsonElement) -> Boolean,
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file in the assistant's bound workspace Rootfs.
        path is relative to the current working directory ${cwdDisplay(cwd)} unless it starts with '/'.
        Writing files under /workspace but outside the current working directory requires user approval.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true, cwd = cwd)
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
    needsApproval = { needsApproval(it) },
    execute = {
        val params = it.jsonObject
        val path = params.resolveRequiredToolPath("path", cwd)
        // 路径落在沙盒可写区（/workspace、/tmp）之外时，直接报错并引导到真实文件工具，
        // 避免模型把"真实文件"误当沙盒文件写、造成"文件到底改没改"的混乱
        if (isOutsideWorkspaceWritableRoots(path)) {
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
    needsApproval: (JsonElement) -> Boolean,
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file in the assistant's bound workspace Rootfs.
        path is relative to the current working directory ${cwdDisplay(cwd)} unless it starts with '/'.
        Writing files under /workspace but outside the current working directory requires user approval.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true, cwd = cwd)
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
    needsApproval = { needsApproval(it) },
    execute = {
        val params = it.jsonObject
        val path = params.resolveRequiredToolPath("path", cwd)
        // 同上：沙盒可写区之外直接引导到真实文件工具
        if (isOutsideWorkspaceWritableRoots(path)) {
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
    githubTokenHint: Boolean = false,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the workspace's Linux rootfs (bash — not Windows; use Unix commands). /workspace = persistent files. ")
        append("Each call is a fresh process: cd/export don't persist; use absolute paths or 'cd /path && cmd'. ")
        append("cwd must be under /workspace. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Output capped: each of stdout/stderr keeps first ~7KB + last ~3KB; for large outputs use head/tail/grep to read specific parts. ")
        append("Optional stdin text (UTF-8, max ${STDIN_MAX_BYTES / 1024}KB) is piped to the command's stdin, then closed — e.g. {\"command\":\"cat > config.json\",\"stdin\":\"...\"}. ")
        append("Timeout default 30s, max $SHELL_TIMEOUT_MAX_SECONDS s. Changed files under /workspace are reported. ")
        if (githubTokenHint) {
            append("GITHUB_TOKEN/GH_TOKEN env vars hold the bound GitHub account token (git HTTPS clones from github.com are authenticated via extraheader too). " +
                "For api.github.com requests use the env var instead of a literal, e.g. curl -H \"Authorization: Bearer ${'$'}GITHUB_TOKEN\". " +
                "Never print the token value; it is masked in outputs. ")
        }
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
                            "Working directory: path relative to the workspace files root, or an absolute /workspace/... path. " +
                                "Defaults to '$defaultCwd'. Paths outside /workspace are rejected — for other rootfs locations (e.g. /tmp) use 'cd /path && cmd' inside the command."
                        } else {
                            "Working directory: path relative to the workspace files root, or an absolute /workspace/... path. " +
                                "Defaults to root. Paths outside /workspace are rejected — for other rootfs locations (e.g. /tmp) use 'cd /path && cmd' inside the command."
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
                put("stdin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional UTF-8 text piped to the command's standard input (then closed). Max ${STDIN_MAX_BYTES / 1024}KB."
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
        // cwd 语义与文件工具对齐：接受相对路径与 /workspace 绝对写法，词法归一（折叠 .. 与重复分隔符）。
        // /workspace 子树之外（/tmp 等）宿主侧无法校验存在性，显式报错引导在命令内 cd，而非剥斜杠静默映射
        val rawCwd = params.string("cwd")?.trim()?.takeIf { it.isNotEmpty() } ?: defaultCwd
        val cwdNormalized = normalizeWorkspaceCwd(rawCwd)
        require(cwdNormalized == null || isUnderWorkspaceTree(cwdNormalized, WORKSPACE_ROOT)) {
            "cwd \"$rawCwd\" is outside /workspace; workspace_shell only supports cwd under /workspace " +
                "(relative path or absolute /workspace/... path). " +
                "For other rootfs locations, cd inside the command, e.g. \"cd /tmp && ls\""
        }
        val cwd = cwdNormalized?.removePrefix("$WORKSPACE_ROOT/") ?: ""
        // timeout 容错：非法/0/负数回退默认值而非 clamp 成 1s，超过上限收敛到 600s
        val timeoutSeconds = params.string("timeout")?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.coerceAtMost(SHELL_TIMEOUT_MAX_SECONDS)
        val timeoutMillis = timeoutSeconds?.times(1_000L) ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val stdinBytes = params.string("stdin")?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        if (stdinBytes != null && stdinBytes.size > STDIN_MAX_BYTES) {
            error("stdin exceeds max ${STDIN_MAX_BYTES / 1024}KB: ${stdinBytes.size} bytes")
        }
        val beforeSnapshot = runCatching {
            workspaceRepository.listAllFiles(workspaceId, WorkspaceStorageArea.FILES)
        }.getOrDefault(emptyList())
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis, stdinBytes)
        val afterSnapshot = runCatching {
            workspaceRepository.listAllFiles(workspaceId, WorkspaceStorageArea.FILES)
        }.getOrDefault(emptyList())
        val (addedFiles, modifiedFiles, removedFiles) = computeWorkspaceFileDiff(
            before = beforeSnapshot,
            after = afterSnapshot,
        )
        // 分流截断标志：只有爆掉的那条流退化为 head-only，未爆的流仍保留真实尾部（报错通常落在爆掉的那条）
        val stdoutShown = boundShellStream(result.stdout, result.stdoutTruncated)
        val stderrShown = boundShellStream(result.stderr, result.stderrTruncated)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", if (stdoutShown != null) stdoutShown + TRUNCATED_OUTPUT_MARKER else result.stdout)
                    put("stderr", if (stderrShown != null) stderrShown + TRUNCATED_OUTPUT_MARKER else result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated || stdoutShown != null || stderrShown != null) put("truncated", true)
                    putFileDiffList("addedFiles", addedFiles)
                    putFileDiffList("modifiedFiles", modifiedFiles)
                    putFileDiffList("removedFiles", removedFiles)
                }.toString()
            )
        )
    },
)

private fun createListFilesTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_files",
    description = """
        List a directory in the assistant's bound workspace Rootfs.
        path defaults to the current working directory ${cwdDisplay(cwd)}; relative paths resolve against it, absolute Rootfs paths are also accepted.
        Returns file entries with name, path, isDirectory and sizeBytes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false, cwd = cwd)
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_list_files") },
    execute = {
        val path = it.jsonObject.resolveOptionalToolPath("path", cwd) ?: cwd ?: WORKSPACE_ROOT
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
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = """
        Glob-match files under a directory in the assistant's bound workspace Rootfs.
        path defaults to the current working directory ${cwdDisplay(cwd)}; relative paths resolve against it, absolute Rootfs paths are also accepted.
        pattern is relative to the search path: e.g. with path /workspace use "ai-output/txt/*.txt" or "**/*.txt".
        A leading /workspace prefix in pattern is tolerated and stripped. Returns matching entries with absolute Rootfs paths.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false, cwd = cwd)
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern, relative to the search path")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_glob") },
    execute = {
        val params = it.jsonObject
        val path = params.resolveOptionalToolPath("path", cwd) ?: cwd ?: WORKSPACE_ROOT
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
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = """
        Search file contents under a directory in the assistant's bound workspace Rootfs.
        path defaults to the current working directory ${cwdDisplay(cwd)}; relative paths resolve against it, absolute Rootfs paths are also accepted.
        Returns matching lines with absolute Rootfs path, line number and text.
        Searching large directories (e.g. the whole rootfs) can be slow.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false, cwd = cwd)
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
                    put("description", "Only search files matching this glob (relative to the search path). Optional.")
                })
            },
            required = listOf("query"),
        )
    },
    needsApproval = { needsApproval("workspace_grep") },
    execute = {
        val params = it.jsonObject
        val path = params.resolveOptionalToolPath("path", cwd) ?: cwd ?: WORKSPACE_ROOT
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

private fun createMoveFileTool(
    workspaceId: String,
    needsApproval: (JsonElement) -> Boolean,
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_move",
    description = """
        Move or rename a file/directory inside the assistant's bound workspace (/workspace).
        Paths are relative to the current working directory ${cwdDisplay(cwd)} unless they start with '/'.
        Moving to a location outside the current working directory requires user approval.
        An existing target is only overwritten when overwrite=true. Only /workspace paths are supported — use shell mv for other rootfs locations.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Source file or directory to move. Relative to the current working directory (${cwdDisplay(cwd)}) or absolute /workspace path."
                    )
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Destination path. Relative to the current working directory (${cwdDisplay(cwd)}) or absolute /workspace path."
                    )
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Overwrite an existing target. Defaults to false.")
                })
            },
            required = listOf("source", "target"),
        )
    },
    needsApproval = { needsApproval(it) },
    execute = {
        val params = it.jsonObject
        val source = params.resolveRequiredToolPath("source", cwd)
        val target = params.resolveRequiredToolPath("target", cwd)
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val entry = workspaceRepository.moveFile(
            workspaceId,
            source.requireWorkspaceRelative("workspace_move source"),
            target.requireWorkspaceRelative("workspace_move target"),
            overwrite,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", entry.path.absoluteRootfsPath())
                    put("movedFrom", source)
                    put("name", entry.name)
                    put("isDirectory", entry.isDirectory)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                    put("changeStatus", "moved")
                }.toString()
            )
        )
    },
)

private fun createDeleteFileTool(
    workspaceId: String,
    needsApproval: (JsonElement) -> Boolean,
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_delete",
    description = """
        Move a file or directory into the workspace trash (/workspace/.trash) — recoverable via workspace_restore, NOT a permanent delete.
        Directories require recursive=true. Deleting outside the current working directory requires user approval.
        Only /workspace paths are supported — use shell rm for other rootfs locations.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true, cwd = cwd)
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Delete a directory and its contents. Defaults to false.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval(it) },
    execute = {
        val params = it.jsonObject
        val path = params.resolveRequiredToolPath("path", cwd)
        val recursive = params["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val trashed = workspaceRepository.trashFile(
            workspaceId,
            WorkspaceStorageArea.FILES,
            path.requireWorkspaceRelative("workspace_delete"),
            recursive,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("trashed", trashed)
                    if (!trashed) put("note", "Path does not exist or is already in the trash")
                }.toString()
            )
        )
    },
)

private fun createRestoreFileTool(
    workspaceId: String,
    needsApproval: (JsonElement) -> Boolean,
    cwd: String?,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_restore",
    description = """
        Restore a previously deleted file or directory from the workspace trash back to its original path.
        path is the original path of the deleted item (relative to the current working directory ${cwdDisplay(cwd)} or absolute /workspace path).
        Restoring outside the current working directory requires user approval.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true, cwd = cwd)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval(it) },
    execute = {
        val path = it.jsonObject.resolveRequiredToolPath("path", cwd)
        val restored = workspaceRepository.restoreFile(
            workspaceId,
            WorkspaceStorageArea.FILES,
            path.requireWorkspaceRelative("workspace_restore"),
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("restored", restored)
                    if (!restored) put("note", "Path is not in the trash or its original location is unavailable")
                }.toString()
            )
        )
    },
)

private fun createShellAsyncTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell_async",
    description = buildString {
        append("Run a shell command in the workspace rootfs in the background; returns an id immediately. ")
        append("Poll with workspace_task_status. Intended for long jobs (install/build/long scripts) that would exceed the normal 30s timeout. ")
        append("cwd must be under /workspace. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Timeout default $SHELL_TIMEOUT_MAX_SECONDS s (max $SHELL_TIMEOUT_MAX_SECONDS s). Full output is saved to /tool_outputs/<taskId>.txt (24h retention). No file-change diff is reported.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run in the background")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Working directory: relative to the workspace files root, or an absolute /workspace/... path. Defaults to '${defaultCwd ?: "root"}'. Paths outside /workspace are rejected."
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Timeout in seconds. Defaults to $SHELL_TIMEOUT_MAX_SECONDS, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell_async") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        // cwd 语义与 workspace_shell 保持一致：/workspace 子树内相对/绝对写法，子树外显式报错
        val rawCwd = params.string("cwd")?.trim()?.takeIf { it.isNotEmpty() } ?: defaultCwd
        val cwdNormalized = normalizeWorkspaceCwd(rawCwd)
        require(cwdNormalized == null || isUnderWorkspaceTree(cwdNormalized, WORKSPACE_ROOT)) {
            "cwd \"$rawCwd\" is outside /workspace; workspace_shell_async only supports cwd under /workspace " +
                "(relative path or absolute /workspace/... path)."
        }
        val cwd = cwdNormalized?.removePrefix("$WORKSPACE_ROOT/") ?: ""
        val timeoutSeconds = params.string("timeout")?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.coerceAtMost(SHELL_TIMEOUT_MAX_SECONDS)
            ?: SHELL_TIMEOUT_MAX_SECONDS
        val taskId = workspaceRepository.launchAsyncCommand(workspaceId, command, cwd, timeoutSeconds * 1_000L)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", taskId)
                    put("status", "running")
                    put("note", "Poll with workspace_task_status(tool). Full output will be at /tool_outputs/$taskId.txt")
                }.toString()
            )
        )
    },
)

private fun createTaskStatusTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_task_status",
    description = """
        Check the status of a background task started with workspace_shell_async.
        Returns running/succeeded/failed/timed_out; for terminal states also exitCode and the bounded stdout/stderr.
        Tasks live for the current app session (restart loses them); output files persist in /tool_outputs for 24h and can be read with shell.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("taskId", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell_async")
                })
            },
            required = listOf("taskId"),
        )
    },
    needsApproval = { false },
    execute = {
        val taskId = it.jsonObject.string("taskId") ?: error("taskId is required")
        val status = workspaceRepository.asyncTaskStatus(taskId)
            ?: error("Task not found: $taskId (background tasks only live for the current app session — you may need to rerun the command)")
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("taskId", status.taskId)
                    put("state", status.state.name.lowercase())
                    status.exitCode?.let { exit -> put("exitCode", exit) }
                    if (status.timedOut) put("timedOut", true)
                    status.error?.let { put("error", it) }
                    if (status.stdout.isNotEmpty()) put("stdout", status.stdout)
                    if (status.stderr.isNotEmpty()) put("stderr", status.stderr)
                    if (status.state != AsyncTaskState.RUNNING) put("outputPath", status.outputPath)
                }.toString()
            )
        )
    },
)

private val ENV_NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

private fun createSetEnvTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_set_env",
    description = """
        Persist an environment variable for all future workspace_shell calls (every shell call is a fresh process; exports don't carry over).
        Stored in the rootfs profile.d and loaded by each login shell. Name must match [A-Za-z_][A-Za-z0-9_]*; omit value to remove the variable.
        Values are literal (no shell substitution or expansion). Caution: setting PATH to a broken value will break subsequent shell commands until you reset it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Environment variable name, e.g. MIRROR_INDEX")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Literal value. Omit to remove the variable.")
                })
            },
            required = listOf("name"),
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val name = params.string("name") ?: error("name is required")
        require(ENV_NAME_REGEX.matches(name)) { "name must match [A-Za-z_][A-Za-z0-9_]*" }
        val value = params.string("value")
        if (value != null) require(!value.contains('\n') && !value.contains('\r')) { "value must be a single line" }
        workspaceRepository.setEnvPersistence(workspaceId, name, value)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("name", name)
                    if (value == null) put("removed", true) else put("value", value)
                }.toString()
            )
        )
    },
)

/**
 * 把工具入参的 Rootfs 绝对路径转成文件区相对根路径（workspace 文件工具的唯一可寻址域）。
 * 仅允许 /workspace 子树；根自身与子树外路径显式拒绝，引导用 shell 处理其它 rootfs 位置。
 */
private fun String.requireWorkspaceRelative(what: String): String {
    require(isUnderWorkspaceTree(this, WORKSPACE_ROOT)) {
        "$what $this is outside /workspace — workspace file tools only support /workspace paths; use shell mv/rm for other rootfs locations"
    }
    val relative = removePrefix("$WORKSPACE_ROOT/")
    require(relative.isNotBlank() && relative != ".") { "Cannot $what on the workspace root itself" }
    return relative
}

private fun String.absoluteRootfsPath(): String = "$WORKSPACE_ROOT/$this"

/** 必填路径参数 → Rootfs 绝对路径；缺失/空白/非法输入抛错（错误文案即工具回给模型的指引） */
private fun kotlinx.serialization.json.JsonObject.resolveRequiredToolPath(name: String, cwd: String?): String =
    resolveWorkspaceToolPath(string(name) ?: error("$name is required"), cwd)

/** 可选路径参数 → Rootfs 绝对路径；缺失/空白返回 null，由调用方回退 cwd 默认值 */
private fun kotlinx.serialization.json.JsonObject.resolveOptionalToolPath(name: String, cwd: String?): String? =
    string(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveWorkspaceToolPath(it, cwd) }

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

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean, cwd: String?) {
    val cwdLabel = cwdDisplay(cwd)
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "File path. Relative to the current working directory ($cwdLabel); absolute Rootfs paths are also accepted."
            } else {
                "Optional directory path. Defaults to the current working directory ($cwdLabel); absolute Rootfs paths are also accepted."
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

/**
 * shell 单流输出的展示截断：超限保留 head+tail（报错通常在尾部，纯 head 会切掉关键信息）。
 * 执行层已截断（>128K 只保留头部、无真实尾部）时退化为 head-only，由 marker 说明。
 * 返回 null 表示未截断。
 */
internal fun boundShellStream(text: String, execTruncated: Boolean): String? = when {
    text.length <= SHELL_STREAM_MAX_CHARS -> null
    execTruncated -> text.take(SHELL_STREAM_HEAD_CHARS)
    else -> text.take(SHELL_STREAM_HEAD_CHARS) +
        "\n…[${text.length - SHELL_STREAM_HEAD_CHARS - SHELL_STREAM_TAIL_CHARS} chars omitted]…" +
        text.takeLast(SHELL_STREAM_TAIL_CHARS)
}

/** 文件变更列表限量写入：数组最多 [SHELL_FILE_DIFF_MAX_ENTRIES] 条，超限时补 <key>Total 字段上报真实总数 */
private fun JsonObjectBuilder.putFileDiffList(key: String, files: List<String>) {
    if (files.isEmpty()) return
    putJsonArray(key) {
        files.take(SHELL_FILE_DIFF_MAX_ENTRIES).forEach { add(JsonPrimitive(it)) }
    }
    if (files.size > SHELL_FILE_DIFF_MAX_ENTRIES) put("${key}Total", files.size)
}
