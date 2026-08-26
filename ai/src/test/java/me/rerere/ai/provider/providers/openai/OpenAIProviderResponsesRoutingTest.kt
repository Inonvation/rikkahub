package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 DeepSeek 模型 + responsesAPI 开关下的 /responses 路由决策。
 *
 * 背景：提示词优化、标题生成、建议、压缩、翻译等后台场景使用非流式 generateText，
 * 且第三方 OpenAI 兼容中继大多不实现 /responses。这些场景必须回退到 Chat Completions，
 * 否则在「提示词优化模型 = DeepSeek + 开启 responsesAPI」时会一直报优化失败。
 */
class OpenAIProviderResponsesRoutingTest {

    private val provider = OpenAIProvider(OkHttpClient())

    private fun model(modelId: String) = Model(modelId = modelId, displayName = modelId)

    private fun deepSeekSetting(
        baseUrl: String = "https://api.deepseek.com/v1",
        useResponseApi: Boolean = true,
    ) = ProviderSetting.OpenAI(baseUrl = baseUrl, useResponseApi = useResponseApi)

    @Test
    fun `deepseek chat on official host falls back to chat completions`() {
        // deepseek-chat 不在 /responses 支持名单，即使流式也不走 /responses
        assertFalse(provider.useResponsesApiFor(deepSeekSetting(), model("deepseek-chat"), stream = true))
        assertFalse(provider.useResponsesApiFor(deepSeekSetting(), model("deepseek-chat"), stream = false))
    }

    @Test
    fun `deepseek reasoner on official host falls back to chat completions`() {
        assertFalse(provider.useResponsesApiFor(deepSeekSetting(), model("deepseek-reasoner"), stream = true))
    }

    @Test
    fun `deepseek v4 pro on official host falls back to chat completions`() {
        assertFalse(provider.useResponsesApiFor(deepSeekSetting(), model("deepseek-v4-pro"), stream = true))
    }

    @Test
    fun `deepseek v4 flash streaming on official host uses responses api`() {
        assertTrue(provider.useResponsesApiFor(deepSeekSetting(), model("deepseek-v4-flash"), stream = true))
    }

    @Test
    fun `deepseek v4 flash non streaming on official host falls back to chat completions`() {
        // 提示词优化等后台场景是非流式调用，不走 /responses
        assertFalse(provider.useResponsesApiFor(deepSeekSetting(), model("deepseek-v4-flash"), stream = false))
    }

    @Test
    fun `deepseek model on third party relay falls back to chat completions`() {
        val relay = deepSeekSetting(baseUrl = "https://relay.example.com/v1")
        // 中继即使流式也不走 /responses：中继大多只实现 Chat Completions
        assertFalse(provider.useResponsesApiFor(relay, model("deepseek-chat"), stream = true))
        assertFalse(provider.useResponsesApiFor(relay, model("deepseek-v4-flash"), stream = true))
    }

    @Test
    fun `deepseek model with scheme less base url falls back to chat completions`() {
        // baseUrl 缺 scheme 时 host 解析为 null，必须回退而不是误走 /responses
        assertFalse(provider.useResponsesApiFor(deepSeekSetting(baseUrl = "api.deepseek.com/v1"), model("deepseek-chat"), stream = true))
        assertFalse(provider.useResponsesApiFor(deepSeekSetting(baseUrl = "api.deepseek.com/v1"), model("deepseek-v4-flash"), stream = true))
    }

    @Test
    fun `responses api disabled never uses responses api`() {
        val setting = deepSeekSetting(useResponseApi = false)
        assertFalse(provider.useResponsesApiFor(setting, model("deepseek-v4-flash"), stream = true))
        assertFalse(provider.useResponsesApiFor(setting, model("gpt-5"), stream = true))
    }

    @Test
    fun `non deepseek model keeps responses api when enabled`() {
        assertTrue(provider.useResponsesApiFor(deepSeekSetting(), model("gpt-5"), stream = false))
        assertTrue(provider.useResponsesApiFor(deepSeekSetting(), model("gpt-5"), stream = true))
    }

    @Test
    fun `chatgpt subscription always uses responses api`() {
        val setting = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
        )
        assertTrue(provider.useResponsesApiFor(setting, model("deepseek-chat"), stream = true))
    }
}
