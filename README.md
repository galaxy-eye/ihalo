# ihalo

ihalo 是一个 [Halo](https://www.halo.run) 插件，为文章编辑器提供 **AI 写作助手** 能力：用 AI 根据主题写文章、续写、润色、改写以及优化 Markdown 排版。

插件基于 Halo 官方 **AI Foundation** 插件（[plugin-ai-foundation](https://github.com/halo-dev/plugin-ai-foundation)）集成语言模型：ihALO 只负责「编辑器编排 + 各模式提示词 + 结果回填」，真正的模型调用、Provider / API Key 管理、重试与出站安全都由 AI Foundation 完成，因此 ihALO 不再自建上游连接，也不再需要自己保存任何 API Key。

## ✨ 功能特性

- **AI 写文章**：输入主题，一键生成结构完整的 Markdown 文章。
- **续写**：顺着当前内容继续写，保持语气与风格。
- **润色**：保持原意，优化语句的流畅度与准确性。
- **改写**：更换表达方式重写内容。
- **优化排版**：调整 Markdown 层级、列表、加粗、引用等，让结构更清晰。
- 实时 Markdown 预览 + 图片粘贴上传。
- 支持选择语言模型（AI Foundation 的 `aiModelSelector`）、随机性（temperature）、最大输出 token 与自定义系统提示词。

## 🧰 技术栈

- 后端：Java 21 + Spring WebFlux（响应式）
- 依赖：`run.halo.aifoundation:api`（AI Foundation Java SDK）
- 前端：Vue 3 + TypeScript + Vite

## 📦 依赖与安装顺序

> ihALO 依赖 **plugin-ai-foundation**。请在安装/启用 ihALO 之前先安装并启用 AI Foundation 插件，并在其控制台里至少配置一个语言模型（Provider 与 API Key）。

- Java 21+
- Node.js >= 22.12.0
- Halo >= 2.25.0
- AI Foundation >= 1.0.0（`pluginDependencies: ai-foundation >=1.0.0 & <2.0.0`）

## 开发运行

```bash
# 启动 Halo 并加载当前插件（推荐，需 Docker）
./gradlew.bat haloServer

# 或编译/打包
./gradlew.bat build
```

构建完成后在 `build/libs` 目录得到插件 jar。

### 前端热更新（可选）

```bash
cd ui
pnpm install
pnpm dev
```

## ⚙️ 配置

1. 在 Halo 控制台安装并启用 **AI Foundation** 插件，配置至少一个 Provider 与语言模型。
2. 在插件列表中找到 `ihalo AI 写作助手`，进入 **插件设置**：
   - **语言模型**：通过 `aiModelSelector` 选择已配置的语言模型（必填）。
   - 可选：随机性、最大输出 token、自定义系统提示词。
3. 回到文章编辑，在新建/编辑文章时选择 **ihalo AI 编辑器**，即可使用 AI 工具。

## 🔌 运行原理

- 前端通过 `editor:create` 扩展点注册编辑器 `ihalo-ai`，`rawType` 为 `markdown`。
- 编辑器将用户的操作请求发送到插件的自定义 API：`POST /apis/console.api.ihalo.run/v1alpha1/ai`。
- 后端 `AiService` 通过 `ExtensionGetter.getEnabledExtension(AiModelService.class)` 拿到 AI Foundation 的 `AiModelService`，再调用 `languageModel(modelName).generateText(...)` 生成内容并返回给前端。

## 📄 目录结构

```text
├── src/main/java/run/ihalo/ihalo   # 后端
│   ├── IhaloPlugin.java            # 插件生命周期入口
│   ├── AiEndpoint.java             # 自定义 API（POST /ai, GET /ai/config）
│   ├── AiService.java              # 通过 AI Foundation SDK 编排模型调用
│   ├── AiSetting.java              # 插件配置映射（modelName / temperature / maxTokens / systemPrompt）
│   ├── AiRequest.java              # 请求体
│   └── AiException.java            # 上游错误异常
├── src/main/resources
│   ├── plugin.yaml                 # 插件描述（声明依赖 ai-foundation）
│   └── extensions
│       ├── settings.yaml           # 插件设置表单（含 aiModelSelector）
│       └── role-template.yaml      # RBAC 角色模板
└── ui/src
    ├── index.ts                    # 插件前端入口（注册编辑器）
    ├── api/ai.ts                   # AI API 客户端
    └── components/IhaloEditor.vue  # AI 编辑器组件
```

## 🛠 安全说明

- ihALO 不再保存或直接调用任何第三方 API Key；凭据、Provider 路由与出站请求安全由 AI Foundation 统一管理。
- ihALO 仅调用 AI Foundation 插件暴露的受控 SDK（语言模型生成），不新增网络出口。
- 只有授予 `ihalo-role-ai` 角色的用户才能调用 AI 接口（超级管理员默认拥有全部权限）。

## 📄 许可证

[GPL-3.0](./LICENSE) © galaxy-eye
