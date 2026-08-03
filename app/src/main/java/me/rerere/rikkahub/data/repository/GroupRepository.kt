package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.GroupDAO
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.model.DiscussionConfig
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 群组数据仓库。`configJson` 序列化 [DiscussionConfig]（与旧 discussion_json 同手法）。
 */
class GroupRepository(
    private val groupDao: GroupDAO,
) {

    private fun groupEntityToGroup(entity: GroupEntity): Group = Group(
        id = Uuid.parse(entity.id),
        name = entity.name,
        config = entity.configJson.ifEmpty { null }
            ?.let { runCatching { JsonInstant.decodeFromString<DiscussionConfig>(it) }.getOrNull() },
        createAt = Instant.ofEpochMilli(entity.createAt),
        updateAt = Instant.ofEpochMilli(entity.updateAt),
    )

    private fun groupToGroupEntity(group: Group): GroupEntity = GroupEntity(
        id = group.id.toString(),
        name = group.name,
        configJson = group.config?.let { JsonInstant.encodeToString(it) } ?: "",
        createAt = group.createAt.toEpochMilli(),
        updateAt = group.updateAt.toEpochMilli(),
    )

    fun getAllGroupsFlow(): Flow<List<Group>> =
        groupDao.getAll().map { list -> list.map { groupEntityToGroup(it) } }

    fun getGroupFlow(groupId: Uuid): Flow<Group?> =
        groupDao.getFlow(groupId.toString()).map { it?.let { e -> groupEntityToGroup(e) } }

    suspend fun getGroupById(groupId: Uuid): Group? =
        groupDao.getById(groupId.toString())?.let { groupEntityToGroup(it) }

    suspend fun insert(group: Group) {
        groupDao.insert(groupToGroupEntity(group))
    }

    suspend fun update(group: Group) {
        groupDao.update(groupToGroupEntity(group))
    }

    suspend fun deleteGroup(groupId: Uuid) {
        groupDao.deleteById(groupId.toString())
    }

    suspend fun touchUpdateAt(groupId: Uuid, updateAt: Instant = Instant.now()) {
        groupDao.touchUpdateAt(groupId.toString(), updateAt.toEpochMilli())
    }
}
