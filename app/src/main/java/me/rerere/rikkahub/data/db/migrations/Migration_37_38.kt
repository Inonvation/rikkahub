package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_37_38"

val Migration_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(37, 38)
        db.beginTransaction()
        try {
            // 记忆元数据：创建/更新时间（旧数据为 0，UI 判断 >0 才显示）
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `memoryentity` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
