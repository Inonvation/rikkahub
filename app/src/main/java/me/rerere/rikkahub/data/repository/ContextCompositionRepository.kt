package me.rerere.rikkahub.data.repository

import android.util.Log
import me.rerere.rikkahub.data.ai.ContextComposition
import me.rerere.rikkahub.data.ai.ContextCompositionStore
import me.rerere.rikkahub.data.db.dao.ContextCompositionDAO
import me.rerere.rikkahub.data.db.entity.ContextCompositionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "ContextCompositionRepo"

/**
 * 上下文构成快照的持久化桥：进程级 store（Compose snapshot，UI 即时读取）+
 * Room 落库（每会话一行）。app 重启 / 软件更新后进程级 store 清空，由库中记录的
 * 最近一次快照恢复，浮窗「构成详情」、顶栏圆圈、自动压缩不回落兜底估算。
 *
 * 存储成本极低（单行 7 个整型）；记录随会话删除联动清理（ConversationRepository）。
 */
class ContextCompositionRepository(
    private val dao: ContextCompositionDAO,
    private val scope: CoroutineScope,
) {
    /** 写入：先更新进程级快照让 UI 立即重组，再异步落库。落库失败不阻断本次展示（下次生成会重写）。 */
    fun save(conversationId: String, composition: ContextComposition) {
        ContextCompositionStore.update(conversationId, composition)
        val entity = composition.toEntity(conversationId)
        scope.launch {
            runCatching { dao.upsert(entity) }
                .onFailure { Log.w(TAG, "save: persist context composition failed", it) }
        }
    }

    /**
     * 读取：进程级缺失（进程重启 / 软件更新）时从库恢复最近一次快照，回填进程级 store
     * 并返回；进程内已有该会话快照（本进程刚生成过）时不读库、不覆盖，返回 null。
     */
    suspend fun restoreIfAbsent(conversationId: String): ContextComposition? {
        if (ContextCompositionStore.get(conversationId) != null) return null
        val entity = dao.getById(conversationId) ?: return null
        // 读库挂起期间可能有新生成写入 store，再次校验避免用旧记录覆盖新快照
        if (ContextCompositionStore.get(conversationId) == null) {
            ContextCompositionStore.update(conversationId, entity.toComposition())
        }
        return ContextCompositionStore.get(conversationId)
    }

    /** 会话删除联动：清理进程级快照 + 库记录 */
    suspend fun delete(conversationId: String) {
        ContextCompositionStore.remove(conversationId)
        dao.deleteByConversationId(conversationId)
    }
}

internal fun ContextComposition.toEntity(
    conversationId: String,
    updatedAt: Long = System.currentTimeMillis(),
): ContextCompositionEntity = ContextCompositionEntity(
    conversationId = conversationId,
    systemTokens = systemTokens,
    builtinToolTokens = builtinToolTokens,
    mcpToolTokens = mcpToolTokens,
    skillToolTokens = skillToolTokens,
    messageTokens = messageTokens,
    updatedAt = updatedAt,
)

internal fun ContextCompositionEntity.toComposition(): ContextComposition = ContextComposition(
    systemTokens = systemTokens,
    builtinToolTokens = builtinToolTokens,
    mcpToolTokens = mcpToolTokens,
    skillToolTokens = skillToolTokens,
    messageTokens = messageTokens,
)