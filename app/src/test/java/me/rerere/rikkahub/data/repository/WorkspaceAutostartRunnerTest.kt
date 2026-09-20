package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

/**
 * 自启动脚本纯逻辑测试：脚本发现（排序/启用停用识别）与文件名白名单。
 * 只覆盖 top-level 纯函数，不触碰 android.util.Log / WorkspaceManager，纯 JVM 可跑。
 */
class WorkspaceAutostartRunnerTest {

    @Test
    fun `parse keeps enabled sh and disabled sh and ignores others`() {
        val dir = Files.createTempDirectory("autostart").toFile()
        try {
            listOf("b.sh", "a.sh.disabled", "c.sh", "readme.txt", "d").forEach {
                File(dir, it).writeText("#!/bin/bash\n")
            }
            File(dir, "sub").mkdirs()

            val scripts = parseAutostartScripts(dir.listFiles()!!.toList())

            assertEquals(listOf("b.sh", "c.sh"), scripts.filter { it.enabled }.map { it.fileName })
            assertEquals(listOf("a.sh.disabled"), scripts.filterNot { it.enabled }.map { it.fileName })
            assertEquals(listOf("a", "b", "c"), scripts.map { it.displayName })
            // 总体按文件名排序
            assertEquals(listOf("a.sh.disabled", "b.sh", "c.sh"), scripts.map { it.fileName })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parse empty dir returns empty`() {
        val dir = Files.createTempDirectory("autostart").toFile()
        try {
            assertTrue(parseAutostartScripts(dir.listFiles()?.toList().orEmpty()).isEmpty())
            assertTrue(parseAutostartScripts(emptyList()).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parse reports size and enabled flag`() {
        val dir = Files.createTempDirectory("autostart").toFile()
        try {
            val f = File(dir, "job.sh")
            f.writeText("echo hi\n")
            val script = parseAutostartScripts(listOf(f)).single()
            assertTrue(script.enabled)
            assertEquals("job", script.displayName)
            assertEquals(f.length(), script.sizeBytes)
            assertEquals(f.lastModified(), script.updatedAt)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parse classifies service scripts`() {
        val dir = Files.createTempDirectory("autostart").toFile()
        try {
            File(dir, "web.service.sh").writeText("echo up\n")
            File(dir, "old.service.sh.disabled").writeText("echo down\n")
            File(dir, "job.sh").writeText("echo one\n")

            val scripts = parseAutostartScripts(dir.listFiles()!!.toList())

            val service = scripts.single { it.fileName == "web.service.sh" }
            assertTrue(service.service)
            assertTrue(service.enabled)
            assertEquals("web", service.displayName)

            val disabled = scripts.single { it.fileName == "old.service.sh.disabled" }
            assertTrue(disabled.service)
            assertTrue(!disabled.enabled)
            assertEquals("old", disabled.displayName)

            val oneshot = scripts.single { it.fileName == "job.sh" }
            assertTrue(!oneshot.service)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `script name accepts valid names and appends sh suffix`() {
        assertEquals("mirror.sh", autostartScriptFileName("mirror"))
        assertEquals("mirror.sh", autostartScriptFileName("mirror.sh"))
        assertEquals("mirror.sh", autostartScriptFileName(" mirror.sh "))
        assertEquals("my-job_2.sh", autostartScriptFileName("my-job_2"))
        assertEquals("v1.2.sh", autostartScriptFileName("v1.2.sh"))
        assertEquals("web.service.sh", autostartScriptFileName("web.service"))
        assertEquals("web.service.sh", autostartScriptFileName("web.service.sh"))
    }

    @Test
    fun `script name rejects invalid input`() {
        assertNull(autostartScriptFileName(""))
        assertNull(autostartScriptFileName("   "))
        assertNull(autostartScriptFileName(".sh"))
        assertNull(autostartScriptFileName(".."))
        assertNull(autostartScriptFileName("a/b"))
        assertNull(autostartScriptFileName("../evil"))
        assertNull(autostartScriptFileName("a b"))
        assertNull(autostartScriptFileName("中文"))
        assertNull(autostartScriptFileName("a;b"))
        assertNull(autostartScriptFileName("a'b"))
    }
}
