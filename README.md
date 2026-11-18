# ihalo

ihalo 是一个 [Halo](https://www.halo.run) 插件，为文章编辑器提供 **AI 写作助手** 能力：用 AI 根据主题写文章、续写、润色、改写以及优化 Markdown 排版。

插件提供一个独立的 AI 编辑器（通过 Halo 的 `editor:create` 扩展点注册），后端通过自定义 API 代理到任意 **OpenAI 兼容（Chat Completions）** 的服务，例如 OpenAI、DeepSeek、Moonshot、通义千问、Ollama 等。

## ✨ 功能特性

- **AI 写文章**：输入主题，一键生成结构完整的 Markdown 文章。
- **续写**：顺着当前内容继续写，保持语气与风格。
- **润色**：保持原意，优化语句的流畅度与准确性。
- **改写**：更换表达方式重写内容。
- **优化排版**：调整 Markdown 层级、列表、加粗、引用等，让结构更清晰。
- 实时 Markdown 预览 + 图片粘贴上传。
- 支持自定义系统提示词、随机性（temperature）、最大输出 token 与请求超时。
- API Key 使用 Halo `Secret` 保存，不落明文；出站请求带 SSRF 防护与超时/体积限制。

## 🧰 技术栈

- 后端：Java 21 + Spring WebFlux（响应式）
- 前端：Vue 3 + TypeScript + Vite
- 构建：Gradle（Halo 官方插件 Gradle 插件）

## 📦 快速开始（开发）

前置条件：

- Java 21+
- Node.js >= 22.12.0
- pnpm
- 已运行的 Halo 实例（>= 2.25.0），推荐使用 Halo DevTools

### 开发运行

```bash
# 启动 Halo 并加载当前插件（推荐，需 Docker）
./gradlew.bat haloServer

# 或单独编译/打包
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

1. 在 Halo 控制台（Console）的插件列表中找到 `ihalo AI 写作助手`，进入 **插件设置**。
2. 填写：
   - **服务地址 / Base URL**：例如 `https://api.openai.com/v1`（无需带 `/chat/completions`）。
   - **模型名称**：例如 `gpt-4o-mini`、`deepseek-chat`、`qwen-plus`。
   - **API Key**：通过 `secret` 组件保存，不会以明文存储。
   - 可选：随机性、最大 token、自定义系统提示词、超时。
3. 回到文章编辑，在新建/编辑文章时选择 **ihalo AI 编辑器**，即可使用 AI 工具。

## 🔌 运行原理

- 前端通过 `editor:create` 扩展点注册编辑器 `ihalo-ai`，`rawType` 为 `markdown`。
- 编辑器将用户的操作请求发送到插件的自定义 API：`POST /apis/console.api.ihalo.run/v1alpha1/ai`。
- 后端 `AiService` 读取插件配置（含从 Secret 解析的 API Key），校验目标地址（SSRF 防护）后，使用响应式 `WebClient` 调用 OpenAI 兼容接口，并把结果返回给前端。

## 📄 目录结构

```text
├── src/main/java/run/ihalo/ihalo   # 后端
│   ├── IhaloPlugin.java            # 插件生命周期入口
│   ├── AiEndpoint.java             # 自定义 API（POST /ai, GET /ai/config）
│   ├── AiService.java              # 调用 LLM 服务
│   ├── AiSetting.java              # 插件配置映射
│   ├── AiRequest.java              # 请求体
│   ├── AiException.java            # 上游错误异常
│   └── SsrfGuard.java              # 出站地址安全检查
├── src/main/resources
│   ├── plugin.yaml                 # 插件描述
│   └── extensions
│       ├── settings.yaml           # 插件设置表单
│       └── role-template.yaml      # RBAC 角色模板
└── ui/src
    ├── index.ts                    # 插件前端入口（注册编辑器）
    ├── api/ai.ts                   # AI API 客户端
    └── components/IhaloEditor.vue  # AI 编辑器组件
```

## 🛠 安全说明

- API Key 只保存在 Halo `Secret` 中，插件设置仅保存 Secret 名称。
- 出站请求前会校验目标地址（仅允许 http/https、非内网/环回/CGNAT 地址），并设置连接与响应超时、限制响应体大小。
- 只有授予 `ihalo-role-ai` 角色的用户才能调用 AI API（超级管理员默认拥有全部权限）。

## 📄 许可证

[GPL-3.0](./LICENSE) © galaxy-eye
