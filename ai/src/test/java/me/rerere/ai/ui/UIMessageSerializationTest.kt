package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class UIMessageSerializationTest {

    @Test
    fun `synthetic marker is not serialized`() {
        val message = UIMessage.user("internal").copy(isSynthetic = true)

        val encoded = Json.encodeToString(message)
        val decoded = Json.decodeFromString<UIMessage>(encoded)

        assertTrue(message.isSynthetic)
        assertFalse(encoded.contains("isSynthetic"))
        assertFalse(decoded.isSynthetic)
    }

    @Test
    fun `modelName round trips through serialization`() {
        val message = UIMessage.user("hi").copy(
            modelId = Uuid.random(),
            modelName = "Test Model",
        )

        val decoded = Json.decodeFromString<UIMessage>(Json.encodeToString(message))

        assertEquals("Test Model", decoded.modelName)
        assertEquals(message.modelId, decoded.modelId)
    }

    @Test
    fun `legacy json without modelName decodes with null default`() {
        val legacy = Json.decodeFromString<UIMessage>(
            """{"role":"user","parts":[]}"""
        )

        assertNull(legacy.modelName)
        assertNull(legacy.modelId)
    }
}
