package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import me.rerere.rikkahub.data.trustedfolders.SafeFolderAccess
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderBrokenLink
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderEntry
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderHealthReport
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSearchMatch
import me.rerere.rikkahub.data.trustedfolders.TrustedOp
import me.rerere.rikkahub.utils.generateUnifiedDiff
import org.koin.java.KoinJavaComponent.getKoin

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/**
 * 信任文件夹 AI 工具。全部操作基于**当前助手绑定的项目**内的真实文件（SAF）。
 *
 * 边界：
 * - **助手未绑定项目（或项目已删除）时不注入任何工具**（[createTrustedFolderTools] 返回空列表），AI 无法触碰。
 * - 所有路径均为**相对项目根**的相对路径，经 [SafeFolderAccess.validateRelPath] 校验，拒绝 `..` 逃逸。
 * - 审批严格按绑定项目设置中的操作开关（读/建/改/删），不做 forceNoApproval 旁路——真实文件系统上的写操作始终受开关约束。
 */
suspend fun createTrustedFolderTools(
    repository: TrustedFolderRepository,
    projectId: String,
): List<Tool> {
    runCatching { repository.withProject(projectId) { it } }.getOrNull() ?: return emptyList()

    fun needsApproval(op: TrustedOp): (JsonElement) -> Boolean = { input ->
        // 配置目录（.obsidian 等）内的写操作强制审批，即使设置里允许修改也要用户确认
        val protected = op != TrustedOp.READ && input.jsonObject.protectedPath(repository)
        repository.approvalNeeded(op, projectId) || input.jsonObject.pathInvalid("path") || protected
    }

    return listOf(
        createListTool(repository, projectId, needsApproval(TrustedOp.READ)),
        createReadTool(repository, projectId, needsApproval(TrustedOp.READ)),
        createSearchTool(repository, projectId, needsApproval(TrustedOp.READ)),
        createWriteTool(repository, projectId, needsApproval(TrustedOp.CREATE)),
        createCreateFolderTool(repository, projectId, needsApproval(TrustedOp.CREATE)),
        createEditTool(repository, projectId, needsApproval(TrustedOp.EDIT)),
        createRenameTool(repository, projectId, needsApproval(TrustedOp.EDIT)),
        createMoveTool(repository, projectId, needsApproval(TrustedOp.EDIT)),
        createDeleteTool(repository, projectId, needsApproval(TrustedOp.DELETE)),
        createHealthTool(repository, projectId, needsApproval(TrustedOp.READ)),
    )
}

private fun createListTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_list",
    description = """
        List a directory inside the trusted folder bound to this assistant (real files on the device).
        Path is RELATIVE to the trusted folder root; omit or use "" for the root.
        Returns entries with name, path, isDirectory and sizeBytes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { putRelPathProperty(required = false) },
            required = emptyList(),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val path = it.jsonObject.relPathOrEmpty("path")
        val entries = repository.list(path, projectId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    putJsonArray("entries") { entries.forEach { e -> add(e.toJson()) } }
                }.toString()
            )
        )
    },
)

private fun createReadTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_read",
    description = """
        Read a file inside the trusted folder bound to this assistant. Path is RELATIVE to the trusted folder root.
        Supports UTF-8 text files (Markdown, notes, etc.) and image files.
        Cannot list a directory - use trusted_folder_list instead.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { putRelPathProperty(required = true) },
            required = listOf("path"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val path = it.jsonObject.relPath("path")
        if (path.isImagePath()) {
            val bytes = repository.readBytes(path, projectId)
            val filesManager = getKoin().get<FilesManager>()
            val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
            listOf(
                UIMessagePart.Image(url = uris.first().toString()),
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("description", "Image file read successfully")
                    }.toString()
                ),
            )
        } else {
            val text = repository.readText(path, projectId)
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

private fun createWriteTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_write",
    description = """
        Write a UTF-8 text file inside the trusted folder bound to this assistant (real files on the device).
        Path is RELATIVE to the trusted folder root; parent directories are created automatically.
        If the file already exists, overwrite=true replaces it; overwrite=false errors.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putRelPathProperty(required = true)
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
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val path = params.relPath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val existedBefore = runCatching { repository.readText(path, projectId).isNotEmpty() }.getOrDefault(false)
        val entry = repository.writeText(path, text, overwrite, projectId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    toResultFields(entry)
                    put("changeStatus", if (existedBefore) "edited" else "added")
                }.toString()
            )
        )
    },
)

private fun createCreateFolderTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_create_folder",
    description = """
        Create a folder (or folders, if nested) inside the trusted folder bound to this assistant.
        Path is RELATIVE to the trusted folder root. Parent directories are created automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { putRelPathProperty(required = true) },
            required = listOf("path"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val path = it.jsonObject.relPath("path")
        val entry = repository.createFolder(path, projectId)
        listOf(UIMessagePart.Text(buildJsonObject { toResultFields(entry, "created") }.toString()))
    },
)

private fun createEditTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_edit",
    description = """
        Edit a UTF-8 text file inside the trusted folder bound to this assistant. Path is RELATIVE to the trusted folder root.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putRelPathProperty(required = true)
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
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val path = params.relPath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = repository.readText(path, projectId)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器（复用工作区的 TextReplacers）
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = repository.writeText(path, result.updated, overwrite = true, projectId)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    toResultFields(entry)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("changeStatus", "edited")
                }.toString(),
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createRenameTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_rename",
    description = """
        Rename a file or folder inside the trusted folder bound to this assistant. Path is RELATIVE to the trusted folder root.
        new_name must be a plain name (no slashes).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putRelPathProperty(required = true)
                put("new_name", buildJsonObject {
                    put("type", "string")
                    put("description", "New name for the file or folder (no slashes)")
                })
            },
            required = listOf("path", "new_name"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val path = params.relPath("path")
        val newName = params.string("new_name") ?: error("new_name is required")
        val entry = repository.rename(path, newName, projectId)
        listOf(UIMessagePart.Text(buildJsonObject { toResultFields(entry, "renamed") }.toString()))
    },
)

private fun createMoveTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_move",
    description = """
        Move a file or folder to another directory inside the trusted folder bound to this assistant.
        Both path and target_dir are RELATIVE to the trusted folder root; target_dir may be "" for the root.
        The item keeps its name in the target directory.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putRelPathProperty(required = true)
                put("target_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "Target directory relative to the trusted folder root (\"\" = root)")
                })
            },
            required = listOf("path", "target_dir"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val path = params.relPath("path")
        val targetDir = params.relPathOrEmpty("target_dir")
        val entry = repository.move(path, targetDir, projectId)
        listOf(UIMessagePart.Text(buildJsonObject { toResultFields(entry, "moved") }.toString()))
    },
)

private fun createDeleteTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_delete",
    description = """
        Delete a file or folder inside the trusted folder bound to this assistant (permanently, cannot be undone).
        Path is RELATIVE to the trusted folder root. Deleting a folder deletes everything under it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { putRelPathProperty(required = true) },
            required = listOf("path"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val path = it.jsonObject.relPath("path")
        repository.delete(path, projectId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("deleted", true)
                }.toString()
            )
        )
    },
)

private fun createSearchTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_search",
    description = """
        Search file contents inside the trusted folder bound to this assistant. Path is RELATIVE to the trusted folder root.
        Returns matching lines with path and line number. Searches text files only.
        If the result is truncated (reached maxResults), narrow the search with path.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putRelPathProperty(required = false)
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
                put("maxResults", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max matching lines to return. Defaults to 50.")
                })
            },
            required = listOf("query"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val path = params.relPathOrEmpty("path")
        val query = params.string("query") ?: error("query is required")
        val regex = params["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val ignoreCase = params["ignoreCase"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val maxResults = params.string("maxResults")?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val matches = repository.search(path, query, regex, ignoreCase, maxResults, projectId)
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
                    if (matches.size >= maxResults) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun createHealthTool(
    repository: TrustedFolderRepository,
    projectId: String,
    needsApproval: (JsonElement) -> Boolean,
) = Tool(
    name = "trusted_folder_check_links",
    description = """
        Scan all Markdown notes in the bound trusted folder for broken [[wikilinks]] (links to notes that don't exist)
        and empty notes (no body). Returns totalNotes, emptyNotes and brokenLinks (source file, link, target).
        Useful to spot dead links in an Obsidian vault.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { }, required = emptyList())
    },
    needsApproval = needsApproval,
    execute = {
        val files = repository.scanMarkdownFiles(projectId)
        val report = analyzeMarkdownHealth(files)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("totalNotes", report.totalNotes)
                    putJsonArray("emptyNotes") { report.emptyNotes.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("brokenLinks") {
                        report.brokenLinks.forEach { b ->
                            add(
                                buildJsonObject {
                                    put("source", b.source)
                                    put("link", b.link)
                                    put("target", b.target)
                                }
                            )
                        }
                    }
                }.toString()
            )
        )
    },
)

/**
 * 断链/空笔记体检（纯逻辑，可单测）。
 * - 收集所有笔记 basename（去目录、去 .md 后缀）作为有效目标集合
 * - 提取 `[[目标]]` 双链（忽略带扩展名的附件目标，如图片）
 * - 目标 basename 不在笔记集合中 → 断链
 * - 去掉 YAML frontmatter 后正文为空 → 空笔记
 */
fun analyzeMarkdownHealth(files: List<Pair<String, String>>): TrustedFolderHealthReport {
    val basenames = files.map { (path, _) ->
        path.substringAfterLast('/').removeSuffix(".md").removeSuffix(".markdown").trim()
    }.filter { it.isNotEmpty() }.toSet()
    val broken = mutableListOf<TrustedFolderBrokenLink>()
    val empty = mutableListOf<String>()
    val linkRegex = Regex("""\[\[([^\]|#^]+)(?:[#|^][^\]]*)?\]\]""")
    for ((path, content) in files) {
        if (contentWithoutFrontmatter(content).isBlank()) empty += path
        for (m in linkRegex.findAll(content)) {
            val raw = m.groupValues[1].trim()
            if (raw.isEmpty()) continue
            val target = raw.removeSuffix(".md").removeSuffix(".markdown").substringAfterLast('/').trim()
            if (target.isEmpty()) continue
            // 带扩展名的非笔记目标（如附件图片）不判断链
            if (target.contains('.')) continue
            if (target !in basenames) {
                broken += TrustedFolderBrokenLink(source = path, link = m.value, target = raw)
            }
        }
    }
    // 同一篇笔记里多次引用同一目标（如两个 [[X]]）只报一条，否则 UI 用 source:target 作 key 会重复崩溃
    return TrustedFolderHealthReport(
        brokenLinks = broken.distinctBy { it.source to it.target },
        emptyNotes = empty.distinct().sorted(),
        totalNotes = files.size,
    )
}

/** 去掉 YAML frontmatter（`---` 之间的头）后的正文 */
private fun contentWithoutFrontmatter(content: String): String {
    val trimmed = content.trimStart()
    if (!trimmed.startsWith("---")) return trimmed
    val lines = trimmed.lineSequence().toList()
    var end = -1
    for (i in 1 until lines.size) {
        if (lines[i].trim() == "---") { end = i; break }
    }
    if (end < 0) return trimmed
    return lines.drop(end + 1).joinToString("\n")
}

// ---------- helpers ----------

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

/** 必填相对路径：非法（绝对路径、`..` 逃逸等）直接抛错拒绝，并附工具区分引导 */
private fun kotlinx.serialization.json.JsonObject.relPath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    return try {
        SafeFolderAccess.validateRelPath(path)
    } catch (e: IllegalArgumentException) {
        // 绝对路径多半是模型把"沙盒绝对路径"或"设备真实路径"直接填进来了。
        // 引导到 workspace（信任文件夹内路径是相对当前激活项目的），避免模型反复试错。
        val hint = if (path.startsWith("/")) {
            "信任文件夹内路径必须是相对当前绑定项目的相对路径（如 notes/diary.md），不是绝对路径。" +
                "若你想操作工作区沙盒文件，请改用 workspace_* 工具（路径以 /workspace/ 开头）。"
        } else {
            null
        }
        throw IllegalArgumentException(
            if (hint != null) "${e.message}。$hint" else e.message ?: "非法路径"
        )
    }
}

/** 可选相对路径：缺失/空串返回根目录 */
private fun kotlinx.serialization.json.JsonObject.relPathOrEmpty(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim().orEmpty()
    return try {
        SafeFolderAccess.validateRelPath(path)
    } catch (e: IllegalArgumentException) {
        val hint = if (path.startsWith("/")) {
            "信任文件夹内路径必须是相对当前绑定项目的相对路径（如 notes/diary.md），不是绝对路径。" +
                "若你想操作工作区沙盒文件，请改用 workspace_* 工具（路径以 /workspace/ 开头）。"
        } else {
            null
        }
        throw IllegalArgumentException(
            if (hint != null) "${e.message}。$hint" else e.message ?: "非法路径"
        )
    }
}

/** 越界双保险：路径非法时强制 needsApproval=true（实际执行时 [SafeFolderAccess] 仍会硬拒绝） */
private fun kotlinx.serialization.json.JsonObject.pathInvalid(name: String): Boolean =
    runCatching { SafeFolderAccess.validateRelPath(string(name) ?: "") }.isFailure

/** 是否落在配置目录（.obsidian 等）内——写操作强制审批 */
private fun kotlinx.serialization.json.JsonObject.protectedPath(repository: TrustedFolderRepository): Boolean =
    runCatching { repository.isProtectedPath(SafeFolderAccess.validateRelPath(string("path") ?: "")) }
        .getOrDefault(false)

private fun JsonObjectBuilder.putRelPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Path RELATIVE to the trusted folder root, e.g. notes/diary.md"
            } else {
                "Optional path RELATIVE to the trusted folder root, e.g. notes. Omit or use \"\" for the root."
            }
        )
    })
}

private fun TrustedFolderEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}

private fun JsonObjectBuilder.toResultFields(entry: TrustedFolderEntry, action: String = "ok") {
    put("action", action)
    put("path", entry.path)
    put("name", entry.name)
    put("isDirectory", entry.isDirectory)
    put("sizeBytes", entry.sizeBytes)
}
