package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_52_53"

/**
 * 压缩机制从「上下文快照」改为「插入摘要检查点」后的旧列清理。
 *
 * 旧实现把压缩结果存进 `conversationentity.compressed_json`（摘要 + 保留尾部），
 * 新实现改为在消息流里插入一条带 `isContextCheckpoint` 标记的摘要消息，原消息
 * 原样保留。该列不再有读写方，删除以免继续携带陈旧快照（重进会话时可能被
 * 误当成有效上下文）。
 *
 * 用 `DROP COLUMN` 而非重建表：`message_node` 以 `ON DELETE CASCADE` 外键引用本表，
 * 重建过程中的 `DROP TABLE` 会级联删掉整表消息（SQLite 在外键开启时对 DROP TABLE
 * 执行隐式 DELETE）。App 运行时用 requery 自带 SQLite（3.50），支持 DROP COLUMN。
 */
val Migration_52_53 = object : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(52, 53)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `conversationentity` DROP COLUMN `compressed_json`")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
