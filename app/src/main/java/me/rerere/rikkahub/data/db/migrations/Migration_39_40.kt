package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_39_40"

val Migration_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(39, 40)
        db.beginTransaction()
        try {
            // 子代理用量记录写缓存 token（cache write）。聊天底部栏缓存命中率按
            // 「全部输入 - 写缓存」剔除写缓存，缺此列时写缓存会被算进分母、命中率偏低。
            db.execSQL("ALTER TABLE `subagent_task_usage` ADD COLUMN `cache_write_tokens` INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
