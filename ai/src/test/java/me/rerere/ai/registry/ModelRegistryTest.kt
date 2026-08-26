package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun testGPT5() {
        assertTrue(ModelRegistry.GPT_5.match("gpt-5"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5-chat"))
        assertTrue(ModelRegistry.GPT_5.match("gpt-5-mini"))
        assertFalse(ModelRegistry.GPT_5.match("deepseek-v3"))
        assertFalse(ModelRegistry.GPT_5.match("gemini-2.0-flash"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.1"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-4o"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.0"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-6"))
    }

    @Test
    fun testGemini25() {
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest"))
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-pro-latest"))
        assertTrue(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-pro"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash-image-preview"))
        assertTrue(ModelRegistry.GEMINI_2_5_IMAGE.match("gemini-2.5-flash-image"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash")
        )
    }

    @Test
    fun testClaudeSeries() {
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4.5-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-3.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-5"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-opus-5"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("claude-sonnet-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("claude-opus-5")
        )
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-5"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-opus-5"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-5-20260305"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-4.5"))
    }

    @Test
    fun testDeepSeekResponsesSupported() {
        // DeepSeek 官方 Responses API 目前仅确认 deepseek-v4-flash 可用，
        // 其余模型应回退到 Chat Completions
        assertTrue(ModelRegistry.DEEPSEEK_RESPONSES.match("deepseek-v4-flash"))
        assertTrue(ModelRegistry.DEEPSEEK_RESPONSES.match("DeepSeek-V4-Flash"))
        assertFalse(ModelRegistry.DEEPSEEK_RESPONSES.match("deepseek-v4-pro"))
        assertFalse(ModelRegistry.DEEPSEEK_RESPONSES.match("deepseek-chat"))
        assertFalse(ModelRegistry.DEEPSEEK_RESPONSES.match("deepseek-reasoner"))
        assertFalse(ModelRegistry.DEEPSEEK_RESPONSES.match("deepseek-v3"))
        assertFalse(ModelRegistry.DEEPSEEK_RESPONSES.match("deepseek-v3.1"))
        assertFalse(ModelRegistry.DEEPSEEK_RESPONSES.match("gpt-5"))
    }

    @Test
    fun testSpecificityPriority() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2.5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2")
        )
    }

    @Test
    fun testOpenAIOModels() {
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o1"))
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o3-mini"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
    }

    @Test
    fun testGlm5AndMinimaxM25() {
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("minimax-m2.5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("glm-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("minimax-m2.5")
        )
    }

    @Test
    fun testDeepseekV4() {
        val reasonerAbilities = ModelRegistry.MODEL_ABILITIES.getData("deepseek-reasoner")
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash")
        )
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-pro")
        )
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-vision-exp")
        )
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash-vision-exp")
        )
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-flash"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-pro"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-flash-vision-exp"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v3"))
    }

    @Test
    fun testContextLengthOrDefault() {
        assertEquals(1_000_000, ModelRegistry.contextLengthOrDefault("deepseek-v4-flash"))
        assertEquals(128_000, ModelRegistry.contextLengthOrDefault("unknown-model-xyz"))
        assertEquals(
            256_000,
            Model(modelId = "deepseek-v4-flash", contextLength = 256_000).contextLengthOrDefault(),
        )
        assertEquals(128_000, Model(modelId = "unknown-model-xyz", contextLength = 0).contextLengthOrDefault())
    }

    @Test
    fun testContextLengthDefault() {
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("unknown-model-xyz"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("gpt-4o"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-4-sonnet"))
    }

    @Test
    fun testContextLengthDsl() {
        val model = defineModel {
            tokens("custom", "ctx")
            contextLength(1_000_000)
        }
        assertEquals(1_000_000, model.contextLength)

        val defaultModel = defineModel {
            tokens("custom", "default")
        }
        assertEquals(null, defaultModel.contextLength)
    }
}
