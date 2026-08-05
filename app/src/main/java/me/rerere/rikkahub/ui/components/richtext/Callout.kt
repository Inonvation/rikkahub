package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Danger
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Question
import me.rerere.hugeicons.stroke.QuoteUp
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tick01

// Obsidian callout 配色（固定色，与 Obsidian 默认主题接近）
private val NoteBlue = Color(0xFF3B82F6)
private val SuccessGreen = Color(0xFF16A34A)
private val WarningOrange = Color(0xFFF59E0B)
private val DangerRed = Color(0xFFEF4444)
private val ExamplePurple = Color(0xFF8B5CF6)
private val QuoteGray = Color(0xFF6B7280)
private val QuestionTeal = Color(0xFF14B8A6)
private val AbstractCyan = Color(0xFF06B6D4)

/** callout 视觉样式：主色 + 图标 */
internal data class CalloutStyle(
    val color: Color,
    val icon: ImageVector,
)

/** Obsidian callout 类型 → 颜色/图标映射。未知类型回退到 note（蓝） */
internal fun calloutStyle(type: String): CalloutStyle {
    return when (type.trim().lowercase()) {
        "note", "info" -> CalloutStyle(NoteBlue, HugeIcons.InformationCircle)
        "tip", "success", "check", "done", "hint" -> CalloutStyle(SuccessGreen, HugeIcons.Tick01)
        "warning", "caution", "important" -> CalloutStyle(WarningOrange, HugeIcons.Danger)
        "danger", "bug", "error", "failure" -> CalloutStyle(DangerRed, HugeIcons.Alert02)
        "example" -> CalloutStyle(ExamplePurple, HugeIcons.Sparkles)
        "quote", "cite" -> CalloutStyle(QuoteGray, HugeIcons.QuoteUp)
        "question", "faq", "help" -> CalloutStyle(QuestionTeal, HugeIcons.Question)
        "abstract", "summary", "tldr" -> CalloutStyle(AbstractCyan, HugeIcons.File01)
        "todo" -> CalloutStyle(NoteBlue, HugeIcons.CheckList)
        else -> CalloutStyle(NoteBlue, HugeIcons.InformationCircle)
    }
}

/**
 * Obsidian callout 提示框：左侧彩条 + 浅底色 + 图标标题行。
 * [collapsed] 为 true 时默认收起（点击标题行展开），配合 `> [!type]-` 折叠标记。
 */
@Composable
internal fun CalloutCard(
    type: String,
    title: String,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = calloutStyle(type)
    var expanded by remember { mutableStateOf(!collapsed) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                drawRect(color = style.color.copy(alpha = 0.07f), size = size)
                drawRect(color = style.color, size = Size(4.dp.toPx(), size.height))
            }
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = style.color,
                modifier = Modifier.weight(1f),
            )
            if (collapsed) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    tint = style.color.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(top = 4.dp)) {
                content()
            }
        }
    }
}
