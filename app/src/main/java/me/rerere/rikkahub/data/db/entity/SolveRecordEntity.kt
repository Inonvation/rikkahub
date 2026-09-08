package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 拍照搜题历史记录。
 *
 * imagePath 存裁剪后落盘图片在应用私有目录下的相对路径（不含 filesDir 前缀），
 * 与记录生命周期绑定：删除记录时同步删除图片文件，避免私有目录残留孤儿文件。
 * questionText 仅在解题模型走 OCR 降级（无 vision 能力）时保存识别出的题干文本。
 * followUpsJson 存该题的多轮追问（JSON 数组字符串，默认 "[]"，元素含
 * question/reasoning/answer 三字段）——内嵌而非独立子表：追问数量少（个位数）、
 * 必须与记录同生命周期（删除/撤销/回填天然一致），独立表反而要处理级联与撤销恢复的两难。
 */
@Entity(
    tableName = "solve_history",
    indices = [
        Index(value = ["created_at"])
    ]
)
data class SolveRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("image_path")
    val imagePath: String,
    @ColumnInfo("question_text")
    val questionText: String?,
    @ColumnInfo("process_text")
    val processText: String,
    @ColumnInfo("final_text")
    val finalText: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo(name = "follow_ups", defaultValue = "[]")
    val followUpsJson: String = "[]",
    /**
     * 思考全文：解题时模型输出的 reasoning（如有）。历史回填后仍可查看思考，
     * 供「复盘解题思路」场景使用。老记录默认空串（无思考）。
     */
    @ColumnInfo("reasoning_text", defaultValue = "")
    val reasoningText: String = "",
    /**
     * 思考耗时（ms）：模型思考 startAt→endAt 区间，恢复历史时用于展示
     * 「思考了 n 秒」。老记录为 null（无思考或未记录时长，UI 降级为不显示时长）。
     */
    @ColumnInfo("reasoning_ms")
    val reasoningMs: Long? = null,
)
