# 幻影工坊

图片与视频生成工作台。

[English](README.md)

## 仓库结构

| 路径 | 用途 |
| --- | --- |
| 根目录 `pom.xml` 及各模块目录 | Java 21 Maven 父工程及所有后端模块 |
| `frontend/` | 使用 TypeScript 和 Tailwind CSS 的 Next.js App Router 应用 |
| `Dockerfile`、`docker-compose.yaml`、`.dockerignore`、`.env.example` | 位于仓库根目录的部署配置 |
| `docs/` | 项目文档 |

## 前端开发

在仓库根目录执行：

```sh
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

开发服务监听 3000 端口，初始页面位于 `frontend/src/app/page.tsx`。

```sh
pnpm --dir frontend lint
pnpm --dir frontend build
pnpm --dir frontend start
```

## 后端开发

在 IDE 中将根目录的 `pom.xml` 导入为 Maven 工程。仓库自带 Maven Wrapper，无需本地安装 Maven：

```sh
./mvnw validate
```

依赖与插件版本由根 POM 统一管理（Java 21、Spring Boot 4.1.2 BOM、Temporal Java SDK 1.41.1 BOM）。API 与 Worker 已具备最小 Spring Boot 启动应用，Worker 可连接 Temporal 并注册自检 Workflow。

业务数据库迁移脚本位于 `database/src/main/resources/db/migration`，由 Flyway 管理。在仓库根目录启动 PostgreSQL 并执行迁移：

```sh
docker compose up -d --wait postgres
./mvnw -pl database flyway:migrate
```

默认连接 `jdbc:postgresql://localhost:5432/visionforge`，用户名和密码均为 `visionforge`。Flyway 可通过 `-Dvisionforge.db.url=... -Dvisionforge.db.user=... -Dvisionforge.db.password=...` 覆盖；应用使用 `VISIONFORGE_DB_URL`、`VISIONFORGE_DB_USER`、`VISIONFORGE_DB_PASSWORD` 环境变量。

### 认证与工作空间

API 提供 `/api/v1/auth/register`、`/api/v1/auth/login`、`/api/v1/auth/workspace`、`/api/v1/auth/switch-workspace`、`/api/v1/auth/refresh`、`/api/v1/auth/logout`、`/api/v1/auth/me`。Access Token 为绑定单个工作空间的短有效期 JWT（包含 `wsid`、`ws_role`、`platform_admin`）；Refresh Token 为用户级会话，轮换并以哈希形式存储。

只有一个工作空间的用户登录后直接获得 TokenPair；有多个工作空间的用户会获得短有效期 selectionToken，通过 `/api/v1/auth/workspace` 选择空间。切换空间通过 `/api/v1/auth/switch-workspace` 换取新的 Access Token，刷新时需携带当前 `workspaceId`。注册成功会自动创建个人工作空间。

工作空间管理接口位于 `/api/v1/workspaces`、`/api/v1/workspaces/{id}`、`/members` 和 `/transfer`。所有带工作空间路径的请求，其 Access Token 的 `wsid` 必须与路径一致。系统角色为 `ADMIN` 和 `USER`；空间角色为 `OWNER`、`ADMIN`、`MEMBER`。平台管理员可切换进入任意工作空间，工作空间操作会写入审计日志。

启动 API 前必须设置 `JWT_SECRET`（Base64 编码，至少 32 字节）：

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
```

如需初始化首个管理员，再设置 `BOOTSTRAP_ADMIN_EMAIL` 和 `BOOTSTRAP_ADMIN_PASSWORD`（至少 12 个字符）。账号不存在时会创建，已存在时会提升为 `ADMIN`。Worker 使用相同的数据库配置，需在 PostgreSQL 可用后启动。

### 文本模型配置与对话

API 和 Worker 都必须设置 `MODEL_CONFIG_SECRET`（Base64，恰好 32 字节），用于加密存储模型 API Key：

```sh
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
```

系统管理员通过 `/api/v1/admin/models` 配置 OpenAI 兼容网关：

```sh
curl -X POST http://localhost:8080/api/v1/admin/models \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "displayName": "主文本模型",
    "baseUrl": "https://gateway.example.com/v1",
    "apiKey": "sk-...",
    "modelId": "gpt-6-astra",
    "reasoningEffort": "MEDIUM",
    "maxInputTokens": 32000,
    "maxOutputTokens": 4096,
    "defaultModel": true
  }'
```

API Key 不会通过接口返回，响应中只有 `apiKeyConfigured`。模型调用请求发送到 `{baseUrl}/chat/completions`，使用 `max_tokens`，配置了 reasoning effort 时同时发送 `reasoning_effort`。AgentRun 由 Worker 执行，Worker 必须保持运行。

会话接口：

```text
POST /api/v1/workspaces/{workspaceId}/conversations
GET  /api/v1/workspaces/{workspaceId}/conversations
GET  /api/v1/workspaces/{workspaceId}/conversations/{conversationId}/messages
POST /api/v1/workspaces/{workspaceId}/conversations/{conversationId}/messages
GET  /api/v1/workspaces/{workspaceId}/agent-runs/{runId}
```

发送消息返回 `202 Accepted` 和 `runId`；助手回复由 Workflow 异步写入，客户端轮询 AgentRun，直到状态为 `COMPLETED` 或 `FAILED`。如果 Temporal 暂时不可用，AgentRun 会保持 `PENDING`，由 Worker 的启动补偿任务重试启动 Workflow。

Worker 运行时配置位于 `visionforge.worker`，可通过 `WORKER_MAX_CONCURRENT_ACTIVITIES`、`WORKER_MAX_CONCURRENT_WORKFLOW_TASKS`、`WORKER_WORKFLOW_CACHE_SIZE`、`WORKER_MAX_WORKFLOW_THREADS`、`WORKER_GRACEFUL_SHUTDOWN_TIMEOUT` 等环境变量覆盖。启动补偿由 `WORKER_START_COMPENSATION_ENABLED`、`WORKER_START_COMPENSATION_INTERVAL_MS`、`WORKER_START_COMPENSATION_BATCH_SIZE`、`WORKER_START_COMPENSATION_MAX_ATTEMPTS` 控制。

## 部署

部署文件位于仓库根目录。Dockerfile 和 Compose 文件仍为占位配置，可运行的服务定义将在后续添加。
