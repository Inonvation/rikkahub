package me.rerere.knowledge.retrieval

/**
 * 检索查询类型策略：对齐 NoteGen `rag-retrieval-policy.ts` 的动态权重思路。
 *
 * NoteGen 按 query 特征把检索分成 exact / short / multi-constraint / semantic 四类，
 * 每类切换「关键词 / 向量 / 模糊」的相对权重——避免一种查询类型下另一路的好结果被挤掉
 * （例如精确的文件名/ID 查询，纯语义检索几乎命中不了，必须关键词主导）。
 *
 * 本项目是双路（关键词 FTS + 向量），把 NoteGen 的三路权重比映射到 rrfFusion 的
 * `keywordWeight` 参数（>1 关键词主导，<1 向量主导，1 等权）：
 * - exact（精确标识符）→ 关键词主导（对齐 0.6 : 0.25 ≈ 2.4）
 * - multi-constraint（多条件）→ 向量主导（对齐 0.25 : 0.6 ≈ 0.42）
 * - short（短查询）→ 略偏词面（对齐 0.25 : 0.4 ≈ 0.625，取 1.2 避免过度）
 * - semantic（默认）→ 用知识库配置的基准权重
 */
object RetrievalQueryStrategy {
    /** 精确标识符：数字-字母组合 ID（如 abc-123 / v2_0）、纯 2 位以上数字、带扩展名的文件名 */
    private val exactIdentifierRegex = Regex(
        """\b(?=[a-zA-Z0-9_-]*[a-zA-Z])(?=[a-zA-Z0-9_-]*\d)[a-zA-Z0-9]+(?:[-_][a-zA-Z0-9]+)+\b"""
    )
    private val pureNumberRegex = Regex("""\b\d{2,}\b""")
    private val fileNameRegex = Regex("""[\w.-]+\.\w{2,4}\b""")

    private val cjkRegex = Regex("""[一-鿿぀-ヿ가-힯]""")

    /** 多约束标记：命中 ≥2 个或含两个分号视为多条件查询 */
    private val multiConstraintMarkers = listOf(
        "同时", "并且", "而且", "必须", "不能", "不得", "以及", "且", "还要", "并且要",
        " and ", " with ", " without ", " must ", " not ",
    )
    private val doubleSemicolonRegex = Regex("""[；;].+[；;]""")

    /**
     * 根据 query 计算关键词检索的相对权重。
     *
     * @param query 检索词
     * @param baseWeight 知识库配置的基准关键词权重（默认 1f）
     */
    fun keywordWeight(query: String, baseWeight: Float): Float {
        val compact = query.replace(Regex("""[\p{P}\p{S}\s]"""), "")
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val isShort = if (cjkRegex.containsMatchIn(query)) compact.length <= 6 else words.size <= 2

        return when {
            hasExactIdentifier(query) -> 2.4f
            hasMultipleConstraints(query) -> 0.5f
            isShort -> 1.2f
            else -> baseWeight
        }
    }

    private fun hasExactIdentifier(query: String): Boolean =
        exactIdentifierRegex.containsMatchIn(query) ||
            pureNumberRegex.containsMatchIn(query) ||
            fileNameRegex.containsMatchIn(query)

    private fun hasMultipleConstraints(query: String): Boolean {
        val markerHits = multiConstraintMarkers.count { query.contains(it) }
        return markerHits >= 2 || doubleSemicolonRegex.containsMatchIn(query)
    }
}
