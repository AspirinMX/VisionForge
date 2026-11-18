# VisionForge（幻影工坊）

**面向持久化编排与全链路工程可靠性的 AI 多模态影视、图像与视频创作工作台。**

[English](README.md) · [文档中心](docs/README.md) · [贡献指南](CONTRIBUTING.md)

---

## 项目简介

VisionForge 将多轮工具调用 AI Agent 与交互式分镜成片工作区、独立运维管理后台结合为一体，核心工程特性包括：

- **Temporal 持久化编排**：通过 `AgentRunWorkflow`、`MediaGenerationWorkflow` 与 `VideoCompositionWorkflow` 将付费模型调用、异步轮询、产物归档、FFmpeg 成片与额度结算彻底脱离 HTTP 请求生命周期。
- **以会话为隔离边界的创作工作区**：每个会话独占脚本、分镜、提示词、参考图与生成/合成资产，不存在跨用户或工作空间越权风险（访问他人资源统一返回 `404`）。
- **实时 SSE 推流与可恢复思考过程**：基于 Redis Pub/Sub 桥接实时推送推理原文/摘要、工具调用状态（`RUNNING` / `WAITING` / `SUCCEEDED` / `FAILED`）和正文增量，同时全量落库以支持页面刷新无缝恢复。
- **结构化分镜到成片流水线**：内置 `schemaVersion: 1` 分镜校验器、镜头首帧与候选视频回写、`ffprobe` 源片区间校验、FFmpeg 剪辑拼接以及 S3 分片上传发布。
- **Animate UI 克制极简工作台 + 独立管理后台**：前端采用 Next.js 16 App Router 与 **Animate UI**（`@animate-ui/react` + Base UI + `motion`），提供悬浮胶囊输入框与可折叠右侧画布，并将模型/用户运维隔离在独立的 `(admin)` `/admin/*` 管理端中。

---

## 总体架构

```text
浏览器 (Next.js 16 + Animate UI)
   │  /api/v1/*（经由 frontend/src/app/api/v1/[...path]/route.ts 同源运行期代理）
   ▼
Spring Boot API（端口 8080，api 模块）
   ├── 认证鉴权 / JWT 与管理员守卫（identity）
   ├── S3 预签名凭证与会话文件服务（asset）
   ├── 额度账户与流水查询（billing）
   ├── SSE 事件桥（Redis Pub/Sub -> SseEmitter）
   └── 事务持久化 WorkflowStartRequest + Temporal 启动 ─► Temporal Server（端口 7233）
                                                              │
Spring Boot Temporal Worker（worker 模块）◄────────────────────┘
   ├── AgentRunWorkflow（OpenAI Responses API + 17 个工具 + 上下文压缩）
   ├── MediaGenerationWorkflow（OpenAI Images、火山方舟、阿里百炼适配器）
   ├── VideoCompositionWorkflow（FFmpeg + ffprobe + S3 分片上传）
   └── AgentRunStartCompensator（定时补偿启动失败的 Workflow）
         │
         ├──► PostgreSQL 18（Flyway V1–V15，应用侧 UUIDv7 主键）
         ├──► Redis 7（SSE 实时事件通道）
         └──► RustFS / S3 兼容对象存储（二进制媒体、预签名直传/下载）
```

---

## 仓库目录结构

| 路径 | 职责说明 |
| --- | --- |
| `common/` | 跨模块契约（`ApiResponse`、`PageResult`、`ErrorCode`、`BizAssert`）、`UuidV7Generator`、`TraceContext`、`LogMasker`、`@BizLog` 审计基础 |
| `identity/` | 用户、系统角色（`ADMIN`、`USER`）、bcrypt 认证、JWT 与 Refresh Token 哈希轮换 |
| `asset/` | 会话文本版本文件（`conversation_file`）与二进制对象存储生命周期（`asset`、`upload_session`） |
| `billing/` | 额度账户、幂等预占/结算/释放（`credit_reservation`）与 Keyset 分页流水（`credit_ledger`） |
| `agent/` | 模型配置与 AES-GCM 密钥加解密、会话与轮次运行时、上下文压缩、分镜校验、模型/媒体协议适配器及 Temporal 工作流与活动 |
| `api/` | Spring Boot HTTP / SSE 接口层、Security 过滤链、全局异常处理与 DTO 映射 |
| `worker/` | 非 Web Spring Boot Temporal Worker 宿主、并发与轮询调优及启动补偿任务 |
| `database/` | 独立于应用启动之外的 Flyway 数据库迁移脚本（`V1`–`V15`） |
| `frontend/` | Next.js 16 App Router 前端应用（`(auth)`、`(studio)` 与独立 `(admin)` 路由组），基于 React 19、Tailwind CSS v4 与 Animate UI |
| `scripts/` | 本地工具脚本，含 `mock_media_server.py`（免付费模拟 OpenAI / 方舟 / 百炼媒体接口） |
| `docs/` | 企业级技术文档体系（架构、设计细节、API 契约、前后端阿里巴巴级开发规范与部署指南） |

---

## 核心技术栈

| 层次 | 关键技术与仓库基线版本 |
| --- | --- |
| **后端运行时** | Java `21`、Spring Boot `4.1.2`、Maven Wrapper（版本由根 `pom.xml` 统一托管） |
| **工作流引擎** | Temporal Java SDK `1.41.1`、Temporal Server `temporalio/auto-setup:1.29.5` |
| **数据与迁移** | PostgreSQL `18`、Flyway `12.4.0`、MyBatis-Plus `3.5.18.1`（`mybatis-plus-spring-boot4-starter`）、`hutool-core:5.8.48` |
| **消息与媒体基础设施** | Redis `7`、RustFS / AWS SDK S3 `2.59.2`、FFmpeg / `ffprobe` |
| **模型与媒体 SDK** | OpenAI Java SDK `4.99.0`（Responses API 流式 + Images）、火山方舟 `ark-runtime:0.17.0`、阿里百炼 HTTP 适配器 |
| **前端** | Next.js `16.4.6`（Turbopack、`cacheComponents`）、React `19.3.0`、TypeScript `5`、Tailwind CSS `4.3.3`、Animate UI（`@animate-ui/react:2.4.6`）、`motion:14.14.0`、`@base-ui/react` |

---

## 快速启动

### 1. 环境准备

- JDK `21`（仓库自带 Maven Wrapper `./mvnw`，无需本地安装 Maven）
- Node.js 与 `pnpm`（`12.21.0`）
- Docker 与 Docker Compose
- 如需本地跑通视频合成或视频 Mock，需在系统 `PATH` 中安装 `ffmpeg` 与 `ffprobe`

### 2. 启动开发基础服务并执行数据库迁移

```sh
cp .env.example .env
docker compose up -d --wait postgres redis rustfs temporal temporal-ui
docker compose up rustfs-init
./mvnw -pl database flyway:migrate
```

- PostgreSQL：`localhost:5432`（默认库/用户/密码均为 `visionforge`）
- Redis：`localhost:6379`
- RustFS S3 与控制台：`localhost:9000` / `localhost:9001`（`rustfsadmin` / `rustfsadmin`）
- Temporal gRPC 与 UI：`localhost:7233` / [http://localhost:8233](http://localhost:8233)

### 3. 注入必要密钥并启动后端服务

生成 32 字节 Base64 密钥（严禁将真实 `.env` 提交至仓库）：

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
# 可选：首次启动引导管理员账号（密码至少 12 位）
export BOOTSTRAP_ADMIN_EMAIL="admin@example.com"
export BOOTSTRAP_ADMIN_PASSWORD="change-this-strong-password"
```

在仓库根目录分别启动 API 与 Worker：

```sh
./mvnw -pl api spring-boot:run
./mvnw -pl worker spring-boot:run
```

### 4. 启动前端应用

```sh
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

访问 [http://localhost:3000](http://localhost:3000)。浏览器的 `/api/v1/*` 请求统一经由 `frontend/src/app/api/v1/[...path]/route.ts` 反向代理至后端（运行期通过 `VISIONFORGE_API_BASE_URL` 切换目标地址）。

> [!NOTE]
> **本地免付费媒体 Mock**：联调图片/视频生成与成片流程时，可运行 `python scripts/mock_media_server.py --port 8000`，在 Worker 中配置 `MEDIA_ALLOW_HTTP=true`，并在 `/admin/models` 中将模型端点指向本地 Mock。**生产环境严禁开启 `MEDIA_ALLOW_HTTP`**。

### 5. 常用验证与全栈容器部署

```sh
# 后端单元测试
./mvnw -B test

# 前端代码规范、类型检查与生产构建
pnpm --dir frontend lint
pnpm --dir frontend build

# 完整容器化生产形态栈（含一次性 Flyway migrate 服务）
docker compose -f docker-compose.prod.yaml up -d --build
```

---

## 技术文档导航

| 文档 | 内容摘要 |
| --- | --- |
| [docs/README.md](docs/README.md) | 文档总览与角色阅读指引 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 8 模块依赖规范、Temporal 工作流、Redis SSE 事件流、RustFS 对象存储与安全边界 |
| [docs/DESIGN_DETAILS.md](docs/DESIGN_DETAILS.md) | 数据库 Schema（`V1`–`V15`）、生成/计费状态机、上下文压缩、分镜 Schema 与前端设计细节 |
| [docs/API_REFERENCE.md](docs/API_REFERENCE.md) | 前端消费的全部 HTTP 与 SSE 接口契约、字段定义与全量错误码对照表 |
| [docs/BACKEND_DEV_STANDARDS.md](docs/BACKEND_DEV_STANDARDS.md) | 阿里巴巴风格 Java 后端开发规范（强制 / 推荐 / 禁止） |
| [docs/FRONTEND_DEV_STANDARDS.md](docs/FRONTEND_DEV_STANDARDS.md) | Next.js 16 + React 19 + Animate UI 前端开发规范（强制 / 推荐 / 禁止） |
| [docs/DEVELOPMENT_SETUP.md](docs/DEVELOPMENT_SETUP.md) | 详细本地搭建步骤、全量环境变量参考、Mock 工具与故障排查 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献流程与多行 Commit 提交规范 |
