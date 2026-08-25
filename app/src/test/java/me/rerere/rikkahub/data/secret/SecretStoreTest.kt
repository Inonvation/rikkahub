package me.rerere.rikkahub.data.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM 测试用内存实现（AndroidKeyStore 无法在单测环境运行）。 */
class FakeSecretStore : SecretStore {
    override val isAvailable: Boolean get() = true
    private val entries = mutableMapOf<String, String>()

    override fun encrypt(plaintext: String): String? {
        val key = java.util.UUID.randomUUID().toString()
        entries[key] = plaintext
        return "enc:$key"
    }

    override fun decrypt(ciphertext: String): String? =
        ciphertext.removePrefix("enc:").let { entries[it] }
}

class SecretStoreTest {

    @Test
    fun roundTripEncryptDecrypt() {
        val store = FakeSecretStore()
        val cipher = store.encrypt("sk-verysecret123")!!
        assertTrue(cipher.startsWith("enc:"))
        assertNotEquals("sk-verysecret123", cipher)
        assertEquals("sk-verysecret123", store.decrypt(cipher))
        assertTrue(store.isAvailable)
    }

    @Test
    fun decryptUnknownCiphertextReturnsNull() {
        val store = FakeSecretStore()
        assertNull(store.decrypt("enc:missing"))
        assertNull(store.decrypt("garbage"))
    }

    @Test
    fun secretRefsFormatAndDetection() {
        assertEquals("keystore:provider:abc:secret", SecretRefs.providerSecret("abc"))
        assertEquals("keystore:model:m1:headers", SecretRefs.modelHeaders("m1"))
        assertEquals("keystore:model:m1:bodies", SecretRefs.modelBodies("m1"))
        assertEquals("keystore:mcp:c1:secrets", SecretRefs.mcpSecrets("c1"))
        assertEquals("keystore:assistant:a1:headers", SecretRefs.assistantHeaders("a1"))
        assertEquals("keystore:assistant:a1:bodies", SecretRefs.assistantBodies("a1"))
        assertTrue(SecretRefs.isSecretRef("keystore:provider:x:secret"))
        assertFalse(SecretRefs.isSecretRef("plain-text"))
        assertTrue(SecretRefs.providerSecret("x").startsWith(SecretRefs.PREFIX))
    }
}
