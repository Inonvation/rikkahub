package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_35_36"

val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(35, 36)
        try {
            db.execSQL(
                "ALTER TABLE `conversationentity` ADD COLUMN `discussion_json` TEXT NOT NULL DEFAULT ''"
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
