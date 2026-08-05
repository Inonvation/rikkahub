package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认双链预处理生成的内部链接前缀（rikkahub-note/）在 GFM safe links 下能被解析为 INLINE_LINK。
 * （此前验证过 GFM 对 `note:` 协议与含空格的 destination 都能解析——问题出在运行时 hasHtml→MarkdownNew 分支，
 *  已通过「onLinkClick 非空时强制走 MarkdownNode 路径」修复。）
 */
class WikilinkParseTest {
    private val parser = MarkdownParser(
        GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    )

    private fun containsLink(node: ASTNode): Boolean {
        if (node.type == MarkdownElementTypes.INLINE_LINK) return true
        return node.children.any { containsLink(it) }
    }

    @Test
    fun `relative prefix link is rendered as inline link`() {
        val ast = parser.buildMarkdownTreeFromString("[x](rikkahub-note/目标)")
        assertTrue("rikkahub-note/ 相对路径应生成 INLINE_LINK", containsLink(ast))
    }

    @Test
    fun `encoded target with space is rendered as inline link`() {
        val ast = parser.buildMarkdownTreeFromString("[x](rikkahub-note/%E7%AC%94%E8%AE%B0%20%E4%B8%80)")
        assertTrue("percent-encode 后的目标应生成 INLINE_LINK", containsLink(ast))
    }
}
