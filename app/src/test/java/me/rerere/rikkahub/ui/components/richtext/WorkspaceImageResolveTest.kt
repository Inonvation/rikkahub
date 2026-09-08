package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * workspace 图片引用降级解析链的纯函数单测。
 *
 * 背景：AI 在正文 Markdown 里引用工作区图片时经常把路径拼错（相对 cwd 写、
 * 前缀笔误 /workspaces、URL 编码、携带 query 等），严格匹配失败会让气泡图片
 * 永远占位空白。这里覆盖规范化与候选生成的各种拼法。
 */
class WorkspaceImageResolveTest {

    // ---- normalizeWorkspaceImageSrc ----

    @Test
    fun `strips workspace prefix to bare path`() {
        assertEquals("a/b.gif", normalizeWorkspaceImageSrc("/workspace/a/b.gif"))
    }

    @Test
    fun `normalizes workspace scheme variants`() {
        assertEquals("a.gif", normalizeWorkspaceImageSrc("workspace://a.gif"))
        assertEquals("a.gif", normalizeWorkspaceImageSrc("workspace:/a.gif"))
        assertEquals("a.gif", normalizeWorkspaceImageSrc("workspace:a.gif"))
    }

    @Test
    fun `scheme prefix matching is case insensitive end to end`() {
        // startsWith(ignoreCase) 必须配套大小写不敏感的 removePrefix，否则归一产物携带原始前缀
        assertEquals("a.gif", normalizeWorkspaceImageSrc("Workspace://a.gif"))
        assertEquals("imgs/a.gif", normalizeWorkspaceImageSrc("WORKSPACE:/imgs/a.gif"))
        assertEquals("a.gif", normalizeWorkspaceImageSrc("/Workspaces/a.gif"))
    }

    @Test
    fun `relative filename starting with workspace is not treated as absolute ref`() {
        // workspaces.gif 是 workspace 根下的相对文件名，不应被 workspace: 前缀判断误伤
        assertEquals(
            listOf("/workspace/workspaces.gif"),
            buildWorkspaceImageCandidates("workspaces.gif", null),
        )
        assertEquals(
            listOf("/workspace/imgs/workspaces.gif", "/workspace/workspaces.gif"),
            buildWorkspaceImageCandidates("workspaces.gif", "/workspace/imgs"),
        )
    }

    @Test
    fun `fixes plural workspaces typo`() {
        assertEquals("a.gif", normalizeWorkspaceImageSrc("/workspaces/a.gif"))
    }

    @Test
    fun `strips query and fragment`() {
        assertEquals("a.gif", normalizeWorkspaceImageSrc("/workspace/a.gif?v=1"))
        assertEquals("a.gif", normalizeWorkspaceImageSrc("/workspace/a.gif#section"))
    }

    @Test
    fun `decodes percent encoding without plus mangling`() {
        assertEquals("my image a+b.gif", normalizeWorkspaceImageSrc("/workspace/my%20image%20a+b.gif"))
    }

    @Test
    fun `collapses duplicate slashes and dot segments`() {
        assertEquals("a/b.gif", normalizeWorkspaceImageSrc("/workspace//a/./b.gif"))
        // workspace:/// 与 workspace:// 同义（沿用原 resolver 语义：≡ /workspace/ 相对）
        assertEquals("a/b.gif", normalizeWorkspaceImageSrc("workspace:///a/./b.gif"))
    }

    @Test
    fun `keeps non workspace absolute path`() {
        assertEquals("/root/out/a.gif", normalizeWorkspaceImageSrc("/root/out/a.gif"))
    }

    @Test
    fun `keeps relative path as is`() {
        assertEquals("img/a.gif", normalizeWorkspaceImageSrc("img/a.gif"))
        // `./` 段与无前缀等价（相对路径下语义相同），消解后返回无前缀形式
        assertEquals("img/a.gif", normalizeWorkspaceImageSrc("./img/a.gif"))
    }

    @Test
    fun `resolves interior parent segments`() {
        // 消解 .. 后落回 workspace 根相对
        assertEquals("a.gif", normalizeWorkspaceImageSrc("/workspace/imgs/../a.gif"))
        // 两个 .. 消解 imgs 与 workspace 段 → 越出 workspace 根，保留绝对形式交由防穿越校验拒绝
        assertEquals("/a.gif", normalizeWorkspaceImageSrc("/workspace/imgs/../../a.gif"))
    }

    @Test
    fun `rejects url schemes`() {
        assertNull(normalizeWorkspaceImageSrc("https://example.com/a.gif"))
        assertNull(normalizeWorkspaceImageSrc("data:image/gif;base64,R0lGOD"))
        assertNull(normalizeWorkspaceImageSrc("file:///sdcard/a.gif"))
        assertNull(normalizeWorkspaceImageSrc("C:/Users/a.gif"))
    }

    @Test
    fun `rejects blank and slash only`() {
        assertNull(normalizeWorkspaceImageSrc(""))
        assertNull(normalizeWorkspaceImageSrc("   "))
        assertNull(normalizeWorkspaceImageSrc("/workspace/"))
        assertNull(normalizeWorkspaceImageSrc("/"))
    }

    // ---- buildWorkspaceImageCandidates ----

    @Test
    fun `relative path yields cwd candidate first then root`() {
        assertEquals(
            listOf("/workspace/imgs/a.gif", "/workspace/a.gif"),
            buildWorkspaceImageCandidates("a.gif", "/workspace/imgs"),
        )
    }

    @Test
    fun `relative path without cwd yields root candidate only`() {
        assertEquals(
            listOf("/workspace/a.gif"),
            buildWorkspaceImageCandidates("a.gif", null),
        )
    }

    @Test
    fun `absolute workspace path is single candidate without cwd hijack`() {
        // AI 明确写 /workspace/... 时只按根解析，不插 cwd 候选（cwd 下同名文件不应劫持）
        assertEquals(
            listOf("/workspace/a.gif"),
            buildWorkspaceImageCandidates("/workspace/a.gif", "/workspace/imgs"),
        )
        assertEquals(
            listOf("/workspace/a.gif"),
            buildWorkspaceImageCandidates("workspace://a.gif", "/workspace/imgs"),
        )
    }

    @Test
    fun `non workspace absolute path is single candidate`() {
        assertEquals(
            listOf("/root/out/a.gif"),
            buildWorkspaceImageCandidates("/root/out/a.gif", "/workspace/imgs"),
        )
    }

    @Test
    fun `url input yields no candidates`() {
        assertEquals(emptyList<String>(), buildWorkspaceImageCandidates("https://example.com/a.gif", "/workspace"))
    }

    // ---- joinRootfsPath ----

    @Test
    fun `join keeps absolute bare`() {
        assertEquals("/workspace/a.gif", joinRootfsPath("/workspace/imgs", "/workspace/a.gif"))
    }

    @Test
    fun `join trims trailing slash of cwd`() {
        assertEquals("/workspace/imgs/a.gif", joinRootfsPath("/workspace/imgs/", "a.gif"))
    }
}
