<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub</h1>

  <p>原生 Android LLM 聊天客户端，支持在多个 AI 服务商之间自由切换。</p>

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)
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

- **多 AI Provider 支持**：自定义 API / URL / 模型，兼容 OpenAI、Google、Anthropic 接口
- **多模态输入**：图片、文本文档、PDF、Docx
- **MCP 支持**：Model Context Protocol
- **Markdown 渲染**：代码高亮、LaTeX 公式、表格、Mermaid 图表
- **消息分支**：分支对话与重新生成
- **联网搜索**：Exa、Tavily、Zhipu、Brave、Perplexity 等
- **Workspace**：基于 proot 的 Linux 智能体运行环境
- **提示词变量**：模型名、时间等；支持自定义 HTTP 请求头与请求体
- **智能体定制**：类 ChatGPT 记忆、AI 翻译
- **导入导出**：Provider 二维码导入导出、Silly Tavern 角色卡导入
- **Material You 设计**：支持深色模式

## 本分支新增功能

### 本地知识库

导入 PDF、Word、Excel 等文档，在本地建立可供 AI 检索的知识库。对话中 AI 可以直接引用你的资料
回答问题。

- 支持中文全文检索，文档内容变化后自动重新处理
- 扫描版 PDF 通过离线 OCR 识别文字，全程无需联网

### 学习助手

内置英语、数学、物理、政治四大学科的专业导师，各自配备针对性的系统提示词与工具，并配套知识
卡片、错题本、词汇、笔记四个管理面板，支持 Markdown 与数学公式渲染，覆盖从学习、练习到复习的
完整流程。

### 多代理并行

复杂任务可拆解给多个子代理并行执行，每个子代理独立调用工具、互不阻塞。需要用户决策时，通过
问答面板以确认题或选择题的形式交互；任务中断后可从原进度继续，结果确保送达。

### 语音朗读（TTS）

英文单词支持 TTS 朗读，可切换音色，并同步显示 IPA 音标辅助发音学习，适合背单词等场景。

### 费用统计

对话结束后按模型定价展示本次的 token 用量与估算费用，方便掌握成本。

### 记忆增强

记忆条目带时间戳元数据，并支持全文检索，AI 能更准确地回顾与定位历史上下文。

### 提示词优化

将草稿提示词一键优化为结构化、分级编排的专业提示词，结果以统一标签包裹，便于复制复用。

### 自动任务计划

AI 在对话中自动识别用户意图并生成待办清单，任务持久化保存，可在合适时机被动提醒。

### 多 API Key

同一服务商可配置多个 API key 自动轮换，缓解单个 key 的配额限制。


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

启动应用：

```bash
adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.inonvation.rikkahub.debug
adb shell am start -n <package>/<activity>
```

## 声明

本项目 fork 自 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)，为个人学习与使用而
维护，并非官方发布。项目遵循原项目的 [AGPL-3.0](LICENSE) 开源协议，尊重原作者及所有上游贡献
者。如本仓库内容涉及任何侵权问题，请通过 GitHub Issue 联系维护者，将在核实后删除相关内容。

## 许可证

本项目基于 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 开发，遵循
[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。
