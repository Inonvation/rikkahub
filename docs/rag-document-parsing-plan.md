# RikkaHub 知识库文档解析增强方案

## 一、问题本质

当前知识库的缺陷不在向量模型，而在**文档→文本的转换环节**。

- **扫描版 PDF**：本质是图片，没有文本层。现有 `PdfParser` 基于 MuPDF 的 `StructuredText.asText()`，读不到文本层时返回空字符串。
- **图片型 PPT / 复杂排版 PPT**：自实现的 `PptxParser` 只能读文本框，对嵌入图片、SmartArt、复杂图表无能为力。
- **公式、表格、多栏布局**：现有解析器会丢失结构，导致向量化后的内容上下文断裂。

向量模型只负责把文本变成向量。Garbage in, garbage out。

---

## 二、业界主流做法调研

### 2.1 Cherry Studio 的方案

Cherry Studio 把文档处理能力拆成两层：

| 层级 | 作用 | 可选项 |
|---|---|---|
| **OCR 服务** | 图片/扫描件 → 文字 | 系统 OCR、Tesseract、Paddle OCR、OpenVINO |
| **文档处理服务商** | 复杂版式 PDF → 结构化文本 | **MinerU（默认，云端 API）**、Paddle OCR、第三方视觉模型 |

关键设计：

- 在**知识库设置**里加"文档预处理"开关。
- 上传文件时自动判断：PDF/图片先过 OCR/文档解析服务商，再向量化。
- **切换处理器后，已向量化的资料不会自动重做，需手动重新导入**。

### 2.2 MinerU

- 开源项目（OpenDataLab），支持 PDF、图片、DOCX、PPTX、XLSX。
- 内置 PP-OCRv6 OCR，支持 109 种语言，自动检测扫描页并触发 OCR。
- 输出 Markdown / JSON，保留标题、段落、表格、公式 LaTeX。
- 提供云端 API（`https://mineru.net`）和本地部署两种方式。

### 2.3 Docling（IBM 开源）

- 支持 PDF、DOCX、PPTX、XLSX、图片、音频/视频字幕。
- 统一文档对象模型，RAG 集成成熟（LangChain、LlamaIndex、Haystack）。
- 提供 `docling-serve` REST API，可容器化部署。
- 优势是**多格式统一**，中文生态不如 MinerU。

### 2.4 Marker

- 基于本地 VLM（surya）做 OCR 和版面分析。
- 有 `balanced`（高精度，GPU）和 `fast`（CPU）两种模式。
- 对扫描 PDF 效果好，但体积大、需要 GPU 或 Apple Silicon 才好用。

### 2.5 Zerox

- 把文档每页转成图片，调用 GPT-4o / Claude / Gemini 等视觉模型生成 Markdown。
- 效果最好，但**按页收费**，大文档成本高。
- 适合复杂图表、布局极乱的文档。

### 2.6 RAGFlow

- 自研 `deepdoc` + 可选接入 MinerU / Docling。
- 对扫描件、图片、PPT 都有覆盖。
- 也用**多模态模型理解 PDF/DOCX 里的图片**。

---

## 三、针对 RikkaHub 的推荐方案

项目约束：

1. Android 端个人应用，不能让每个用户自己搭服务器。
2. 本地算力有限，不能塞一个 4GB 的模型包。
3. 需要支持离线场景，但不能全靠离线（效果不够）。
4. 现有 `:knowledge` 模块扩展点清晰。

**核心思路：云端文档解析服务为主，本地轻量 OCR 兜底，长期考虑多模态 Chunk。**

### 3.1 方案总览

```
┌─────────────────────────────────────────────────────────────┐
│                    文档导入流程                              │
│  上传文件 → 格式检测 → 预处理决策 → 解析 → 分块 → 向量化   │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
   ┌─────────┐         ┌─────────────┐        ┌──────────┐
   │ 纯文本   │         │ 图片/扫描件  │        │ 复杂版式 │
   │ 直接解析  │         │ 本地/云端OCR │        │ 云端解析  │
   └─────────┘         └─────────────┘        └──────────┘
```

### 3.2 第一阶段：补齐扫描 PDF 和图片 OCR 短板

**目标**：让扫描 PDF 和图片不再读出空字符串或乱码。

#### 3.2.1 新增本地 OCR 能力

在 `:document` 或 `:app` 模块新增 `OcrEngine` 接口：

```kotlin
interface OcrEngine {
    suspend fun recognize(images: List<Bitmap>): List<String>
    val isAvailable: Boolean
    val name: String
}
```

实现两个引擎：

| 引擎 | 场景 | 说明 |
|---|---|---|
| `SystemOcrEngine` | 默认首选 | Android 接 **ML Kit Text Recognition v2**。支持中文/日文/韩文/拉丁文，端侧运行，免费，模型动态下载。 |
| `CloudOcrEngine` | 复杂图片、公式、表格 | 复用现有 `OcrTransformer`，调用用户配置的 OCR 模型（如 GPT-4o-mini / Gemini Flash）。 |

#### 3.2.2 扫描 PDF 处理流程

1. 上传 PDF 时，先用 MuPDF 检测页数、每页是否有文本层、是否有大量图片。
2. 判断逻辑：
   - 文本层完整且字符数正常 → 走现有解析流程。
   - 文本层为空/极少 → 把每页渲染成 Bitmap，走 OCR。
   - 文本乱码 → 同样走 OCR。
3. OCR 结果按阅读顺序拼接，再交给分块器。

#### 3.2.3 图片型 PPT 处理

现有 `PptxParser` 能解析文字版 PPT，但 PPT 经常把内容做成图片。

增强逻辑：

1. 先走现有 XML 解析，拿到文本。
2. 同时检测每页 slide 中的图片元素。
3. 如果某页文字极少但图片很多 → 把该页渲染成图片 → 走 OCR。
4. 合并文本层结果和 OCR 结果。

### 3.3 第二阶段：接入云端文档解析服务

**目标**：解决表格、公式、多栏、复杂排版导致的结构丢失。

#### 3.3.1 新增"文档处理服务商"设置

参考 Cherry Studio，在设置里新增：

- **文档解析 Provider**：
  - MinerU（默认推荐）
  - 自定义 OpenAI-compatible 视觉模型
  - 本地模式（只用第一阶段的能力）
- **API Key / API Host**
- **是否对纯文本文件启用**（默认关闭）

#### 3.3.2 调用时机

在 `DocumentProcessor.parseDocument()` 里增加一层路由：

```kotlin
when {
    fileType.isImage() -> 本地/云端 OCR
    fileType.isPdf() && needsOcrOrLayout(pdf) -> 文档解析服务
    fileType.isPptx() && hasImageSlides -> 文档解析服务
    else -> 现有解析器
}
```

云端服务返回 Markdown，再按现有流程分块。

#### 3.3.3 Provider 抽象

新增 `DocumentParserProvider` 接口：

```kotlin
interface DocumentParserProvider {
    suspend fun parse(file: File, fileType: String): DocumentParseResult
}

data class DocumentParseResult(
    val markdown: String,
    val pages: List<PageResult>? = null,
    val images: List<ExtractedImage>? = null
)
```

先实现两个 Provider：

| Provider | 实现 |
|---|---|
| `MinerUParserProvider` | 调用 `https://mineru.net` API，返回 Markdown |
| `VisionModelParserProvider` | 把文档分页转图，调用配置好的 vision 模型生成 Markdown |

### 3.4 第三阶段：多模态知识库（长期）

**目标**：PPT 里的图、PDF 里的流程图、截图本身也能被检索到。

#### 3.4.1 问题

有些内容用文字描述会失真：

- 架构图
- 数据可视化图表
- 产品设计稿
- 复杂表格

更好的做法是**同时保留图片和文字两种模态**。

#### 3.4.2 方案

1. 文档解析时，把图表、图片单独提取出来，生成描述（caption）。
2. 在向量库中同时存储：
   - 文本 chunk 的 embedding
   - 图片描述的 embedding
3. 检索时，文本查询匹配文本 chunk；图片相关查询也能命中图片描述。
4. 更高级：用多模态 embedding 模型（如 `jina-clip-v1`）。

这一步需要 `:knowledge` 模块支持**图片/文档附件存储**，并且 `KnowledgeChunkEntity` 增加 `imagePath` 字段。

---

## 四、关键设计决策

### 4.1 本地 vs 云端怎么选？

| 方案 | 优点 | 缺点 | 建议 |
|---|---|---|---|
| 纯本地 OCR（ML Kit/Tesseract） | 离线、隐私、免费 | 精度一般，公式表格差 | 作为兜底和简单图片 OCR |
| 云端 MinerU | 中文强、公式表格好、支持 PPT/PDF | 需要网络、可能有费用 | 默认推荐 |
| 视觉模型（GPT-4o/Gemini） | 理解力强、布局复杂也能处理 | 贵、慢 | 作为高端选项 |
| 本地 VLM（Marker/surya） | 隐私、离线 | 模型大、需要 GPU/Apple Silicon | 暂不适合移动端 |

### 4.2 什么文件走云端解析？

按需触发，不要所有文件都走：

- PDF：检测到文本层不足或乱码时触发。
- PPTX：检测到某页图片占比高或文字极少时触发。
- DOCX/XLSX：通常不需要，除非嵌入大量图片。
- 图片：OCR 处理。

### 4.3 已向量化的文档怎么处理？

参考 Cherry Studio：

> **切换处理器或重新配置后，已向量化的内容不会自动重做。需要手动重新导入。**

在知识库设置页增加：

- "重新处理全部文档" 按钮（已有）
- 每个文档显示当前使用的解析方式
- 重新上传同名文件时提示"是否使用新配置重新处理"

### 4.4 成本和隐私怎么平衡？

给用户提供三个档位：

| 档位 | 解析方式 | 适用场景 |
|---|---|---|
| 经济 | 本地 OCR + 现有解析器 | 普通扫描件、隐私敏感 |
| 标准 | MinerU 云端 API | 复杂 PDF、学术论文、合同 |
| 高质量 | 视觉模型（GPT-4o/Gemini） | 图表密集、布局极乱 |

---

## 五、实现路径建议

### 第一期（2-3 天工作量）

1. 接入 ML Kit Text Recognition v2 作为默认本地 OCR。
2. 修改 `PdfParser`，增加扫描页检测，对无文本层页面渲染成 Bitmap 后 OCR。
3. 增强 `PptxParser`，对图片型 slide 调用 OCR。
4. 知识库设置里增加"文档预处理"开关。

### 第二期（3-5 天工作量）

1. 新增 `DocumentParserProvider` 接口。
2. 实现 MinerU Provider。
3. 实现视觉模型 Provider（可选）。
4. 在 `DocumentProcessor` 中增加解析路由。
5. 设置页增加文档处理服务商配置。

### 第三期（可选，1-2 周）

1. 多模态 chunk 存储。
2. 图片 caption 生成。
3. 多模态检索。

---

## 六、需要确认的问题

1. **是否允许调用云端 API？** MinerU、视觉模型都需要联网，可能产生费用。
2. **是否接受 Google ML Kit？** 它依赖 Google Play 服务，在国内设备上可能受限。备选是 Tesseract 或 PaddleOCR-Lite，但集成更重。
3. **MinerU API 有没有预算上限？** 如果用户量大，需要考虑限流、缓存、失败 fallback。
4. **要不要做"每文档选择解析方式"？** 还是全局统一配置？

---

## 七、结论

最务实的路线：

> **第一阶段用 ML Kit / 视觉模型 OCR 解决扫描 PDF 和图片 PPT 的"读不出字"问题；第二阶段接入 MinerU 等云端文档解析服务解决复杂排版和结构保留问题；第三阶段再考虑多模态知识库。**

这样改动是渐进的，不会推翻现有 `:knowledge` 模块架构，而且每一阶段都能让用户感知到明显的质量提升。
