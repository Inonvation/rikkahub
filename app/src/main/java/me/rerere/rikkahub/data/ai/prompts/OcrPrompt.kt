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
 * 只保留题目内容转写：保持题目原样顺序，公式用 markdown/latex，禁止任何结构包装。
 */
val DEFAULT_SOLVE_OCR_PROMPT =
    """
    You are an OCR assistant for math homework.

    Extract ONLY the problem/question content from the image:
    - Transcribe the question text exactly, keeping the original reading order.
    - Write math formulas and equations in Markdown with LaTeX.
    - Ignore page headers, footers, page numbers, watermarks and unrelated decorations.
    - Output ONLY the cleaned question text in Markdown/LaTeX.

    Do NOT output any preamble, commentary, JSON, code fences, element lists,
    position descriptions, or anything besides the question itself.
    """.trimIndent()
