package me.rerere.rikkahub.ui.pages.study

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.StudySubject

/**
 * 三个学习面板共用的学科 Tab 栏。第一项「全部」，其余来自数据库实际存在的学科。
 */
@Composable
fun SubjectTabBar(
    subjects: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabItems = remember(subjects) {
        val clean = subjects.map { StudySubject.normalize(it) }.distinct()
            .sortedBy { StudySubject.ORDERED_CODES.indexOf(it) }
        listOf<String?>(null) + clean
    }
    val selectedIndex = tabItems.indexOf(selected).coerceAtLeast(0)
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        edgePadding = 8.dp,
    ) {
        tabItems.forEachIndexed { index, code ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(code) },
                text = {
                    Text(
                        if (code == null) "全部" else StudySubject.name(code),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                },
            )
        }
    }
}
