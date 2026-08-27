package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * memory_tool 分类参数的容错行为：分类是组织性元数据，
 * 任何非规范取值都不应阻断记忆的创建/更新。
 */
class MemoryToolsTest {

    private val captured = mutableListOf<MemoryCategory?>()

    private fun newTool(): Tool = buildMemoryTools(
        json = Json,
        onCreation = { content, category ->
            captured.add(category)
            AssistantMemory(7, content, category, 1L, 1L)
        },
        onUpdate = { id, content, category ->
            captured.add(category)
            AssistantMemory(id, content, category, 1L, 2L)
        },
        onDelete = {},
    ).single()

    private suspend fun executeCreate(categoryRaw: String?): JsonObject {
        val args = if (categoryRaw == null) {
            """{"action":"create","content":"fact"}"""
        } else {
            """{"action":"create","content":"fact","category":$categoryRaw}"""
        }
        val result = newTool().execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text
        return Json.parseToJsonElement(result.text).jsonObject
    }

    @Test
    fun `explicit json null category does not block creation`() = runBlocking {
        // 回归：模型传 {"category": null} 曾误判为非法值导致写入失败
        val payload = executeCreate("null")
        assertEquals("7", payload["id"]!!.jsonPrimitive.content)
        assertTrue(captured.single() == null)
    }

    @Test
    fun `missing category stays legacy null`() = runBlocking {
        executeCreate(null)
        assertTrue(captured.single() == null)
    }

    @Test
    fun `case and whitespace normalized`() = runBlocking {
        executeCreate("\"WORK\"")
        assertEquals(MemoryCategory.WORK, captured[0])
        executeCreate("\" goal \"")
        assertEquals(MemoryCategory.GOAL, captured[1])
    }

    @Test
    fun `unknown value degrades to OTHER instead of error`() = runBlocking {
        val payload = executeCreate("\"hobbies-and-music\"")
        assertEquals("7", payload["id"]!!.jsonPrimitive.content)
        assertEquals(MemoryCategory.OTHER, captured.single())
    }

    @Test
    fun `non-string primitive degrades to OTHER`() = runBlocking {
        executeCreate("123")
        assertEquals(MemoryCategory.OTHER, captured.single())
    }
}
