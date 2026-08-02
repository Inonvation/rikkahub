package me.rerere.rikkahub.data.model

/**
 * 学习工具的固定学科字典。三个学习面板（笔记/错题本/知识点卡片）共用。
 */
object StudySubject {
    const val ENGLISH = "english"
    const val MATH = "math"
    const val POLITICS = "politics"
    const val MECHANICS = "mechanics"
    const val OTHER = "other"

    /** 固定顺序，用于 Tab 排序 */
    val ORDERED_CODES = listOf(ENGLISH, MATH, POLITICS, MECHANICS, OTHER)

    val CODE_TO_NAME = mapOf(
        ENGLISH to "英语",
        MATH to "数学",
        POLITICS to "政治",
        MECHANICS to "机械原理",
        OTHER to "其他",
    )

    private val ALIASES = mapOf(
        "英语" to ENGLISH,
        "数学" to MATH,
        "政治" to POLITICS,
        "机械原理" to MECHANICS,
        "机原" to MECHANICS,
        "其他" to OTHER,
    )

    /** 未知值归到 "other"，兼容现有 english/math/politics/mechanics 及中文别名 */
    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return OTHER
        val code = trimmed.lowercase()
        if (code in ORDERED_CODES) return code
        return ALIASES[trimmed] ?: OTHER
    }

    fun name(code: String?): String = CODE_TO_NAME[normalize(code)] ?: OTHER
}
