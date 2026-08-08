# RikkaHub 远端增量同步 — 实施计划

> 依据 `docs/remote-sync-plan.md` 细化。本文档是**可执行任务分解**，原文是设计依据。
> 先读原文，再按本文档推进。

## 0. 现状核验（2026-08 代码走读结论）

| # | 文档假设 | 实测结论 | 影响 |
|---|---|---|---|
| 1 | `checkpointWal()` private（WebDavSync.kt:45） | 确认 | 提取为共享 |
| 2 | WebDavClient 已有全套方法 | 确认（put/get/getStream/downloadToFile/delete/head/mkcol/propfind/exists/ensureCollectionExists/list） | 直接复用 |
| 3 | **remoteTag 用 ETag** | **WebDavClient 完全不读 ETag**：`head()` 不取 `ETag` 头、`propfind` XML 未请求 `<D:getetag/>` | **必须新增 ETag 支持**，见 §4.1 |
| 4 | S3Client 方法齐全 | 确认（putObject/getObject/downloadObjectToFile/deleteObject/headObject/listObjects/objectExists） | S3 无目录语义，不需要 ensureDirectory |
| 5 | BackupPage 4 个 tab | 确认：WebDavTab/S3Tab/ImportExportTab/ReminderTab，`SecondaryScrollableTabRow` | 加第 5 个 `SyncTab` 即可 |
| 6 | DataSourceModule 共享 HttpClient（340-353） | 确认：`HttpClient(OkHttp)`，connect 20s / read 10min / write 120s | 同步用独立 client，见 §4.4 |
| 7 | Settings 剔除清单需核对 | Settings 共 **118 字段**；含密钥/设备本地类：`webDavConfig`、`s3Config`、`webServerAccessPassword`、`backupReminderConfig`，另有 `providers[].apiKey` | 剔除清单见 §4.2 |
| 8 | SettingsJsonMigrator 跟进 V4 | **Migrator 无版本号机制**，是纯子结构迁移（mcpServers/assistants/quickMessages），不是逐版本 | SyncConfig 只加默认值即可，**不需要迁移逻辑** |
| 9 | `applyPendingDbRestore()` 须在首次 `get<AppDatabase>()` 前 | 确认。**首个触碰 DB 的点是 `RikkaHubApp.syncManagedFiles()` → `FilesManager.syncFolder()` → `FilesRepository`** | 见 §3 顺序约束 |
| 10 | 触发点 `RikkaHubApp.onCreate()` | 确认。启动钩子加在 `syncManagedFiles()`（RikkaHubApp.kt:83）**之前** | 见 §3 |

## 1. 里程碑

| 里程碑 | 内容 | 出口条件 |
|---|---|---|
| **M0** 基础设施 | SyncStateStore + SyncProvider 抽象 + WebDav/S3 适配 + ETag 支持 | 单测绿，provider 层可 mock 验证 |
| **M1** 增量引擎 | SyncManager 决策表 + settings/DB/附件 push/pull + 冲突副本 | SyncDecisionTest 全覆盖 + 本地 WebDAV mock 集成测试 |
| **M2** 启动链路 | checkpointWal 共享化 + SettingsSyncCodec + 启动钩子 + 立即同步按钮 | 双设备手动场景通过，DB 替换时序正确 |
| **M3** 自动调度 | SyncWorker 每日任务 + SyncTab 完整 UI | Worker 注册幂等，网络约束生效 |
| **M4** 坚果云适配 | `/dav` 规范化 + 429/503 冷却 + 实测 ETag/单文件上限 | 实测记录落文档 |
| **M5** 增强（P2） | 冲突记录 UI/清理、仅 Wi-Fi | 可选 |

依赖关系：M0 → M1 → M2 → M3；M4 可与 M1 并行（仅影响 WebDavSyncProvider 与实测）。

## 2. 任务分解

### M0 基础设施

**T1 提取 checkpointWal 为共享**（P0，最先做，阻塞 T4）
- 新增 `AppDatabase.checkpointWal()` 扩展或 `FilesManager.checkpointWal()`，放 `data/db/` 或 `data/files/`。
- 改 `WebDavSync.checkpointWal()` 引用共享实现，删除 private 版本。
- 验证：现有备份功能回归（构建 + 手动备份一次）。

**T2 SyncStateStore**（`data/sync/SyncStateStore.kt`）
- DataStore + `SyncState`/`FileSyncRecord`/`ConflictRecord`（按原文 §7 结构）。
- `deviceId` 首次访问时生成 UUID 并持久化。
- 提供 `syncInProgress` 互斥的 CAS 语义（读-改-写循环），防止并发重入。
- `syncedFiles` 为 `Map<String, FileSyncRecord>`，更新走整体替换（DataStore 粒度）。
- 验证：`SyncStateStoreTest`（互斥、pending 语义、deviceId 稳定）。

**T3 SyncProvider 抽象**（`data/sync/SyncProvider.kt`）
- `RemoteFileMeta` + `SyncProvider` 接口（按原文 §5.1）。
- **WebDavSyncProvider**（`data/sync/WebDavSyncProvider.kt`）：适配 WebDavClient。`ensureDirectory` → `ensureCollectionExists(path)`；`head` → `head(path)` 映射 etag；`list` → `list(path)`。
- **S3SyncProvider**（`data/sync/S3SyncProvider.kt`）：`ensureDirectory` 返回 `Result.success(Unit)`（S3 无目录）；`putFile` → `putObject(key=remotePath, file)`；`head` → `headObject`；`list` → `listObjects`。
- 注意：provider 只负责「单文件/单目录操作」，目录编排与决策全在 SyncManager。
- 验证：编译 + 用 mock provider 驱动的 SyncManager 测试。

**T4 WebDavClient ETag 支持**（阻塞 M1 的 remoteTag 决策）
- `head()`：读取响应头 `ETag` 存入 `WebDavResourceInfo.etag`。
- `propfind()`：请求体加 `<D:getetag/>`，解析 `<D:etag>` 文本。
- `WebDavResourceInfo` 增加 `etag: String? = null`（默认 null，兼容现有调用）。
- 验证：`WebDavClientTest`（mock 响应含/不含 ETag 两种路径）；坚果云实测放 M4。

### M1 增量引擎

**T5 SettingsSyncCodec**（`data/sync/SettingsSyncCodec.kt`）
- `toSyncableJson` / `fromSyncableJson`，剔除清单见 §4.2。
- 验证：`SettingsSyncCodecTest` —— 剔除后无 `apiKey`/`webDavConfig`/`s3Config` 等 key 泄露；`fromSyncableJson(json, local)` 保留被剔除字段为 local 现值。

**T6 SyncManager**（`data/sync/SyncManager.kt`）——本功能核心
- 决策表按原文 §5.2 实现，产出 5 分支。
- `sync()`：先 pull 再 push；内部按单元编排 settings/DB/upload/skills/fonts。
- 冲突处理按原文 §6：settings 取新即丢；附件留 `.conflict-<deviceId>`；DB 留 `.old-<timestamp>`，写 `ConflictRecord`。
- 附件变更检测：`syncedFiles` 记录 sha256 作为中间桥梁（原文 §5.2 核心思路）。
- 删除传播：本地文件消失且 meta 记录存在 → delete 远端 + 删 meta + 清 syncedFiles。
- 验证：`SyncDecisionTest` 5 分支全覆盖；mock provider 集成测试验证 push/pull/delete/冲突副本落盘。

**T7 DB 增量链路**（P0，依赖 T1）
- 上传：`checkpointWal()` → 复制为单文件 → push `db/rikka_hub.db` + 写 `db.meta.json`（sha256/timestamp/deviceId/size）。
- 下载：远端 db 更新 → 下载到 `cacheDir/tmp` → 校验 sha256 == meta.sha256 → 记 `dbRestorePending`（不直接替换活动 DB）。
- 冲突：meta timestamp 大者胜，本地留 `rikka_hub.db.old-<timestamp>`。
- 验证：mock provider 模拟双端改动。

### M2 启动链路

**T8 启动钩子**（RikkaHubApp.kt）
- 顺序：`applyPendingDbRestore()`（阻塞，必须在 `syncManagedFiles()` 之前）→ 启动协程 `startCloudSync()`。
- `applyPendingDbRestore`：校验文件存在 + sha → 覆盖 `databases/rikka_hub.db` 并删 `-wal`/`-shm` → 清 `dbRestorePending`。用 `runCatching` 包裹，失败只记日志不阻塞启动。
- `startCloudSync()` 按原文 §8 步骤 1-8 实现（读 syncConfig → 建 provider → 在线检查 → 间隔/互斥判断 → 置锁 → sync → 收尾）。
- 在线检查用 `ConnectivityManager`（registerActiveNetworkCallback 太重，直接 getActiveNetwork 一次判断即可）。

**T9 立即同步按钮**（最小入口）
- `BackupPage` 加第 5 个 `SyncTab`（最简版：开关 + 立即同步 + 上次同步时间），完整版在 T11。
- 调 `SyncManager.syncIfNeeded(Trigger.MANUAL, force = true)`。

### M3 自动调度

**T10 SyncWorker**（`data/sync/SyncWorker.kt`）
- `CoroutineWorker`，`PeriodicWorkRequestBuilder(intervalHours)`，`Constraints(NetworkType.CONNECTED)`。
- `enqueueUniquePeriodicWork("cloud_sync", UPDATE)`，注册点放 `DataSourceModule` 或 App 启动处。
- 与启动同步互斥靠 `syncInProgress` + `lastSyncTime`。
- 验证：注册幂等（重复启动不重复建任务）；断网返回 `retry()`。

**T11 SyncTab 完整 UI**
- 绑定 `syncConfig.enabled/provider/intervalHours/autoSyncOnLaunch`；状态区（上次同步、pendingSync 提示）。
- 配置字段 `SyncConfig` 并入 `Settings`（PreferencesStore.kt），**必须列入 §4.2 剔除清单**。

### M4 坚果云适配（可与 M1 并行）

**T12 坚果云适配**（WebDavSyncProvider 或 WebDavClient 层）
- 路径规范化：host `dav.jianguoyun.com` 且配置 path 为空 → 自动补 `/dav` 前缀。
- 429/503 冷却：解析 `Retry-After`，以 base URL 为 key 记 `temporaryBlockUntil`（默认 60s），冷却期内跳过请求并置 `pendingSync`。
- **实测**：① ETag 是否可靠返回（决定 remoteTag 用 etag 还是 lastModified，见 §4.1）；② 免费版单文件大小上限（DB 大时是否需分片）。
- 验证：实测结果记录到本文件 §7，驱动 §4.1 决策。

## 3. 顺序约束（硬性）

```
applyPendingDbRestore()        ← 最先，同步执行
   ↓ 必须在此之后才开始任何 get<AppDatabase>()
syncManagedFiles()             ← 现启动任务，保持原序
startCloudSync()               ← 异步协程，可放最后
```

- `applyPendingDbRestore` 失败不崩溃：`runCatching` + 日志，DB 以本地现状启动。
- `dbRestorePending` 路径文件放 `cacheDir`（启动已清理 cacheDir，见 RikkaHubApp.deleteTempFiles —— **需确认清理时机早于 applyPendingDbRestore**，否则 pending 文件会被删。若冲突，改放 `filesDir/.sync_pending/`）。
- T8 里把 `deleteTempFiles()` 的 cacheDir 清理挪到 `applyPendingDbRestore()` 之后，或改用独立目录。

## 4. 设计决策与偏离点

### 4.1 ETag 策略（文档未覆盖，新增）
- 先给 WebDavClient 加 ETag 解析（T4），`RemoteFileMeta.etag` 从 `WebDavResourceInfo.etag` 映射。
- 坚果云实测 ETag 可靠 → 用 ETag；不可靠/缺失 → 退化用 `lastModified`（`remoteTag = lastModified.toString()`），决策表逻辑不变，只换 tag 来源。
- S3 的 `S3ObjectMetadata` 若含 ETag 则直接映射，否则同样退化。

### 4.2 settings 剔除清单（初版，实现时逐字段过）
| 类别 | 字段 |
|---|---|
| 凭据 | `webDavConfig`、`s3Config`、`webServerAccessPassword`、`providers[].apiKey`（及各 provider 密钥类字段） |
| 同步配置自身 | `syncConfig` |
| 设备本地 | `launchCount`、`backupReminderConfig`、`deviceId`（若在 Settings 中） |
| 其余 | 白名单之外一律不同步（默认策略） |

实现方式：维护**可同步白名单**（explicit allowlist），不在名单的字段全部剔除，比黑名单更安全。

### 4.3 Migrator 处理（偏离原文 §十二）
- 原文「SettingsJsonMigrator 跟进 V4」**不适用**：Migrator 无版本号机制。
- 只需 `Settings` 加 `syncConfig: SyncConfig = SyncConfig()` 默认值；旧 JSON 反序列化自动补默认。无需改 Migrator。

### 4.4 同步专用 HttpClient（偏离原文 §2.1 复用共享 client）
- 原文建议复用 DataSourceModule 的共享 HttpClient。但共享 client 是 `readTimeout 10min`（AI 流式专用），同步要 `writeTimeout` 宽松（大 DB 上传）、`readTimeout` 适中。
- 建议 `DataSourceModule` 新增 `named("syncClient")`：connect 20s / read 120s / write 300s，OkHttp 引擎。WebDavSyncProvider/S3SyncProvider 注入该 client。

### 4.5 S3 目录语义
- S3 是扁平 key 空间：`ensureDirectory` 为空操作；目录由 key 前缀隐含。`listObjects(prefix="rikkahub-sync/upload/")` 需处理 `CommonPrefixes`（delimiter="/"）以恢复目录结构。

## 5. 验证计划

| 层级 | 内容 | 通过标准 |
|---|---|---|
| 单元 | SyncDecisionTest / SettingsSyncCodecTest / SyncStateStoreTest / WebDavClientTest | 全绿；决策表 5 分支全覆盖 |
| 集成 | mock SyncProvider（内存实现）驱动 SyncManager | push/pull/delete/冲突副本落盘路径验证 |
| 手动双设备 | A 改 B 拉 / B 改 A 拉 / 双端同改（冲突副本）/ 离线启动（pendingSync）/ DB 双端改动（keep_newest + old 副本）/ 重启后 DB 生效 | 清单全过 |
| 回归 | 现有整包备份/恢复 | 远端目录隔离（`rikkahub-sync/` vs `rikkahub_backups/`），WebDavSync 未改行为 |

## 6. 风险与缓解

| 风险 | 缓解 |
|---|---|
| DB 冲突丢数据（keep_newest） | 产品取舍，靠每日整包备份兜底；P2 可评估拆域合并 |
| ETag 不可靠（坚果云） | 退化 lastModified（§4.1）；先实测后定策略 |
| 同步配置含凭据 | 白名单剔除（§4.2），SettingsSyncCodecTest 断言无 key 泄露 |
| DB 替换时序 | §3 硬约束；`applyPendingDbRestore` 用 runCatching 不阻塞启动 |
| cacheDir 清理误删 pending 文件 | 改用独立目录 `filesDir/.sync_pending/` 或调整清理顺序 |
| 触发频率失控 | `intervalHours` 由 SyncManager 统一把关，Worker 只是兜底 |
| settings 白名单漏字段 | 实现时对 Settings 118 字段逐字段过一遍，白名单之外全剔除 |

## 7. 实测记录（M4 完成后回填）

| 项目 | 结论 |
|---|---|
| 坚果云 ETag 可靠性 | 待实测 |
| 坚果云免费版单文件上限 | 待实测 |
| 是否需要分片 | 待实测 |

## 9. 实现进度记录

| 任务 | 状态 | 验证 |
|---|---|---|
| T1 checkpointWal 共享化 | ✅ | 编译通过 |
| T2 SyncStateStore | ✅ | 5 用例全绿 |
| T3 SyncProvider + WebDav/S3 适配 | ✅ | 编译通过 |
| T4 WebDavClient ETag | ✅ | 编译通过 |
| T5 SettingsSyncCodec 白名单 | ✅ | 7 用例全绿 |
| T6 SyncManager 增量引擎 | ✅ | 11 + 8 用例全绿 |
| T7 DB 增量链路（meta 校验+冲突副本） | ✅ | 11 用例全绿 |
| T8 启动钩子（applyPendingDbRestore + startCloudSync + CloudSyncCoordinator） | ✅ | 编译通过 |
| T9 立即同步按钮（SyncTab） | ✅ | 编译通过 |
| T10 SyncWorker 每日任务 | ✅ | 编译通过 |
| T11 SyncTab 完整 UI + SyncConfig | ✅ | 编译通过 |
| T12 坚果云适配（/dav + 429/503 冷却） | ✅ 代码就绪 | WebDavClientTest 4 用例全绿；真实坚果云实测待做 |

**实现细节（相对本文档 §4 的落地说明）**：
- `db.meta.json`：`DbMeta(sha256, timestamp, deviceId, size)`，上传时随 DB 一并写入，下载时校验 sha256 一致才记 `dbRestorePending`。
- `CloudSyncCoordinator`（AppSync.kt）：统一处理 enabled 检查、在线检测（ConnectivityManager）、最小间隔、`syncInProgress` 互斥、provider 构建、force 手动触发。
- `Settings.syncConfig`：字段已加入 PreferencesStore（`sync_config` key），默认 `SyncConfig()`，**不参与** SettingsSyncCodec 白名单同步（设备本地项）。
- 冲突记录写入 `SyncStateStore.conflicts`（`recordConflict`），SyncTab 状态区展示 pendingSync / dbRestorePending。
- 冲突副本（`.conflict-<deviceId>` / `.old-<ts>`）被 `walkFiles` 排除，不参与后续增量。
- SyncManager 无 Context 依赖（filesRoot/syncPendingDir/dbAccess/settings 全注入），可纯 JVM 单测。
- lint 存量问题：仓库原有 235 个 error（MissingTranslation 187、LocalContextGetResourceValueCall 39 等）与本功能无关；本次新增文件 lint 0 error。

## 8. 建议执行顺序（合并）

1. **T1 checkpointWal 共享化** → 2. **T2 SyncStateStore** + 3. **T3 Provider 抽象** → 4. **T4 ETag** → 5. **T5 SettingsSyncCodec** → 6. **T6 SyncManager** → 7. **T7 DB 链路** → 8. **T8 启动钩子** → 9. **T9 立即同步按钮**（此处即可双设备实测）→ 10. **T10 Worker** → 11. **T11 SyncTab 完整 UI** → 12. **T12 坚果云适配+实测** → 回填 §7。

每完成 T6/T7 跑 `./gradlew test`；每次改动跑 `./gradlew assembleDebug` 验证编译。
