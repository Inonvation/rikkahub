package me.rerere.document

import java.io.File
import java.util.zip.ZipInputStream

object XlsxParser {
    fun parse(file: File): String {
        return try {
            file.inputStream().use { fileInputStream ->
                ZipInputStream(fileInputStream).use { zipStream ->
                    var sharedStringsText = ""
                    val sheetTexts = mutableListOf<String>()

                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "xl/sharedStrings.xml" -> {
                                sharedStringsText = zipStream.bufferedReader().readText()
                            }
                            entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) -> {
                                sheetTexts.add(zipStream.bufferedReader().readText())
                            }
                        }
                        entry = zipStream.nextEntry
                    }

                    if (sheetTexts.isEmpty()) return "Excel 文件中没有找到工作表"

                    val sharedStrings = parseSharedStringsFromText(sharedStringsText)

                    val result = StringBuilder()
                    sheetTexts.forEachIndexed { index, xml ->
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
        val strings = mutableListOf<String>()
        // Match <si>...</si> blocks, extract text from <t> or <r><t> elements
        val siRegex = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
        val tRegex = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)

        for (siMatch in siRegex.findAll(xml)) {
            val siContent = siMatch.groupValues[1]
            val texts = tRegex.findAll(siContent).map { it.groupValues[1] }.toList()
            strings.add(texts.joinToString(""))
        }
        return strings
    }

    private fun parseSheetFromText(xml: String, sharedStrings: List<String>): String {
        val result = StringBuilder()
        val rowRegex = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cRegex = Regex("<c[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        val vRegex = Regex("<v>(.*?)</v>")
        val tAttrRegex = Regex("""t\s*=\s*"([^"]*)"""")

        for (rowMatch in rowRegex.findAll(xml)) {
            val rowContent = rowMatch.groupValues[1]
            val cells = mutableListOf<String>()

            for (cMatch in cRegex.findAll(rowContent)) {
                val cContent = cMatch.groupValues[1]
                // Check cell type: s=shared string, b=boolean, otherwise inline/number
                val cellType = tAttrRegex.find(cMatch.value)?.groupValues?.get(1) ?: ""
                val vMatch = vRegex.find(cContent)
                val value = vMatch?.groupValues?.get(1) ?: ""

                val displayValue = when (cellType) {
                    "s" -> {
                        val idx = value.toIntOrNull() ?: -1
                        if (idx in sharedStrings.indices) sharedStrings[idx] else ""
                    }
                    "b" -> if (value == "1") "TRUE" else "FALSE"
                    else -> value
                }
                cells.add(displayValue)
            }

            if (cells.isNotEmpty()) {
                result.append(cells.joinToString("\t"))
                result.append("\n")
            }
        }

        return result.toString()
    }
}