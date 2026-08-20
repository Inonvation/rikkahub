package me.rerere.rikkahub.data.ai.cost

import kotlinx.serialization.Serializable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.TokenUsage
import kotlin.time.Clock
import kotlin.time.Instant

/** 单模型定价配置。美元单价单位：USD / 每 1M tokens；人民币单价单位：CNY / 每 1M tokens */
@Serializable
data class ModelPricingConfig(
    val modelId: String,
    val inputPriceUsd: Double = 0.0,
    val outputPriceUsd: Double = 0.0,
    val cachedInputPriceUsd: Double = 0.0,
    /** 人民币官方单价（每 1M tokens）。0 表示官方未公布人民币价，运行时按汇率换算。 */
    val inputPriceCny: Double = 0.0,
    val outputPriceCny: Double = 0.0,
    val cachedInputPriceCny: Double = 0.0,
    /** 按 DeepSeek 高峰/空闲自动计价时，基础价视为空闲价，高峰价为基础价 × 2。 */
    val timeAware: Boolean = false,
    /** 费用倍率，用于代理/中转渠道按官方价上浮或打折（默认 1.0） */
    val multiplier: Double = 1.0,
)

@Serializable
enum class CostCurrency {
    USD,
    RMB,
}

/**
 * 会话费用估算与缓存命中率统计。
 *
 * 定价分两层：内置常见模型预置表（2026-08 官网价）+ 用户在费用配置窗里的覆盖。
 * 未知模型计 0（UI 显示 $0.00），可点费用打开配置窗补充。
 *
 * 费用口径：`uncached_input × inPrice + cached × cachedPrice + output × outPrice`，
 * 其中 uncached_input = promptTokens - cachedTokens（各家 provider 的 promptTokens 都已含缓存部分）。
 */
object CostCalculator {
    private val BEIJING_TIME_ZONE = TimeZone.of("Asia/Shanghai")

    /** 北京时间 09:00-12:00、14:00-18:00 为 DeepSeek 高峰时段，其余为空闲时段。 */
    fun isPeakTime(timeMillis: Long? = null): Boolean {
        val instant = timeMillis?.let { Instant.fromEpochMilliseconds(it) } ?: Clock.System.now()
        val hour = instant.toLocalDateTime(BEIJING_TIME_ZONE).hour
        return hour in 9..11 || hour in 14..17
    }

    /**
     * 预置表，条目按「更具体在前」排列（resolve 用 contains 子串匹配，第一个命中生效）。
     *
     * 数据来源（2026-08-20 查证）：
     * - Anthropic platform.claude.com/docs/en/about-claude/pricing（Base input / Output / Cache Hits）
     * - OpenAI developers.openai.com/api/docs/pricing（短上下文标准价）
     * - Gemini ai.google.dev/gemini-api/docs/pricing（≤200k 标准价）
     * - DeepSeek api-docs.deepseek.com/quick_start/pricing
     */
    val PRESET_PRICING: List<ModelPricingConfig> = listOf(
        // ---- Anthropic Claude ----
        ModelPricingConfig("claude-fable", 10.0, 50.0, 1.0),
        ModelPricingConfig("claude-mythos", 10.0, 50.0, 1.0),
        ModelPricingConfig("claude-opus-4-1", 15.0, 75.0, 1.5),
        ModelPricingConfig("claude-opus-4", 15.0, 75.0, 1.5),
        ModelPricingConfig("claude-opus", 5.0, 25.0, 0.5),
        ModelPricingConfig("claude-haiku-3", 0.8, 4.0, 0.08),
        ModelPricingConfig("claude-haiku", 1.0, 5.0, 0.1),
        ModelPricingConfig("claude-sonnet", 3.0, 15.0, 0.3),

        // ---- OpenAI ----
        ModelPricingConfig("gpt-5.6-sol", 5.0, 30.0, 0.5),
        ModelPricingConfig("gpt-5.6-terra", 2.0, 12.0, 0.2),
        ModelPricingConfig("gpt-5.6-luna", 0.2, 1.2, 0.02),
        ModelPricingConfig("gpt-5.5", 5.0, 30.0, 0.5),
        ModelPricingConfig("gpt-5.4-mini", 0.75, 4.5, 0.075),
        ModelPricingConfig("gpt-5.4-nano", 0.2, 1.25, 0.02),
        ModelPricingConfig("gpt-5.4", 2.5, 15.0, 0.25),
        ModelPricingConfig("gpt-5.2", 1.75, 14.0, 0.175),
        ModelPricingConfig("gpt-5.1", 1.25, 10.0, 0.125),
        ModelPricingConfig("gpt-5-mini", 0.25, 2.0, 0.025),
        ModelPricingConfig("gpt-5-nano", 0.05, 0.4, 0.005),
        ModelPricingConfig("gpt-5", 1.25, 10.0, 0.125),
        ModelPricingConfig("gpt-4.1-mini", 0.4, 1.6, 0.1),
        ModelPricingConfig("gpt-4.1-nano", 0.1, 0.4, 0.025),
        ModelPricingConfig("gpt-4.1", 2.0, 8.0, 0.5),
        ModelPricingConfig("gpt-4o-mini", 0.15, 0.6, 0.075),
        ModelPricingConfig("gpt-4o", 2.5, 10.0, 1.25),
        ModelPricingConfig("o4-mini", 1.1, 4.4, 0.275),
        ModelPricingConfig("o3-mini", 1.1, 4.4, 0.55),
        ModelPricingConfig("o3", 2.0, 8.0, 0.5),

        // ---- Google Gemini ----
        ModelPricingConfig("gemini-3.6-flash", 1.5, 7.5, 0.15),
        ModelPricingConfig("gemini-3.5-flash-lite", 0.3, 2.5, 0.03),
        ModelPricingConfig("gemini-3.5-flash", 1.5, 9.0, 0.15),
        ModelPricingConfig("gemini-3.1-flash-lite", 0.25, 1.5, 0.025),
        ModelPricingConfig("gemini-3-flash-preview", 0.5, 3.0, 0.05),
        ModelPricingConfig("gemini-3.1-pro-preview", 2.0, 12.0, 0.2),
        ModelPricingConfig("gemini-2.5-pro", 1.25, 10.0, 0.125),
        ModelPricingConfig("gemini-2.5-flash-lite", 0.1, 0.4, 0.01),
        ModelPricingConfig("gemini-2.5-flash", 0.3, 2.5, 0.03),
        ModelPricingConfig("gemini", 1.25, 10.0, 0.125),

        // ---- DeepSeek ----
        // 2026-08-17 起分高峰/空闲计价；内置价按空闲时段，timeAware 开启后高峰自动 ×2。
        ModelPricingConfig(
            "deepseek-v4-flash",
            0.22, 0.66, 0.007,
            inputPriceCny = 1.5, outputPriceCny = 4.5, cachedInputPriceCny = 0.05,
            timeAware = true,
        ),
        ModelPricingConfig(
            "deepseek-v4-pro",
            0.66, 1.98, 0.022,
            inputPriceCny = 4.5, outputPriceCny = 13.5, cachedInputPriceCny = 0.15,
            timeAware = true,
        ),
        ModelPricingConfig(
            "deepseek",
            0.22, 0.66, 0.007,
            inputPriceCny = 1.5, outputPriceCny = 4.5, cachedInputPriceCny = 0.05,
            timeAware = true,
        ),
    )

    private const val DEFAULT_CNY_RATE = 7.2

    /** 用户覆盖优先（精确 modelId 匹配），其次内置预置（子串匹配，取第一个命中）。未命中返回 null。 */
    fun resolve(modelId: String?, overrides: List<ModelPricingConfig>): ModelPricingConfig? {
        if (modelId.isNullOrBlank()) return null
        overrides.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }?.let { return it }
        return PRESET_PRICING.firstOrNull { modelId.contains(it.modelId, ignoreCase = true) }
    }

    /** 单条 usage 折算为美元；未知模型返回 0.0。 */
    fun costUsd(
        modelId: String?,
        usage: TokenUsage?,
        overrides: List<ModelPricingConfig>,
        timeMillis: Long? = null,
    ): Double {
        if (usage == null) return 0.0
        val pricing = resolve(modelId, overrides) ?: return 0.0
        val factor = if (pricing.timeAware && isPeakTime(timeMillis)) 2.0 else 1.0
        val cached = usage.cachedTokens.toDouble()
        val uncachedInput = (usage.promptTokens.toDouble() - cached).coerceAtLeast(0.0)
        val output = usage.completionTokens.toDouble()
        val usd = (uncachedInput * pricing.inputPriceUsd +
            cached * pricing.cachedInputPriceUsd +
            output * pricing.outputPriceUsd) / 1_000_000.0
        return usd * pricing.multiplier * factor
    }

    /**
     * 缓存命中率 0..1；无输入 token 时返回 null（UI 显示 `-`）。
     *
     * 口径：命中「读缓存」的 token ÷ 全部输入中参与读取的部分。
     * 分母剔除 cacheWriteTokens（写缓存那次天然全 miss，如 Anthropic 首次写入 cache_creation，
     * 计入分母会把「读」的命中率系统性拉低）；OpenAI/Gemini 无写缓存字段，行为不变。
     */
    fun cacheHitRate(usages: List<TokenUsage?>): Double? {
        val cached = usages.sumOf { it?.cachedTokens ?: 0 }.toDouble()
        val input = usages.sumOf { it?.promptTokens ?: 0 }.toDouble()
        val write = usages.sumOf { it?.cacheWriteTokens ?: 0 }.toDouble()
        val readInput = input - write
        if (readInput <= 0) return null
        return (cached / readInput).coerceIn(0.0, 1.0)
    }

    /**
     * 单条 usage 折算为人民币。人民币单价优先取配置里的人民币字段（官方人民币价），
     * 为 0（官方未公布人民币价，如 Claude/OpenAI/Gemini）时按美元价 × 汇率换算。
     */
    fun costCny(
        modelId: String?,
        usage: TokenUsage?,
        overrides: List<ModelPricingConfig>,
        rate: Double = DEFAULT_CNY_RATE,
        timeMillis: Long? = null,
    ): Double {
        if (usage == null) return 0.0
        val pricing = resolve(modelId, overrides) ?: return 0.0
        val factor = if (pricing.timeAware && isPeakTime(timeMillis)) 2.0 else 1.0
        val effectiveRate = if (rate > 0) rate else DEFAULT_CNY_RATE
        val cached = usage.cachedTokens.toDouble()
        val uncachedInput = (usage.promptTokens.toDouble() - cached).coerceAtLeast(0.0)
        val output = usage.completionTokens.toDouble()
        val cny = (uncachedInput * cnyUnit(pricing.inputPriceCny, pricing.inputPriceUsd, effectiveRate) +
            cached * cnyUnit(pricing.cachedInputPriceCny, pricing.cachedInputPriceUsd, effectiveRate) +
            output * cnyUnit(pricing.outputPriceCny, pricing.outputPriceUsd, effectiveRate)) / 1_000_000.0
        return cny * pricing.multiplier * factor
    }

    /** 某模型在指定货币下的三个单价（输入 / 输出 / 缓存输入，每 1M），无人民币官方价时按汇率换算。 */
    fun unitPrices(
        modelId: String?,
        overrides: List<ModelPricingConfig>,
        currency: CostCurrency,
        rate: Double = DEFAULT_CNY_RATE,
    ): Triple<Double, Double, Double>? {
        val pricing = resolve(modelId, overrides) ?: return null
        val r = if (rate > 0) rate else DEFAULT_CNY_RATE
        return when (currency) {
            CostCurrency.USD -> Triple(pricing.inputPriceUsd, pricing.outputPriceUsd, pricing.cachedInputPriceUsd)
            CostCurrency.RMB -> Triple(
                cnyUnit(pricing.inputPriceCny, pricing.inputPriceUsd, r),
                cnyUnit(pricing.outputPriceCny, pricing.outputPriceUsd, r),
                cnyUnit(pricing.cachedInputPriceCny, pricing.cachedInputPriceUsd, r),
            )
        }
    }

    private fun cnyUnit(cny: Double, usd: Double, rate: Double): Double =
        if (cny > 0) cny else usd * rate

    fun formatUsd(cost: Double): String = "$${formatTrim(cost)}"

    /** 入参已是人民币金额，不再乘汇率。 */
    fun formatCny(costCny: Double): String = "¥${formatTrim(costCny)}"

    private fun formatTrim(value: Double): String {
        val s = "%.4f".format(value)
            .trimEnd('0')
            .trimEnd('.')
        return if (s == "-0") "0" else s
    }
}
