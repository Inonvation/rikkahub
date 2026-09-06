package me.rerere.rikkahub.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOAuthClientTest {

    private val client = GitHubOAuthClient(clientId = "test_client_id")

    // ---- parseDeviceCodeResponse ----

    @Test
    fun parsesDeviceCodeResponse() {
        val start = client.parseDeviceCodeResponse(
            """{"device_code":"dc123","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""",
        )
        assertNotNull(start)
        assertEquals("dc123", start!!.deviceCode)
        assertEquals("WDJB-MJHT", start.userCode)
        assertEquals("https://github.com/login/device", start.verificationUri)
        assertEquals(900L, start.expiresInSec)
        assertEquals(5L, start.intervalSec)
    }

    @Test
    fun fillsDefaultsForMissingOptionalFields() {
        val start = client.parseDeviceCodeResponse(
            """{"device_code":"dc","user_code":"AB-CD"}""",
        )
        assertNotNull(start)
        assertEquals("https://github.com/login/device", start!!.verificationUri)
        assertEquals(900L, start.expiresInSec)
        assertEquals(5L, start.intervalSec)
    }

    @Test
    fun deviceCodeResponseGarbageReturnsNull() {
        assertNull(client.parseDeviceCodeResponse("not json"))
        assertNull(client.parseDeviceCodeResponse("""{"error":"boom"}"""))
    }

    // ---- parseTokenResponse ----

    @Test
    fun parsesSuccessToken() {
        val poll = client.parseTokenResponse(
            """{"access_token":"gho_abc","token_type":"bearer","scope":"repo,read:user"}""",
        )
        assertTrue(poll is GitHubOAuthClient.TokenPoll.Success)
        assertEquals("gho_abc", (poll as GitHubOAuthClient.TokenPoll.Success).accessToken)
        assertEquals("repo,read:user", poll.scope)
    }

    @Test
    fun pendingMapsToPending() {
        val poll = client.parseTokenResponse("""{"error":"authorization_pending","error_description":"..."}""")
        assertEquals(GitHubOAuthClient.TokenPoll.Pending, poll)
    }

    @Test
    fun slowDownAddsFiveSecondsToInterval() {
        val poll = client.parseTokenResponse("""{"error":"slow_down","interval":8}""")
        assertEquals(GitHubOAuthClient.TokenPoll.SlowDown(13L), poll)
    }

    @Test
    fun slowDownWithoutIntervalDefaultsToTen() {
        val poll = client.parseTokenResponse("""{"error":"slow_down"}""")
        assertEquals(GitHubOAuthClient.TokenPoll.SlowDown(10L), poll)
    }

    @Test
    fun expiredAndDeniedAndDisabledMap() {
        assertEquals(
            GitHubOAuthClient.TokenPoll.Expired,
            client.parseTokenResponse("""{"error":"expired_token"}"""),
        )
        assertEquals(
            GitHubOAuthClient.TokenPoll.Denied,
            client.parseTokenResponse("""{"error":"access_denied"}"""),
        )
        val poll = client.parseTokenResponse("""{"error":"device_flow_disabled"}""")
        assertTrue(poll is GitHubOAuthClient.TokenPoll.Error)
        assertTrue((poll as GitHubOAuthClient.TokenPoll.Error).message.contains("Device Flow"))
    }

    @Test
    fun unknownErrorCarriesDescription() {
        val poll = client.parseTokenResponse("""{"error":"weird","error_description":"something broke"}""")
        assertTrue(poll is GitHubOAuthClient.TokenPoll.Error)
        assertEquals("something broke", (poll as GitHubOAuthClient.TokenPoll.Error).message)
    }

    @Test
    fun garbageTokenResponseFails() {
        val poll = client.parseTokenResponse("not json")
        assertTrue(poll is GitHubOAuthClient.TokenPoll.Error)
    }

    // ---- 其他 ----

    @Test
    fun describesStartErrorWithHumanReadableReason() {
        assertEquals(
            "该 OAuth App 未启用 Device Flow（请在 App 设置勾选 Enable Device Flow 后重试）",
            client.describeStartError("""{"error":"device_flow_disabled","error_description":"..."}"""),
        )
        assertEquals(
            "OAuth client_id 不正确，请检查 local.properties 配置",
            client.describeStartError("""{"error":"incorrect_client_credentials"}"""),
        )
        assertEquals("boom happened", client.describeStartError("""{"error":"boom","error_description":"boom happened"}"""))
        assertEquals("GitHub device code 响应解析失败", client.describeStartError("not json"))
    }

    @Test
    fun defaultScopeRequestsRepoAndUser() {
        assertEquals("repo read:user", GitHubOAuthClient.DEFAULT_SCOPE)
    }

    @Test
    fun configuredDependsOnClientId() {
        assertTrue(GitHubOAuthClient("abc").isConfigured())
        assertTrue(!GitHubOAuthClient("").isConfigured())
    }
}
