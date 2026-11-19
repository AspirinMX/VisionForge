# VisionForge HTTP & SSE 接口契约参考

本文档以 [`api/`](../api) 模块控制器及前端 [`endpoints.ts`](../frontend/src/lib/api/endpoints.ts)、[`types.ts`](../frontend/src/lib/api/types.ts) 为准，系统整理前端消费的全部 `/api/v1/**` HTTP 与 SSE 接口契约、鉴权机制、统一响应包络及全部模块错误码。

---

## 1. 通用约定

### 1.1 请求转发与基址

- 浏览器统一请求同源路径 `/api/v1/**`。
- Next.js 运行期代理 [`route.ts`](../frontend/src/app/api/v1/[...path]/route.ts) 移除 `hop-by-hop` 头与 `accept-encoding`，按后端变量 `VISIONFORGE_API_BASE_URL`（默认 `http://127.0.0.1:8080`）原样转发 HTTP 状态码与流式 Body；若后端不可达则返回 `502` JSON（`COMMON_SERVICE_UNAVAILABLE`）。
- 除 `/api/v1/auth/{register,login,refresh,logout}` 与健康检查 `/actuator/{health/**,info}` 外，其余接口均须携带 `Authorization: Bearer <accessToken>`。

### 1.2 统一响应包络 (`ApiResponse<T>`)

除 SSE（`text/event-stream`）外，全部 JSON 响应遵循 [`ApiResponse`](../common/src/main/java/app/pagefault/visionforge/common/api/ApiResponse.java)：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {},
  "traceId": "5f2c9a1e4b2d4c61a80e2a8f5b7c1e34",
  "timestamp": "2026-11-19T00:00:00Z"
}
```

- 参数校验失败返回 `400`、`code = "COMMON_INVALID_ARGUMENT"`，并在 `data` 中以字段路径 Map 返回全部校验错误。
- 资源隔离：访问不属于当前用户的会话、文件、资产、生成/合成任务，统一返回 **404**，绝不返回 403。

---

## 2. 认证与用户接口

### 2.1 Auth (`AuthController`, `/api/v1/auth`)

| 方法与路径 | 状态码 | 鉴权 | 请求体 / 查询参数 | 响应 `data` |
| --- | --- | --- | --- | --- |
| `POST /api/v1/auth/register` | `201` | 公开 | `{ email, password (12-72), displayName (<=100) }` | `UserResponse` |
| `POST /api/v1/auth/login` | `200` | 公开 | `{ email, password }` | `LoginResponse { token, user }` |
| `POST /api/v1/auth/refresh` | `200` | 公开 | `{ refreshToken }`（**单次轮换**，旧令牌失效） | `TokenResponse` |
| `POST /api/v1/auth/logout` | `200` | 公开 | `{ refreshToken }` | `null` |
| `GET /api/v1/auth/me` | `200` | JWT | — | `UserResponse` |
| `PATCH /api/v1/auth/me` | `200` | JWT | `{ displayName (1-100) }` | `UserResponse` |
| `POST /api/v1/auth/change-password` | `200` | JWT | `{ currentPassword, newPassword (12-72) }`（校验原密码并吊销全部旧 Refresh Token） | `null` |

### 2.2 管理员用户接口 (`AdminUserController`, `/api/v1/admin/users`)

| 方法与路径 | 状态码 | 权限 | 参数 | 响应 `data` |
| --- | --- | --- | --- | --- |
| `GET /api/v1/admin/users` | `200` | `ADMIN` | `?page=1&size=20&q=...&role=ADMIN\|USER&status=ACTIVE\|DISABLED` | `PageResult<UserResponse> { items, page, size, total }` |
| `POST /api/v1/admin/users` | `201` | `ADMIN` | `{ email, password (12-72), displayName, role }` | `UserResponse` |
| `PUT /api/v1/admin/users/{id}/role` | `200` | `ADMIN` | `{ role: "ADMIN" \| "USER" }`（禁止修改本人角色） | `UserResponse` |
| `PUT /api/v1/admin/users/{id}/status` | `200` | `ADMIN` | `{ status: "ACTIVE" \| "DISABLED" }`（禁用本人返回 409；禁用即吊销 Refresh Token） | `UserResponse` |
| `POST /api/v1/admin/users/{id}/reset-password` | `200` | `ADMIN` | `{ newPassword (12-72) }`（同步吊销该用户全部 Refresh Token） | `null` |
| `POST /api/v1/admin/users/{id}/revoke-sessions` | `200` | `ADMIN` | 路径参数 `{id}` | `{ revokedCount }` |

---

## 3. 模型目录与管理员配置

### 3.1 普通用户模型目录 (`ModelCatalogController`, `/api/v1/models`)

| 方法与路径 | 状态码 | 查询参数 | 响应 `data` |
| --- | --- | --- | --- |
| `GET /api/v1/models` | `200` | `?capabilityType=CHAT\|IMAGE\|VIDEO`（可选） | `PublicModelResponse[]`（脱敏：不含 `baseUrl` 与 `apiKey`） |

### 3.2 管理员模型配置 (`AdminModelController`, `/api/v1/admin/models`)

| 方法与路径 | 状态码 | 权限 | 请求体核心约束 | 响应 `data` |
| --- | --- | --- | --- | --- |
| `GET /api/v1/admin/models` | `200` | `ADMIN` | — | `ModelResponse[]`（仅含 `apiKeyConfigured`） |
| `GET /api/v1/admin/models/{id}` | `200` | `ADMIN` | — | `ModelResponse` |
| `POST /api/v1/admin/models` | `201` | `ADMIN` | `displayName(<=100)`, `baseUrl(<=500)`, `apiKey(<=1000)`, `modelId(<=200)`, `reasoningEffort`, `reasoningReplay`, `capabilityType`, `protocol`, `executionMode`, `priceUnits(>=0)`, `supportsImageInput`, `maxInputTokens(1..2000000)`, `maxOutputTokens(1..1000000)`, `enabled`, `defaultModel` | `ModelResponse` |
| `PATCH /api/v1/admin/models/{id}` | `200` | `ADMIN` | 同上字段全可选 | `ModelResponse` |
| `POST /api/v1/admin/models/{id}/default` | `200` | `ADMIN` | —（按 `capabilityType` 设置唯一默认） | `ModelResponse` |
| `POST /api/v1/admin/models/{id}/test` | `200` | `ADMIN` | —（脱敏探测模型网关连通性与鉴权） | `ModelTestResponse { ok, reachable, statusCode, latencyMs, protocol, detail }` |

### 3.3 管理端运维概览 (`AdminOverviewController`, `/api/v1/admin/overview`)

| 方法与路径 | 状态码 | 权限 | 说明 | 响应 `data` |
| --- | --- | --- | --- | --- |
| `GET /api/v1/admin/overview` | `200` | `ADMIN` | 汇总用户、活跃会话、运行中/异常任务及 `ARCHIVE_FAILED` / `PENDING_RECONCILIATION` / 补偿失败指标 | `AdminOverviewResponse` |

### 3.4 Nacos 配置中心控制台 (`AdminConfigController`, `/api/v1/admin/configs`)

| 方法与路径 | 状态码 | 权限 | 请求体 / 说明 | 响应 `data` |
| --- | --- | --- | --- | --- |
| `GET /api/v1/admin/configs` | `200` | `ADMIN` | 返回 Nacos 连接状态与全部受管 JSON 配置项（`visionforge-runtime/security/billing/alert.json`） | `ConfigCenterOverviewResponse` |
| `GET /api/v1/admin/configs/{dataId}` | `200` | `ADMIN` | 读取指定 `dataId` 的最新配置与默认模板 | `ConfigEntryResponse` |
| `PUT /api/v1/admin/configs/{dataId}` | `200` | `ADMIN` | `{ content }`（校验 JSON 对象后发布至 Nacos 并热刷新） | `ConfigEntryResponse` |
| `POST /api/v1/admin/configs/{dataId}/reset` | `200` | `ADMIN` | 还原该 `dataId` 为内置默认 JSON 配置 | `ConfigEntryResponse` |

### 3.5 邮件与 Webhook 告警管理 (`AdminAlertController`, `/api/v1/admin/alerts`)

| 方法与路径 | 状态码 | 权限 | 请求体 / 查询参数 | 响应 `data` |
| --- | --- | --- | --- | --- |
| `GET /api/v1/admin/alerts/channels` | `200` | `ADMIN` | — | `AlertChannelResponse[]`（脱敏，仅返回 `secretConfigured`） |
| `POST /api/v1/admin/alerts/channels` | `201` | `ADMIN` | `{ name, channelType (EMAIL\|WEBHOOK), target, secret?, subscribedEvents?, minSeverity?, enabled? }` | `AlertChannelResponse` |
| `PATCH /api/v1/admin/alerts/channels/{id}` | `200` | `ADMIN` | 同上字段全可选 | `AlertChannelResponse` |
| `DELETE /api/v1/admin/alerts/channels/{id}` | `200` | `ADMIN` | 路径参数 `{id}` | `null` |
| `POST /api/v1/admin/alerts/channels/{id}/test` | `200` | `ADMIN` | 同步发送测试告警并记录投递流水 | `AlertDeliveryResponse` |
| `GET /api/v1/admin/alerts/deliveries` | `200` | `ADMIN` | `?page=1&size=50&channelId=&status=SENT\|FAILED\|SKIPPED&eventType=` | `PageResult<AlertDeliveryResponse>` |

---

## 4. 会话、消息、流式 SSE 与 AgentRun

### 4.1 会话与 Run (`ConversationController`, `AgentRunController`)

| 方法与路径 | 状态码 | 请求 / 查询参数 | 响应 `data` |
| --- | --- | --- | --- |
| `GET /api/v1/conversations` | `200` | — | `ConversationResponse[]` |
| `POST /api/v1/conversations` | `201` | `{ title? (<=200) }` | `ConversationResponse` |
| `GET /api/v1/conversations/{conversationId}` | `200` | — | `ConversationResponse` |
| `PATCH /api/v1/conversations/{conversationId}` | `200` | `{ title (1..200) }` | `ConversationResponse` |
| `DELETE /api/v1/conversations/{conversationId}` | `200` | —（存在 `PENDING`/`RUNNING` 的 `AgentRun` 时返回 `409 CONVERSATION_BUSY`） | `null` |
| `GET /api/v1/conversations/{conversationId}/messages` | `200` | — | `MessageResponse[]`（附带 `assetIds`, `generationTaskIds`, `process`） |
| `POST /api/v1/conversations/{conversationId}/messages` | `202` | `{ content (1..32000), assetIds? (<=8) }` | `AgentTurnResponse { runId, workflowId, userMessageId }` |
| `POST /api/v1/conversations/{conversationId}/messages/stream` | `200` | 同上 | `text/event-stream` SSE 事件流 |
| `GET /api/v1/agent-runs` | `200` | `?conversationId=&status=&limit=20` | `AgentRunResponse[]`（含 `process` 过程视图） |
| `GET /api/v1/agent-runs/{runId}` | `200` | — | `AgentRunResponse`（含 `process`） |
| `POST /api/v1/agent-runs/{runId}/cancel` | `200` | —（将 `AgentRun` 标记为 `CANCELLED` 并向 Temporal Workflow 发送 cancel） | `AgentRunResponse` |

### 4.2 SSE 事件流协议 (`POST .../messages/stream`)

浏览器**必须使用 `fetch` + 流式 Reader**（禁止 `EventSource`，因其无法发送 `Authorization` 头）。事件均遵循 `event: <type>\ndata: <JSON>\n\n`：

| `event` / `type` | 关键负载字段 | 语义 |
| --- | --- | --- |
| `accepted` | `runId` | 订阅成功且 Run 已入队 |
| `turn_start` | `turnNo` | 模型新轮次开始 |
| `reasoning` | `content` | 上游返回的明文思维链增量 |
| `reasoning_summary` | `content` | 加密推理模型的文本摘要增量 |
| `tool_start` / `tool_waiting` / `tool_done` | `turnNo, callId, toolName, action, target, status, fileId?, taskId?, message?` | 工具调用状态（`WAITING` 表示等待媒体生成终态） |
| `delta` | `content` | 助手正文 Markdown 增量 |
| `done` | `assistantMessageId` | 回合正常结束，流关闭 |
| `error` | `message` | 失败消息，流关闭；前端应兜底查询 Run 与持久化消息 |

---

## 5. 会话工作区文件、上传与对象存储资产

### 5.1 会话文件 (`ConversationFileController`, `/api/v1/conversations/{conversationId}/files`)

| 方法与路径 | 状态码 | 参数 / 请求体 | 响应 `data` |
| --- | --- | --- | --- |
| `GET /files` | `200` | `?kind=&limit=50` | `ConversationFileResponse[]` |
| `GET /files/search` | `200` | `?q=&kind=&limit=50` | `ConversationFileResponse[]` |
| `POST /files` | `201` | `{ kind, title (<=200), path? (<=500), content }` | `ConversationFileResponse` |
| `GET /files/{fileId}` | `200` | — | `ConversationFileResponse` |
| `PATCH /files/{fileId}` | `200` | `{ baseVersion (>=1), content, title? }` | `ConversationFileResponse`（版本冲突返回 `409`） |
| `GET /files/{fileId}/versions` | `200` | `?limit=50` | `ConversationFileVersionResponse[]`（解析关联 `assetId`） |
| `GET /files/{fileId}/versions/{version}` | `200` | — | `ConversationFileVersionResponse` |

> 创建或更新 `kind = STORYBOARD` 时，服务端同步触发 [`StoryboardValidator`](../agent/src/main/java/app/pagefault/visionforge/agent/storyboard/StoryboardValidator.java) 校验，失败返回 `400 AGENT_STORYBOARD_INVALID`。

### 5.2 S3 直传凭证与资产解析 (`UploadController`, `ConversationAssetController`, `AssetController`)

| 方法与路径 | 状态码 | 参数 / 请求体 | 响应 `data` |
| --- | --- | --- | --- |
| `POST /api/v1/conversations/{conversationId}/uploads` | `201` | `{ kind, filename, mimeType, sizeBytes (>0) }` | `UploadTicketResponse { uploadSessionId, assetId, uploadUrl, requiredHeaders, ... }` |
| `PUT <uploadUrl>` | `200` | 原生二进制流直传 RustFS，须严格匹配 `requiredHeaders`（含 `Content-Type`） | — |
| `POST /api/v1/conversations/{conversationId}/uploads/{uploadSessionId}/complete` | `200` | —（服务端 HeadObject 校验大小与 MIME） | `AssetResponse (status: READY)` |
| `GET /api/v1/conversations/{conversationId}/assets` | `200` | `?kind=&source=&limit=50` | `AssetResponse[]` |
| `GET /api/v1/conversations/{conversationId}/assets/{assetId}` | `200` | — | `AssetResponse` |
| `GET /api/v1/assets/{assetId}` | `200` | — | `AssetResponse` |
| `GET /api/v1/assets/{assetId}/download-url` | `200` | — | `AssetDownloadUrlResponse { assetId, downloadUrl, expiresAt }` |

---

## 6. 媒体生成、视频合成与账户额度

### 6.1 媒体生成任务 (`GenerationTaskController`, `/api/v1/generation-tasks`)

| 方法与路径 | 状态码 | 关键请求 / 查询字段 | 响应 `data` |
| --- | --- | --- | --- |
| `POST /api/v1/generation-tasks` | `202` + `Location` | Header 可选 `Idempotency-Key`；Body `{ conversationId, type: "IMAGE"\|"VIDEO", modelDefinitionId?, prompt (<=32000), parameters?, inputs? (<=8), sourceTaskId?, shotId? (<=64), storyboardFileId?, storyboardVersion? }` | `GenerationTaskResponse` |
| `GET /api/v1/generation-tasks` | `200` | `?conversationId=&limit=20` | `GenerationTaskResponse[]` |
| `GET /api/v1/generation-tasks/{taskId}` | `200` | — | `GenerationTaskResponse` |
| `POST /api/v1/generation-tasks/{taskId}/cancel` | `202` | — | `GenerationTaskResponse` |
| `POST /api/v1/generation-tasks/{taskId}/retry-archive` | `202` | —（仅修复 `ARCHIVE_FAILED`，不重新生成或重复扣费） | `GenerationTaskResponse` |

### 6.2 视频合成任务 (`CompositionTaskController`, `/api/v1/composition-tasks`)

| 方法与路径 | 状态码 | 参数 | 响应 `data` |
| --- | --- | --- | --- |
| `POST /api/v1/composition-tasks` | `202` + `Location` | `{ conversationId, storyboardFileId, storyboardVersion?, outputSpec? }` | `CompositionTaskResponse` |
| `GET /api/v1/composition-tasks` | `200` | `?conversationId=&status=&limit=20` | `CompositionTaskResponse[]`（批量加载 inputs） |
| `GET /api/v1/composition-tasks/{taskId}` | `200` | — | `CompositionTaskResponse` |
| `POST /api/v1/composition-tasks/{taskId}/cancel` | `202` | — | `CompositionTaskResponse` |
| `POST /api/v1/composition-tasks/{taskId}/retry` | `202` + `Location` | — | `CompositionTaskResponse` |

### 6.3 账户额度与流水 (`AccountController`, `/api/v1/account`)

| 方法与路径 | 状态码 | 查询参数 | 响应 `data` |
| --- | --- | --- | --- |
| `GET /api/v1/account/credit` | `200` | — | `CreditSummaryResponse { balanceUnits, reservedUnits, availableUnits, updatedAt }` |
| `GET /api/v1/account/credit/ledger` | `200` | `?limit=50&before=<ledgerId>`（UUIDv7 倒序游标） | `CreditLedgerEntryResponse[]` |
| `GET /api/v1/account/credit/ledger/export` | `200` | `?maxRows=2000`（最高 5000） | `text/csv; charset=UTF-8` 额度流水 CSV 下载 |
| `POST /api/v1/account/credit/redeem` | `200` | `{ code }` | `RedeemCodeResponse { ledgerId, code, amountUnits, newBalanceUnits, newAvailableUnits }` |
| `GET /api/v1/account/usage` | `200` | `?window=100` | `CreditUsageResponse { window, totalPriceUnits, buckets }` |

### 6.4 管理端调账与兑换码 (`AdminBillingController`, `/api/v1/admin`)

| 方法与路径 | 状态码 | 权限 | 请求体 / 查询参数 | 响应 `data` |
| --- | --- | --- | --- | --- |
| `POST /api/v1/admin/users/{id}/credit-adjust` | `200` | `ADMIN` | `{ deltaUnits (!=0), idempotencyKey (<=128), remark? }` | `AdminCreditLedgerResponse` |
| `GET /api/v1/admin/billing/ledger` | `200` | `ADMIN` | `?page=1&size=50&userId=&entryType=` | `PageResult<AdminCreditLedgerResponse>` |
| `GET /api/v1/admin/billing/redemptions` | `200` | `ADMIN` | `?page=1&size=50&batchName=&status=ACTIVE\|VOIDED` | `PageResult<RedemptionCodeResponse>` |
| `POST /api/v1/admin/billing/redemptions` | `201` | `ADMIN` | `{ batchName, count (1..100), amountUnits, maxUsesPerCode, perUserLimit, customPrefix?, expiresAt? }` | `RedemptionCodeResponse[]` |
| `POST /api/v1/admin/billing/redemptions/{id}/void` | `200` | `ADMIN` | 路径参数 `{id}` | `RedemptionCodeResponse` |

### 6.5 管理员审计日志 (`AdminAuditLogController`, `/api/v1/admin/audit-logs`)

| 方法与路径 | 状态码 | 权限 | 查询参数 | 响应 `data` / 内容 |
| --- | --- | --- | --- | --- |
| `GET /api/v1/admin/audit-logs` | `200` | `ADMIN` | `?page=1&size=50&type=&subType=&operatorId=&bizNo=&traceId=&success=&from=&to=` | `PageResult<AuditLogResponse>` |
| `GET /api/v1/admin/audit-logs/export` | `200` | `ADMIN` | 同上（支持 `&maxRows=2000`，最高 10000） | `text/csv; charset=UTF-8` 下载文件（含 UTF-8 BOM 与公式字符转义） |

---

## 7. 全量错误码矩阵

客户端只能依赖稳定字符串 `code` 做逻辑分支，禁止依赖中文文案 `message`。

### 7.1 `CommonErrorCode`

| 错误码 | HTTP | 默认文案 |
| --- | --- | --- |
| `COMMON_INVALID_ARGUMENT` | 400 | 请求参数不合法 |
| `COMMON_UNAUTHORIZED` | 401 | 未认证或登录状态已失效 |
| `COMMON_FORBIDDEN` | 403 | 没有执行该操作的权限 |
| `COMMON_NOT_FOUND` | 404 | 请求的资源不存在 |
| `COMMON_METHOD_NOT_ALLOWED` | 405 | 请求方法不被支持 |
| `COMMON_CONFLICT` | 409 | 请求与当前资源状态冲突 |
| `COMMON_UNSUPPORTED_MEDIA_TYPE` | 415 | 不支持的内容类型 |
| `COMMON_RATE_LIMITED` | 429 | 请求过于频繁，请稍后重试 |
| `COMMON_INTERNAL_ERROR` | 500 | 系统内部错误，请稍后重试 |
| `COMMON_SERVICE_UNAVAILABLE` | 503 | 服务暂时不可用，请稍后重试 |

### 7.2 `IdentityErrorCode`

| 错误码 | HTTP | 说明 |
| --- | --- | --- |
| `IDENTITY_EMAIL_ALREADY_REGISTERED` | 409 | 该邮箱已注册 |
| `IDENTITY_INVALID_CREDENTIALS` | 401 | 邮箱或密码错误 |
| `IDENTITY_TOKEN_INVALID` | 401 | 登录状态已失效 |
| `IDENTITY_USER_NOT_FOUND` | 404 | 用户不存在 |
| `IDENTITY_SELF_ROLE_CHANGE_FORBIDDEN` | 409 | 不能修改自己的系统角色 |
| `IDENTITY_SELF_STATUS_CHANGE_FORBIDDEN` | 409 | 不能禁用当前登录账号 |
| `IDENTITY_WEAK_PASSWORD` | 400 | 密码长度不在 12–72 字符范围内 |
| `IDENTITY_INVALID_EMAIL` | 400 | 邮箱格式不正确 |
| `IDENTITY_ACCOUNT_DISABLED` | 403 | 账号已被禁用 |

### 7.3 `BillingErrorCode`

| 错误码 | HTTP | 说明 |
| --- | --- | --- |
| `BILLING_ACCOUNT_NOT_FOUND` | 404 | 额度账户不存在 |
| `BILLING_INSUFFICIENT_CREDIT` | 409 | 额度不足，无法受理该任务 |
| `BILLING_RESERVATION_NOT_FOUND` | 404 | 额度预占记录不存在 |
| `BILLING_RESERVATION_STATE_CONFLICT` | 409 | 额度预占状态不允许该操作 |
| `BILLING_SETTLE_CONFLICT` | 409 | 账户余额与预占不一致 |
| `BILLING_AMOUNT_INVALID` | 400 | 额度金额不合法 |
| `BILLING_IDEMPOTENCY_CONFLICT` | 409 | 幂等键对应了不同的操作参数 |
| `BILLING_REDEMPTION_CODE_NOT_FOUND` | 404 | 兑换码不存在或格式无效 |
| `BILLING_REDEMPTION_CODE_VOIDED` | 409 | 该兑换码已作废 |
| `BILLING_REDEMPTION_CODE_EXPIRED` | 409 | 该兑换码已过期 |
| `BILLING_REDEMPTION_CODE_EXHAUSTED` | 409 | 该兑换码已被领完 |
| `BILLING_REDEMPTION_USER_LIMIT_REACHED` | 409 | 已达到该兑换码使用次数上限 |

### 7.4 `AssetErrorCode`

| 错误码 | HTTP | 说明 |
| --- | --- | --- |
| `ASSET_FILE_NOT_FOUND` / `ASSET_FILE_VERSION_NOT_FOUND` | 404 | 会话文件或文件版本不存在 |
| `ASSET_FILE_KIND_REQUIRED` / `ASSET_FILE_KIND_UNSUPPORTED` | 400 | 文件类型缺失或不支持 |
| `ASSET_FILE_TITLE_INVALID` / `ASSET_FILE_TITLE_TOO_LONG` | 400 | 标题为空或超过 200 字符 |
| `ASSET_FILE_PATH_INVALID` / `ASSET_FILE_CONTENT_EMPTY` / `ASSET_FILE_CONTENT_TOO_LONG` | 400 | 路径非法或文本超过 1 MB |
| `ASSET_FILE_PATH_CONFLICT` / `ASSET_FILE_VERSION_CONFLICT` | 409 | 同路径文件已存在或 `baseVersion` 过期 |
| `ASSET_CONVERSATION_NOT_FOUND` / `ASSET_NOT_FOUND` / `UPLOAD_SESSION_NOT_FOUND` | 404 | 归属校验失败或对象/会话不存在 |
| `ASSET_NOT_READY` / `UPLOAD_SESSION_NOT_ACTIVE` / `UPLOAD_NOT_COMPLETED` / `UPLOAD_SIZE_MISMATCH` / `UPLOAD_CONTENT_TYPE_MISMATCH` | 409 | 直传状态、大小或 MIME 核验失败 |
| `ASSET_KIND_REQUIRED` / `ASSET_FILENAME_REQUIRED` / `ASSET_MIME_UNSUPPORTED` / `ASSET_SIZE_INVALID` | 400 | 上传申请元数据不合法 |
| `ASSET_SIZE_TOO_LARGE` | 413 | 文件超过 `STORAGE_MAX_UPLOAD_BYTES` |
| `UPLOAD_SESSION_EXPIRED` | 410 | 上传凭证会话已过期 |
| `STORAGE_NOT_CONFIGURED` / `STORAGE_OPERATION_FAILED` | 503 / 502 | 对象存储不可用或操作异常 |

### 7.5 `AgentErrorCode`

| 错误码 | HTTP | 说明 |
| --- | --- | --- |
| `AGENT_MODEL_NOT_FOUND` / `AGENT_CONVERSATION_NOT_FOUND` / `AGENT_RUN_NOT_FOUND` / `AGENT_STORYBOARD_SHOT_NOT_FOUND` / `AGENT_GENERATION_NOT_FOUND` / `AGENT_SKILL_NOT_FOUND` / `AGENT_TOOL_NOT_FOUND` / `AGENT_ALERT_CHANNEL_NOT_FOUND` | 404 | 对应实体不存在或不属于当前用户 |
| `AGENT_MODEL_CONFIG_INVALID` / `AGENT_MODEL_API_KEY_REQUIRED` / `AGENT_MESSAGE_EMPTY` / `AGENT_MESSAGE_TOO_LONG` / `AGENT_STORYBOARD_INVALID` / `AGENT_GENERATION_PROMPT_EMPTY` / `AGENT_GENERATION_CAPABILITY_UNSUPPORTED` / `AGENT_GENERATION_INPUT_INVALID` / `AGENT_GENERATION_PROVIDER_UNSUPPORTED` / `AGENT_TOOL_ARGUMENTS_INVALID` / `AGENT_ALERT_CONFIG_INVALID` | 400 | 请求参数、模型配置、分镜校验、告警配置或工具入参不合法 |
| `AGENT_MODEL_NOT_CONFIGURED` / `AGENT_CONVERSATION_BUSY` / `AGENT_RUN_INVALID_STATE` / `AGENT_GENERATION_IDEMPOTENCY_CONFLICT` / `AGENT_GENERATION_STATE_CONFLICT` / `AGENT_GENERATION_CANCEL_UNSUPPORTED` / `AGENT_TOOL_CONVERSATION_REQUIRED` / `AGENT_TOOL_INVOCATION_IN_PROGRESS` | 409 | 模型未就绪、会话并发互斥、幂等冲突或状态不允许 |
| `AGENT_MODEL_PROVIDER_ERROR` / `AGENT_MODEL_RESPONSE_INVALID` / `AGENT_GENERATION_PROVIDER_ERROR` / `AGENT_GENERATION_ARCHIVE_FAILED` | 502 | 上游模型或媒体网关失败、响应非法或结果归档失败 |
| `AGENT_RUN_START_FAILED` / `AGENT_GENERATION_START_FAILED` | 503 | 工作流投递不可用 |
| `AGENT_TOOL_EXECUTION_FAILED` | 500 | 内部工具执行异常 |
