package me.rerere.rikkahub.data.ai.openai

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodexCredentialImportTest {
    @Test
    fun `parses official Codex auth json format`() {
        val authJson = """
            {
              "auth_mode": "chatgpt",
              "tokens": {
                "id_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.${jwtPayload("""{"email":"user@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"acct-123","chatgpt_plan_type":"plus"}}""")}.sig",
                "access_token": "access-123",
                "refresh_token": "refresh-123",
                "account_id": "acct-123"
              },
              "last_refresh": "2026-08-25T00:00:00Z"
            }
        """.trimIndent()

        val parsed = parseCodexCredentialImport(authJson)

        assertEquals("access-123", parsed.accessToken)
        assertEquals("refresh-123", parsed.refreshToken)
        assertEquals("acct-123", parsed.accountId)
        assertEquals("user@example.com", parsed.email)
        assertEquals("plus", parsed.planType)
    }

    @Test
    fun `parses plain token json with snake case`() {
        val json = """
            {
              "access_token": "access-456",
              "refresh_token": "refresh-456",
              "account_id": "acct-456"
            }
        """.trimIndent()

        val parsed = parseCodexCredentialImport(json)

        assertEquals("access-456", parsed.accessToken)
        assertEquals("refresh-456", parsed.refreshToken)
        assertEquals("acct-456", parsed.accountId)
        assertNull(parsed.email)
    }

    @Test
    fun `parses camel case token json`() {
        val json = """
            {
              "accessToken": "access-789",
              "refreshToken": "refresh-789",
              "accountId": "acct-789"
            }
        """.trimIndent()

        val parsed = parseCodexCredentialImport(json)

        assertEquals("access-789", parsed.accessToken)
        assertEquals("refresh-789", parsed.refreshToken)
        assertEquals("acct-789", parsed.accountId)
    }

    @Test
    fun `accepts missing refresh token`() {
        val json = """{"access_token":"access-only","account_id":"acct-1"}"""

        val parsed = parseCodexCredentialImport(json)

        assertEquals("access-only", parsed.accessToken)
        assertNull(parsed.refreshToken)
        assertEquals("acct-1", parsed.accountId)
    }

    @Test
    fun `rejects json without access token`() {
        val json = """{"refresh_token":"r","account_id":"a"}"""

        try {
            parseCodexCredentialImport(json)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("access_token"))
        }
    }

    @Test
    fun `rejects json without account id`() {
        val json = """{"access_token":"a"}"""

        try {
            parseCodexCredentialImport(json)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("account_id"))
        }
    }

    @Test
    fun `rejects invalid json`() {
        try {
            parseCodexCredentialImport("not-json-at-all")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    private fun jwtPayload(claims: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(claims.encodeToByteArray())
}
