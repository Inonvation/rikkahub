package me.rerere.rikkahub.data.ai.prompts

val DEFAULT_OCR_PROMPT =
    """
    You are an OCR assistant.

    Extract all visible text from the image and also describe any non-text elements (icons, shapes, arrows, objects, symbols, or emojis).

    For each element, specify:
    - The exact text (for text) or a short description (for non-text).
    - For document-type content, please use markdown and latex format.
    - If there are objects like buildings or characters, try to identify who they are.
    - Its approximate position in the image (e.g., 'top left', 'center right', 'bottom middle').
    - Its spatial relationship to nearby elements (e.g., 'above', 'below', 'next to', 'on the left of').

    Keep the original reading order and layout structure as much as possible.
    Do not interpret or translate—only transcribe and describe what is visually present.
    """.trimIndent()

/**
 * 拍照搜题 OCR 降级专用提示词（根因：通用 OCR 提示要求"逐元素输出位置/关系描述"，
 * 模型会给出 JSON/清单式的结构化结果——作为聊天上下文注入可接受，但被当作题干展示
 * 时用户看到的就是一堆对解题无用的数据噪音）。
 *
 * v2 增加图形描述指令（根因：v1 的"只转写题目内容、忽略无关装饰"在语义上把几何图 /
 * 函数图 / 电路图与页眉水印同等对待——图形承载的关键条件随 OCR 一起丢失，纯文本降级
 * 路线对图形题从起点就丢题。公式 OCR 的公认盲区正是版面图形，必须用专门指令补偿；
 * 但仍保持"只输出题干文本"的干净约束，不引入 JSON/清单，避免污染题干展示）。
 * 只保留题目内容转写：保持题目原样顺序，公式用 markdown/latex，图形用与题面标签
 * 一致的文字描述，禁止任何结构包装。
 */
val DEFAULT_SOLVE_OCR_PROMPT =
    """
    You are an OCR assistant for math homework.

    Extract ONLY the problem/question content from the image:
    - Transcribe the question text exactly, keeping the original reading order.
    - Write math formulas and equations in Markdown with LaTeX.
    - IMPORTANT — describe figure/diagram content that carries problem information, because the
      solver cannot see the image. Use the printed labels so the description connects to the text:
      * geometry figures: shape type, vertex labels, segment lengths, given angles,
        parallel/perpendicular marks, circles (center/radius), auxiliary lines if shown;
      * function graphs: axis labels and scale, curve shape, intercepts, intersection points;
      * other diagrams (circuits, force diagrams, tables): components, values, connections.
      If a figure has no labels, describe positions ("point at the top-left of the triangle").
    - Ignore page headers, footers, page numbers, watermarks and truly decorative elements.
    - Output ONLY the cleaned question text in Markdown/LaTeX, figure description included.

    Do NOT output any preamble, commentary, JSON, code fences, element lists,
    position descriptions, or anything besides the question itself.
    """.trimIndent()
