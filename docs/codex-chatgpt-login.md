# OpenAI Provider 支持「ChatGPT 订阅登录」（Codex）

分支：`feat/codex-chatgpt-login`（基于 `master`，与主分支隔离）

## 背景

上游 [rikkahub/rikkahub#1753](https://github.com/rikkahub/rikkahub/issues/1753) 提出：OpenAI Provider 除 API Key 外，应支持用 ChatGPT 订阅（含免费账号的 Codex 额度）登录，直接使用订阅账号可用的 Codex 模型（如 `gpt-5.6-luna`、`gpt-5.6-terra`、`gpt-5.1-codex` 等）。

参考实现：[rikkahub/rikkahub#1752](https://github.com/rikkahub/rikkahub/pull/1752)（Draft）。本分支在其方案基础上适配本地 fork，并对照**官方 openai/codex 源码**（`codex-rs/login/src/device_code_auth.rs`、`auth/manager.rs`）与社区实现（rig PR #1615、dsh-llm-codex、con-terminal PR #244 等）逐项核实端点、参数与安全边界。

## 实现方式（新增配置 + 新增代码，不破坏现有功能）

- **认证方式默认仍为 API Key**（`OpenAIAuthType.API_KEY`），旧配置反序列化不受影响（新字段带默认值）。
- 订阅登录入口只在**官方 OpenAI 端点**（`api.openai.com` / `chatgpt.com`）显示；第三方 OpenAI-compatible Provider 不显示、也无法使用订阅凭据（authenticator 会硬校验 host）。
- 分享/导出 Provider 配置时剔除 `codexCredentials`，refresh token 绝不离机。
- 请求日志对 `Authorization` / `ChatGPT-Account-Id` / Cookie 等脱敏；`auth.openai.com` 请求体不打日志。

## 改动清单

### ai 模块（`me.rerere.ai`）

| 文件 | 改动 |
| --- | --- |
| `provider/ProviderSetting.kt` | 新增 `OpenAIAuthType`、`OpenAICodexCredentials`、`OPENAI_CODEX_BASE_URL`；OpenAI 增加 `authType`/`codexCredentials` 字段（默认 API_KEY/null） |
| `provider/ProviderManager.kt` | 透传 `OpenAICodexTokenProvider` 给 OpenAIProvider（默认 null，行为不变） |
| `provider/providers/openai/OpenAIRequestAuthenticator.kt`（新增） | 统一认证：API Key 原逻辑；订阅模式校验 host 后附加 Bearer + `ChatGPT-Account-Id` + `originator` |
| `provider/providers/openai/OpenAIProvider.kt` | 模型列表支持 Codex `/models?client_version=` 与 `models`/`data` 两种响应形状、过滤不可用/隐藏模型、保留 embedding 自动识别；各端点统一走 authenticator |
| `provider/providers/openai/ResponseAPI.kt` | 订阅模式强制 `stream=true`、剥离 temperature/top_p/max_output_tokens（Codex 后端 400）、显式 `Accept: text/event-stream`、非流式调用聚合流式响应（`collectStreamingTextGeneration`） |
| `provider/providers/openai/ChatCompletionsAPI.kt` | 统一走 authenticator |
| `provider/providers/openai/CodexSseContentTypeInterceptor.kt`（新增） | 兼容 Codex SSE 响应缺失 `Content-Type`（只包装官方 Codex 端点、只改 contentType 不缓冲流） |
| 测试 | `OpenAIProviderModelsTest`、`OpenAIRequestAuthenticatorTest`、`CodexSseContentTypeInterceptorTest`、`ResponseApiStreamingGenerationTest` 新增；`ResponseApiRequestMessageTest` 增加强制流式用例 |

### app 模块（`me.rerere.rikkahub`）

| 文件 | 改动 |
| --- | --- |
| `data/ai/openai/OpenAICodexAuthService.kt`（新增） | Codex 设备授权登录（usercode → 轮询 → 换 token）、refresh token 自动刷新（per-provider 互斥锁防并发烧 token）、凭据持久化到 Settings |
| `di/DataSourceModule.kt` | 注册 AuthService；OkHttp 增加 SSE Content-Type 兼容拦截器；ProviderManager 注入 token provider；日志脱敏 Cookie/ChatGPT-Account-Id |
| `data/ai/RequestLoggingInterceptor.kt` | 敏感头脱敏扩展 + auth.openai.com 请求体不打日志 |
| `ui/components/ui/ShareSheet.kt` | 分享时剔除 codexCredentials |
| `ui/pages/setting/SettingProviderDetailPage.kt` | 订阅模式隐藏余额配置；模型列表加载失败展示错误 |
| `ui/pages/setting/components/ProviderConfigure.kt` | OpenAI 设置页新增「认证方式」分段（API Key / ChatGPT 订阅）、登录/退出、设备码对话框 |
| `res/values*/strings.xml` | 新增 10 条字符串（en/zh/ja/zh-rTW/ko-rKR/ru 全量） |
| 测试 | `OpenAICodexAuthServiceTest` 新增；`ShareSheetTest`、`ProviderConfigureConvertToTest` 增加用例 |

## 认证流程（与官方 Codex CLI `codex --login` 一致）

1. `POST auth.openai.com/api/accounts/deviceauth/usercode`（body `{"client_id":"app_EMoamEEZ73f0CkXaXp7hrann"}`）→ 拿到 `user_code`。
2. 用户在浏览器打开 `https://auth.openai.com/codex/device` 输入一次性代码（App 内打开浏览器并复制代码到剪贴板）。
3. 轮询 `POST .../deviceauth/token`（403/404 = 待授权，15 分钟超时）。
4. 用返回的 `authorization_code` + `code_verifier` 在 `oauth/token` 换 `access_token`/`refresh_token`，并从 JWT 提取 `chatgpt_account_id`。
5. 之后请求 `chatgpt.com/backend-api/codex/*`：`Authorization: Bearer <access_token>` + `ChatGPT-Account-Id: <account_id>`。
6. access token 过期前 5 分钟自动用 refresh token 刷新（JSON body，与官方一致）；refresh token 单次使用即轮换。

## 使用方式

1. 设置 → Provider → OpenAI（官方端点）→「认证方式」选 **ChatGPT 订阅**。
2. 点「使用 ChatGPT 登录」→ 浏览器完成授权 → 回到 App 即登录成功，自动加载可用模型。
3. 选择 `gpt-5.6-luna` / `gpt-5.6-terra` / `gpt-5.1-codex` 等模型对话（以登录后拉取的模型列表为准）。

### 免手机验证：导入已有凭据（auth.json）

设备授权有时会触发 OpenAI 的手机号验证。如果已在其它环境（如 PC 的 Codex CLI、或
[Codex Auth JSON 生成器](https://codexauth.moshushi.xyz/) 等工具）拿到官方 `auth.json`，
可以直接粘贴导入，跳过设备授权与手机验证：

1. 在「ChatGPT 订阅」下点 **「导入凭据」**。
2. 粘贴官方 `auth.json` 内容（`tokens.access_token / refresh_token / account_id / id_token`），
   或任何包含 `access_token` + `account_id` 的 JSON（snake_case / camelCase 均可）。
3. 确认导入：凭据写入本机并激活订阅模式；含 `refresh_token` 时支持自动续期。

要点：
- 导入的凭据与登录所得凭据**同等对待**：只发送给官方 Codex 端点，分享配置时剔除，日志脱敏。
- 只有 access token（无 refresh_token）的凭据可用到 token 过期，过期后需重新导入或走设备登录
  （界面会提示）。
- 第三方生成/中转的凭据属 OpenAI 条款灰色地带，自行承担账号风险；不要把他人的 auth.json
  发给你。

## 已知限制 / 风险

- **未合入上游**：本分支为个人 fork 定制；上游 PR #1752 仍是 Draft。合入上游后可用 `git fetch upstream && git merge upstream/master` 跟进。
- 订阅认证走非公开的 `chatgpt.com/backend-api/codex`，OpenAI 可能随时调整协议/限流；Token 失效需重新登录。
- 免费账号的 Codex 有速率/额度限制（独立于 API Key 计费）。
- 模型可见性取决于账号：ChatGPT 网页可用模型 ≠ Codex 后端开放模型（如 `gpt-5.6-sol` 在 ChatGPT 账号登录态下通常不可用）。
- 属服务条款灰色地带，仅限个人使用，注意账号安全。
