package me.rerere.rikkahub.data.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import rikka.shizuku.IShizukuCommandService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService：以 shell(ADB) 身份运行，由 Shizuku 通过 app_process 启动。
 *
 * 命令通过 [ProcessBuilder] 直接 exec 参数数组执行，不经过 shell，避免拼接注入。
 * stdout/stderr 用独立线程并发读取，防止命令输出量超过管道缓冲时与超时互锁。
 */
class UserService() : IShizukuCommandService.Stub() {
    companion object {
        private const val TAG = "ShizukuUserService"
        private const val MAX_OUTPUT_BYTES = 256 * 1024
    }

    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile
    private var lastExitCode = -1

    @Volatile
    private var lastStdout = ""

    @Volatile
    private var lastStderr = ""

    /**
     * Shizuku 要求提供无参或 Context 构造器；Context 构造器需 [Keep] 防止混淆移除。
     */
    @Keep
    constructor(context: Context) : this()

    override fun execute(cmd: Array<String>, timeoutMillis: Long): Int {
        return try {
            val process = ProcessBuilder(*cmd).start()
            val stdoutFuture = readAllAsync(process.inputStream)
            val stderrFuture = readAllAsync(process.errorStream)
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            lastExitCode = if (finished) process.exitValue() else -1
            lastStdout = getQuietly(stdoutFuture)
            lastStderr = getQuietly(stderrFuture)
            Log.i(TAG, "exit=$lastExitCode, timedOut=${!finished}, cmd=${cmd.joinToString(" ")}")
            lastExitCode
        } catch (e: Throwable) {
            Log.e(TAG, "execute failed", e)
            lastExitCode = -1
            lastStdout = ""
            lastStderr = e.message ?: "unknown error"
            lastExitCode
        }
    }

    override fun getExitCode(): Int = lastExitCode

    override fun getStdout(): String = lastStdout

    override fun getStderr(): String = lastStderr

    override fun destroy() {
        Log.i(TAG, "destroy")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun readAllAsync(stream: InputStream): Future<String> =
        ioExecutor.submit<String> { readAll(stream) }

    private fun getQuietly(future: Future<String>): String =
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            ""
        }

    private fun readAll(stream: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        try {
            while (true) {
                val read = stream.read(buf)
                if (read < 0) break
                val remaining = MAX_OUTPUT_BYTES - total
                if (remaining > 0) {
                    val n = minOf(read, remaining)
                    buffer.write(buf, 0, n)
                    total += n
                }
            }
        } finally {
            runCatching { stream.close() }
        }
        return buffer.toString("UTF-8")
    }
}