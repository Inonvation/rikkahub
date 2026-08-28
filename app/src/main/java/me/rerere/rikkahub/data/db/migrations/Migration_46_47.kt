package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_46_47"

/** 新增上下文构成快照表（每会话一行，见 ContextCompositionEntity） */
val Migration_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(46, 47)
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `context_composition` (
                    `conversation_id` TEXT NOT NULL,
                    `system_tokens` INTEGER NOT NULL,
                    `builtin_tool_tokens` INTEGER NOT NULL,
                    `mcp_tool_tokens` INTEGER NOT NULL,
                    `skill_tool_tokens` INTEGER NOT NULL,
                    `message_tokens` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`conversation_id`)
                )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}