package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun parseModelTypeMapsKnownAndRejectsUnknown() {
        assertEquals(ModelType.CHAT, parseModelType("chat"))
        assertEquals(ModelType.IMAGE, parseModelType("IMAGE"))
        assertEquals(ModelType.EMBEDDING, parseModelType("embedding"))
        assertEquals(ModelType.RERANKING, parseModelType("reranking"))
        assertNull(parseModelType("bogus"))
    }

    @Test
    fun parseAbilitiesMapsToolAndReasoning() {
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            parseAbilities(listOf("tool", "REASONING")),
        )
        assertEquals(emptyList<ModelAbility>(), parseAbilities(emptyList()))
        assertNull(parseAbilities(null))
    }

    @Test
    fun parseModalitiesMapsTextAndImage() {
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), parseModalities(listOf("text", "image")))
        assertNull(parseModalities(null))
    }

    @Test
    fun parseBuiltInToolsMapsConfigNames() {
        assertEquals(
            setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration),
            parseBuiltInTools(listOf("search", "image_generation", "unknown")),
        )
        assertEquals("url_context", BuiltInTools.UrlContext.configName())
        // 非法值被忽略（与 abilities/modalities 解析语义一致）
        assertEquals(emptySet<BuiltInTools>(), parseBuiltInTools(listOf("bogus")))
    }

    @Test
    fun parseBalanceReadsObjectAndRejectsInvalid() {
        val obj = buildJsonObject {
            put(
                "balance",
                buildJsonObject {
                    put("enabled", JsonPrimitive(true))
                    put("apiPath", JsonPrimitive("/credits"))
                    put("resultPath", JsonPrimitive("data.total_usage"))
                }
            )
        }
        val balance = parseBalance(obj["balance"])
        assertNotNull(balance)
        assertEquals(true, balance?.enabled)
        assertEquals("/credits", balance?.apiPath)
        assertEquals("data.total_usage", balance?.resultPath)

        // 非对象 / 缺失 → null（保持现值）
        assertNull(parseBalance(buildJsonObject { put("x", JsonPrimitive(1)) }["x"]))
        assertNull(parseBalance(buildJsonObject { }["missing"]))
    }

    @Test
    fun parsePromptCacheTtlMapsApiValues() {
        assertEquals(ClaudePromptCacheTtl.FIVE_MINUTES, parsePromptCacheTtl("5m"))
        assertEquals(ClaudePromptCacheTtl.ONE_HOUR, parsePromptCacheTtl("1h"))
        assertEquals(ClaudePromptCacheTtl.ONE_HOUR, parsePromptCacheTtl("ONE_HOUR"))
        assertNull(parsePromptCacheTtl("10m"))
        assertNull(parsePromptCacheTtl(null))
    }

    @Test
    fun inferModelBasicConfigDetectsChatVisionReasoning() {
        // gpt-4o-vision：chat + 工具 + 图片输入
        val vision = inferModelBasicConfig("gpt-4o-vision")
        assertEquals(ModelType.CHAT, vision.type)
        assertTrue(vision.abilities.contains(ModelAbility.TOOL))
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), vision.inputModalities)
        assertEquals(listOf(Modality.TEXT), vision.outputModalities)

        // o1：chat + 工具 + 推理
        val reasoning = inferModelBasicConfig("o1-mini")
        assertEquals(ModelType.CHAT, reasoning.type)
        assertTrue(reasoning.abilities.contains(ModelAbility.REASONING))
        assertEquals(listOf(Modality.TEXT), reasoning.inputModalities)
    }

    @Test
    fun inferModelBasicConfigDetectsSpecializedTypes() {
        val embedding = inferModelBasicConfig("text-embedding-3-large")
        assertEquals(ModelType.EMBEDDING, embedding.type)
        assertTrue(embedding.abilities.isEmpty())

        val image = inferModelBasicConfig("dall-e-3")
        assertEquals(ModelType.IMAGE, image.type)
        assertEquals(listOf(Modality.IMAGE), image.outputModalities)

        val rerank = inferModelBasicConfig("bge-reranker-v2")
        assertEquals(ModelType.RERANKING, rerank.type)
    }

    @Test
    fun parseCustomHeadersReadsObjectArray() {
        val scalar = buildJsonObject {
            put("customHeaders", JsonPrimitive("not-an-array"))
        }
        assertNull(parseCustomHeaders(scalar["customHeaders"]))

        val withHeaders = buildJsonObject {
            put(
                "headers",
                kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("name", JsonPrimitive("Authorization"))
                        put("value", JsonPrimitive("Bearer x"))
                    })
                    add(buildJsonObject {
                        put("name", JsonPrimitive("X-Test"))
                        put("value", JsonPrimitive("v1"))
                    })
                }
            )
        }
        val headers = parseCustomHeaders(withHeaders["headers"])
        assertEquals(
            listOf(CustomHeader("Authorization", "Bearer x"), CustomHeader("X-Test", "v1")),
            headers,
        )
        assertNull(parseCustomHeaders(null))
    }
}
