package me.rerere.rikkahub.data.secret

/**
 * 密钥加密存储接口。
 *
 * - [AndroidSecretStore]：AndroidKeyStore 硬件/隔离环境保护的 AES-GCM 实现（真机）；
 * - Fake（测试）：仅用于 JVM 单元测试。
 *
 * 用法：`encrypt(明文)` 得到 base64 密文，可安全落盘（DataStore/文件/备份包）；
 * `decrypt(密文)` 还原。明文密钥永不写入 agent/ 配置文件——配置文件只放 [SecretRefs] 引用。
 */
interface SecretStore {
    /** 加密明文，返回 base64(IV+密文)；失败返回 null */
    fun encrypt(plaintext: String): String?

    /** 解密 base64(IV+密文)；密文非法/密钥不可用返回 null */
    fun decrypt(ciphertext: String): String?

    /** 当前是否有可用的加密密钥 */
    val isAvailable: Boolean
}

/**
 * agent/ 配置文件中的密钥引用规范（keystore:<type>:<id>:<field>）。
 *
 * 配置文件只写引用，明文密钥由 [SecretStore] 保管；未来 P2 写回时按引用解析。
 */
object SecretRefs {
    const val PREFIX = "keystore:"

    fun providerSecret(providerId: String) = "keystore:provider:$providerId:secret"
    fun modelHeaders(modelId: String) = "keystore:model:$modelId:headers"
    fun modelBodies(modelId: String) = "keystore:model:$modelId:bodies"
    fun mcpSecrets(mcpId: String) = "keystore:mcp:$mcpId:secrets"
    fun assistantHeaders(assistantId: String) = "keystore:assistant:$assistantId:headers"
    fun assistantBodies(assistantId: String) = "keystore:assistant:$assistantId:bodies"

    /** 是否为合法引用格式。 */
    fun isSecretRef(value: String): Boolean = value.startsWith(PREFIX)
}
