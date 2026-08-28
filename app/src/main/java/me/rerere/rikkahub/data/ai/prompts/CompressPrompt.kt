package me.rerere.rikkahub.data.ai.prompts

// 默认压缩提示词：Codex 式「上下文检查点压缩 / 交接摘要」骨架 + Claude Code 式结构化段落
// 与防语境漂移要求。{content}/{target_tokens}/{additional_context}/{locale} 占位符由
// ChatService.compressConversation 填充；用户可在设置页自定义（改默认值不影响已自定义用户）。
internal val DEFAULT_COMPRESS_PROMPT = """
    You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task. The summary must let it continue seamlessly without re-reading the original conversation.

    Organize the summary into these sections (skip a section only if there is truly nothing to record):
    1. User Intent & Goal — the user's original request and how it evolved; quote key phrases verbatim
    2. Key Decisions & Reasons — what was decided and why
    3. Files, Data & Sources — files/datasets/URLs involved: what was read or modified, and the key tool results (paths, numbers, statuses)
    4. Errors & Fixes — errors encountered, what was tried, what worked or failed
    5. Outstanding Work — what remains undone
    6. Next Steps — concrete suggested next actions

    If this conversation contains a summary from a previous compaction, extract its key historical thread (what was done, key decisions and why, outcomes, direction) and include it as a cumulative "Historical Context" section at the top of your new summary. Each compaction adds 3-5 sentences. Never discard entries from previous compactions.

    Requirements:
    1. Target approximately {target_tokens} tokens
    2. Keep the summary in the same language as the original conversation, and use {locale} for the final output
    3. Quote critical original phrases verbatim (user intent, error messages, key numbers) instead of paraphrasing everything — this prevents context drift
    4. Tool entries in the conversation are real interactions: record which files/data were read or modified and their outcomes, not the raw payloads
    5. Output the summary directly without explanations or meta-commentary
    6. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or the equivalent in {locale})

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
