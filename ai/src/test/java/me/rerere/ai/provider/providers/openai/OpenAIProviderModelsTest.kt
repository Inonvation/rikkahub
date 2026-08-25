package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAIProviderModelsTest {
    @Test
    fun `Codex subscription model URL includes client version`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
        )

        val url = openAIModelsUrl(setting)

        assertEquals("$OPENAI_CODEX_BASE_URL/models", url.toString().substringBefore('?'))
        assertEquals("0.148.0", url.queryParameter("client_version"))
    }

    @Test
    fun `API key model URL keeps standard shape`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.API_KEY,
            baseUrl = "https://api.openai.com/v1",
        )

        val url = openAIModelsUrl(setting)

        assertEquals("https://api.openai.com/v1/models", url.toString())
        assertNull(url.queryParameter("client_version"))
    }

    @Test
    fun `subscription model URL always targets official codex endpoint regardless of configured base url`() {
        // 内置第三方 Provider（如 RikkaHub，baseUrl=api.rikka-ai.com）切到订阅后，
        // 请求必须仍走官方 Codex 端点。
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = "https://api.rikka-ai.com/v1",
        )

        val url = openAIModelsUrl(setting)

        assertEquals("$OPENAI_CODEX_BASE_URL/models", url.toString().substringBefore('?'))
        assertEquals("0.148.0", url.queryParameter("client_version"))
    }

    @Test
    fun `Codex model response uses visible API supported slugs`() {
        val models = parseOpenAIModels(
            """
            {
              "models": [
                {
                  "slug": "gpt-5.6-luna",
                  "display_name": "GPT-5.6 Luna",
                  "supported_in_api": true,
                  "visibility": "list"
                },
                {
                  "slug": "hidden-model",
                  "supported_in_api": true,
                  "visibility": "hide"
                },
                {
                  "slug": "unsupported-model",
                  "supported_in_api": false,
                  "visibility": "list"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, models.size)
        assertEquals("gpt-5.6-luna", models.single().modelId)
        assertEquals("GPT-5.6 Luna", models.single().displayName)
    }

    @Test
    fun `standard OpenAI data array still parses with embedding detection`() {
        val models = parseOpenAIModels(
            """
            {
              "data": [
                {"id": "gpt-4o", "object": "model"},
                {"id": "text-embedding-3-small", "object": "model"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, models.size)
        assertEquals("gpt-4o", models[0].modelId)
        assertEquals("text-embedding-3-small", models[1].modelId)
        assertEquals(me.rerere.ai.provider.ModelType.EMBEDDING, models[1].type)
    }
}
