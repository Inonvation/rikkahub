package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_34_35"

val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(34, 35)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `subagent_task` (
                    `task_id` TEXT NOT NULL,
                    `parent_conv` TEXT NOT NULL,
                    `agent_id` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `task_json` TEXT NOT NULL,
                    PRIMARY KEY(`task_id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_subagent_task_parent_conv` " +
                    "ON `subagent_task` (`parent_conv`)"
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
