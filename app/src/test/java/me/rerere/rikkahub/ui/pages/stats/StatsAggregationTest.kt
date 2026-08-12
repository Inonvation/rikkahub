package me.rerere.rikkahub.ui.pages.stats

import me.rerere.rikkahub.data.db.dao.DayModelUsage
import me.rerere.rikkahub.data.db.dao.ModelUsageEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsAggregationTest {

    private val names = mapOf(
        "uuid-gpt-openai" to "GPT-4o",
        "uuid-gpt-moonshot" to "GPT-4o",
        "uuid-gpt-case" to "gpt-4o",
        "uuid-ds" to "DeepSeek-V3",
    )

    @Test
    fun `model usage merges same display name from different providers`() {
        val entries = listOf(
            ModelUsageEntry(modelId = "uuid-gpt-openai", count = 3, tokens = 100),
            ModelUsageEntry(modelId = "uuid-gpt-moonshot", count = 5, tokens = 200),
            ModelUsageEntry(modelId = "uuid-ds", count = 2, tokens = 50),
        )
        val merged = mergeModelUsageByDisplayName(entries, names)

        assertEquals(2, merged.size)
        val gpt = merged.first { it.modelId == "GPT-4o" }
        assertEquals(8, gpt.count)
        assertEquals(300L, gpt.tokens)
    }

    @Test
    fun `model usage merges display names differing only in case`() {
        val entries = listOf(
            ModelUsageEntry(modelId = "uuid-gpt-openai", count = 2, tokens = 60),
            ModelUsageEntry(modelId = "uuid-gpt-case", count = 4, tokens = 120),
        )
        val merged = mergeModelUsageByDisplayName(entries, names)

        assertEquals(1, merged.size)
        assertEquals(6, merged.first().count)
        assertEquals(180L, merged.first().tokens)
    }

    @Test
    fun `model usage merges display names with surrounding whitespace`() {
        val spaced = mapOf("uuid-a" to " GPT-4o ", "uuid-b" to "gpt-4o")
        val entries = listOf(
            ModelUsageEntry(modelId = "uuid-a", count = 1, tokens = 10),
            ModelUsageEntry(modelId = "uuid-b", count = 2, tokens = 20),
        )
        val merged = mergeModelUsageByDisplayName(entries, spaced)

        assertEquals(1, merged.size)
        assertEquals(3, merged.first().count)
        assertEquals(30L, merged.first().tokens)
    }

    @Test
    fun `model usage keeps first display name spelling`() {
        val entries = listOf(
            ModelUsageEntry(modelId = "uuid-gpt-case", count = 4, tokens = 120),
            ModelUsageEntry(modelId = "uuid-gpt-openai", count = 2, tokens = 60),
        )
        val merged = mergeModelUsageByDisplayName(entries, names)

        assertEquals(1, merged.size)
        assertEquals("gpt-4o", merged.first().modelId)
    }

    @Test
    fun `model usage keeps unknown ids for others fallback`() {
        val entries = listOf(
            ModelUsageEntry(modelId = "uuid-gpt-openai", count = 2, tokens = 60),
            ModelUsageEntry(modelId = "uuid-unknown-legacy", count = 4, tokens = 120),
        )
        val merged = mergeModelUsageByDisplayName(entries, names)

        assertEquals(2, merged.size)
        val unknown = merged.first { it.modelId == "uuid-unknown-legacy" }
        assertEquals(4, unknown.count)
        assertEquals(120L, unknown.tokens)
    }

    @Test
    fun `model usage keeps empty id for inherited main model`() {
        val entries = listOf(
            ModelUsageEntry(modelId = "uuid-gpt-openai", count = 2, tokens = 60),
            ModelUsageEntry(modelId = "", count = 3, tokens = 90),
        )
        val merged = mergeModelUsageByDisplayName(entries, names)

        assertEquals(2, merged.size)
        val empty = merged.first { it.modelId.isEmpty() }
        assertEquals(3, empty.count)
        assertEquals(90L, empty.tokens)
    }

    @Test
    fun `model usage sorts merged entries by count descending`() {
        val entries = listOf(
            ModelUsageEntry(modelId = "uuid-ds", count = 1, tokens = 10),
            ModelUsageEntry(modelId = "uuid-gpt-openai", count = 9, tokens = 500),
            ModelUsageEntry(modelId = "uuid-gpt-moonshot", count = 7, tokens = 300),
        )
        val merged = mergeModelUsageByDisplayName(entries, names)

        assertEquals(listOf("GPT-4o", "DeepSeek-V3"), merged.map { it.modelId })
        assertEquals(16, merged.first().count)
        assertEquals(800L, merged.first().tokens)
    }

    @Test
    fun `trend merges same display name within same day`() {
        val entries = listOf(
            DayModelUsage(day = "2026-08-01", modelId = "uuid-gpt-openai", count = 1, tokens = 10),
            DayModelUsage(day = "2026-08-01", modelId = "uuid-gpt-moonshot", count = 2, tokens = 20),
            DayModelUsage(day = "2026-08-02", modelId = "uuid-gpt-openai", count = 3, tokens = 30),
        )
        val merged = mergeTrendByDisplayName(entries, names)

        assertEquals(2, merged.size)
        val firstDay = merged.first { it.day == "2026-08-01" }
        assertEquals("GPT-4o", firstDay.modelId)
        assertEquals(3, firstDay.count)
        assertEquals(30L, firstDay.tokens)
    }

    @Test
    fun `trend unifies display name spelling across days`() {
        val entries = listOf(
            DayModelUsage(day = "2026-08-01", modelId = "uuid-gpt-openai", count = 1, tokens = 10),
            DayModelUsage(day = "2026-08-02", modelId = "uuid-gpt-case", count = 2, tokens = 20),
        )
        val merged = mergeTrendByDisplayName(entries, names)

        assertEquals(2, merged.size)
        assertEquals(setOf("GPT-4o", "GPT-4o"), merged.map { it.modelId }.toSet())
        assertEquals("GPT-4o", merged.first { it.day == "2026-08-02" }.modelId)
    }
}
