package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.rikkahub.data.db.dao.KnowledgeCardDao
import me.rerere.rikkahub.data.db.dao.NoteDao
import me.rerere.rikkahub.data.db.dao.VocabularyDao
import me.rerere.rikkahub.data.db.dao.WrongQuestionDao
import me.rerere.rikkahub.data.model.StudySubject

class StudyTools(
    private val vocabularyDao: VocabularyDao,
    private val wrongQuestionDao: WrongQuestionDao,
    private val knowledgeCardDao: KnowledgeCardDao,
    private val noteDao: NoteDao,
    private val knowledgeChunkDao: KnowledgeChunkDao,
) {
    fun getTools(
        enabledTools: List<String>,
        conversationId: String,
        assistantId: String,
        studySubject: String,
    ): List<Tool> = buildList {
        enabledTools.forEach { toolName ->
            val normalizedSubject = StudySubject.normalize(studySubject)
            when (toolName) {
                "save_vocabulary" -> add(
                    createVocabularyTool(conversationId, vocabularyDao)
                )
                "save_wrong_question" -> add(
                    createWrongQuestionTool(conversationId, wrongQuestionDao, normalizedSubject)
                )
                "save_knowledge_card" -> add(
                    createKnowledgeCardTool(conversationId, knowledgeCardDao, normalizedSubject)
                )
                "save_note" -> add(
                    createNoteTool(conversationId, assistantId, noteDao, normalizedSubject)
                )
                "quiz_user" -> add(
                    createQuizUserTool(knowledgeChunkDao)
                )
            }
        }
    }
}