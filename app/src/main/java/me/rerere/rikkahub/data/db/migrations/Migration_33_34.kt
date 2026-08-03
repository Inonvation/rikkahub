package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_33_34"

val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(33, 34)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `subagent_task_usage` (
                    `task_id` TEXT NOT NULL,
                    `conversation_id` TEXT NOT NULL,
                    `agent_id` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `prompt_tokens` INTEGER NOT NULL,
                    `completion_tokens` INTEGER NOT NULL,
                    `cached_tokens` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    PRIMARY KEY(`task_id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_subagent_task_usage_conversation_id` " +
                    "ON `subagent_task_usage` (`conversation_id`)"
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
