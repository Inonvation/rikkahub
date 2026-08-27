# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程，便于快速上手并保持一致的协作质量。

## 关键事实：这是个人定制 fork

本仓库是 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 的定制分支，由 **Inonvation** 维护，仅限个人使用：

- **Firebase 已移除**（Analytics / Crashlytics），构建**不需要** `google-services.json`
- 自定义包名 `com.inonvation.rikkahub`，自定义签名 `keystore.jks`
- `origin` → `https://github.com/Inonvation/rikkahub.git`（日常推送）
- `upstream` → `https://github.com/rikkahub/rikkahub.git`（原项目）
- **永不向 upstream 推送或提 PR**；拉上游用 `git fetch upstream && git merge upstream/master`（上游默认分支为 `master`）

更多定制细节见 `CLAUDE.md`。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle（`compileSdk=37` / `minSdk=26` / Java 17）：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

## 设备安装规则

- **禁止通过 adb 删除手机上的任何软件**（包括 `adb uninstall`、`pm uninstall` 以及 Gradle 设备测试流程中的自动卸载）。卸载会清除应用数据，可能造成用户数据丢失。
- 安装或更新手机上的应用**只允许覆盖安装**：`adb install -r <apk>`，或使用不会卸载的安装任务。
- 默认不要运行会先卸载再安装的设备测试任务（如 `connectedDebugAndroidTest`），除非用户明确要求，且用户已确认应用数据可以清除。

构建/安装要点（与上游不同，容易猜错）：

- **入口 Activity 是 `RouteActivity`**（MAIN/LAUNCHER），项目里**没有** `MainActivity`
- `debug` 与 `release` 变体都带 `applicationIdSuffix = ".debug"`，实际包名 `com.inonvation.rikkahub.debug`；`release` 变体用 debug 签名（`signingConfigs.getByName("debug")`）
- **ABI 拆分包**：默认只打 `arm64-v8a`；加 `-PallAbis` 打全 ABI。APK 输出在 `app/build/outputs/apk/{debug|release}/app-arm64-v8a-{debug|release}.apk`
- `web` 模块 `preBuild` 会执行 `pnpm run build` 构建 `web-ui/` 并复制静态资源，**需要本地可用 `pnpm`**（Windows 走 `cmd /c pnpm run build`）；该任务只 build 不 install，需先 `pnpm install --frozen-lockfile`
- `material3/material-color-utilities` 是 git 子模块，clone 需 `--recursive`
- Room schema 由 KSP 导出到 `app/schemas`，`androidTest` 用它做 migration 测试
- 签名配置在 `local.properties`（已 gitignore）；CI（`.github/workflows/daily-build.yml`）通过 secrets 解码 keystore 并写入

## Windows 受限环境构建失败排查

在受限权限环境（如 DSH 沙箱）下，Gradle wrapper 首次运行需要在用户目录创建发行版缓存，可能直接失败：

- **现象**：`gradlew.bat` 启动即退出，报 `java.io.FileNotFoundException: C:\Users\<user>\.gradle\wrapper\dists\gradle-9.6.0-bin\<hash>\gradle-9.6.0-bin.zip.lck (拒绝访问)`，构建尚未开始。
- **原因**：wrapper 要写入 `%USERPROFILE%\.gradle` 下载并解压 Gradle 发行版，受限环境对该目录无写权限（工作区外的沙箱拦截）。
- **解决**：以完整访问权限运行构建（沙箱一次性权限提升后重试同一命令）；或先以完整权限预热 wrapper（如执行一次 `gradlew --version`），之后普通权限构建可复用已下载的发行版缓存。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`speech/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

`ai` 模块的 SSE 流式测试依赖 `ai/src/test/resources/stream-traces/` 下的 `events.jsonl`，由 `trace-cli`（bun）录制生成；新增 provider 流式场景需配套生成 trace + `expected.json` 并注册到 `StreamTraceReplayTest`。

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic（内含 `:app:baselineprofile` 子模块）
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **knowledge**: 知识库模块（Room 持久化 + `:ai`），namespace `me.rerere.knowledge`
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **videogen**: 异步视频生成供应商抽象层（阿里万相 / 火山 Seedance / MiniMax H3）。只做协议适配（create/query + 可取消轮询 Flow），不负责持久化任务、下载视频或 UI；供应商专属字段走 `extraParameters`，见 `videogen/README.md`
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.
- **build-logic**: 约定插件 `rikkahub.android.library` / `rikkahub.android.library.compose`，feature 模块统一 apply

仓库内还有两个独立工具（不在 Gradle 构建内）：

- **trace-cli**（bun）：录制真实 Provider 的 SSE 响应生成 `ai` 模块可回放的 `events.jsonl`
- **locale-tui**（uv）：Android `strings.xml` 翻译管理 TUI，`cd locale-tui && uv run python src/main.py`，改动字符串资源时用 `locale-tui-localization` skill

设计与实现计划文档集中在 `docs/`（如 `remote-sync-plan.md`、`settings-refactor-plan.md`、`knowledge-retrieval-optimization-plan.md` 等），改动对应领域前先查阅相关 plan。

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant, keeping a list of MessageNodes
  in a tree structure to support message branching, plus metadata like title, pin status, chat suggestions, optional
  conversation-level system prompt, and prompt injection bindings. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.), with a role (USER, ASSISTANT, SYSTEM, TOOL),
  token usage info, and streaming support through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching; each node keeps a list of
  alternative messages and tracks the selected one (selectIndex), enabling regeneration and branch switching.
  (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Common transformers include:
  TemplateTransformer (Pebble templates), ThinkTagTransformer (`<think>` → reasoning), RegexOutputTransformer,
  DocumentAsPromptTransformer, Base64ImageToLocalFileTransformer, OcrTransformer. Output transformers support
  `visualTransform()` for streaming UI and `onGenerationFinish()` for final processing.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
