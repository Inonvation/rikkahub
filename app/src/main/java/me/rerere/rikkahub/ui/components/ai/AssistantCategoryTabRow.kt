package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import kotlin.uuid.Uuid

/**
 * 助手分类单选 Tab 行：「全部」+ 各分类 chip，行尾可选固定操作位（如分类管理入口）。
 *
 * - selectedCategoryId 为 null 表示「全部」
 * - 分类拖动排序收在分类管理 Sheet 里，此行只做单选导航，避免手势冲突
 */
@Composable
fun AssistantCategoryTabRow(
    categories: List<Tag>,
    selectedCategoryId: Uuid?,
    onSelectCategory: (Uuid?) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val hapticController = rememberHaptic()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "all") {
                FilterChip(
                    selected = selectedCategoryId == null,
                    onClick = {
                        hapticController.lightTap()
                        onSelectCategory(null)
                    },
                    label = { Text(stringResource(R.string.assistant_category_all)) },
                    shape = RoundedCornerShape(50),
                )
            }
            items(categories, key = { it.id }) { category ->
                FilterChip(
                    selected = selectedCategoryId == category.id,
                    onClick = {
                        hapticController.lightTap()
                        onSelectCategory(category.id)
                    },
                    label = { Text(category.name) },
                    shape = RoundedCornerShape(50),
                )
            }
        }
        trailingContent?.invoke()
    }
}
