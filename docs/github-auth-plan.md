# GitHub 账号绑定与 API 认证方案（规划稿）

> 状态：已拍板，P0–P2 已实施（2026-09-06，待真机验证）
> 需求来源：个人定制需求——认证后 GitHub 相关操作（技能导入/更新、工作区 shell 命令）不再受未认证 API 限流，并解锁私有仓库
> 关联：b530dbab 技能更新检测（GitHub 来源登记 + SHA 对比）

## 1. 需求与目标

1. **应用内绑定 GitHub 账号**：在软件内完成 OAuth 认证（无需用户离开应用去生成 PAT，同时保留 PAT 粘贴兜底）。
2. **认证后解除限流**：`api.github.com` 请求从「未认证 60 次/小时/IP」提升到「认证后 5000 次/小时/用户」。
3. **全调用点覆盖**：技能导入/更新检查（`GitHubSkillClient`）自动携带凭据；工作区 shell（proot Ubuntu 内的 `curl`/`git`/`gh` 等）能拿到凭据；私有仓库可读（技能来源、git clone）。

非目标：不做 GitHub App（fine-grained permission）形态；不做多账号/组织账号切换；不支持 GitHub Enterprise Server；不做登录态驱动的任何云服务。

## 2. 现状盘点（本地代码事实）

**GitHub 调用点只有一个客户端**，改造面收敛：

| 调用点 | 位置 | 现状 |
| --- | --- | --- |
| `GitHubSkillClient` | `app/src/main/java/me/rerere/rikkahub/data/files/GitHubSkillClient.kt:33` | 技能导入/更新的唯一 GitHub 客户端。HttpURLConnection，**无任何 Authorization 头**；trees API（1 请求列全树）+ commits API（ETag 条件请求）+ raw 优先/contents API 回退下载 |
| `SkillUpdateManager` | `app/src/main/java/me/rerere/rikkahub/data/files/SkillUpdateManager.kt` | 消费上者的更新检测：12h 节流、304 不重复拉、403/404 推进节流窗口 |
| 应用自身更新检查 | 无 | 全仓库无其他 `api.github.com` 调用点（grep 已确认） |

**工作区 shell 是 proot Ubuntu rootfs**，环境注入点现成：

- `workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt:90`：`/usr/bin/env -i` 干净环境 + **显式环境变量列表**（HOME/PATH/CI/NO_COLOR 等）——追加 `GITHUB_TOKEN` 即可进入容器；
- `WorkspaceManager.kt:389` `executeCommand()` 是命令唯一入口，`WorkspaceShellContext`（`WorkspaceShellRunner.kt:12`）是参数载体，加一个 `extraEnv` 字段即可全链路打通；
- rootfs 是完整 Ubuntu：`git`/`curl` 可用，`gh` 可装。

**可复用的既有基础设施**：

- `data/secret/SecretStore.kt` + `AndroidSecretStore.kt`：Keystore AES-256-GCM 加密存储 + `SecretRefs` 引用规范（`keystore:<type>:<id>:<field>`）。**目前尚未在任何 DI 处接线**（为 agent 配置 P2 预留），本项目可顺带接线；
- `data/sync/SettingsSyncCodec.kt`：同步是显式白名单（`:27 ALLOWLIST`）+ 递归密钥剔除（`:122 SECRET_KEYS` 已含 `accessToken`/`client_secret`/`oauth`）——token 只要不进 `Settings` 顶层白名单就天然不同步；
- `RepositoryModule.kt:139`：`GitHubSkillClient()` 无参单例，注入 token provider 时改这一处；
- 设置子页模式：`ui/pages/setting/SettingXxxPage` + `RouteActivity.kt` 集中路由。

**限流事实边界**（明确预期）：受未认证配额约束的只有 `api.github.com` 三类请求（trees / commits / contents 回退）；`raw.githubusercontent.com` 是 CDN 不占 REST 配额，公共仓库的 `git clone` 也不走 REST 配额。绑定的收益 = API 请求 60→5000 次/小时 + **私有仓库可读** + search API 提额。

## 3. 主流做法调研

| 项目 | 形态 | 认证方式 | 凭据存放 | 对本方案的启示 |
| --- | --- | --- | --- | --- |
| GitHub CLI (`gh`) | CLI | 默认 web flow（`127.0.0.1` loopback 回调），headless 走 device flow | 系统 credential store；`gh auth setup-git` 装 git credential helper；`GH_TOKEN`/`GITHUB_TOKEN` 环境变量可覆盖 | 环境变量是 shell 场景的通行证；git 私仓克隆用 credential helper 思路 |
| Copilot CLI | CLI | **device flow 为默认**（`/login`） | 本地文件 | 无浏览器/无后端场景 device flow 是 GitHub 亲儿子 |
| Git Credential Manager | 桌面 | device flow / loopback 均支持 | 系统钥匙串 | device flow 是原生应用的主流选择 |
| GitHub Mobile | 移动 | 浏览器 OAuth web flow（有服务端托管 client_secret） | 服务端 | web flow 需要 client_secret 参与 token 兑换，**不适合无服务端的纯本地 app** |
| actions/checkout | CI | token 经 `http.extraheader=AUTHORIZATION: basic x-access-token:...` 注入 git | 环境变量 | 工作区 `git clone` 私仓的现成做法（免改 remote URL、免落盘凭据文件） |

**Device Flow 关键规格**（官方文档已核对）：

1. `POST https://github.com/login/device/code`（`client_id` + 空格分隔 `scope`，`Accept: application/json`）→ `device_code`、`user_code`（8 字符含连字符）、`verification_uri=https://github.com/login/device`、`expires_in=900`、`interval=5`；
2. 用户在任意浏览器打开 verification_uri 输入 user_code 授权（**不需要回调，不需要 client_secret**，前提：OAuth App 设置勾选 *Enable Device Flow*）；
3. 按 `interval` 轮询 `POST https://github.com/login/oauth/access_token`（`grant_type=urn:ietf:params:oauth:grant-type:device_code`）。错误码：`authorization_pending`（继续轮询）、`slow_down`（interval+5s）、`expired_token`（15 分钟超时重来）、`access_denied`（用户取消）、`device_flow_disabled`（App 未开开关）；
4. 得到 `gho_` 开头长期 token（GitHub OAuth 用户 token 默认不过期，**无需 refresh token**；失效仅发生在用户撤销或改密码触发全局失效，用 401 检测）；
5. 设备侧限制：user_code 提交 50 次/小时/应用，轮询频率违规触发 `slow_down`——正常使用打不满。

**限流数值**：未认证 REST 60 次/小时/IP；认证后 5000 次/小时/用户+应用；search API 未认证 10 次/分 → 认证 30 次/分。任何有效 token（即使零 scope）都能把 REST 提到 5000/h。

## 4. 方案选型

| 形态 | 需要注册 | 需要密钥保护 | 实现复杂度 | 用户体验 | 结论 |
| --- | --- | --- | --- | --- | --- |
| **OAuth Device Flow** | 自建 OAuth App（勾选 Device Flow），仅 `client_id` | 否（device flow 不用 secret） | 中 | 应用内弹窗显示 8 位码 + 一键跳浏览器 | **主路径** |
| Web Flow（loopback 回调） | OAuth App + 本机起 ServerSocket 监听 | **是**（token 兑换必须带 client_secret，嵌入 APK 等于公开） | 高 | 略快一跳 | 放弃：secret 内嵌在个人 fork 里无意义且更不安全 |
| PAT 粘贴 | 无 | 否 | 低 | 用户去设置页手动建 token | **兜底路径**（也覆盖 fine-grained PAT / 未来 GHE 场景） |

**Scope 选择**：请求 `repo read:user`——`repo` 才能读私有仓库（技能来源/git clone），`read:user` 取 login+头像做绑定展示。若只需提升配额（公共仓库），任意有效 token 即可；不愿授权 `repo` 时可在 PAT 兜底路径用零 scope/fine-grained token。授权管理入口：`https://github.com/settings/connections/applications/:client_id`。

**前置准备（需手动操作一次）**：在 github.com/settings/developers 注册 OAuth App（随便填 callback，勾选 *Enable device flow*），`client_id` 写入 `local.properties`（已 gitignore，符合仓库惯例）→ BuildConfig 注入；未配置时设置页隐藏绑定入口。

## 5. 总体设计

```
GitHubOAuthClient（纯 JVM：device flow 端点 + 轮询状态机）
        │ 产 token
        ▼
GitHubAuthManager（单例 StateFlow<GitHubAccount?>）
  ├─ token()：suspend 取当前 token（内存缓存）
  ├─ bindAndStore()：加密落盘（SecretStore）+ GET /user 拉账号
  ├─ markInvalid()：401 后置为失效态，UI 引导重绑
  └─ rateLimitSnapshot：最近响应的 X-RateLimit-Remaining/Limit/Reset
        │
        ├─→ GitHubSkillClient（构造注入 tokenProvider）：trees/commits/contents/raw 全部带 Bearer
        └─→ 工作区工具调用点：extraEnv 注入 GITHUB_TOKEN/GH_TOKEN + git extraheader
```

### 5.1 存储与安全

- 新增 `GitHubAuthStore`（自持 DataStore preferences key `github_auth_v1`，JSON：`{version, tokenCipher, login, avatarUrl, scopes, boundAt}`），**不进 `Settings` data class**——同步白名单天然隔离，改动面最小；
- token 密文经 `SecretStore`（Keystore AES-GCM）加密；`SecretRefs` 扩展 `fun githubToken() = "keystore:github:oauth:token"`；`RepositoryModule` 顺带接线 `AndroidSecretStore` 单例；
- Keystore 密钥不随备份迁移 → 配置备份/换机后密文不可解，`GitHubAuthManager` 解密失败即视为未绑定（与 `AndroidSecretStore` 既有语义一致，token 恰好也低价值可重取）；
- 明文 token 仅存在于进程内存；不写日志；PAT 兜底路径同管同存。

### 5.2 调用点改造（技能侧）

- `GitHubSkillClient` 构造改为 `GitHubSkillClient(private val tokenProvider: suspend () -> String? = { null })`，`request()`（`:249`）统一加 `Authorization: Bearer`（raw 请求也带：公共仓库无害，私有仓库 raw 通常可用，失败仍走既有 contents API 回退）；
- 403 且 `X-RateLimit-Remaining: 0` → 文案改「GitHub API 限流，绑定账号可提升至 5000 次/小时」；401 → 通知 `markInvalid()`；404 文案去掉「私有仓库暂不支持」改为「仓库不存在或无权访问（可在技能页登录 GitHub）」；
- `SkillUpdateManager` 不动（12h 节流 + 304 条件请求在 5000/h 下仍是好习惯）。

### 5.3 UI

- 新增 `ui/pages/setting/SettingGitHubPage`（`RouteActivity` 加路由），`SettingPage` 列表加「GitHub」条目：
  - 未绑定：`[使用 GitHub 登录]`（device flow 对话框：user_code 大字 + 复制 + 打开 `github.com/login/device` + 状态流转 pending→已授权/过期/取消）+ 折叠的 `[粘贴 Personal Access Token]` 兜底；client_id 未配置时入口隐藏并显示一句话说明；
  - 已绑定：头像 + login + scope + 配额余量（最近一次响应头快照 + reset 倒计时）+ 解绑（本地删除 + 外链授权管理页）；
- 技能页动态入口：`SkillsVM` 遇到限流类失败时，在错误提示旁给「绑定 GitHub 解除限流」跳转（对齐「入口按上下文动态化」偏好）。

### 5.4 工作区注入（shell 场景）

- `WorkspaceShellContext` 加 `extraEnv: Map<String, String> = emptyMap()`；`WorkspaceManager.executeCommand` 加同名参数透传；`ProotShellRunner.buildCommand` 在显式 env 列表后追加 extraEnv（`env -i` 语义下这是唯一通道，机制已存在）；
- app 层工作区工具调用点按开关传入：
  - `GITHUB_TOKEN` + `GH_TOKEN`（`gh` CLI 与多数工具识别）；
  - git 私仓克隆走 actions/checkout 做法：`GIT_CONFIG_COUNT=1`、`GIT_CONFIG_KEY_0=http.https://github.com/.extraheader`、`GIT_CONFIG_VALUE_0=AUTHORIZATION: basic <base64("x-access-token:<token>")>`——免改 remote URL、免写凭据文件、进程级隔离；
  - `curl https://api.github.com/...` 场景由 AI 自行带 `-H "Authorization: Bearer $GITHUB_TOKEN"`，在 shell 工具描述/系统提示中告知该变量存在；
- **开关与门控**：设置页总闸「向工作区 shell 注入 GitHub 凭据」**默认关**；开启后跟随既有能力模式门控（仅在允许工作区工具的模式下注入）；
- **安全声明（接受的风险）**：token 进入 AI 可控 shell 后可被 `env` 等命令读出，存在提示词注入外泄面；凭据走环境变量而非写文件/URL，已是本地化的最佳实践；P3 可选做输出脱敏（对 `ghp_|gho_` 前缀 token 串在 shell 输出管线替换为 `***`）。

### 5.5 失效与撤销语义

无 refresh token：API 401 → `markInvalid()` → 状态置「已失效」并引导重新绑定（不清账号展示位）；解绑仅删本地凭据，UI 附「撤销授权」外链（无 client_secret 无法程序化吊销，与 gh CLI 行为一致）。

## 6. 分期实施路径

**P0 认证核心 + 绑定 UI**
- 新增 `data/github/GitHubOAuthClient.kt`（纯 JVM 可测）、`GitHubAuthManager.kt`、`GitHubAuthStore.kt`；
- `data/secret/`：`SecretRefs.githubToken()`；`RepositoryModule` 接线 `AndroidSecretStore`；
- `local.properties` → `build.gradle.kts` BuildConfig `GITHUB_CLIENT_ID`；
- `SettingGitHubPage`（绑定对话框/解绑/PAT 兜底）+ `RouteActivity` 路由 + 设置列表条目；
- 验收：真机走通绑定→展示→解绑；`./gradlew :app:compileDebugKotlin`。

**P1 技能调用点接入**
- `GitHubSkillClient` token 注入 + 错误文案/限流识别；`RepositoryModule:139` 构造参数更新；
- 技能页限流动态入口 + 配额状态展示；
- 验收：绑定后技能更新检查请求带 Bearer（日志验证），触发 403 文案正确引导。

**P2 工作区注入**
- workspace 模块：`WorkspaceShellContext.extraEnv` + `WorkspaceManager.executeCommand` 透传 + `ProotShellRunner` env 追加（含 git extraheader 计算，放 workspace 模块纯函数可单测）；
- app 层工具调用点传参 + 总闸开关 + shell 工具描述更新；
- 验收：工作区内 `curl -s -o /dev/null -w '%{http_code}' https://api.github.com/user` 返回 200；私仓 `git clone` 成功。

**P3 可选打磨**：输出脱敏、账号元数据进同步白名单（仅 login/头像，不含 token）、PAT 失效主动探测、release 下载场景（若未来加应用内更新）。

## 7. 测试计划

- `GitHubOAuthClientTest`：device 响应解析、`slow_down` interval 累加、`access_denied`/`expired_token` 分支、grant_type 常量、`Accept: application/json`；
- `GitHubSkillClient` 注入测试：token 存在时请求头带 Bearer、为空时不带（把 header 组装抽成可测纯函数）；
- git extraheader base64 计算纯函数单测；
- `SettingsSyncCodec` 回归：`github_auth` 不在 `Settings`（无需改测试，加一条注释性断言防回归亦可）；
- 遵循仓库惯例：被 JVM 单测直调的文件禁 `android.util.Log`；Windows 下用目标模块任务验证而非全量 `test`。

## 8. 风险与开放问题

1. **client_id 注册是人工前置**：未注册时功能不可用（UI 明示）；device flow 忘勾选会报 `device_flow_disabled`，文案给指引；
2. **token 外泄面**（AI shell / 截断输出）——见 §5.4 声明，默认关闸由用户取舍；
3. **raw 私仓兼容性**未 100% 确定——既有 contents API 回退兜底，P1 验证时确认；
4. **开放问题（拍板项）**：① scope 一次要 `repo read:user` 还是先只 `read:user`（私仓需求出现再升格）；② shell 注入默认值（本稿建议默认关）；③ PAT 兜底是否 P0 就做（本稿建议做，实现成本半天以内）。

## 9. 实施记录（2026-09-06）

**拍板结论**（按推荐项执行）：scope 一次要 `repo read:user`；shell 注入总闸默认关；PAT 兜底并入 P0。P0–P2 全部落地（输出脱敏从 P3 提前到 P2，依据下述安全调研）。

### 9.1 安全设计对齐主流 agent

| 实践 | 主流参照 | 本实现 |
| --- | --- | --- |
| token 静态存储于系统级密钥库 | Claude Code 用 macOS Keychain、gh 用系统 credential store | `SecretStore`（Keystore AES-256-GCM），明文仅存进程内存 |
| 凭据经环境变量进入 agent shell | gh 的 `GH_TOKEN` 约定、Codex Cloud 的 secret 注入 | `extraEnv` → proot env：`GITHUB_TOKEN`/`GH_TOKEN` |
| 凭据能力按信任级别门控 | Codex sandbox 模式、Claude Code permission modes | 总闸默认关 + 仅工作区 shell 工具注入 |
| 日志/输出机密打码 | GitHub Actions secrets masking（Claude Code 不脱敏已是归档 issue anthropics/claude-code#32523） | `WorkspaceRepository`/`WorkspaceAsyncTaskRunner` 统一脱敏 stdout/stderr |
| 凭据不进 URL/argv | actions/checkout 的 `http.extraheader` 方案 | `GIT_CONFIG_*` 环境变量传 extraheader，不改 remote、不落盘 |

残余风险（与 OpenAI Codex CLI「AGENTS.md 注入窃取凭据」同类，主流 agent 均接受）：shell 内 `env` 可读 token，存在提示词注入外泄面。缓解 = 默认关闸 + 随时可在 GitHub 侧一键撤销授权。

### 9.2 落地清单

新增：
- `data/github/GitHubOAuthClient.kt`（Device Flow 端点 + 轮询状态机，纯函数可测）
- `data/github/GitHubAuthManager.kt`（独立 DataStore `github_auth` + Keystore 加密 + 绑定/失效/解绑编排）
- `data/github/GitHubShellEnv.kt`（shell env 构造 + 输出脱敏，纯函数）
- `ui/pages/setting/SettingGitHubPage.kt` + `GitHubAuthVM.kt`（绑定/解绑/PAT/注入开关/配额展示）
- 测试：`GitHubOAuthClientTest`（12 例）、`GitHubShellEnvTest`（6 例）

修改：
- `GitHubSkillClient.kt`：全请求 Bearer 注入 + 401 失效回调 + X-RateLimit 快照 + 限流/私仓文案引导
- `PreferencesStore.kt`：`workspaceGithubTokenEnabled`（不进同步白名单）
- `workspace` 模块：`WorkspaceShellContext.extraEnv` → `WorkspaceManager` → `ProotShellRunner`（env -i 下逐项显式追加）
- `WorkspaceRepository.kt` / `WorkspaceAsyncTaskRunner.kt`：env 注入 + 输出脱敏单点收敛
- `WorkspaceTools.kt`：总闸开启时工具描述告知 AI `$GITHUB_TOKEN` 可用
- DI（Repository/ViewModel Module）、`RouteActivity`（Screen.SettingGitHub）、认证入口行在 `UserProfileSettingPage`（设置 → 个人资料，展示绑定状态）、`build.gradle.kts`（`GITHUB_CLIENT_ID`）

### 9.3 验证与待办

- `:app:compileDebugKotlin` 通过；GitHub 相关 31 个 JVM 单测全绿。
- 人工前置（未做）：github.com/settings/developers 注册 OAuth App + 勾选 *Enable Device Flow*，`client_id` 写 `local.properties` 的 `GITHUB_CLIENT_ID`。
- P3 优化轮（2026-09-06 已实施）：①技能页未绑定动态入口；②配额展示 reset 倒计时；③授权弹窗重试状态；④账号元数据（login/头像/scope，不含 token）镜像进 Settings 并加入同步白名单，未绑定时引导重绑；⑤解绑确认弹窗带注入失效后果；⑥应用内「检查更新」（GitHubReleaseChecker 读 Releases，版本号三元组比较，关于页入口）。
- P3 待办：真机验证绑定全流程与私仓 clone、token 过期/撤销场景回归。
