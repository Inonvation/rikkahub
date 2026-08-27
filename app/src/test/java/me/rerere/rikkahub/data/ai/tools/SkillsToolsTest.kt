package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillsToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createMetadata(name: String, dirName: String = name): SkillMetadata {
        return SkillMetadata(
            name = name,
            description = "Test skill",
            skillDir = tempFolder.newFolder(dirName).apply {
                resolve("SKILL.md").writeText(
                    """
                        ---
                        name: $name
                        description: Test skill
                        ---
                        Skill instructions
                    """.trimIndent()
                )
            },
        )
    }

    @Test
    fun `use_skill reads metadata directory when display name differs`() = runBlocking {
        val skillDir = tempFolder.newFolder("directory-name")
        skillDir.resolve("SKILL.md").writeText(
            """
                ---
                name: Display Name
                description: Test skill
                ---
                Skill instructions
            """.trimIndent()
        )
        val tool = createSkillTools(
            enabledSkills = setOf("Display Name"),
            listAllSkills = {
                listOf(
                    SkillMetadata(
                        name = "Display Name",
                        description = "Test skill",
                        skillDir = skillDir,
                    )
                )
            },
        ).first { it.name == "use_skill" }

        val result = tool.execute(
            buildJsonObject {
                put("name", "Display Name")
            }
        )

        assertEquals("Skill instructions", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `skill_admin_list reports enablement state`() = runBlocking {
        // provider 会被多次调用，必须幂等：预构建列表而不是每次调用都建临时目录
        val skills = listOf(
            createMetadata("alpha"),
            createMetadata("beta"),
        )
        val tools = createSkillTools(
            enabledSkills = setOf("alpha"),
            listAllSkills = { skills },
        )
        val tool = tools.first { it.name == "skill_admin_list" }

        val result = tool.execute(buildJsonObject {})

        val text = (result.single() as UIMessagePart.Text).text
        assertTrue("alpha should be enabled", text.contains("name: alpha") && text.contains("enabled for current assistant: true"))
        assertTrue("beta should be disabled", text.contains("name: beta") && text.contains("enabled for current assistant: false"))
    }

    @Test
    fun `skill_admin_list sees skills installed during the turn`() = runBlocking {
        val liveSkills = mutableListOf(createMetadata("alpha"))
        val tool = createSkillTools(
            enabledSkills = emptySet(),
            listAllSkills = { liveSkills.toList() },
        ).first { it.name == "skill_admin_list" }

        assertFalse(
            "beta should not be visible before install",
            (tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text.contains("name: beta")
        )

        // 模拟同轮内通过 workspace 写入新技能目录
        liveSkills.add(createMetadata("beta"))

        val text = (tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text
        assertTrue("newly installed beta should be visible immediately", text.contains("name: beta"))
        assertTrue(text.contains("enabled for current assistant: false"))
    }

    @Test
    fun `skill_admin_set_enabled accepts skill installed during the turn`() = runBlocking {
        var captured: Set<String>? = null
        val liveSkills = mutableListOf<SkillMetadata>()
        val tool = createSkillTools(
            enabledSkills = emptySet(),
            listAllSkills = { liveSkills.toList() },
            setEnabledSkills = { captured = it },
        ).first { it.name == "skill_admin_set_enabled" }

        liveSkills.add(createMetadata("late"))

        val result = tool.execute(
            buildJsonObject {
                put("name", "late")
                put("enabled", true)
            }
        )

        assertEquals(setOf("late"), captured)
        assertEquals("Skill 'late' enabled for the current assistant.", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `skill_admin_set_enabled operations in same turn build on latest state`() = runBlocking {
        var captured: Set<String>? = null
        val liveSkills = mutableListOf(createMetadata("alpha"), createMetadata("beta"), createMetadata("gamma"))
        val tool = createSkillTools(
            enabledSkills = setOf("alpha", "beta"),
            listAllSkills = { liveSkills.toList() },
            setEnabledSkills = { captured = it },
        ).first { it.name == "skill_admin_set_enabled" }

        tool.execute(buildJsonObject { put("name", "beta"); put("enabled", false) })
        assertEquals(setOf("alpha"), captured)

        tool.execute(buildJsonObject { put("name", "gamma"); put("enabled", true) })
        assertEquals("second op must retain the first op's change", setOf("alpha", "gamma"), captured)
    }

    @Test
    fun `skill_admin_set_enabled appears even when no skill enabled`() = runBlocking {
        val tools = createSkillTools(
            enabledSkills = emptySet(),
            listAllSkills = { listOf(createMetadata("alpha")) },
        )
        assertTrue(tools.any { it.name == "skill_admin_list" })
        assertTrue(tools.any { it.name == "skill_admin_set_enabled" })
    }

    @Test
    fun `skill_admin_set_enabled invokes callback with updated set`() = runBlocking {
        var captured: Set<String>? = null
        val skills = listOf(createMetadata("alpha"))
        val tools = createSkillTools(
            enabledSkills = emptySet(),
            listAllSkills = { skills },
            setEnabledSkills = { captured = it },
        )
        val tool = tools.first { it.name == "skill_admin_set_enabled" }

        val result = tool.execute(
            buildJsonObject {
                put("name", "alpha")
                put("enabled", true)
            }
        )

        assertEquals(setOf("alpha"), captured)
        assertEquals("Skill 'alpha' enabled for the current assistant.", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `skill_admin_set_enabled disables skill via callback`() = runBlocking {
        var captured: Set<String>? = null
        val skills = listOf(
            createMetadata("alpha"),
            createMetadata("beta"),
        )
        val tools = createSkillTools(
            enabledSkills = setOf("alpha", "beta"),
            listAllSkills = { skills },
            setEnabledSkills = { captured = it },
        )
        val tool = tools.first { it.name == "skill_admin_set_enabled" }

        tool.execute(
            buildJsonObject {
                put("name", "beta")
                put("enabled", false)
            }
        )

        assertEquals(setOf("alpha"), captured)
    }

    @Test
    fun `system prompt only lists enabled skills`() {
        val tools = createSkillTools(
            enabledSkills = setOf("alpha"),
            listAllSkills = { listOf(createMetadata("alpha"), createMetadata("beta")) },
        )
        val prompt = tools.first { it.name == "use_skill" }.systemPrompt(
            Model(modelId = "test-model"),
            emptyList(),
        )

        assertTrue(prompt.contains("alpha"))
        assertFalse(prompt.contains("beta"))
        assertTrue(prompt.contains("skill_admin_list"))
    }
}
