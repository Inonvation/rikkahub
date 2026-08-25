# RikkaHub 支持「ChatGPT 订阅登录 OpenAI Codex」可行性分析

> 调研对象：[rikkahub/rikkahub#1753](https://github.com/rikkahub/rikkahub/issues/1753)（需求）与 [rikkahub/rikkahub#1752](https://github.com/rikkahub/rikkahub/pull/1752)（Draft 参考实现）
> 调研时间：2026-08-25 ｜ 结论：**技术可行，官方方向一致；但尚未合入，当前本地 App 里没有该功能入口**

## 1. 需求（Issue #1753）

- 希望 OpenAI Provider 除 API Key 外，支持使用 **ChatGPT 订阅登录**，从而使用订阅账号（含免费账号的 Codex 额度）可用的 **Codex 模型**。
- 涉及：设备授权、Access Token / Refresh Token 保存、账号 ID、Codex 后端特殊请求逻辑。
- 作者已给出可运行参考实现（Draft PR #1752），并主动提出安全顾虑（Token 本地存储与刷新、凭据不泄漏到第三方 OpenAI-compatible 地址、分享配置不含登录凭据）。
- 状态：**open，0 条评论**（截至 2026-08-25，维护者尚未回复）。

## 2. 参考实现（PR #1752，作者 Ayuilos）

状态：**open / Draft / 未合并**（base = rikkahub/rikkahub，head = Ayuilos/Miffan 的 `codex/provider-subscription-auth` 分支；最后更新 2026-08-24，仍在活跃推进）。

核心改动（`ai` 模块为主）：

| 文件 | 改动 |
| --- | --- |
| `ProviderSetting.kt` | 新增 `OpenAIAuthType`（`api_key` / `chatgpt_subscription`）、`OpenAICodexCredentials`（accessToken/refreshToken/accountId/expiresAt/email/planType）、`OPENAI_CODEX_BASE_URL = https://chatgpt.com/backend-api/codex` |
| `OpenAIRequestAuthenticator.kt`（新增） | 订阅模式下强制 host 必须是官方 Codex 端点，附加 `Authorization: Bearer <token>` + `ChatGPT-Account-Id` + `originator` 头；API Key 模式行为不变 |
| Codex Device Authorization | 浏览器设备授权登录 / 退出 / Token 刷新（与官方 Codex CLI 的 ChatGPT 登录同一机制） |
| `OpenAIProvider.kt` | 订阅模式模型列表走 `/models?client_version=0.148.0`，解析 `data/models/slug`，过滤 `supported_in_api=false` 与 `visibility=hide` |
| `ResponseAPI.kt` | 订阅模式强制 `stream=true`（Codex 后端要求流式）；自定义 `SSEEventSource` 兼容**缺失 Content-Type** 的 SSE（OkHttp 默认 EventSource 会拒绝） |
| 分享/导出 | 分享 Provider 配置时移除 `codexCredentials`，避免登录凭据外泄 |

作者自述验证：单元测试（device auth / token / SSE / 配置清理）+ `assembleDebug` 通过 + **Android 真机手动验证**登录、模型加载与三种连接测试。

## 3. 现状核实（本地与上游）

- upstream `master` 的 `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt`：**没有** `authType` / `codexCredentials` 字段 → PR 未合入。
- 本地仓库 `F:\git\rikkahub`（fork：`origin = Inonvation/rikkahub`，`upstream = rikkahub/rikkahub`，分支 `master`）：grep `OpenAICodexTokenProvider|OPENAI_CODEX_BASE_URL|CHATGPT_SUBSCRIPTION|OpenAIAuthType` **无任何匹配** → 本地代码没有该功能。

> **结论：你目前安装/构建的 RikkaHub 里没有「登录 Codex / ChatGPT 账号」的入口，还不能直接用订阅账号对话。** 要等 PR 合入上游，或手动把该分支合入自己的 fork。

## 4. 可行性评估：高

1. **机制是 OpenAI 官方支持的**：官方 [Codex CLI](https://github.com/openai/codex) 本身就支持 `codex --login` 用 ChatGPT 账号（设备授权流）登录，PR 复用的正是这条认证链路；[DeepSeek Harness 的 dsh-llm-codex 插件](https://github.com/NOirBRight/dsh-llm-codex) 也验证了第三方客户端可以用 ChatGPT Codex 登录。
2. **实现已跑通**：作者声明真机登录、模型加载、普通/流式回复、工具调用均验证通过，且有配套单元测试。
3. **安全设计合理**：凭据只发给官方 `chatgpt.com/backend-api/codex`，分享配置剔除凭据，符合 issue 里提出的顾虑。

## 5. 关于「GPT Luna」能不能用

- GPT-5.6 分三个层级：**Sol（旗舰）/ Terra / Luna（最快、最便宜）**；近期免费 ChatGPT 用户已能无限制使用 Luna 文字聊天（参考 [Times of India](https://timesofindia.indiatimes.com/technology/tech-news/openai-upgrades-chatgpt-free-users-to-gpt-5-6-luna-adds-unlimited-text-chats/articleshow/133028756.cms)、[OpenAI 官方 GPT-5.6 页](https://openai.com/zh-Hans-CN/index/gpt-5-6/)）。
- **注意区分两个模型列表**：ChatGPT 网页/App 的模型 ≠ Codex 后端（`chatgpt.com/backend-api/codex`）给账号开放的模型。
  - Codex（ChatGPT 账号登录态）目前**不支持 `gpt-5.6-sol`**：报错 "The 'gpt-5.6-sol' model is not supported when using Codex with a ChatGPT account"（见 [openai/codex#34027](https://github.com/openai/codex/issues/34027)）；Sol 通常要 Pro/灰度，Plus 一般只有 Terra/Luna。
  - `gpt-5.6-terra` / `gpt-5.6-luna` 在 Codex（ChatGPT 账号登录态）可用（参考 [腾讯云 AnyAIGC 文章](https://cloud.tencent.com.cn/developer/article/2706898)）。
- PR 的实现是**登录后从 Codex 后端拉「该账号可用模型列表」**——你的账号在 Codex 里能看到什么（包括是否出现 `gpt-5.6-luna`），App 里就会显示什么；即使暂时没有 Luna，也能用 `gpt-5.1-codex` / `codex-mini-latest` 等模型对话。
- 免费账号使用 Codex 有**速率/额度限制**（订阅额度体系，与 API Key 计费相互独立）。

## 6. 如果你想现在用上（可选路线）

1. **等合入**：持续关注 #1752，合入上游后 `git fetch upstream && git merge upstream/master` 即可。风险：仍是 Draft、维护者未表态，时间不确定。
2. **手动合入到你的 fork**（现在就能做）：
   - 把 `Ayuilos/Miffan` 的 `codex/provider-subscription-auth` 分支拉下来 cherry-pick 或 merge 到 `F:\git\rikkahub`；
   - 注意你的 fork 与上游已存在大量自定义提交，合并可能产生冲突，需要逐一解决并重新 `assembleDebug` 验证。
3. **直接用 Ayuilos/Miffan 的 Release**（[Ayuilos/Miffan releases](https://github.com/Ayuilos/Miffan/releases)）：那是别人的定制 fork，不含你的定制（去 Firebase、自定义包名/签名等），一般不建议。

## 7. 风险提示

- 订阅认证走的是**非公开的 chatgpt.com backend API**，OpenAI 可能随时调整协议、限流或封禁第三方客户端；Token 失效后需重新登录。
- 属于 OpenAI 服务条款的灰色地带，仅限个人使用，注意账号安全。
- 若你在意稳定/合规，正式场景仍建议用 API Key（两套独立计费体系）。
