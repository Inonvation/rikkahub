package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user one or more questions when you need clarification, additional information, or confirmation.
        Each question has a type: text (free input with optional suggestion chips), single (pick exactly one option),
        multi (pick multiple options), or confirmation (yes/no).
        You can optionally provide a title for the question sheet and a rationale for each question to explain why you're asking.
        The answers will be returned as a JSON object mapping question IDs to the user's responses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional title shown at the top of the question sheet")
                })
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions to ask the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("rationale", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional explanation of why this question is being asked, shown as hint text below the question")
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                        add("confirmation")
                                    }
                                )
                                put(
                                    "description",
                                    "Answer type: text (free text input with optional suggestion chips, default), single (select exactly one option), multi (select one or more options), confirmation (yes/no)"
                                )
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional list of suggested options. For 'text' type, shown as quick-fill chips. For 'single'/'multi', the selectable choices."
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("placeholder", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional placeholder text for the text input field")
                            })
                            put("required", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Whether the user must answer this question before submitting. Defaults to true.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    systemPrompt = { _, _ ->
        "When you need clarification, confirmation, or additional information to give a correct answer, " +
            "ask the user with ask_user. Prefer asking a focused question over guessing."
    },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)
