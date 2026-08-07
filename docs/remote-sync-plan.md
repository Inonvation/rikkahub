# RikkaHub 远端增量同步方案（可实现文档）

## 一、需求与目标

- **定位**：跨设备双向同步。多台设备通过同一远端（默认坚果云 WebDAV）交换应用数据，任一端修改自动传播到另一端。
- **触发**：应用启动时自动检测云端状态并执行增量同步；后台由 WorkManager 每日兜底。
- **冲突**：全自动处理，不打断用户；保留冲突副本可手动找回。
- **边界（维持现状）**：只同步 `settings + rikka_hub.db + upload/skills/fonts`，**不纳入** workspace 沙箱、knowledge/raw、images、trash、tool_outputs。
- **与现有备份互补**：现有 WebDAV/S3 整包 zip 备份负责"时间点回滚"，本功能负责"日常增量双向传播"，两套并存、互不干扰（使用不同远端目录）。

## 二、现状分析

### 2.1 已有能力（直接复用）

| 能力 | 位置 | 说明 |
|---|---|---|
| WebDAV 客户端 | `data/sync/webdav/WebDavClient.kt` | `put/get/getStream/downloadToFile/delete/head/mkcol/propfind/exists/ensureCollectionExists/list`，Basic Auth，流式传输 |
| S3 客户端 | `data/sync/s3/S3Client.kt` | `putObject/getObject/downloadObjectToFile/deleteObject/headObject/listObjects/objectExists`，手写 SigV4 |
| DB 一致性 checkpoint | `WebDavSync.checkpointWal()`（WebDavSync.kt:45，**private**） | `PRAGMA wal_checkpoint(TRUNCATE)`，保证备份/同步的 `.db` 自洽 |
| 配置结构 | `PreferencesStore.kt:838` `WebDavConfig(url, username, password, path="rikkahub_backups", items)` | 已有 url/path 字段，可承载同步远端目录 |
| 备份 UI 容器 | `ui/pages/backup/BackupPage.kt`（4 tab） | 可加第 5 个"同步"tab |
| WorkManager 注入 | `RikkaHubApp.kt:56` `workManagerFactory()` + `koin-androidx-workmanager` | 可直接注册 Worker |
| Ktor 客户端 | `di/DataSourceModule.kt:340-353` 共享 `HttpClient`（OkHttp 引擎） | WebDAV/S3 同步专用，独立超时 |
| 启动任务模式 | `RikkaHubApp.onCreate()` 的 `get<AppScope>().launch(Dispatchers.IO){ runCatching{...} }` | 同步钩子沿用同一模式 |

### 2.2 缺口

1. 无同步状态持久化（`syncedShas`、`deviceId`、`lastSyncTime`）。
2. 无统一 Provider 抽象（WebDAV/S3 接口名不同，需各自适配）。
3. 无增量引擎：现有只有全量 zip 备份。
4. 无冲突处理、无设备锁。
5. 无自动触发（启动钩子、Worker、网络检测均无）。
6. `checkpointWal()` 是 private，需提取为共享。

## 三、总体架构

```
                    ┌─────────────────────────────────────────┐
                    │              SyncManager                 │
                    │  决策表 + 增量编排 + 冲突处理 + 互斥锁      │
                    └───────┬──────────────┬──────────────────┘
                            │              │
                   ┌────────▼────┐   ┌─────▼───────────┐
                   │ SyncProvider │   │ SyncStateStore  │
                   │ (接口抽象)    │   │ (DataStore)      │
                   └──────┬──────┘   └─────────────────┘
                 ┌─────────┼─────────┐
            WebDAVProvider   S3Provider
                 │                │
           WebDavClient      S3Client   ← 复用现有实现
                 │
        坚果云 WebDAV（适配 /dav + 限流）

触发源：
  ① RikkaHubApp.startCloudSync()（应用启动）    ← P0
  ② SyncWorker（WorkManager 每日兜底）          ← P1
  ③ 设置页"立即同步"按钮                         ← P1
```

**协调规则**：所有触发源共用 `SyncManager`；`SyncStateStore.syncInProgress` 互斥标记防止并发重入；`lastSyncTime` + `intervalHours` 控制最小间隔；离线时记 `pendingSync` 待下次触发补齐。

## 四、远端存储结构

与现有整包备份（`rikkahub_backups/`）完全隔离，使用独立根目录（复用 `WebDavConfig.path` 改指向 `rikkahub-sync`，或新配置字段）：

```
<dav-root>/rikkahub-sync/
  index.json                      # {schemaVersion:1, lastSyncAt}
  settings.json                   # 白名单过滤后的可同步设置
  settings.meta.json              # {sha256, updatedAtMs, deviceId}
  db/rikka_hub.db                 # checkpoint 后的单文件
  db.meta.json                    # {sha256, timestamp, deviceId, size}
  upload/<filename>
  upload/<filename>.meta.json     # {sha256, size, lastModifiedMs, updatedAtMs, deviceId}
  skills/<relpath>                # 递归，目录结构扁平化
  skills/<relpath>.meta.json
  fonts/<filename>
  fonts/<filename>.meta.json
```

设计要点：
- 内容文件与 `.meta.json` 一一对应；meta 记录该文件**最后一次由哪台设备、何时**写入，供冲突决策。
- `index.json` 记录 schema 版本，预留升级空间。
- 附件文件按扁平相对路径存储，删除本地文件时同步删除远端对应文件（用 `syncedFiles` 记录对比）。

## 五、同步单元与增量策略

### 5.1 Provider 抽象

```kotlin
data class RemoteFileMeta(
    val path: String,           // 远端相对路径（含前缀）
    val size: Long,
    val lastModified: Instant?,
    val etag: String?,          // WebDAV: ETag 头; S3: ETag 头; 坚果云/部分服务可能为 null
)

interface SyncProvider {
    suspend fun ensureDirectory(path: String): Result<Unit>
    suspend fun putFile(remotePath: String, file: File, contentType: String): Result<Unit>
    suspend fun downloadToFile(remotePath: String, target: File): Result<Unit>
    suspend fun head(remotePath: String): Result<RemoteFileMeta?>   // null=不存在
    suspend fun delete(remotePath: String): Result<Unit>
    suspend fun list(dirPath: String): Result<List<RemoteFileMeta>>
}
```

- `WebDavSyncProvider`：适配 `WebDavClient`（`head→head`、`put→put(file)`、`list→list`）。
- `S3SyncProvider`：适配 `S3Client`（`head→headObject`、`put→putObject`、`list→listObjects`）。

### 5.2 通用增量决策

本地对每个同步文件算 `sha256`，与 `SyncStateStore.syncedFiles[relPath]` 记录的 `FileSyncRecord(sha256, remoteTag, updatedAtMs, deviceId)` 比较，结合远端 `head` 结果：

| 场景 | 判定 | 动作 |
|---|---|---|
| 远端不存在 | — | **push**（上传 + 写 meta） |
| 本地 sha == 记录 sha，远端 etag/lastModified 与记录 remoteTag 不同 | 本地没变、远端被别的设备改过 | **pull** |
| 本地 sha != 记录 sha，远端未变（记录 remoteTag == 当前 etag） | 只有本地改过 | **push** |
| 本地 sha != 记录 sha，远端也变了 | **冲突** | 见 §6 |
| 本地与记录 sha 相同，远端 etag 相同 | 无变化 | skip |

**ETag 不可直接与本地 SHA-256 比较**（算法/引号格式不同），因此**必须**用本地 `syncedFiles` 记录做中间桥梁，这是 note-gen `syncedFileShas` 模式的同款思路。

### 5.3 各单元同步

| 单元 | 增量粒度 | 备注 |
|---|---|---|
| `settings.json` | 整文件替换 | 序列化前走白名单过滤（§5.4），meta 用 `updatedAtMs` 冲突 |
| `rikka_hub.db` | 整包传输 | 上传前 `checkpointWal()`；下载后校验 sha256 |
| `upload/*` | 文件级 | 上传目录下直接文件 |
| `skills/**` | 文件级 | 递归枚举，保留相对路径 |
| `fonts/*` | 文件级 | 直接文件 |

同步方向：**启动/触发时先 pull 再 push**（先吸收其他设备变更，再传播本机变更），减少冲突概率。

### 5.4 settings 同步白名单（安全关键）

`Settings` 内含 **provider API key**、**webDavConfig/s3Config 凭据**、**syncConfig 自身**，绝不能整包上云。新增 `SettingsSyncCodec`：

```kotlin
object SettingsSyncCodec {
    /** 同步时剔除的字段：凭据 / 设备本地字段 / 同步配置自身 */
    fun toSyncableJson(settings: Settings): String
    /** 恢复时把被剔除字段保留为本地现有值 */
    fun fromSyncableJson(json: String, local: Settings): Settings
}
```

默认剔除（硬编码清单）：
- `providers[].apiKey`（及各 provider 的密钥类字段，需按 `Settings` 实际字段枚举核对）
- `webDavConfig`、`s3Config`（凭据，各设备各自配置）
- `syncConfig`（同步开关/周期为设备本地项）
- 设备本地字段：`deviceId`、`launchCount`、`trustedFolderSettings` 等（实现时按 `Settings` 全字段核对，白名单之外的 key 一律不同步）

恢复规则：被剔除字段回落到本机当前值，**不覆盖**。

## 六、冲突处理（全自动）

所有冲突一律按 meta 中 `updatedAtMs` 取新（last-write-wins），被覆盖方的旧内容保留冲突副本：

| 单元 | 胜者 | 败者去向 |
|---|---|---|
| settings | `updatedAtMs` 大者 | 丢弃（settings 无法合并，取新即丢旧，风险可接受） |
| 附件文件 | `updatedAtMs` 大者 | 本地留 `xxx.conflict-<deviceId>`；远端留 `xxx.conflict-<deviceId>` |
| 数据库 | meta `timestamp` 大者 | 本地留 `rikka_hub.db.old-<timestamp>`；远端留 `db.old/<timestamp>/rikka_hub.db` |

- 冲突副本**不参与**后续增量（`syncedFiles` 记录会排除 `.conflict` / `.old-` 后缀文件）。
- 冲突发生后写一条记录到 `SyncStateStore.conflicts`，设置页可查看并手动清理（P2）。

**已知取舍**：DB 冲突为 keep_newest，双端各自新写对话时后写覆盖先写，最多丢数分钟数据，靠每日整包备份兜底。若后续需要 DB 级合并，单独评估"拆域 JSON 同步"（P2 可选，成本高，默认不做）。

## 七、同步状态持久化（SyncStateStore）

新增 DataStore，独立于 `settings`：

```kotlin
@Serializable
data class SyncState(
    val deviceId: String = "",                          // 首次同步生成 UUID
    val syncedFiles: Map<String, FileSyncRecord> = emptyMap(), // 远端相对路径 -> 记录
    val lastSyncTime: Long = 0L,
    val syncInProgress: Boolean = false,                // 互斥锁
    val pendingSync: Boolean = false,                   // 上次离线未完成
    val dbRestorePending: String? = null,               // 待应用的 DB 下载路径（cacheDir）
    val conflicts: List<ConflictRecord> = emptyList(),
)

@Serializable
data class FileSyncRecord(
    val sha256: String,
    val remoteTag: String,      // 上次确认同步时的远端 etag
    val updatedAtMs: Long,      // 本地文件修改时间
    val deviceId: String,
)

@Serializable
data class ConflictRecord(val relPath: String, val atMs: Long, val winnerDevice: String)
```

## 八、启动时自动检测流程

在 `RikkaHubApp.onCreate()` 中，`syncManagedFiles()`（RikkaHubApp.kt:83）之后加：

```kotlin
// 在 startKoin 后、任何 get<AppDatabase>() 之前执行（P0 顺序约束见下）
applyPendingDbRestore()   // 若有 dbRestorePending，先替换 DB 文件再继续
startCloudSync()          // 异步启动同步
```

`startCloudSync()` 流程：

```
1. 读 settings.syncConfig；enabled == false → return
2. 按 syncConfig.provider 构建 SyncProvider；凭据不完整 → return
3. 在线检查（ConnectivityManager）：
   离线 → syncState.pendingSync = true → return
4. now - lastSyncTime < intervalHours 且 !pendingSync → return
5. syncState.syncInProgress 为 true（Worker 正在跑）→ return
6. 置 syncInProgress = true
7. SyncManager.sync()（pull 再 push，全程 Dispatchers.IO）
8. 更新 lastSyncTime、清 pendingSync、syncInProgress = false
```

**`applyPendingDbRestore()` 顺序约束（重要）**：AppDatabase 是 Koin 懒加载单例，首次 `get<AppDatabase>()` 才打开文件。`applyPendingDbRestore()` 必须在所有其他启动任务**之前**同步执行（或排在第一个协程），否则 `rikka_hub.db` 被打开后无法替换。DB pull 的完整链路：

```
同步期：远端 db 更新 → 下载到 cacheDir/tmp → 校验 sha256 == db.meta.sha256
      → 记 syncState.dbRestorePending = tmp 路径（不直接替换活动 DB）
重启期：applyPendingDbRestore()：校验文件存在+sha → 覆盖 databases/rikka_hub.db（并删 -wal/-shm）
      → 清 dbRestorePending
```

## 九、WorkManager 每日兜底

新增 `SyncWorker`（`data/sync/SyncWorker.kt`）：

```kotlin
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        if (!isOnline()) return Result.retry()
        return runCatching {
            get<SyncManager>().syncIfNeeded(Trigger.WORKER)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
```

- 注册：`PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)`（最小周期受系统限制，默认 24h 可满足每日）。
- `Constraints`：`NetworkType.CONNECTED`（可选加 `NETWORK_METERED` 排除，设置项控制）。
- 与启动同步互斥：靠 `syncInProgress` + `lastSyncTime` 天然去重，不会重复执行。
- 注册点：`di/DataSourceModule.kt` 或 `RikkaHubApp` 用 `WorkManager.enqueueUniquePeriodicWork` + `ExistingPeriodicWorkPolicy.UPDATE`（保证配置变更后幂等重建）。

## 十、坚果云适配

`WebDavSyncProvider` 或 `WebDavClient` 层补两处（借鉴 note-gen `webdav.ts`）：

1. **路径规范化**：host 为 `dav.jianguoyun.com` 且配置 path 为空时自动补 `/dav` 前缀。
2. **限流冷却**：请求返回 `429/503` 时解析 `Retry-After`，以 base URL 为 key 记录 `temporaryBlockUntil`（默认 60s 冷却）；冷却期内对该 host 直接跳过请求（记 `pendingSync`）。

**待实测验证项**：
- 坚果云免费版单文件大小上限（DB 较大时是否需分片，影响上传策略）。
- 坚果云是否返回可靠的 `ETag` 头（决定 remoteTag 是否可用；若不可用则退化用 `lastModified`）。

## 十一、设置 UI

在 `ui/pages/backup/BackupPage.kt` 增加第 5 个 tab `SyncTab`：

| 控件 | 绑定字段 |
|---|---|
| 同步开关 | `settings.syncConfig.enabled` |
| 远端类型（WebDAV/S3） | `syncConfig.provider`（复用现有 WebDAV/S3 配置表单） |
| 同步周期 | `syncConfig.intervalHours` |
| 启动自动同步开关 | `syncConfig.autoSyncOnLaunch` |
| 立即同步按钮 | 调 `SyncManager.syncIfNeeded(Trigger.MANUAL, force = true)` |
| 上次同步时间 / pendingSync 提示 | `SyncStateStore` |
| 冲突记录列表（P2） | `SyncStateStore.conflicts`，可清理副本 |

配置存储：`SyncConfig` 并入 `Settings`（`PreferencesStore.kt`），字段见 §7 附录；**必须列入 §5.4 同步剔除清单**。

## 十二、新增/修改文件清单

### 新增

| 文件 | 职责 |
|---|---|
| `data/sync/SyncProvider.kt` | 接口 + `RemoteFileMeta` |
| `data/sync/WebDavSyncProvider.kt` | WebDAV 适配（含坚果云路径/限流） |
| `data/sync/S3SyncProvider.kt` | S3 适配 |
| `data/sync/SyncManager.kt` | 决策表 + 增量编排 + 冲突 + 互斥 |
| `data/sync/SyncStateStore.kt` | 同步状态 DataStore |
| `data/sync/SyncWorker.kt` | WorkManager 每日任务 |
| `data/sync/SettingsSyncCodec.kt` | settings 白名单序列化/恢复 |
| `ui/pages/backup/tabs/SyncTab.kt` | 同步设置 UI |
| 单测：`data/sync/SyncDecisionTest.kt`、`SettingsSyncCodecTest.kt`、`SyncStateStoreTest.kt` | 见 §十四 |

### 修改

| 文件 | 改动 |
|---|---|
| `data/datastore/PreferencesStore.kt` | `Settings` 增加 `SyncConfig`；补充字段迁移（`SettingsJsonMigrator` 需跟进 V4） |
| `RikkaHubApp.kt` | `applyPendingDbRestore()` + `startCloudSync()` 启动钩子 |
| `data/sync/webdav/WebDavSync.kt` | `checkpointWal()` 由 private 提取为共享（移入 `AppDatabase` 扩展或 `FilesManager`），供同步与备份共用 |
| `di/DataSourceModule.kt` | 注册 `SyncManager`/`SyncStateStore`/`SyncWorker` |
| `data/datastore/migration/SettingsJsonMigrator.kt` | `SyncConfig` 默认值迁移 |

## 十三、实施步骤

### P0 同步核心（可独立验证）
1. `SyncStateStore` + `deviceId` 生成 + 互斥/`pendingSync` 字段。
2. `SyncProvider` 接口 + `WebDavSyncProvider`/`S3SyncProvider` 适配。
3. `SyncManager`：决策表 + settings/DB/附件增量 push/pull + 冲突处理（含冲突副本）。
4. 提取 `checkpointWal` 为共享；DB 下载暂存 + `dbRestorePending` 链路。
5. `SettingsSyncCodec` 白名单。
6. `RikkaHubApp.startCloudSync()` + `applyPendingDbRestore()`。
7. 设置页"立即同步"按钮（临时入口，可先做最小 UI）。

### P1 自动调度
8. `SyncWorker` 每日注册 + 网络约束。
9. `SyncTab` 完整 UI（开关/周期/状态）。
10. 坚果云适配（`/dav` 规范化 + 429/503 冷却）+ 实测单文件上限与 ETag。

### P2 增强（可选）
11. 冲突记录查看与手动清理。
12. 可选"仅 Wi-Fi"自动同步、DB 拆域合并（不推荐，成本高）。

## 十四、测试计划

| 层级 | 内容 |
|---|---|
| 单元 | `SyncDecisionTest`：§5.2 决策表 5 种分支全覆盖；`SettingsSyncCodecTest`：剔除/回落正确，无 key 泄露；`SyncStateStoreTest`：互斥锁、pending 语义 |
| 集成 | 本地 WebDAV mock（`okhttp MockWebServer`，若测试依赖允许）验证 push/pull/delete/冲突副本落盘 |
| 手动双设备 | 场景清单：A 改 B 拉 / B 改 A 拉 / 双端同改（冲突副本出现）/ 离线启动（pendingSync）/ DB 双端改动（keep_newest + old 副本）/ 重启后 DB 生效 |
| 回归 | 现有整包备份/恢复不受影响（远端目录隔离 + `WebDavSync` 未动） |

## 十五、风险与边界

1. **DB 冲突丢数据**：keep_newest 下后写覆盖先写（最多数分钟），由每日整包备份兜底——明确为产品取舍。
2. **同步配置含凭据**：`syncConfig`/`webDavConfig`/`s3Config` 必须列入同步剔除清单，实现时逐字段核对 `Settings`，防止 key 上云。
3. **settings 全量字段核对**：白名单之外字段默认不同步，需对 `Settings` 现有字段做一次完整枚举，避免漏掉新字段。
4. **坚果云兼容性**：ETag 可靠性、单文件大小上限需实测；若 ETag 缺失，冲突检测退化用 `lastModified`（精度降低）。
5. **DB 替换时机**：`applyPendingDbRestore` 必须在首次 `get<AppDatabase>()` 之前，顺序是硬约束。
6. **触发频率**：WorkManager 周期最小约 15 分钟，`intervalHours` 语义是"距上次同步的最小间隔"，由 `SyncManager` 统一把关，Worker 周期只是兜底频次上限。
