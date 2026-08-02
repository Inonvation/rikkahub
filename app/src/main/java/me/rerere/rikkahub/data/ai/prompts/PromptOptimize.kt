package me.rerere.rikkahub.data.ai.prompts

/**
 * 提示词优化功能：优化场景、优化语气与优化深度。
 * 系统提示词由「场景提示词 + 语气指引 + 深度指引」三部分拼接而成，
 * 避免 场景×语气×深度 全组合膨胀（4 段场景 + 3 段语气 + 2 段深度 = 9 段文案）。
 */
internal enum class PromptOptimizeScene(val code: String) {
    GENERAL("general"),
    WRITING("writing"),
    QUESTION("question"),
    PROGRAMMING("programming"),
}

/** 优化语气：作为内置预设，不可自定义模板 */
internal enum class PromptOptimizeTone {
    SERIOUS, HUMOROUS, NORMAL,
}

/**
 * 优化深度：控制改写详略程度，只影响 prompt 文案，不联动思考预算（思考预算由设置页单独控制）。
 * code 用于 DataStore 按场景持久化。
 */
internal enum class PromptOptimizeDepth(val code: String) {
    CONCISE("concise"),
    MEDIUM("medium"),
    DETAILED("detailed"),
}

/** 场景的中文展示名（用于界面筛选与自定义模板的 {scene} 占位符） */
internal fun PromptOptimizeScene.toDisplayText(): String = when (this) {
    PromptOptimizeScene.GENERAL -> "通用"
    PromptOptimizeScene.WRITING -> "写作"
    PromptOptimizeScene.QUESTION -> "提问"
    PromptOptimizeScene.PROGRAMMING -> "编程"
}

/** 语气的中文展示名（用于自定义模板的 {tone} 占位符） */
internal fun PromptOptimizeTone.toDisplayText(): String = when (this) {
    PromptOptimizeTone.SERIOUS -> "严肃"
    PromptOptimizeTone.HUMOROUS -> "幽默"
    PromptOptimizeTone.NORMAL -> "正常"
}

/** 深度的中文展示名（用于自定义模板的 {depth} 占位符） */
internal fun PromptOptimizeDepth.toDisplayText(): String = when (this) {
    PromptOptimizeDepth.CONCISE -> "精简"
    PromptOptimizeDepth.MEDIUM -> "中等"
    PromptOptimizeDepth.DETAILED -> "详细"
}

/**
 * 根据场景、语气与深度返回对应的系统提示词。
 * 结构：场景提示词（职责/规则） + 语气指引（差异化措辞方向） + 深度指引（改写详略）。
 * 提示词为英文编写（主流模型对英文指令理解更充分），
 * 每条末尾统一锁定"输出语言跟随用户输入"，因此中文输入会得到中文结果。
 */
internal fun promptOptimizeSystemPrompt(
    scene: PromptOptimizeScene,
    tone: PromptOptimizeTone,
    depth: PromptOptimizeDepth,
): String = sceneSystemPrompt(scene) + "\n\n" + toneGuidance(tone) + "\n\n" + depthGuidance(depth)

private fun sceneSystemPrompt(scene: PromptOptimizeScene): String = when (scene) {
    PromptOptimizeScene.GENERAL -> GENERAL_PROMPT
    PromptOptimizeScene.WRITING -> WRITING_PROMPT
    PromptOptimizeScene.QUESTION -> QUESTION_PROMPT
    PromptOptimizeScene.PROGRAMMING -> PROGRAMMING_PROMPT
}

private fun toneGuidance(tone: PromptOptimizeTone): String = when (tone) {
    PromptOptimizeTone.SERIOUS -> SERIOUS_TONE
    PromptOptimizeTone.HUMOROUS -> HUMOROUS_TONE
    PromptOptimizeTone.NORMAL -> NORMAL_TONE
}

private fun depthGuidance(depth: PromptOptimizeDepth): String = when (depth) {
    PromptOptimizeDepth.CONCISE -> CONCISE_DEPTH
    PromptOptimizeDepth.MEDIUM -> MEDIUM_DEPTH
    PromptOptimizeDepth.DETAILED -> DETAILED_DEPTH
}

private val GENERAL_PROMPT = """
    You are a prompt optimization expert. Rewrite the user's text into a clear, well-structured conversational instruction.

    Rules:
    1. Preserve the original intent and key information; do not add content the user did not express
    2. Make the goal, constraints, and expected output explicit; remove ambiguity and repetition
    3. Use precise, natural phrasing and keep the structure clean
    4. Output only the optimized text. No explanations, introductions, or prefixes
    5. Respond in the same language as the user's input
""".trimIndent()

private val WRITING_PROMPT = """
    You are a writing prompt expert. Rewrite the user's text into a complete, effective creative writing instruction.

    Rules:
    1. Preserve the intent and creative direction; do not drift from the subject
    2. Specify the writing type (article / story / copy / essay, etc.), target reader, length, and structure
    3. Add useful context such as background, style, and must-avoid items when helpful
    4. Output only the optimized text. No explanations, introductions, or prefixes
    5. Respond in the same language as the user's input
""".trimIndent()

private val QUESTION_PROMPT = """
    You are a question optimization expert. Rewrite the user's question so it is specific, complete, and easy to answer accurately.

    Rules:
    1. Preserve what the user actually wants to know; do not change the direction of the question
    2. Clarify vague terms, add necessary background, and split broad questions into answerable ones
    3. State the expected answer form (explanation / steps / examples / comparison) and scope when useful
    4. Output only the optimized question. No explanations, introductions, or prefixes
    5. Respond in the same language as the user's input
""".trimIndent()

private val PROGRAMMING_PROMPT = """
    You are a software engineering prompt expert. Rewrite the user's text into a precise, implementable programming instruction.

    Rules:
    1. Preserve the technical intent; clarify the programming language, environment, and constraints
    2. Make the input/output, edge cases, and expected behavior explicit
    3. Keep technical terms accurate; break multi-step tasks into clear steps
    4. Output only the optimized text. No explanations, introductions, or prefixes
    5. Respond in the same language as the user's input
""".trimIndent()

private val SERIOUS_TONE = """
    Tone: rewrite in a serious, rigorous, professional manner. Be precise and direct; avoid jokes, casual filler, or flippant wording. Keep it formal but not stiff.
""".trimIndent()

private val HUMOROUS_TONE = """
    Tone: rewrite in a light-hearted, witty manner while keeping the core intent intact. A touch of humor and friendly phrasing is welcome, but never let it obscure the meaning or reduce clarity.
""".trimIndent()

private val NORMAL_TONE = """
    Tone: rewrite in a natural, neutral, everyday manner. Neither overly formal nor playful; plain, clear, and approachable.
""".trimIndent()

private val CONCISE_DEPTH = """
    Detail level: concise. Keep the rewrite tight — drop filler and repetition, keep only the essential information, and produce the shortest clear version that still captures the intent.
""".trimIndent()

private val MEDIUM_DEPTH = """
    Detail level: medium. Rewrite at a balanced level of detail — improve clarity and structure without expanding length; preserve the substance as-is.
""".trimIndent()

private val DETAILED_DEPTH = """
    Detail level: detailed. Expand the rewrite where it helps — make structure, constraints, background, and expectations explicit; a longer, more thorough result is fine.
""".trimIndent()

/**
 * 可编辑的提示词优化模板（设置 → 默认模型 → 提示词 里可改，按场景各自存储）。
 * 未配置自定义模板时，运行时使用上面按 场景 + 语气 + 深度 分组的精选提示词。
 * 配置后使用此模板，并填入以下占位符：
 * - {scene}  优化场景（通用 / 写作 / 提问 / 编程）
 * - {tone}   优化语气（严肃 / 幽默 / 正常）
 * - {depth}  优化深度（精简 / 中等 / 详细）
 * - {content} 待优化的原始文字
 */
internal val DEFAULT_PROMPT_OPTIMIZE_PROMPT = """
    You are a prompt optimization expert. Optimize the user's text below according to the scene, tone, and detail level.

    Scene: {scene}
    Tone: {tone}
    Detail level: {depth}

    Requirements:
    1. Preserve the original intent; do not add information the user did not express
    2. Follow the given scene, tone, and detail level to adjust clarity, structure, context, and wording
    3. Output only the optimized text. No explanations, introductions, or prefixes
    4. Respond in the same language as the user's input

    <content>
    {content}
    </content>
""".trimIndent()

/**
 * 各场景的默认可编辑模板。与 [promptOptimizeSystemPrompt] 语义一致，但含 {scene}/{tone}/{depth}/{content} 占位符，
 * 供设置页编辑器回显（保证"看到的即运行时生效的"，且各场景默认内容明确区隔）。
 */
internal fun defaultPromptOptimizePromptForScene(scene: PromptOptimizeScene): String = when (scene) {
    PromptOptimizeScene.GENERAL -> """
        You are a prompt optimization expert. Rewrite the user's text into a clear, well-structured instruction.

        Scene: {scene}
        Tone: {tone}
        Detail level: {depth}

        Requirements:
        1. Preserve the original intent and key information; do not add content the user did not express
        2. Make the goal, constraints, and expected output explicit; remove ambiguity and repetition
        3. Output only the optimized text. No explanations, introductions, or prefixes
        4. Respond in the same language as the user's input

        <content>
        {content}
        </content>
    """.trimIndent()

    PromptOptimizeScene.WRITING -> """
        You are a writing prompt expert. Rewrite the user's text into a complete, effective creative writing instruction.

        Scene: {scene}
        Tone: {tone}
        Detail level: {depth}

        Requirements:
        1. Preserve the intent and creative direction; do not drift from the subject
        2. Specify the writing type, target reader, length, and structure; add useful context when helpful
        3. Output only the optimized text. No explanations, introductions, or prefixes
        4. Respond in the same language as the user's input

        <content>
        {content}
        </content>
    """.trimIndent()

    PromptOptimizeScene.QUESTION -> """
        You are a question optimization expert. Rewrite the user's question so it is specific, complete, and easy to answer accurately.

        Scene: {scene}
        Tone: {tone}
        Detail level: {depth}

        Requirements:
        1. Preserve what the user actually wants to know; do not change the direction of the question
        2. Clarify vague terms, add necessary background, and split broad questions into answerable ones
        3. State the expected answer form and scope when useful
        4. Output only the optimized question. No explanations, introductions, or prefixes
        5. Respond in the same language as the user's input

        <content>
        {content}
        </content>
    """.trimIndent()

    PromptOptimizeScene.PROGRAMMING -> """
        You are a software engineering prompt expert. Rewrite the user's text into a precise, implementable programming instruction.

        Scene: {scene}
        Tone: {tone}
        Detail level: {depth}

        Requirements:
        1. Preserve the technical intent; clarify the programming language, environment, and constraints
        2. Make the input/output, edge cases, and expected behavior explicit
        3. Keep technical terms accurate; break multi-step tasks into clear steps
        4. Output only the optimized text. No explanations, introductions, or prefixes
        5. Respond in the same language as the user's input

        <content>
        {content}
        </content>
    """.trimIndent()
}
