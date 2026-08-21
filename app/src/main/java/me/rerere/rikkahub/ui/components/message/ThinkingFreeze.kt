package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.time.Duration

/** 悬浮吸顶条的总高度（含渐变区）；滚动折叠"解除吸顶"的目标位置也以它计算 */
internal val ThinkingFrozenBarHeight = 56.dp

/**
 * 思考内容吸顶冻结状态：聊天页上报列表内容区顶边（顶栏底边）的窗口 Y，
 * 思考步骤把自己注册进 [sections]，由 [activeSection] 选出最靠近顶栏的冻结步骤，
 * 聊天页据此渲染悬浮吸顶条。
 */
@Stable
class ThinkingFreezeState {
    /** 列表内容区顶边的窗口 Y（顶栏底边）；思考头部滚到其上方即进入冻结区 */
    var topBarBottomY by mutableIntStateOf(Int.MAX_VALUE)

    /**
     * 悬浮条触发的程序滚动（折叠/展开）进行中。
     * ChatList 的自动跟随用它区分"用户手势上滑"和"程序滚动"，
     * 避免折叠动画刚结束就被拉回底部。
     */
    var scrollingByProgram by mutableStateOf(false)

    /** 已注册的思考步骤，key 为 reasoning 节 key，由 ChatMessageReasoningStep 维护 */
    val sections = mutableStateMapOf<String, ThinkingFrozenBarSection>()

    /**
     * 当前应显示吸顶条的思考步骤：头部进入冻结区（topY < 顶栏底边）且卡片底部仍可见
     * （bottomY > 顶栏底边），且内容可见（展开/预览态）。多个时取最靠近顶栏的那个。
     */
    val activeSection: ThinkingFrozenBarSection?
        get() = sections.values
            .filter {
                it.topY.value < topBarBottomY &&
                    it.bottomY.value > topBarBottomY &&
                    it.contentVisible.value
            }
            .maxByOrNull { it.topY.value }
}

/**
 * 单个思考步骤在吸顶条中的注册数据。
 *
 * [topY]/[bottomY] 在滚动时由 onLayoutRectChanged 持续更新；其余字段由步骤每次重组同步，
 * 均用 MutableState 以便吸顶条实时读取。
 */
@Stable
class ThinkingFrozenBarSection(
    val key: String,
    val topY: MutableIntState = mutableIntStateOf(Int.MAX_VALUE),
    val bottomY: MutableIntState = mutableIntStateOf(Int.MIN_VALUE),
    val duration: MutableState<Duration> = mutableStateOf(Duration.ZERO),
    val title: MutableState<String?> = mutableStateOf(null),
    /** 该思考步骤是否仍在流式生成中（用于吸顶条主文案的轮换趣味文案） */
    val streaming: MutableState<Boolean> = mutableStateOf(false),
    val cardColor: MutableState<Color> = mutableStateOf(Color.Unspecified),
    val contentVisible: MutableState<Boolean> = mutableStateOf(false),
    val collapsed: MutableState<Boolean> = mutableStateOf(false),
    val folded: MutableState<Boolean> = mutableStateOf(false),
    var onToggle: () -> Unit = {},
)

/** 由聊天页提供；思考步骤内部读取并注册，未提供时吸顶条不生效 */
val LocalThinkingFreezeState = staticCompositionLocalOf<ThinkingFreezeState?> { null }

/**
 * 由聊天页提供：按像素量平滑滚动列表（滚动折叠/展开用）。
 * 正数向上滚（内容上移），负数向下滚（内容下移）。
 * 挂起函数：调用方（悬浮条点击等）在协程中调用，可等待滚动完成再继续。
 */
val LocalScrollThinkingHeaderToPin = staticCompositionLocalOf<((suspend (Float) -> Unit)?)> { null }

/**
 * 悬浮吸顶条：位于聊天顶栏下方（列表内容区顶边），与顶栏无间距。
 * 背景为不透明卡片色到透明的由上至下渐变，仅内容行可点击（渐变区不拦截触摸）。
 */
@Composable
fun ThinkingFrozenBar(
    state: ThinkingFreezeState,
    modifier: Modifier = Modifier,
) {
    val section = state.activeSection
    // 记住最近一次显示的 section：退场动画期间 section 已变为 null，
    // 仍需渲染旧内容供 fadeOut 使用，避免动画瞬间消失
    var displayedSection by remember { mutableStateOf(section) }
    if (section != null) displayedSection = section
    // 冻结条与真实头部共用同一共享头部行（ReasoningHeaderRow），两者位置在冻结边界处重合，
    // 因此过渡只需要淡入淡出交叉，不要额外的垂直展开/收缩位移动画（避免视觉位置跳变）。
    val enterTransition: EnterTransition = fadeIn(tween(180))
    val exitTransition: ExitTransition = fadeOut(tween(180))
    AnimatedVisibility(
        visible = section != null,
        modifier = modifier,
        enter = enterTransition,
        exit = exitTransition,
    ) {
        displayedSection?.let { ThinkingFrozenBarContent(it) }
    }
}

@Composable
private fun ThinkingFrozenBarContent(section: ThinkingFrozenBarSection) {
    val cardColor = section.cardColor.value
    // 与消息卡片对齐：左侧 16dp（列表 contentPadding），右侧 24dp（助手消息收窄）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 24.dp)
            .height(ThinkingFrozenBarHeight)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to cardColor.copy(alpha = 1f),
                        0.5f to cardColor.copy(alpha = 1f),
                        1f to cardColor.copy(alpha = 0f),
                    ),
                )
            },
    ) {
        // 复用共享头部行：图标 + 标题/思考了n秒 + 折叠/展开箭头，整行可点、触感统一
        ReasoningHeaderRow(
            title = section.title.value,
            duration = section.duration.value,
            // 流式生成中 → loading=true，主文案走轮换趣味文案；结束后恢复静态标题/思考时长
            loading = section.streaming.value,
            contentVisible = section.contentVisible.value,
            folded = section.folded.value,
            onClick = section.onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 8.dp),
        )
    }
}
