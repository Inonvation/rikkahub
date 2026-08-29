# 助手分类功能规划（v2）

> v2 变更：按反馈把改造重心放在「助手」设置页本身——分类管理成为页面一级能力，而非藏进对话框；同时扩容助手卡片「⋯」菜单。UI 形态见 `docs/assistant-category-mockup.html`。

## 结论

分类能力**直接复用现有标签体系**，不新增数据模型、不做数据迁移：

- 数据基座：`Tag(id, name)`（`data/model/Tag.kt`）、`Settings.assistantTags: List<Tag>`、`Assistant.tags: List<Uuid>`（多分类绑定，多对多天然支持）
- 持久化与同步：PreferencesStore（`ASSISTANT_TAGS`）、SettingsSyncCodec 已含 `assistantTags`、备份导入天然兼容
- 工作量集中在 UI 层：助手设置页改为**分类单选 Tab 导航 + 分类管理 Sheet**，卡片「⋯」菜单扩容；切换助手弹层同步改为分类 Tab（保持两处交互一致）

## 现状缺口

| 缺口 | 现状 |
|---|---|
| 分类管理弱 | `TagsInput` 藏在助手详情页，主列表页只能过滤，无法新建/重命名/删除/排序分类 |
| 切换器是过滤不是导航 | 多选"任一匹配"chips，无「全部」态，不符合"切到某分类看助手"的直觉 |
| 卡片「⋯」菜单太空 | 只有克隆 / 删除 |
| 三处过滤行代码重复 | AssistantPicker.kt / SafeModeActivity / AssistantPage 各一份 |

## 方案设计（对应草图 ①–⑤）

### 1. 助手页主界面（草图①⑤，P0）

- 标签过滤行升级为**分类单选 Tab**：行首「全部」chip + 各分类 chip（单选，点分类只看该分类助手，再点/点全部回到全部），行尾固定 **⚙ 分类管理** 入口
- 搜索、卡片布局、助手长按拖排序（仅「全部」态可用，沿用现有 `isFiltering` 语义）不变
- **选中分类时**：列表末尾追加虚线「＋ 添加助手到此分类」卡 → 弹出多选对话框（列出未属于该分类的助手，勾选批量加入）；空分类时列表仅有此卡

### 2. 分类管理 Sheet（草图②，P0）

`ModalBottomSheet`（复用现有底部弹层组件）：

- 每行：≡ 拖动排序（复用 `rememberReorderableLazyListState`）+ 分类名 + 助手数 + ✏️ 重命名 + 🗑 删除
- 重命名：AlertDialog + OutlinedTextField，重名校验（复用 TagsInput 对话框逻辑）
- 删除：确认弹窗 → 删除该 Tag 并从所有 `Assistant.tags` 移除引用，一次 `updateSettings` 落盘；助手本身不受影响
- 底部「新分类名称…」输入行 + 添加按钮
- 分类排序结果同步作用于切换助手的 Tab 顺序（同一个 `assistantTags` 列表）

### 3. 卡片「⋯」菜单扩容（草图③④，P0）

`AssistantActionSheet` 扩为：

- **编辑分类**（新增，核心入口）：AlertDialog 多选 chips + 「＋ 新建分类」就地创建并勾选（复用 TagsInput 重名校验）；副标题显示当前归属
- **移到顶部**（新增）：一次 `updateSettings` 移到列表首位
- **克隆**（已有）
- **删除**（已有，默认助手按 `DEFAULT_ASSISTANTS_IDS` 门控隐藏）

### 4. 切换助手弹层对齐（P1）

`AssistantPickerSheet`（ChatPage / SafeModeActivity 两处）的过滤行替换为同一套分类单选 Tab（「全部」+ 分类），与设置页交互一致；列表卡片与切换流程不动。可将 Tab 行抽成共享组件收拢三处重复。

### 5. 文案（P1，走 locale-tui）

「标签」→「分类」：改现有 key 值（`assistant_page_tags`、`tag_input_dialog_*`），新增 key（全部 / 分类管理 / 移到顶部 / 编辑分类 / 添加助手到分类等）。

## 实施顺序

1. 助手页 Tab 化 + 分类管理 Sheet + VM（`renameCategory` / `deleteCategory`，复用 `updateSettings`）→ `./gradlew :app:compileDebugKotlin`
2. 「⋯」菜单扩容（编辑分类对话框、移到顶部）+ 分类内「＋ 添加助手」
3. 切换器 Tab 对齐（共享组件）
4. locale-tui 文案统一；过滤/引用清理抽纯函数可补 JVM 单测；装机与 UI 验证用户自理

## 明确不做

- 不新增独立 Category 模型、不强制单分类归属
- 不动数据模型/迁移/备份/同步（零字段变更）
- 不在卡片上额外堆分类信息（现有"前 2 个分类 chip"展示保留）
- 不合并 SafeMode 与主切换器的列表部分重复代码（仅同步 Tab 行）
