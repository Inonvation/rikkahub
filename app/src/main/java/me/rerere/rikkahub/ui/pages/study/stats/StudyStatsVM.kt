package me.rerere.rikkahub.ui.pages.study.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.StatsParams
import me.rerere.rikkahub.data.ai.tools.StudyDaoSet
import me.rerere.rikkahub.data.ai.tools.collectStudyItems
import me.rerere.rikkahub.data.model.StudySubject
import java.time.LocalDate
import java.time.ZoneId

data class TypeCount(val code: String, val label: String, val count: Int)

data class SubjectCount(val subjectCode: String, val total: Int)

data class DayCount(val date: LocalDate, val total: Int)

data class StudyStatsUiState(
    val isLoading: Boolean = true,
    val totalCounts: List<TypeCount> = emptyList(),   // 固定顺序: 生词/笔记/错题/知识点
    val bySubject: List<SubjectCount> = emptyList(),  // 按 ORDERED_CODES 排序，只含笔记/错题/卡片（生词无 subject）
    val byDay: List<DayCount> = emptyList(),          // 近7天(含今天)，空天补零
)

class StudyStatsVM(private val daos: StudyDaoSet) : ViewModel() {

    private val _state = MutableStateFlow(StudyStatsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        delay(50) // 仿 StatsVM，让 loading 态可见
        val allItems = withContext(Dispatchers.IO) {
            collectStudyItems(daos, StatsParams("all", null, null, 0), System.currentTimeMillis())
        }

        // totalCounts: 按 StudyItem.type 分组计数，固定顺序 vocabulary/note/wrong_question/knowledge_card
        val totalCounts = listOf(
            "vocabulary" to "生词",
            "note" to "笔记",
            "wrong_question" to "错题",
            "knowledge_card" to "知识点",
        ).map { (code, label) ->
            TypeCount(code, label, allItems.count { it.type == code })
        }

        // bySubject: 生词 subject 为空串天然排除（与 AI 工具口径一致）。
        // 用 normalize 把中文别名/大小写变体归到规范学科码，再按 ORDERED_CODES 排序，只留非空学科。
        val subjectTotals = allItems
            .filter { it.subject.isNotBlank() }
            .groupingBy { StudySubject.normalize(it.subject) }
            .eachCount()
        val bySubject = StudySubject.ORDERED_CODES.mapNotNull { code ->
            subjectTotals[code]?.takeIf { it > 0 }?.let { SubjectCount(code, it) }
        }

        // byDay: 近7天（含今天），每天统计 createdAt 落在当天的 items 总数，空天补零
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val byDay = (0L until 7L).map { offset ->
            val day = today.minusDays(6 - offset)
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DayCount(day, allItems.count { it.createdAt in dayStart until dayEnd })
        }

        _state.value = StudyStatsUiState(
            isLoading = false,
            totalCounts = totalCounts,
            bySubject = bySubject,
            byDay = byDay,
        )
    }
}
