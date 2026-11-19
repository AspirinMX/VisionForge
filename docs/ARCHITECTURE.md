# VisionForge 系统架构文档

本文档描述 VisionForge 的模块化单体架构、模块依赖边界、Temporal 持久化工作流编排、Redis 实时事件桥、双存储管线以及安全隔离边界，面向架构设计评审、跨模块开发与线上排障。

---

## 1. 系统总体拓扑

VisionForge 采用 **Web API 与 Temporal Worker 进程分离** 的运行形态，浏览器通过 Next.js 同源运行期代理访问后端：

```text
+-----------------------------------------------------------------------+
| 浏览器 (Next.js 16 App Router + Animate UI)                           |
| - /(auth)  : 登录与注册                                               |
| - /(studio): 创作工作台、会话工作区、全局任务中心、账户与额度           |
| - /(admin) : 独立系统管理控制台 (/admin, /models, /configs, /alerts,     |
|              /users, /billing, /audit-logs)                           |
+-----------------------------------+-----------------------------------+
                                    | 同源 /api/v1/* + 流式响应透传
                                    v
+-----------------------------------------------------------------------+
| Next.js Runtime Proxy (frontend/src/app/api/v1/[...path]/route.ts)    |
| - 清除 hop-by-hop 头与 accept-encoding，按 VISIONFORGE_API_BASE_URL 转发 |
+-----------------------------------+-----------------------------------+
                                    | HTTP/1.1 + SSE
                                    v
+-----------------------------------+-----------------------------------+
| vision-forge-api (api 模块, 默认端口 :8080)                             |
| - Stateless JWT Resource Server (HS256, /api/v1/admin/** -> ROLE_ADMIN)|
| - GlobalExceptionHandler 统一包装 ApiResponse                          |
| - 控制面：认证、模型目录、会话/文件、直传凭证、任务受理、额度与账本     |
| - AgentRunStreamService：Redis Pub/Sub -> SseEmitter 实时桥接          |
+-------+-----------------------+-----------------------+---------------+
        |                       |                       |
        | 事务写入 + 启动        | 订阅频道               | Presign / HeadObject
        v                       v                       v
+---------------+       +-------------+       +-------------------------+
| PostgreSQL 18 |       |   Redis 7   |       | RustFS / S3 Compatible  |
| Flyway 管理表  |       | Pub/Sub SSE |       | 原始上传 / 生成 / 成片  |
+-------^-------+       +------^------+       +------------^------------+
        |                      |                           |
        | 读取上下文 / 落库     | 发布流式事件               | 下载 / 流式分片上传
+-------+----------------------+---------------------------+------------+
| vision-forge-worker (worker 模块, WebApplicationType.NONE)             |
| - 连接 Temporal Server (:7233, queue: vision-forge)                    |
| - 注册 Workflow: SelfCheckWorkflowImpl, AgentRunWorkflowImpl,          |
|                  MediaGenerationWorkflowImpl, VideoCompositionWorkflowImpl|
| - AgentRunStartCompensator: 定期补偿 PENDING 状态的 WorkflowStartRequest|
| - 外部集成：OpenAI Responses / Images, 火山方舟, 阿里百炼, FFmpeg       |
+-----------------------------------------------------------------------+
```

---

## 2. 后端 8 模块拓扑与边界约束

根工程 [`pom.xml`](../pom.xml) 统一管理 Java `21`、Spring Boot `4.1.2`、Temporal SDK `1.41.1`、MyBatis-Plus `3.5.18.1`、Flyway `12.4.0` 与外部 SDK 版本。实际 Maven 模块依赖为严格有向无环图：

```text
          api                      worker
       /       \                      |
      v         v                     v
  identity    agent <-----------------+
      \      /  |  \
       v    v   v   v
      common  asset  billing      database (独立 Flyway SQL 迁移，零代码依赖)
               \      /
                v    v
                common
```

| 模块 | Artifact ID | 核心职责 | 直接内部依赖 |
| --- | --- | --- | --- |
| `common` | `vision-forge-common` | `ApiResponse`、`PageResult`、`ErrorCode`、`BusinessException`、`SystemException`、`BizAssert`、`UuidV7Generator`、`TraceContext`、`LogMasker`、`CsvWriter`、`@BizLog` 切面及 PostgreSQL 动态配置缓存 `DynamicRuntimeConfig` | 无 |
| `identity` | `vision-forge-identity` | `user_account`、`refresh_token`、bcrypt 凭证认证、JWT 签发与管理员引导启动器 | `common` |
| `asset` | `vision-forge-asset` | 会话文本版本文件（`conversation_file`、`conversation_file_version`）、S3 对象存储适配、`upload_session`、`asset`、`asset_variant` | `common` |
| `billing` | `vision-forge-billing` | 额度账户（`credit_account`）、幂等预占/结算/释放（`credit_reservation`）与 Keyset 分页流水（`credit_ledger`） | `common` |
| `agent` | `vision-forge-agent` | 模型定义与密钥加密、会话与轮次运行时、上下文压缩、分镜校验与服务、工具集、AI/媒体协议适配器、Temporal Workflow 与 Activity | `common`、`identity`、`asset`、`billing` |
| `api` | `vision-forge-api` | HTTP / SSE 控制器、Security 过滤链、`GlobalExceptionHandler`、API DTO、SSE 订阅管理 | `agent`、`identity` |
| `worker` | `vision-forge-worker` | 非 Web Temporal Worker 容器、Poller 与并发参数配置、`AgentRunStartCompensator` | `agent` |
| `database` | `vision-forge-database` | Flyway 业务数据库迁移脚本（解耦于 API 与 Worker 启动之外） | 无 |

### 关键解耦机制

1. **反向依赖禁止与 SPI 回调**：`identity`、`asset`、`billing` 绝不反向依赖 `agent`、`api` 或 `worker`。需要跨边界检查会话所有权时（例如 `asset` 与 `api` 判断会话是否归属当前用户），通过 [`ConversationReferenceChecker`](../asset/src/main/java/app/pagefault/visionforge/asset/library/ConversationReferenceChecker.java) 接口在运行期由 `agent` 中的 `AgentConversationReferenceChecker` 实现注入；审计日志中操作人与存储同样通过 `OperatorProvider`、`TenantContext`、`BizLogStore` SPI 注入 `common.audit`。
2. **数据访问隔离**：每个业务模块拥有各自独立的包路径 `@MapperScan` 与 `UUIDTypeHandler`（例如 `AgentUuidTypeHandler`、`AssetUuidTypeHandler`、`BillingUuidTypeHandler`），禁止跨模块注入 Mapper 或编写跨领域数据库 JOIN，复合数据一律由应用层编排。
3. **DTO 隔离**：控制器层禁止直接序列化 MyBatis 实体，必须映射为 `api.*.dto.*Response` 不可变 `record`，确保敏感凭据（`api_key_ciphertext`、`password_hash`、`token_hash`）永不出域。

---

## 3. Temporal 持久化工作流与启动补偿

`worker` 模块在 [`TemporalWorkerConfiguration`](../worker/src/main/java/app/pagefault/visionforge/worker/temporal/TemporalWorkerConfiguration.java) 中创建 `WorkerFactory`，绑定 `TEMPORAL_TASK_QUEUE`（默认 `vision-forge`），统一注册 4 个 Workflow 实现与 4 组 Activity 实例。

### 3.1 Outbox 事务 + `AgentRunStartCompensator`

长任务均采用“**本地事务先落库 + 事务外投递 Temporal + 定时扫描补偿**”模式：

1. API 请求在单一本地数据库事务内写入业务主记录（初始 `PENDING`）、冻结输入、预占额度（生图/视频），并在 `workflow_start_request` 插入一条 `status = PENDING` 记录，注明 `workflow_type`（`AGENT_RUN` / `MEDIA_GENERATION` / `VIDEO_COMPOSITION`）与 `business_id`。
2. 本地事务提交后，`api` 侧 Starter 立即调用 `WorkflowClient.start(...)` 并更新请求状态为 `STARTED`。
3. 若此时 Temporal Server 不可用或请求进程重启，[`AgentRunStartCompensator`](../worker/src/main/java/app/pagefault/visionforge/worker/temporal/AgentRunStartCompensator.java) 在 Worker 侧按 `interval-ms`（默认 `5000ms`）分批拉取 `PENDING` 请求，根据持久化的 `workflow_type`（不信任任何外部类名）幂等拉起对应 Workflow；若 Temporal 返回 `WorkflowExecutionAlreadyStarted`，直接标记为 `STARTED`。

### 3.2 三大业务 Workflow 执行模型

| Workflow | Activity 分组与策略 | 超时与重试语义 | 核心职责 |
| --- | --- | --- | --- |
| `AgentRunWorkflowImpl` | `modelActivities`、`toolActivities`、`awaitActivities`、`finalizeActivities` | 模型调用 `StartToClose = 15m`，`MaximumAttempts = 1`；工具执行 `2m` / `2` 次；终态清理 `30s` / `3` 次 | 加载上下文 -> 循环调用大模型与工具 -> 遇 `await_generation` 时每 `5s` 睡眠等待媒体任务终态（上限 `1440` 次，即 2 小时）而不阻塞 Activity 线程 -> 完成回复 |
| `MediaGenerationWorkflowImpl` | `submitActivities`、`pollActivities`、`finalizeActivities` | 提交 `15m` / `1` 次；轮询 `2m` / `2` 次；结算/失败处理 `30s` / `3` 次 | 提交付费生成任务（`MaximumAttempts = 1` 防重复扣费）；若异步任务则每 `5s` 轮询远端状态（上限 `1440` 次）；完成后下载归档 S3 并触发额度结算 |
| `VideoCompositionWorkflowImpl` | `composeActivities`、`finalizeActivities` | 合成 `StartToClose = 30m`，**`HeartbeatTimeout = 60s`**，`MaximumAttempts = 1` | 执行 `ffprobe` 片段校验 -> FFmpeg 剪辑拼接 -> S3 分片流式上传 -> 发布资产与文件版本 |

---

## 4. Redis Pub/Sub 实时 SSE 事件流

`POST /api/v1/conversations/{conversationId}/messages/stream` 与非流式 `POST /messages` 共用同一事务提交入口，差异仅在于是否建立 SSE 通道：

1. `AgentRunStreamService.subscribe(runId)` 向 `RedisMessageListenerContainer` 动态注册 `ChannelTopic`（`AgentRunStreamPublisher.channel(runId)`），创建超时默认 `300000ms`（`STREAM_TIMEOUT_MS`）的 `SseEmitter`，并立即发送 `event: accepted`。
2. Worker 在运行各阶段将 `AgentRunStreamEvent` 发布到 Redis：
   - 轮次边界：`turn_start`
   - 推理内容：`reasoning`（明文）、`reasoning_summary`（摘要）
   - 工具进度：`tool_start`、`tool_waiting`、`tool_done`（经 [`ToolDisplayFormatter`](../agent/src/main/java/app/pagefault/visionforge/agent/runtime/ToolDisplayFormatter.java) 脱敏为简洁操作名、安全 target 与 `fileId`/`taskId` 引用）
   - 正文流与终态：`delta`、`done`（含 `assistantMessageId`）、`error`
3. **持久化优先于实时通道**：Redis Pub/Sub 仅承担低延迟在线投递（best-effort）。当收到 `done` 或 `error` 时服务端关闭 Emitter；若中途断线，客户端或页面刷新后通过 `GET /api/v1/agent-runs` 与 `GET /api/v1/conversations/{conversationId}/messages` 中的 `process` 视图（由 `AgentProcessReader` 聚合数据库 `agent_run_turn` 与 `tool_invocation`）无损恢复完整思维过程与回复。

---

## 5. 对象存储与双端点签名设计

1. **存储介质分流**：脚本、分镜 JSON、提示词、其他文本文件（最大 `1 MB`）存储在 PostgreSQL `conversation_file` + `conversation_file_version`；图片、视频等二进制对象存储在 RustFS / S3 兼容对象存储。
2. **永不持久化临时 URL**：数据库 `asset` 只保存永久主键与对象键（`bucket`、`object_key`、`mime_type`、`size_bytes` 等）。
3. **内外网双端点**：
   - 服务端与 Worker 使用 `STORAGE_ENDPOINT`（如容器网络内 `http://rustfs:9000`）访问 S3、`HeadObject` 校验、媒体下载归档与分片上传。
   - 对前端签发直传 `PUT` 与下载 `GET` 预签名 URL 时，使用 `STORAGE_PUBLIC_ENDPOINT`（浏览器可达地址）单独生成签名，确保内部网络拓扑改动不影响前端直传签名校验。
4. **S3 能力子集约束**：存储适配器仅使用 RustFS 验证过的 S3 子集（对象 CRUD、预签名 GET/PUT、Range 读取、Multipart 上传）。

---

## 6. 安全模型与数据隔离

- **无工作空间多租户**：系统明确移除工作空间概念，**会话即隔离边界**。用户仅可见本人创建的会话、文件、资产、生成任务、合成任务与额度记录；对他人 ID 的任何读取或修改均统一返回 **404**，不以 403 泄漏资源是否存在。
- **双角色鉴权**：系统角色仅为 `USER` 与 `ADMIN`。`SecurityConfiguration` 仅开放 `/api/v1/auth/{register,login,refresh,logout}` 与 `/actuator/{health/**,info}` 匿名访问；`/api/v1/admin/**` 强制 `hasRole("ADMIN")`；管理员权限严格用于模型配置与用户角色运维，不附带跨用户创作数据查看权限。
- **静态密钥加密**：[`ApiKeyCipher`](../agent/src/main/java/app/pagefault/visionforge/agent/model/ApiKeyCipher.java) 使用 `AES/GCM/NoPadding`（12 字节随机 IV、128-bit GCM Tag）搭配严格 32 字节 Base64 `MODEL_CONFIG_SECRET` 加密存储模型密钥，响应模型始终只暴露布尔值 `apiKeyConfigured`。
- **令牌体系**：密码使用 `BCryptPasswordEncoder`（默认 strength 12）；Access Token 为 15 分钟有效期的 HS256 JWT（`JWT_SECRET` 至少 32 字节 Base64）；Refresh Token 为 30 天有效期的随机令牌，数据库中仅保存单向哈希并在每次刷新时轮换。
- **前端路由隔离**：管理控制台界面（模型路由与用户角色）完全隔离在 Next.js `(admin)` 路由组及 `AdminRoleGuard` + `AdminShell` 中，与创作者 `(studio)` 路由组与会话/额度 Provider 完全分离。
