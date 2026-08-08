package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "S3Sync"

class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val database: AppDatabase,
) {
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(
        config: S3Config,
        onProgress: ((String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, onProgress)
        val client = getS3Client(config)
        val keyPrefix = "rikkahub_backups/${file.name}"

        if (BackupSplitter.needsSplit(file)) {
            // 大文件分片上传（规避单文件上限，如 413）
            onProgress?.invoke("文件较大，正在分片上传…")
            val splitDir = File(context.cacheDir, "split_${System.currentTimeMillis()}")
            splitDir.mkdirs()
            try {
                val parts = BackupSplitter.split(file, splitDir)
                parts.forEachIndexed { index, part ->
                    onProgress?.invoke("正在上传分片 ${index + 1}/${parts.size}…")
                    client.putObject(
                        key = "${keyPrefix}.part${index + 1}",
                        file = part,
                        contentType = "application/octet-stream"
                    ).getOrThrow()
                }
                Log.i(TAG, "backupToS3: Uploaded ${file.name} in ${parts.size} parts (${file.length().fileSizeToString()})")
            } finally {
                splitDir.deleteRecursively()
            }
        } else {
            onProgress?.invoke("正在上传备份文件…")
            client.putObject(
                key = keyPrefix,
                file = file,
                contentType = "application/zip"
            ).getOrThrow()
            Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        }

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val result = client.listObjects(
            prefix = "rikkahub_backups/",
            maxKeys = 1000
        ).getOrThrow()

        // 分片 backup_x.zip.partN 聚合为父条目；常规 backup_x.zip 单独成条
        val all = result.objects
            .filter { it.key.startsWith("rikkahub_backups/backup_") }
        val parts = all.filter { BackupSplitter.partIndex(it.key.substringAfterLast("/")) != null }
        val partGroups = parts.groupBy { it.key.substringBeforeLast(".part") }

        all
            .mapNotNull { obj ->
                val baseName = obj.key.substringAfterLast("/")
                val group = partGroups[obj.key] ?: emptyList()
                if (BackupSplitter.partIndex(baseName) != null) {
                    null
                } else {
                    S3BackupItem(
                        key = obj.key,
                        displayName = baseName,
                        size = group.sumOf { it.size }.takeIf { group.isNotEmpty() } ?: obj.size,
                        lastModified = (group.mapNotNull { it.lastModified }.maxOrNull())
                            ?: obj.lastModified ?: Instant.EPOCH
                    )
                }
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(
        config: S3Config,
        item: S3BackupItem,
        onProgress: ((String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // 检查是否为分片备份
            val result = client.listObjects(
                prefix = "rikkahub_backups/${item.displayName}.part",
                maxKeys = 1000
            ).getOrThrow()
            val partObjects = result.objects.sortedBy {
                BackupSplitter.partIndex(it.key.substringAfterLast("/"))
            }

            if (partObjects.isNotEmpty()) {
                onProgress?.invoke("正在下载备份分片…")
                val splitDir = File(context.cacheDir, "split_${System.currentTimeMillis()}")
                splitDir.mkdirs()
                try {
                    val downloadedParts = partObjects.mapIndexed { index, part ->
                        val localPart = File(splitDir, part.key.substringAfterLast("/"))
                        onProgress?.invoke("正在下载分片 ${index + 1}/${partObjects.size}…")
                        client.downloadObjectToFile(part.key, localPart).getOrThrow()
                        localPart
                    }
                    onProgress?.invoke("正在合并分片…")
                    BackupSplitter.merge(downloadedParts, backupFile)
                    Log.i(TAG, "restoreFromS3: Merged ${partObjects.size} parts to ${backupFile.length().fileSizeToString()}")
                } finally {
                    splitDir.deleteRecursively()
                }
            } else {
                Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
                onProgress?.invoke("正在下载备份文件…")
                client.downloadObjectToFile(item.key, backupFile).getOrThrow()
                Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")
            }

            // Restore from backup file
            restoreFromBackupFile(backupFile, config, onProgress)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val result = client.listObjects(
            prefix = "rikkahub_backups/${item.displayName}.part",
            maxKeys = 1000
        ).getOrThrow()
        val parts = result.objects
        if (parts.isNotEmpty()) {
            parts.forEach { client.deleteObject(it.key).getOrThrow() }
            Log.i(TAG, "deleteS3BackupFile: Deleted ${item.displayName} (${parts.size} parts)")
        } else {
            client.deleteObject(item.key).getOrThrow()
            Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
        }
    }

    suspend fun prepareBackupFile(
        config: S3Config,
        onProgress: ((String) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            onProgress?.invoke("正在导出设置…")
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            // Backup database files
            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                onProgress?.invoke("正在导出聊天记录…")
                database.checkpointWal()
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, "rikka_hub.db")
                }

                val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                if (walFile.exists()) {
                    addFileToZip(zipOut, walFile, "rikka_hub-wal")
                }

                val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                if (shmFile.exists()) {
                    addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                }
            }

            // Backup chat files (upload folder)
            if (config.items.contains(S3Config.BackupItem.CHAT_FILES) ||
                config.items.contains(S3Config.BackupItem.FILES)
            ) {
                onProgress?.invoke("正在导出聊天附件…")
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                }
            }

            // Backup skills
            if (config.items.contains(S3Config.BackupItem.SKILLS)) {
                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    onProgress?.invoke("正在导出技能…")
                    Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = "${FileFolders.SKILLS}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                }
            }

            // Backup fonts
            if (config.items.contains(S3Config.BackupItem.FONTS) ||
                config.items.contains(S3Config.BackupItem.FILES)
            ) {
                val fontsFolder = File(context.filesDir, FileFolders.FONTS)
                if (fontsFolder.exists() && fontsFolder.isDirectory) {
                    onProgress?.invoke("正在导出字体…")
                    Log.i(TAG, "prepareBackupFile: Backing up fonts from ${fontsFolder.absolutePath}")
                    fontsFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.FONTS}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Fonts folder does not exist or is not a directory")
                }
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: S3Config,
        onProgress: ((String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                    when (zipEntry.name) {
                        "settings.json" -> {
                            val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                            Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                            try {
                                val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                val settings = json.decodeFromString<Settings>(migratedJson)
                                settingsStore.update(settings)
                                Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                throw Exception("Failed to restore settings: ${e.message}")
                            }
                        }

                        "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                                val dbFile = when (zipEntry.name) {
                                    "rikka_hub.db" -> context.getDatabasePath("rikka_hub")
                                    "rikka_hub-wal" -> File(
                                        context.getDatabasePath("rikka_hub").parentFile,
                                        "rikka_hub-wal"
                                    )

                                    "rikka_hub-shm" -> File(
                                        context.getDatabasePath("rikka_hub").parentFile,
                                        "rikka_hub-shm"
                                    )

                                    else -> null
                                }

                                dbFile?.let { targetFile ->
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring ${zipEntry.name} to ${targetFile.absolutePath}"
                                    )
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                    )
                                }
                            }
                        }

                        else -> {
                            if ((config.items.contains(S3Config.BackupItem.FILES) ||
                                    config.items.contains(S3Config.BackupItem.CHAT_FILES)) &&
                                zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                            ) {
                                val fileName = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                if (fileName.isNotEmpty()) {
                                    val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                                    if (!uploadFolder.exists()) {
                                        uploadFolder.mkdirs()
                                        Log.i(TAG, "restoreFromBackupFile: Created upload directory")
                                    }

                                    val targetFile = safeResolveWithin(uploadFolder, fileName)
                                    if (targetFile == null) {
                                        // 恶意/损坏备份的路径穿越条目：跳过，绝不写出目标目录
                                        Log.w(
                                            TAG,
                                            "restoreFromBackupFile: Skipping unsafe upload entry ${zipEntry.name} (path traversal)"
                                        )
                                        zipIn.closeEntry()
                                        return@let
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring file ${zipEntry.name} to ${targetFile.absolutePath}"
                                    )

                                    try {
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "restoreFromBackupFile: Failed to restore file ${zipEntry.name}", e)
                                        throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                    }
                                }
                            } else if ((config.items.contains(S3Config.BackupItem.FILES) ||
                                    config.items.contains(S3Config.BackupItem.SKILLS)) &&
                                zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                            ) {
                                restoreSkillEntry(zipIn, zipEntry.name)
                            } else if ((config.items.contains(S3Config.BackupItem.FILES) ||
                                    config.items.contains(S3Config.BackupItem.FONTS)) &&
                                zipEntry.name.startsWith("${FileFolders.FONTS}/")
                            ) {
                                val fileName = zipEntry.name.substringAfter("${FileFolders.FONTS}/")
                                if (fileName.isNotEmpty() && !fileName.contains('/')) {
                                    val fontsFolder = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
                                    val targetFile = safeResolveWithin(fontsFolder, fileName)
                                        ?: run {
                                            Log.w(
                                                TAG,
                                                "restoreFromBackupFile: Skipping unsafe font entry ${zipEntry.name} (path traversal)"
                                            )
                                            zipIn.closeEntry()
                                            return@let
                                        }
                                    FileOutputStream(targetFile).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                    )
                                }
                            } else {
                                Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                            }
                        }
                    }

                    zipIn.closeEntry()
                }
            }
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
    }

    /**
     * 防 Zip-Slip 路径穿越：把 zip 内的相对路径解析到 root 之下，
     * 解析结果越出 root（含 ".." 或绝对路径）时返回 null，调用方应跳过该条目。
     */
    private fun safeResolveWithin(root: File, relativePath: String): File? {
        val target = File(root, relativePath)
        val rootCanonical = try {
            root.canonicalPath
        } catch (e: Exception) {
            return null
        }
        val targetCanonical = try {
            target.canonicalPath
        } catch (e: Exception) {
            return null
        }
        if (targetCanonical == rootCanonical || targetCanonical.startsWith(rootCanonical + File.separator)) {
            return target
        }
        Log.w(TAG, "safeResolveWithin: blocked path traversal: $relativePath (root=$rootCanonical)")
        return null
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
