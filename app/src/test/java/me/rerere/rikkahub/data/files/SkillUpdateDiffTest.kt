package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillUpdateDiffTest {

    private fun manifest(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun changeSetDetectsAddedModifiedRemoved() {
        val manifest = mapOf(
            "SKILL.md" to "sha-old",
            "assets/a.png" to "sha-same",
            "refs/old.md" to "sha-x",
        )
        val remote = mapOf(
            "SKILL.md" to "sha-new",
            "assets/a.png" to "sha-same",
            "refs/new.md" to "sha-y",
        )
        val changes = SkillUpdateDiff.computeChangeSet(manifest, remote)
        // 结果按路径排序（SKILL.md < refs/...）
        assertEquals(
            listOf(
                FileChange.Modified("SKILL.md"),
                FileChange.Added("refs/new.md"),
                FileChange.Removed("refs/old.md"),
            ),
            changes,
        )
    }

    @Test
    fun changeSetEmptyWhenIdentical() {
        val m = mapOf("SKILL.md" to "a", "x" to "b")
        assertTrue(SkillUpdateDiff.computeChangeSet(m, mapOf("x" to "b", "SKILL.md" to "a")).isEmpty())
    }

    @Test
    fun changeSetWithEmptyManifestDegradesToFullDownload() {
        // computeChangeSet 本身的语义：空清单 → 全部远端文件记为 Added。
        // 实际更新链路上，无清单旧记录改走 computeChangeSetFromDisk（磁盘基线），不再全量下载
        val remote = mapOf("SKILL.md" to "a", "assets/x" to "b")
        val changes = SkillUpdateDiff.computeChangeSet(emptyMap(), remote)
        assertEquals(2, changes.size)
        assertTrue(changes.all { it is FileChange.Added })
    }

    @Test
    fun diskBaselineChangeSetOnlyDownloadsDivergentFiles() {
        // 旧记录无清单：以磁盘为基线，仅远端有而磁盘缺失或不一致的文件需要下载
        val disk = mapOf(
            "SKILL.md" to "sha-same",
            "assets/a.png" to "sha-old",
            "notes/user.txt" to "sha-user", // 用户新增：磁盘独有
        )
        val remote = mapOf(
            "SKILL.md" to "sha-same", // 一致：不下载
            "assets/a.png" to "sha-new", // 上游已改：下载
            "refs/new.md" to "sha-added", // 上游新增：下载
        )
        val changes = SkillUpdateDiff.computeChangeSetFromDisk(disk, remote)
        assertEquals(
            listOf(
                FileChange.Modified("assets/a.png"),
                FileChange.Added("refs/new.md"),
            ),
            changes,
        )
        // 磁盘独有文件绝不能判 Removed（无法区分用户新增/上游删除，保守保留）
        assertTrue(changes.none { it is FileChange.Removed })
    }

    @Test
    fun diskBaselineEmptyDiskStillFullDownload() {
        // 磁盘缺失/为空时没有可用基线：仍全量下载
        val remote = mapOf("SKILL.md" to "a", "assets/x" to "b")
        val changes = SkillUpdateDiff.computeChangeSetFromDisk(emptyMap(), remote)
        assertEquals(2, changes.size)
        assertTrue(changes.all { it is FileChange.Added })
    }

    @Test
    fun localStateFlagsModifiedTrackedButNotUserAdded() {
        val manifest = mapOf(
            "SKILL.md" to "sha1",
            "assets/a.png" to "sha2",
        )
        val disk = mapOf(
            // 用户改了 SKILL.md
            "SKILL.md" to "sha1-local-edit",
            // 未动 a.png
            "assets/a.png" to "sha2",
            // 用户新增文件：不算「本地已修改」
            "notes/local.txt" to "sha3",
        )
        val state = SkillUpdateDiff.computeLocalState(manifest, disk)
        assertEquals(listOf("SKILL.md"), state.modifiedTracked)
        assertEquals(listOf("notes/local.txt"), state.locallyAdded)
        assertTrue(state.hasModifiedTracked)
    }

    @Test
    fun localStateFlagsDeletedTrackedFile() {
        val manifest = mapOf("SKILL.md" to "sha1", "refs/old.md" to "sha2")
        val disk = mapOf("SKILL.md" to "sha1")
        val state = SkillUpdateDiff.computeLocalState(manifest, disk)
        assertEquals(listOf("refs/old.md"), state.modifiedTracked)
    }

    @Test
    fun localStateCleanWhenUserOnlyAddedFiles() {
        val manifest = mapOf("SKILL.md" to "sha1")
        val disk = mapOf("SKILL.md" to "sha1", "extra.txt" to "sha9")
        val state = SkillUpdateDiff.computeLocalState(manifest, disk)
        assertFalse(state.hasModifiedTracked)
        assertEquals(listOf("extra.txt"), state.locallyAdded)
    }
}

class SkillContentBlobShaTest {

    @Test
    fun matchesGitHashObjectKnownVectors() {
        // 与 `git hash-object --stdin` 结果对齐（增量变更检测依赖与 GitHub blob SHA 同源）
        assertEquals(
            "ce013625030ba8dba906f756967f9e9ca394464a",
            SkillContentHash.computeBlobSha("hello\n".toByteArray()),
        )
        assertEquals(
            "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391",
            SkillContentHash.computeBlobSha(byteArrayOf()),
        )
    }

    @Test
    fun blobShaChangesWithContent() {
        val a = SkillContentHash.computeBlobSha("a".toByteArray())
        val b = SkillContentHash.computeBlobSha("b".toByteArray())
        assertTrue(a != b)
    }

    @Test
    fun dirBlobShasMatchPerFileComputation() {
        val dir = java.nio.file.Files.createTempDirectory("skill-blob-sha").toFile()
        try {
            java.io.File(dir, "SKILL.md").writeText("hello\n")
            java.io.File(dir, "assets").mkdirs()
            java.io.File(dir, "assets/x.txt").writeText("world")

            val dirShas = SkillContentHash.computeDirBlobShas(dir)!!
            assertEquals(
                SkillContentHash.computeBlobSha("hello\n".toByteArray()),
                dirShas["SKILL.md"],
            )
            assertEquals(
                SkillContentHash.computeBlobSha("world".toByteArray()),
                dirShas["assets/x.txt"],
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
