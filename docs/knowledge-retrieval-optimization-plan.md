# RikkaHub 知识库底层检索优化方案

## 一、问题本质

当前知识库的检索问题不在向量模型（embedding 质量），而在**检索链路的工程实现**：召回路径有 bug、分数语义混乱、关键词检索形同虚设、向量检索是内存暴力扫描。用户看到的"RRF 才 0.03""分数一会相关度一会 RRF"都是这些问题的**表象**，不是检索效果本身差。

Garbage retrieval, garbage answer。

---

## 二、深入探究发现的问题清单（按优先级）

### P0 — 底层召回有真 bug

**1. 内存 BM25 恒返回空（死代码 + 假兜底）**
`Bm25Searcher.bm25Score` 里 `df = 1f`、`corpusSize = 1f`，代入 `idf = ln(1 + (1-1+0.5)/(1+0.5)) = ln(1) = 0`。所有查询词的 IDF 恒为 0 → 所有文档分数恒为 0 → `filter { score > 0f }` 后永远空列表。FTS 可用时它只是兜底不触发，但 **FTS 一旦挂掉，关键词检索静默返回空，用户不知道**。

**2. `VectorStore` 全内存暴力扫描**
每次检索把整个知识库全部 chunk 载入内存，做 512 维线性余弦；`mmrDiversify` 又对每个候选与每个已选做 O(n²) 的 embedding 相似度。知识库上万 chunk 时首检卡顿。且 `toChunkEntity` 把 `embedding = null`、`tokenCount = 0` 塞回结果，后续再序列化会掉数据。

**3. FTS 索引全量重建**
`FtsKeywordSearcher.reconcile` 只比对 chunk 总数与索引总数，任一文档增删改都触发 `rebuildBase` 整库 delete+insert。文档一多，每次检索前都对账重建，极慢。FTS 内置的 BM25 排序能力被浪费，只拿来粗筛 + snippet，真排序靠内存 BM25（还是坏的）。

**4. `hybrid` 模式在未配置 embedding 模型时退化成纯关键词**
`queryEmbedding = null` 时 vector 侧空跑，只剩关键词路径，但用户不知道检索退化了，分数照样显示 RRF 0.02。

**5. `semantic` 分块策略是空壳 + 算法有缺陷**
app 侧 `DocumentProcessor` 的 `when` 里有 `SemanticChunker`，但 `knowledge/processing/DocumentProcessor.kt`（dead code）的 `when` 里没有它。且 `SemanticChunker` 在 `chunkSize=512` 时被"超限即切"主导，话题边界检测几乎不触发；`estimateTokenCount` 只是 `length*0.4` 的拍脑袋估算。

**6. `similarityThreshold` 语义不一致**
`relevance`（rerank 分）走绝对比较（`score >= threshold`），`ranking`（RRF 分）走"相对最大分 × 阈值"。同一滑块两套语义，调了没反应。

### P1 — 召回质量与一致性

**7. 三份 `DocumentProcessor`**
- `app/.../data/DocumentProcessor.kt` — 活实现（`processDocument` 完整走 FTS 索引 + 增量重建）。
- `knowledge/.../processing/DocumentProcessor.kt` — 死代码（`getByBaseId` 方法都不存在，永远编译不进调用链），且 `when` 里没有 `semantic` 分支。
- `StudyDetailActions` — 本地构造一份（`DocumentProcessor(knowledgeManager, settingsStore, providerManager, ftsManager, baseId)`）。

**8. 多库检索参数语义不统一**
`KnowledgeSearchTool.semanticOrHybridSearch` 里 `contextWindow` 取 `firstNotNullOfOrNull`（第一个库的配置），但 `maxTopK` 取 `maxOf`。一个取首个、一个取最大，语义混乱。

**9. `searchTest` 吞异常**
embedding 生成失败返回 `null`、检索异常返回空列表，用户看到"没结果"但不知道为什么。

### P2 — 分数语义与配置体验

**10. 分数展示混乱**
RRF 分（0~0.03）与 rerank 分（0~1）并排显示，标签分两套（`RRF:` / `相关度:`），阈值滑块对 RRF 无效但不提示。**这是用户"RRF 才 0.03""一会相关度一会 RRF"的直接来源。**

**11. 搜索 sheet 塞了 4 个高级参数**
topK / 阈值 / rerank / 关键词权重，全是给懂 RRF 的人看的。普通用户想"试一下检索效果"，被迫先理解概念。

**12. 测试参数与设置页参数两套**
`searchTest` 有独立 topK / 阈值 / 权重，设置页也有一套，容易改了这个忘了那个。

---

## 三、优化方案

### 改动一：修好关键词召回（P0-1、3、4）

**目标**：FTS 成为关键词召回唯一入口，删掉坏掉的内存 BM25，FTS 不可用明确报错而不是静默空。

1. `RetrievalPipeline` 去掉 `Bm25Searcher` 兜底回退：
   - `keywordSearcher` 从可空改为**必填**。
   - 删除 `keywordSearcher` 为 null 时回退 `bm25Searcher` 的分支。
   - `Bm25Searcher.kt` 直接删除（死代码，且恒空）。
2. FTS 不可用时（`FtsKeywordSearcher.search` 抛 `IllegalStateException`）不再 `runCatching` 吞掉，让异常向上冒泡，由调用方（`KnowledgeSearchTool` / `searchTest`）把错误呈现给用户。
3. FTS 增量更新：
   - `FtsKeywordSearcher.reconcile` 从"比对总数"改为**按 `document_id` 级对账**——查询 `knowledge_chunk_fts` 中缺失/多余的 document_id，只重建受影响文档的索引。
   - 需要 `KnowledgeChunkDao` 增加按 document 查询 FTS 对账的 DAO 方法（`getDocumentIdsByKnowledgeBaseId`）。
   - `processDocument` 完成后只重建该文档的索引，而不是整库 `rebuildBase`。

**涉及文件**：
- `knowledge/.../retrieval/RetrievalPipeline.kt`
- `knowledge/.../retrieval/Bm25Searcher.kt`（删）
- `knowledge/.../retrieval/KeywordSearcher.kt`
- `knowledge/.../KnowledgeManager.kt`
- `app/.../data/db/fts/FtsKeywordSearcher.kt`
- `app/.../data/db/fts/KnowledgeChunkFtsManager.kt`
- `knowledge/.../data/dao/KnowledgeChunkDao.kt`

### 改动二：统一检索分数为 0~1 语义（P0-6、P2-10）

**目标**：所有 `RetrievalResult` 的分数统一为 0~1，UI 统一显示"相似度 xx%"，消除 RRF 分数造成的困惑。

1. `RetrievalResult` 改造：
   ```kotlin
   enum class ScoreSource { RERANK, SEMANTIC, HYBRID, KEYWORD }

   data class RetrievalResult(
       val chunk: KnowledgeChunkEntity,
       val score: Float,              // 保留原始分数（排序用）
       val normalizedScore: Float,    // 新增：统一 0~1，UI 用这个
       val scoreSource: ScoreSource,  // 新增：分数来源
       val rank: Int,
       val snippet: String? = null,
   )
   ```
2. 分数归一化规则：
   - **RERANK**：`normalizedScore = relevanceScore`（rerank 接口返回的就是 0~1）。
   - **SEMANTIC**：`normalizedScore = 余弦相似度`（0~1）。
   - **HYBRID**：`normalizedScore = 语义余弦相似度`（RRF 分仅用于排序，不上显示）。
   - **KEYWORD**：`normalizedScore = 1f / (1f + rank)`（相对分，标注"关键词匹配"）。
3. UI 统一显示：
   - `KnowledgeBaseDetailPage` 结果卡片的 `RRF: 0.0234` → `相似度 78%`。
   - `KnowledgeSearchTool.formatResults` 的 `RRF 0.023` → `相似度 78%（关键词）`。
4. 废弃 `scoreKind: String` 字段，改用 `scoreSource` 枚举。

**涉及文件**：
- `knowledge/.../retrieval/RetrievalPipeline.kt`
- `knowledge/.../retrieval/RetrievalResult`（同文件）
- `knowledge/.../tool/KnowledgeSearchTool.kt`
- `app/.../ui/pages/knowledge/KnowledgeBaseDetailPage.kt`
- `app/.../ui/pages/knowledge/KnowledgeBaseDetailVM.kt`

### 改动三：统一阈值语义（P0-6）

**目标**：`similarityThreshold` 只对语义/rerank 结果做绝对过滤，关键词结果不参与。

1. `RetrievalPipeline.applyThreshold`：
   - `scoreSource == SEMANTIC || RERANK` → `normalizedScore >= threshold` 保留。
   - `scoreSource == HYBRID` → 用语义分数（`normalizedScore`）`>= threshold`。
   - `scoreSource == KEYWORD` → **不参与阈值过滤**，永远保留。
2. UI：
   - `KnowledgeBaseDetailPage` 搜索 sheet：纯关键词模式（`queryEmbedding` 不可用）隐藏阈值滑块，注明"关键词匹配不做相似度过滤"。
   - `KnowledgeBaseSettingsPage`：把 `similarityThreshold` 的显示加回来（现在存了但设置页不显示）。

**涉及文件**：
- `knowledge/.../retrieval/RetrievalPipeline.kt`
- `app/.../ui/pages/knowledge/KnowledgeBaseDetailPage.kt`
- `app/.../ui/pages/knowledge/KnowledgeBaseSettingsPage.kt`

### 改动四：向量检索性能（P0-2）

**前提验证结果**：检查 `app-arm64-v8a-debug.apk` 内的 `libsimple.so`，**无 vec0 / sqlite-vec 符号**（`vec0:0, sqlite_vector:0`）。当前 SQLite 不含向量扩展。引入 vec0 需重编 native fork，超出本次范围。**走方案 B（内存分片余弦）。**

1. `VectorStore` 重构：
   - **按文档分片缓存**：`CachedVector` 按 `documentId` 分组，检索时按需加载文档分片，避免全库一次性载入。
   - **分批余弦**：固定维度 `FloatArray`，分片计算，避免一次性载入全部 embedding。
   - **分块缓存上限提升**：`DEFAULT_MAX_CACHED_BASES` 从 5 提到 10（配合分片，内存可控）。
   - `toChunkEntity` 修复：保留 `tokenCount`，不再塞 `embedding = null` 的假数据（从 DAO 重新加载完整 entity）。
2. MMR O(n²) 优化：
   - 预计算候选间相似度矩阵（`Map<chunkId, Map<chunkId, Float>>`），避免 `mmrDiversify` 里反复 `getEmbedding`。
   - 缓存查询 embedding，避免重复计算。
3. `tokenCount` 修复：`Chunk.tokenCount` 的 `estimateTokenCount` 保留（分块用），但**存储时**直接用 chunk 的字符长度估算（与现有逻辑一致），不引入额外计算。

**涉及文件**：
- `knowledge/.../vector/VectorStore.kt`
- `knowledge/.../retrieval/RetrievalPipeline.kt`（MMR）
- `app/.../data/DocumentProcessor.kt`（tokenCount 存储）

### 改动五：清理死代码与一致性（P0-5、P1-7、8、9）

1. 删 `knowledge/.../processing/DocumentProcessor.kt` 死代码（确认无引用后）。
2. `StudyDetailActions` 里的本地构造改为复用 app 侧实现（或确认其用途后保留）。
3. `semantic` 分块策略：由于 `SemanticChunker` 算法有缺陷，**从 UI 选项里隐藏**（`CHUNK_STRATEGIES` 去掉 `"semantic"`），避免误导。未来修好算法再放回。
4. 多库检索参数统一：
   - `KnowledgeSearchTool` 的 `contextWindow` 改为**按命中文档的库取**（与 `maxTopK` 一致）。
5. `searchTest` 不吞异常：把失败原因（embedding 失败 / 检索异常 / 无模型）显式写入返回给 UI 的字段。

**涉及文件**：
- `knowledge/.../processing/DocumentProcessor.kt`（删）
- `app/.../ui/pages/study/StudyDetailActions.kt`
- `app/.../ui/pages/knowledge/KnowledgeBaseSettingsPage.kt`
- `knowledge/.../tool/KnowledgeSearchTool.kt`
- `app/.../ui/pages/knowledge/KnowledgeBaseDetailVM.kt`

### 改动六：UI 配置体验（P2-11、12）

1. 搜索 sheet 精简：
   - 默认只显示输入框 + 结果列表 + 耗时。
   - topK / 阈值 / rerank / 关键词权重收进"高级参数"折叠区。
2. 纯关键词模式隐藏阈值滑块。
3. 设置页把 `similarityThreshold` 显示出来（改动的三）。
4. 消除两套参数：`searchTest` 首次搜索用知识库设置初始化参数（已有 `searchParamsInitialized` 逻辑），设置页改动后刷新。

**涉及文件**：
- `app/.../ui/pages/knowledge/KnowledgeBaseDetailPage.kt`
- `app/.../ui/pages/knowledge/KnowledgeBaseSettingsPage.kt`
- `app/.../ui/pages/knowledge/KnowledgeBaseDetailVM.kt`

---

## 四、验证方式

1. **构建**：`./gradlew :app:assembleDebug`，确保编译通过。
2. **关键词检索**：导入一个含明确关键词的文档，检索该词，确认命中（FTS 真实索引生效，非内存 BM25）。
3. **分数显示**：检索测试 sheet 确认分数显示为"相似度 xx%"（0~1），不再出现"RRF 0.02"。
4. **阈值过滤**：配 embedding 模型后，调阈值确认低相似度结果被过滤；纯关键词模式确认阈值滑块隐藏。
5. **向量性能**：导入较大文档（数百 chunk），确认首检不再明显卡顿。
6. **语义分块**：确认 UI 不再显示"语义"分块选项。

---

## 五、需要确认的问题

1. **semantic 分块是隐藏还是修好**？方案默认隐藏。若想保留，需另起一轮专门修 `SemanticChunker`。
2. **FTS 增量对账**：需要新增 DAO 方法，是否接受改动 `knowledge_chunk_fts` 表结构（加索引）？默认只加对账查询，不动表结构。
3. **`StudyDetailActions` 里的 `DocumentProcessor` 构造**：是学习场景的独立用例，还是可以复用 app 侧实现？需确认其用途再决定。

---

## 六、结论

> **让 FTS 成为关键词检索唯一真实来源，删掉坏掉的内存 BM25；把检索分数统一成 0~1 语义，消除"RRF 0.03"的困惑；阈值只对语义/rerank 生效；向量检索按文档分片加载 + 预计算相似度矩阵优化性能；清理死代码，统一多库参数；精简搜索 sheet 的配置交互。**

六条改动覆盖 P0（召回正确性）到 P2（配置体验），不引入新的 native 依赖（vec0 验证不可用），全部基于现有 FTS5 + Room 架构落地。
