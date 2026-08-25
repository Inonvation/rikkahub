package me.rerere.rikkahub.data.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore 实现的 AES-256-GCM 加密存储。
 *
 * - 密钥由系统 Keystore（硬件/隔离环境）保护，应用进程拿不到明文密钥；
 * - 密文 = base64(随机 IV(12B) + GCM 密文(含 128bit tag))，可安全落盘；
 * - 单例密钥 alias 复用，首次调用自动生成。
 *
 * 注意：AndroidKeyStore 密钥不随备份/换机迁移——跨设备导入配置时，
 * 密钥包无法解密，需重新输入密钥（与方案 P2 语义一致）。
 */
class AndroidSecretStore : SecretStore {

    companion object {
        private const val KEY_ALIAS = "rikkahub_secret_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TAG_BITS = 128
        private const val IV_SIZE = 12
    }

    override val isAvailable: Boolean
        get() = runCatching { getOrCreateKey(); true }.getOrDefault(false)

    override fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(ciphertext: String): String? = runCatching {
        val raw = Base64.decode(ciphertext, Base64.NO_WRAP)
        if (raw.size < IV_SIZE) return null
        val iv = raw.copyOfRange(0, IV_SIZE)
        val encrypted = raw.copyOfRange(IV_SIZE, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
