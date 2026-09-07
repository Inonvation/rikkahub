# 拍照解题体验重构规划（v2：从「翻译式页面」到「拍题工作台」）

> 状态：**阶段 A + B 已实施，待真机验收**（2026-09-07；阶段 C 待拍板）
> 目标：把当前「镜像 AI 翻译的表格工具页」重构为真正的拍题体验——**应用内相机取景 → 框选题目 → 自动解题 → 题干可核对修正**的连贯闭环。
> 方向已与 Inonvation 拍板：① 拍题入口采用 **CameraX 应用内取景**；② 题干可编辑按 **Y 方案**（仅 OCR 降级路径展示可编辑题干）；③ 生成中退出页面需**保存进度/续解**。

## 0.2 实施记录（阶段 B + 续解，2026-09-07）

在阶段 A 基础上追加：

1. **框选可见性修复**（阶段 A 真机反馈：白纸照片上白框不可见）：遮罩加深 0.62、框线改主题主色 3.5dp + 内侧白高光线 1.5dp、手柄白外圈 + 主色内芯。
2. **题干可编辑（Y）**：`SolveVM` 新增 `resultQuestion`（取 `SolveResult.questionText`，仅 OCR 降级路径有值；vision 直送为 null 不展示）+ `resolveWithQuestion(text)`——纯文本重解，**不重发题图**（避免无视觉模型内部 OCR 二次覆盖用户修正）；落库图片引用回退会话首图，历史缩略图不丢。结果页新增题干卡（文本可选中 + 「修正」编辑 + 「保存并重新解题」）。
3. **跨页面续解**（架构变更）：`SolveVM` 由 ViewModel 改为 **Koin 单例**（注入 `AppScope`），页面改用 `koinInject`（import 为 `org.koin.compose.koinInject`）。根因：ViewModel 随页面 entry 销毁即取消 job，用户解题中途退出会丢任务；单例 + AppScope 使 job 与状态常驻，重进页面自动接上进度（进程被杀任务终止，已完成结果已落库，语义与前台服务一致）。`ViewModelModule` 移除原 viewModel 注册，注册迁至 `AppModule`。
4. strings 追加 en/zh 各 5 条（题干卡文案）。

验证：`:app:compileDebugKotlin` ✅ 66s；`:app:assembleDebug` ✅ 6m49s（含一次瞬态文件锁 AccessDenied 重试）。装机走查待设备连接。


## 0.1 实施记录（阶段 A，2026-09-07）

已按 §4 阶段 A 落地，与规划的差异（实施中的合理调整）：

1. **SolvePage 主壳瘦身**：由 1086 行压缩至 ~470 行。页面 = `SolvePhase`（ViewFinder/CropConfirm/Solving/Result）四态分发；历史仍是页面内子模式（复用同一 VM 回填），但 v1 的表单输入卡（拍照/相册等宽按钮 + 补充说明卡 + 手动「开始解题」）全部移除。
2. **`SolvePhase` 状态机进 VM**（`SolveVM.kt`），v1 的「内容非空推断阶段」（hasActivity）退役；VM 新增 `images: List`（对齐链路 List 语义）与 `pendingCapture`（框选中的原图）；`confirmCropAndSolve(normRect)` 裁切落盘后**自动触发 solve**，无手动按钮。
3. **CameraX 取景**（`SolveCameraView.kt`）：`PreviewView` + bindToLifecycle（DisposableEffect 绑/解绑 + dispose unbindAll）；快门写 `cacheDir/solve_camera` 后经 FileProvider 转 content://（与相册 uri 类型统一，裁切链路都走 contentResolver）；顶部悬浮条（返回/历史/模型）由壳覆盖。`camera-core` 死依赖补全为 camera2/lifecycle/view 三件套（1.6.2）。
4. **框选**（`SolveCropOverlay.kt`）：应用内拖拽矩形取代 uCrop 跳转。坐标系约定：原图 coil 按 EXIF 转正显示、裁切前 `ImageUtils.convertHeifToJpeg` 把旋转烘焙进位图 → 两侧同朝向，框选归一化 (0..1) 直接按像素比例映射。手势 = 框内平移 + 四角缩放，clamp 在图片渲染区内。
5. **补充说明**（note）移到框选确认页底部单行输入；纯文本无图解题不再暴露 UI（solveQuestion 链路保留兼容）。
6. **历史图片文件引用保护**（实现时发现）：retake/换题清理当前图片时只删未被历史记录引用的文件——历史 imagePath 与当前会话文件同源，直接删会让历史卡片空图。
7. **strings**：en +15 / zh +15，`photo_solve_tap_to_zoom` 文案去掉已失效的 "re-crop"；其余语言待 locale-tui 同步（遗留项）。
8. `skipCropImage` 全局设置保留（聊天发图仍消费），SolvePage 不再读取；uCrop/`useCropLauncher`/FilesPicker/ChatPage 全链路未动。

验证：`:app:compileDebugKotlin` ✅（首轮全量 11m 出 2 错修复后增量 4m4s 通过）、`:app:assembleDebug` ✅（offline，6m17s，APK 87M）。真机走查清单见 §7。


## 0. 本轮定位（先讲清来龙去脉，避免推翻不该推翻的东西）

拍照解题功能已上线两版，两版文档都在 `docs/`，定位不同：

| 文档 | 定位 | 与本次关系 |
|---|---|---|
| `photo-solve-plan.md`（已实施） | v1 从 0 到 1：配置层 + `solveQuestion` 核心 + 页面 + `solve_question` 工具。**页面明确「整体镜像 TranslatorPage」**——这是当时快速上线的有意取舍 | "照抄翻译"的根因在此，不是 bug，是 v1 策略 |
| `photo-solve-ui-proposal.html`（已实施） | 真机反馈后的排版修正：输入/生成/结果三态、思考折叠露尾、解答卡合并、底部操作条 | 解决的是**排版层次**，已体现在当前 `SolvePage.kt` |

**本轮不重做**：`solveQuestion` 数据链路（vision 直送 / OCR 降级 / 三段输出协议）、`solve_question` 工具与 solver 子代理、Room 历史结构与 DAO、前台保活服务——这些在 v1 已成型且与 UI 解耦，重构不动。

**本轮要解决的**：UI 仍然是「表单工具页」而非「拍题应用」的**范式差距**——无取景、拍解分离、无框选、无题干核对通道。这是交互范式重构，不是又一次排版修补。

## 1. 现状诊断（代码级，全部对照当前树核实）

### 1.1 交互链路：两次跳转、拍解分离
```
抽屉入口(ChatDrawer:848) → SolvePage
  → [表单输入态] 拍照按钮 / 相册按钮(等宽大卡)
  → 系统相机 TakePicture(ActivityResultContracts)     ← 跳系统相机 App
  → uCrop Activity(useCropLauncher / CropLauncher.kt) ← 再跳一个外部裁剪页
  → 回 SolvePage → 手动点「开始解题」                  ← 与拍照是两个动作
  → 流式输出
```
- `SolvePage.kt:165-190`：`TakePicture()` + `GetContent()` 双 launcher，中间穿插 uCrop，全部回调逻辑手写在 Composable 里。
- 无任何「对准题目」语境：照片角度、取景范围、题目占画面比例都不受控，拍完只能整张图发或跳去 uCrop 手工抠。

### 1.2 照抄翻译页的具体证据（这就是"不像解题功能"的视觉根因）
- 顶栏同构：`Clock02` 历史入口 + `ModelSelector(onlyIcon)` + `BackButton`（`SolvePage.kt:231-257` ≈ `TranslatorPage.kt:186-214`）。
- **历史是页面内子模式**：`showHistory` + `selectedIds` + `selecting` + 两个删除对话框的状态机整段复制自翻译页（`SolvePage.kt:130-145,192-307` ≈ `TranslatorPage.kt:111-127,148-266`），注释自述"与翻译页一致"。
- 卡片/按钮语言同源：标题 label 卡片 + `CardToolButton`、全宽 48dp 主按钮、`LinearWavyProgressIndicator` 生成卡、`SelectionContainer`+Markdown 结果卡。
- `SolveHistoryView.kt`(379 行) 与 `TranslationHistoryView` 大面积重复（左滑删除/长按多选/批量删除同一套）。

结论：解题页与翻译页唯一的差异只是"输入内容从语言变成图片+补充文本"。搜题 App 的核心心智（镜头对准、框选、即拍即解）完全缺席。

### 1.3 工程问题（重构要顺手清掉的）
1. **无 OCR 题干回显**：`solveQuestion` 里 vision 直送路径 `questionText = null`（`GenerationHandler.kt:1208-1227`）；OCR 降级路径才回传题干且只落库（`SolveResult.questionText`），UI 从不展示 → 识别错误零纠正通道。
2. **单图状态**：`SolveVM._imageUri: String?`（`SolveVM.kt:51`），而链路层 `solveQuestion(imageUris: List)` 本来就支持多图——UI 拖了后腿。
3. **阶段判定靠"内容非空"而非显式状态**：`SolvePage.kt:313` `hasActivity = generating || reasoning.isNotBlank() || ...`——拍照后、解题前的"待确认"中间态无法表达，将来加 Crop 确认页必须改显式 phase。
4. **临时文件生命周期散落**：`cameraOutputFile`/`onCleanup`/手动 delete 都在 Composable 作用域（`SolvePage.kt:154-190`），换图/取消/裁剪失败路径多，易漏。
5. **离开页面即丢当前生成**：`SolveVM.onCleared` 取消 job（`SolveVM.kt:249-254`），前台服务只防进程冻结不防 VM 销毁——用户解题中途切走，回来一场空且不落库。
6. **死依赖信号**：`camera-core 1.6.2` 在 `app/build.gradle.kts:252` 声明但全仓零 import——说明早规划过 CameraX，本次可顺理成章补齐三件套。

## 2. 目标形态：拍题工作台

核心转变：**首页从"表单"变成"镜头"；解题从"手动触发"变成"拍完即解"**。

```
┌─────────────────────────────────────────────────────────────┐
│ 态 0  取景（无题图时，主视图）                                 │
│   全屏相机预览（无顶栏干扰）+ 底部操作区                        │
│   [快门大钮] [相册] [闪光灯] ｜ 顶部角标：模型/设置               │
├─────────────────────────────────────────────────────────────┤
│ 态 1  框选确认（拍完/选完图）                                  │
│   全屏显示题图，拖拽矩形框选题目；框外变暗、可缩放、可重拍/重选   │
│   [重拍] [确认 → 自动解题]                                     │
├─────────────────────────────────────────────────────────────┤
│ 态 2  解题/结果                                                │
│   题图摘要条（缩略+重拍/移除）                                  │
│   题干卡（识别文本，可编辑修正 → 修正后重解）※阶段 B             │
│   思考过程（折叠露尾） + 解答卡（过程 + 可誊抄作答）              │
│   底部：重新解题 / 复制作答 ｜ 顶栏：历史、模型选择               │
└─────────────────────────────────────────────────────────────┘
```
历史保留页面内子模式，但状态机/删除逻辑从主函数抽离（§4-A2）。

**为什么是这个形态**：对照主流拍题应用（作业帮/夸克）验证过——它们的公共范式是"镜头即首页、框选即指令、拍完不等按钮"。三条都不复杂，缺的只是把现有链路首尾接起来：`solveQuestion` 已能流式解题，差的是"相机取景 UI + 一次自动触发 + 框选裁剪替换 uCrop"。

## 3. 关键取舍

### 3.1 取景：CameraX 应用内相机（已拍板）
- **依赖**：`gradle/libs.versions.toml` 新增 `camera-camera2` / `camera-lifecycle` / `camera-view`（对齐现有 `cameraCore = 1.6.2`）；`PreviewView` 是 View，用 `AndroidView` 包进 Compose。
- **权限**：复用现有 `rememberPermissionState(PermissionCamera)`（`ui/components/ui/permission/`，`PermissionTypes.kt:56`）+ rationale 弹窗 + 去设置引导，框架已具备。
- **生命周期**：`ProcessCameraProvider` 的 bind 放在 `DisposableEffect` 内绑定/解绑，随页面组合生命周期走。
- **取舍说明**：uCrop 仍保留——聊天发图路径（`ChatPage`/`FilesPicker`/`useCropLauncher`）继续用它；只是解题侧不再跳外部裁剪 Activity。`skipCropImage` 全局设置保留（聊天仍消费，`SettingPreferencesGeneralPage.kt:133`），SolvePage 的读取分支移除。

### 3.2 框选：应用内拖拽矩形，替换 uCrop
- 拍照/选图后进入**同一页面内的框选层**（全屏题图 + 半透明遮罩 + 可拖拽矩形，四角手柄 + 拖动平移）。
- 确认后按矩形裁剪出子图再落盘（`filesDir/solve_images/`，沿用 `SolveVM.onImageReady` 拷贝先例）→ **直接自动触发 `solve()`**（无按钮）。
- 坐标换算：框选矩形基于显示图面坐标，导出需映射到原图像素坐标（含 EXIF 旋转）；实现时用显示尺寸与位图尺寸比例换算，加最小框尺寸约束防误触。
- **取舍**：为什么不用 uCrop 就地改造？uCrop 是外部 Activity + 通用裁剪心智（无"对准题目/去掉无关版面"的语境与快捷文案），且无法与"确认即自动解题"衔接；自绘框选层 ~150 行，换来单页内闭环与可引导文案。

### 3.3 题干编辑：两条路线，阶段 B 内选
拍题 App 的纠错闭环 = 「识别题干 → 用户可见可改 → 以修正题干解题」。当前代码只有 OCR 降级路径才有题干文本，vision 直送路径没有：

- **方案 X（统一 OCR 先行，体验对齐拍题 App）**：无论解题模型有无视觉，拍照确认后先用 `ocrModelId` 提取题干文本 → 题干确认/编辑页 → 图+文本（vision 模型）或纯文本（非 vision 模型）送解题。统一了所有用户路径，纠错发生在解题前（省 token、省一次跑偏结果）。代价：每次多一次 OCR 请求延迟（可做"跳过识别直接解题"快速通道）。
- **方案 Y（最小改动）**：链路不动，仅把降级路径已有的 `questionText` 外显为题干卡（结果页可编辑 → 改后重解）；vision 路径暂不提供题干卡。

建议：**阶段 A 不做题干编辑，先交付取景-框选-自动解题闭环**（Y 里"降级题干可见"顺手做掉，成本≈0）；**阶段 B 拍板 X 或 Y 深化**。理由：题干确认关卡若做成强制步骤会打断"拍完即解"的连贯性，应先让用户感受新交互，再决定要不要在解题前插一道确认。

### 3.4 状态显式化 + 历史解耦
- `SolveVM` 增加 `UiPhase { ViewFinder, CropConfirm, Solving, Result, History }` 显式状态驱动页面（替代现在 `hasActivity` 靠内容非空推断，`SolvePage.kt:313`）。
- `_imageUri: String?` → `_images: List<String>`（对齐链路 `List` 语义；本轮 UI 仍单张，未来连拍/多题直接进）。
- 历史子模式（showHistory/多选/删除）从 `SolvePage` 主函数抽为独立 `SolveHistorySection.kt`，主函数从 1086 行瘦到 ~300 行。**刻意不与翻译页合并**——两页后续演进方向不同，提前耦合不如各自内聚；重复的只有通用删除状态机，暂不抽公共库（收益低、动翻译页风险大）。

### 3.5 已知限制（本期不动，如实标注）
- 解题中离开页面仍取消任务（§1.3-5）。要根治需把生成 job 提升到 Activity/Service 作用域（改动面大）；本期维持现状语义并写进 Release 已知问题。规避手段：结果自动落库更快，且离开重进从历史回填。
- 不做自动框题/版面检测（需要专门版面分析模型，收益不确定）；不做连拍队列、不入错题本（阶段 C 候选）。

## 4. 阶段拆分与改动明细

每阶段独立验证构建，可单独交付。建议顺序：A → B；C 另立。

### 阶段 A：取景 + 框选 + 自动解题闭环（本次主目标）

**A1 依赖与相机基础**
- `gradle/libs.versions.toml` + `app/build.gradle.kts`：加 `camera-camera2` / `camera-lifecycle` / `camera-view`（1.6.2）。
- 新增 `ui/pages/solve/SolveCameraView.kt`：`PreviewView`(AndroidView) + 快门/相册/闪光灯 + `ImageCapture` 回调（JPEG，尺寸上限），权限不足时展示引导而非黑屏。
- `Manifest` 无新增权限（CAMERA 已有）。

**A2 页面结构重构（SolvePage 拆分）**
- `SolvePage.kt`：仅保留 Scaffold + phase 路由 + 共享状态（错误 toast/snackbar/对话框）。
- 新增组件文件：
  - `SolveCameraView.kt`（态 0，全屏取景）
  - `SolveCropOverlay.kt`（态 1，框选/重拍/确认）
  - `SolveResultSection.kt`（态 2：题干卡 + 思考 + 解答 + 底部操作条；把现有 `ReasoningCard/SolutionCard/ImageThumbStrip` 迁入并局部调整）
  - `SolveHistorySection.kt`（历史子模式状态机 + 列表，自 `SolvePage` 抽出）
- 顶栏策略：态 0 全屏沉浸（隐藏 TopAppBar，避免"工具页"感）；态 2 显示历史/模型角标。

**A3 VM 状态改造**
- `UiPhase` 状态机 + `_images: List<String>` + 拍照临时文件管理收敛进 VM（新增 `takePhoto(context)` 内部自管临时文件，替换 Page 里散落的 `cameraOutputFile`/`onCleanup`）。
- 框选确认回调 → 裁剪落盘 → **自动调用 `solve()`**（移除「开始解题」手动按钮路径）。
- `noteText` 保留：态 0 底部"补充说明"折叠条（如"只求第三问"）→ 并入请求。

**A4 顺手项**
- 降级路径题干外显：结果页题干卡在有 `questionText` 时展示（阶段 B 再做可编辑）。
- strings：新增取景引导/框选引导/重拍/自动解题中 等文案（EN+zh，走 `locale-tui`）。
- 验证：`:app:compileDebugKotlin` + 真机走查（权限首启、拍照→框选→自动解题、相册路径、框选一致性）。

### 阶段 B：题干回显统一 + 可编辑 + 追问

- **题干可见**：按 §3.3 拍板 X（统一 OCR 先行+题干确认页，带"跳过识别"快速通道）或 Y（仅降级题干展示）。涉及 `data/ai/SolveOcr.kt`（X 时把 ocr 调用从降级分支提到前置）、`GenerationHandler.solveQuestion`（X 时解耦"题干提取"与"解题"两个阶段）。
- **编辑重解**：题干卡进入编辑 → 保存 → 以「修正题干 + 原图」重跑 `solve()` → 新历史记录。
- **追问**：结果卡底部输入"换种解法/更详细/看不懂"→ 追加为 questionText 重解（新记录）。最小实现即可，不做聊天式多轮。

### 阶段 C：后置增强（本期不做，立项候选）
多题连拍入队（`_images` 已铺路）、一键入错题本、聊天页与解题页题干组件复用、自动版面框题、离开页面续解（job 提升 Service 作用域）。

## 5. 边界与风险

| # | 风险/边界 | 对策 |
|---|---|---|
| 1 | 框选矩形 ↔ 原图坐标偏移/EXIF 旋转 | 导出按位图实际尺寸比例换算；显示图统一按旋转后朝向；真机用含边框文字试卷验证 |
| 2 | CameraX 绑定生命周期（旋转/退后台/恢复） | `DisposableEffect` 解绑重绑；取景页不缓存 `Preview` 于 VM |
| 3 | 低光/抖动照片质量 | ImageCapture 用 JPEG + 尺寸上限；提示文案"保持纸张平整、光线充足" |
| 4 | 权限拒绝/永久拒绝 | 复用 rationale 弹窗 + 去设置引导；未授权时不黑屏给引导层 |
| 5 | 生成中离开页面丢任务（现状语义） | 本期保留，标注已知限制；结果已落库可从历史回填 |
| 6 | 动画副作用（用户对抖动/滚动敏感） | 态切换不做整页动画；输出跟随沿用 600px 视口阈值；框选层出现用简单淡入 |
| 7 | camera 三件套引入包体 | camera-view 等约 1MB 级，minSdk 26 满足；可接受 |
| 8 | 与聊天发图路径相互影响 | uCrop/`skipCropImage`/FilesPicker 一律不动，仅 SolvePage 停止读取 |

## 6. 文件改动清单（预估）

**新增**
- `gradle/libs.versions.toml`、`app/build.gradle.kts`：camera-camera2/lifecycle/view 依赖
- `ui/pages/solve/SolveCameraView.kt`（取景）
- `ui/pages/solve/SolveCropOverlay.kt`（框选）
- `ui/pages/solve/SolveResultSection.kt`（结果区，自 SolvePage 迁出）
- `ui/pages/solve/SolveHistorySection.kt`（历史状态机，自 SolvePage 迁出）
- `data/ai/SolveCrop.kt`（框选裁剪纯函数：坐标换算 + 位图裁剪，可单测）※若框选逻辑放 VM 可并入

**修改**
- `ui/pages/solve/SolvePage.kt`：大幅瘦身，只留路由
- `ui/pages/solve/SolveVM.kt`：`UiPhase` + `_images: List` + 拍照临时文件自管 + 自动 solve
- `app/src/main/res/values*/strings.xml`：新文案（locale-tui）
- （阶段 B）`data/ai/GenerationHandler.kt` / `data/ai/SolveOcr.kt` / `data/ai/SolveOutput.kt`

**不动**：`SolveHistoryDao`/Entity/Room、`SolveGenerationForegroundService`、`solve_question` 工具与子代理、`RouteActivity`、`ChatDrawer`、聊天发图与 uCrop 全链路。

## 7. 验证方式

1. 每阶段结束：`./gradlew :app:compileDebugKotlin`（涉及新增裁剪纯函数时补 `:app:testDebugUnitTest`）。
2. 阶段 A 真机验收清单：
   - 首启权限：允许 / 拒绝 → 引导 → 去设置回来；
   - 拍照 → 框选 → 自动解题端到端（无手动按钮）；
   - 相册路径 → 同一框选层；
   - 框选边界一致性：拍含上下左右边界的试卷，裁剪结果无偏移；
   - 重新拍照/移除/历史回填在 new phase 下不回归；
   - 聊天发图仍走 uCrop/skipCrop（回归）。
3. 收尾：`:app:assembleDebug` + `adb install -r`（只覆盖安装）。
