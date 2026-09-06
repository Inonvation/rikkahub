package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillContentHashTest {

    @Test
    fun sameContentSameHashRegardlessOfMapOrder() {
        val a = mapOf(
            "SKILL.md" to "hello".toByteArray(),
            "assets/x.txt" to "world".toByteArray(),
        )
        val b = mapOf(
            "assets/x.txt" to "world".toByteArray(),
            "SKILL.md" to "hello".toByteArray(),
        )
        assertEquals(SkillContentHash.computeFilesHash(a), SkillContentHash.computeFilesHash(b))
    }

    @Test
    fun contentChangeChangesHash() {
        val base = mapOf("SKILL.md" to "hello".toByteArray())
        assertEquals(
            SkillContentHash.computeFilesHash(base),
            SkillContentHash.computeFilesHash(mapOf("SKILL.md" to "hello".toByteArray())),
        )
        assertNotEquals(
            SkillContentHash.computeFilesHash(base),
            SkillContentHash.computeFilesHash(mapOf("SKILL.md" to "hello!".toByteArray())),
        )
        // 路径即内容：同名不同路径也算变化
        assertNotEquals(
            SkillContentHash.computeFilesHash(base),
            SkillContentHash.computeFilesHash(mapOf("other/SKILL.md" to "hello".toByteArray())),
        )
    }

    @Test
    fun dirHashMatchesFilesHashAndUsesInvariantSeparators() {
        val dir = Files.createTempDirectory("skill-hash").toFile()
        try {
            File(dir, "SKILL.md").writeText("hello")
            File(dir, "assets").mkdirs()
            File(dir, "assets/x.txt").writeText("world")

            val dirHash = SkillContentHash.computeDirHash(dir)
            val filesHash = SkillContentHash.computeFilesHash(
                mapOf(
                    "SKILL.md" to "hello".toByteArray(),
                    "assets/x.txt" to "world".toByteArray(),
                ),
            )
            assertEquals(filesHash, dirHash)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun dirHashOfMissingDirIsNull() {
        assertNull(SkillContentHash.computeDirHash(File("/nonexistent/path/for/test")))
    }

    @Test
    fun dirHashDetectsLocalExtraFile() {
        val dir = Files.createTempDirectory("skill-hash-extra").toFile()
        try {
            File(dir, "SKILL.md").writeText("hello")
            val installed = SkillContentHash.computeDirHash(dir)
            // 用户本地加了一个文件 → 指纹变化（本地已修改）
            File(dir, "notes.txt").writeText("local edit")
            assertNotEquals(installed, SkillContentHash.computeDirHash(dir))
            assertTrue(installed != null)
        } finally {
            dir.deleteRecursively()
        }
    }
}

class SkillSourceRegistryTest {

    private fun sampleSource(name: String = "my-skill") = SkillSource(
        skillName = name,
        repoOwner = "owner",
        repoName = "repo",
        branch = "main",
        path = "skills/$name",
        commitSha = "abc123",
        etag = "W/\"etag\"",
        contentHash = "deadbeef",
        autoUpdate = true,
        localModified = false,
        updateAvailable = true,
        remoteSha = "def456",
        installedAt = 1234L,
        lastCheckedAt = 5678L,
    )

    @Test
    fun roundTripPreservesAllFields() {
        val dir = Files.createTempDirectory("skill-registry").toFile()
        try {
            val registry = SkillSourceRegistry(File(dir, ".sources.json"))
            val source = sampleSource()
            registry.save(mapOf(source.skillName to source))

            val loaded = registry.load()
            assertEquals(1, loaded.size)
            assertEquals(source, loaded[source.skillName])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingFileLoadsEmpty() {
        val dir = Files.createTempDirectory("skill-registry-empty").toFile()
        try {
            assertTrue(SkillSourceRegistry(File(dir, ".sources.json")).load().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptedFileLoadsEmpty() {
        val dir = Files.createTempDirectory("skill-registry-corrupt").toFile()
        try {
            val file = File(dir, ".sources.json")
            file.writeText("{ not json")
            assertTrue(SkillSourceRegistry(file).load().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownJsonFieldsAreIgnored() {
        val dir = Files.createTempDirectory("skill-registry-unknown").toFile()
        try {
            val file = File(dir, ".sources.json")
            file.writeText(
                """[{"skillName":"a","repoOwner":"o","repoName":"r","branch":"","path":"",
                   "commitSha":"s","contentHash":"h","futureField":1}]""",
            )
            val loaded = SkillSourceRegistry(file).load()
            assertEquals(1, loaded.size)
            assertEquals("a", loaded["a"]?.skillName)
        } finally {
            dir.deleteRecursively()
        }
    }
}
