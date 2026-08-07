package me.rerere.rikkahub.data.sync

import android.util.JsonReader
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 备份包内容的轻量预览信息，用于导入确认对话框。
 */
data class BackupPreview(
    val hasSettings: Boolean = false,
    val hasDatabase: Boolean = false,
    val uploadFileCount: Int = 0,
    val skillFileCount: Int = 0,
    val fontFileCount: Int = 0,
)

/**
 * 解析本地备份 zip / Chatbox json / Cherry Studio zip 的内容概要。
 */
object BackupPreviewAnalyzer {
    /**
     * 按导入类型分析文件，返回可展示的中文描述文本。
     * 分析失败时返回 null，调用方应静默忽略预览。
     */
    fun analyze(file: File, type: String): String? {
        return runCatching {
            when (type) {
                "local" -> analyzeZip(file).describe()
                "chatbox" -> "包含 ${file.countChatboxSessions()} 个会话"
                "cherry" -> {
                    val count = CherryStudioProviderImporter.importProviders(file).size
                    "包含 $count 个 Provider"
                }

                else -> null
            }
        }.getOrNull()
    }

    fun analyzeZip(file: File): BackupPreview {
        var hasSettings = false
        var hasDatabase = false
        var uploadFileCount = 0
        var skillFileCount = 0
        var fontFileCount = 0

        ZipInputStream(FileInputStream(file)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                when (val name = entry?.name ?: "") {
                    "settings.json" -> hasSettings = true
                    "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> hasDatabase = true
                    else -> when {
                        name.startsWith("${FileFolders.UPLOAD}/") -> uploadFileCount++
                        name.startsWith("${FileFolders.SKILLS}/") -> skillFileCount++
                        name.startsWith("${FileFolders.FONTS}/") -> fontFileCount++
                    }
                }
                zipIn.closeEntry()
            }
        }

        return BackupPreview(
            hasSettings = hasSettings,
            hasDatabase = hasDatabase,
            uploadFileCount = uploadFileCount,
            skillFileCount = skillFileCount,
            fontFileCount = fontFileCount,
        )
    }

    private fun BackupPreview.describe(): String {
        val parts = mutableListOf<String>()
        if (hasSettings) parts.add("设置")
        if (hasDatabase) parts.add("聊天记录")
        if (uploadFileCount > 0) parts.add("上传文件×$uploadFileCount")
        if (skillFileCount > 0) parts.add("技能文件×$skillFileCount")
        if (fontFileCount > 0) parts.add("字体×$fontFileCount")
        return if (parts.isEmpty()) {
            "未检测到可导入内容"
        } else {
            "包含：${parts.joinToString("、")}"
        }
    }

    private fun File.countChatboxSessions(): Int {
        var count = 0
        bufferedReader(Charsets.UTF_8).use { reader ->
            val jsonReader = JsonReader(reader).apply { isLenient = true }
            try {
                jsonReader.beginObject()
                while (jsonReader.hasNext()) {
                    val name = jsonReader.nextName()
                    if (name.startsWith("session:")) count++
                    jsonReader.skipValue()
                }
                jsonReader.endObject()
            } catch (e: Exception) {
                return 0
            }
        }
        return count
    }
}
