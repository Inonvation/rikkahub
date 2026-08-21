package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_44_45"

val Migration_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(44, 45)
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE `conversationentity` ADD COLUMN `compressed_json` TEXT NOT NULL DEFAULT ''"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
