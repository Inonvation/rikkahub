package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * 生成中待注入的引导信号（steering），按 FIFO 排队。
 *
 * @param id 唯一标识，UI 气泡用它定位「立即发送 / 取消 / 编辑」
 * @param parts 引导内容（文本 + 附件，与输入框 getContents 同构），作为真实 USER 消息注入
 * @param immediate true = 用户点了该气泡的「立即发送」：GenerationHandler 在下一轮边界
 *   （工具调用完成/输出结束）消费，引导作为真实 USER 消息追加到上下文尾部（对齐
 *   Codex turn/steer），不打断当前流式；
 *   false = 默认排队：GenerationHandler 不消费，等当前回合输出完成后由 ChatService
 *   作为普通用户消息依次发送。
 */
data class PendingSteering(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val immediate: Boolean = false,
)
