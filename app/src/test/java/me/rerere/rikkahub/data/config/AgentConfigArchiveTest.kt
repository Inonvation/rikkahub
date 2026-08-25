package me.rerere.rikkahub.data.config

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

class AgentConfigArchiveTest {

    private fun tempAgentRoot(): Pair<File, () -> Unit> {
        val dir = Files.createTempDirectory("agent-archive-test").toFile()
        return dir to { dir.deleteRecursively() }
    }

    private fun sampleSettings(): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(
                name = "OpenAI",
                apiKey = "sk-secret",
                baseUrl = "https://api.openai.com/v1",
            )
        ),
        assistants = listOf(me.rerere.rikkahub.data.model.Assistant(name = "助手")),
    )

    @Test
    fun exportZipExcludesTmpAndBackups() {
        val (root, cleanup) = tempAgentRoot()
        try {
            AgentConfigExporter.export(sampleSettings(), root)
            // 制造 .tmp 与 backups 干扰
            File(root, "config/providers.json.tmp").writeText("junk")
            File(root, "backups").mkdirs().let { File(root, "backups/x.bak").writeText("junk") }

            val zip = File(root.parentFile, "export-test.zip")
            assertTrue(AgentConfigArchive.exportZipToFile(root, zip))

            val names = mutableListOf<String>()
            java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    names += e.name
                    z.closeEntry()
                }
            }
            assertTrue(names.contains(AgentConfigPaths.PROVIDERS_FILE))
            assertFalse(names.any { it.endsWith(".tmp") })
            assertFalse(names.any { it.startsWith("backups/") })
        } finally {
            cleanup()
        }
    }

    @Test
    fun importZipRestoresFilesAndSkipsTraversal() {
        val (root, cleanup) = tempAgentRoot()
        try {
            AgentConfigExporter.export(sampleSettings(), root)
            val zip = File(root.parentFile, "export-test.zip")
            assertTrue(AgentConfigArchive.exportZipToFile(root, zip))

            // 清空后导入
            root.deleteRecursively()
            root.mkdirs()
            val count = AgentConfigArchive.importZip(root, zip)
            assertTrue(count >= 3) // providers/mcp/manifest/assistants 等
            assertTrue(File(root, AgentConfigPaths.PROVIDERS_FILE).isFile)
            assertTrue(File(root, AgentConfigPaths.MCP_FILE).isFile)
            assertTrue(File(root, AgentConfigPaths.ASSISTANTS_DIR).isDirectory)
        } finally {
            cleanup()
        }
    }

    @Test
    fun importZipSkipsUnknownAndEvilEntries() {
        val (root, cleanup) = tempAgentRoot()
        try {
            root.mkdirs()
            val zip = File(root.parentFile, "evil.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("config/providers.json"))
                z.write("""{"schemaVersion":1,"providers":[]}""".toByteArray())
                z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("../evil.txt"))
                z.write("evil".toByteArray())
                z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("backups/sneaky.bak"))
                z.write("sneaky".toByteArray())
                z.closeEntry()
            }
            val count = AgentConfigArchive.importZip(root, zip)
            assertEquals(1, count)
            assertTrue(File(root, AgentConfigPaths.PROVIDERS_FILE).isFile)
            assertFalse(File(root, "evil.txt").exists())
            assertFalse(File(root, "backups").exists())
        } finally {
            cleanup()
        }
    }

    @Test
    fun importZipReturnsMinusOneForCorruptedZip() {
        val (root, cleanup) = tempAgentRoot()
        try {
            root.mkdirs()
            val bad = File(root.parentFile, "corrupt.zip")
            bad.writeText("this is not a zip file at all")

            // 损坏 zip：-1 表示失败，而不是被误报为"成功导入 0 个"
            assertEquals(-1, AgentConfigArchive.importZip(root, bad))
        } finally {
            cleanup()
        }
    }
}
