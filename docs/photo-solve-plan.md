# 拍照搜题（Photo Solve）功能规划

> 状态：**已实施**（2026-09-07，见文末「实施记录与偏差」）
> 目标：新增「拍照搜题」独立功能页 + 将解题助手抽成可被任意助手调用的 `solve_question` 工具（子代理模式）。
> 所有引用的文件路径、类名、字段名均已对照当前代码树核实。

## 0. 已核实的现状结论（方案的事实基础）

| 事实 | 出处 |
|---|---|
| 右侧栏入口在 `StudyDrawerSections()` 的「更多工具」区，AI翻译是第一项 | `app/.../ui/pages/chat/ChatDrawer.kt:844-849` |
| 翻译功能 = 页面 + VM + Room 历史 + `GenerationHandler.translateText()` 轻量流式入口，不依赖 Conversation 持久化 | `ui/pages/translator/*`、`GenerationHandler.kt:1073` |
| 默认模型以 `Uuid` 存 DataStore（如 `translateModeId`/`ocrModelId`），`settings.findModelById(id)` 反查 | `PreferencesStore.kt:904-967` |
| 图片裁剪已有现成封装 `useCropLauncher`（uCrop 2.2.11-native），可被 `skipCropImage` 跳过 | `ui/components/ai/CropLauncher.kt` |
| 图片发送链路：Uri → `FilesManager.createChatFilesByContents()` 落盘 → `UIMessagePart.Image(url)` → 发送时 `Image.encodeBase64()`（质量 85 + EXIF 校正） | `FilesManager.kt`、`ai/util/FileEncoder.kt` |
| OCR 降级已有实现 `OcrTransformer`：模型无 IMAGE 输入时用 `settings.ocrModelId` 视觉模型把图片转文本，带 3 天 LruCache | `data/ai/transformers/OcrTransformer.kt` |
| 模型视觉能力标记：`Model.inputModalities.contains(Modality.IMAGE)` | `ai/provider/Model.kt` |
| 子代理体系：`SubAgentCatalog` 定义 + `SubAgentRunner.runSynchronously()` 同步执行 + `subAgentRunLoop` 独立回合（有 `onStreamUpdate/onMessagesUpdate` 实时回调）+ 任务表/Room 落库/详情页 UI | `data/ai/subagent/*` |
| 子代理模型解析要求候选模型具备 `ModelAbility.TOOL` | `SubAgentRunner.kt:132-155` |
| `SubAgentRequest` 只有 `task: String`，**不支持图片** | `SubAgentRequest.kt` |
| 工具输出截断仅在助手具备 shell 权限时触发（`hasShellAccess`），普通助手不截断 | `GenerationHandler.kt:913-922` |
| 助手工具开关 = `Assistant` 普通字段 + `ChatModePolicy` 的 `withAvailability` 门控 + `AssistantToolsPage` UI | `Assistant.kt`、`ChatMode.kt:103-118`、`AssistantToolsPage.kt` |

## 1. 总体架构

三个关注点彻底分离，两个消费端共享同一解题核心：

```
┌─────────────── 配置层（DataStore Settings）───────────────┐
│  solveModelId / solvePrompt / solveThinkingBudget        │
│  （新增）；复用 ocrModelId 作为 OCR 降级模型               │
└──────────────────────────┬───────────────────────────────┘
                           │
┌────────────────── 解题核心 QuestionSolver ────────────────┐
│  输入: 图片(file uri) + 题目文本(可选)                      │
│  路由: 解题模型有 IMAGE → 直接多模态                        │
│        无 IMAGE 且配置 ocrModelId → OCR 提取 → 纯文本解题   │
│        两者皆无 → 抛可读异常（引导配置）                     │
│  输出: Flow<SolveStreamUpdate>（reasoning / process /     │
│        final 三段式实时回调）+ 最终 SolveResult            │
└───────────┬──────────────────────────┬───────────────────┘
            │                          │
   ┌────────▼─────────┐      ┌─────────▼──────────────┐
   │ 拍照搜题页         │      │ solve_question 工具      │
   │ SolvePage/SolveVM │      │ → SubAgentRunner 派发    │
   │ （直接调核心）     │      │   solver 子代理（同步）    │
   └──────────────────┘      └────────────────────────┘
```

**关键取舍**：拍照搜题页直调 `QuestionSolver`（走 GenerationHandler 的 provider 流式通道，同 `translateText` 形态）；工具端走子代理体系（任务表/落库/详情页/实时进度 UI 全部复用现成机制）。二者共享同一段系统提示词与输出协议，保证两个入口的解题质量一致。

## 2. 配置与数据层（Phase 1）

### 2.1 Settings 新增字段（`PreferencesStore.kt`）

```kotlin
// Settings data class
val solveModelId: Uuid = UNSET_MODEL_ID,        // 默认解题模型
val solvePrompt: String = DEFAULT_SOLVE_PROMPT, // 解题系统提示词（可编辑，仿 translatePrompt）
val solveThinkingBudget: Int = 0,               // 思考预算，仿 translateThinkingBudget
```

- 新增 preference key：`SOLVE_MODEL` / `SOLVE_PROMPT` / `SOLVE_THINKING_BUDGET`，读写仿 `TRANSLATE_MODEL` 组（`PreferencesStore.kt:126,277,520` 附近）。
- 不新增独立的“解题 OCR 模型”，降级复用 `ocrModelId`——OCR 模型本来就要求 vision，语义一致，少一个配置项。

### 2.2 默认提示词（新文件 `data/ai/prompts/Solve.kt`）

`DEFAULT_SOLVE_PROMPT`，与输出协议（见 §4.3）强耦合，中英双语指令。要点：

1. 角色：解题助手，输出面向中学/大学学生。
2. 流程：先读取并转述题目 → 分步推导（展示关键思考）→ 最后输出精炼作答。
3. **输出协议**：全程正文为“解题过程”；最后必须输出 `<final_answer>...</final_answer>` 标记包裹的**精炼整理版**（规范步骤、必要文字说明、算式与结论，学生可直接誊抄到答题纸）。
4. 约束：题目信息不全时列出缺失条件并给最合理假设；禁止编造数据；图片不清时先描述识别到的题干。

### 2.3 历史记录（Room）

- 新实体 `data/db/entity/SolveRecordEntity.kt`，表 `solve_history`：
  `id: Uuid(PK)` / `imagePath: String`（裁剪后落盘的私有目录相对路径）/ `questionText: String?`（OCR 降级时保存识别文本，可空）/ `processText: String`（标记前的过程全文）/ `finalText: String`（标记内精炼版全文）/ `modelId: String` / `createdAt: Long`
- 新 DAO `data/db/dao/SolveHistoryDao.kt`：`listAll()` / `getById` / `deleteByIds` / `deleteAll` / `insertAndTrim`，完全仿 `TranslationHistoryDao`。`HISTORY_LIMIT = 100`。
- `AppDatabase` 版本号 +1，新增 migration（`CREATE TABLE solve_history ...`）；KSP schema 导出到 `app/schemas` 供 androidTest migration 测试（仓库既有约定）。
- `di/DataSourceModule.kt` 注册 `solveHistoryDao()`（仿 `translationHistoryDao`，约 271 行处）。

## 3. 解题核心（Phase 2）：`GenerationHandler.solveQuestion()`

不新建独立引擎类，直接在 `GenerationHandler` 中新增公开方法（与 `translateText` 同级，复用其 provider 解析、重试、StreamChunkHandler 骨架）：

```kotlin
data class SolveStreamUpdate(
    val reasoning: String,   // 思考全文（累积）
    val process: String,     // 解题过程全文（累积，标记前）
    val finalAnswer: String, // 精炼版全文（累积，标记内）
    val done: Boolean,
)

fun solveQuestion(
    settings: Settings,
    imageUris: List<String>,        // file:// uri（已裁剪落盘）
    questionText: String? = null,   // 附加文本（OCR 结果 / 用户补充描述）
    onStreamUpdate: ((SolveStreamUpdate) -> Unit)? = null,
): Flow<SolveResult>
```

实现要点：

1. **模型路由**：`settings.findModelById(settings.solveModelId)`；为空或 `UNSET_MODEL_ID` → 抛 `IllegalStateException("未配置解题模型")`（UI 捕获后显示配置引导空态）。
2. **消息构造**：`messages = listOf(UIMessage.user(parts))`，parts = `[Image(url)...] + [Text(解题指令 + questionText)]`。系统提示词以 system 角色消息传入；实施时若 `TextGenerationParams` 无 systemPrompt 通道，则将提示词并入首条 user 消息头部（翻译功能即此形态，已验证可行）。
3. **流式收集**：`providerHandler.streamText(...)` + `StreamChunkHandler`，同 `translateText`；每次 chunk 后从 `UIMessage.parts` 提取 `Reasoning` 全文与 `Text` 全文，按 `<final_answer>` 标记切分为 process/final 两段回调。
4. **重试策略**：复制翻译的重试语义——仅在**尚未输出任何内容**时重试（`RetryPolicy(maxRetries=2, initialDelayMs=400, maxDelayMs=5000)`），已输出后失败直接抛错，避免重复内容。
5. **OCR 降级**（工具端与页面端共用此分支）：解题模型 `inputModalities` 不含 `Modality.IMAGE` 时——
   - `settings.ocrModelId` 有效且该模型含 IMAGE → 复用 `OcrTransformer.performOcr()`（享受 ocr_cache 缓存）把图片转文本 → 以纯文本消息解题；`questionText` 字段落库 OCR 结果。
   - OCR 模型未配置 → 抛可读异常：「当前解题模型不支持图片输入，且未配置 OCR 模型，请到设置中配置」。

## 4. 拍照搜题页（Phase 3）

### 4.1 入口与路由

- `ChatDrawer.kt` `StudyDrawerSections` → 「更多工具」`DrawerSection` 内、AI翻译条目**之前**插入（844 行前）：

```kotlin
DrawerListItem(
    icon = HugeIcons.Camera01,   // 实施时从 HugeIcons 里选可用相机图标
    title = stringResource(R.string.chat_page_menu_photo_solve),
    onClick = { navController.navigate(Screen.PhotoSolve) },
)
```

- `RouteActivity.kt`：`data object PhotoSolve : Screen`（仿 `Screen.Translator`，887 行）+ `entry<Screen.PhotoSolve> { SolvePage() }`（仿 485-487 行）。

### 4.2 页面结构（新包 `ui/pages/solve/`，四文件仿 `translator/`）

**SolvePage.kt** —— 整体镜像 `TranslatorPage`（Scaffold + TopAppBar）：

- TopAppBar：历史按钮（切换页内历史模式，仿 `showHistory`）+ 返回。
- **输入区**（生成前）：
  - 图片预览卡：未选图时显示两个等宽入口按钮——「拍照」（`ActivityResultContracts.TakePicture()`，FileProvider 临时 uri，仿 ChatPage `cameraLauncher`）/「相册」（`GetContent("image/*")`）。
  - 选图后自动进入 uCrop（`useCropLauncher`，输出到私有目录）；尊重 `skipCropImage` 设置；预览图右下角提供「重新裁剪」小按钮。
  - 可选文本框：用户补充描述（如“第 3 问”、“求详细过程”）。
  - 底部主按钮「开始解题」↔ 生成中变「停止」（同翻译页可取消按钮）。
- **输出区**（生成中/完成后）：
  - 思考区：复用聊天页的 Reasoning 折叠展示组件（实施时确认具体组件名，ChatPage 已有现成渲染），流式展开。
  - 解题过程：`MarkdownBlock` 流式渲染。
  - **精炼作答卡**：`<final_answer>` 内容渲染在独立高亮 Surface 卡片（顶部标签「作答 · 可直接誊抄」+ 复制按钮），学生一眼定位。
- **历史模式**：`SolveHistoryView.kt` 仿 `TranslationHistoryView`（列表 + 左滑删除 `SwipeToDismissBox` + 长按多选 + 底部浮动工具条 + 空态），点击记录 → 展示该记录的图片 + 过程 + 精炼版，可一键复制。

**SolveVM.kt**（Koin 注入 `SettingsStore` / `GenerationHandler` / `SolveHistoryDao`，完全仿 `TranslatorVM`）：

```
data class SolveUiState(
    val imageUri: String? = null,       // 已裁剪图片
    val generating: Boolean = false,
    val reasoning: String = "",
    val process: String = "",
    val finalAnswer: String = "",
    val error: String? = null,
    val viewingRecordId: Uuid? = null,  // 历史查看态
)
```

- `solve()`：`viewModelScope.launch { currentJob = ... }`；校验图片已选、解题模型已配置（未配置直接置 error 态显示引导，不发请求）；`onStreamUpdate` 更新三段文本；完成后 `insertAndTrim` 落库。
- `cancel()`：`currentJob?.cancel()`（`CancellationException` 正常吞掉，同翻译页）。
- 图片持久化：裁剪产物直接写入 `filesDir/solve_images/<uuid>.jpg`（在 `FilesManager` 或 VM 内落盘），历史记录只存相对路径；删除记录（含批量）时同步删除对应图片文件。

### 4.3 输出协议与容错（防 bug 重点）

选**单次生成 + 标记分段**，而非两阶段两次调用：

- **取舍依据**：两次调用 token 与延迟翻倍，且阶段 1 失败会牵连阶段 2；单次生成让模型在完整上下文里整理作答，一致性更好。代价是依赖格式遵循——用以下三层容错覆盖：
  1. 流式中出现 `<final_answer>`：其后文本进入精炼区（闭标记出现前也实时渲染）。
  2. 流式结束仍只有开标记没有闭标记：开标记后的全文归精炼区。
  3. 全程无标记：全文归「解题过程」区，精炼卡不渲染（降级为普通展示，不报错）。
- 提示词中明确“`<final_answer>` 标记必须独占一行”，降低嵌套/截断概率；解析用简单 `indexOf` 切分，不用正则回溯。
- 历史落库在流结束后执行：`processText/finalText` 按**同一套切分逻辑**取自最终全文，保证 UI 展示与历史回看一致（切分逻辑抽成纯函数 `splitSolveOutput(text): Pair<String, String>`，可单测）。

### 4.4 状态机

```
Idle(无图) ──拍照/相册──▶ CropPending ──uCrop回调──▶ ImageReady
ImageReady ──开始解题──▶ Generating(三段流式) ──完成──▶ Done(落库)
Generating ──停止/失败──▶ ImageReady(保留图片与已生成内容) / Error
Done/历史点击 ──▶ ViewingRecord ──返回──▶ ImageReady
```

- 生成中禁用图片更换与再次提交（按钮态 + 分支守卫，避免并发 job 相互覆盖 `currentJob`）。
- VM 用 `koinViewModel()` 页面级作用域：导航到别处再回来状态保留；系统杀进程后按 Idle 恢复（可接受，历史里有落库记录）。

## 5. 工具化（Phase 4）：`solve_question` 子代理工具

### 5.1 solver 子代理定义（`SubAgentCatalog`）

```kotlin
val solver = SubAgentDefinition(
    id = "solver",
    name = "解题子代理",
    description = "解答学科题目（数学/物理/化学/生物/英语等），输出分步推导与可直接誊抄的精炼作答，供母代理交叉验证。",
    commandAlias = "solve",
    systemPrompt = <复用 §2.2 DEFAULT_SOLVE_PROMPT 主体 + 子代理补充规则>，
    capabilities = setOf(SubAgentCapability.NONE),  // 纯 LLM，无工具
    maxSteps = 1,
    timeoutSeconds = 300,
)
```

需同步给子代理体系打的两个补丁（均向后兼容）：

1. **图片支持**：`SubAgentRequest` 增加 `val images: List<String> = emptyList()`（@Serializable 默认值，旧落库数据解析不受影响）；`SubAgentRunner.runInternal` 构造首条 user 消息时，把 images 中的 `UIMessagePart.Image` 与任务文本合并（复用 `Image.encodeBase64()` 发送链路）。
2. **工具能力豁免**：`SubAgentDefinition` 增加 `requiresToolAbility: Boolean = true`；`resolveModel`（`SubAgentRunner.kt:150-152`）在 `requiresToolAbility == false` 时跳过 `ModelAbility.TOOL` 检查。根因：solver 无工具纯生成，强制 TOOL 能力会把视觉但无工具调用的模型误杀成全候选失效。

**solver 模型解析**：工具侧显式传 `modelId = settings.solveModelId.toString()`，走 `resolveModel` 既有候选链（任务指定 > def 默认 > subAgentModelId > chatModelId）——解题模型失效时自动回落，不会 FAILED。

### 5.2 工具声明（新文件 `data/ai/tools/SolverTools.kt`）

```kotlin
Tool(
    name = "solve_question",
    description = "调用解题子代理解答题目。当用户提出解题/做题/作业/考试题等相关问题" +
        "(数学、物理、化学等学科)，或用户给出题目并希望获得规范的解答步骤，" +
        "或需要核对、验证某道题的答案正误时，调用此工具。" +
        "工具会返回解题助手的完整解答(过程+精炼作答)，你应结合自己的初步分析交叉验证后再答复用户。",
    parameters = { InputSchema.Obj(
        properties = buildJsonObject {
            put("question", ... /* string, 必填：题目的完整文本化描述；图片题转述题干关键信息与问题 */),
            put("image_paths", ... /* array<string>, 可选：题目图片的本地 file:// 路径，引用当前对话用户消息中的图片 */),
            put("subject", ...   /* string, 可选：学科，如 math/physics */),
        },
        required = listOf("question"),
    ) },
    systemPrompt = "使用 solve_question 的结果前，先独立完成自己的分析，再与其逐步骤比对；" +
        "结论一致则给用户确定性答复，不一致时明确指出分歧步骤并给出你的判断依据。",
    execute = { args -> ... },
)
```

`execute` 流程（同步等待，参考强制指令 `/search` 使用 `runSynchronously` 的既有路径）：

1. 解析 args → `SubAgentRequest(agentId="solver", task=question(+subject), images=image_paths)`。
2. **路径安全校验**（必须）：每个 image path 先 `File(path).canonicalPath`，必须位于 `context.filesDir` 规范前缀下，否则该路径剔除并附警告——防止模型伪造路径读任意文件。
3. 若 `image_paths` 非空但请求模型最终解析结果不支持 IMAGE 输入：走 §3 的 OCR 降级分支（用 `settings.ocrModelId` 提取文本拼进 task）。
4. `subAgentRunner.runSynchronously(request, parentConversationId, taskId = __toolCallId)`（`injectToolCallId = true`，taskId 与工具调用对齐，复用子代理任务卡片实时进度 UI 与 Room 落库）。
5. 终态映射：`SUCCEEDED` → 返回 `UIMessagePart.Text(完整解题报告)`；`FAILED/TIMEOUT/TOKEN_LIMIT` → 返回 JSON `{"error": ...}`；`CANCELLED` → 向上传播取消。

### 5.3 开关与门控（每个助手独立，默认关）

1. `Assistant.kt`：`val enableQuestionSolver: Boolean = false`。
2. `ChatMode.kt`：`Capability` 枚举新增 `QUESTION_SOLVER`；`withAvailability` 增加：
   `if (Capability.QUESTION_SOLVER in capabilities && !assistant.enableQuestionSolver) add(Capability.QUESTION_SOLVER)`。
3. `ChatToolRegistry.kt`：新增 `Entry(capabilities = setOf(Capability.QUESTION_SOLVER), assemble = { createSolverTools(...) })`——装配层按 capability 过滤，开关关闭时工具根本不出现在 provider 的 tool 列表里（不是“声明了但禁用”，彻底不泄露 schema）。
4. `AssistantToolsPage.kt`：「工具与服务」页新增开关行，`vm.update(assistant.copy(enableQuestionSolver = it))`。

### 5.4 截断风险确认

`maybeTruncateToolOutput`（`GenerationHandler.kt:922`）只在 `hasShellAccess` 为真时截断：普通助手不受影响；开启 workspace 的助手 + 解题报告超长时会被截断为文件引用——可接受（模型可用 workspace 工具读全文），实施时在报告中验证一次即可，无需改截断逻辑。

## 6. 国际化与资源

- 新页面字符串统一走 `app/src/main/res/values*/strings.xml`（key 前缀 `photo_solve_`），用 `locale-tui` 同步翻译（仓库既有 `locale-tui-localization` skill 约定）。
- 图标：入口用 HugeIcons 相机类图标，实施时确认 `HugeIcons` 对象中的确切可用名（备选 Camera01 / CameraAdd01）。

## 7. 边界与风险清单

| # | 风险/边界 | 处理 |
|---|---|---|
| 1 | 模型不遵守 `<final_answer>` 标记 | §4.3 三层容错，永不崩、永不空屏 |
| 2 | `solveModelId` 指向的模型被删除 | 页面：findModelById 返回 null → 配置引导态；工具：resolveModel 候选链自动回落 |
| 3 | 解题模型无视觉能力且未配 OCR | 可读异常 + UI 引导；有 OCR 则自动降级（结果记 questionText） |
| 4 | 生成中用户退出页面 | job 挂在 VM scope 继续跑完并落库；进程被杀则丢本次（与翻译功能同等水位，不引入前台服务） |
| 5 | 并发提交 | `generating` 态禁用提交按钮 + VM 分支守卫 |
| 6 | Room 迁移 | 版本 +1 + 手写 Migration + schema 导出；androidTest migration 测试覆盖 |
| 7 | 图片路径注入（工具端） | canonicalPath 必须 filesDir 前缀，否则剔除 |
| 8 | 大图 token 消耗 | 裁剪即限尺寸 + `encodeBase64` 已有压缩(质量85)；历史预览用缩略图加载 |
| 9 | 子代理旧数据兼容 | `SubAgentRequest.images` 与 `requiresToolAbility` 均带默认值，反序列化向后兼容 |
| 10 | Task 字段长文本 | task = 题目文本，通常 < 2k 字符，远低于 SubAgentLoop 的 8000 字消息上限 |

## 8. 实施顺序与验证

按依赖排序，每阶段结束跑一次构建：

1. **Phase 1 配置与数据层**：PreferencesStore + prompts/Solve.kt + Room(entity/dao/migration) + DataSourceModule。验证：`./gradlew :app:compileDebugKotlin`。
2. **Phase 2 解题核心**：`GenerationHandler.solveQuestion()` + `splitSolveOutput` 纯函数 + 单测（标记完整/缺闭标记/无标记三案例）。验证：`./gradlew :app:testDebugUnitTest`。
3. **Phase 3 页面**：SolvePage/SolveVM/SolveHistoryView + 路由 + 抽屉入口 + strings。验证：compile + 真机走查（拍照→裁剪→流式→落库→历史回看→删除含图片清理）。
4. **Phase 4 工具化**：SubAgentCatalog.solver + SubAgentRequest.images + requiresToolAbility + SolverTools + ChatToolRegistry + Capability/withAvailability + Assistant 字段 + AssistantToolsPage 开关。验证：compile + 真机（开开关→聊天里发题目图片让模型调 solve_question→观察子代理任务卡片进度与最终交叉验证答复；关开关→工具不出现在请求 schema）。
5. **收尾**：`./gradlew assembleDebug` + `adb install -r`（只覆盖安装）+ 真机回归聊天主流程未受影响。

## 9. 文件改动清单（汇总）

**新增**
- `app/.../data/ai/prompts/Solve.kt`（DEFAULT_SOLVE_PROMPT）
- `app/.../data/ai/tools/SolverTools.kt`（solve_question 工具工厂）
- `app/.../data/db/entity/SolveRecordEntity.kt`、`data/db/dao/SolveHistoryDao.kt`
- `app/.../ui/pages/solve/SolvePage.kt`、`SolveVM.kt`、`SolveHistoryView.kt`
- `docs/upstream-sync-log.md` 不动；本文件随实施更新状态

**修改**
- `data/datastore/PreferencesStore.kt`（3 个字段 + 3 个 key + 读写）
- `data/ai/GenerationHandler.kt`（solveQuestion + splitSolveOutput）
- `data/db/AppDatabase`（版本 +1 + migration）+ `di/DataSourceModule.kt`（DAO 注册）
- `RouteActivity.kt`（Screen.PhotoSolve + entry）+ `ui/pages/chat/ChatDrawer.kt`（入口条目）
- `data/ai/subagent/SubAgentCatalog.kt`（solver）、`SubAgentRequest.kt`（images）、`SubAgentDefinition.kt`（requiresToolAbility）、`SubAgentRunner.kt`（resolveModel 豁免 + 首条消息图片合并）
- `data/model/Assistant.kt`（enableQuestionSolver）、`data/model/ChatMode.kt`（Capability + 门控）、`service/ChatToolRegistry.kt`（Entry）
- `ui/pages/assistant/detail/AssistantToolsPage.kt`（开关 UI）
- `app/src/main/res/values*/strings.xml`

## 10. 实施记录与偏差（2026-09-07）

已按规划落地，与规划的差异（均为实施中的合理调整）：

1. **前台服务独立实现**：`service/SolveGenerationForegroundService.kt`（manifest 已注册，dataSync 类型）。未复用聊天版——聊天版通知点击回跳具体会话，解题无会话概念，通知仅拉起主界面。SolveVM 在 solve() 时 acquire、finally/onCleared 兜底 release。
2. **OCR 降级抽成共享函数** `data/ai/SolveOcr.kt`（`ocrSolveImages`）：`solveQuestion` 与 `solve_question` 工具共用同一实现，保证两入口降级行为一致；不复用 `OcrTransformer.performOcr`（其静默兜底会把错误题干喂给解题流程）。
3. **`SolveResult` 增加 `questionText` 字段**：OCR 降级/纯文本路径回传实际发给模型的题干，落库到 `solve_history.question_text`，历史卡片可显示题干。
4. **solveQuestion 是 `GenerationHandler` 成员函数**（非顶层），与 translateText 同级共享 providerManager 等。
5. **工具声明/子代理提示词均为英文**（按要求）；工具 description 符合仓库约定（含 "Use when / Avoid"，`ToolDescriptionConventionTest` 约定）。
6. **`Capability.QUESTION_SOLVER` 加入 `DEFAULT_CAPABILITIES`**（标准及以上模式可见），可用性由 `withAvailability` 按 `assistant.enableQuestionSolver` 折叠；`ModePickerSheet.Capability.note()` 补充了枚举中文注释。
7. **solver 子代理同步等待**（`runSynchronously`），taskId = `__toolCallId`，复用子代理任务卡片实时进度 UI 与 Room 落库；`SubAgentRequest.images` + `SubAgentDefinition.requiresToolAbility` 两个向后兼容补丁已落地。solver 同时出现在 `spawn_subagent` 的 agentId 枚举中（纯生成、无工具、maxSteps=1，异步派发也可用）。
8. **图片路径安全校验**已实现：canonicalPath 必须位于 `context.filesDir` 前缀下，非法路径剔除并在工具结果中附警告。

验证：`:app:compileDebugKotlin` ✅、`:app:testDebugUnitTest` 全量 ✅（含新增 `SolveOutputParserTest` 6 例）、`:app:assembleDebug` ✅、Room schema 49.json 已导出（AutoMigration 48→49）。真机走查（拍照/相册/裁剪/流式/前台保活/历史/工具开关）待设备连接后执行。
