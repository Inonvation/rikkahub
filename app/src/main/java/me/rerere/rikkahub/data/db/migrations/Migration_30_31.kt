package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_30_31"

val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(30, 31)
        try {
            db.execSQL("ALTER TABLE vocabulary ADD COLUMN archived INTEGER DEFAULT 0 NOT NULL")
            db.execSQL("ALTER TABLE wrong_questions ADD COLUMN archived INTEGER DEFAULT 0 NOT NULL")
            db.execSQL("ALTER TABLE knowledge_cards ADD COLUMN archived INTEGER DEFAULT 0 NOT NULL")
            db.execSQL("ALTER TABLE study_notes ADD COLUMN archived INTEGER DEFAULT 0 NOT NULL")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}