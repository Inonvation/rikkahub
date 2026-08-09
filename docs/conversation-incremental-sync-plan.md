# RikkaHub 聊天数据拆分同步 — 设计文档

> 状态：待评审。依据 `docs/remote-sync-implementation-plan.md` 的现有增量同步继续演进。
> 参考：NoteGen（`F:\note-gen\src\lib\sync\conversation-sync.ts`，1572 行）已验证的"会话级文件 + 消息级合并"模式。

## 1. 背景与目标

### 现状（实测）

- 全部聊天数据存在单个 Room SQLite 文件：`rikka_hub.db` **224MB**（gzip 后 72MB）
- 构成：`message_node` 135MB（消息 JSON 文本）、`message_fts` 52MB（可重建的搜索索引）、其它 37MB
- 同步引擎 `SyncManager` 以**整个 DB 文件**为单位：发一条消息 → 文件字节变 → 整库重传
- 单条消息删除无法传播到云端（文件级看不到单条删除）

### 目标

1. 聊天消息按**会话**拆分同步：发一条消息只重传该会话文件（活跃会话通常 KB~MB，远小于 224MB）
2. 消息删除可传播到云端（墓碑机制）
3. 多设备**不丢消息**（消息级合并，双端各改都能保留）
4. 本地数据源不变（仍是 SQLite，UI / 业务零改动），只改写入时给消息打版本 + 新增同步通道
5. 严格版本兼容：云端格式带 `format/version`，可升级；App 可回退到整库同步

### 非目标（本阶段）

- 超长会话自动摘要（NoteGen 的 compaction）——单会话 JSON 可能很大，留作后续增强
- 改动附件文件通道：图片/文档内容已走 `upload/` 独立同步（`includeChatFiles`），本设计只处理"消息里的引用"
- 其它 DB 小表（收藏/记忆/子代理/知识库）的独立同步——本阶段保留整库同步作为兜底

## 2. 现状核验（2026-08 代码走读结论）

| # | 项目 | 实测结论 | 对设计的影响 |
|---|---|---|---|
| 1 | 消息存储 | `message_node`：`id`(UUID)/`conversation_id`/`node_index`/`messages`(JSON `List<UIMessage>`)/`select_index`；FK CASCADE | 消息有稳定 `id`，可做消息级合并 |
| 2 | 消息写入 | **全部收敛到** `ConversationRepository.saveMessageNodes()`（`insertAll REPLACE`）；由 `insertConversation`/`updateConversation` 调用 | 只需改这两个入口即可全覆盖 |
| 3 | 会话更新 | `updateConversation` 先 `deleteByConversation` 再 `saveMessageNodes`（整会话重插） | 重插必须保留已有消息版本，否则全变"新" |
| 4 | 删除链路 | 删会话走 `deleteConversation`（FK CASCADE 删节点）；`deleteByConversation` 实际仅被 update 用 | 删除会话要单独记墓碑 |
| 5 | `updateAt` | **覆盖不全**：编辑/删除消息、改标题、置顶、移文件夹都不递增 `updateAt` | **不能依赖 `updateAt` 判断变化**，须引入单调时钟 |
| 6 | 消息字段 | `UIMessage.id`(Uuid) + `createdAt`(LocalDateTime)，无变更时间戳 | 需给消息加 `syncUpdatedAt`（JSON 内字段） |
| 7 | 附件引用 | 图片/文档 part 的 `url` 是本地 `file://` 绝对路径；内容存 `upload/` | 导出时把本地路径重写为 `upload/<name>`，下载时还原 |
| 8 | 同步引擎 | `SyncManager`：单元 `settings.json`/`db/...db`/`upload`/`skills`/`fonts`；`computePlan`+执行分离；已带 `onProgress` 进度回调 | 新增"会话"单元类型，复用框架 |
| 9 | FTS 索引 | `message_fts` 52MB，可从消息重建 | 走会话文件后 FTS 无需上传 |

## 3. 参考设计（NoteGen 已验证的模式，按需复用）

| 模式 | NoteGen 做法 | 复用价值 |
|---|---|---|
| 双版本号 | 会话/消息各带 `syncId`(稳定) + `syncUpdatedAt`(单调时钟)，父随子联动 | 增量判断 + 合并排序的基础 |
| 单调时钟 | 单行表 `max(value+1, Date.now())`，观察远端时间戳吸收进时钟 | 本地修改必然产生全局更大的版本，天然"谁新谁赢" |
| index + items | 云端 `index.json`（小，快跳过未变会话）+ `items/<id>.json`（每会话一文件） | 快速跳过 + 单文件增量上传 |
| 三层墓碑 | 会话墓碑在 index，消息墓碑在 item 内，`deletedAt` 参与 max 合并；**index 为提交点** | 删除同步 + 中断安全 |
| 消息级 LWW 合并 | 按 `syncId` union，`syncUpdatedAt` 大者胜，重复按内容签名裁决 | 双端并发不丢消息 |
| baseline 冲突检测 | 记录上次成功同步的版本；两端都在 baseline 后改 → 真冲突 → 仍 union 合并 | 无需向量时钟 |
| 幂等写入 | 每条语句幂等、不用事务；CAS 写（远端已变则放弃）；index 先写、墓碑先发布后删文件 | 断点/并发恢复自然成立 |
| 确定性迁移 | 旧数据用 `uuidv5(namespace + 内容签名)` 生成稳定 id，内容匹配复用已有 id | 迁移幂等、不重复 |

## 4. 目标架构

### 4.1 云端存储结构

```
<syncRoot>/conversations/index.json
<syncRoot>/conversations/items/<conversationId>.json
```

`index.json`（极小，快速跳过）：

```json
{
  "format": "rikkahub-conversations-index",
  "version": 1,
  "updatedAt": 1720000000000,
  "conversations": [
    {
      "id": "<conversationId>",
      "syncUpdatedAt": 1720000000000,
      "title": "标题",
      "createAt": 1,
      "isPinned": false,
      "messageCount": 12,
      "path": "conversations/items/<conversationId>.json"
    }
  ],
  "deleted": [ { "id": "<conversationId>", "deletedAt": 1720000000001 } ]
}
```

`items/<id>.json`（单会话全量，可独立增量上传）：

```json
{
  "format": "rikkahub-conversation",
  "version": 1,
  "id": "<conversationId>",
  "syncUpdatedAt": 1720000000000,
  "title": "标题",
  "createAt": 1,
  "isPinned": false,
  "assistantId": "...",
  "customSystemPrompt": null,
  "modeInjectionIds": [],
  "lorebookIds": [],
  "workspaceCwd": null,
  "folderId": null,
  "groupId": null,
  "nodes": [
    {
      "id": "<nodeId>",
      "selectIndex": 0,
      "messages": [
        {
          "id": "<messageId>",
          "syncUpdatedAt": 1720000000000,
          "role": "user",
          "createdAt": "2026-08-01T10:00:00",
          "parts": [ { "type": "text", "text": "..." } ]
        }
      ]
    }
  ],
  "deletedMessageIds": [ { "id": "<messageId>", "deletedAt": 1720000000002 } ]
}
```

要点：
- `conversations/` 与现有 `db/`、`upload/`、`skills/`、`fonts/` 平级，互不干扰
- 解析严格校验 `format`/`version` 精确相等；不匹配视为损坏，**跳过该会话**，绝不覆盖云端
- 附件内容不在此 JSON 内（走 `upload/` 文件通道），消息里的 `file://` 路径在导出时重写为 `upload/<name>`

### 4.2 版本号机制：单调时钟

**不能复用 `ConversationEntity.updateAt`**（§2-5 覆盖不全）。引入独立时钟：

- `SyncStateStore` 新增字段 `syncClockMs: Long`（DataStore 持久化）
- 取新版本：`max(syncClockMs + 1, System.currentTimeMillis())`，原子读改写
- 观察远端时间戳后吸收进时钟：`max(clock, remoteTs)` → 本地下一次修改必然比已见的任何远端数据新
- 会话级版本：`ConversationEntity` 新增列 `sync_updated_at`
- 消息级版本：`UIMessage` 新增字段 `syncUpdatedAt: Long = 0`（JSON 内，kotlinx 默认值保证旧数据可反序列化）

### 4.3 数据模型改造（Room 迁移 41 → 42）

```kotlin
// ConversationEntity 新增
@ColumnInfo("sync_updated_at", defaultValue = "0")
val syncUpdatedAt: Long = 0L,
```

- `MessageNodeEntity` **不加列**（消息版本在 `messages` JSON 内，随消息走）
- 迁移：手动 `Migration_41_42`（`ALTER TABLE conversationentity ADD COLUMN sync_updated_at INTEGER NOT NULL DEFAULT 0`）+ 导出 schema 校验；保留 Room fallback（清库重建仅在开发环境接受，生产须走 migration）
- 存量消息 `syncUpdatedAt=0`：首次导出会话时批量填充（用会话导出时刻的时钟值）

### 4.4 写入路径改造（核心，防"全量重插丢版本"）

现状 `updateConversation` = `deleteByConversation` + `saveMessageNodes`（整会话重插）。改造后必须：**内容未变的消息保留原版本，内容变化/新增才推进时钟**。

改造 `saveMessageNodes`（`ConversationRepository.kt:529`）：

1. 事务内先读当前库中该会话的节点 `id -> 每条消息的 (id, syncUpdatedAt)` 快照
2. 遍历新保存的节点：
   - 节点 `id` 已存在：逐消息比对
     - 消息 `id` 已存在且**序列化内容不变** → 保留原 `syncUpdatedAt`
     - 消息 `id` 已存在但内容变 / 消息 `id` 新增 → `syncUpdatedAt = nextClock()`
   - 节点 `id` 不存在 → 全节点消息赋新版本
3. 会话级 `sync_updated_at` = 本次 `max(所有消息 syncUpdatedAt)`（若不变则维持）
4. 仍用 `deleteByConversation` + `insertAll`（写入方式不变，只补版本）

内容不变判定：整条消息序列化字符串比对（消息 JSON 序列化成本在长会话可接受；流式每 chunk 内容都在变，会推进版本——这正是要的，见 §4.5）

调用点影响：`insertConversation`/`updateConversation` 内部走改造后的 `saveMessageNodes`，**仓库层之外的调用方无需改动**（它们只传 `Conversation`）。

### 4.5 同步时机：等待会话空闲

流式输出每 chunk 都会 `saveConversation` → 版本推进。同步引擎在跑一个会话的导出前，须确保该会话**无活跃流式/编辑**（NoteGen 的 `waitForConversationSyncIdle`）：

- `SyncManager` 导出会话前检查：该会话 `updateAt` 距今 > N 秒（如 5s）且无进行中的生成（复用 `ChatService`/`SubAgentRunner` 的进行中状态，或简单用时间窗）
- 避免把半截消息传上去

### 4.6 同步引擎改造（复用 `SyncManager` 框架）

新增单元类型与云端通道，与现有 settings/db/upload 平行：

- **本地收集**：从 DB 读出所有会话的 `(id, sync_updated_at, title, messageCount, ...)` 清单
- **决策**（对每个会话）：
  - 本地 `sync_updated_at` > 云端 index 中该会话 `syncUpdatedAt` → **上传**该会话 item.json
  - 云端 index 有、本地无且不在本地删除列表 → **下载**合并
  - 本地删了、云端还有 → 写会话墓碑（index.deleted）并删云端文件
  - 本地与云端版本一致 → **跳过**
- **上传**：导出一会话 JSON → 流式上传 `conversations/items/<id>.json`（复用 `uploadFile`）→ 更新 index.json（CAS：先读 index，若远端已变则合并后再写）
- **下载**：读 index.json → 跳过未变会话 → 拉有变化的 item.json → 合并进本地 DB
- **进度回调**：复用 `onProgress`，单位 = 会话（`humanLabel` 返回会话标题）
- **并入数据范围**：`SyncConfig` 新增 `includeConversations: Boolean = true`（聊天记录走新通道）；勾选"聊天记录"时启用会话同步，DB 整库同步中的 `db/rikka_hub.db` 对应降级/不再上传聊天数据

### 4.7 合并策略（消息级 LWW，不丢消息）

下载 item.json 后与本地该会话合并（`ConversationRepository` 新增 `mergeConversationItem`）：

1. **墓碑优先**：item 的 `deletedMessageIds` 与本地已删列表合并 → 被墓碑覆盖的消息从两侧剔除
2. **消息合并**（按 `UIMessage.id`）：
   - 只在本地 → 保留本地版本
   - 只在云端 → 从云端插入
   - 两端都有 → `syncUpdatedAt` 大者胜；相同则按内容签名（稳定序列化）裁决
   - 合并后按 `createdAt` 排序回写节点
3. **节点重组**：按合并后的消息重新组装 `MessageNode`（`nodeIndex` 保持稳定；若云端/本地的节点 id 都保留，则双份并排——不删任何一方的消息）
4. **会话元数据**：`title/isPinned/...` 按 `syncUpdatedAt` 大者胜
5. 合并结果若同时不同于本地与云端（内容来自双方）→ `syncUpdatedAt = max(所有消息版本, 墓碑 deletedAt) + 1`，避免其它设备按旧 index 跳过
6. **持久化**：逐条幂等 upsert（不包事务），中断可安全重试（与 NoteGen 一致）

### 4.8 删除同步（墓碑）

- **删消息**：本地删除 → 记入本会话墓碑列表（`deletedMessageIds`，存 DataStore 或新表）→ 下次上传 item.json 带上 → 其它设备合并时应用删除
- **删会话**：本地删除 → 记会话墓碑（index.deleted）→ 上传 index 后**再**删云端 item 文件（index 为提交点）
- **下载应用删除**：远端 index.deleted → 本地删会话（走 `deleteConversation`）；item 内墓碑 → 删对应消息
- 墓碑用单调时钟 `deletedAt`，参与 max 合并，防止"删了又因旧版本回来"

### 4.9 附件引用映射

消息 part 的 `url` 是本地绝对路径（`/data/user/0/com.inonvation.rikkahub.debug/files/upload/xxx`）。跨设备路径不同，导出/下载必须重写：

- **导出**：`file://.../upload/<name>` → `upload/<name>`（遍历 `image/document/video/audio` 的 url）
- **下载合并**：`upload/<name>` → 本机 `filesDir/upload/<name>`（配合 `includeChatFiles` 已同步好的附件文件）
- 非 `upload/` 的本地路径（不在此通道）→ 导出时**跳过该引用**并在日志标注（宁缺毋滥，不暴露设备路径）

### 4.10 冲突处理

- 常规：消息级 LWW + 墓碑优先，天然合并（§4.7），不丢消息
- 兜底：若 item.json 损坏（format/version 不匹配）→ 不覆盖云端、不崩溃，记错误、跳过该会话
- 极端（合并抛异常）→ 保留本地 + 云端留 `.conflict-<deviceId>` 副本，下次可手工处理

## 5. 版本兼容与迁移

### 5.1 云端格式版本

- `format`/`version` 硬校验：升级版本时须提供迁移解析器；未知版本直接跳过（不覆盖）
- 本设计使用 `version = 1`，字段全部显式（含默认值），后续可加字段不改版本

### 5.2 首次启用（迁移）

- 本地 DB **不做物理迁移**：消息仍在 `message_node`，只是导出为会话 JSON
- 首次同步：全量导出所有会话 → 逐会话上传 → 写 index.json（幂等，可断点续传：已上传且版本未变的会话跳过）
- 存量消息 `syncUpdatedAt=0`：导出时按当前时钟填充

### 5.3 回退（关键保证）

- `SyncConfig` 新增开关 **`includeConversations`（聊天增量同步）**，与现有"聊天记录"勾选联动
- **关闭** → 回退整库同步（本地 DB 完整，`db/rikka_hub.db` 全量上传），云端 `conversations/` 目录保留不动（不删，避免误清）
- **关闭期间**：消息仍写本地 DB，整库同步把它们带上去；重新开启后按会话版本快检，差异自然补齐
- 老版本 App（不含本功能）：看不到 `conversations/` 目录，但整库同步仍把 `db/rikka_hub.db` 全量传——**与新版并存时以 index.json 为准会冲突**。处理：检测云端 index 存在时，整库同步跳过 `db/` 上传（只下载时校验一致性），并提示升级

## 6. 测试计划

**单元测试**
- 单调时钟：回拨系统时间仍递增；观察远端时间戳吸收
- 序列化：`item.json`/`index.json` 编解码；旧消息 JSON（无 `syncUpdatedAt`）反序列化为默认值 0
- 合并：单端新增/修改/删除、双端各改（LWW 正确保留双方）、墓碑优先、内容签名平局裁决
- 写入保留版本：`saveMessageNodes` 内容未变保留原版本、变化/新增推进；整会话重插幂等
- 附件映射：`file://` → `upload/<name>` → 本机路径往返；非 upload 引用跳过
- 校验：format/version 不匹配 → 跳过不覆盖

**集成测试（mock provider，复用现有 `SyncManagerIntegrationTest` 模式）**
- 上传：发消息 → 只上传该会话 item + index 更新
- 下载：云端新增/修改会话 → 合并进本地，消息不丢
- 删除：本地删消息/会话 → 墓碑传播 → 云端删除
- 双端并发：两端各改同一会话不同消息 → 合并后双方消息都在
- 断点：上传中断 → 重试幂等，不重复不覆盖
- 迁移：存量 224MB 库 → 首次导出正确性、幂等（跑两次结果一致）

**回归**
- 现有 `SyncManagerIntegrationTest`/`SyncManagerPreviewTest`/`WebDavSyncProviderTest` 全绿
- 整库备份/恢复不受影响

## 7. 风险清单与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 合并 bug 导致丢消息 | **最高** | 消息级 union + 墓碑优先；单测覆盖双端并发；先小范围灰度 |
| 整会话重插丢失消息版本 | 高 | §4.4 内容比对保留版本；`saveMessageNodes` 单测 |
| Room 迁移失败 | 高 | 手动 Migration_41_42 + schema 校验；保留 fallback |
| 流式写入推进版本导致传半截 | 中 | §4.5 等待空闲 + 时间窗 |
| 附件路径映射错误（图片丢失） | 中 | 覆盖所有 part 类型；映射失败跳过并记日志 |
| 新旧版本并存冲突 | 中 | index 存在时整库同步跳过 `db/`；提示升级 |
| 云端 index 并发覆盖 | 中 | CAS 写（先读后写，远端变则合并再写） |
| 单会话超大（长对话） | 低 | 本阶段接受（单会话文件远小于整库）；后续 compaction |

## 8. 实施阶段

| 里程碑 | 内容 | 出口条件 |
|---|---|---|
| **C0** 版本地基 | 单调时钟（SyncStateStore）+ `UIMessage.syncUpdatedAt` + `ConversationEntity.sync_updated_at`（Room 42）+ `saveMessageNodes` 保留版本 | 单测绿：写入幂等保留版本、时钟单调 |
| **C1** 导出上传 | 会话清单收集 + item/index 序列化 + 上传通道 + index CAS + `includeConversations` 开关 | mock provider 集成测试：发消息只传该会话 |
| **C2** 下载合并 | item/index 下载 + §4.7 消息级合并 + §4.8 墓碑删除 + §4.9 附件映射 | 集成测试：双端并发不丢消息、删除传播 |
| **C3** 迁移与回退 | 首次全量导出、整库同步与 `conversations/` 并存规则、回退开关 | 存量库迁移幂等；关闭开关回退整库 |
| **C4** 收尾 | 进度 UI（复用 onProgress）、数据范围勾选联动、文档 | 全量测试绿 + 真机双设备手动验证 |

依赖：C0 → C1 → C2 → C3 → C4。C0 不碰同步，可先行验证；C1~C2 是核心。
