# Skill 界面与更新体验优化方案

状态：P0+P1 已实施（2026-09-19），P2 未做；详情页未包含「绑定 GitHub 仓库」入口（避免拖入整套导入流程，保留在列表页）
日期：2026-09-19

## 1. 现状与问题清单

### 1.1 更新链路（数据层）

现状：`SkillUpdateManager` 记录安装时 commit SHA + ETag + 目录级内容指纹；检查走 commits API
条件请求；应用更新 = `fetchSkillFiles()` 全量重下 → `saveSkillFileBytesAtomically()` 整目录替换。

问题：

| # | 问题 | 影响 |
|---|------|------|
| A1 | 应用更新**全量重下**所有文件，哪怕只改了 1 行 SKILL.md | 大技能（含图片资源）流量/时间浪费，弱网下更新易失败 |
| A2 | 应用更新**无进度回调**，UI 只有 busy 禁用 | 大技能更新像"卡死"，与导入（有 x/y 进度）体验不一致 |
| A3 | `CheckResult.UpdateAvailable` 只带 remoteSha，不解析 commit message/日期 | 用户不知道"更新了什么"，只能盲更 |
| A4 | 无变更预览：确认覆盖前看不到新增/修改/删除了哪些文件 | "覆盖本地修改"弹窗是盲盒 |
| A5 | 整目录替换会**连带删掉用户新增的文件**；且 `computeDirHash` 把用户新增文件也算进指纹，加一个本地文件就让整个技能变"本地已修改"、堵死自动更新 | 误伤用户扩展内容 |
| A6 | localModified 检测每次检查都全目录重读文件算 hash | 大技能下检查开销大 |
| A7 | 注册表无 per-file 指纹（manifest），无法做增量，也无法指出"本地改了哪几个文件" | 增量更新的前置缺口 |
| A8 | 无"检查全部/更新全部"的手动入口：`checkAll` 只在冷启动/Worker/进页时跑 | 多技能用户要逐个进菜单点 |

### 1.2 界面与交互

| # | 问题 | 影响 |
|---|------|------|
| B1 | 检查更新/立即更新/自动更新全部收在卡片「⋮」菜单里，有更新时只有一个文字 chip，不可点击 | 更新动作入口深、触达差 |
| B2 | 卡片不显示来源仓库、安装/最近更新时间 | 无法辨识技能来自哪里、新旧程度 |
| B3 | 更新结果只靠 toast；`NoChange`（内容其实没变）与 `UpToDate` 文案混用易困惑 | 反馈弱 |
| B4 | 详情页只有文件树 + 字节大小，无技能描述/来源/更新入口/自动更新开关 | 更新管理在列表页、编辑在详情页，割裂 |
| B5 | 编辑文件用 `AlertDialog + OutlinedTextField(maxLines=20)` | 长 SKILL.md 在弹窗里滚动编辑，体验差；无脏状态指示、无预览 |
| B6 | 详情页看不到本地修改状态，也无差异查看 | 与"本地已修改"护栏配套的查证手段缺失 |
| B7 | 批量选择只支持批量删除，不支持批量检查/更新 | 多技能更新效率低 |
| B8 | 导入对话框只有 URL 输入，无导入前预览（会发现哪些技能、多少文件） | 导入失败才知路径错 |

## 2. 目标

1. **增量差异更新**：只下载有变化的文件，更新前可预览变更清单，更新中有进度。
2. 保留全部现有护栏语义（本地修改保护、自动更新双开关、原子落盘、ETag 节流）。
3. 更新动作从"藏在菜单"升级为"徽标即按钮"，详情页补齐更新管理。
4. 编辑体验从弹窗升级为全屏编辑器。

## 3. 数据层方案：增量更新

### 3.1 注册表扩展（`SkillSource`）

新增字段（全部带默认值，老 JSON 兼容，沿用现有"缺字段不炸"约定）：

```kotlin
/** 安装/更新时的每文件指纹清单（相对路径 -> sha256），增量更新与本地改动定位的依据 */
val fileHashes: Map<String, String> = emptyMap(),
/** 最近一次检测到的更新对应的 commit 信息（展示用） */
val remoteCommitMessage: String? = null,
val remoteCommitTime: Long? = null,
```

- `contentHash`（整目录指纹）保留作为 fast path：本地快速判断"有没有任何变化"。
- 写盘/读盘不变（`.sources.json`），只多序列化两个字段。

### 3.2 变更集计算（纯函数，可单测）

```kotlin
sealed interface FileChange {
    data class Added(val path: String) : FileChange
    data class Modified(val path: String) : FileChange
    data class Removed(val path: String) : FileChange
}
// manifest = 注册表 fileHashes；remote = 本次列树后逐文件 hash；disk = 本地逐文件 hash
fun computeChangeSet(manifest, remote, disk): List<FileChange>
```

- 下载新文件时逐文件算 hash（`computeFilesHash` 已有单文件能力，抽个 `hashFile(path, bytes)`）。
- **localModified 语义细化**（修复 A5）：
  - "已修改" = 清单内文件在磁盘上内容不一致（tracked 文件被改/被删）；
  - 用户新增的文件（不在清单内）**不再**视为"本地已修改"，只算"本地扩展"；
  - 自动更新放行条件：无 tracked 修改 → 放行，且保留用户新增文件。

### 3.3 增量应用（`applyUpdateLocked` 重写）

```
1. listTreeFiles()                      // 1 个 API 请求（不变）
2. 与 manifest 对比，得到 changeSet     // 纯本地
3. 只下载 Added + Modified 的文件       // raw 优先 + 并发 6（复用现有）
4. staging 组装：
   - 变更文件写入 staging
   - 未变化的 tracked 文件、用户新增文件从本地**复制**进 staging
   - Removed 的 tracked 文件不复制（即删除）
   - 仍然 staging + rename 整目录原子替换（落盘安全语义不变）
5. 更新注册表：fileHashes = remote 全量、commitSha/etag 刷新
```

- 网络开销从 O(全部文件) 降到 O(变更文件)；本地 copy 很廉价，换取原子性不回退。
- `applyUpdate` 增加 `onProgress(done, total)` 回调（total = 下载文件数），复用 SkillsVM 导入的
  150ms 节流发射模式。
- `recordInstall` 同步写入 per-file manifest。
- localModified 快速判断改为：整目录 hash 与 contentHash 不一致时，再逐文件 diff 细分
  （先便宜后昂贵，两层判断）。

### 3.4 检查阶段增强

- `getPathCommitHead` 的响应体本身就含 commit message/author/date（per_page=1），
  顺带解析出 `remoteCommitMessage` / `remoteCommitTime`，随 `UpdateAvailable` 返回并落注册表。
- 检查请求次数不变（仍是 1 次/技能/检查周期）。

## 4. UI 方案

### 4.1 SkillsPage（列表页）

**卡片（B1/B2）**：

- 有更新时，「有更新」chip 升级为**可点击的填充色按钮**（tertiary 底）："更新 ↻"，
  点击直接进入更新流程（先弹变更预览，见 4.3）；不再进菜单找"立即更新"。
- 卡片副标题行增加来源：`owner/repo · path`（有 source 时），以及最近更新相对时间
  （"3 天前更新"）。menu 里补"查看仓库"跳浏览器（可选）。
- busy 时卡片右上角菜单图标替换为 16dp CircularProgressIndicator（现在只有禁用，无状态感）。

**页级操作（A8/B7）**：

- 顶栏增加"检查全部"动作（触发 `checkAll(force=true)`，逐技能 busy 态在卡片内联显示）。
- 列表顶部（GitHub 绑定提示之下）插入一条**更新横幅**：存在 updateAvailable 技能时显示
  "N 个技能有更新 [全部更新]"，一键对所有非 localModified 技能走预览→应用流程
  （localModified 的保持逐个确认）。
- 批量选择模式补两个动作：批量检查、批量更新（与批量删除并排在浮动工具条）。

**反馈（B3）**：

- 应用成功改为 toaster + 卡片徽标即时消除（sources flow 已是响应式，无需额外处理）；
  `NoChange` 文案与 `UpToDate` 区分（"远端无实质变更"）。

### 4.2 SkillDetailPage（详情页）

- 顶部（文件树之上）加一段**技能信息头**：description、来源仓库（点击可跳转）、
  安装/最近更新时间、更新状态行：
  - 有更新 → "更新 ↻"按钮 + commit message 摘要；
  - 本地已修改 → 列出被修改的 tracked 文件（来自 per-file diff）+ "放弃我的修改/更新覆盖"入口；
  - 无 source → "绑定 GitHub 仓库"入口（复用现有 bindMode 对话框）。
  - 自动更新开关从列表页菜单迁到这里（列表菜单保留也行，二选一拍板）。
- **编辑器升级（B5）**：把 `EditFileDialog` 换成全屏对话框（`Dialog(properties = usePlatformDefaultWidth=false)`
  或独立 route）：等宽字体、不限行数滚动、顶部文件名 + 脏状态圆点、保存/取消按钮；
  `.md` 文件加"预览"切换（项目内已有 markdown 渲染组件可复用，聊天消息渲染那套）。
- 新建文件对话框保持，补"新建目录"（mkdirs）小动作（可选）。

### 4.3 更新流程统一（核心交互）

手动更新（卡片按钮 / 详情页 / 全部更新）统一为：

```
点击"更新"
  → 下载变更文件（内存中，带进度：正在获取 2/5 个变更文件）
  → 变更预览 Dialog：
      commit「fix: xxx」· 2 天前
      +2 新增  ✎3 修改  −1 删除
      （可展开文件列表，修改的 tracked-本地-也改过的文件标"将覆盖你的修改"）
  → [取消] / [应用更新]
  → staging 原子替换 → toaster 成功 → 徽标消失
```

- localModified 且用户未确认时，预览弹窗直接内联"将覆盖你的修改"警示 + 覆盖按钮，
  替代现在的两段式（SkippedLocalModified → 二次确认弹窗），少一次打断；
  自动更新路径仍走旧的安全护栏（静默跳过）。
- 无变化（NoChange）时预览弹窗直接显示"内容无实质变化"并可顺手刷新记录。

### 4.4 导入预览（B8，可选 P2）

导入对话框确认后先列树 + findSkillRoots，展示"将导入 N 个技能：a、b、c（共 M 个文件）"
再开始下载。复用增量更新的列树结果逻辑，成本低。

## 5. 分期落地

| 阶段 | 内容 | 涉及文件 |
|------|------|----------|
| P0 数据层 | SkillSource 扩展 + 变更集计算 + 增量应用 + 进度回调 + commit 信息解析；recordInstall 写 manifest | SkillSource.kt / SkillUpdateManager.kt / GitHubSkillClient.kt / SkillsVM.kt |
| P0 UI | 卡片"更新"按钮化 + 更新横幅/全部更新 + 预览 Dialog + 更新进度 | SkillsPage.kt / SkillsVM.kt |
| P1 | 详情页信息头（来源/更新状态/localModified 文件清单/绑定入口） | SkillDetailPage.kt / SkillDetailVM.kt |
| P1 | 全屏编辑器 + md 预览 | SkillDetailPage.kt（新组件文件） |
| P1 | 批量检查/批量更新 + 顶栏"检查全部" | SkillsPage.kt / SkillsVM.kt |
| P2 | 导入预览、查看仓库外链、新建目录、diff 视图（需快照缓存，另立小方案） | — |

## 6. 测试与验证

- 纯 JVM 单测：`computeChangeSet`（新增/修改/删除/用户新增保留/manifest 缺失自愈）、
  老 JSON（无 fileHashes）解码兼容、`parseCommitsResponse` 扩展字段。
- `SkillUpdateManager` 用 fake `GitHubSkillClient`（现有测试若已有桩则扩展）验证：
  增量只请求变更文件、staging 替换后本地新增文件存活、localModified 细分语义。
- 编译验证：`./gradlew :app:compileDebugKotlin`；模块测试按 Windows 环境怪癖只跑目标模块任务。
- 真机验证用户自理；交付编译 + 单测 + 自审。

## 7. 风险与取舍

- **staging 复制大文件**：增量应用要对未变化文件做本地复制，超大技能（几十 MB 资源）
  会有一次磁盘拷贝成本。可接受（相比全量重下仍大赚）；后续可用 `renameTo` 逐文件 move +
  失败回退优化，但会牺牲严格原子性，暂不做。
- **registry 体积**：per-file hash 使 `.sources.json` 增大（2000 文件技能约 +150KB 上限）。
  可接受；如有需要后续改为独立 manifest 文件。
- **commit message 截断**：展示取首行、超 80 字符截断。
- **文案/本地化**：新 UI 文案按页面惯例走 `strings.xml`（skills_page_* / skill_detail_page_*），
  用 locale-tui skill 补齐。
