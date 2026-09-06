package me.rerere.rikkahub.data.github

import java.util.Base64
import me.rerere.workspace.WorkspaceCommandResult

/**
 * GitHub 凭据进入工作区 shell 的环境构造与输出脱敏（纯函数，JVM 单测覆盖）。
 *
 * 环境注入对标主流 agent/CI 的做法：
 * - `GITHUB_TOKEN`/`GH_TOKEN`：gh CLI 与多数工具识别的通行证（对标 gh 的环境变量约定）；
 * - git 私仓克隆走 actions/checkout 的 `http.https://github.com/.extraheader` 方案，
 *   经 `GIT_CONFIG_COUNT/KEY_0/VALUE_0` 环境变量传入——免改 remote URL、免落盘凭据文件；
 * - `curl https://api.github.com/...` 由 AI 自行带 `Authorization: Bearer $GITHUB_TOKEN`。
 *
 * 输出脱敏对标 GitHub Actions 的日志机密打码：token 一旦注入，命令输出中的明文出现
 * 一律替换为占位符（Claude Code 因不脱敏泄密钥是已归档 issue，这里做在前面）。
 */
object GitHubShellEnv {

    /** token 出现在输出中时的占位符 */
    const val MASK = "***GITHUB_TOKEN***"

    /** 构造注入 proot 环境的变量表 */
    fun buildShellEnv(token: String): Map<String, String> {
        val basic = Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray(Charsets.UTF_8))
        return mapOf(
            "GITHUB_TOKEN" to token,
            "GH_TOKEN" to token,
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.https://github.com/.extraheader",
            "GIT_CONFIG_VALUE_0" to "AUTHORIZATION: basic $basic",
        )
    }

    /** 总闸开启且已绑定账号时返回注入环境，否则空表 */
    fun shellEnvIfEnabled(enabled: Boolean, token: String?): Map<String, String> {
        if (!enabled) return emptyMap()
        val t = token?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return buildShellEnv(t)
    }

    /** 替换文本中的机密明文。过短的串（<8）不处理，避免误伤 */
    fun maskSecrets(text: String, secrets: Collection<String>): String {
        var result = text
        for (secret in secrets) {
            if (secret.length < 8) continue
            result = result.replace(secret, MASK)
        }
        return result
    }

    fun maskResult(result: WorkspaceCommandResult, secrets: Collection<String>): WorkspaceCommandResult {
        if (secrets.isEmpty()) return result
        return result.copy(
            stdout = maskSecrets(result.stdout, secrets),
            stderr = maskSecrets(result.stderr, secrets),
        )
    }
}
