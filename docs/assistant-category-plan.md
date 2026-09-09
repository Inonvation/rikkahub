# 助手分类功能规划（v3 · 单分类）

> v3 变更（按 Inonvation 反馈）：分类语义从「多对多标签」收敛为**单归属分类**，分组浏览去掉「全部」组，未分类助手归入「其他」组；分类管理同时支持**分类顺序**与**每个分类内助手顺序**的调整。UI 交互稿见 `docs/assistant-category-collapse-mockup.html`（折叠动画提案）。

## 结论（v3 最终语义）

- 数据基座：`Tag(id, name)`（`data/model/Tag.kt`）、`Settings.assistantTags: List<Tag>`（顺序 = 分类展示顺序）、`Assistant.category: Uuid?`（**单归属**，null = 未分类 → 「其他」组）
- `Assistant.tags: List<Uuid>` 保留但**仅作旧数据兼容解码**：读取时 `category ?: tags.firstOrNull()`（`Assistant.effectiveCategory`）收敛为单值，PreferencesStore 解码链一次性把旧多分类搬到 `category` 并清空 `tags`，随后落盘
- 助手顺序仍只有一份：`Settings.assistants` 全局顺序。分类内顺序 = 全局顺序中该组成员的相对顺序；分类管理的组内拖拽通过 `reorderGroupMembers` 把新顺序**投影回全局槽位**，不另存顺序副本（同步/备份零改动）
- UI 形态：助手设置页与助手选择弹层均为 **分类折叠组 + 「其他」组**（默认全展开、手风琴平滑动画），去掉「全部」组；分类管理二级可管理分类成员顺序并支持跨分类移动

## 现状缺口（v2 遗留 → v3 闭合）

| 缺口 | 现状 |
|---|---|
| 多分类造成重复展示/计数歧义 | 收敛为单归属（`category: Uuid?`），同一助手至多属一个分类 |
| 「全部」组语义与「分类浏览」重叠 | 移除全部组；未分类归入「其他」组 |
| 组内顺序不可调 | 分类管理弹层二级页面：每个分类/「其他」内部助手可长按拖拽排序 |
| 卡片重复分类 chip | 单 chip 展示所属分类 |

## 数据模型与迁移

- `Assistant` 增加 `category: Uuid? = null`；`tags` 标记 @Deprecated，仅解码兜底
- `PreferencesStore` settingsFlow 规范化：`category = category ?: effectiveCategory`，并把 `tags` 置空，下次 `update` 落盘即完成迁移（幂等）
- `SettingsSyncCodec` / 备份 / DataStore 结构无需改（通用序列化，字段增减自动兼容）
- `AgentConfigImporter`：DTO 的 `tags` 只取第一个写入 `category`，本地已有 `category` 则保留

## UI 交互

### 助手设置页（AssistantPage）

- 分组列表：各分类组（assistantTags 顺序，折叠组头 + 成员）+「其他」组（category == null）
- 组头可折叠/展开（默认展开，手风琴动画）；搜索态扁平展示跨分组命中，不参与折叠与排序
- 卡片长按排序**移除**：排序入口统一收进分类管理（主列表只做浏览）
- 卡片 ⋯ 菜单 → 「编辑分类」改为**单选对话框**（含取消归属 = 回「其他」，可就地新建分类并选中）

### 分类管理弹层（CategoryManageSheet）

- 一级：分类列表（拖拽排序分类、重命名、删除、新建）+「其他」行（未分类助手计数）
- 二级（点行尾图标进入）：该分类或「其他」的助手列表，长按拖拽调整组内顺序；分类二级页可把其它助手移入、把成员移出到「其他」
- 顺序落盘：`reorderGroupMembers(orderedIds)` 保持非本组成员的全局相对位置不变，仅按新顺序回填本组成员槽位

### 选择弹层（AssistantPickerSheet / SafeMode）

- 与设置页同构：分类折叠组 +「其他」组，去掉「全部」Tab；SafeMode 过滤同步为 `effectiveCategory` 单值判断

## 明确不做

- 不保留多分类语义（不引入"主分类 + 附属分类"）
- 不为每个分类另存独立顺序副本（全局 assistants 顺序单源）
- 不在卡片上堆叠多个分类 chip
- 不合并 SafeMode 与主弹层的列表部分重复代码（同步 Tab 行语义即可）
