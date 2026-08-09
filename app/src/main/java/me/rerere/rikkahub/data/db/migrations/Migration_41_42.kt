package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_41_42"

/**
 * 41 → 42：会话增量同步版本列。
 * `sync_updated_at` 为会话的单调时钟版本号（0 = 存量未参与同步），
 * 仅新增列，不触碰消息数据，迁移幂等安全。
 */
val Migration_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(41, 42)
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE `conversationentity` ADD COLUMN `sync_updated_at` INTEGER NOT NULL DEFAULT 0"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
