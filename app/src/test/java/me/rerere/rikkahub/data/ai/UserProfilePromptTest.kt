package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryCategory
import me.rerere.rikkahub.data.model.ResponseTonePreset
import me.rerere.rikkahub.data.model.UserProfileSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfilePromptTest {

    @Test
    fun `disabled profile returns null`() {
        val prompt = buildUserProfilePrompt(
            profile = UserProfileSetting(enabled = false, occupation = "Engineer"),
            nickname = "A",
        )
        assertNull(prompt)
    }

    @Test
    fun `enabled but empty content returns null`() {
        assertNull(
            buildUserProfilePrompt(
                profile = UserProfileSetting(enabled = true),
                nickname = "",
            )
        )
    }

    @Test
    fun `nickname alone triggers injection`() {
        val prompt = buildUserProfilePrompt(
            profile = UserProfileSetting(enabled = true),
            nickname = "小明",
        )!!
        assertTrue(prompt.contains("**User Profile**"))
        assertTrue(prompt.contains("小明"))
        assertFalse(prompt.contains("**Response Style**"))
    }

    @Test
    fun `all fields rendered`() {
        val prompt = buildUserProfilePrompt(
            profile = UserProfileSetting(
                enabled = true,
                occupation = "Android Engineer",
                language = "中文",
                additionalInfo = "Likes concise commit messages",
            ),
            nickname = "Neo",
        )!!
        // 空白无关断言：kotlinx pretty print 的分隔符格式随库版本可能变化
        assertTrue(prompt.contains(Regex("\"name\"\\s*:\\s*\"Neo\"")))
        assertTrue(prompt.contains(Regex("\"occupation\"\\s*:\\s*\"Android Engineer\"")))
        assertTrue(prompt.contains(Regex("\"language_preference\"\\s*:\\s*\"中文\"")))
        assertTrue(prompt.contains("Likes concise commit messages"))
    }

    @Test
    fun `tone preset injects response style`() {
        val prompt = buildUserProfilePrompt(
            profile = UserProfileSetting(enabled = true, tonePreset = ResponseTonePreset.CONCISE),
            nickname = "",
        )!!
        assertTrue(prompt.contains("**Response Style**"))
        assertTrue(prompt.contains("Concise"))
    }

    @Test
    fun `custom tone with blank text is skipped`() {
        val prompt = buildUserProfilePrompt(
            profile = UserProfileSetting(
                enabled = true,
                occupation = "Dev",
                tonePreset = ResponseTonePreset.CUSTOM,
                toneCustom = "   ",
            ),
            nickname = "",
        )!!
        assertFalse(prompt.contains("**Response Style**"))
    }

    @Test
    fun `follow assistant preset skips style section`() {
        val prompt = buildUserProfilePrompt(
            profile = UserProfileSetting(enabled = true, tonePreset = ResponseTonePreset.FOLLOW_ASSISTANT),
            nickname = "N",
        )!!
        assertFalse(prompt.contains("**Response Style**"))
    }
}

class MemoryContextBlockTest {

    private fun memory(id: Int, content: String, updated: Long, category: MemoryCategory? = null) =
        AssistantMemory(id = id, content = content, category = category, createdAt = updated, updatedAt = updated)

    @Test
    fun `empty memories produce empty block`() {
        assertEquals("", buildMemoryContextBlock(emptyList()))
    }

    @Test
    fun `block wrapped in memories tag with instruction line`() {
        val block = buildMemoryContextBlock(listOf(memory(1, "User likes tea", 100)))
        assertTrue(block.startsWith("<memories>"))
        assertTrue(block.trimEnd().endsWith("</memories>"))
        assertTrue(block.contains("Relevant long-term memories"))
        assertTrue(block.contains("User likes tea"))
    }

    @Test
    fun `category name included when set`() {
        val block = buildMemoryContextBlock(listOf(memory(1, "goal fact", 100, MemoryCategory.GOAL)))
        assertTrue(block.contains("\"category\": \"GOAL\""))
        val noCategory = buildMemoryContextBlock(listOf(memory(1, "legacy", 100)))
        assertFalse(noCategory.contains("category"))
    }

    @Test
    fun `entries capped and newest preferred over oldest`() {
        val memories = (1..30).map { i -> memory(id = i, content = "fact $i", updated = i.toLong()) }
        val block = buildMemoryContextBlock(memories)
        // 上限 24 条：最旧的 6 条（id 1..6）应被裁掉，最新的保留
        assertFalse(block.contains("fact 1\""))
        assertTrue(block.contains("fact 30"))
        assertTrue(block.contains("fact 7"))
    }

    @Test
    fun `char budget trims oldest entries`() {
        // 标记前缀置于内容开头，避免被 MAX_MEMORY_ENTRY_CHARS 截断抹掉；
        // 单条成本 ~550 字符，预算 2000 → 保留最新 3 条
        val memories = (1..10).map { i ->
            memory(id = i, content = "[fact-$i]" + "x".repeat(520), updated = i.toLong())
        }
        val block = buildMemoryContextBlock(memories)
        assertTrue(block.contains("[fact-10]")) // 最新条目必然保留
        assertFalse(block.contains("[fact-7]")) // 第 4 新的条目超预算
        assertFalse(block.contains("[fact-5]")) // 更旧条目被裁掉
    }
}
