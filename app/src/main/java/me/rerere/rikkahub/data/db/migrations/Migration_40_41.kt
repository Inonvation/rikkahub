package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_40_41"

val Migration_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(40, 41)
        db.beginTransaction()
        try {
            // 文件来源标记：chat=聊天附件 avatar=头像，用于文件管理按来源区分
            db.execSQL(
                "ALTER TABLE `managed_files` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'chat'"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
