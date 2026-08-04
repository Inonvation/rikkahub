package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_38_39"

val Migration_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(38, 39)
        db.beginTransaction()
        try {
            // 子代理用量记录实际计费模型 id（旧行为 null，费用统计时回退会话主模型）
            db.execSQL("ALTER TABLE `subagent_task_usage` ADD COLUMN `model_id` TEXT")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
