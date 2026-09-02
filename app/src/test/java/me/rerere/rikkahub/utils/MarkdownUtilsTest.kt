package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownUtilsTest {

    @Test
    fun `extract thinking title from single bold line`() {
        assertEquals("需求分析", "**需求分析**".extractThinkingTitle())
    }

    @Test
    fun `extract thinking title takes the last bold line`() {
        val text = """
            **第一步：拆解**
            拆解正文
            **第二步：验证**
            验证正文
        """.trimIndent()
        assertEquals("第二步：验证", text.extractThinkingTitle())
    }

    @Test
    fun `extract thinking title keeps previous title while last line is streaming`() {
        // 最后一行是半截的加粗（`**第二`），尚未闭合 → 不匹配，保持上一个完整标题
        val text = """
            **第一步**
            正文
            **第二
        """.trimIndent()
        assertEquals("第一步", text.extractThinkingTitle())
    }

    @Test
    fun `extract thinking title does not leak asterisks when a line has multiple bold`() {
        // 非贪婪 `.+?` 会取到 "a** **b"，把星号残留到标题里；改为 [^*]+ 后该行不再匹配
        assertEquals("上一个标题", "**上一个标题**\n**a** **b**".extractThinkingTitle())
    }

    @Test
    fun `extract thinking title supports markdown heading`() {
        assertEquals("需求分析", "# 需求分析\n正文".extractThinkingTitle())
        assertEquals("需求分析", "## 需求分析 ##\n正文".extractThinkingTitle())
    }

    @Test
    fun `extract thinking title prefers the last heading regardless of style`() {
        assertEquals("第二阶段", "**第一阶段**\n正文\n## 第二阶段\n正文".extractThinkingTitle())
    }

    @Test
    fun `extract thinking title skips comments inside code fence`() {
        // 代码块里的 `# 注释` 属于代码本身，不应被当成阶段标题
        val text = """
            **方案设计**
            ```python
            # 这是一条代码注释，不是阶段标题
            print(1)
            ```
        """.trimIndent()
        assertEquals("方案设计", text.extractThinkingTitle())
    }

    @Test
    fun `extract thinking title ignores bold followed by content`() {
        // "加粗后跟内容"是正文强调，不是独占一行的阶段标题
        assertNull("**需求分析**：先看输入\n正文".extractThinkingTitle())
    }

    @Test
    fun `extract thinking title skips empty bold line`() {
        assertEquals("有效标题", "**有效标题**\n**  **".extractThinkingTitle())
    }

    @Test
    fun `extract thinking title returns null when nothing matches`() {
        assertNull("".extractThinkingTitle())
        assertNull("纯正文，没有任何加粗行".extractThinkingTitle())
    }
}
