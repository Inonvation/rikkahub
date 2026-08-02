package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_32_33"

val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        try {
            db.execSQL("ALTER TABLE wrong_questions ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
