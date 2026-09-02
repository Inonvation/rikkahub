package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.config.AgentConfigPaths
import me.rerere.rikkahub.data.config.AgentConfigRepository
import me.rerere.rikkahub.data.db.fts.MemoryFtsManager
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceAsyncTaskRunner
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderStore
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        // Lazy 注入 SubAgentRunner：SubAgentRunner 依赖本 Repository，用 lazy 打破构造期循环依赖
        ConversationRepository(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            lazy { get<me.rerere.rikkahub.data.ai.subagent.SubAgentRunner>() },
        )
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        GroupRepository(groupDao = get())
    }

    single {
        MemoryFtsManager(get())
    }

    single {
        MemoryRepository(get(), get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    // 后台 shell 任务执行器：输出目录与截断恢复目录（/tool_outputs）同源，由 RikkaHubApp 按 24h 保留制清理
    // 终态回调转发到 AppEventBus：通知等外部副作用不轮询，而是靠事件立刻感知任务结束
    single {
        val eventBus = get<AppEventBus>()
        WorkspaceAsyncTaskRunner(
            manager = get(),
            outputDir = File(get<Context>().filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
        ) { taskId ->
            eventBus.tryEmit(AppEvent.AsyncTaskTerminal(taskId = taskId))
        }
    }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get())
    }

    single {
        TrustedFolderStore(get())
    }

    // agent/ 统一配置只读门面（filesDir/agent）
    single {
        AgentConfigRepository(
            agentRoot = File(get<Context>().filesDir, AgentConfigPaths.ROOT_DIR),
            assets = get<Context>().assets,
        )
    }

    single {
        TrustedFolderRepository(get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
