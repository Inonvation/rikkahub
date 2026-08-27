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
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.SubAgentUsageDAO
import me.rerere.rikkahub.data.db.dao.DayModelUsage
import me.rerere.rikkahub.data.db.dao.MessageTokenStats
import me.rerere.rikkahub.data.db.dao.ModelUsageEntry
import me.rerere.rikkahub.data.db.dao.AssistantUsageEntry
import me.rerere.rikkahub.data.db.dao.SubAgentTokenStats
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.db.dao.getTrendByModel
import me.rerere.rikkahub.data.db.dao.getModelUsage
import me.rerere.rikkahub.data.db.dao.getAssistantUsage
import me.rerere.rikkahub.data.db.dao.getModelNameSnapshots
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
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
    // 消息落库时的模型名快照（modelId -> 展示名），配置失联时兜底显示真实名称
    val modelNameSnapshots: Map<String, String> = emptyMap(),
)

/** 统计聚合结果的缓存有效期：期内导航进出统计页直接复用，不重复全表 json_each 聚合 */
private const val STATS_CACHE_TTL_MS = 60_000L

/** 进程级统计缓存：避免每次进入统计页都跑聚合查询 */
private object StatsCache {
    var snapshot: AppStats? = null
    var updatedAt: Long = 0L
}

/**
 * 把带日期/版本后缀的 API 模型 id 清洗为可读名称：
 * glm-flash-20250827 → glm-flash、claude-3-5-sonnet-20241022 → claude-3-5-sonnet。
 * 仅在显示名就是 API modelId（探测/导入写入）时使用，避免误伤自定义名称。
 */
private fun cleanModelId(modelId: String): String =
    modelId
        .replace(Regex("""-\d{4}-\d{2}-\d{2}$"""), "")
        .replace(Regex("""-\d{8}$"""), "")

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

        val launchCount = settingsStore.settingsFlow.value.launchCount

        // 全部统计查询并行执行（IO 线程），避免串行等待
        val trendStartDate = today.minusYears(1).toString()
        val trendStartMillis = today.minusYears(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val conversationsPerDay: Map<LocalDate, Int>
        val totalConversations: Int
        val tokenStats: MessageTokenStats
        val subStats: SubAgentTokenStats
        val trendByModel: List<DayModelUsage>
        val modelUsage: List<ModelUsageEntry>
        val assistantUsage: List<AssistantUsageEntry>
        val subTrendByModel: List<DayModelUsage>
        val subModelUsage: List<ModelUsageEntry>
        val modelNameSnapshots: Map<String, String>
        coroutineScope {
            // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
            val heatmapDeferred = async(Dispatchers.IO) {
                messageNodeDAO
                    .getMessageCountPerDay(startDate)
                    .mapNotNull { entry ->
                        runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                    }
                    .toMap()
            }
            val countDeferred = async(Dispatchers.IO) { conversationDAO.countAll() }
            // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
            val tokenDeferred = async(Dispatchers.IO) { messageNodeDAO.getTokenStats() }
            // 子代理 token 统计（独立表持久化），与主聊天合并
            val subTokenDeferred = async(Dispatchers.IO) { subAgentUsageDAO.getTokenStats() }
            // 趋势与用量排行统一取最近一年窗口（12 周 / 6 个月粒度均覆盖），避免全表 json_each 展开
            val trendDeferred = async(Dispatchers.IO) { messageNodeDAO.getTrendByModel(trendStartDate) }
            val modelDeferred = async(Dispatchers.IO) { messageNodeDAO.getModelUsage(trendStartDate) }
            val assistantDeferred = async(Dispatchers.IO) { messageNodeDAO.getAssistantUsage(trendStartDate) }
            val subTrendDeferred = async(Dispatchers.IO) { subAgentUsageDAO.getTrendByModel(trendStartMillis) }
            val subModelDeferred = async(Dispatchers.IO) { subAgentUsageDAO.getModelUsage(trendStartMillis) }
            val snapshotNamesDeferred = async(Dispatchers.IO) {
                messageNodeDAO.getModelNameSnapshots(trendStartDate)
            }
            conversationsPerDay = heatmapDeferred.await()
            totalConversations = countDeferred.await()
            tokenStats = tokenDeferred.await()
            subStats = subTokenDeferred.await()
            trendByModel = trendDeferred.await()
            modelUsage = modelDeferred.await()
            assistantUsage = assistantDeferred.await()
            subTrendByModel = subTrendDeferred.await()
            subModelUsage = subModelDeferred.await()
            modelNameSnapshots = snapshotNamesDeferred.await()
                .mapNotNull { snapshot ->
                    val name = snapshot.modelName.ifBlank { null } ?: return@mapNotNull null
                    snapshot.modelId.takeIf { it.isNotEmpty() }?.let { it to cleanModelId(name) }
                }
                .toMap()
        }

        // 模型/助手显示名映射（id -> 名称），UI 侧排行榜用于展示。
        // 探测/导入模型时 displayName 会被写成 API modelId（如 glm-flash-20250827），
        // 统计展示时清洗掉日期/版本后缀呈现可读名称；用户自定义过的显示名原样保留。
        val settings = settingsStore.settingsFlow.value
        val rawModelDisplayNames = settings.providers
            .flatMap { it.models }
            .associate { model ->
                val name = model.displayName.ifBlank { model.modelId }
                model.id.toString() to if (name == model.modelId) cleanModelId(model.modelId) else name
            }
        // 不同供应商可能配置同名模型（Uuid 不同、显示名相同）：统计按显示名合并后，
        // 条目的 modelId 即显示名，这里补自映射让 UI 侧能直接解析，不再显示为「未知模型」
        val modelDisplayNames = rawModelDisplayNames + rawModelDisplayNames.values.associateWith { it }
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
            trendByModel = mergeTrendByDisplayName(trendByModel + subTrendByModel, rawModelDisplayNames),
            modelUsage = mergeModelUsageByDisplayName(modelUsage + subModelUsage, rawModelDisplayNames),
            assistantUsage = assistantUsage,
            modelDisplayNames = modelDisplayNames,
            assistantDisplayNames = assistantDisplayNames,
            modelNameSnapshots = modelNameSnapshots,
        )
    }
}
