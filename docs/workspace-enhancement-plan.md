# Workspace 能力强化方案（复刻桌面级操作）

> 2026-09-19 起草，待拍板。目标：让工作区更接近一台「真电脑」——长驻服务、脚本、自启动、定时任务。

## 1. 现状盘点

workspace 模块现状（`workspace/` + app 层接线）：

| 能力 | 现状 | 缺口 |
|---|---|---|
| 单次命令 | `workspace_shell`：每次调用一个全新 proot 进程，`--kill-on-exit`，默认 30s / 上限 600s | `nohup xxx &` 随调用结束被杀——**无法启动任何常驻进程**，这是「复刻电脑」的第一硬缺口 |
| 后台任务 | `workspace_shell_async`：daemon 线程池 + 任务注册表，输出落 `/tool_outputs`（24h） | 本质仍是「延长版单次调用」：上限 600s，进程被超时强杀；任务表仅进程内存活 |
| 交互终端 | termux `TerminalSession` + proot，页面导航不杀会话（`WorkspaceTerminalSessionManager`） | 仅 UI 场景；AI 无法往里发命令/读输出；app 进程死后全会话消失，无 FGS/wake lock 保活 |
| 环境持久化 | `workspace_set_env` → rootfs `/etc/profile.d`，登录 shell 自动加载 | 已覆盖 env；无「每次 shell 前自动跑脚本」的等价机制 |
| Rootfs 补丁 | DNS/hosts/hostname/locale/组/tmp/pip 镜像（`RootfsPatcher`） | 无自启动钩子挂点 |
| 定时任务 | 无 | 无 cron / systemd timer 等价物 |
| 开机自启 | 无（无 BOOT_COMPLETED receiver） | — |

可复用的既有基建：

- **FGS 模式**：`ChatGenerationForegroundService`（生成期前台保护）；`WebServerService`（常驻前台服务先例）。
- **调度经验**：`docs/proactive-message-plan.md` 已沉淀 AlarmManager 精确闹钟 + BootReceiver + FGS 台账补做的完整血泪清单。
- **内嵌 Web 服务**：`web` 模块（Ktor）已能常驻开端口，可作 rootfs 内网络服务的反代出口。
- **持久化**：`WorkspaceEntity`（Room），可直接加列承载自启动配置。

## 2. 方案总览（按依赖排序）

```
P0 常驻会话（地基）──→ P1-A FGS 保活
                    ├→ P1-B 自启动脚本（shell 级 → 工作区级 → 设备级）
                    └→ P1-C 定时任务
P2 局域网服务反代 / rootfs 快照 / 运行面板
```

## 3. P0：常驻会话与守护进程通道（地基）

**问题**：proot `--kill-on-exit` + 每次调用新进程，决定了当前架构下 AI 起不了任何 daemon。jupyter、ssh 隧道、dev server、监控脚本统统起不来。

**方案**：新增 `WorkspaceDaemonManager`（AppScope 单例，与 `WorkspaceTerminalSessionManager` 平级），持有**命名常驻会话**：

- 复用 termux `TerminalSession` 的 headless 用法（它已解决 pty + 进程生命周期管理，且是既有依赖）+ `WorkspaceTerminalSession.kt` 里的 proot 命令行拼装；不走 UI，纯后台。
- 每个 daemon 的输出环形缓冲落盘到 `/tool_outputs/daemon-<name>.log`（对齐三层截断预算语义，head+tail），终端 transcript 天然就是输出源。
- app 进程死亡即全部 daemon 消失——由 P1-A 的 FGS 缓解，方案里明确「不承诺跨进程存续」，重启后 daemon 表重建（Room 记录期望清单，启动时对照实际重建/标记丢失）。

**新增 AI 工具组**（对齐 tmux/nohup 心智）：

| 工具 | 语义 | 审批 |
|---|---|---|
| `workspace_daemon_start` | 在命名会话里启动长驻命令，立即返回；重名可复用（幂等） | 走审批 |
| `workspace_daemon_status` | 列出/查询单个 daemon：运行状态、存活时长、输出尾部 | 只读免审 |
| `workspace_daemon_send` | 向 daemon stdin 写入文本（回答交互提示、发控制命令） | 走审批 |
| `workspace_daemon_kill` | 终止指定 daemon | 走审批 |

**审批与能力隔离**：daemon 一旦启动就脱离了「每次调用 before/after 快照 diff」的管控模型，文件改动在 status 时不再上报（成本太高）。补偿手段：
- `daemon_start/kill/send` 全部走审批（含 `resolveWorkspaceToolApproval` 开关）；
- 能力隔离对齐既有决策——daemon 工具组的管理入口归 CREATIVE 能力，UNRESTRICTED 收紧语义不变；
- `daemon_status` 返回输出尾部 + 存活时长即可，模型用普通 shell 自查 `/workspace` 变更。

**验收**：AI 能起一个 `python -m http.server`，跨多轮对话仍存活，`daemon_status` 能读到请求日志，`daemon_kill` 能杀掉。

## 4. P1-A：服务保活（FGS）

**问题**：app 退后台后，进程随时可能被杀（尤其无前台服务时），daemon/async 任务/终端会话随之消失。

**方案**：`WorkspaceKeepAliveService`（FGS），照抄 `ChatGenerationForegroundService` + proactive plan 的成熟模式：

- **触发条件可配**（默认关）：存在 RUNNING 的 daemon 或 async 任务时自动 acquire，全部终态后自动 release；提供设置页总开关（「工作区保活」）。
- 常驻通知即诊断探针（proactive plan 规范 12）：用户报「服务没了」先看通知在不在。
- 可选 wake lock（CPU 保活），仅在有 RUNNING daemon 时持有。

**明确写进文档的边界**（不做的承诺）：
- Android 15 起 `dataSync` 型 FGS 有 6 小时上限；超长任务靠 daemon 表 + 重建兜底，而不是硬扛系统。
- Doze 下单次 CPU 窗口仍受限；网络型 daemon 断连后靠自身的重试逻辑（这是「复刻电脑」做不到 100% 的部分，如实告知用户）。

## 5. P1-B：自启动（三级）

> **实施记录（2026-09-19）**：shell 级已落地，实现与下文设想有一处关键差异——不走 profile.d source，而是 app 侧 `WorkspaceAutostartRunner` 直连 `WorkspaceManager.executeCommand` 执行。原因：profile.d 会在**每次** shell 调用时重复执行脚本（副作用重复），而「自启动」语义是每次打开 app 跑一遍；app 侧执行还免去动 RootfsPatcher、天然获得每脚本超时（120s）与结果回传（内存态，UI 可视化）。工作区级/设备级仍按原设想待做。
>
> **实施记录（同日追加）：服务型脚本（P0 最小版）已并入自启动体系**。`*.service.sh` 结尾的脚本由 `WorkspaceManager.spawnCommand`（workspace 模块新增 spawn 路径，不等待不设超时）启动，app 持有 Process + 有界输出缓冲 + 退出监视线程，直到手动停止或 app 退出（`--kill-on-exit` 连带清理 rootfs 子进程）；解决「wb2api/jupyter 等服务随脚本结束被杀」的问题。触发时机升级为 **app onCreate 即引导全部就绪工作区**（`autostartBootAll`），shell/终端首访钩子幂等保留。开关语义：服务型脚本的开/关 = 启动/停止进程；AI 工具新增 `start`/`stop` 动作。仍缺 FGS 保活（P1-A）：app 退后台被杀则服务随之终止。

- 已实现：约定目录 `/workspace/.rikka/startup/*.sh`（停用 = 改名 `.sh.disabled`），按文件名顺序执行（bash -l，cwd=/workspace）；触发点 = AI shell / async 任务 / 终端首访（每进程一次）；详情页「基本」Tab 可视化管理（列表/开关/编辑/删除/立即执行/运行日志）；AI 工具 `workspace_autostart`（list/add/remove/enable/disable/run）。
- 脚本内容可直接用 workspace_write_file 编辑（目录在 /workspace 子树内）；输出尾部仅存内存（进程重启即失），不落盘避免污染 shell 文件变更快照。

**shell 级（最便宜，先做）**：
- 约定目录 `/workspace/.rikka/startup/*.sh`，由登录 shell 自动 source。落点：`RootfsPatcher` 写一个 `/etc/profile.d/rikka-startup.sh`，内容就是遍历 source 该目录。
- AI 用现有 shell 工具即可写脚本，**零新工具**；与 `workspace_set_env` 的 profile.d 机制同源。
- 语义对齐「每台电脑的 bashrc」。

**工作区级**：
- `WorkspaceEntity` 加 `autoStart` JSON 列（有序命令列表 + 备注）。
- 执行时机：app 冷启动 & rootfs 就绪后、工作区首次访问时，经 `WorkspaceDaemonManager` 以 daemon 形式跑（长驻型）或 one-shot proot（初始化型，如 `pip install -r /workspace/requirements.txt`）。
- UI：工作区详情页加「自启动」管理区；`workspace_daemon_*` 工具组附带一个 `workspace_autostart` 管理工具（list/add/remove/reorder）。
- 语义对齐「登录后自启的程序 + 开机跑一次的初始化脚本」。

**设备级（P2，最后做）**：
- `BOOT_COMPLETED` receiver → FGS → 按 autoStart 清单执行。完整照抄 proactive-message-plan 的可靠性清单（FGS 先行、台账补做、单一路径、通知即探针）。
- 需用户在系统设置里手动放开自启权限（各家 ROM 差异大），设置页提供跳转引导。

## 6. P1-C：定时任务（cron 等价物）

- rootfs 内跑 cron 不可行（无 init、进程随调用死），走 **app 级调度**：`AlarmManager.setExactAndAllowWhileIdle` → Receiver → FGS → 经 DaemonManager/one-shot 执行注册命令 → **先续订下一次**再执行（proactive plan 规范 1）。
- 任务定义持久化在 Room（`WorkspaceScheduleEntity`）：cron 表达式或间隔、命令、cwd、启用状态、上次执行结果台账。
- 工具：`workspace_schedule`（list/add/remove/toggle）；UI 复用自启动管理区旁边。
- 与 proactive-message-plan 共享 AlarmManager/FGS 基建但**路径独立**（那边推消息、这边跑命令），只共享 receiver→FGS 的骨架代码。

## 7. P2：锦上添花

| 项 | 内容 | 依赖 |
|---|---|---|
| 局域网服务反代 | `web` 模块 Ktor 增加端口转发：把 rootfs 内 localhost 端口（jupyter、dev server）暴露给局域网/本机浏览器 | P0（daemon 先得活着） |
| rootfs 快照 | 现在 `backupFiles` 只覆盖 files 区；增加 rootfs 打包/恢复（tar + 排除 /tmp /proc），换机、装坏后一键回滚 | 无 |
| 运行面板 | 工作区详情页展示 daemon 列表 + `/proc` 读 CPU/mem + 退出通知（对齐 async 任务的 AppEventBus 广播模式） | P0 |
| 预装脚本 | rootfs 装完跑一次的「常用工具包」（git/python/node + pip/npm 镜像），降低每工作区重复配置成本 | P1-B（shell 级自启动即其载体） |

## 8. 风险与约束

1. **审批面扩大**：daemon/schedule 类工具天然是「一次授权、长期生效」，务必全部接 `resolveWorkspaceToolApproval` + 显式审批，不允许任何免审启动路径。
2. **输出预算**：daemon 日志环形缓冲必须复用三层截断预算（128K/10K/32K+16K）语义，改任何一层需对齐其余（见既有约定）。
3. **「跳过」文案禁令**：保活/自启动相关 UI 按钮不得使用「跳过」字样（无障碍自动点击误触）。
4. **Android 15 dataSync 6h 上限**：保活不是无限保活，daemon 表 + 重建是兜底主路径。
5. **Room 迁移**：`WorkspaceEntity` 加列、新增 `WorkspaceScheduleEntity` 各需一次 schema 迁移 + androidTest migration 测试（KSP 导出 schemas）。
6. **不承诺跨进程存续**：所有「重启后」语义都以 Room 期望清单 + 启动时重建为准，文档如实写。

## 9. 建议实施顺序

1. **P0 常驻会话 + 工具组**（地基，纯 app 层，可独立交付验收）
2. **P1-B shell 级自启动**（RootfsPatcher 一个文件 + 约定目录，成本极低）
3. **P1-A FGS 保活**（依赖 P0 才有意义）
4. **P1-B 工作区级自启动**（Room 迁移 + UI）
5. **P1-C 定时任务**（复用 2-4 的骨架）
6. P2 按需挑
