package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSkillClientTest {

    private val client = GitHubSkillClient()

    @Test
    fun parsesRepoRootUrl() {
        val info = client.parseGitHubUrl("https://github.com/owner/repo")
        assertEquals("owner", info?.owner)
        assertEquals("repo", info?.repo)
        // 无 /tree/branch → 跟随默认分支
        assertEquals("", info?.branch)
        assertEquals("", info?.path)
    }

    @Test
    fun parsesBranchUrl() {
        val info = client.parseGitHubUrl("https://github.com/owner/repo/tree/main/")
        assertEquals("main", info?.branch)
        assertEquals("", info?.path)
    }

    @Test
    fun parsesBranchAndSubdirUrl() {
        val info = client.parseGitHubUrl("https://github.com/owner/repo/tree/dev/skills/my-skill")
        assertEquals("dev", info?.branch)
        assertEquals("skills/my-skill", info?.path)
    }

    @Test
    fun stripsQueryFragmentDotGitAndWww() {
        // 浏览器复制的地址常带 query/fragment
        val withQuery = client.parseGitHubUrl("https://github.com/owner/repo?tab=readme-ov-file")
        assertEquals("repo", withQuery?.repo)

        val withFragment = client.parseGitHubUrl("https://github.com/owner/repo/tree/main#readme")
        assertEquals("main", withFragment?.branch)

        // clone 地址带 .git
        val dotGit = client.parseGitHubUrl("https://github.com/owner/repo.git")
        assertEquals("repo", dotGit?.repo)

        // www 前缀
        val www = client.parseGitHubUrl("https://www.github.com/owner/repo")
        assertEquals("repo", www?.repo)
    }

    @Test
    fun parsesBlobFileUrlAsParentDir() {
        // 从网页点进 SKILL.md 复制的文件地址 → 取所在目录
        val info = client.parseGitHubUrl("https://github.com/owner/repo/blob/main/skills/my-skill/SKILL.md")
        assertEquals("main", info?.branch)
        assertEquals("skills/my-skill", info?.path)

        // 根目录文件
        val rootFile = client.parseGitHubUrl("https://github.com/owner/repo/blob/main/SKILL.md")
        assertEquals("", rootFile?.path)
    }

    @Test
    fun rejectsNonGithubUrls() {
        assertNull(client.parseGitHubUrl("https://gitlab.com/owner/repo"))
        assertNull(client.parseGitHubUrl("not a url"))
        assertNull(client.parseGitHubUrl("https://github.com/owner"))
    }

    @Test
    fun parsesCommitsResponse() {
        val json = """[{"sha":"abc123","commit":{"message":"x"}}]"""
        assertEquals("abc123", client.parseCommitsResponse(json))
    }

    @Test
    fun handlesEmptyAndMalformedCommitsResponse() {
        assertNull(client.parseCommitsResponse("[]"))
        assertNull(client.parseCommitsResponse(null))
        assertNull(client.parseCommitsResponse(""))
        assertNull(client.parseCommitsResponse("not json"))
        assertNull(client.parseCommitsResponse("[{\"no_sha\":1}]"))
    }

    @Test
    fun findSkillRootsAtRepoRoot() {
        val paths = listOf("SKILL.md", "assets/a.png", "refs.md")
        assertEquals(listOf(""), client.findSkillRoots(paths))
    }

    @Test
    fun findsMultipleTopLevelSkillRoots() {
        val paths = listOf(
            "docs/README.md",
            "skill-a/SKILL.md",
            "skill-a/assets/x.txt",
            "skill-b/SKILL.md",
            "skill-b/nested/deep/SKILL.md", // 嵌套在 skill-b 内，不是独立技能根
        )
        // 只扫直接子目录一层；skill-b 的嵌套 SKILL.md 不产生额外根
        assertEquals(listOf("skill-a", "skill-b"), client.findSkillRoots(paths))
    }

    @Test
    fun findsNoneWhenNoSkillMd() {
        assertEquals(emptyList<String>(), client.findSkillRoots(listOf("README.md", "src/main.kt")))
    }

    @Test
    fun ignoresDeeplyNestedSkillMdWhenNoRootSkill() {
        // 仅深层嵌套（examples/…）时不算技能根，避免误收示例
        assertEquals(emptyList<String>(), client.findSkillRoots(listOf("examples/demo/SKILL.md")))
    }
}
