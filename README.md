<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub</h1>

  <p>原生 Android LLM 聊天客户端，支持在多个 AI 服务商之间自由切换。</p>
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat 界面" width="150" />
  <img src="docs/img/desktop.png" alt="模型选择" width="450" />
</div>

## 项目简介

RikkaHub 是一款原生 Android 的 LLM 聊天客户端，支持兼容 OpenAI、Google、Anthropic 等接口的自定义
API，并可通过 Web 端在多平台使用。

本仓库是 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 的**个人定制分支**，由
Inonvation 维护，**仅用于个人学习与使用**。它在完整继承上游功能的基础上，移除了 Firebase
依赖，并围绕本地知识库、学习、子代理等场景新增了一批能力，详见下文。

## 功能特性（继承自上游）

- **MCP 支持**：支持AI创建/编辑/删除/测试MCP
- **Markdown 渲染**：参考obsidian，提供更多markdown渲染
- **联网搜索**：Exa、Tavily、Zhipu、Brave、Perplexity 等
- **Workspace**：基于 proot 的 Linux 智能体运行环境，优化了工具说明和沙箱环境感知

## 本分支新增功能

### RAG本地知识库

导入 PDF、Word、Excel 等文档，在本地建立可供 AI 检索的知识库。对话中 AI 可以直接引用你的资料
回答问题。需配置向量模型和重排序模型（可选）

### 信任文件夹

读取操作手机上的指定文件夹，可搭配obsidian本地笔记使用

### 学习助手

内置英语、数学、政治、机械原理四大学科的专业导师，各自配备针对性的系统提示词与工具，并配套知识
卡片、错题本、词汇、笔记四个管理面板，支持 Markdown 与数学公式渲染

### 多代理并行

复杂任务可拆解给多个子代理并行执行，每个子代理独立调用工具、互不阻塞

### 学习面板（TTS）

目前支持生词面板，错题本，笔记及知识点功能，并为四个预设导师助手配置相关工具，位于聊天界面右侧边栏

### 聊天界面改版

底部支持显示对话费用，缓存命中率，信任文件夹及工作区绑定目录，点击即可跳转或设置

### 记忆增强

记忆条目带时间戳元数据，并支持全文检索，AI 能更准确地回顾与定位历史上下文。

### 提示词优化功能

将草稿提示词一键优化为结构化、分级编排的专业提示词，结果以统一标签包裹，便于复制复用。入口在输入框发送按钮左侧

### todolist任务计划

AI 在对话中自动识别复杂任务并拆解生成待办清单

### 其他

设置页面布局优化重构，新增全局触感反馈，优化系统内置提示词及工具说明，工作区交互逻辑优化等

## 与原项目的差异

- 移除 Firebase（Analytics / Crashlytics），编译无需 `google-services.json`
- 自定义包名 `com.inonvation.rikkahub`（debug / release 变体均带 `.debug` 后缀）
- 自定义签名密钥，配置见 `local.properties`
- 默认仅构建 arm64-v8a ABI，可通过 `-PallAbis` 构建全部 ABI


## 构建与安装

本分支已移除 Firebase，构建无需 `google-services.json`。

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## 声明

本项目 fork 自 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)，为个人学习与使用而
维护，并非官方发布。项目遵循原项目的 [AGPL-3.0](LICENSE) 开源协议，尊重原作者及所有上游贡献
者。如本仓库内容涉及任何侵权问题，请通过 GitHub Issue 联系维护者，将在核实后删除相关内容。

## 许可证

本项目基于 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 开发，遵循
[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。
