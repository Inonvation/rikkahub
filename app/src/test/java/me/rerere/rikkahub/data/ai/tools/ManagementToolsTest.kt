package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ManagementToolsTest {

    @Test
    fun updateProviderModelsReplacesFullList() {
        val current = listOf(
            Model(id = Uuid.random(), modelId = "a"),
            Model(id = Uuid.random(), modelId = "b"),
        )

        val updated = updateProviderModels(
            current = current,
            full = listOf("c", "d"),
            add = null,
            remove = null,
        )

        assertEquals(listOf("c", "d"), updated.map { it.modelId })
    }

    @Test
    fun updateProviderModelsAppliesAddAndRemove() {
        val current = listOf(
            Model(id = Uuid.random(), modelId = "a"),
            Model(id = Uuid.random(), modelId = "b"),
        )

        val updated = updateProviderModels(
            current = current,
            full = listOf("a", "c"),
            add = listOf("b"),
            remove = listOf("c"),
        )

        assertEquals(listOf("a", "b"), updated.map { it.modelId })
    }

    @Test
    fun redactSearchJsonMasksSecretButKeepsOtherFields() {
        val json = buildJsonObject {
            put("apiKey", JsonPrimitive("secret"))
            put("url", JsonPrimitive("https://example.com"))
        }

        val redacted = redactSearchJson(json)

        assertEquals("***", redacted["apiKey"]?.jsonPrimitive?.content)
        assertEquals("https://example.com", redacted["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun decodeSearchOptionsBuildsRequestedType() {
        val options = decodeSearchOptions(
            type = "tavily",
            config = buildJsonObject {
                put("apiKey", JsonPrimitive("test-key"))
                put("depth", JsonPrimitive("standard"))
            },
        )

        assertTrue(options is SearchServiceOptions.TavilyOptions)
        options as SearchServiceOptions.TavilyOptions
        assertEquals("test-key", options.apiKey)
        assertEquals("standard", options.depth)
    }

    @Test
    fun isValidModeRefAcceptsBuiltinCustomAndNull() {
        val settings = Settings(
            init = true,
            customModes = listOf(CustomModeConfig(id = "custom-1")),
        )

        assertTrue(isValidModeRef(ChatMode.STANDARD.name, settings))
        assertTrue(isValidModeRef(ModeRefs.custom("custom-1"), settings))
        assertTrue(isValidModeRef(null, settings))
        assertFalse(isValidModeRef("not-a-mode", settings))
    }
}
