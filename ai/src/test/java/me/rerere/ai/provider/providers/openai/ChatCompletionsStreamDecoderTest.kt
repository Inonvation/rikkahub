package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.util.json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsStreamDecoderTest {
    @Test
    fun `deepseek final chunk with null optional fields should not fail`() {
        val decoder = ChatCompletionsStreamDecoder()
        val result = decoder.accept(
            SseEvent(
                data = json.encodeToString(
                    buildJsonObject {
                        put("id", "chatcmpl-test")
                        put("object", "chat.completion.chunk")
                        put("model", "deepseek-v4-flash")
                        put(
                            "choices",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("index", 0)
                                        put("finish_reason", JsonNull)
                                        put(
                                            "delta",
                                            buildJsonObject {
                                                put("role", JsonNull)
                                                put("content", "hello")
                                                put("reasoning_content", JsonNull)
                                                put("tool_calls", JsonNull)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
        )

        assertFalse(result.completed)
        assertTrue(result.chunks.isNotEmpty())
        assertTrue(decoder.accept(SseEvent(data = "[DONE]")).completed)
    }
}
