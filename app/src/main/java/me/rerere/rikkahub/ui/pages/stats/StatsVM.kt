package me.rerere.rikkahub.ui.pages.stats

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.SubAgentUsageDAO
import me.rerere.rikkahub.data.db.dao.DayModelUsage
import me.rerere.rikkahub.data.db.dao.ModelUsageEntry
import me.rerere.rikkahub.data.db.dao.AssistantUsageEntry
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.db.dao.getTrendByModel
import me.rerere.rikkahub.data.db.dao.getModelUsage
import me.rerere.rikkahub.data.db.dao.getAssistantUsage
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    // 用量趋势：按日 + 模型聚合，供堆叠柱状图分段展示
    val trendByModel: List<DayModelUsage> = emptyList(),
    // 模型使用率：按 modelId 聚合，由高到低
    val modelUsage: List<ModelUsageEntry> = emptyList(),
    // 助手使用率：按 assistantId 聚合，由高到低
    val assistantUsage: List<AssistantUsageEntry> = emptyList(),
    // id -> 显示名映射（模型/助手，未识别的在 UI 侧回退）
    val modelDisplayNames: Map<String, String> = emptyMap(),
    val assistantDisplayNames: Map<String, String> = emptyMap(),
)

/** 统计聚合结果的缓存有效期：期内导航进出统计页直接复用，不重复全表 json_each 聚合 */
private const val STATS_CACHE_TTL_MS = 60_000L

/** 进程级统计缓存：避免每次进入统计页都跑聚合查询 */
private object StatsCache {
    var snapshot: AppStats? = null
    var updatedAt: Long = 0L
}

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
    private val subAgentUsageDAO: SubAgentUsageDAO,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val now = SystemClock.elapsedRealtime()
        val cached = StatsCache.snapshot
        when {
            // 缓存新鲜：直接使用，秒开，不再聚合
            cached != null && now - StatsCache.updatedAt < STATS_CACHE_TTL_MS -> {
                _stats.value = cached.copy(isLoading = false)
                return
            }

            // 有缓存但已过期：先显示旧数据，后台重新聚合（stale-while-revalidate）
            cached != null -> {
                _stats.value = cached.copy(isLoading = false)
            }

            // 无缓存：显示加载态
            else -> {
                _stats.value = _stats.value.copy(isLoading = true)
            }
        }

        val result = computeStats()
        StatsCache.snapshot = result
        StatsCache.updatedAt = SystemClock.elapsedRealtime()
        _stats.value = result
    }

    private suspend fun computeStats(): AppStats {
        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        // 子代理 token 统计（独立表持久化），与主聊天合并
        val subStats = subAgentUsageDAO.getTokenStats()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        // 三个聚合查询并行执行（IO 线程，避免串行等待）
        val (trendByModel, modelUsage, assistantUsage) = coroutineScope {
            // 趋势只取最近一年（12 周 / 6 个月粒度均覆盖）
            val trendDeferred = async(Dispatchers.IO) {
                messageNodeDAO.getTrendByModel(today.minusYears(1).toString())
            }
            val modelDeferred = async(Dispatchers.IO) { messageNodeDAO.getModelUsage() }
            val assistantDeferred = async(Dispatchers.IO) { messageNodeDAO.getAssistantUsage() }
            Triple(
                trendDeferred.await(),
                modelDeferred.await(),
                assistantDeferred.await(),
            )
        }

        // 模型/助手显示名映射（id -> 名称），UI 侧排行榜用于展示
        val settings = settingsStore.settingsFlow.value
        val modelDisplayNames = settings.providers
            .flatMap { it.models }
            .associate { it.id.toString() to it.displayName.ifBlank { it.modelId } }
        val assistantDisplayNames = settings.assistants
            .associate { it.id.toString() to it.name }

        return AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens + subStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens + subStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens + subStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
            trendByModel = trendByModel,
            modelUsage = modelUsage,
            assistantUsage = assistantUsage,
            modelDisplayNames = modelDisplayNames,
            assistantDisplayNames = assistantDisplayNames,
        )
    }
}
