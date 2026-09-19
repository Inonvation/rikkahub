package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.migration.migrateProvidersJson
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderIsolationTest {
    private fun openAi(
        id: Uuid = Uuid.random(),
        name: String,
        models: List<Model>,
    ) = ProviderSetting.OpenAI(
        id = id,
        name = name,
        models = models,
        apiKey = "k",
        baseUrl = "https://example.com/v1",
    )

    private fun model(modelId: String, id: Uuid = Uuid.random()) = Model(
        modelId = modelId,
        displayName = modelId,
        id = id,
        type = ModelType.CHAT,
    )

    @Test
    fun `generateUniqueProviderName keeps free name`() {
        assertEquals("阿里云百炼", generateUniqueProviderName("阿里云百炼", emptyList()))
        assertEquals("阿里云百炼", generateUniqueProviderName("阿里云百炼", listOf("OpenAI")))
    }

    @Test
    fun `generateUniqueProviderName appends copy suffix on collision`() {
        assertEquals(
            "阿里云百炼 副本",
            generateUniqueProviderName("阿里云百炼", listOf("阿里云百炼")),
        )
        assertEquals(
            "阿里云百炼 副本 2",
            generateUniqueProviderName("阿里云百炼", listOf("阿里云百炼", "阿里云百炼 副本")),
        )
        assertEquals(
            "阿里云百炼 副本 3",
            generateUniqueProviderName(
                "阿里云百炼",
                listOf("阿里云百炼", "阿里云百炼 副本", "阿里云百炼 副本 2"),
            ),
        )
    }

    @Test
    fun `generateUniqueProviderName compares case-insensitively`() {
        assertEquals("openai 副本", generateUniqueProviderName("openai", listOf("OpenAI")))
    }

    @Test
    fun `withProviderIsolation returns same list when already isolated`() {
        val sharedModelId = Uuid.random()
        val providers = listOf(
            openAi(name = "A", models = listOf(model("m1", sharedModelId))),
            openAi(name = "B", models = listOf(model("m2"))),
        )
        // Model.id 不同供应商也不同，且名称不同 → 应原样返回
        assertSame(providers, providers.withProviderIsolation())
    }

    @Test
    fun `withProviderIsolation regenerates shared model ids for later providers`() {
        val sharedId = Uuid.random()
        val providers = listOf(
            openAi(id = Uuid.random(), name = "阿里云百炼", models = listOf(model("qwen-max", sharedId))),
            openAi(id = Uuid.random(), name = "阿里云百炼", models = listOf(model("qwen-max", sharedId))),
        )

        val result = providers.withProviderIsolation()

        assertEquals(2, result.size)
        val first = result[0].models.single()
        val second = result[1].models.single()
        assertEquals(sharedId, first.id)
        assertNotEquals(sharedId, second.id)
        // 名称隔离
        assertEquals("阿里云百炼", result[0].name)
        assertEquals("阿里云百炼 副本", result[1].name)
    }

    @Test
    fun `withProviderIsolation is stable with deterministic id factory`() {
        val sharedId = Uuid.random()
        val providers = listOf(
            openAi(id = Uuid.parse("11111111-1111-1111-1111-111111111111"), name = "A", models = listOf(model("qwen-max", sharedId))),
            openAi(id = Uuid.parse("22222222-2222-2222-2222-222222222222"), name = "A", models = listOf(model("qwen-max", sharedId))),
        )

        val firstPass = providers.withProviderIsolation()
        val secondPass = providers.withProviderIsolation()

        assertEquals(firstPass[1].models.single().id, secondPass[1].models.single().id)
        assertEquals(firstPass[1].name, secondPass[1].name)
    }

    @Test
    fun `withProviderIsolation drops duplicate provider ids keeping first`() {
        val id = Uuid.random()
        val providers = listOf(
            openAi(id = id, name = "A", models = listOf(model("m1"))),
            openAi(id = id, name = "B", models = listOf(model("m2"))),
        )

        val result = providers.withProviderIsolation()

        assertEquals(1, result.size)
        assertEquals("A", result[0].name)
        assertTrue(result[0].models.any { it.modelId == "m1" })
    }

    @Test
    fun `stableRemappedModelId is deterministic for same inputs`() {
        val providerId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val m = model("qwen-max", Uuid.random())
        assertEquals(stableRemappedModelId(providerId, m), stableRemappedModelId(providerId, m))
        assertNotEquals(
            stableRemappedModelId(providerId, m),
            stableRemappedModelId(Uuid.random(), m),
        )
    }

    @Test
    fun `migrateProvidersJson fixes shared model ids and duplicate names`() {
        val sharedId = Uuid.random()
        // 必须显式声明 List<ProviderSetting>：若推断为 List<OpenAI>，编码会丢 type 判别字段
        val providers = listOf<ProviderSetting>(
            openAi(id = Uuid.random(), name = "阿里云百炼", models = listOf(model("qwen-max", sharedId))),
            openAi(id = Uuid.random(), name = "阿里云百炼", models = listOf(model("qwen-max", sharedId))),
        )
        val json = JsonInstant.encodeToString(providers)

        val migratedJson = migrateProvidersJson(json)
        val migrated = JsonInstant.decodeFromString<List<ProviderSetting>>(migratedJson)

        assertEquals(2, migrated.size)
        assertEquals("阿里云百炼", migrated[0].name)
        assertEquals("阿里云百炼 副本", migrated[1].name)
        assertEquals(sharedId, migrated[0].models.single().id)
        assertNotEquals(sharedId, migrated[1].models.single().id)
        // 再跑一次应幂等（id 已唯一，名称已隔离）
        assertEquals(migratedJson, migrateProvidersJson(migratedJson))
    }

    @Test
    fun `migrateProvidersJson returns original when already clean`() {
        val providers = listOf<ProviderSetting>(
            openAi(id = Uuid.random(), name = "A", models = listOf(model("m1"))),
            openAi(id = Uuid.random(), name = "B", models = listOf(model("m2"))),
        )
        val json = JsonInstant.encodeToString(providers)
        assertEquals(json, migrateProvidersJson(json))
    }
}
