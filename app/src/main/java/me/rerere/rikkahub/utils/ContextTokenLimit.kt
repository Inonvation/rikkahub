package me.rerere.rikkahub.utils

import me.rerere.ai.provider.DEFAULT_MODEL_CONTEXT_LENGTH

/**
 * 上下文窗口上限解析：模型声明（或注册表兜底）优先，其次助手配置，最后全局默认。
 * 压缩弹窗 / 顶栏占用圈 / /compact 命令共用同一口径。
 */
internal fun resolveContextTokenLimit(
    modelContextTokenLimit: Int?,
    assistantContextTokenLimit: Int,
): Int = modelContextTokenLimit?.takeIf { it > 0 }
    ?: assistantContextTokenLimit.takeIf { it > 0 }
    ?: DEFAULT_MODEL_CONTEXT_LENGTH
