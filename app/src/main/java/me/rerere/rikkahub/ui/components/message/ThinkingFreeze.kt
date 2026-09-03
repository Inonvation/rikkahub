package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import me.rerere.rikkahub.ui.components.ui.barHazeBlurStyle

/** 悬浮吸顶条的总高度（含渐变区）；滚动折叠"解除吸顶"的目标位置也以它计算 */
internal val ThinkingFrozenBarHeight = 44.dp

/** 推出提前结束：气泡底部距冻结线小于此距离（露出量）时，行与玻璃带开始淡出 */
private val FrozenExitAhead = 32.dp

/** 推出全隐阈值：气泡底部露出量小于此值时，行与玻璃带已完全消失（"还剩一点点未被覆盖"时无模糊残留） */
private val FrozenExitDead = 8.dp

/** 行在钉住临界区的淡入距离：钉住瞬间行与玻璃带同步淡入（"停住出现时才模糊"） */
private val FrozenPinFade = 4.dp

/**
 * 思考内容吸顶冻结状态：聊天页上报列表内容区顶边（顶栏底边）的窗口 Y，
 * 思考步骤把自己注册进 [sections]，由 [activeSection] 选出最靠近顶栏的冻结步骤，
 * 聊天页据此渲染悬浮吸顶条。
 */
@Stable
class ThinkingFreezeState {
    /** 列表内容区顶边的窗口 Y（顶栏底边）；思考头部滚到其上方即进入冻结区 */
    var topBarBottomY by mutableIntStateOf(Int.MAX_VALUE)

    /** 思考折叠/展开的程序滚动与高度动画进行中：自动跟随应跳过抢滚 */
    var scrollingByProgram by mutableStateOf(false)

    /** 已注册的思考步骤，key 为 reasoning 节 key，由 ChatMessageReasoningStep 维护 */
    val sections = mutableStateMapOf<String, ThinkingFrozenBarSection>()

    /**
     * 当前应显示吸顶条的思考步骤：头部已进入冻结区（topY < 顶栏底边）且卡片底部仍可见
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

/** 由聊天页提供：当前列表是否钉在底部（折叠思考后判断是否需要重新贴底） */
val LocalIsChatListAtBottom = staticCompositionLocalOf<(() -> Boolean)?> { null }

/**
 * 由聊天页提供：用户当前是否正在控制列表（触碰中 / 滚动中 / 最近 350ms 内刚触碰或滚动过）。
 * 自动折叠（思考 / 工具气泡 / 过程内容）在用户控制列表时一律暂缓——折叠会改变 item 高度，
 * 触发 LazyColumn 锚点修正，把正在看历史的用户拽回（"生成完后下滑查看上方消息回弹抽搐"根因）。
 */
val LocalIsChatListUserControlled = staticCompositionLocalOf<(() -> Boolean)?> { null }

/**
 * 由聊天页提供：把列表滚回底部（折叠思考后抵消 LazyColumn scrollBack 的上移）。
 * 挂起实现：内部检查用户是否已滑离底部，且可被取消（用户开始滚动时放弃贴底），
 * 避免"看历史时突然被拽回底部"。
 */
val LocalScrollChatToBottom = staticCompositionLocalOf<(suspend () -> Unit)?> { null }

/**
 * 由聊天页提供：用户手动展开/收起某条消息里的可折叠内容（思考步骤、过程链、工具气泡等）时回调。
 * 聊天页据此在流式加载中取消自动跟随，避免 item 高度骤增被自动跟随硬拽到底部（"展开后突然跳到底部"根因）。
 * 供 ChatMessage / ChainOfThought / 工具气泡等合成调用；未提供（如非主聊天列表页面）时不生效。
 */
val LocalOnManualContentToggle = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 悬浮吸顶条：位于聊天顶栏下方（冻结线处），与顶栏无间距，玻璃材质与顶栏连成一面。
 * 行为对齐 LazyColumn stickyHeader：
 *  - 头部滚动到冻结线（顶栏底边）即停住：玻璃带与行在钉住瞬间同步淡入，
 *    （"停住出现时才模糊"，骑行期间不提前遮糊头部文字）；头部越过线后按原
 *    frozenNow 逻辑隐藏（在顶栏玻璃下方淡出），克隆行停在线上接管点击；
 *  - 正文继续从行下滚过（毛玻璃 + 卡片色 wash 遮瑕）；
 *  - 推出：卡片尾部滑到行下缘，行随卡片上滑淡出，玻璃带保持全显直到段失活。
 * 行的位移逐帧连续（跟滚→钉住→推出），透明度按位置派生（graphicsLayer lambda 读 state），
 * 不触发重组；多思考段场景互不影响。
 */
@Composable
fun ThinkingFrozenBar(
    state: ThinkingFreezeState,
    hazeState: HazeState,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val section = state.activeSection
    // 记住最近一次显示的 section：退场动画期间 section 已变为 null，
    // 仍需渲染旧内容供 fadeOut 使用，避免动画瞬间消失
    var displayedSection by remember { mutableStateOf(section) }
    if (section != null) displayedSection = section
    // 冻结条与真实头部共用同一共享头部行（ReasoningHeaderRow），行在钉住临界区与
    // 真实头部位置重合，过渡为淡入淡出交叉 + 连续位移，无需额外位移动画（避免视觉跳变）。
    val enterTransition: EnterTransition = fadeIn(tween(180))
    val exitTransition: ExitTransition = fadeOut(tween(180))
    AnimatedVisibility(
        visible = section != null,
        modifier = modifier,
        enter = enterTransition,
        exit = exitTransition,
    ) {
        displayedSection?.let {
            ThinkingFrozenBarContent(
                state = state,
                section = it,
                hazeState = hazeState,
                blurEnabled = blurEnabled,
            )
        }
    }
}

/**
 * 行的目标顶边相对钉住位的位移（px）：
 *  - 骑行（topY 在钉住位下方）：跟随真实头部 → 正位移（行不可见，仅玻璃带在渐显）；
 *  - 钉住（topY 已过线）：0（行停在线的自然位置 = 带顶 + 2dp 内边距）；
 *  - 推出（卡片底边已到行下缘上方）：负位移（行随卡片上滑离场）。
 */
private fun rowShiftPx(
    section: ThinkingFrozenBarSection,
    lineY: Int,
    slideTopY: Int,
    rowHeightPx: Int,
): Float {
    val topY = section.topY.value
    val bottomY = section.bottomY.value
    val rowH = rowHeightPx.coerceAtLeast(1)
    val pinnedTop = max(topY, slideTopY)
    val pushTop = bottomY - rowH
    return (min(pinnedTop, pushTop) - slideTopY).toFloat()
}

@Composable
private fun ThinkingFrozenBarContent(
    state: ThinkingFreezeState,
    section: ThinkingFrozenBarSection,
    hazeState: HazeState,
    blurEnabled: Boolean,
) {
    val density = LocalDensity.current
    val cardColor = section.cardColor.value
    // wash 颜色随段切换平滑过渡（相邻两个思考段钉住交接时卡片色不瞬跳）
    val animatedCardColor by animateColorAsState(
        targetValue = cardColor,
        animationSpec = tween(200),
        label = "frozenWashColor",
    )
    val exitAheadPx = with(density) { FrozenExitAhead.toPx() }.toInt()
    val exitDeadPx = with(density) { FrozenExitDead.toPx() }.toInt()
    val pinFadePx = with(density) { FrozenPinFade.toPx() }.toInt()
    // 行在带内的顶内边距：钉住时行顶边 = 冻结线 + 2dp（与跨界时真实头部位置重合的历史语义）
    val pinTopPx = with(density) { 2.dp.toPx() }.toInt()
    // 玻璃带毛玻璃样式：顶部与顶栏同强度相接（连成一张玻璃面），
    // 底部 ~40% 高度内渐隐，连同卡片色 wash 一起熔进卡片正文
    val bandHazeStyle = barHazeBlurStyle(
        progressive = HazeProgressive.verticalGradient(
            startY = with(density) { (ThinkingFrozenBarHeight * 0.55f).toPx() },
            startIntensity = 1f,
            endY = with(density) { ThinkingFrozenBarHeight.toPx() },
            endIntensity = 0f,
        ),
    )
    // 行的实测高度：推出公式用的"行下缘"（卡片尾部滑到它上方才把行推出）
    var rowHeightPx by remember { mutableIntStateOf(0) }

    // 玻璃带全宽铺满（与顶栏同宽同材质，接缝处无缝）；卡片色 wash 限宽到卡片对齐区域
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ThinkingFrozenBarHeight)
            .then(
                if (blurEnabled) Modifier.hazeBlur(
                    input = HazeInput.Sources(hazeState),
                    style = bandHazeStyle,
                )
                else Modifier
            )
            .drawBehind {
                // 卡片色 wash：模糊开时峰值 0.5（保留卡片归属暗示 + 底部融入卡片），
                // 关时峰值 1（实色遮瑕）；左右对齐卡片（16dp / 24dp 内缩）
                val peak = if (blurEnabled) 0.5f else 1f
                val insetStart = 16.dp.toPx()
                val insetEnd = 24.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to animatedCardColor.copy(alpha = peak),
                        0.5f to animatedCardColor.copy(alpha = peak),
                        1f to animatedCardColor.copy(alpha = 0f),
                    ),
                    topLeft = Offset(insetStart, 0f),
                    size = Size(size.width - insetStart - insetEnd, size.height),
                )
            }
            .graphicsLayer {
                // 玻璃带透明度：入口由钉住（shift 归零临界区）驱动同步淡入，
                // 退出由气泡底部露出量驱动——露出量掉进 [FrozenExitDead, FrozenExitAhead]
                // 区间时提前淡出，剩 [FrozenExitDead] 时已完全消失，气泡还剩一点点
                // 未被顶栏覆盖时不再有模糊残留
                val shift = rowShiftPx(section, state.topBarBottomY, state.topBarBottomY + pinTopPx, rowHeightPx)
                val exposed = section.bottomY.value - state.topBarBottomY
                alpha = (1f - shift / pinFadePx).coerceIn(0f, 1f) *
                    ((exposed - exitDeadPx).toFloat() / (exitAheadPx - exitDeadPx)).coerceIn(0f, 1f)
            },
    ) {
        ReasoningHeaderRow(
            title = section.title.value,
            duration = section.duration.value,
            // 流式生成中 → loading=true，主文案走轮换趣味文案；结束后恢复静态标题/思考时长
            loading = section.streaming.value,
            contentVisible = section.contentVisible.value,
            folded = section.folded.value,
            // 骑行中不挂点击（穿透到同位真实头部走折叠逻辑）；钉住/推出后由克隆行接管切换
            onClick = if (section.topY.value <= state.topBarBottomY + pinTopPx) section.onToggle else null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                // 行对齐卡片内容区：列表 16dp 卡片起点 + 12dp 行内缩 = 28dp；
                // 右缘同理 24dp + 12dp（带子已全宽，原来的 16/24 缩进并入行自身）
                .padding(start = 28.dp, top = 2.dp, end = 36.dp, bottom = 8.dp)
                .onSizeChanged { rowHeightPx = it.height }
                .graphicsLayer {
                    // 行位移：骑行跟滚（正，行不可见）；钉住/推出后停在原位不滑动。
                    // 透明度与玻璃带同公式：入口由钉住驱动、退出由气泡露出量驱动，
                    // 行与模糊同步出现、同步消失（气泡还剩一点点未被顶栏覆盖时已无残影）
                    val shift = rowShiftPx(section, state.topBarBottomY, state.topBarBottomY + pinTopPx, rowHeightPx)
                    val exposed = section.bottomY.value - state.topBarBottomY
                    translationY = shift.coerceAtLeast(0f)
                    alpha = (1f - shift / pinFadePx).coerceIn(0f, 1f) *
                        ((exposed - exitDeadPx).toFloat() / (exitAheadPx - exitDeadPx)).coerceIn(0f, 1f)
                },
        )
    }
}