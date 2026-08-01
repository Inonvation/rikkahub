package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.VocabularyDao
import me.rerere.rikkahub.data.db.entity.VocabularyEntity
import kotlin.uuid.Uuid

fun createVocabularyTool(
    conversationId: String,
    vocabularyDao: VocabularyDao,
): Tool = Tool(
    name = "save_vocabulary",
    description = """
        Save a word with its translations, examples, and mnemonic to the user's vocabulary panel for later review.
        Call this after explaining a word to the user.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("word", buildJsonObject {
                    put("type", "string")
                    put("description", "The word being saved")
                })
                put("pronunciation", buildJsonObject {
                    put("type", "string")
                    put("description", "IPA pronunciation")
                })
                put("translations", buildJsonObject {
                    put("type", "array")
                    put("description", "List of translation objects with 'pos' (part of speech) and 'definition' fields")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("pos", buildJsonObject {
                                put("type", "string")
                                put("description", "Part of speech (n., v., adj., adv.)")
                            })
                            put("definition", buildJsonObject {
                                put("type", "string")
                                put("description", "The translated definition in Chinese")
                            })
                        })
                    })
                })
                put("examples", buildJsonObject {
                    put("type", "array")
                    put("description", "List of example objects with 'en' and 'zh' fields")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("en", buildJsonObject {
                                put("type", "string")
                                put("description", "English example sentence")
                            })
                            put("zh", buildJsonObject {
                                put("type", "string")
                                put("description", "Chinese translation of the example")
                            })
                        })
                    })
                })
                put("mnemonic", buildJsonObject {
                    put("type", "string")
                    put("description", "Memory aid or mnemonic in Chinese")
                })
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Tags like 考研高频, 熟词僻义, 写作词汇")
                })
            },
            required = listOf("word", "translations")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val word = params["word"]?.jsonPrimitive?.contentOrNull ?: error("word is required")

        // 查重：如果已存在则拒绝保存
        val existingCount = vocabularyDao.countByWord(word)
        if (existingCount > 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("duplicate", true)
                        put("word", JsonPrimitive(word))
                        put("message", JsonPrimitive("「$word」已在生词本中，请去生词面板复习，无需重复保存"))
                    }.toString()
                )
            )
        }

        val pronunciation = params["pronunciation"]?.jsonPrimitive?.contentOrNull ?: ""
        val translations = params["translations"]?.jsonArray?.toString() ?: "[]"
        val examples = params["examples"]?.jsonArray?.toString() ?: "[]"
        val mnemonic = params["mnemonic"]?.jsonPrimitive?.contentOrNull ?: ""
        val tags = params["tags"]?.jsonArray?.toString() ?: "[]"

        val entity = VocabularyEntity(
            id = Uuid.random().toString(),
            word = word,
            pronunciation = pronunciation,
            translations = translations,
            examples = examples,
            mnemonic = mnemonic,
            tags = tags,
            sourceConversationId = conversationId,
        )
        vocabularyDao.insert(entity)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("saved", true)
                    put("word", JsonPrimitive(word))
                    put("message", JsonPrimitive("已保存到生词本"))
                }.toString()
            )
        )
    }
)