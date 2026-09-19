package me.rerere.rikkahub.ui.pages.chat

import android.os.SystemClock
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.ui.components.message.ThinkingFreezeState

// 用户触碰/滚动列表后的冷却窗口：窗口内自动跟随一律不发起。
// 封死两个竞态：手指已按下但尚未消费滚动（touch-slop 窗口）、手指抬起后
// 排队中的 scrollToItem 才执行（松手窗口）。
internal const val USER_SCROLL_COOLDOWN_MS = 400L

// 手动展开/折叠内容后的跟随解锁窗：窗口内点按冷却不再拦跟随，主跟随逐帧重锚贴底。
// 必须 >= USER_SCROLL_COOLDOWN_MS：点按本身会刷新触点冷却（抬起刷新晚 click 回调约一帧），
// 窗口短于冷却会在"窗口已到期、冷却未结束"之间留下无跟随盲区，漂移重新累积成小硬跳。
internal const val CONTENT_TOGGLE_FOLLOW_UNLOCK_MS = 600L

// "用户正在控制列表"的手势判定窗（自动折叠暂缓、发送贴底放弃、折叠回底放弃共用）：
// 仅真实滚动手势刷新（见 [ChatListScroller.lastUserGestureAt]），纯点击不刷新，
// 保证"点折叠卡 → 回底"的既有行为不被时间窗误伤。
internal const val USER_GESTURE_WINDOW_MS = 350L

/**
 * 聊天列表滚动控制中枢：唯一持有"谁在控制列表"的状态与全部程序滚动入口。
 *
 * 此前同类状态散落在 ChatList（跟随闩锁 + 触点冷却 + 解锁窗 + 滑贴）与 ChatPage
 * （接管闩锁 + 手势时间戳 + 发送贴底 + 各 CompositionLocal 内联实现）两处，两套
 * 收集器各自订阅同一布局流、各自判定贴底，容易演化出不一致。现全部收敛到本类：
 * ChatPage 创建并把它通过 CompositionLocal 提供给消息组件；ChatList 的触点追踪器
 * 与主循环 [ChatListScrollEffect] 驱动它。
 *
 * 状态机（主循环逐帧评估，语义与逐条守卫的防回归核对见 docs/chat-scroll-refactor-plan.md）：
 * - [userTouching]：手指按在列表上（down 即置位，覆盖 touch-slop 前窗口）。
 *   程序滚动绝不与用户拖拽并发。
 * - [followSuspended]（跟随闩锁）：用户手势滚动即挂起（不判方向，fling 中间帧会丢）；
 *   稳定贴底（过触点冷却）、用户手势滚回底部、或新用户消息入列时解除。
 *   解除后的第一发跟随改动画滑贴（[glidePending]），消除停留期流式累积差量的硬跳。
 * - [userTookOver]（接管闩锁）：触碰或用户手势滚动即置位、稳定贴底解除。
 *   自动折叠（思考/工具气泡/过程内容）据此暂缓——折叠改变 item 高度会触发
 *   LazyColumn 锚点修正，把正在看历史的用户拽回。
 * - [lastTouchAt]：任意触碰/手势时刻，跟随冷却与松手窗口用。
 * - [lastUserGestureAt]：仅真实滚动手势时刻（触碰中恰逢布局变化、或非程序滚动
 *   进行中）。纯点击不刷新——"点折叠卡 → 回底"不被 350ms 窗误伤。
 *
 * 程序滚动一律走本类方法（[collapseRepin] / [scrollByProgram] / [requestSendScroll]，
 * 跟随在 [ChatListScrollEffect]）：挂起滚动可被用户手势打断（LazyColumn 滚动互斥），
 * 发请求前以实时触点/滚动状态二次校验。跟随与折叠回底会把
 * [ThinkingFreezeState.scrollingByProgram] 置位，主循环据此不把程序滚动记作用户手势。
 */
@Stable
internal class ChatListScroller(
    val listState: LazyListState,
    internal val scope: CoroutineScope,
    val freezeState: ThinkingFreezeState? = null,
) {
    /** 用户手指正按在列表上（ChatList 触点追踪器维护，down 即置位） */
    var userTouching by mutableStateOf(false)
        internal set

    /** 最近一次触碰/手势滚动时刻（elapsedRealtime）：跟随冷却窗口用 */
    var lastTouchAt by mutableLongStateOf(SystemClock.elapsedRealtime())
        internal set

    /** 最近一次真实滚动手势时刻：350ms 判定窗用，纯点击不刷新 */
    var lastUserGestureAt by mutableLongStateOf(0L)
        internal set

    /** 手动展开/折叠消息内可折叠内容的时刻：跟随解锁窗用 */
    var lastContentToggleAt by mutableLongStateOf(0L)
        internal set

    /** 跟随闩锁：true = 用户已上滑接管，自动跟随挂起 */
    var followSuspended by mutableStateOf(false)
        internal set

    /** 接管闩锁：触碰/用户手势即置位、稳定贴底解除；自动折叠据此暂缓 */
    var userTookOver by mutableStateOf(false)
        internal set

    /** 发送贴底请求标记（performSend 置位，ChatList 在列表尺寸变化时消费） */
    var sendScrollPending by mutableStateOf(false)
        private set

    /** 重新武装后的第一发跟随改动画滑贴：pending = 待消费，job = 在途平滑滚动 */
    internal var glidePending = false
    internal var glideJob: Job? = null

    /** 列表是否钉在底部（或内容不满一屏）：末项可见且其下缘不低于内容区下缘（8px 容差） */
    fun isPinned(info: LazyListLayoutInfo = listState.layoutInfo): Boolean {
        val last = info.visibleItemsInfo.lastOrNull() ?: return false
        return isChatListPinnedToBottom(
            totalItemsCount = info.totalItemsCount,
            lastVisibleIndex = last.index,
            lastItemEnd = last.offset + last.size,
            viewportEnd = info.viewportEndOffset,
            afterContentPadding = info.afterContentPadding,
        )
    }

    /** 用户是否正在控制列表：触碰中 / 手势滚动中（程序滚动除外）/ 350ms 内有手势 / 接管闩锁 */
    fun isUserControlled(): Boolean {
        return userTouching ||
            (listState.isScrollInProgress && freezeState?.scrollingByProgram != true) ||
            SystemClock.elapsedRealtime() - lastUserGestureAt < USER_GESTURE_WINDOW_MS ||
            userTookOver
    }

    /** 触点追踪器入口：按下状态变化时更新并刷新触点冷却 */
    fun noteUserTouch(pressed: Boolean) {
        if (userTouching != pressed) {
            userTouching = pressed
            lastTouchAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 用户手动展开/折叠消息内可折叠内容（思考步骤/过程链/工具气泡等）：
     * - 贴底：明确的跟随意图信号——清跟随闩锁（防"回底后闩锁尚未稳定复位"边缘态）
     *   并开解锁窗，主跟随从高度动画首帧起逐帧重锚贴底；
     * - 离底（读历史/上翻）：武装跟随闩锁，防 item 高度突变被自动跟随拽回。
     * 不判 userTouching / isScrollInProgress：click 回调与触点追踪器同处一个抬起事件的
     * Main pass，子节点先于父节点处理，点击此刻触碰态必然仍为 true——判它会让贴底
     * 分支永不可达；真正的拖拽接管由主循环处理。
     */
    fun onManualContentToggle() {
        if (isPinned()) {
            followSuspended = false
            lastContentToggleAt = SystemClock.elapsedRealtime()
        } else {
            followSuspended = true
            lastTouchAt = SystemClock.elapsedRealtime()
        }
    }

    /** 发送后贴底请求（performSend 调用；由 ChatList 在列表尺寸变化时消费） */
    fun requestSendScroll() {
        sendScrollPending = true
    }

    /**
     * 消费发送贴底请求：新消息已入列且用户没有在操作列表时，requestScrollToItem
     * 在下一次 measure 直落底部（即时无动画，不与流式布局抢帧）。用户正滚动/
     * 触碰/350ms 内刚有过手势时放弃——新用户消息已解除跟随闩锁，由自动跟随接管贴底。
     */
    fun consumeSendScroll(): Boolean {
        if (!sendScrollPending) return false
        sendScrollPending = false
        val recentlyTouched =
            SystemClock.elapsedRealtime() - lastUserGestureAt < USER_GESTURE_WINDOW_MS
        if (listState.isScrollInProgress || userTouching || recentlyTouched) return false
        // 用真实末项索引：越界索引会先落到 bogus 落点再重锚，产生位置闪变
        val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        listState.requestScrollToItem(target)
        return true
    }

    /**
     * 折叠思考/过程内容后的回底（LocalScrollChatToBottom）：抵消折叠内容收缩后
     * LazyColumn scrollBack 的视口上移。用户已接管/有手势/正在触碰时放弃，
     * 绝不把正在看历史的用户拽回。
     */
    suspend fun collapseRepin() {
        // 消费接管闩锁（无论是否贴底都复位，让下一次折叠重新武装）
        val interrupted = userTookOver
        userTookOver = false
        val recentlyScrolled =
            SystemClock.elapsedRealtime() - lastUserGestureAt < USER_GESTURE_WINDOW_MS
        val userGesture = listState.isScrollInProgress && freezeState?.scrollingByProgram != true
        if (interrupted || recentlyScrolled || userGesture || userTouching) return
        // 程序滚动标记：贴底滚动本身不算用户滚动（避免反向武装接管闩锁/自动跟随抢滚）。
        // 可取消的挂起 scrollToItem + 超时兜底：贴底前用户恰好起手即放弃，绝不压过手势。
        freezeState?.scrollingByProgram = true
        try {
            withTimeoutOrNull(300) {
                listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }
        } finally {
            freezeState?.scrollingByProgram = false
        }
    }

    /**
     * 吸顶条滚动折叠/展开：按像素量平滑滚动（正数向上滚/负数向下滚）。
     * 直接 scrollBy 不受锚点换算影响；程序滚动期间抑制自动跟随抢滚。
     */
    suspend fun scrollByProgram(delta: Float) {
        if (delta == 0f) return
        if (listState.isScrollInProgress) return
        freezeState?.scrollingByProgram = true
        try {
            listState.scrollBy(delta)
        } finally {
            freezeState?.scrollingByProgram = false
        }
    }
}

/**
 * 聊天列表滚动主循环：单一 snapshotFlow 逐帧评估"用户接管"与"自动跟随"。
 * 由 [ChatList] 调用；接管判定独立于自动跟随运行（自动折叠抑制不受开关影响）。
 *
 * 跟随用可取消的挂起 scrollToItem（新 emission 即取消在途滚动、逐帧重锚），
 * 不用 fire-and-forget 的 requestScrollToItem——它在滚动进行中会硬跳并把位置钉死，
 * 且会在列表内部作用域排一个手势结束后的补正协程（"闪到底又弹回"根因）。
 */
@Composable
internal fun ChatListScrollEffect(
    scroller: ChatListScroller,
    autoScrollEnabled: Boolean,
    loading: Boolean,
) {
    val loadingState by rememberUpdatedState(loading)
    val state = scroller.listState
    val freezeState = scroller.freezeState
    LaunchedEffect(state, loadingState, autoScrollEnabled, freezeState) {
        // 记录"上一帧是否在滚动"与"本次滚动是否由用户手指驱动"：
        // 用于在用户亲手滚回底部并停下（手势结束）时立刻重新武装跟随。
        var wasScrollInProgress = false
        var scrollWasUserDriven = false
        snapshotFlow {
            Pair(state.layoutInfo, state.isScrollInProgress)
        }.collectLatest { (info, inProgress) ->
            val pinned = scroller.isPinned(info)
            val userTouching = scroller.userTouching
            // 本次滚动过程中任一转场看到"手指按在列表上"即记为用户驱动；
            // 抬起后 fling 仍算用户手势（isScrollInProgress 继续但手指已抬起）
            if (inProgress && userTouching) {
                scrollWasUserDriven = true
            }
            // 手势结束 = 上一帧在滚、本帧已停。先读后更，避免被 collectLatest
            // 取消时丢状态。
            val scrollEnded = wasScrollInProgress && !inProgress
            wasScrollInProgress = inProgress

            // —— 用户接管判定（自动折叠抑制；独立于自动滚动开关）——
            val programScroll = freezeState?.scrollingByProgram == true
            if (userTouching || (inProgress && !programScroll)) {
                // 手指按在列表上（即使尚未消费滚动）或非程序滚动进行中 → 接管。
                // 程序滚动（跟随/折叠动画）不算：否则生成末帧的最后一次跟随会把
                // 自动折叠误判为"用户在看历史"而跳过。
                scroller.userTookOver = true
                scroller.lastUserGestureAt = SystemClock.elapsedRealtime()
            } else if (!inProgress && pinned && !userTouching) {
                // 列表稳定贴底且用户未触碰 → 解除接管
                scroller.userTookOver = false
            }

            if (!autoScrollEnabled) return@collectLatest
            // 空布局帧（组合销毁/首帧布局前 LazyColumn 短暂清空可见列表）整段跳过跟随评估：
            // 此帧的 pinned 恒为 false，放行会误发跟随滚动
            if (info.visibleItemsInfo.isEmpty()) return@collectLatest

            // —— 跟随闩锁：解除与重武装 ——
            // 解除：任何用户手势滚动都算接管，不做方向判断（快速 fling / 与程序滚动
            // 重叠时 collectLatest 会丢中间帧，方向判定漏闩 → 生成结束瞬间把正在看
            // 历史的用户拽回）。回到底部由下方两条重武装路径恢复。
            if (inProgress && userTouching) {
                scroller.followSuspended = true
                scroller.lastTouchAt = SystemClock.elapsedRealtime()
            }
            // 重武装 1：滚动稳定、确实贴底、且过了触点冷却。冷却防"内容突变恰好贴底"
            // 的误复位——用户仍在上拉后的位置，立即复位会让跟随下一帧把列表拽回。
            val settledAfterInteraction =
                SystemClock.elapsedRealtime() - scroller.lastTouchAt >= USER_SCROLL_COOLDOWN_MS
            if (!inProgress && !state.isScrollInProgress && pinned && settledAfterInteraction) {
                scroller.followSuspended = false
                // 本帧起列表回到底部、跟随将恢复，其间的流式内容已累积在下方，
                // 第一发即时 scrollToItem 会硬跳该差量，改为动画滑贴过渡。
                scroller.glidePending = true
            }
            // 重武装 2：用户驱动的滚动手势刚结束且停在底部 → 立即恢复（不等冷却窗）。
            // 只靠重武装 1 的话，流式追加会在冷却窗内持续把 pinned 打成 false，
            // "打断跟随后再手动滑回底部"不会恢复跟随；此处以"用户亲手滚回底部"
            // 的手势结束信号代替，非用户驱动不会走到这里，不会误武装。
            if (scrollEnded && scrollWasUserDriven && !state.isScrollInProgress && pinned) {
                scroller.followSuspended = false
                scroller.lastTouchAt = SystemClock.elapsedRealtime()
                scroller.glidePending = true
            }
            // 滚动停止后清空用户驱动标记，避免上一个手势的标记串到下一次程序滚动
            if (!inProgress) {
                scrollWasUserDriven = false
            }

            // —— 发起跟随：仅当生成中、用户未上滑、列表已离开底部 ——
            // 已钉底/正在滚动/折叠动画中/用户触碰中都不发；发请求前用实时
            // isScrollInProgress 二次校验，挡住 snapshotFlow 的陈旧快照。
            val shouldFollow = !scroller.followSuspended && loadingState && !pinned
            val followCooledDown =
                SystemClock.elapsedRealtime() - scroller.lastTouchAt >= USER_SCROLL_COOLDOWN_MS
            // 手动展开/折叠后的解锁窗：点按本身刷新了触点冷却，窗口内绕过它，
            // 让主跟随从展开动画首帧起逐帧重锚贴底（漂移无法累积，无"先展开再蹦"）
            val toggleUnlocked =
                SystemClock.elapsedRealtime() - scroller.lastContentToggleAt < CONTENT_TOGGLE_FOLLOW_UNLOCK_MS
            val requestNow = !inProgress && !state.isScrollInProgress && !programScroll &&
                shouldFollow && (followCooledDown || toggleUnlocked) && !userTouching
            if (requestNow && !state.isScrollInProgress) {
                // 跟随滚动期间置 scrollingByProgram：接管判定不把程序滚动记作用户手势，
                // 生成结束的自动折叠不被"刚跟随过"误杀；collectLatest 取消在途滚动时 finally 复位。
                // 分流：重新武装后的第一发（glidePending）改动画滑贴，挂在函数级 scope，
                // 不随 collectLatest 重启/取消；动画中途用户起手拖拽时由 foundation
                // 取消在途滚动、finally 复位标志。
                val gliding = scroller.glideJob?.isActive == true
                if (scroller.glidePending && !gliding) {
                    scroller.glidePending = false
                    scroller.glideJob = scroller.scope.launch {
                        freezeState?.scrollingByProgram = true
                        try {
                            // 目标取当前末项（底部占位 Spacer），动画距离自适应；
                            // 动画期间流式新内容若继续追加，收尾由后续即时跟随补上，差量极小
                            state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1)
                        } finally {
                            freezeState?.scrollingByProgram = false
                            scroller.glideJob = null
                        }
                    }
                } else {
                    freezeState?.scrollingByProgram = true
                    try {
                        state.scrollToItem(info.totalItemsCount - 1)
                    } finally {
                        freezeState?.scrollingByProgram = false
                    }
                }
            }
        }
    }
}
