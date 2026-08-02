package me.rerere.rikkahub.data.ai.tools

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.XlsxParser
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** 单个文档解析结果上限，防止超大文档撑爆子代理上下文 */
private const val MAX_DOCUMENT_CHARS = 50_000

/**
 * 文档读取工具：供文档分析子代理解析本地文档（PDF/Word/PPT/EPUB/XLSX/文本）。
 *
 * path 支持三种输入：
 * - 本地绝对路径（/data/user/0/.../files/upload/xxx.pdf）
 * - file:// URI（UIMessagePart.Document.url 的格式）
 * - /upload/<name>（workspace 内路径，映射到 app 私有 upload 目录）
 */
fun createDocumentReadTool(): Tool {
    return Tool(
        name = "document_read",
        description = """
            Read and extract text content from a local document file.
            Supports: PDF, DOCX, PPTX, EPUB, XLSX, CSV, TXT, MD, JSON, XML, HTML.
            Use this when the task requires analyzing an uploaded document.

            Parameter:
            - path: local file absolute path, or file:// URI, or /upload/<name>.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "文档本地绝对路径，或 file:// URI，或 /upload/<name>")
                    })
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"缺少 path 参数\"}"))

            val result = runCatching {
                val file = DocumentPathResolver.resolve(path)
                if (file == null || !file.exists() || !file.isFile) {
                    "{\"error\":\"文件不存在: $path\"}"
                } else {
                    val text = parseDocument(file)
                    if (text.length > MAX_DOCUMENT_CHARS) {
                        text.take(MAX_DOCUMENT_CHARS) + "\n...[内容过长已截断，共 ${text.length} 字符]"
                    } else text
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                "{\"error\":\"解析失败: ${e.message}\"}"
            }
            listOf(UIMessagePart.Text(result))
        },
    )
}

/** 解析 path 到本地文件（依赖 FilesManager 解析 upload 目录） */
private object DocumentPathResolver : KoinComponent {
    private val filesManager: me.rerere.rikkahub.data.files.FilesManager by inject()

    suspend fun resolve(path: String): File? {
        if (path.isBlank()) return null
        // file:// URI
        if (path.startsWith("file://")) {
            return runCatching { path.toUri().toFile() }.getOrNull()
        }
        // /upload/<name>：映射到 app 私有 upload 目录
        if (path.startsWith("/upload/")) {
            val name = path.removePrefix("/upload/")
            val entity = runCatching {
                filesManager.getByRelativePath("upload/$name")
            }.getOrNull()
            return entity?.let { filesManager.getFile(it) }
        }
        // 绝对路径
        return File(path)
    }
}

/** 按扩展名分发解析器（与 DocumentProcessor.parseDocument 对齐） */
private suspend fun parseDocument(file: File): String {
    val ext = file.extension.lowercase()
    return when (ext) {
        "pdf" -> PdfParser.parserPdf(file)
        "docx" -> DocxParser.parse(file)
        "pptx" -> PptxParser.parse(file)
        "epub" -> EpubParser.parse(file)
        "xlsx" -> XlsxParser.parse(file)
        "xls" -> "旧版 .xls 格式暂不支持，请在 Excel 中另存为 .xlsx 格式"
        "csv", "txt", "md", "markdown", "json", "xml", "html", "htm" -> file.readText()
        else -> file.readText()
    }
}
