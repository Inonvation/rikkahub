package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_31_32"

val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(31, 32)
        try {
            db.execSQL("ALTER TABLE study_notes ADD COLUMN subject TEXT NOT NULL DEFAULT 'other'")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
