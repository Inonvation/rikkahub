package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 大文件客户端分片工具。
 *
 * 背景：坚果云 WebDAV 单文件上限 500MB、GitHub 100MB，整包 zip 备份可能超限（413）。
 * 方案：把大 zip 切成多个 <= [MAX_PART_BYTES] 的分片，分别上传为 `xxx.zip.part<N>`，
 * 恢复时按序下载并合并回完整 zip。纯客户端逻辑，不依赖服务器分片能力。
 */
object BackupSplitter {
    /** 分片大小上限：预留余量，确保任一单独分片远低于 500MB 限制。 */
    const val MAX_PART_BYTES = 400L * 1024 * 1024 // 400MB

    /** 文件名 → 分片名，如 `backup_x.zip.part1`。 */
    fun partName(baseName: String, index: Int): String = "$baseName.part$index"

    /** 分片名 → 序号；非分片返回 null。 */
    fun partIndex(partName: String): Int? {
        val marker = ".part"
        val idx = partName.lastIndexOf(marker)
        if (idx < 0) return null
        return partName.substring(idx + marker.length).toIntOrNull()
    }

    /** 判断是否需要分片。 */
    fun needsSplit(file: File): Boolean = file.length() > MAX_PART_BYTES

    /** 切分文件为多个分片（输出到 [outDir]，命名 [partName])。返回分片列表。 */
    fun split(file: File, outDir: File): List<File> {
        val parts = mutableListOf<File>()
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            var partIndex = 1
            var current = File(outDir, partName(file.name, partIndex))
            var currentOut = FileOutputStream(current)
            var written = 0L
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                currentOut.write(buf, 0, read)
                written += read
                if (written >= MAX_PART_BYTES) {
                    currentOut.close()
                    parts += current
                    partIndex++
                    current = File(outDir, partName(file.name, partIndex))
                    currentOut = FileOutputStream(current)
                    written = 0L
                }
            }
            currentOut.close()
            if (current.length() > 0 || parts.isEmpty()) {
                parts += current
            }
        }
        // 若最后一个分片恰好为空文件则剔除
        return parts.filter { it.length() > 0 }
    }

    /** 按分片名序号合并为完整文件。 */
    fun merge(parts: List<File>, target: File) {
        val sorted = parts.sortedBy { partIndex(it.name) ?: Int.MAX_VALUE }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            sorted.forEach { part ->
                FileInputStream(part).use { input ->
                    input.copyTo(out)
                }
            }
        }
    }
}
