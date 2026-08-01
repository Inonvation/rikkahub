package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_27_28"

val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(27, 28)
        try {
            // 知识库检索配置
            db.execSQL("ALTER TABLE knowledge_base ADD COLUMN keyword_weight REAL DEFAULT 1.0 NOT NULL")
            db.execSQL("ALTER TABLE knowledge_base ADD COLUMN use_multiquery INTEGER DEFAULT 0 NOT NULL")
            db.execSQL("ALTER TABLE knowledge_base ADD COLUMN context_window INTEGER DEFAULT 0 NOT NULL")
            db.execSQL("ALTER TABLE knowledge_base ADD COLUMN mmr_lambda REAL DEFAULT 0.7 NOT NULL")

            // 分块上下文前缀（用于 Contextual Chunking）
            db.execSQL("ALTER TABLE knowledge_chunk ADD COLUMN context_prefix TEXT DEFAULT '' NOT NULL")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}