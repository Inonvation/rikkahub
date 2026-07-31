package me.rerere.document

import java.io.File
import java.util.zip.ZipInputStream

object XlsxParser {
    fun parse(file: File): String {
        return try {
            file.inputStream().use { fileInputStream ->
                ZipInputStream(fileInputStream).use { zipStream ->
                    var sharedStringsText: String? = null
                    val sheetXmlTexts = mutableListOf<String>()

                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "xl/sharedStrings.xml" -> {
                                sharedStringsText = zipStream.bufferedReader().readText()
                            }
                            entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) -> {
                                sheetXmlTexts.add(zipStream.bufferedReader().readText())
                            }
                        }
                        entry = zipStream.nextEntry
                    }

                    if (sheetXmlTexts.isEmpty()) return "Excel 文件中没有找到工作表"

                    val sharedStrings = parseSharedStringsFromText(sharedStringsText ?: "")

                    val result = StringBuilder()
                    sheetXmlTexts.forEachIndexed { index, xml ->
                        if (index > 0) result.append("\n")
                        result.append(parseSheetFromText(xml, sharedStrings))
                    }
                    result.toString().trim()
                }
            }
        } catch (e: Exception) {
            "Error parsing Excel file: ${e.message}"
        }
    }

    private fun parseSharedStringsFromText(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val strings = mutableListOf<String>()
        // 逐个 <si> 块扫描，避免 DOT_MATCHES_ALL 对整个大串建捕获组
        var cursor = 0
        while (true) {
            val siStart = xml.indexOf("<si>", cursor)
            if (siStart < 0) break
            val siEnd = xml.indexOf("</si>", siStart)
            if (siEnd < 0) break
            val siContent = xml.substring(siStart + 4, siEnd)
            strings.add(extractTTexts(siContent))
            cursor = siEnd + 5
        }
        return strings
    }

    private fun extractTTexts(siContent: String): String {
        val sb = StringBuilder()
        var cursor = 0
        while (true) {
            val tStart = siContent.indexOf("<t", cursor)
            if (tStart < 0) break
            val gt = siContent.indexOf('>', tStart)
            if (gt < 0) break
            val tEnd = siContent.indexOf("</t>", gt)
            if (tEnd < 0) break
            sb.append(siContent, gt + 1, tEnd)
            cursor = tEnd + 4
        }
        return sb.toString()
    }

    private fun parseSheetFromText(xml: String, sharedStrings: List<String>): String {
        val result = StringBuilder()
        // 逐 <row> 块扫描，避免 DOT_MATCHES_ALL 对整个大串建捕获组
        var cursor = 0
        while (true) {
            val rowStart = xml.indexOf("<row", cursor)
            if (rowStart < 0) break
            val rowContentStart = xml.indexOf('>', rowStart)
            if (rowContentStart < 0) break
            val rowEnd = xml.indexOf("</row>", rowContentStart)
            if (rowEnd < 0) break

            val rowContent = xml.substring(rowContentStart + 1, rowEnd)
            val cells = parseCellsFromRow(rowContent, sharedStrings)
            if (cells.isNotEmpty()) {
                result.append(cells.joinToString("\t"))
                result.append("\n")
            }
            cursor = rowEnd + 6
        }
        return result.toString()
    }

    private fun parseCellsFromRow(rowContent: String, sharedStrings: List<String>): List<String> {
        val cells = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val cStart = rowContent.indexOf("<c", cursor)
            if (cStart < 0) break
            val gt = rowContent.indexOf('>', cStart)
            if (gt < 0) break
            // cell 标签属性（含 t="s" 类型标记）
            val cOpenTag = rowContent.substring(cStart, gt + 1)
            val cellType = Regex("""t\s*=\s*"([^"]*)"""").find(cOpenTag)?.groupValues?.get(1) ?: ""
            // 有 <v> 子元素时，其闭合标签是 </c>
            val hasV = rowContent.indexOf("<v>", gt + 1)
            val cEnd = rowContent.indexOf("</c>", gt + 1)
            if (cEnd < 0) break

            val cellContent = if (hasV in gt + 1 until cEnd) {
                rowContent.substring(gt + 1, cEnd)
            } else {
                // 内联字符串/无 <v> 的情况，取开标签到 </c> 的文本
                rowContent.substring(gt + 1, cEnd)
            }

            val value = if (hasV in gt + 1 until cEnd) {
                val vMatch = Regex("<v>(.*?)</v>").find(cellContent)
                vMatch?.groupValues?.get(1) ?: ""
            } else {
                // 内联字符串（t="inlineStr"）取 <t>...</t>
                val tMatch = Regex("<t[^>]*>(.*?)</t>").find(cellContent)
                tMatch?.groupValues?.get(1) ?: ""
            }

            val displayValue = when (cellType) {
                "s" -> {
                    val idx = value.toIntOrNull() ?: -1
                    if (idx in sharedStrings.indices) sharedStrings[idx] else ""
                }
                "b" -> if (value == "1") "TRUE" else "FALSE"
                else -> value
            }
            cells.add(displayValue)
            cursor = cEnd + 4
        }
        return cells
    }
}
