package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_42_43"

/**
 * 42 -> 43：会话新增能力模式快照列。
 * `mode` 存内置模式枚举名或 `custom:<id>`，空串表示未显式设置（生成期按助手/全局解析）。
 * 仅新增列，不触碰消息数据，迁移幂等安全。
 */
val Migration_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(42, 43)
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE `conversationentity` ADD COLUMN `mode` TEXT NOT NULL DEFAULT ''"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
