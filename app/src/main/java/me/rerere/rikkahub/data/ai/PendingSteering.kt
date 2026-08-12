package me.rerere.rikkahub.data.ai

import kotlin.uuid.Uuid

/**
 * 生成中待注入的引导信号（steering），按 FIFO 排队。
 *
 * @param id 唯一标识，UI 气泡用它定位「立即发送 / 取消 / 编辑」
 * @param text 引导文本
 * @param immediate true = 用户点了该气泡的「立即发送」：GenerationHandler 在下一轮边界
 *   （工具调用完成/输出结束）消费，注入为 user_guidance 气泡 + 续答指令，不打断当前流式；
 *   false = 默认排队：GenerationHandler 不消费，等当前回合输出完成后由 ChatService
 *   依次自动注入（自动引导）。
 */
data class PendingSteering(
    val id: Uuid = Uuid.random(),
    val text: String,
    val immediate: Boolean = false,
)
