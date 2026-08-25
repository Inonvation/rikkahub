package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFrontmatterParserTest {

    @Test
    fun parsesNameDescriptionAndAllowedTools() {
        val content = """
            ---
            name: web-search
            description: Search the web with a tool.
            allowed-tools:
              - web_search
              - fetch_page
            ---
            # Instructions
            Use the tools above.
        """.trimIndent()

        val frontmatter = SkillFrontmatterParser.parse(content)
        assertEquals("web-search", frontmatter["name"])
        assertEquals("Search the web with a tool.", frontmatter["description"])
        assertEquals(listOf("web_search", "fetch_page"), frontmatter.getStringList("allowed-tools"))
        // body 只保留 frontmatter 之后的内容
        assertTrue(SkillFrontmatterParser.extractBody(content).startsWith("# Instructions"))
    }

    @Test
    fun missingAllowedToolsReturnsNull() {
        val content = """
            ---
            name: plain
            description: No tools.
            ---
            body
        """.trimIndent()
        val frontmatter = SkillFrontmatterParser.parse(content)
        assertEquals("plain", frontmatter["name"])
        assertNull(frontmatter.getStringList("allowed-tools"))
    }

    @Test
    fun nonFrontmatterContentReturnsEmpty() {
        val empty = SkillFrontmatterParser.parse("Just a normal markdown file.")
        assertNull(empty["name"])
        assertEquals("Just a normal markdown file.", SkillFrontmatterParser.extractBody("Just a normal markdown file."))
    }
}
