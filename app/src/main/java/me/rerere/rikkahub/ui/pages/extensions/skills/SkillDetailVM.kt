package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillSource
import me.rerere.rikkahub.data.files.SkillUpdateManager
import java.io.File

data class SkillFile(
    val file: File,
    val relativePath: String,
)

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

class SkillDetailVM(
    private val skillManager: SkillManager,
    private val skillUpdateManager: SkillUpdateManager,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    /** 技能元信息（frontmatter 解析结果），信息头展示用 */
    private val _meta = MutableStateFlow<SkillMetadata?>(null)
    val meta = _meta.asStateFlow()

    /** 来源注册表快照（skillName -> 来源），页面按技能名取用 */
    val sources = skillUpdateManager.sources

    /** 自动更新全局总闸（默认关）：关时不展示按技能自动更新开关 */
    val autoUpdateGloballyEnabled: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { it.skillAutoUpdateEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 本地改动的 tracked 文件清单（相对安装清单），信息头展示用 */
    private val _modifiedFiles = MutableStateFlow<List<String>>(emptyList())
    val modifiedFiles = _modifiedFiles.asStateFlow()

    /** 已准备好的增量更新（预览弹窗数据源），null 表示无 */
    private val _pendingPrepared = MutableStateFlow<SkillUpdateManager.PreparedUpdate?>(null)
    val pendingPrepared = _pendingPrepared.asStateFlow()

    /** 更新准备/应用进行中 */
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private var skillName = ""

    fun init(name: String) {
        if (skillName == name) return
        skillName = name
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = skillManager.getSkillDir(skillName) ?: return@launch
            _tree.value = buildTree(dir, dir)
            _meta.value = skillManager.listSkills().find { it.name == skillName }
            _modifiedFiles.value = runCatching {
                skillUpdateManager.localModifiedFiles(skillName)
            }.getOrDefault(emptyList())
        }
    }

    private fun buildTree(root: File, dir: File): List<SkillFileNode> {
        val items = dir.listFiles()?.toList() ?: return emptyList()
        val files = items
            .filter { it.isFile }
            .sortedWith(compareBy({ it.name != "SKILL.md" }, { it.name }))
            .map { f -> SkillFileNode.FileNode(SkillFile(f, f.relativeTo(root).path)) }
        val dirs = items
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { d -> SkillFileNode.DirNode(d.name, d.relativeTo(root).path, buildTree(root, d)) }
        return dirs + files
    }

    fun readFile(skillFile: SkillFile): String = skillFile.file.readText()

    // Returns null on success, error message on failure
    fun saveFile(relativePath: String, content: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (relativePath == "SKILL.md") {
                val name = SkillFrontmatterParser.parse(content)["name"]
                if (name != skillName) {
                    withContext(Dispatchers.Main) { onResult("不允许修改技能名称（name 字段必须为 \"$skillName\"）") }
                    return@launch
                }
            }
            val success = skillManager.saveSkillFile(skillName, relativePath, content)
            loadFiles()
            withContext(Dispatchers.Main) { onResult(if (success) null else "保存失败") }
        }
    }

    fun deleteFile(skillFile: SkillFile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = skillManager.deleteSkillFile(skillName, skillFile.relativePath)
            if (success) loadFiles()
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    /**
     * 增量更新准备：下载变更文件到内存，完成后由页面弹出预览。
     * 与列表页同一套流程（SkillUpdateManager.prepareUpdate），仅 UI 挂点不同。
     */
    fun prepareUpdate(onResult: (SkillUpdateManager.PrepareResult) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { skillUpdateManager.prepareUpdate(skillName) }
                .getOrElse { SkillUpdateManager.PrepareResult.Failed(it.message ?: "unknown") }
            _busy.value = false
            loadFiles()
            if (result is SkillUpdateManager.PrepareResult.Prepared) {
                _pendingPrepared.value = result.update
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun applyPrepared(onResult: (SkillUpdateManager.ApplyResult) -> Unit) {
        val prepared = _pendingPrepared.value ?: return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { skillUpdateManager.applyPrepared(prepared) }
                .getOrElse { SkillUpdateManager.ApplyResult.Failed(it.message ?: "unknown") }
            _busy.value = false
            _pendingPrepared.value = null
            loadFiles()
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun dismissPrepared() {
        _pendingPrepared.value = null
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            skillUpdateManager.setAutoUpdate(skillName, enabled)
        }
    }
}
