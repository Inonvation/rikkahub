package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun WorkspacePickerButton(
    assistant: Assistant,
    conversation: Conversation,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    val navController = LocalNavController.current
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    var showSheet by remember { mutableStateOf(false) }
    if (workspaces.isEmpty()) return
    val hasWorkspace = boundWorkspace != null

    ToggleSurface(
        checked = hasWorkspace,
        onClick = { showSheet = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .height(if (compact) 40.dp else 44.dp)
                .padding(horizontal = if (compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier.size(if (compact) 22.dp else 24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (hasWorkspace && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name) {
                    Icon(
                        imageVector = HugeIcons.Codesandbox,
                        contentDescription = stringResource(R.string.assistant_page_workspace),
                        modifier = Modifier.size(if (compact) 20.dp else 22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else if (hasWorkspace) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Codesandbox,
                        contentDescription = stringResource(R.string.assistant_page_workspace),
                        modifier = Modifier.size(if (compact) 20.dp else 22.dp),
                    )
                }
            }
        }
    }

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                    if (conversation.workspaceCwd != null) {
                        onUpdateConversation(conversation.copy(workspaceCwd = null))
                    }
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                navController.navigate(Screen.Workspaces)
            },
            onDismiss = { showSheet = false },
            onSettings = { workspaceId ->
                showSheet = false
                navController.navigate(Screen.WorkspaceDetail(workspaceId))
            },
        )
    }
}
