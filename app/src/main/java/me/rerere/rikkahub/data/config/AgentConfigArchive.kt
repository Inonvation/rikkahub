package me.rerere.rikkahub.data.config

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * agent/ 目录的 zip 导出/导入（配置迁移）。
 *
 * - 导出：打包 agent/ 下全部配置文件（脱敏视图，密钥只有 keystore:* 引用），
 *   排除 .tmp 与 backups/ 快照；
 * - 导入：解压到 agent/，逐条目走 [AgentConfigPaths.resolveAllowed] 白名单校验，
 *   越权/未知条目跳过；导入后可用 [AgentConfigImporter] 应用到 DataStore。
 *
 * 密钥不随包迁移（跨设备无法解密 Keystore 密钥），导入后需重新填写密钥。
 */
object AgentConfigArchive {

    /** 导出到文件（测试/本地用）。 */
    fun exportZipToFile(agentRoot: File, target: File): Boolean = runCatching {
        target.outputStream().buffered().use { exportZip(agentRoot, it) }
        true
    }.getOrDefault(false)

    /** 导出到输出流（SAF 用）。成功返回 true。 */
    fun exportZip(agentRoot: File, out: OutputStream): Boolean = runCatching {
        ZipOutputStream(out.buffered()).use { zip ->
            agentRoot.walkTopDown().forEach { f ->
                if (f.isFile && !f.name.endsWith(".tmp")) {
                    val rel = f.relativeTo(agentRoot).path.replace(File.separatorChar, '/')
                    if (rel.startsWith("backups/")) return@forEach
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        true
    }.getOrDefault(false)

    /**
     * 导入 zip 到 agent/（白名单校验，越权/未知条目跳过）。
     * 返回导入文件数；**zip 损坏/IO 失败返回 -1**（调用方据此区分"导入 0 个"与"导入失败"）。
     */
    fun importZip(agentRoot: File, zipFile: File): Int {
        // ZipInputStream 对非 zip 垃圾输入可能静默当作 EOF，先校验 zip 魔数再解压
        if (!looksLikeZip(zipFile)) return -1
        return runCatching {
            var count = 0
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val target = AgentConfigPaths.resolveAllowed(agentRoot, entry.name)
                    if (target == null) {
                        // 越权/未知条目（backups/、.tmp 等）跳过
                        zip.closeEntry()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    count++
                    zip.closeEntry()
                }
            }
            count
        }.getOrDefault(-1)
    }

    /** zip 魔数校验：PK\x03\x04（普通条目）/ PK\x05\x06（空 zip）/ PK\x07\x08（spanned）。 */
    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            read == 4 &&
                header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte() &&
                (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
        }
    }.getOrDefault(false)
}
