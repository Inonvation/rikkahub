# 拍照搜题功能优化方案

> 状态：**P0 已落地（2026-09-08）**，P1/P2 待排期
> 目标：针对「解题模型提示词」「solve_question 工具声明」「AI 调用解题子代理的体验」三块做一轮针对性优化，提升解题成功率与使用体验。
> 前置事实：功能已于 2026-09-07 完整落地（`docs/photo-solve-plan.md` 状态已实施）。本方案**不改变既有架构**（子代理同步等待、OCR 降级共享函数），核心优化落在这层协议之上；唯一的协议扩展是 `<problem_statement>` 题干块（§4.1，为题干全路径回显服务）。
> 落地记录：P0 四项（v3 提示词 + 共享常量 / OCR 图形补强 / 工具声明三处修订 / 题干卡全路径回显）已于 2026-09-08 全部实现并通过编译与单测，详见 §8 落地表。

---

## 1. 现状盘点（方案的事实基础）

对照代码核实到的既有能力与相关位置：

| 能力 | 位置 |
|---|---|
| 双入口共享解题核心（页面直调流式 / 工具派发 solver 子代理） | `GenerationHandler.solveQuestion()`；`SolverTools.kt` → `SubAgentRunner.runSynchronously` |
| `<final_answer>` 精炼作答协议 + 三层容错切分 | `data/ai/prompts/Solve.kt`、`SolveOutput.kt`、`SolveOutputParserTest` |
| 视觉模型直通 / OCR 降级双路线（复用 `ocrModelId`） | `SolveOcr.kt` `ocrSolveImages()`、`prompts/OcrPrompt.kt` |
| 解题提示词两处手工同步维护 | `Solve.kt` `DEFAULT_SOLVE_PROMPT`（可编辑）+ `SubAgentCatalog.kt:265` solver `systemPrompt`（硬编码副本） |
| solver 失败自动重试已存在 | `SubAgentRunner.runWithTimeout`（maxRounds / isRetryableFailure） |
| 前台服务保活、历史落库、页内追问线程、题干可编辑重解 | `SolveGenerationForegroundService.kt`、`SolveHistoryDao`、`SolveVM.askFollowUp/followUpQuestion`、`resolveWithQuestion` |

### 现有方案已经做对的（本轮不动）

1. **单次生成 + 标记分段**而非两阶段两次调用——token/延迟/一致性上都是正确取舍，调研结论（ToRA/PAL 之外的主流出品做法）未推翻它。
2. **母代理交叉验证**而非照搬子代理结果——这是比 Photomath 类"单模型直出"更稳的架构，予以保留。
3. **无视觉模型的 OCR 降级**与**路径安全校验**——均已做扎实。

---

## 2. 竞品与文献调研摘要

> 标注：【确认】= 调研中已核实（官方站点/arXiv/github）；【推断】= 公开常识级，建议上线前再验证。全文见本次会话调研记录。

**产品体验层（Photomath / 作业帮 / Gauth / Socratic / Khanmigo）**
1. Photomath【推断】：分步解答 + 动画推导 + "同类题再练"；手写识别。
2. 作业帮/小猿【推断】：题库命中优先、解析分层（答案/过程/考点/易错点）、错题本沉淀；AI 只兜底长尾题。
3. Gauth【确认：gauthmath.com】：AI 分步 + 真人导师兜底最难 5%；Socratic/Khanmigo【推断】：苏格拉底式引导。

**方法与提示词层【确认-文献】**
1. Chain-of-Thought 分步推理（Wei et al. arXiv:2201.11903）。
2. Self-Consistency 多采样投票降幻觉（Wang et al. arXiv:2203.11171）——但成本成倍，只适合按需（难题/用户要求复核），不适合默认。
3. ToRA（arXiv:2309.17452）/ PAL（arXiv:2211.10435）：NL 推理 + 外部计算/符号求解交替，显著提升计算类题正确率——对应本文案 §5.4 远期方向。
4. 公式 OCR→LaTeX 结构化（Mathpix【确认】/Pix2Text【确认】）vs 整页视觉 LLM 的路线之争：前者公式准、可复制、token 省，但**版面/几何图形是共同盲区**——几何题的图形信息 OCR 后丢失，这是纯文本降级路线解不了几何题的根因（本文案 §4.2 直接针对）。

---

## 3. 差距分析：三块优化域

| 优化域 | 现状 | 差距（根因） | 对应章节 |
|---|---|---|---|
| 解题提示词 | 工作流 4 步较通用；图片题"转述题干"只作为内部步骤，**不要求作为可见输出** | ① 视觉直通/子代理路径下，模型"读错题"用户与母代理都难以及时发现（图在模型眼里、题面校验在用户手里，中间没有可对齐的桥）；② verify 步骤无学科抓手，一句泛化的"substitute back"对几何/化学/应用题指导力弱；③ 一张图含多题时无行为约定，答非所问 | §4.1 |
| OCR 提示词 | `DEFAULT_SOLVE_OCR_PROMPT` 只转写文字与公式，**显式忽略图形** | 几何图/函数图/电路图题在 OCR 降级后丢失全部图形信息，解题必然失败——且失败发生在用户看不到的地方（拿残缺题干硬解） | §4.2 |
| 工具声明 | description 要求图片题母代理"转述题干关键信息"；交叉验证策略无豁免条款 | ① 母代理可能无视觉能力（或读图能力弱于 solver），"先转述再传图"要么做不到、要么转述错污染 solver；② 母代理无视觉时无法真正"逐步骤交叉验证"，现有 systemPrompt 强制 reconcile，会诱导母代理为显得能干而改写/误判子代理结果；③ 多题图无定位约定 | §4.3 |
| AI 调用体验 | 同步等待 + 子代理任务卡 + 失败重试已具备；结果回传母代理交叉验证 | ① vision 直通路径下解题过程的"题目重述"缺失，母代理/用户校验链在图片题上断裂（承 §4.1）；② 无"按需复核"能力（Self-Consistency 的按需形态），难题一次解错只能靠追问，成本不可控；③ 前台服务通知无完成回跳 | §5.1 / §5.2 / §5.3 |

---

## 4. P0：提示词与工具声明修订（纯文本改动，收益最大）

> 三个改动都只碰提示词文本与工具描述，**不动协议、不动调度、不动 schema 结构**。`solvePrompt` 对用户可编辑，因此默认值升级会走既有的"legacy 静默迁移"机制（`LEGACY_SOLVE_PROMPTS`），旧版用户自定义的不受影响。
>
> ⚠️ 2026-09-08 落地时按用户追加需求扩展了 §4.1：restatement 从"正文可见章节"升级为 **`<problem_statement>` 协议化题干块**（可切分、可渲染），配套切分层（`SolveOutput.kt` 三段切分）与 UI 层（题干卡全路径回显，vision 直通/OCR 降级都展示 AI 读到的题面）。这是本次唯一动到协议的改动，其余仍为纯文本级。

### 4.1 解题提示词 v3（`prompts/Solve.kt` + solver 同步）

> 落地状态：**已完成**。`SOLVE_PROMPT_CORE` 常量已抽到 `Solve.kt`，`SubAgentCatalog.solver.systemPrompt` 引用同一常量；`problem_statement` 三段切分 + 单测已合入 `SolveOutput.kt`/`SolveOutputParserTest.kt`。

**问题根因**：解题错误的第一来源不是"算错"而是"读错题"。图片题经视觉模型直读或 OCR 转写后，模型以为自己在解的题与真实题面之间可能出现偏差；当前提示词把"转述题干"当作内部动作（step 1），用户与母代理都拿不到一个可校验的中间产物，等发现答错时已经浪费一次完整生成。

**改动一：图片/重建题干题，强制可见的题目重述（restatement）作为过程开头**

在 Workflow 中把"转述"升级为**输出要求**（仅当题目以图片给出、或题干文本是 OCR/母代理重建时）：

```markdown
## Workflow
1. Read the problem carefully. If the problem was given as image(s), or the problem text was
   reconstructed (OCR / relayed by a parent agent), START your reply with a concise
   "题目理解 / Problem understood" block written in the reply language, listing:
   - the question stem as you read it,
   - every known value / given condition (with the symbols that appear in the problem),
   - what is asked (the target quantity or required parts),
   - anything you could NOT read or that is ambiguous — state it explicitly here instead of
     guessing silently.
   This restatement is the verification anchor for the user (and the calling agent): if it does
   not match the real problem, they can stop you before you waste the solve.
   For text-only problems given verbatim by the user, skip the restatement (one-line goal
   confirmation is enough).
```

理由与边界：代价是图片题每轮多 2~5 行 token；收益是解错题的返工（动辄几百 token 的完整生成）可被提前掐断，且在 UI 上给用户一个"模型读到什么"的对照位。**不强制纯文本题重述**，避免对直接可读题干产生冗余噪音。

> 落地细化（题干卡全路径回显）：v3 要求 restatement 以 **`<problem_statement>` 标记块**输出（单独成行），由切分层 `splitSolveOutput` 拆出 `statement` 段。SolvePage 题干卡两种路径都展示：
> - **vision 直通**：`launchSolveInternal` 判定模型有视觉 → 流式中 statement 增量实时上屏到题干卡（用户边看边可掐断）；
> - **OCR 降级**：模型无视觉、题面由内部 OCR 重建 → 题干卡展示回传的 `questionText`（OCR 产物即"AI 读到的题"）。
> 题干卡编辑重解（`resolveWithQuestion`）按模型视觉能力分流：无视觉 → 纯文本重解（避免重发图触发 OCR 覆盖修正）；有视觉 → 重发原图 + 修正文本作 notes 对齐。
> 该能力同时服务于母代理链路：solver 输出经工具结果回传后，`<problem_statement>` 同样可被母代理作为交叉验证锚点。

**改动二：subject 特化验证清单替换泛化的 verify 步骤**

**问题根因**：现状 step 4 "Verify the result when feasible (substitute back, sanity-check units...)" 对 prompt 是弱约束——模型把它当成可选项且没有学科抓手；对数学题重算关键代数、对几何题逐条核对图形条件、对化学题查配平与状态符号，才是文献（ToRA/PAL）与教辅产品（作业帮解析分层）共同指向的有效自检。母代理交叉验证也需要 solver 给出"验证了什么、结果如何"的明确结论，否则只能复读步骤。

```markdown
## Verification
After solving, run a verification pass matching the subject and report its outcome in 1-3
sentences at the end of the worked solution (before <final_answer>):
- math: substitute the result back into the original equation(s); re-derive heavy algebra /
  calculus if any; check domain and edge cases.
- physics: dimension/unit analysis; conservation laws; magnitude sanity vs typical values.
- chemistry: equation balance; state symbols; significant figures.
- geometry / figure-based: re-check every given condition from the figure against your steps.
- other subjects: re-read the question and confirm every requested part is answered.
State concretely what you verified and the outcome (e.g. "verified by substitution: x=3
satisfies both equations"). If a check fails, correct the solution — never leave it unreconciled.
```

**改动三：一张图含多题的行为约定**

```markdown
## Rules (add)
- If an image or the task contains multiple separate problems, state which problem you are
  solving (match the user's request / the most prominent one) before solving it. If the request
  does not identify a specific problem, solve the first/main one and say so explicitly.
```

**改动四（结构性，与文本无关但防回归）**：把 v3 全文抽成共享常量（如 `data/ai/prompts/Solve.kt` 暴露 `SOLVE_PROMPT_CORE`），`SubAgentCatalog.solver.systemPrompt` 改为 `核心常量 + 子代理补充规则`（Language 行裁决、输出供母代理交叉验证），**消除两处手工同步的漂移风险**——本次 v3 若不抽，下次仍会漏同步。

> solver 特有规则现状已包含（Language 行 / parent cross-verification），追加 v3 的三个改动即可。

### 4.2 OCR 提示词补强：图形信息结构化描述（`prompts/OcrPrompt.kt`）

**问题根因**：`DEFAULT_SOLVE_OCR_PROMPT` 的指令是"只转写题目内容、忽略与题目无关的装饰"，但几何/函数/电路等题的关键信息**以图形承载**，并非装饰。当前提示词在语义上把图形与页眉水印同等对待——OCR 降级路径对图形题从起点就丢题。调研确认：公式 OCR（Mathpix/Pix2Text 系）的公认盲区正是版面图形，必须用专门指令补偿。

在 `DEFAULT_SOLVE_OCR_PROMPT` 增加图形描述段（保持"只输出题干"的干净约束，不引入 JSON/清单）：

```markdown
You are an OCR assistant for homework papers.

Extract the problem content from the image:
- Transcribe the question text exactly, keeping the original reading order.
- Write math formulas and equations in Markdown with LaTeX.
- IMPORTANT — describe figure/diagram content that carries problem information, because the
  solver cannot see the image. Use the printed labels so the description connects to the text:
  * geometry figures: shape type, vertex labels, segment lengths, given angles,
    parallel/perpendicular marks, circles (center/radius), auxiliary lines if shown;
  * function graphs: axis labels and scale, curve shape, intercepts, intersection points;
  * other diagrams (circuits, force diagrams, tables): components, values, connections.
  If the figure has no labels, describe positions ("point at top-left of the triangle").
- Ignore page headers, footers, page numbers, watermarks and truly decorative elements.
- Output ONLY the cleaned question text in Markdown/LaTeX, figure description included.
Do NOT output any preamble, commentary, JSON, code fences, element lists, or anything besides
the question itself.
```

边界：图形描述仍是"文字近似"，复杂的立体几何/精细电路图该提示词也兜不住——兜底是用户仍可编辑题干（现成能力）与视觉模型直通路线。此改动只抬高低估线，不承诺全解。

### 4.3 工具声明修订（`tools/SolverTools.kt`）

> 落地状态：**已完成**（question 参数描述放宽 / systemPrompt 追加无视觉豁免与多题定位引导）。

**问题根因①：图片题要求母代理"转述题干"隐含母代理有视觉能力**。母代理（对话主模型）与 solver 是独立模型配置（`solveModelId`），母代理很可能无视觉或视觉弱于 solver。让它先转述再传图：转不了 → 图片题在入口处失效；转得错 → 拿错误题干污染本来能自己读图的 solver。这与 `image_paths` 的设计意图（solver 自己读图）自相矛盾。

修订 `question` 参数描述（放宽转述要求）：

```markdown
"The complete, self-contained problem statement in text form. For image-based problems, do NOT
transcribe the full stem — the sub-agent reads the attached image itself. Instead describe the
context you have: which problem to solve if the image contains several (e.g. 'the 2nd question'),
any part of the text you can read, and the subject if known. A full transcription is only
required when you cannot attach the image (no image_paths)."
```

**问题根因②：交叉验证策略无"能力不足"豁免，诱导母代理误改子代理结果**。`solve_question` 的 systemPrompt 要求母代理 reconcile（一致→自信作答；不一致→指出分歧并给判断）。当母代理无视觉、题目只有图时，它**没有独立复核的能力**，"不一致"大概率是母代理自己误读图，强制 reconcile 会把对的子代理答案改错。

修订工具 systemPrompt（追加豁免条款）：

```markdown
## solve_question Usage Policy
(保留现有：MUST call first / overrides do-it-yourself / reconcile 条款)
- Cross-verification caveat: you can only reconcile steps you can independently verify. If the
  problem is image-based and you have no vision (or the image is the only source of truth),
  do NOT override the sub-agent's result based on your own reading — relay its steps faithfully,
  clearly mark the parts you could not verify yourself, and let the user decide.
```

**问题根因③：多题图无定位约定**（承 §4.1 改动三，工具侧给入口）：

`description` 的 "Use when" 区补一句定位引导：

```markdown
If the image contains several problems, tell the user it will be solved one at a time and put
which one into `question`; if none is specified, the sub-agent picks the main one and says so.
```

---

## 5. P1：AI 调用解题子代理的功能体验

### 5.1 按需复核（Self-Consistency 的按需形态）

**根因**：调研确认 Self-Consistency 多采样投票能降幻觉（arXiv:2203.11171），但默认开启使每次解题 token/延迟成倍——对多数一次做对的题是浪费。把"投票"做成**用户按需触发**的复核动作，让难题、争议题、计算量大题的收益落在刀刃上。

方案：在结果区的追问线程输入框上方加一行预设快捷提问 chip——「用另一种方法验证 / 再检查一遍计算」。「另一种方法验证」走现成 `followUpQuestion`（不改链路），仅需在 UI 加 chip 与一句预置文案；成本≈零，且语言服务现状不用动。

取舍：这只在"换思路重解"层面做一致性弱校验，不是真正的 N 采样投票（那是 P2 远期：需 solve 链路支持同题多跑合并）。作为第一步性价比最高。

### 5.2 前台服务通知完成回跳（体验层，低优先级）

**根因**：`SolveGenerationForegroundService` 通知点击"仅拉起主界面（无会话）"——用户把 App 切走等解题时，收到完成通知点进去停在主页，找不到结果。既然结果在 SolvePage（有 Room 记录），完成时可把通知更新为"解答完成，点击查看"，点击拉起 `Screen.PhotoSolve`（结果按最近记录恢复，`restoreRecord` 已具备）。边界：Service 当前在 solve 结束即 release，完成态通知需要把"保活窗口"延长到用户点击或短超时（如 60s）——需评估前台服务时长策略，故列入低优。

### 5.3 页面路径上的已有能力确认（不改，仅核对）

- 聊天侧调用 `solve_question` 时，子代理任务卡片实时进度 UI 与 Room 落库已具备（`observeTask(toolCallId)`）。
- solver 失败自动重试已具备（`runWithTimeout` maxRounds），无需新增。
- SolvePage 结果区已具备 Reasoning 折叠、`<final_answer>` 高亮卡 + 一键复制、题干可编辑重解、追问线程——与 v3 的 restatement 配合后，"读错题可早期掐断 + 用户对照题面"的闭环才真正完整。

---

## 6. P2 / 远期（架构级，本次不做，留档）

| 方向 | 内容 | 取舍/边界 |
|---|---|---|
| **solver 装配程序化校验工具**（ToRA/PAL 路线） | 给 solver 开启受限计算工具（工作区 shell/sympy 重算关键代数），解方程/求导/积分类题由"心算 + 语言验证"升级为"程序化重算" | 子代理从纯 LLM 变多步 agent（maxSteps>1、requiresToolAbility 翻转），改动横跨 SubAgentRunner 装配面与任务 UI；只有计算密集题型收益明显，需先用冒烟集量化当前失败率再决定投入 |
| **结构化元信息**（学科/考点/难度） | 新增 `<solve_meta>` 之类的结构化头部供历史筛选、错题聚合（作业帮式学情） | 与现有 `<final_answer>` 切分协议正交，需扩 `splitSolveOutput` 与 Room schema；收益偏"沉淀"而非"单次体验"，宜在 v3 稳定后再议 |
| **题库命中优先** | 本地不可能建题库；联网搜题命中（search 模块）只对教辅原题有意义 | 版权与延迟成本高，不建议 |

---

## 7. 评测与验证

提示词/OCR 提示词改动是"软"改动，构建无法证明好坏，需要固定评测集 + 真机走查：

1. **冒烟题库**：新建 `docs/photo-solve-eval-set.md`，收录 10~15 道覆盖各学科的题（必须含：① 一道几何图题；② 一道含公式的计算题；③ 一道含多题的照片；④ 一道模糊/反光图），每道记录图源、期望答案。v3 前后用同一批题各跑一轮，人工比对 process 首段 restatement 是否忠实、final_answer 是否正确——这是提示词升级的验收标准。
2. **单测**：`SolveOutputParserTest` 不受影响（协议未动）；若抽共享常量，补一个断言 `solver.systemPrompt` 包含 `SOLVE_PROMPT_CORE` 的测试，防止漂移回归。
3. **构建验证**：改动均文本级，`./gradlew :app:compileDebugKotlin` + `:app:testDebugUnitTest` 即可；无 schema/依赖改动。
4. **真机走查**（工具声明修订后）：开 `enableQuestionSolver` 的助手，分别用①无视觉母代理 + 图片题 ②有视觉母代理 + 图片题 ③多题照片 发消息，确认：工具被调用、solver 读图、restatement 出现、母代理在无视觉时不乱改答案。

---

## 8. 落地顺序

| 步骤 | 内容 | 文件 | 状态 |
|---|---|---|---|
| 1 | v3 核心文本 → `SOLVE_PROMPT_CORE` 共享常量 + `<problem_statement>` 协议化题干块 | `data/ai/prompts/Solve.kt`、`data/ai/subagent/SubAgentCatalog.kt`、`data/ai/SolveOutput.kt`、`SolveOutputParserTest.kt` | ✅ 已合入（编译 + 单测通过） |
| 2 | OCR 提示词图形描述补强 | `data/ai/prompts/OcrPrompt.kt` | ✅ 已合入 |
| 3 | 工具声明三处修订（question 语义 / 验证豁免 / 多题定位） | `data/ai/tools/SolverTools.kt` | ✅ 已合入 |
| 4 | 题干卡全路径回显（vision 流式上屏 / OCR 回传 / 编辑重解分流） | `ui/pages/solve/SolveVM.kt`、`SolvePage.kt` | ✅ 已合入 |
| 5 | 冒烟题库 + 两轮对比评测 | `docs/photo-solve-eval-set.md` | ⬜ 待做（真机评测前置） |
| 6 | （P1）追问快捷 chip「换一种方法验证」 | `ui/pages/solve/SolvePage.kt` | ⬜ 待排期 |
| 7 | （P1 低优）前台服务完成通知回跳 | `service/SolveGenerationForegroundService.kt`、`SolveVM.kt` | ⬜ 待排期 |

第 1~4 步已于 2026-09-08 落地（相互独立，分批合入）；`./gradlew :app:compileDebugKotlin` 与 `SolveOutputParserTest` 单测均通过。步骤 5 起为真机评测/后续体验项。
