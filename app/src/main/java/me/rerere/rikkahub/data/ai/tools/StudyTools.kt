package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.rikkahub.data.model.StudySubject

class StudyTools(
    private val studyDaoSet: StudyDaoSet,
    private val knowledgeChunkDao: KnowledgeChunkDao,
) {
    fun getTools(
        enabledTools: List<String>,
        conversationId: String,
        assistantId: String,
        studySubject: String,
        permissions: StudyToolPermissions = StudyToolPermissions(),
    ): List<Tool> {
        if (enabledTools.isEmpty()) return emptyList()
        return buildList {
            // 学科隔离：能力工具（search/update/delete/stats/read）只能接触助手配置学科的内容；
            // 未配置学科（studySubject 空白）则不约束。save_* 新增时用助手学科作为默认 subject。
            val subjectScope = studySubject.trim().let { if (it.isBlank()) null else StudySubject.normalize(it) }
            enabledTools.forEach { toolName ->
                val normalizedSubject = StudySubject.normalize(studySubject)
                when (toolName) {
                    "save_vocabulary" -> add(
                        createVocabularyTool(conversationId, studyDaoSet.vocabularyDao)
                    )
                    "save_wrong_question" -> add(
                        createWrongQuestionTool(conversationId, studyDaoSet.wrongQuestionDao, normalizedSubject)
                    )
                    "save_knowledge_card" -> add(
                        createKnowledgeCardTool(conversationId, studyDaoSet.knowledgeCardDao, normalizedSubject)
                    )
                    "save_note" -> add(
                        createNoteTool(conversationId, assistantId, studyDaoSet.noteDao, normalizedSubject)
                    )
                    "quiz_user" -> add(
                        createQuizUserTool(knowledgeChunkDao)
                    )
                }
            }
            // study_search 始终可用（只读），供 update/delete 定位目标
            add(createStudySearchTool(studyDaoSet, subjectScope))
            if (permissions.editEnabled) {
                add(createUpdateVocabularyTool(studyDaoSet, permissions, subjectScope))
                add(createUpdateNoteTool(studyDaoSet, permissions, subjectScope))
                add(createUpdateWrongQuestionTool(studyDaoSet, permissions, subjectScope))
                add(createUpdateKnowledgeCardTool(studyDaoSet, permissions, subjectScope))
            }
            if (permissions.deleteEnabled) {
                add(createDeleteVocabularyTool(studyDaoSet, permissions, subjectScope))
                add(createDeleteNoteTool(studyDaoSet, permissions, subjectScope))
                add(createDeleteWrongQuestionTool(studyDaoSet, permissions, subjectScope))
                add(createDeleteKnowledgeCardTool(studyDaoSet, permissions, subjectScope))
            }
            if (permissions.statsEnabled) {
                addAll(createStudyStatsTools(studyDaoSet, permissions, subjectScope))
            }
            // 读取学习内容（问题5）
            add(createStudyReadTool(studyDaoSet, subjectScope))
        }
    }
}
