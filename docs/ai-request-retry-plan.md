# AI 请求重试优化设计文档

> 状态：**Phase 1 + Phase 2 + Phase 3 已落地，并加入崩溃恢复（部分输出防抖落库）**（待整仓回归）
> 范围：主对话 / 子代理文本生成（`GenerationHandler`）+ 只读微调用（标题/建议/优化/压缩/OCR/检索改写/翻译）
> 默认重试次数：**5 次**（对齐 DSH `@deepseek-ai/dsh-llm-retry` 默认值；后台微调用用 2 次小预算）
> 参考实现：OpenAI Codex（`request_max_retries` / 指数退避 + 尊重服务端 `Retry-After`）、DSH `@deepseek-ai/dsh-llm-retry`（provider-owned policy、有界退避 + 对称 jitter）
> 面向「生成中进程被杀/崩溃丢失部分输出」：新增防抖落库（1.5s）——部分回复不丢。

---

## 1. 背景与现状

### 1.1 当前重试所在位置

重试逻辑**只**存在于 `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`，且仅覆盖文本生成：

```kotlin
private const val MAX_GENERATION_RETRIES = 2
private const val RETRY_INITIAL_BACKOFF_MS = 1000L
```

`generateInternal()` 内部有两段：

- 非流式（`else` 分支，L750–773）：`attempt` 从 0 起，`attempt >= MAX_GENERATION_RETRIES || !isRetryable()` 时抛出。
- 流式（`if (stream)` 分支，L653–749）：分成两种路径
  - **已输出**（有意义输出出现后断流）→ 走 `MAX_STREAM_RESUME_ATTEMPTS = 1` 的「续答唤醒」（往发送列表末尾追加 `continue` 指令，让模型接续）。
  - **未输出**（首 token 前失败）→ 走普通整轮重试。

### 1.2 判定与退避

```kotlin
private fun Throwable.isRetryable(): Boolean = when (this) {
    is HttpException -> code != null && (code == 429 || code in 500..599)
    is IOException -> true
    else -> false
}
private fun backoffDelay(attempt: Int): Long = RETRY_INITIAL_BACKOFF_MS * (1L shl (attempt - 1))
```

也就是：只有 `429 / 5xx / IOException` 可重试；退避 `1s, 2s, 4s`，**无上限、无 jitter、不认 `Retry-After`**。

### 1.3 底层传输层

`app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt` 中主 client 与 AI client 均设置了 `retryOnConnectionFailure(true)`（L310 / L407 / L423）。该选项只覆盖 **TCP/连接期**失败，不覆盖服务端返回的 HTTP 状态码（429/5xx）。

### 1.4 缺口总结

| 缺口 | 说明 |
| --- | --- |
| 重试次数偏低 | 默认仅 2 次（共 3 次尝试），瞬态错误容错弱 |
| 退避不完善 | 无上限、无 jitter、不认 `Retry-After`（对齐 Codex/DSH） |
| 覆盖单一 | 翻译、总结、标题、建议、压缩、Embedding、图片等 AI 调用**无重试** |
| 无 UI 反馈 | 用户看不到「正在重试 / 第几次」，DSH 有 `llm/retry` + `llm/retry-started` |
| 计数器混用 | 流式「续答唤醒」与「整轮重试」共用 `attempt`，逻辑易混淆 |
| 配置不可调 | 次数/退避写死，无法按用户或按 provider 调整 |

---

## 2. 参考实现要点

### 2.1 DSH `@deepseek-ai/dsh-llm-retry`

从 `node_modules/@deepseek-ai/dsh-llm-retry/lib/index.js` 与 `@deepseek-ai/dsh-llm/lib/types/retry-policy.js` 提取的规范：

- 默认策略（normal mode）：
  - `maxRetries = 5`
  - `initialDelayMs = 500`
  - `maxDelayMs = 10_000`
  - `jitterRatio = 0.1`
  - 可重试 code：`EMPTY_RESPONSE` / `RATE_LIMIT` / `SERVER` / `TIMEOUT` / `TRANSPORT`
- **有界指数退避 + 对称 jitter**：

  ```ts
  const exponent = Math.min(retry - 1, 1024);
  const exponential = Math.min(initialDelayMs * 2 ** exponent, maxDelayMs);
  const jitter = 1 - jitterRatio + 2 * jitterRatio * random();
  return Math.min(exponential * jitter, maxDelayMs);
  ```

- **尊重 `providerRetryAfterMs`**：当服务端返回 `Retry-After`（或 `retry-after-ms`）且 ≤ `maxDelayMs` 时直接采用、**不加 jitter**；超过 `maxDelayMs` 时 normal 模式停止重试（委托失败）。
- 在 agent loop 的 `request-error` 边界执行，`llm/retry`（调度前）与 `llm/retry-started`（开始重试前）事件对 UI 可见，且退避期间可取消。

### 2.2 OpenAI Codex CLI

- 对速率限制 / 5xx / 网络错误做指数退避 + 重试，存在 `request_max_retries` 配置项。
- 社区普遍反馈：重试应尊重服务端返回的「Try again in x seconds」延迟，否则退避与限流节奏错配。

---

## 3. 目标设计

### 3.1 新增可复用 `RetryPolicy`

在 `ai` 模块新建（或现有 util 包内）一个通用重试策略 + 执行器：

```kotlin
data class RetryPolicy(
    val maxRetries: Int = 5,          // 重试次数，总尝试数 = maxRetries + 1
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 10_000,
    val jitterRatio: Double = 0.1,
    val retryable: (Throwable) -> Boolean = { it.isRetryable() },
)
```

配套的挂起执行器：

```kotlin
suspend fun <T> retryWithPolicy(
    policy: RetryPolicy,
    // 供 UI 展示 / 日志
    onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T
```

行为：

- `attempt` 从 1 起；`attempt > maxRetries` 或 `!retryable(error)` 时抛出原始异常。
- 退避 = `min(initial * 2^(attempt-1), maxDelay) * (1 - jitter + 2*jitter*random)`，再 `min(..., maxDelay)`。
- 若错误携带 `retryAfterMs`（≤ `maxDelayMs`）则直接使用 `retryAfterMs`，**不加 jitter**；若 `> maxDelayMs` 则**不再重试**（normal 模式）。
- 取消感知：`delay(delayMs)` 本身可取消；`CancellationException` 必须**透传**，不吞、不重试。

### 3.2 `GenerationHandler` 接入

保留现有的两层流式语义，但改为共用同一 `RetryPolicy` 预算，计数器分清楚：

- **非流式**：整次 `providerImpl.generateText(...)` 包进 `retryWithPolicy`。
- **流式**：
  1. `hasMeaningfulOutput == false` 且出错可重试 → **整轮重试**（用 `attempt` 计次）。
  2. `hasMeaningfulOutput == true` 且出错可重试，且 `streamResumeAttempts < MAX_STREAM_RESUME_ATTEMPTS` → 先「续答唤醒」一次（保留部分输出）。
  3. 续答唤醒后再失败 → 回落到整轮重试，但**绝不重发已输出的部分**。
- 每次推迟前调用 `processingStatus.value = "第 n/5 次重试 · 等待 Xs"`；成功后 `processingStatus.value = null`；最终失败时在抛出的错误信息里带上「已重试 N 次」。

### 3.3 Provider 层补充

- `ai/src/main/java/me/rerere/ai/util/ErrorParser.kt` 的 `HttpException` 增加字段：

  ```kotlin
  class HttpException(
      message: String,
      var code: Int? = null,
      var retryAfterMs: Long? = null,
  ) : RuntimeException(message)
  ```

- 各 provider（OpenAI `ChatCompletionsAPI` / `ResponseAPI`、`ClaudeProvider`、`GoogleProvider`）在**非成功响应**与 **SSE `onFailure`** 时从 `response.header("Retry-After")`（或 `retry-after-ms`）解析出 `retryAfterMs` 并写入 `HttpException`。
- 统一判定：`429 / 5xx / IOException` 可重试；`400 / 401 / 403 / 404 / 422` 等鉴权/配置类**不重试**。为后续语义化 code（`RATE_LIMIT` / `SERVER` / `TIMEOUT` / `TRANSPORT` / `EMPTY_RESPONSE`）预留 `retryable` 的分类口。

### 3.4 配置化（后续可加，非第一版必需）

- `Settings` 增加 `aiRequestMaxRetries: Int = 5`（clamp `0..10`）。
- `PreferencesStore` 增加 key（如 `AI_REQ_MAX_RETRIES`）并读写映射。
- `SettingsSyncCodec`（`app/src/main/java/me/rerere/rikkahub/data/sync/SettingsSyncCodec.kt` L101 附近）加入同步白名单。
- 模型 / 行为设置页用 `NumberSettingContent` 暴露。

---

## 4. 分阶段落地

### Phase 1（核心，本方案第一版）

1. 新建 `RetryPolicy` + `retryWithPolicy` + `RetryPolicyTest`。
2. `GenerationHandler`：删除 `MAX_GENERATION_RETRIES` / `RETRY_INITIAL_BACKOFF_MS` / `backoffDelay`，接入 `RetryPolicy`（默认 5 次 / 500ms / 10s / 0.1 jitter）。
3. `HttpException` 增加 `retryAfterMs`；OpenAI 路径解析 `Retry-After`。
4. 每次重试写 `processingStatus`，失败信息带重试次数。
5. 非流式与流式统一走同一策略；流式续答唤醒逻辑保留。

### Phase 2（覆盖扩展，确认后再做）

**结论：有必要，但要有选择地复用，不做无脑全量套用。** 分类依据是「是否只读/幂等」与「是否流式」：

- **优先做（非流式 + 只读/幂等，收益高）**：标题生成、建议生成、提示词优化、压缩（逐 chunk）、OCR 识别、检索查询改写/HyDE/Multi-Query。这些短调用当前是静默失败或回退到原值，重试能显著提升成功率；用较小的重试预算（如 `maxRetries=2..3`）控制后台成本与延迟。
- **流式微调用（需特殊处理）**：`translateText` 是流式接口，若已输出部分译文后再重试会重复片段。应只允许「首 token 前」的重试（复用「已输出不重发」思想），切到 Qwen 非流式路径时可整体重试。
- **跳过**：图片生成/编辑（非幂等且昂贵，重试可能重复出图/重复计费）；Embedding（只读安全但价值低，可选）。
- 统一用同一 `retryWithPolicy` 包装现有 `generateText` 调用，即可复用后端 classifier / `Retry-After` 逻辑，无需重复实现。

### Phase 3（配置化）

暴露 `aiRequestMaxRetries` 到设置页，并打通持久化与同步。

### 崩溃恢复（部分输出防抖落库）

**问题**：AI 生成过程中应用的输出只更新在内存态（`ConversionSession.state`），DB 只在生成成功 `onSuccess` 时落库；一旦进程被杀/崩溃，未输出完的会话就丢了。

**方案**：生成期间对「部分输出」做**防抖落库**（`GENERATION_AUTO_SAVE_INTERVAL_MS = 1.5s`）：
- 每个流式 chunk 重置计时；连续流式暂停 1.5s 才写一次库，避免每个 chunk 都触发整会话重建 + FTS 重建。
- 用 `persistConversation(updateSessionState = false)`，只写库、不写回内存态，避免用旧快照覆盖正在流式更新的 UI。
- 显式停止（CancellationException）时也把当前部分输出落库；成功后取消待定的落库任务。
- 崩溃/被杀后重启，会话从 DB 加载即包含已生成的部分文本（最后一条 assistant `finishedAt = null`），不再丢失。提供「继续生成」按钮可复用现有续答/重生成流程（后续增强）。

---

## 5. 测试

`RetryPolicyTest`（Kotlin/JUnit）覆盖：

- 退避序列单调且不超 `maxDelayMs`。
- jitter 落在 `[initial * (1-jitter), initial * (1+jitter)]`（对称）。
- `retryAfterMs` 生效且不加 jitter；`> maxDelayMs` 时停止。
- `maxRetries` 耗尽后抛出原异常，且 `onRetry` 被调用 `maxRetries` 次。
- `retryable` 分类：`429 / 5xx / IOException` 可重试；`400 / 401 / 403 / 404` 不重试。
- `CancellationException` 立刻透传、不重试。

---

## 6. 风险与约束

- **重复计费**：每次重试都是新的 provider 请求，可能重复计费输入 token；因此默认使用**有限预算 5 次**，不做 DSH 的 `always`（无上限）模式。
- **流式不重复输出**：已输出的部分绝不重发；「续答唤醒」只在该场景使用，未输出时仍走整轮重试。
- **取消优先**：用户停止生成 / 切换会话时，`CancellationException` 必须先落盘已生成内容再向上抛，重试退避应立刻中止。
- **不覆盖其它调用**：本方案第一版只改 `GenerationHandler`（主对话 / 子代理生成），避免一次性把非幂等路径全部纳入造成意外行为。

---

## 7. 变更文件清单（第一版）

| 文件 | 改动 |
| --- | --- |
| `ai/src/main/java/me/rerere/ai/util/ErrorParser.kt` | `HttpException` 增加 `retryAfterMs` |
| `ai/src/main/java/me/rerere/ai/util/RetryPolicy.kt`（新增） | `RetryPolicy` + `retryWithPolicy` + 退避/jitter/`Retry-After` 处理 |
| `ai/src/test/java/me/rerere/ai/util/RetryPolicyTest.kt`（新增） | 单测 |
| `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt` | 解析 `Retry-After` 写入 `HttpException.retryAfterMs` |
| `ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt` | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt` | 用 `RetryPolicy` 替换硬编码；接入 `processingStatus` 回调 |
