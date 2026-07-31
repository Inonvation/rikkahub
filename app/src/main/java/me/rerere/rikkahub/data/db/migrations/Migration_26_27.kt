package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_26_27"

val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(26, 27)
        try {
            // 为知识库文档增加文件 hash，用于重复文件去重
            db.execSQL("ALTER TABLE knowledge_document ADD COLUMN file_hash TEXT DEFAULT NULL")

            // 为知识库增加 HyDE 开关
            db.execSQL("ALTER TABLE knowledge_base ADD COLUMN use_hyde INTEGER DEFAULT 0 NOT NULL")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
