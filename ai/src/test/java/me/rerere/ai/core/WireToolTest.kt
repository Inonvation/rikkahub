package me.rerere.ai.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireToolTest {

    @Test
    fun `trimDescription truncates long and keeps short`() {
        assertEquals("abcdef", "abcdef".trimDescription(100))
        assertEquals("ab…", "abcdef".trimDescription(3))
        assertEquals("", "abc".trimDescription(0))
        assertEquals("", "".trimDescription(10))
        assertEquals("…", "abc".trimDescription(1))
    }

    @Test
    fun `trimmed truncates nested parameter descriptions but keeps shape`() {
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", JsonPrimitive("a".repeat(200)))
                    put("enum", buildJsonArray { add(JsonPrimitive("x")) })
                })
                put("nested", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("inner", buildJsonObject {
                            put("type", "string")
                            put("description", JsonPrimitive("b".repeat(200)))
                        })
                    })
                })
            },
            required = listOf("name"),
        )

        val trimmed = schema.trimmed(paramDescLimit = 10) as InputSchema.Obj

        assertEquals(listOf("name"), trimmed.required)

        val name = trimmed.properties["name"]!!.jsonObject
        val nameDesc = name["description"]!!.jsonPrimitive.content
        assertTrue(nameDesc.length <= 11) // 10 + ellipsis
        assertTrue(nameDesc.endsWith("…"))
        assertTrue(name["enum"]!!.jsonArray.isNotEmpty())

        val inner = trimmed.properties["nested"]!!.jsonObject
            .getValue("properties").jsonObject["inner"]!!.jsonObject
        val innerDesc = inner["description"]!!.jsonPrimitive.content
        assertTrue(innerDesc.length <= 11)
        assertTrue(innerDesc.endsWith("…"))
    }

    @Test
    fun `trimmed fills missing array items at any nesting depth`() {
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("translations", buildJsonObject {
                    put("type", "array")
                    put("description", "List of translation objects")
                })
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("nested", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("innerArray", buildJsonObject { put("type", "array") })
                    })
                })
            },
            required = emptyList(),
        )

        val trimmed = schema.trimmed() as InputSchema.Obj

        val translations = trimmed.properties["translations"]!!.jsonObject
        assertEquals("array", translations["type"]!!.jsonPrimitive.content)
        assertTrue(translations.containsKey("items"))
        assertEquals(0, translations["items"]!!.jsonObject.size)

        // 已有 items 的保持原样
        val tags = trimmed.properties["tags"]!!.jsonObject
        assertEquals("string", tags["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // 嵌套对象里的 array 同样补齐
        val innerArray = trimmed.properties["nested"]!!.jsonObject
            .getValue("properties").jsonObject["innerArray"]!!.jsonObject
        assertTrue(innerArray.containsKey("items"))
    }
}
