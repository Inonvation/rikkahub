package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_36_37"

val Migration_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(36, 37)
        db.beginTransaction()
        try {
            // 群组独立实体：id / name / config_json(DiscussionConfig) / 时间戳
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `groupentity` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `config_json` TEXT NOT NULL DEFAULT '',
                    `create_at` INTEGER NOT NULL,
                    `update_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`))"""
            )
            // 会话关联群组
            db.execSQL(
                "ALTER TABLE `conversationentity` ADD COLUMN `group_id` TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversationentity_group_id` ON `conversationentity` (`group_id`)"
            )
            // 旧群组会话（哨兵 assistantId + discussion_json 非空）→ 每条约一个 Group（group id = 会话 id）
            db.execSQL(
                """INSERT INTO `groupentity` (`id`, `name`, `config_json`, `create_at`, `update_at`)
                    SELECT `id`, `title`, `discussion_json`, `create_at`, `update_at`
                    FROM `conversationentity`
                    WHERE `assistant_id` = '00000000-0000-0000-0000-0000000000dd' AND `discussion_json` != ''"""
            )
            // 打标记 + 清空旧配置（单一数据源：配置只在 groupentity.config_json）
            db.execSQL(
                """UPDATE `conversationentity`
                    SET `group_id` = `id`, `discussion_json` = ''
                    WHERE `assistant_id` = '00000000-0000-0000-0000-0000000000dd' AND `discussion_json` != ''"""
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
