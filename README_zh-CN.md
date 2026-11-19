# VisionForge（幻影工坊）

**面向持久化编排与全链路工程可靠性的 AI 多模态影视、图像与视频创作工作台。**

[English](README.md) · [文档中心](docs/README.md) · [贡献指南](CONTRIBUTING.md)

---

## 项目简介

VisionForge 将多轮工具调用 AI Agent 与交互式分镜成片工作区、独立运维管理后台结合为一体，核心工程特性包括：

- **Temporal 持久化编排**：通过 `AgentRunWorkflow`、`MediaGenerationWorkflow` 与 `VideoCompositionWorkflow` 将付费模型调用、异步轮询、产物归档、FFmpeg 成片与额度结算彻底脱离 HTTP 请求生命周期。
- **以会话为隔离边界的创作工作区**：每个会话独占脚本、分镜、提示词、参考图与生成/合成资产，不存在跨用户或工作空间越权风险（访问他人资源统一返回 `404`）。
- **实时 SSE 推流与可恢复思考过程**：基于 Redis Pub/Sub 桥接实时推送推理过程、工具执行状态与正文增量，同时全量持久化以支持刷新恢复。
- **结构化分镜到成片流水线**：支持结构化 JSON 分镜校验、镜头首帧与候选视频回写、`ffprobe` 源片区间校验、FFmpeg 剪辑拼接与 S3 分片上传发布。
- **Animate UI 工作台与独立管理后台**：前端基于 Next.js 16 App Router 与 **Animate UI**（`@animate-ui/react` + Base UI + `motion`），将创作者工作区 `(studio)` 与模型/用户管理后台 `(admin)` 彻底分离。

---

## 演示

![VisionForge 工作台：Agent 以参考人像生成 3 秒 480P 视频并交付到任务中心](assets/demo_1.png)

Agent 读取参考人像，生成 3 秒 480P 视频，并在会话的任务中心返回已交付的成片：

<video src="https://raw.githubusercontent.com/AspirinMX/VisionForge/main/assets/demo.mp4" controls muted width="100%"></video>

如果内联播放器不可用，可[直接打开生成的视频](assets/demo.mp4)。

---

## 总体架构

```text
浏览器 (Next.js 16 + Animate UI)
   │  /api/v1/*（经由 frontend/src/app/api/v1/[...path]/route.ts 同源运行期代理）
   ▼
Spring Boot API（:8080，api 模块）
   ├── 认证鉴权 / JWT 与管理员守卫（identity）
   ├── 会话文件与 S3 预签名凭证（asset）
   ├── 额度账户与流水查询（billing）
   ├── SSE 事件桥（Redis Pub/Sub -> SseEmitter）
   └── 事务持久化启动请求 + Temporal Client ────────► Temporal Server（:7233）
                                                              │
Spring Boot Temporal Worker（worker 模块）◄────────────────────┘
   ├── AgentRunWorkflow（Responses API + 工具集 + 上下文压缩）
   ├── MediaGenerationWorkflow（OpenAI Images、火山方舟、阿里百炼适配器）
   ├── VideoCompositionWorkflow（FFmpeg + ffprobe + S3 分片上传）
   └── AgentRunStartCompensator（定时补偿待启动的 Workflow 请求）
         │
         ├──► PostgreSQL（Flyway 管理架构）
         ├──► Redis（SSE 实时事件通道）
         └──► RustFS / S3 兼容对象存储（二进制媒体与成片输出）
```

---

## 仓库目录结构

| 路径 | 职责说明 |
| --- | --- |
| `common/` | 跨模块基础契约、UUIDv7 生成、错误模型、日志与业务审计基础设施 |
| `identity/` | 用户管理、bcrypt 认证、JWT 访问令牌与 Refresh Token 轮换 |
| `asset/` | 会话版本化文本文件、上传会话与 S3 对象生命周期 |
| `billing/` | 额度账户、预占/结算/释放与账本流水 |
| `agent/` | 模型配置、会话运行时、上下文压缩、分镜校验、模型/媒体适配器与 Temporal 工作流 |
| `api/` | Spring Boot HTTP / SSE 接口层、安全配置与 DTO 转换 |
| `worker/` | 非 Web Temporal Worker 宿主与工作流启动补偿任务 |
| `database/` | 独立执行的 Flyway 数据库迁移脚本 |
| `frontend/` | Next.js App Router 创作工作台与独立管理后台前端应用 |
| `scripts/` | 本地开发辅助脚本（含媒体 Mock 服务） |
| `assets/` | README 演示截图与生成的视频案例 |
| `docs/` | 架构、设计细节、API 契约、前后端工程规范与开发部署文档（总览见 [`docs/README.md`](docs/README.md)） |
| `pom.xml`、`mvnw`、`mvnw.cmd`、`.mvn/`、`lombok.config` | Maven 根工程、Wrapper 与 Java 编译工具链配置 |
| `Dockerfile.api`、`Dockerfile.worker`、`Dockerfile.frontend`、`docker-compose.yaml`、`docker-compose.prod.yaml`、`.dockerignore`、`.env.example` | 容器构建、Compose 编排与环境变量模板 |
| `AGENTS.md`、`CLAUDE.md`、`CONTRIBUTING.md`、`LICENSE` | 自动化代理指引、贡献指南与开源许可证 |
| `.gitignore`、`.idea/` | 版本控制忽略规则与共享 IDE 配置 |

---

## 核心技术栈

| 层次 | 仓库基线版本 |
| --- | --- |
| **后端运行时** | Java `21`、Spring Boot `4.1.2`、Maven Wrapper |
| **工作流引擎** | Temporal Java SDK `1.41.1`、Temporal Server（`temporalio/auto-setup:1.29.5`） |
| **数据与迁移** | PostgreSQL `18`、Flyway `12.4.0`、MyBatis-Plus `3.5.18.1`、`hutool-core:5.8.48` |
| **消息与媒体基础设施** | Redis `7`、RustFS / AWS SDK S3 `2.59.2`、FFmpeg / `ffprobe` |
| **模型与媒体 SDK** | OpenAI Java SDK `4.99.0`、火山方舟与阿里百炼 HTTP 适配器 |
| **前端** | Next.js `16.4.6`（Turbopack、`cacheComponents`）、React `19.3.0`、TypeScript `5`、Tailwind CSS `4.3.3`、Animate UI（`@animate-ui/react:2.4.6`）、`motion:14.14.0`、`@base-ui/react` |

---

## 快速启动

### 1. 环境准备

- JDK `21`（仓库自带 Maven Wrapper `./mvnw`）
- Node.js 与 `pnpm`（`12.21.0`）
- Docker 与 Docker Compose
- 如需运行视频合成或视频 Mock，需在 `PATH` 中安装 `ffmpeg` 与 `ffprobe`

### 2. 启动基础服务并执行数据库迁移

```sh
cp .env.example .env
docker compose up -d --wait postgres redis rustfs temporal temporal-ui
docker compose up rustfs-init
./mvnw -pl database flyway:migrate
```

- PostgreSQL：`localhost:5432`（`visionforge` / `visionforge`）
- Redis：`localhost:6379`
- RustFS S3 与控制台：`localhost:9000` / `localhost:9001`
- Temporal gRPC 与 UI：`localhost:7233` / [http://localhost:8233](http://localhost:8233)

### 3. 注入密钥并启动后端服务

生成 32 字节 Base64 密钥（严禁将 `.env` 提交至仓库）：

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
# 可选：引导首个管理员账号（密码 12-72 字符）
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

访问 [http://localhost:3000](http://localhost:3000)。浏览器 `/api/v1/*` 请求由 `frontend/src/app/api/v1/[...path]/route.ts` 反向代理至后端（通过运行期变量 `VISIONFORGE_API_BASE_URL` 配置）。

> [!NOTE]
> **本地媒体 Mock**：执行 `python scripts/mock_media_server.py --port 8000`（若 `ffmpeg` 不在 `PATH` 中，可传 `--ffmpeg-path <路径>`、设置 `FFMPEG_PATH`，或直接传 `--video-file <样片.mp4>`）并在 Worker 设置 `MEDIA_ALLOW_HTTP=true`，即可在本地免付费联调生图、生视频与成片。**生产环境严禁开启 `MEDIA_ALLOW_HTTP`**。

### 5. 验证与全栈容器部署

```sh
# 后端测试
./mvnw -B test

# 前端检查与构建
pnpm --dir frontend lint
pnpm --dir frontend build

# 完整容器化生产形态栈
docker compose -f docker-compose.prod.yaml up -d --build
```

---

## 文档与规范

- **项目技术文档中心**：[`docs/README.md`](docs/README.md)
- **贡献指南**：[`CONTRIBUTING.md`](CONTRIBUTING.md)
- **开源许可证**：[`LICENSE`](LICENSE)
