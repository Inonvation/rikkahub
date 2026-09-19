package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import java.util.UUID as JavaUuid
import kotlin.uuid.Uuid

/**
 * 生成供应商副本名：在已用名称集合中为 [baseName] 找到不冲突的名字。
 *
 * 规则与自定义模式复制一致：`原名 副本` → `原名 副本 2` → `原名 副本 3`…
 * 大小写不敏感比较，避免 "OpenAI" / "openai" 混淆。
 */
internal fun generateUniqueProviderName(baseName: String, usedNames: Collection<String>): String {
    val base = baseName.ifBlank { "Provider" }
    val used = usedNames.mapTo(mutableSetOf()) { it.lowercase() }
    if (base.lowercase() !in used) return base
    var name = "$base 副本"
    var index = 2
    while (name.lowercase() in used) {
        name = "$base 副本 $index"
        index++
    }
    return name
}

/**
 * 由 (providerId, model.modelId, type, displayName) 推导稳定 UUID。
 *
 * 用于加载期兜底：旧复制/导入产生的跨供应商共享 Model.id 在未落盘前也能
 * 每次得到同一结果，避免随机 UUID 导致 chatModelId / 收藏在两次启动间漂移。
 */
internal fun stableRemappedModelId(providerId: Uuid, model: Model): Uuid {
    val seed = "provider-model:${providerId}:${model.modelId}:${model.type.name}:${model.displayName}"
    val javaUuid = JavaUuid.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))
    return Uuid.parse(javaUuid.toString())
}

/**
 * 保证供应商列表的隔离不变量：
 * 1. 供应商 id 全局唯一（保留首个，丢弃后续重复）
 * 2. 供应商 name 全局唯一（后续同名自动改名为 `… 副本`）
 * 3. Model.id 跨供应商全局唯一（保留首个命中，后续用 [idFactory] 重生成）
 *
 * [idFactory] 默认 [stableRemappedModelId]（确定性，适合加载期无持久化场景）；
 * 一次性迁移可传入 `{ _, _ -> Uuid.random() }`。
 *
 * 返回值：无变更时返回原列表引用，便于调用方用 `===` / `==` 判断是否需要落盘。
 */
fun List<ProviderSetting>.withProviderIsolation(
    idFactory: (Uuid, Model) -> Uuid = { providerId, model -> stableRemappedModelId(providerId, model) },
): List<ProviderSetting> {
    if (isEmpty()) return this

    val seenProviderIds = mutableSetOf<Uuid>()
    val usedNames = mutableSetOf<String>()
    val seenModelIds = mutableSetOf<Uuid>()
    var changed = false

    val result = mapNotNull { provider ->
        // 供应商 id 去重：保留首个
        if (!seenProviderIds.add(provider.id)) {
            changed = true
            return@mapNotNull null
        }

        val uniqueName = generateUniqueProviderName(provider.name, usedNames)
        if (uniqueName != provider.name) changed = true
        usedNames.add(uniqueName.lowercase())

        val models = provider.models.map { model ->
            if (seenModelIds.add(model.id)) {
                model
            } else {
                changed = true
                val newId = idFactory(provider.id, model)
                // 极端碰撞：新 id 已被占用时退回随机
                val safeId = if (newId in seenModelIds) Uuid.random() else newId
                seenModelIds.add(safeId)
                model.copy(id = safeId)
            }
        }

        if (uniqueName != provider.name || models != provider.models) {
            provider.copyProvider(name = uniqueName, models = models)
        } else {
            provider
        }
    }

    return if (changed) result else this
}
