# 工作目录（cwd）生效与目录外写入审批规划

> 背景：会话级 `workspaceCwd` 已可由「更多选项 → 工作区卡片」设置（点击卡片切目录、右侧图标进文件目录），但实际效果有限——除 shell 默认目录与提示词注入外，文件类工具感知不到 cwd，形同空壳。本文档规划如何让 cwd 真正生效，并为「AI 操作工作目录以外的文件」补上审批边界。

## 1. 现状盘点

### 1.1 cwd 数据流

- 会话级：`conversation.workspaceCwd`（UI 写回时同步存 `assistant.defaultWorkspaceCwd` 作为新会话默认）。
- 装配：`ChatToolRegistry.createWorkspaceToolsIfReady(workspaceId, conversation.workspaceCwd)` → `createWorkspaceTools(cwd=...)`；子代理（`SubAgentToolAssembler`）与讨论（`DiscussionToolAssembler`）装配同样已传 cwd（子代理取父会话值）。
- 提示注入：`WorkspaceReminderTransformer` 在 `<workspace>` 段注入 `- cwd: X` 一行。

### 1.2 cwd 当前生效点（仅 3 处）

| 生效点 | 方式 |
| --- | --- |
| `workspace_shell` | `shellCwd` 作为命令默认 cwd + schema 描述 |
| 系统提示 `<workspace>` | `- cwd: X` 一行 |
| 输入栏 `@` 补全 | `WorkspaceCompletionProvider` 以 cwd 为起点 |

### 1.3 空壳根源

`workspace_read_file / write_file / edit_file / list_files / glob / grep` 的 `path` 语义均为「Rootfs 绝对路径」，缺省 `/workspace` 根，与 cwd 无关。模型即使被告知 cwd，读写默认仍落在 `/workspace` 根；相对路径直接被 `require(startsWith("/"))` 拒绝。

### 1.4 审批现状

- `Tool.needsApproval: (JsonElement) -> Boolean`：签名带输入 JSON，具备**按路径动态审批**的能力，目前只用于静态 per-tool 判定。
- 默认审批表 `WorkspaceToolDefaultApprovals`：仅 `workspace_shell` 需审批；工作区级 `toolApprovals` JSON 覆写（`WorkspaceEntity.toolApprovalOverrides()`）。
- 可写区硬边界：write/edit 目标在 `/workspace`、`/tmp` 之外 → 审批强制 true，且 execute 直接报错引导 `trusted_folder_*`（真实设备文件走信任文件夹通道）。
- 子代理/讨论装配 `forceNoApproval = true`（可信委派先例），仅保留可写区路径检查。
- shell 事后 diff：`addedFiles / modifiedFiles / removedFiles` 已上报。

## 2. 目标

1. cwd 成为文件类工具的默认操作基准，切目录后 AI 行为随之改变（消除空壳感）。
2. 写入/编辑落在 cwd 之外时需要用户审批，形成「cwd 内自由、cwd 外审批、`/workspace` 外禁止」三层边界。
3. 不新增通道、不放开沙盒边界，与信任文件夹体系保持分工。

## 3. 分级方案

### P0 — cwd 全链路生效

改动集中在 `WorkspaceTools.kt` + `WorkspaceReminderTransformer.kt`：

1. **缺省路径 = cwd**：`list_files / glob / grep` 的可选 `path` 缺省从 `/workspace` 改为 cwd；`read_file / write_file / edit_file` 的 `path` 接受相对路径，按 cwd 解析（`notes/a.md` → `${cwd}/notes/a.md`）。
2. **schema 描述同步**：`putPathProperty` 与各工具 description 改为「relative to the current working directory (X); absolute Rootfs paths also accepted」，把 cwd 值直接写进 schema（沿用 shell 工具 `Defaults to '$defaultCwd'` 的既有手法）。
3. **系统提示 `<workspace>` 段终稿**：

```
- cwd: {cwd ?: "/workspace"} · workspace_* tools resolve relative paths against it; use absolute paths to reach other directories (writes outside cwd require approval).
```

4. `createWorkspaceTools` 的 `cwd` 参数语义从「shell 默认目录」升级为「工具链工作目录」，三个装配点无需改动（已传值）。

验收：切 cwd 后，模型不指定绝对路径时读写/list/glob 默认落在 cwd 下；同会话内 cwd 不变则 prompt 前缀缓存稳定（cwd 属会话稳定值，仅用户主动切换时失效一次，可接受）。

### P1 — cwd 外写入动态审批

1. **边界定义**：cwd 子树内=自由区（按 per-tool/per-workspace 审批策略）；`/workspace` 内 cwd 子树外=审批区；`/workspace`、`/tmp` 之外=禁区（现状报错引导，不变）。读取类工具（read/list/glob/grep）不设 cwd 边界——信息只进不出。
2. **实现**：`workspace_write_file / workspace_edit_file` 的 `needsApproval` lambda 追加 cwd 判定，与现有 `pathOutsideWritableRoots` OR 合并：

```kotlin
needsApproval = { input ->
    needsApproval("workspace_write_file") || input.pathOutsideWritableRoots("path") || input.pathOutsideCwd(cwd)
}
```

   `pathOutsideCwd` 解析失败按 cwd 外处理（fail-closed，与 `pathOutsideWritableRoots` 的 `.getOrDefault(true)` 先例一致）。
3. **审批不可绕过**：cwd 外写入审批为安全边界，**不受**工作区级 `toolApprovals` 覆写关闭（覆写只管理 cwd 内的常规审批策略）；`forceNoApproval`（子代理/讨论）维持跳过——可信委派先例，仍受禁区检查约束。
4. **审批 UI**：待审批卡片展示「目标路径 + 当前工作目录 + 超出原因」，用户可判断放行与否（路径信息取工具输入 JSON，`ToolUI` 渲染时已有 input 可用）。
5. **shell 例外**：命令任意、无法静态判定，维持现状（默认审批 + 事后 diff 上报）；不在 P1 做命令文本启发式分析（误报多、收益低）。

### P2 — 可选增强（按需取舍）

1. **会话级审批记忆**：批准 cwd 外写入时提供「本会话不再询问」（`ToolApprovalState.Auto` 语义扩展，会话级 allow 集合，不落库）。
2. **cwd 状态可见性**：输入栏或模式面板展示当前 cwd pill，点击复用 `WorkspaceCwdPickerSheet`（可与卡片入口并存，看实际使用频率再定）。
3. **换目录时回收未决审批**：会话中途切 cwd 后，已 Pending 的 cwd 外审批重新按新 cwd 判定（简单做法：切目录时清空 Pending 审批状态）。

## 4. 明确不做

- 不放开 `/workspace`、`/tmp` 之外的沙盒写入（维持报错引导 `trusted_folder_*`，避免双通道混乱）。
- 不提供「AI 自主切换 cwd」工具：目录主权在用户，AI 需要跨目录时用绝对路径 + 审批。
- 不做 shell 命令文本的 cwd 外路径静态分析。

## 5. 实施顺序与风险

| 阶段 | 内容 | 风险 |
| --- | --- | --- |
| P0 | 缺省路径/相对路径解析 + schema/提示词 | 低；旧消息中的历史工具调用按当时的绝对路径执行，不受影响 |
| P1 | write/edit 动态审批 + 审批卡片展示 | 低；`needsApproval` 每次流式循环调用，纯路径字符串计算无性能问题；注意 `inputAsJson()` 的 JsonNull 容错（fail-closed） |
| P2 | 按需 | — |

P0、P1 各出一个 PR，均以 `:app:compileDebugKotlin` + 既有 workspace 工具单测回归验证。

## 6. 落地记录

**2026-08-30：P0 + P1 已实施**（读取类工具确认不设 cwd 边界，与第 3 节 P1 边界定义一致）。

- 新增 `data/ai/tools/WorkspacePaths.kt`：cwd 归一化、工具输入路径解析（相对 → 以 cwd 为基准）、子树/可写区判定的纯函数模块。cwd 的绝对性判定取原始输入首字符（先判后转换分隔符），避免 Windows 风格相对路径被转换后的前导 `/` 误判为绝对路径。
- `WorkspaceTools.kt`：`createWorkspaceTools` 的 `cwd` 参数升级为工具链工作目录；`read/write/edit` 接受相对路径，`list/glob/grep` 缺省目录改为 cwd；7 个工具的描述与 `path` schema 内嵌当前 cwd 值；新增 `workspaceWriteNeedsApproval`（write/edit 按输入路径动态审批，判定顺序：解析失败 fail-closed → 可写区外强制审批 → forceNoApproval → 覆写 → cwd 边界）。
- `GenerationHandler.kt`：workspace 文件工具串行化锁 key 的 path 按 cwd 归一为 Rootfs 绝对路径，同一文件的相对/绝对两种写法串行到同一把锁。
- `WorkspaceReminderTransformer.kt`：`<workspace>` 段 cwd 行终稿——`- cwd: X · workspace_* tools resolve relative paths against it; use absolute paths to reach other directories (writes under /workspace outside cwd require approval).`（与规划文本的差异：审批域表述精确为 "under /workspace outside cwd"，与 `/tmp` 豁免的实现保持一致，避免模型对 /tmp 写入的审批预期落空。）
- `ToolUI.kt`：修正审批目的映射 key（`workspace_write` → `workspace_write_file`，`workspace_edit` 同理，原 key 与实际工具名不符导致走不到映射）；write/edit 审批目的追加目标路径摘要。
- 测试：新增 `WorkspacePathsTest`（19 例，覆盖归一化/解析/子树/审批判定与 fail-closed 行为），tools 包 88 例全绿。实施中单测捕获并修复一处 cwd 归一化 bug（Windows 风格相对路径误判绝对）。
- 未实施（待真实需求）：P2 全部；写入审批无会话级记忆项（同会话反复写 cwd 外文件时每次需确认）。

**2026-08-30：回归修复——文件变更卡片「定位文件位置」报 `Path does not exist`。**

P0 允许 write/edit 传相对 cwd 的相对路径后，`ChatMessageEditedFiles` 的变更提取仍存**原始入参 path**，
而 `resolveWorkspacePath` 把一切非 `/workspace` 开头的路径归入 LINUX 存储区——相对路径被当成 rootfs 内
相对路径去定位，落到错误存储区/目录，报 `Path does not exist: <相对路径首段目录>`（文件实际存在于
`/workspace` 下）。双层修复：

- **提取层（根因）**：write/edit 的变更 path 优先取工具**输出**里的 `path` 字段（写入后 stat 回显的
  resolved Rootfs 绝对路径），输出缺失（流式中途）才回落 input 原始入参。新增的 shell diff 路径本就是
  `/workspace/` 绝对口径，不受影响。
- **定位层（历史消息兜底）**：`resolveWorkspacePath` 统一 `\` → `/` 分隔符；相对路径按 cwd 解析规则
  （cwd 恒在 `/workspace` 下）归入 FILES 区近似定位，不再误判成 LINUX 区。历史会话若当时 cwd 是
  `/workspace` 子目录，定位可能落到上级目录（无输出路径可用，属可接受的 best-effort）。
- 测试：`FileChangesExtractTest` 新增 3 例（输出路径优先 / 无输出回落 input / 非 JSON 输出容错），
  21 例全绿；`WorkspacePathsTest` 19 例回归通过。
