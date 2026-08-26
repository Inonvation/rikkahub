package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"

        /** SKILL.md 体积上限（字符），超限拒绝写入并保留旧文件。 */
        private const val MAX_SKILL_SIZE = 1_000_000
    }

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSkills(): List<SkillMetadata> {
        val skillsDir = getSkillsDir()
        val skills = skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?: emptyList()
        return applySkillOrder(skills)
    }

    /**
     * 按 settings.skillOrder 中记录的技能名排序，未记录（新导入等）的技能排在最前面。
     */
    private fun applySkillOrder(skills: List<SkillMetadata>): List<SkillMetadata> {
        val order = settingsStore.settingsFlow.value.skillOrder
        if (order.isEmpty() || skills.size <= 1) return skills
        val orderIndex = order.withIndex().associate { it.value to it.index }
        return skills.sortedBy { orderIndex[it.name] ?: -1 }
    }

    /**
     * 把指定技能移动到新位置并持久化到 settings.skillOrder。
     */
    fun reorderSkill(skillName: String, newIndex: Int): List<SkillMetadata> {
        val current = listSkills()
        val oldIndex = current.indexOfFirst { it.name == skillName }
        if (oldIndex < 0) return current
        val newList = current.toMutableList()
        val item = newList.removeAt(oldIndex)
        newList.add(newIndex.coerceIn(0, newList.size), item)
        persistSkillOrder(newList.map { it.name })
        return newList
    }

    /**
     * 按当前展示顺序持久化技能排序。
     */
    fun persistSkillOrder(skillNames: List<String>) {
        runBlocking {
            settingsStore.update { settings ->
                settings.copy(skillOrder = skillNames)
            }
        }
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 先校验再提交：格式非法时**不写盘**（保留旧文件），避免坏 SKILL.md 落盘后再解析失败的
        // “先写后错”路径。校验要求与 SkillsVM / SkillDetailVM 的前置校验保持一致（name/description），
        // 并额外强制 frontmatter 存在、name 与目录名一致、大小上限。
        validateSkillContent(name, content)?.let { reason ->
            Log.w(TAG, "saveSkill rejected: $name ($reason)")
            return null
        }
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray(Charsets.UTF_8)))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    /**
     * 校验 SKILL.md 内容；返回 null 表示合法，否则返回错误原因。
     *
     * 只做“能否安全落盘、能否被系统解析”的硬校验，不校验业务内容；与现有
     * [parseSkillFile] 的准入条件一致（frontmatter 存在、name/description 非空），
     * 额外强制 name 与技能目录名一致并限制体积。
     */
    private fun validateSkillContent(name: String, content: String): String? {
        if (content.isBlank()) return "SKILL.md 内容为空"
        if (content.length > MAX_SKILL_SIZE) return "SKILL.md 超出大小限制"
        val frontmatter = SkillFrontmatterParser.parse(content)
        if (frontmatter === SkillFrontmatter.Empty) return "SKILL.md 缺少有效的 frontmatter"
        val normalizedName = name.trim()
        val fmName = frontmatter["name"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return "SKILL.md 缺少 name 字段"
        if (fmName != normalizedName) return "SKILL.md 的 name 与技能目录名不一致"
        if (frontmatter["description"]?.trim()?.isNotBlank() != true) {
            return "SKILL.md 缺少 description 字段"
        }
        return null
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false

        // 对 SKILL.md 强制校验后再落盘：避免详情页单文件保存直接 writeText 把坏文件写进去
        if (relativePath.equals("SKILL.md", ignoreCase = true)) {
            validateSkillContent(skillName, content)?.let { reason ->
                Log.w(TAG, "saveSkillFile rejected: $skillName/$relativePath ($reason)")
                return false
            }
        }

        target.parentFile?.mkdirs()
        // 单文件原子替换：临时文件 + rename；避免直接 writeText 非受控覆盖崩溃/写坏
        val tmp = File(target.parentFile, target.name + ".tmp")
        return runCatching {
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                // rename 失败（目标已存在/平台差异）时回退直接覆盖，保证不 crash
                tmp.delete()
                target.writeText(content, Charsets.UTF_8)
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "saveSkillFile failed: $skillName/$relativePath", e)
            tmp.delete()
            false
        }
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            if (name == null) {
                Log.w(TAG, "parseSkillFile: missing name in ${skillFile.absolutePath}")
                return null
            }
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            if (description == null) {
                Log.w(TAG, "parseSkillFile: missing description in ${skillFile.absolutePath}")
                return null
            }
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                // Anthropic Agent Skills 规范的可选字段：技能可用的工具白名单
                allowedTools = frontmatter.getStringList("allowed-tools") ?: emptyList(),
                skillDir = skillDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    /** 技能可用的工具白名单（Anthropic Agent Skills allowed-tools，可空） */
    val allowedTools: List<String> = emptyList(),
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
