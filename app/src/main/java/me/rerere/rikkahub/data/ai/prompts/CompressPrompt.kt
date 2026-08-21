package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task.

    Include:
    - Current progress and key decisions made
    - Important context, constraints, or user preferences
    - What remains to be done (clear next steps)
    - Any critical data, examples, or references needed to continue

    If this conversation contains a summary from a previous compaction, extract its key historical thread (what was done, key decisions and why, outcomes, direction) and include it as a cumulative "Historical Context" section at the top of your new summary. Each compaction adds 3-5 sentences. Never discard entries from previous compactions.

    Requirements:
    1. Target approximately {target_tokens} tokens
    2. Keep the summary in the same language as the original conversation, and use {locale} for the final output
    3. Output the summary directly without explanations or meta-commentary
    4. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or the equivalent in {locale})

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
