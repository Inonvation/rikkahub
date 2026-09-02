package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.modifier.shimmer

/** 思考文案第一阶段（正经组）的结束毫秒 */
internal const val THINKING_PHRASE_EARLY_MS = 10_000L

/** 思考文案第二阶段（摸鱼组）的结束毫秒 */
internal const val THINKING_PHRASE_MID_MS = 30_000L

/** 正经组的轮换间隔：文案庄重，不需要频繁换句 */
internal const val THINKING_PHRASE_INTERVAL_EARLY_MS = 5_000L

/** 摸鱼组的轮换间隔 */
internal const val THINKING_PHRASE_INTERVAL_MID_MS = 4_000L

/** 夸张组的轮换间隔：思考越久，越需要"还在动"的视觉反馈，间隔收窄 */
internal const val THINKING_PHRASE_INTERVAL_LATE_MS = 3_000L

/**
 * 根据已思考的时长返回文案组下标：0=正经（0~10s）、1=摸鱼（10~30s）、2=夸张（30s+）。
 */
internal fun phraseGroupIndexFor(elapsedMs: Long): Int = when {
    elapsedMs < THINKING_PHRASE_EARLY_MS -> 0
    elapsedMs < THINKING_PHRASE_MID_MS -> 1
    else -> 2
}

/**
 * 根据已思考的时长返回轮换间隔：随思考推进逐渐收窄（5s → 4s → 3s）。
 * 与 [phraseGroupIndexFor] 共用同一组边界，保证"换组"与"节奏加快"同步发生。
 */
internal fun phraseIntervalFor(elapsedMs: Long): Long = when {
    elapsedMs < THINKING_PHRASE_EARLY_MS -> THINKING_PHRASE_INTERVAL_EARLY_MS
    elapsedMs < THINKING_PHRASE_MID_MS -> THINKING_PHRASE_INTERVAL_MID_MS
    else -> THINKING_PHRASE_INTERVAL_LATE_MS
}

/**
 * 生成一组洗牌后的下标序列（Fisher-Yates），保证三个性质：
 * 1. 长度等于 [size]；2. 是 0..size-1 的一个排列；3. [avoidFirst] 非空时首项不等于它
 * （避免相邻两次显示同一个文案）。
 */
internal fun shuffledPhraseOrder(size: Int, avoidFirst: Int? = null): List<Int> {
    if (size <= 1) return List(size) { it }
    val order = (0 until size).toMutableList()
    for (i in order.indices.reversed()) {
        val j = Random.nextInt(i + 1)
        val tmp = order[i]
        order[i] = order[j]
        order[j] = tmp
    }
    if (avoidFirst != null && order.first() == avoidFirst) {
        // size>1 时必然存在一个不等于 avoidFirst 的下标，交换到首位
        val swap = (1 until size).firstOrNull { order[it] != avoidFirst }
        if (swap != null) {
            val tmp = order[0]
            order[0] = order[swap]
            order[swap] = tmp
        }
    }
    return order
}

/**
 * AI 思考（reasoning 流式进行中）时显示的轮换趣味文案。
 *
 * 文案按思考时长分三组递进：0~10s 正经组 → 10~30s 摸鱼组 → 30s+ 夸张组，
 * 每组内部洗牌后循环、走完一轮重洗且不与上一条重复，配合 [Crossfade] 淡入淡出切换。
 * 轮换间隔也随时长收窄（5s → 4s → 3s，见 [phraseIntervalFor]）：思考越久换句越快，
 * 避免画面静止让用户误以为卡死。
 *
 * - 默认英文（及其他语言）从三个分级字符串数组读取文案池，仅首次组合时读取一次；
 * - [enabled] 为 true 时按阶段间隔切换一条，并带 shimmer 闪烁；
 * - [enabled] 变为 false（生成结束）时停止轮换，停留在当前文案且不再闪烁。
 *
 * 当模型写了自定义思考标题（[primaryTitle]）时，标题以次级色小字显示在主文案旁边，
 * 主文案仍为轮换文案——符合"轮换为主、原标题保留在旁边"的需求。
 */
@Composable
internal fun RotatingThinkingLabel(
    enabled: Boolean,
    primaryTitle: String?,
    chatFontFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    // 三个文案组只读一次并缓存，避免流式期间每次重组重复解析资源
    val earlyPhrases = stringArrayResource(R.array.thinking_rotation_phrases_early)
    val midPhrases = stringArrayResource(R.array.thinking_rotation_phrases_mid)
    val latePhrases = stringArrayResource(R.array.thinking_rotation_phrases_late)
    val phraseGroups = remember {
        listOf(earlyPhrases, midPhrases, latePhrases)
    }
    // 组件进入组合的时刻作为思考起点（loading 期间组件才存在于组合中）
    val startMillis = remember { Clock.System.now().toEpochMilliseconds() }

    var groupIndex by remember { mutableIntStateOf(0) }
    var order by remember { mutableStateOf(shuffledPhraseOrder(phraseGroups[0].size)) }
    var cursor by remember { mutableIntStateOf(0) }
    var current by remember {
        mutableStateOf(phraseGroups[0].getOrElse(order[0]) { "…" })
    }

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            // 间隔按"醒来前的时长"选取，与换组判定同源：走到边界附近时先按旧档位等待，
            // 醒来后以最新 elapsed 换组，组切换与节奏加快在同一轮生效
            delay(phraseIntervalFor(Clock.System.now().toEpochMilliseconds() - startMillis))
            val elapsed = Clock.System.now().toEpochMilliseconds() - startMillis
            val group = phraseGroupIndexFor(elapsed)
            if (group != groupIndex) {
                // 进入下一阶段：换组并重新洗牌
                groupIndex = group
                order = shuffledPhraseOrder(phraseGroups[group].size)
                cursor = 0
            } else {
                cursor = (cursor + 1) % order.size
                if (cursor == 0) {
                    // 走完一轮：重洗并避免首项与上一轮最后一条重复
                    order = shuffledPhraseOrder(order.size, avoidFirst = order.last())
                }
            }
            current = phraseGroups[groupIndex].getOrElse(order[cursor]) { "…" }
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Crossfade(
            targetState = current,
            animationSpec = tween(300),
            label = "thinkingPhraseFade",
        ) { phrase ->
            Text(
                text = phrase,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.shimmer(isLoading = enabled),
            )
        }
        primaryTitle?.let {
            Text(
                text = "· $it",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = chatFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
