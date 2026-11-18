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
docker compose up -d --wait postgres redis
./mvnw -pl database flyway:migrate
```

默认连接 `jdbc:postgresql://localhost:5432/visionforge`，用户名和密码均为 `visionforge`。Flyway 可通过 `-Dvisionforge.db.url=... -Dvisionforge.db.user=... -Dvisionforge.db.password=...` 覆盖；应用使用 `VISIONFORGE_DB_URL`、`VISIONFORGE_DB_USER`、`VISIONFORGE_DB_PASSWORD` 环境变量。

### 认证与数据隔离

API 提供 `/api/v1/auth/register`、`/api/v1/auth/login`、`/api/v1/auth/refresh`、`/api/v1/auth/logout`、`/api/v1/auth/me`。Access Token 为短有效期 JWT，claims 包含 `sub`、`email`、`role`；Refresh Token 为用户级会话，轮换并以哈希形式存储。登录直接签发 TokenPair，没有空间选择和切换。

系统不存在工作空间实体和成员关系。每个用户只能看到自己的会话和文件，访问他人资源返回 `404`。系统角色 `ADMIN` / `USER` 只用于 `/api/v1/admin/**` 运维接口，不授予访问他人内容的权限。

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

API Key 不会通过接口返回，响应中只有 `apiKeyConfigured`。模型调用通过 OpenAI 官方 Java SDK 以流式方式请求 `POST {baseUrl}/responses`（`responses().createStreaming()`），显式提交 `input` 数组，并使用 `store: false`、`max_output_tokens`，配置了 reasoning effort 时提交 `reasoning.effort`。不使用 Chat Completions。AgentRun 由 Worker 执行，Worker 必须保持运行。

会话接口：

```text
POST /api/v1/conversations
GET  /api/v1/conversations
GET  /api/v1/conversations/{conversationId}
GET  /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/messages/stream
GET  /api/v1/agent-runs/{runId}
```

发送消息返回 `202 Accepted` 和 `runId`；助手回复由 Workflow 异步写入，客户端轮询 AgentRun，直到状态为 `COMPLETED` 或 `FAILED`。如果 Temporal 暂时不可用，AgentRun 会保持 `PENDING`，由 Worker 的启动补偿任务重试启动 Workflow。

`/messages/stream` 返回 `text/event-stream`，需要 Redis（Compose 已提供），事件格式：

```text
event: accepted          data: {"type":"accepted","runId":"..."}
event: reasoning         data: {"type":"reasoning","content":"..."}
event: reasoning_summary data: {"type":"reasoning_summary","content":"..."}
event: delta             data: {"type":"delta","content":"..."}
event: done              data: {"type":"done","assistantMessageId":"..."}
event: error             data: {"type":"error","message":"..."}
```

浏览器应使用 `fetch` 的流式 reader 消费，因为 `EventSource` 不能携带 `Authorization` 头。该接口需要 Redis；非流式消息接口在 Redis 不可用时仍可使用。Redis pub/sub 只负责实时投递；连接中断后以已持久化的助手消息为准。

### 会话工作区

每个会话拥有自己的工作区。这次对话中创建的脚本、分镜、提示词、图片和视频都属于它，不与其他会话或用户共享。更新文件会创建不可变版本，并必须携带 `baseVersion` 以检测并发修改。

```text
GET   /api/v1/conversations/{conversationId}/files
POST  /api/v1/conversations/{conversationId}/files
GET   /api/v1/conversations/{conversationId}/files/search
GET   /api/v1/conversations/{conversationId}/files/{fileId}
PATCH /api/v1/conversations/{conversationId}/files/{fileId}
GET   /api/v1/conversations/{conversationId}/files/{fileId}/versions
GET   /api/v1/conversations/{conversationId}/files/{fileId}/versions/{version}
```

首版将文本类文件（`SCRIPT`、`STORYBOARD`、`PROMPT`、`OTHER`）存入 PostgreSQL，单条内容上限为 1 MB。V12 迁移创建 `conversation_file` 和 `conversation_file_version`。

模型可用的会话文件工具是 `list_files`、`search_files`、`read_file`、`create_file`、`update_file`、`list_file_versions`；会话和用户始终来自服务端执行上下文。媒体工具为 `generate_image`、`generate_video`、`get_generation`、`cancel_generation`、`inspect_asset`。

`POST /api/v1/conversations/{conversationId}/messages` 和 `/messages/stream` 支持可选的 `assetIds`（最多 8 个会话内图片）。主模型在本轮调用中会把这些图片作为 `input_image` 看到；历史消息默认只回放文本。

Worker 运行时配置位于 `visionforge.worker`，可通过 `WORKER_MAX_CONCURRENT_ACTIVITIES`、`WORKER_MAX_CONCURRENT_WORKFLOW_TASKS`、`WORKER_WORKFLOW_CACHE_SIZE`、`WORKER_MAX_WORKFLOW_THREADS`、`WORKER_GRACEFUL_SHUTDOWN_TIMEOUT` 等环境变量覆盖。启动补偿由 `WORKER_START_COMPENSATION_ENABLED`、`WORKER_START_COMPENSATION_INTERVAL_MS`、`WORKER_START_COMPENSATION_BATCH_SIZE`、`WORKER_START_COMPENSATION_MAX_ATTEMPTS` 控制。

### 对象存储

会话中的文本文件保存在 PostgreSQL；图片、视频等二进制走 S3 兼容对象存储，本地默认使用 RustFS：

```sh
docker compose up -d --wait rustfs
docker compose up rustfs-init
```

API 和 Worker 读取 `STORAGE_BACKEND`、`STORAGE_ENDPOINT`、`STORAGE_PUBLIC_ENDPOINT`、`STORAGE_REGION`、`STORAGE_BUCKET`、`STORAGE_ACCESS_KEY`、`STORAGE_SECRET_KEY`、`STORAGE_PATH_STYLE`、`STORAGE_PRESIGN_TTL`、`STORAGE_UPLOAD_SESSION_TTL`、`STORAGE_MAX_UPLOAD_BYTES`。预签名 URL 按 `STORAGE_PUBLIC_ENDPOINT` 签名，该地址必须能被浏览器访问；服务端读写仍使用 `STORAGE_ENDPOINT`。V13 创建 `asset`、`asset_variant` 和 `upload_session`；适配器只使用 RustFS 已测试的 S3 子集（对象 CRUD、预签名 GET/PUT、Range 和分片上传）。

上传流程：

```text
POST /api/v1/conversations/{conversationId}/uploads
  -> 返回预签名 PUT URL 和必需请求头
PUT  <预签名 URL>，Content-Type 必须与申请时一致
POST /api/v1/conversations/{conversationId}/uploads/{uploadSessionId}/complete
  -> 校验大小和类型，将 asset 标记为 READY
GET  /api/v1/assets/{assetId}/download-url
  -> 返回短期预签名 GET URL
```

### 媒体生成任务

V14 创建 `generation_task`、`generation_attempt`、`generation_input`、`generation_output`、`message_part` 和额度账本；V15 增加 `shotId`/分镜关联、`WAITING` 工具状态和 `composition_task`/`composition_input`。模型配置增加 `capabilityType`（`CHAT`/`IMAGE`/`VIDEO`）、`protocol`、`executionMode` 和 `priceUnits`；默认模型按能力类型分别唯一。

```text
POST /api/v1/generation-tasks                202 + Location，支持 Idempotency-Key
GET  /api/v1/generation-tasks?conversationId=
GET  /api/v1/generation-tasks/{taskId}
POST /api/v1/generation-tasks/{taskId}/cancel
POST /api/v1/generation-tasks/{taskId}/retry-archive

POST /api/v1/composition-tasks               202 + Location
GET  /api/v1/composition-tasks/{taskId}
POST /api/v1/composition-tasks/{taskId}/cancel
POST /api/v1/composition-tasks/{taskId}/retry
```

生成受理在单个事务内完成任务、输入冻结、输出占位、额度预占和启动请求；`MediaGenerationWorkflow` 独立于 AgentRun，负责提交、等待、归档、发布与结算。归档失败只允许 `retry-archive`，不会重新生成或重复扣费。

生成任务可携带 `shotId`、`storyboardFileId` 和 `storyboardVersion`。首帧图完成后写回分镜的 `firstFrame`，视频完成后追加 `videoCandidates` 并默认选中第一个候选。`STORYBOARD` 文件在创建、更新和 `validate_storyboard` 工具中都会校验 schema。

依赖产物时调用 `await_generation`：当前 AgentRun 持久化等待任务终态，完成后把结果交回同一模型。`compose_video` 按分镜顺序冻结 `selectedVideo`、资产和 `usedRange`，由 `VideoCompositionWorkflow` 从 S3 读取片段、FFmpeg 裁剪拼接、multipart 流式写回并发布成片。Worker 需要可执行的 FFmpeg；可通过 `FFMPEG_PATH`、`COMPOSITION_PART_SIZE_BYTES`、`COMPOSITION_TIMEOUT` 配置。

媒体协议当前注册：

- `STUB`：图片为真实 PNG，视频为占位字节，**不可播放**。
- `OPENAI_IMAGES`：GPT Image 文生图和参考图编辑，使用现有 `openai-java`。
- `ARK_VIDEO_TASKS`：Seedance 提交、查询和视频下载，使用 `ark-runtime`。

OpenAI Images 和 Ark Seedance 适配器已实现，但**尚未进行真实网关联调**；Ark 图片、DashScope/万相、ffprobe/封面/转码和前端生成入口仍未实现。密码/密钥、真实计费和播放器兼容性仍需单独验证。额度账户首次使用时按 `BILLING_INITIAL_CREDIT_UNITS`（默认 10000）初始化。

## 部署

部署文件位于仓库根目录。Dockerfile 和 Compose 文件仍为占位配置，可运行的服务定义将在后续添加。
