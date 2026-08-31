package me.rerere.rikkahub.data.repository

import android.util.Log
import me.rerere.rikkahub.data.ai.tools.boundShellStream
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.uuid.Uuid

/** 后台 shell 任务状态（进程存活期内可查询；重启后任务记录丢失） */
enum class AsyncTaskState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
}

/** 后台任务的可查询状态；输出文件落 /tool_outputs（与截断恢复目录同源，24h 保留） */
data class AsyncTaskStatus(
    val taskId: String,
    val state: AsyncTaskState,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
) {
    /** 完整输出路径（Rootfs 视角），任务结束后可用 shell cat/grep 读取 */
    val outputPath: String get() = "/tool_outputs/$taskId.txt"
}

/**
 * workspace_shell_async 的后端：独立 daemon 线程池执行 proot 命令（分钟级任务不阻塞对话），
 * 完成时把完整输出写入 [outputDir]/<taskId>.txt（根路径 /tool_outputs，随应用 24h 保留制清理）。
 * 任务记录仅存活于进程内，条目数超限时优先淘汰最早的已完成任务。
 */
class WorkspaceAsyncTaskRunner(
    private val manager: WorkspaceManager,
    private val outputDir: File,
) {
    private val tasks = ConcurrentHashMap<String, AsyncTaskStatus>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "workspace-async").apply { isDaemon = true }
    }

    fun launch(
        workspaceId: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
    ): String {
        trimFinishedTasks()
        val taskId = Uuid.random().toString()
        tasks[taskId] = AsyncTaskStatus(taskId = taskId, state = AsyncTaskState.RUNNING)
        executor.execute {
            val status = try {
                val result = manager.executeCommand(workspaceId, command, cwd, timeoutMillis)
                outputDir.mkdirs()
                File(outputDir, "$taskId.txt").writeText(
                    buildString {
                        appendLine("exit: ${result.exitCode}${if (result.timedOut) " (timed out)" else ""}")
                        appendLine("--- stdout ---")
                        append(result.stdout)
                        appendLine()
                        appendLine("--- stderr ---")
                        append(result.stderr)
                    }
                )
                when {
                    result.timedOut -> AsyncTaskStatus(
                        taskId = taskId,
                        state = AsyncTaskState.TIMED_OUT,
                        exitCode = result.exitCode,
                        timedOut = true,
                        stdout = boundShellStream(result.stdout, result.stdoutTruncated) ?: result.stdout,
                        stderr = boundShellStream(result.stderr, result.stderrTruncated) ?: result.stderr,
                    )
                    else -> AsyncTaskStatus(
                        taskId = taskId,
                        state = AsyncTaskState.SUCCEEDED,
                        exitCode = result.exitCode,
                        stdout = boundShellStream(result.stdout, result.stdoutTruncated) ?: result.stdout,
                        stderr = boundShellStream(result.stderr, result.stderrTruncated) ?: result.stderr,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Async task $taskId failed: $command", e)
                AsyncTaskStatus(
                    taskId = taskId,
                    state = AsyncTaskState.FAILED,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
            tasks[taskId] = status
        }
        return taskId
    }

    fun status(taskId: String): AsyncTaskStatus? = tasks[taskId]

    /** 记录数超限时淘汰最早的已完成任务（RUNNING 恒保留） */
    private fun trimFinishedTasks() {
        if (tasks.size <= MAX_TRACKED_TASKS) return
        tasks.entries
            .sortedBy { it.value.taskId }
            .firstOrNull { it.value.state != AsyncTaskState.RUNNING }
            ?.let { tasks.remove(it.key) }
    }

    private companion object {
        private const val TAG = "WorkspaceAsyncTaskRunner"
        private const val MAX_TRACKED_TASKS = 200
    }
}