# VisionForge 核心设计细节

本文档阐述 VisionForge 的关键领域设计决策，涵盖数据库 Schema 演进（`V1`–`V19`）、生成任务四轴生命周期与额度账本、本地上下文压缩与无状态推理重放、结构化分镜 Schema v1、FFmpeg 合成流水线，以及前端工作台与管理后台的设计架构。

---

## 1. 数据库 Schema 演进与核心实体关系（Flyway `V1`–`V19`）

所有业务迁移脚本由 `database/src/main/resources/db/migration/` 统一管理，全部主键采用应用侧生成的 **UUIDv7**（`common.id.UuidV7Generator`，48-bit 毫秒时间戳 + `version=7`、`variant=2`），全部时间字段使用 PostgreSQL `TIMESTAMPTZ` 存储 UTC 时间。

### 1.1 迁移脉络

| 版本范围 | 核心演进内容 |
| --- | --- |
| `V1`–`V3` | 创建 `user_account`、`refresh_token`、`audit_log` 以及早期 `workspace` / `workspace_member` |
| `V4` | 创建 `model_definition`、`conversation`、`message`、`agent_run`、`model_invocation`、`workflow_start_request` |
| `V5`–`V6` | 引入早期存储表与工具调用记录表 `tool_invocation` |
| `V7`–`V10` | 引入 `agent_run_turn`（按轮次保存模型回复与函数调用）、`context_summary`（本地摘要）、`context_epoch` 与 `reasoning_replay` 策略 |
| `V11` | **架构收敛**：彻底移除工作空间概念，删除 `workspace` / `workspace_member` / `workspace_item*` / `conversation_item`，全表转为以用户 + 会话作为隔离边界 |
| `V12` | 创建会话工作区文本文件表 `conversation_file` 与不可变版本表 `conversation_file_version` |
| `V13` | 创建对象存储元数据表 `asset`、`asset_variant` 与直传凭证表 `upload_session` |
| `V14` | 扩充 `model_definition` 能力/协议/计费列，创建 `generation_task`、`generation_attempt`、`generation_input`、`generation_output`、`message_part`、`credit_account`、`credit_reservation`、`credit_ledger` |
| `V15` | 新增 `generation_task` 分镜关联列（`shot_id`、`storyboard_file_id`、`storyboard_version`）、工具 `WAITING` 状态与成片表 `composition_task` / `composition_input` |
| `V16` | 新增 `conversation.deleted_at` 软删除列与活跃会话索引，扩展 `agent_run.status` 允许 `CANCELLED` |
| `V17` | 扩充 `credit_ledger`（支持 `ADJUST` / `REDEEM` 与 `remark`），创建 `credit_redemption_code` / `credit_redemption_record` 兑换码体系 |
| `V18` | 创建邮件与 Webhook 告警通道表 `alert_channel`（HMAC 密钥加密存储）与投递流水表 `alert_delivery` |
| `V19` | 标准化模型推理强度为 `NONE/LOW/MEDIUM/HIGH/XHIGH/MAX`（历史 `MINIMAL` 迁移为 `LOW`），并新增 `model_definition.supported_reasoning_efforts` |

### 1.2 核心约束摘要

- **`model_definition`**：默认模型按能力类型分别唯一（Partial Unique Index `uk_model_definition_default ON model_definition (capability_type) WHERE default_model`）；当 `capability_type = 'CHAT'` 时强制要求 `max_input_tokens > 0` 且 `max_output_tokens > 0`。
- **`conversation_file` & `conversation_file_version`**：同一会话下有效文件路径唯一（`WHERE deleted_at IS NULL AND path IS NOT NULL`）；`(file_id, version)` 唯一，更新需通过 `baseVersion` 进行并发检测。
- **`generation_task`**：`uk_generation_task_idempotency ON generation_task (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL` 保证用户级幂等提交；`state_version` 支持状态更新版本校验。
- **`credit_account`**：强制约束 `balance_units >= 0` 且 `reserved_units >= 0 AND reserved_units <= balance_units`。

---

## 2. 媒体生成“四轴状态”与额度计费闭环

为了避免并发生成、异步轮询、产物归档与费用结算状态交织导致状态爆炸，[`GenerationTask`](../agent/src/main/java/app/pagefault/visionforge/agent/generation/GenerationTask.java) 在数据库层面将任务状态解耦为四个正交轴：

| 轴 | 字段 | 允许枚举值 | 职责说明 |
| --- | --- | --- | --- |
| **执行状态** | `status` | `PENDING`, `SUBMITTING`, `RUNNING`, `ARCHIVING`, `PROCESSING`, `SUCCEEDED`, `PARTIAL_SUCCEEDED`, `FAILED`, `CANCELLED`, `UNKNOWN`, `ARCHIVE_FAILED`, `DELIVERY_FAILED` | 任务当前所处调度/远端执行/归档阶段 |
| **交付状态** | `delivery_status` | `PENDING`, `ARCHIVED`, `PUBLISHED`, `PARTIAL`, `FAILED` | 产物是否已安全拉取至我方 S3 并关联为 `ConversationFile` 版本 |
| **计费状态** | `billing_status` | `RESERVED`, `PENDING_RECONCILIATION`, `SETTLED`, `RELEASED` | 额度预占是否已结算扣减或失败退还 |
| **取消状态** | `cancel_status` | `NONE`, `REQUESTED`, `CONFIRMED`, `UNSUPPORTED`, `TOO_LATE`, `UNKNOWN` | 上游取消请求的确认情况 |

### 额度预占、结算与归档补偿流程

1. **受理预占（同一本地事务）**：计算模型 `price_units`，在 `credit_account` 中原子冻结额度（`reserved_units += price_units`），写入 `credit_reservation(RESERVED)` 与 `credit_ledger(RESERVE)`，并按 `user_id + idempotency_key` 去重（同 key 异参数抛出 `AGENT_GENERATION_IDEMPOTENCY_CONFLICT` 409）。
2. **远端执行与单次提交**：`MediaGenerationWorkflowImpl` 的 `submit` 活动设置 `MaximumAttempts = 1`，绝不因网关超时而自动重复扣费。
3. **正常终态结算**：远端成功且至少部分产物写入对象存储并发布后，执行结算将 `reserved_units` 与 `balance_units` 等额核销，记录 `credit_reservation(SETTLED)` 与 `credit_ledger(SETTLE)`。
4. **失败退还与异常修复**：
   - 若提交前或执行明确失败/取消未产生计费产物，释放预占记录 `credit_ledger(RELEASE)`。
   - 若远端模型已生成成功但在拉取/上传至 RustFS 环节失败（`status = ARCHIVE_FAILED`），**禁止重新向模型网关发起生成**；运维或前端仅允许调用 `POST /api/v1/generation-tasks/{taskId}/retry-archive` 基于已有 `generation_attempt.remote_task_id` / 结果重新下载归档并完成交付。

---

## 3. Agent 上下文管理：无状态重放、推理重放与本地压缩

### 3.1 无状态请求重建与提示词缓存

VisionForge 不依赖模型服务商的有状态 Thread/Assistant 会话，而是调用 OpenAI 官方 Java SDK 的 `POST {baseUrl}/responses`（`responses().createStreaming()`），并显式配置：

- `.store(false)`：禁止网关侧保留对话上下文状态；
- `.include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT))`：请求返回加密推理载体用于后续无状态多轮工具重放；
- `.reasoning(...)`：当模型启用了非 `NONE` 的 `reasoningEffort` 时同时设置 `.summary(Reasoning.Summary.AUTO)`，确保加密推理模型返回可读的思维摘要；
- `.promptCacheKey(...)`：聊天轮次统一使用 `vf-chat:<conversationId>`，摘要压缩使用 `vf-compact:<conversationId>`，最大化前缀命中；
- `OpenAiResponsesModelClient` 设置 `.maxRetries(0)` 与 10 分钟单次超时，缓存最多 `32` 个 `OpenAIClient` 实例。

### 3.2 `ContextCompactor` 本地压缩与 `context_epoch`

当重建上下文超过阈值时，[`ContextCompactor`](../agent/src/main/java/app/pagefault/visionforge/agent/context/ContextCompactor.java) 触发 best-effort 本地压缩（配置项位于 `visionforge.context.*`）：

- **触发公式**：当 `reservedCost + latestSummaryLength + pendingMessagesCost > maxInputTokens * compactThreshold`（默认 `0.8`）且待压缩历史多于 1 条时执行压缩；保留尾部占比约 `compactKeepRatio`（默认 `0.25`）的最近消息原文，将较早连续消息区间压缩为固定六段模板（`[目标]`、`[约束]`、`[已确认]`、`[已否决]`、`[待解决]`、`[产物]`），最大输出 `1024` tokens（`CONTEXT_SUMMARY_MAX_OUTPUT_TOKENS`）。
- **只增不删 & 失败降级**：压缩结果持久化至 `context_summary`（按 `(conversation_id, epoch)` 递增），原始 `message` 表记录永不删除；若摘要模型调用失败则回退为历史裁剪，绝不阻塞主对话。
- **推理重放策略（`reasoning_replay`）**：每条 `agent_run_turn` 绑定产生时的 `context_epoch`。因为上游加密 `reasoning` 载体与输入前缀强绑定，**跨过压缩 epoch 后不会重放旧 epoch 的加密推理**；仅当前 epoch 内按模型配置的 `ENCRYPTED` / `ALL` / `NONE` 重放。

### 3.3 过程时间线视图 (`AgentProcessReader`)

向前端返回历史消息与执行记录时，`AgentProcessReader` 联合查询 `agent_run_turn` 与 `tool_invocation`，并通过 `ToolDisplayFormatter` 将 17 个工具名称（`load_skill`、6 个文件工具、2 个分镜工具、5 个媒体任务与资产检查工具、2 个视频合成工具及 `await_generation`）映射为简洁中文 `action` 与安全 `target`，附带可点击跳转至画布的 `fileId` 与 `taskId`。

---

## 4. 分镜 Schema v1 与视频合成流水线

### 4.1 分镜 JSON 校验规则 (`StoryboardValidator`)

类型为 `STORYBOARD` 的会话文件在创建、更新、Agent 工具 `save_storyboard` / `validate_storyboard` 及发起合成前统一经过 [`StoryboardValidator`](../agent/src/main/java/app/pagefault/visionforge/agent/storyboard/StoryboardValidator.java) 校验：

- **顶层必填**：`schemaVersion` 必须为整数 `1`、`title` 非空、`shots` 为非空数组（上限 `200` 个镜头）。
- **镜头字段约束**：
  - `id`：匹配 `[A-Za-z0-9_-]{1,64}`，且同一分镜内唯一；
  - `order`：`>= 1` 的唯一整数（排序与合成顺序依据）；
  - `durationSeconds`：`1` 到 `60` 秒之间的整数；
  - `firstFramePrompt` & `videoPrompt`：非空文本，各自长度 `<= 8000`；
  - `shotSize`（`EXTREME_WIDE | WIDE | FULL | MEDIUM | CLOSE | EXTREME_CLOSE`）、`camera`（`STATIC | PUSH | PULL | PAN | TRACK | FOLLOW | CRANE`）、`status`（`DRAFT | FIRST_FRAME_GENERATING | FIRST_FRAME_READY | VIDEO_GENERATING | VIDEO_CANDIDATES | APPROVED | LOCKED | FAILED`）必选；
  - `references[]` 中的 `role` 仅允许 `CHARACTER` / `PRODUCT` / `SCENE` / `STYLE`；
  - `videoCandidates[]` 中的 `taskId` 不能重复；`selectedVideo` 若设置则必须携带 `taskId`、`assetId`、`version >= 1`，且存在于非空 `videoCandidates` 中；
  - `usedRange` 若设置则需满足 `0 <= startSeconds < endSeconds <= durationSeconds`。

### 4.2 自动回写与 FFmpeg 成片

- **任务联动回写**：生成任务携带 `shotId`、`storyboardFileId`、`storyboardVersion` 完成后，系统自动更新镜头：首帧图写入 `shot.firstFrame`；视频生成追加至 `shot.videoCandidates` 并默认将首个成功视频设为 `selectedVideo`。
- **合成流水线 (`VideoCompositionWorkflowImpl`)**：
  1. 受理时按分镜 `order` 冻结所有镜头的 `selectedVideo`、对应 `asset_id` 与 `usedRange` 至 `composition_input`；
  2. `VALIDATING` 阶段调用 `ffprobe` 检查每个源视频真实时长是否足够覆盖 `[startSeconds, endSeconds]`；
  3. `COMPOSING` 使用 `FfmpegCommandBuilder` 构建统一尺寸/帧率/编码（`output_spec` 默认含 `width`、`height`、`fps`、`videoCodec`、`preset`、`crf`）的裁剪拼接命令；
  4. `UPLOADING` 通过 S3 Multipart Upload（默认分片 `8 MiB`，最小 `5 MiB`）流式上传至对象存储；
  5. `PUBLISHING` 创建 `Asset(DERIVED)` 与对应 `ConversationFileVersion` 关联。

---

## 5. 前端设计体系（Next.js 16 + Animate UI）

### 5.1 路由组职责隔离

前端路由位于 `frontend/src/app/`，通过 Next.js Route Groups 实现完全隔离的外壳与上下文树：

| 路由组 | 包含页面 | 守卫与壳组件 | 挂载的业务 Provider |
| --- | --- | --- | --- |
| `(auth)` | `/login`、`/register` | 无（公开页面） | 仅根级 `AppProviders` |
| `(studio)` | `/conversations`、`/conversations/[conversationId]`、`/tasks`、`/account` | `StudioAuthGuard` + `AppShell` | `ConversationsProvider` + `CreditProvider` |
| `(admin)` | `/admin`（运维总览）、`/admin/models`、`/admin/configs`、`/admin/alerts`、`/admin/users`、`/admin/billing`、`/admin/audit-logs` | `StudioAuthGuard` + `AdminRoleGuard` + `AdminShell` | **不挂载**会话与额度 Provider，彻底避免管理端界面加载创作者会话数据或混合导航 |

根路径 `/` 检查认证状态后自动导向 `/conversations` 或 `/login`；个人菜单组件 `SidebarUserMenu` 被 `AppShell` 与 `AdminShell` 复用，仅当 `user.role === "ADMIN"` 时在 Studio 个人菜单提供进入 `/admin` 的低干扰入口。

### 5.2 Animate UI 组件栈与全局动效约束

- 根组件 [`AppProviders`](../frontend/src/components/providers/app-providers.tsx) 使用 `<MotionConfig reducedMotion="user">` 包裹 `<AnimateUIProvider reducedMotion="user">`，确保系统级减弱动效偏好在整个应用中自动生效。
- `frontend/src/components/ui/*` 基于 **Animate UI**（`@animate-ui/react@2.4.6`、`motion@14.14.0` 与 `@base-ui/react`）构建，保留中性克制 OKLCH 色彩令牌（`globals.css`）：
  - `Sidebar` 系列采用 `@animate-ui/react/primitives/sidebar` 提供可滑动悬浮高亮与折叠动画；
  - `Tabs`（线型画布标签使用自定义 Base UI `TabsPrimitive.Indicator` 下划线，默认胶囊标签使用 Animate UI 自动滑块）、`Dialog`、`Sheet`、`AlertDialog`、`DropdownMenu`、`Select`、`Tooltip`、`Collapsible`、`Switch`、`Checkbox`、`Progress` 均使用 `@animate-ui/react/primitives/base-ui/*` 驱动过渡；
  - 对话执行过程组件 `AgentProcessTimeline`（直接与最终回答平级按轮次展示可独立折叠的「思考过程」思维链摘要、中间文本与工具调用芯片）与流式等待状态使用 `<ShimmeringText>`，Tool Chip 使用轻量 `motion` 位移入场，底部回到底部按钮使用 `<AnimatePresence>` 平滑显隐。

### 5.3 固定视口与悬浮胶囊输入框布局约束

多栏工作区（`StudioWorkspace` + `ChatPane` + `StudioCanvas`）采用严格的**非页面级滚动、局部独立滚动**设计：

- `AppShell` 与 `AdminShell` 的 `SidebarInset` 固定为 `flex h-svh min-w-0 flex-col overflow-hidden`；
- `react-resizable-panels` 的 `ResizablePanel` 容器必须显式保留 `style={{ overflow: "hidden", height: "100%" }}` 且内部包装 `flex h-full min-h-0 flex-col`；
- `ChatPane` 外层设为 `relative flex h-full min-h-0 flex-col overflow-hidden`，消息区为 `min-h-0 flex-1 overflow-y-auto` 并留足底部内边距（`pb-40`），使胶囊输入框通过 `absolute inset-x-0 bottom-0` 真正悬浮固定于面板底部而非被长对话推到页面末尾。

### 5.4 会话令牌与 SSE 消费客户端设计

- **单飞刷新（`token-store.ts` & `client.ts`）**：多并发请求同时发现 Token 即将在 `30s`（`REFRESH_SKEW_MS`）内过期或返回 401 时，共享同一个 `refreshInFlight` Promise；由于后端每次 `/auth/refresh` 会轮换 Refresh Token 并使旧令牌立即失效，该单飞锁是防止并发 401 雪崩的关键设计。同时监听浏览器 `storage` 事件实现多标签页会话状态同步。
- **基于 `fetch` Reader 的 SSE 解析（`sse.ts`）**：不使用原生 `EventSource`（因其无法设置 `Authorization: Bearer` 头与发送 JSON POST body），改为在 `consumeAgentStream` 中使用 `fetch` + `ReadableStream` + `TextDecoder` 逐块解析 SSE `event:` 与 `data:` 帧，并在发送前或 401 时兼容自动刷新。
