package me.rerere.rikkahub.ui.pages.trustedfolders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.FolderLocked
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderProject
import me.rerere.rikkahub.ui.hooks.rememberHaptic

/**
 * 信任文件夹项目切换底部弹层：列出所有项目，点击切换激活（单选）。长按输入框下方项目名 chip 触发。
 */
@Composable
fun TrustedFolderSelectSheet(
    projects: List<TrustedFolderProject>,
    activeProjectId: String?,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
    ) {
        val hapticController = rememberHaptic()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "切换项目",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                projects.forEach { project ->
                    val selected = project.id == activeProjectId
                    ListItem(
                        leadingContent = {
                            Icon(HugeIcons.FolderLocked, contentDescription = null)
                        },
                        headlineContent = {
                            Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            if (selected) {
                                Icon(
                                    imageVector = HugeIcons.CheckmarkCircle01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                Color.Transparent
                            }
                        ),
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.large)
                            .clickable {
                                hapticController.perform(HapticFeedbackType.KeyboardTap)
                                onSelect(project.id)
                            },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 管理项目
            ListItem(
                leadingContent = {
                    Icon(HugeIcons.Codesandbox, contentDescription = null)
                },
                headlineContent = {
                    Text("管理项目")
                },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable { hapticController.perform(HapticFeedbackType.KeyboardTap); onManage() },
            )
        }
    }
}
