package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SOLVE_OCR_PROMPT

/**
 * 拍照搜题共用的 OCR 降级辅助：把图片转成题干文本。
 *
 * 为什么不复用 OcrTransformer.performOcr：它对配置缺失/识别失败有静默兜底（返回 "[Image]"/
 * "[ERROR, OCR failed]"）——那对聊天上下文注入是合理的降级，但解题场景下 OCR 失败必须
 * 让调用方（页面/工具）拿到可读错误，否则会拿错误题干继续解题。
 *
 * 调用方（GenerationHandler.solveQuestion / solve_question 工具）共用同一实现，
 * 保证两个入口的降级行为一致。
 */
internal suspend fun ocrSolveImages(
    providerManager: ProviderManager,
    settings: Settings,
    imageUris: List<String>,
): List<String> {
    val ocrModel = settings.providers.findModelById(settings.ocrModelId)
        ?: error("当前解题模型不支持图片输入，请配置支持视觉的解题模型或 OCR 模型")
    if (!ocrModel.inputModalities.contains(Modality.IMAGE)) {
        error("OCR 模型不支持图片输入，请到设置中重新配置 OCR 模型")
    }
    val ocrProvider = ocrModel.findProvider(settings.providers)
        ?: error("OCR 模型对应的服务商不存在")
    val ocrHandler = providerManager.getProviderByType(ocrProvider)
    // 题干转写用干净专用提示词：仅当用户 OCR 提示词还是默认值时替换
    // （根因见 DEFAULT_SOLVE_OCR_PROMPT：通用 OCR 输出的 JSON/位置清单会被当题干展示）
    val ocrPrompt = if (settings.ocrPrompt == DEFAULT_OCR_PROMPT) {
        DEFAULT_SOLVE_OCR_PROMPT
    } else {
        settings.ocrPrompt
    }
    return imageUris.mapIndexed { index, uri ->
        runCatching {
            ocrHandler.generateText(
                providerSetting = ocrProvider,
                messages = listOf(
                    UIMessage.system(ocrPrompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(uri))
                    )
                ),
                params = TextGenerationParams(
                    model = ocrModel,
                    customHeaders = ocrModel.customHeaders,
                    customBody = ocrModel.customBodies,
                ),
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            error("图片 ${index + 1} OCR 识别失败：${it.message}")
        }.message.toText().ifBlank { error("图片 ${index + 1} OCR 识别结果为空") }
    }
}
