package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `display messages skip compressed summary and append new assistant reply`() {
        val oldUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("old")),
        )
        val summary = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("[Summary]")),
        )
        val newUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("new")),
        )
        val assistantReply = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
        )

        val result = displayMessagesForChunk(
            displayMessages = listOf(oldUser, newUser),
            chunkMessages = listOf(summary, oldUser, newUser, assistantReply),
        )

        assertEquals(listOf(oldUser, newUser, assistantReply), result)
    }

    @Test
    fun `display messages update existing message by id`() {
        val id = Uuid.random()
        val before = UIMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("partial")),
        )
        val after = before.copy(parts = listOf(UIMessagePart.Text("partial done")))

        val result = displayMessagesForChunk(
            displayMessages = listOf(before),
            chunkMessages = listOf(after),
        )

        assertEquals(listOf(after), result)
    }
}
