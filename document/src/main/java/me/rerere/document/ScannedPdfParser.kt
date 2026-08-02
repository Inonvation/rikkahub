package me.rerere.document

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 扫描版 PDF 解析器：对无文本层的页面，逐页渲染成 Bitmap，交给 ML Kit 本地 OCR 识别。
 *
 * 背景：MuPDF 的 StructuredText.asText() 对扫描版 PDF（本质是图片）返回空字符串，
 * 导致下游分块/向量化喂入空文本。本解析器在文本层缺失时兜底，用端侧 OCR 补出文字。
 *
 * ML Kit 使用 bundled 版（com.google.mlkit:text-recognition-chinese），
 * 模型打进 APK、完全离线、不依赖 Google Play 服务，适合国内设备。
 */
class ScannedPdfParser {

    /**
     * 渲染 DPI。300 是 OCR 对小字/密集排版的甜点区，比 200 明显更准；
     * 超过 300 边际收益下降，且 ML Kit 内部会缩放图像，更高分辨率徒增内存。
     * A4 页约 2480×3508，ARGB_8888 约 34MB，逐页处理可接受。
     */
    private val renderDpi = 300f

    /**
     * 解析 PDF：逐页取文本层，缺失的页面走 OCR。
     * @param file PDF 文件
     * @param enableOcr 是否对无文本层的页面启用 OCR；false 时行为与 [PdfParser.parserPdf] 一致
     * @return 拼接后的全文（含页码标记）
     */
    suspend fun parse(file: File, enableOcr: Boolean): String {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        val pages = document.countPages()
        val result = StringBuilder()
        val recognizer = if (enableOcr) {
            // 惰性创建，仅在存在扫描页时才真正初始化模型
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        } else null
        try {
            for (i in 0 until pages) {
                val page = document.loadPage(i)
                val text = page.toStructuredText().asText()
                result.append("---").append("Page ${i + 1}:\n")
                if (text.isNotBlank()) {
                    // 有文本层：直接用（与 PdfParser 一致）
                    result.append(text).appendLine()
                } else if (recognizer != null) {
                    // 无文本层：渲染成图 → ML Kit OCR
                    val bitmap = AndroidDrawDevice.drawPage(page, renderDpi)
                    val ocrText = try {
                        recognize(recognizer, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    result.append(ocrText.ifBlank { "[扫描页未能识别出文字]" }).appendLine()
                } else {
                    result.appendLine()
                }
            }
        } finally {
            recognizer?.close()
        }
        return result.toString()
    }

    private suspend fun recognize(recognizer: com.google.mlkit.vision.text.TextRecognizer, bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text: Text = await(recognizer.process(image))
        return buildString {
            text.textBlocks.forEach { block ->
                // block 大致对应一个段落（视觉区块），用双换行分隔，
                // 让下游 chunker（sentence/fixed-size）能正确识别段落边界
                if (isNotEmpty()) append("\n\n")
                block.lines.forEach { line ->
                    append(line.text).appendLine()
                }
            }
        }.trim()
    }

    /** 把 Google Play Services 的 Task 转成 suspend 协程。 */
    private suspend fun <T> await(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
        task.addOnFailureListener { cont.resumeWithException(it) }
    }
}
