package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexesCached
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.UIAvatar

/**
 * 新建会话时的预设消息开场展示（简约居中方案）。
 *
 * 聊天界面正中显示：左侧助手头像，右侧同一水平高度靠右对齐的预设消息。
 * 入场动画：头像先淡入/缩放出现，随后预设消息以向右平移源点向左缓动进入（每条轻微错峰）。
 * 不引入卡片/渐变/双层容器，仅头像 + 文本气泡，保持克制。
 */
@Composable
fun PresetMessagesIntro(
    messages: List<UIMessage>,
    assistant: Assistant,
    modifier: Modifier = Modifier,
    onAvatarClick: (() -> Unit)? = null,
) {
    if (messages.isEmpty()) return

    var visible by remember(messages) { mutableStateOf(false) }
    LaunchedEffect(messages) {
        visible = false
        withFrameNanos { }
        visible = true
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            // 头像最先出现：淡入 + 缩放
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(300)) +
                    scaleIn(animationSpec = tween(300), initialScale = 0.8f),
                exit = fadeOut(animationSpec = tween(180)) +
                    scaleOut(animationSpec = tween(180)),
            ) {
                UIAvatar(
                    name = assistant.name,
                    value = assistant.avatar,
                    onClick = onAvatarClick,
                    modifier = Modifier.size(52.dp),
                )
            }

            // 预设消息：同水平高度靠右对齐，向左缓动平移进入
            Column(
                modifier = Modifier.widthIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                messages.forEachIndexed { index, message ->
                    val delay = 130 + index * 90
                    AnimatedVisibility(
                        visible = visible,
                        enter = slideInHorizontally(
                            animationSpec = tween(durationMillis = 360, delayMillis = delay),
                            initialOffsetX = { it },
                        ) + fadeIn(animationSpec = tween(240, delayMillis = delay)),
                        exit = fadeOut(animationSpec = tween(140)),
                    ) {
                        PresetMessageBubble(
                            message = message,
                            assistant = assistant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetMessageBubble(
    message: UIMessage,
    assistant: Assistant,
) {
    val isUser = message.role == MessageRole.USER
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.widthIn(max = 460.dp),
        shape = if (isUser) {
            RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomEnd = 4.dp,
                bottomStart = 16.dp,
            )
        } else {
            RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomEnd = 16.dp,
                bottomStart = 4.dp,
            )
        },
        color = if (isUser) {
            colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            colorScheme.surfaceContainerLow
        },
        tonalElevation = if (isUser) 0.dp else 1.dp,
    ) {
        MarkdownBlock(
            content = message.toText().replaceRegexesCached(
                assistant = assistant,
                scope = if (isUser) AssistantAffectScope.USER else AssistantAffectScope.ASSISTANT,
                visual = true,
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.End),
        )
    }
}
