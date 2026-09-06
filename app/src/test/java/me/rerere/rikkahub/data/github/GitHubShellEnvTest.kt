package me.rerere.rikkahub.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GitHubShellEnvTest {

    private val token = "gho_1234567890abcdef"

    @Test
    fun buildsTokenAndGitExtraHeaderEnv() {
        val env = GitHubShellEnv.buildShellEnv(token)
        assertEquals(token, env["GITHUB_TOKEN"])
        assertEquals(token, env["GH_TOKEN"])
        assertEquals("1", env["GIT_CONFIG_COUNT"])
        assertEquals("http.https://github.com/.extraheader", env["GIT_CONFIG_KEY_0"])
        val expectedBasic = Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray(Charsets.UTF_8))
        assertEquals("AUTHORIZATION: basic $expectedBasic", env["GIT_CONFIG_VALUE_0"])
    }

    @Test
    fun shellEnvRespectsToggleAndToken() {
        assertTrue(GitHubShellEnv.shellEnvIfEnabled(enabled = false, token = token).isEmpty())
        assertTrue(GitHubShellEnv.shellEnvIfEnabled(enabled = true, token = null).isEmpty())
        assertTrue(GitHubShellEnv.shellEnvIfEnabled(enabled = true, token = "  ").isEmpty())
        val env = GitHubShellEnv.shellEnvIfEnabled(enabled = true, token = token)
        assertEquals(token, env["GITHUB_TOKEN"])
    }

    @Test
    fun masksTokenOccurrences() {
        val text = "curl -H \"Authorization: Bearer $token\" https://api.github.com/user\n$token leaked"
        val masked = GitHubShellEnv.maskSecrets(text, setOf(token))
        assertEquals(
            "curl -H \"Authorization: Bearer ${GitHubShellEnv.MASK}\" https://api.github.com/user\n${GitHubShellEnv.MASK} leaked",
            masked,
        )
    }

    @Test
    fun ignoresShortSecrets() {
        val text = "short one abc here"
        assertEquals(text, GitHubShellEnv.maskSecrets(text, setOf("abc")))
    }

    @Test
    fun masksBothStreamsOfResult() {
        val result = me.rerere.workspace.WorkspaceCommandResult(
            exitCode = 0,
            stdout = "token=$token",
            stderr = "echo $token",
        )
        val masked = GitHubShellEnv.maskResult(result, setOf(token))
        assertEquals("token=${GitHubShellEnv.MASK}", masked.stdout)
        assertEquals("echo ${GitHubShellEnv.MASK}", masked.stderr)
    }

    @Test
    fun maskResultNoopWhenNoSecrets() {
        val result = me.rerere.workspace.WorkspaceCommandResult(exitCode = 0, stdout = "hi", stderr = "")
        assertTrue(GitHubShellEnv.maskResult(result, emptySet()) === result)
    }
}
