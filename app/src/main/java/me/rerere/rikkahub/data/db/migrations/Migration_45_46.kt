package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_45_46"

val Migration_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(45, 46)
        db.beginTransaction()
        try {
            // 记忆分类（MemoryCategory 枚举名），历史数据为 NULL
            db.execSQL(
                "ALTER TABLE `memoryentity` ADD COLUMN `category` TEXT DEFAULT NULL"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
