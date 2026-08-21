package me.rerere.rikkahub.data.ai.tools

import java.io.File
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDescriptionConventionTest {

    @Test
    fun highTrafficToolsDescribeWhenToUseOrAvoid() {
        val skillTools = createSkillTools(
            enabledSkills = setOf("alpha"),
            allSkills = listOf(
                SkillMetadata(
                    name = "alpha",
                    description = "Test skill",
                    skillDir = File("."),
                )
            ),
        )
        val memoryTools = buildMemoryTools(
            json = Json,
            onCreation = { content -> AssistantMemory(id = 0, content = content) },
            onUpdate = { id, content -> AssistantMemory(id = id, content = content) },
            onDelete = {},
        )
        val searchTools = createSearchTools(Settings(init = true))
        val tools = skillTools + memoryTools + searchTools.toList()

        assertTrue(tools.isNotEmpty())
        tools.forEach { tool ->
            assertTrue(
                "${tool.name} should describe when to use or avoid",
                tool.description.contains("Use when", ignoreCase = true) ||
                    tool.description.contains("Avoid", ignoreCase = true)
            )
        }
    }
}
