package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.knowledge.KnowledgeManager
import me.rerere.rikkahub.data.model.Assistant
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class, ExperimentalUuidApi::class)
@Composable
fun KnowledgeBaseChips(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIds = assistant.knowledgeBaseIds
    if (selectedIds.isEmpty()) return

    val knowledgeManager = koinInject<KnowledgeManager>()
    val bases by knowledgeManager.baseRepository.getAllWithDocumentCount()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val selected = remember(bases, selectedIds) {
        bases.filter { base ->
            runCatching { Uuid.parse(base.id) }.getOrNull() in selectedIds
        }
    }
    if (selected.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        selected.forEach { base ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HugeIcons.Bookshelf01,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = base.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 160.dp)
                            .padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    IconButton(
                        onClick = {
                            val baseUuid = runCatching { Uuid.parse(base.id) }.getOrNull() ?: return@IconButton
                            onUpdateAssistant(assistant.copy(knowledgeBaseIds = selectedIds - baseUuid))
                        },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "取消选中",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}