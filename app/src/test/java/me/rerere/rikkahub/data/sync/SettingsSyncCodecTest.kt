package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.rikkahub.data.sync.s3.S3Config
import kotlin.uuid.Uuid
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncCodecTest {

    private fun sampleSettings(): Settings = Settings(
        themeId = "solarized",
        developerMode = true,
        webDavConfig = WebDavConfig(
            url = "https://dav.example.com",
            username = "user",
            password = "hunter2",
            path = "sync",
        ),
        s3Config = S3Config(
            endpoint = "https://s3.example.com",
            accessKeyId = "AKID",
            secretAccessKey = "s3secret",
            bucket = "bucket",
        ),
        providers = listOf(
            ProviderSetting.OpenAI(
                id = Uuid.random(),
                name = "My OpenAI",
                apiKey = "sk-verysecret123",
                baseUrl = "https://my-proxy.example.com/v1",
            )
        ),
        launchCount = 42,
        webServerAccessPassword = "webpass",
    )

    @Test
    fun stripsSensitiveTopLevelKeys() {
        val obj = JsonInstant.parseToJsonElement(
            SettingsSyncCodec.toSyncableJson(sampleSettings())
        ).jsonObject

        assertFalse("webDavConfig" in obj)
        assertFalse("s3Config" in obj)
        assertFalse("launchCount" in obj)
        assertFalse("webServerAccessPassword" in obj)
        assertFalse("backupReminderConfig" in obj)
    }

    @Test
    fun keepsAllowlistedKeys() {
        val obj = JsonInstant.parseToJsonElement(
            SettingsSyncCodec.toSyncableJson(sampleSettings())
        ).jsonObject

        assertEquals("solarized", obj["themeId"]?.toString()?.trim('"'))
        assertEquals(true, obj["developerMode"]?.toString()?.toBoolean())
        assertTrue("providers" in obj)
    }

    @Test
    fun stripsApiKeyInsideProviders() {
        val obj = JsonInstant.parseToJsonElement(
            SettingsSyncCodec.toSyncableJson(sampleSettings())
        ).jsonObject

        val providers = obj["providers"] as JsonArray
        val provider = providers[0] as JsonObject
        assertFalse("apiKey" in provider)
        assertEquals("My OpenAI", provider["name"]?.toString()?.trim('"'))
    }

    @Test
    fun keepsStrippedFieldsAsLocalOnRestore() {
        val local = sampleSettings()
        val remoteJson = SettingsSyncCodec.toSyncableJson(local)
        // 远端故意篡改 allowlist 字段，并携带伪装的 webDavConfig
        val remoteObj = JsonInstant.parseToJsonElement(remoteJson).jsonObject
        val tampered = remoteObj.toMutableMap().apply {
            this["themeId"] = JsonPrimitive("dark")
            this["webDavConfig"] = JsonInstant.parseToJsonElement(
                JsonInstant.encodeToString(
                    WebDavConfig(url = "https://evil.example.com", username = "evil", password = "pwned")
                )
            )
        }

        val restored = SettingsSyncCodec.fromSyncableJson(
            JsonInstant.encodeToString(JsonObject(tampered)),
            local
        )

        assertEquals("dark", restored.themeId)
        assertEquals("https://dav.example.com", restored.webDavConfig.url)
        assertEquals("hunter2", restored.webDavConfig.password)
        assertEquals(42, restored.launchCount)
        assertEquals("webpass", restored.webServerAccessPassword)
    }

    @Test
    fun keepsProviderApiKeyAsLocal() {
        val local = sampleSettings()
        val remoteJson = SettingsSyncCodec.toSyncableJson(local)
        val restored = SettingsSyncCodec.fromSyncableJson(remoteJson, local)

        val provider = restored.providers[0] as ProviderSetting.OpenAI
        assertEquals("sk-verysecret123", provider.apiKey)
        assertEquals("My OpenAI", provider.name)
        assertEquals("https://my-proxy.example.com/v1", provider.baseUrl)
    }

    @Test
    fun mergesProviderByNameAndPreservesApiKey() {
        val local = sampleSettings()
        // 远端把 provider 改名，但 local 保留 apiKey
        val remoteSettings = local.copy(
            providers = listOf(
                (local.providers[0] as ProviderSetting.OpenAI).copy(name = "Renamed")
            )
        )
        val remoteJson = SettingsSyncCodec.toSyncableJson(remoteSettings)
        val restored = SettingsSyncCodec.fromSyncableJson(remoteJson, local)

        val provider = restored.providers[0] as ProviderSetting.OpenAI
        assertEquals("Renamed", provider.name)
        assertEquals("sk-verysecret123", provider.apiKey)
    }

    @Test
    fun roundtripPreservesAllowlistedValues() {
        val settings = sampleSettings()
        val restored = SettingsSyncCodec.fromSyncableJson(
            SettingsSyncCodec.toSyncableJson(settings),
            Settings()
        )
        assertEquals(settings.themeId, restored.themeId)
        assertEquals(settings.developerMode, restored.developerMode)
        // provider 非密钥字段同步过来
        val provider = restored.providers.first { it.id == settings.providers[0].id }
        assertEquals("My OpenAI", provider.name)
        assertNotEquals(settings.webDavConfig, restored.webDavConfig)
    }

    @Test
    fun syncsCustomModesWithDefaultRef() {
        val custom = CustomModeConfig(
            id = "sync-custom",
            name = "同步自定义模式",
            policy = ChatModePolicy(capabilities = setOf(Capability.WORKSPACE)),
        )
        val settings = sampleSettings().copy(
            defaultMode = ModeRefs.custom(custom.id),
            customModes = listOf(custom),
        )
        val restored = SettingsSyncCodec.fromSyncableJson(
            SettingsSyncCodec.toSyncableJson(settings),
            Settings(),
        )

        assertEquals(settings.defaultMode, restored.defaultMode)
        assertEquals(settings.customModes, restored.customModes)
    }
}
