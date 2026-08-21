package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "management_audit")
data class ManagementAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("timestamp")
    val timestamp: Long,
    @ColumnInfo("tool")
    val tool: String,
    @ColumnInfo("target")
    val target: String,
    @ColumnInfo("result")
    val result: String,
    @ColumnInfo("detail")
    val detail: String = "",
)
