# VisionForge 技术方案提案

状态：架构讨论稿，作为后续实现依据，不代表相关能力已经实现。

更新日期：2026-11-18。

## 1. 产品定位与范围

VisionForge（幻影工坊）是以对话为主入口的图片与视频生成工作台。用户通过自然语言提出需求，主对话模型根据上下文选择工具，完成创意讨论、提示词优化、剧本编辑、素材选择、图片生成和视频生成。

首版面向个人用户，通过第三方接入 OpenAI Responses API 调用模型。首版保留工作空间、成员关系和工作空间存储库：工作空间是租户、权限、计费和存储归属边界，会话只绑定存储条目。多人分工、审核、自建模型和复杂作品编排在后续扩展，首版不实现。

核心目标：

- 主模型动态决定是否调用工具、调用顺序以及如何使用结果。
- 模型调用、工具执行和长时间生成任务能够追踪、恢复和核对费用。
- 保存完整用户消息，同时独立管理模型上下文和摘要。
- 将生成图片、视频及其来源关系长期保存为可复用资产。
- 支持用户修改、重新生成和复用已有作品，保留版本与执行记录。

首版不建设独立微服务体系，不引入 Kafka 或 CDC，不自建 GPU 推理平台，不预先实现完整多镜头剪辑系统。

## 2. 已确定的设计方向

| 项目 | 设计方向 |
| --- | --- |
| 主交互 | 对话式工作台，主模型负责动态规划和工具选择 |
| 创作能力 | 图片、视频、剧本、素材操作均作为工具暴露 |
| 创作存储 | 脚本、分镜、提示词、图片和视频进入工作空间存储库；会话只绑定条目，文件不归属于会话 |
| 工作流 | Agent Loop，不预设必须先写剧本、再生成图片、最后生成视频 |
| 模型接入 | 第三方接入 OpenAI Responses API（`POST /responses`）；优先采用 OpenAI 官方 Java SDK |
| 上游协议 | 请求上游时使用 HTTP SSE（`stream: true`）；不使用 Chat Completions、同步响应或 WebSocket；不使用 `previous_response_id` |
| 协议假设 | 按讨论约定，假设上游实现 OpenAI Responses API 的 HTTP SSE 事件和工具调用语义，不将上游实现差异作为本提案的前置问题 |
| 上下文压缩 | 不使用 Responses compact 接口；compact 是一次普通的 Responses API 模型调用，摘要由本地管理和持久化 |
| 持久化执行 | 优先采用 Temporal，实施前通过故障恢复验证 |
| 消息中间件 | 首版移除 Kafka、Debezium/CDC，也不引入替代 MQ |
| 流式输出 | 上游 Responses API 使用 HTTP SSE；Worker 通过 Redis pub/sub 发布增量，API 再以 HTTP SSE 推送给前端；持久化消息是事实来源，断线不重放 |
| 业务存储 | PostgreSQL |
| 临时数据 | Redis，用于缓存、限流和 SSE 增量分发 |
| 媒体存储 | RustFS 或其他经过验证的 S3 兼容对象存储 |
| 数据库演进 | Flyway 管理业务数据库迁移 |
| 身份与空间 | 用户与工作空间多对多；空间内 OWNER/ADMIN/MEMBER；登录后选择空间，access token 绑定工作空间 |
| 平台管理 | 系统 ADMIN 可跨空间执行任何操作，不需要加入空间；所有跨空间操作记录审计 |
| 后端布局 | Maven 模块保留在仓库根目录 |
| 部署布局 | Dockerfile、Compose 和部署环境模板保留在仓库根目录 |
| 前端 | frontend/ 中的 Next.js、TypeScript、Tailwind CSS 工程 |
| 许可方向 | 项目自有代码采用 Apache-2.0，第三方部分遵循其原始许可 |

模型角色暂按以下方式规划，实际模型 ID 通过配置映射：

- gpt-6-astra 等文本模型：主对话、创意与提示词完善、剧本创作、摘要生成。
- image-2、Seedream：图片生成或编辑能力。
- Seedance：视频生成能力。

上述名称表示产品接入目标，不作为对官方模型命名和能力范围的断言。参数和能力由模型目录配置。统一接入方式与 SDK 不意味着内部业务必须使用同一种生成操作；视频任务仍然需要明确的提交、查询、取消和产物获取语义。

## 3. Maven 模块调整

建议由原来的十个模块收敛为八个模块。API 与 Worker 是两个启动入口，其余为共享业务或资源模块。

| 模块 | 处理方式 | 职责 |
| --- | --- | --- |
| common | 保留 | 少量跨模块基础类型和错误定义，避免成为通用代码堆积区 |
| identity | 保留 | 用户、工作空间、成员和权限 |
| asset | 保留 | 工作空间存储库、上传下载、对象存储、媒体元数据、条目版本、资产引用及生命周期 |
| billing | 保留 | 模型用量、费用、额度预占、结算和流水 |
| agent | 调整后的核心业务模块 | 会话、上下文、动态工具调用、生成任务；通过 asset 读写工作空间存储条目 |
| api | 保留 | HTTP/SSE 入口、请求校验、认证接入和服务启动装配 |
| worker | 保留 | Temporal Worker 启动、Workflow/Activity 注册和执行资源配置 |
| database | 保留 | 业务数据库迁移脚本 |

原模块处理：

- bootstrap：删除独立模块，HTTP 服务启动职责归入 api。
- model：SDK 接入、模型能力和供应商适配合并到 agent 内部。
- generation：其任务和执行职责整合到 agent；生成任务的独立数据模型与生命周期继续保留。

目标布局：

```text
VisionForge/
├── pom.xml
├── common/
├── identity/
├── asset/
├── billing/
├── agent/
├── api/
├── worker/
├── database/
├── frontend/
├── docs/
├── Dockerfile
├── docker-compose.yaml
├── .dockerignore
└── .env.example
```

依赖约束：

- api、worker 负责装配业务模块，不能互相依赖。
- api 调用 agent 的公开应用服务，worker 注册 agent 提供的执行实现。
- agent 通过公开服务接口调用 identity、asset、billing。
- agent 通过 asset 的公开服务读写工作空间存储库；剧本和媒体不保存两份副本。
- identity、asset、billing 不反向依赖 agent，归属关联可以保存业务 ID。
- common 不依赖业务模块，不放 Web 启动配置或持久化实体。
- database 不依赖业务模块，迁移发布也不依赖 API 或 Worker 的启动。
- 跨模块不直接访问对方的 Mapper、Repository 或数据库实体。

agent 内部先采用包级划分，具体包结构可随实现调整：

```text
agent/
├── conversation/    # 会话、消息、分支
├── context/         # 上下文构建、快照和摘要
├── runtime/         # AgentRun、模型步骤、工具执行记录
├── tool/            # 工具定义、校验和分发
├── creative/        # 剧本、分镜、提示词工具；读写工作空间存储库，不维护独立文件副本
├── generation/      # 图片、视频生成任务
├── provider/        # SDK、模型能力、路由和上游调用
└── temporal/        # Workflow、Activity 及其实现
```

这些是职责边界，不要求每个包立刻建立完整分层。需要复用或独立演进时再拆 Maven 模块。

## 4. 核心业务对象

必须区分对话、单轮执行、创作作品和媒体生成任务：

| 对象 | 含义 |
| --- | --- |
| Conversation | 用户会话，生命周期可以长期持续 |
| Message | 用户或系统实际呈现的消息，支持多个内容块 |
| AgentRun | 一次用户请求或一次明确续跑的执行过程 |
| ModelInvocation | 一次实际模型调用及其参数、结果和用量 |
| ToolInvocation | 一个工具调用及其参数、执行状态和结果 |
| WorkspaceItem | 工作空间存储库中的脚本、提示词、分镜、图片或视频条目 |
| WorkspaceItemVersion | 存储条目的不可变版本，包含文本内容或媒体资产引用 |
| ConversationItem | 会话与存储条目的绑定关系，不改变条目归属 |
| GenerationTask | 一次图片或视频生成请求，有独立执行和结算状态 |
| GenerationAttempt | 某个生成任务的一次上游执行尝试 |
| Asset | 已上传或生成的图片、视频及衍生文件 |
| ContextSnapshot | 某次模型调用使用的上下文组成与版本 |

一个会话可以包含多次 AgentRun，一个 AgentRun 可以调用多个工具，一个工具也可以创建独立的长时间生成任务。AgentRun 结束不意味着生成任务必须结束。一个存储条目可以被多个会话绑定；会话删除只删除绑定，不删除存储条目。

## 5. 身份、工作空间与权限

本节确定首版的工作空间模型和登录后的空间上下文。首版仍面向个人用户，但成员关系按团队协作的完整语义建模，后续扩展不需要改表。

### 5.1 用户与工作空间

用户与工作空间是多对多关系，workspace_member 是带角色的成员关系，而不是简单关联表。

- 一个用户可以加入多个工作空间；一个工作空间可以包含多个用户。
- 每个工作空间恰好有一个 OWNER；OWNER 也是 workspace_member 中的一行，不另外保存 workspace.owner_user_id。
- workspace_member 使用独立 UUIDv7 主键，并以 UNIQUE (workspace_id, user_id) 保证成员唯一。不使用复合主键，避免与主键规范和 MyBatis-Plus 映射冲突。
- 通过 PostgreSQL 部分唯一索引保证每个空间只有一个 OWNER：`CREATE UNIQUE INDEX ... ON workspace_member (workspace_id) WHERE role = 'OWNER'`。
- 注册成功后自动创建个人工作空间（personal = true），注册者为 OWNER。新用户登录后不会出现无空间可用。
- 工作空间只归档，不物理删除：状态为 ACTIVE / ARCHIVED。归档后保留历史数据；恢复和彻底删除不在首版范围。
- 成员状态字段保留 ACTIVE；邀请未注册用户、INVITED/PENDING 流程不在首版实现。
- 工作空间同时拥有存储库，保存脚本、分镜、提示词、图片和视频等条目。条目归属工作空间，不归属会话；会话通过 `conversation_item` 绑定条目。首版个人空间只有一个成员，存储库由该用户使用；多人分工和审核在后续阶段扩展。

### 5.2 两级角色

系统角色与空间角色分开，不共用 claim。

| 层级 | 取值 | 作用 |
| --- | --- | --- |
| 系统角色 | ADMIN / USER | 平台级用户管理和系统配置 |
| 空间角色 | OWNER / ADMIN / MEMBER | 单个工作空间内的成员管理、内容访问和生成操作 |

权限矩阵：

| 操作 | OWNER | 空间 ADMIN | MEMBER | 平台 ADMIN |
| --- | --- | --- | --- | --- |
| 使用对话和生成 | ✓ | ✓ | ✓ | ✓（跨空间） |
| 查看工作空间存储库和空间级资产 | ✓ | ✓ | ✓ | ✓（跨空间） |
| 查看成员列表 | ✓ | ✓ | ✓ | ✓（跨空间） |
| 添加或移除成员 | ✓ | 仅 MEMBER | ✗ | ✓（跨空间） |
| 修改成员角色 | ✓ | 仅 MEMBER，不能授予 ADMIN | ✗ | ✓（跨空间） |
| 修改空间名称和描述 | ✓ | ✓ | ✗ | ✓（跨空间） |
| 归档空间 | ✓ | ✗ | ✗ | ✓（跨空间） |
| 转让所有权 | ✓ | ✗ | ✗ | ✓（跨空间） |

平台管理员可以访问任意工作空间并执行任何业务操作，不需要加入该空间。管理员权限来自系统角色 ADMIN，不通过提升空间角色实现。所有跨空间操作必须记录审计（操作者、目标空间、操作类型、traceId、时间）。

权限矩阵中的“查看本空间内容”指工作空间存储库和空间级资产，不自动等于查看其他成员的私人会话。会话可见性单独定义；首版个人空间无差异，多人协作阶段再确定共享和审核策略。

空间内约束：

- 空间角色操作中，OWNER 不能被移除、不能被降级，必须先转让所有权。
- 不能移除空间中的最后一个管理员（OWNER 或 ADMIN）。
- 平台管理员可以执行包括转让和移除在内的任何操作，不受成员关系限制。
- 每个空间必须始终有一个 OWNER；部分唯一索引保证至多一个，服务层在创建、转让和移除时保证至少一个。即使由平台管理员操作，移除最后一个 OWNER 前也必须先转让所有权。
- 首版只支持按邮箱添加已注册用户；邀请未注册用户、邀请链接和邮件发送后置。

### 5.3 登录与空间选择

采用“登录后选择空间，access token 绑定空间”的方案。

- access token 是空间级凭证，claims 包含 `sub`、`email`、`role`（系统角色）、`wsid`、`ws_role`、`iss`、`aud`、`iat`、`exp`、`jti`。
- refresh token 是用户级会话，不绑定单个空间；刷新时重新校验目标空间的成员关系并重新读取角色。成员被移除后，已签发 access token 在剩余有效期内仍可用，但下一次刷新会失败。

登录流程：

1. `POST /api/v1/auth/login` 校验邮箱密码，并加载该用户 ACTIVE 的成员关系。
2. 只有一个可用空间时，直接签发该空间的 TokenPair 并返回空间列表。
3. 有多个空间时，返回 5 分钟有效的 selectionToken 和空间列表，不签发正式 token。
4. `POST /api/v1/auth/workspace` 使用 selectionToken 和 workspaceId 换取空间级 access/refresh token。
5. 没有可用空间时返回明确错误；正常注册流程已自动创建个人空间，因此该状态只应出现在数据异常的账号上。

已登录用户通过 `POST /api/v1/auth/switch-workspace` 切换空间。服务端校验成员关系后签发新的 access token，refresh token 保持不变；因此多标签页切换互不影响。平台管理员切换空间时跳过成员关系校验，可以进入任意工作空间。刷新时客户端携带当前 workspaceId，服务端重新校验该用户仍是该空间的有效成员（平台管理员除外）。客户端记住上次使用的空间，但服务端不信任该记忆；URL 中体现当前空间，例如 `/w/{workspaceId}/...`。

隔离规则：

> 凡是路径中带 `{workspaceId}` 的接口，access token 的 `wsid` 必须等于路径中的 `workspaceId`，否则返回 403。

用户要操作另一个空间，必须先切换空间换取新 token，不能通过修改 URL 越权。业务查询必须同时带 `workspace_id` 条件，workspaceId 只能来自服务端 token 或服务端执行上下文，不能来自请求体、模型参数或工具参数。

### 5.4 平台管理员的跨空间操作

平台管理员（系统角色 ADMIN）可以访问任意工作空间并执行任何业务操作，不需要成为该空间成员：

- 管理接口允许列出全部工作空间并进入任意空间。
- 管理员进入目标空间时绕过成员关系校验；访问令牌包含 `platform_admin = true`，授权判断优先于空间角色。不把管理员伪装成 OWNER。
- 管理员可以查看内容、管理成员、修改空间、归档空间和转让所有权；操作范围不受空间角色限制。
- 所有跨空间操作记录审计，写操作必须留下操作者、目标空间、操作类型和 traceId。
- 每个空间必须始终有一个 OWNER；部分唯一索引保证至多一个，服务层保证至少一个。移除最后一个 OWNER 前必须先转让所有权。
- 平台管理员身份只由系统角色 ADMIN 决定，不通过加入空间或提升 `ws_role` 获得。

### 5.5 接口草案

认证：

```text
POST /api/v1/auth/login
POST /api/v1/auth/workspace
POST /api/v1/auth/switch-workspace
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
```

空间与成员：

```text
GET    /api/v1/workspaces
POST   /api/v1/workspaces
GET    /api/v1/workspaces/{workspaceId}
PATCH  /api/v1/workspaces/{workspaceId}
POST   /api/v1/workspaces/{workspaceId}/archive
POST   /api/v1/workspaces/{workspaceId}/transfer
GET    /api/v1/workspaces/{workspaceId}/members
POST   /api/v1/workspaces/{workspaceId}/members
PATCH  /api/v1/workspaces/{workspaceId}/members/{userId}
DELETE /api/v1/workspaces/{workspaceId}/members/{userId}
```

平台管理：

```text
GET /api/v1/admin/workspaces
GET /api/v1/admin/workspaces/{workspaceId}
```

错误码至少包括：工作空间不存在、无访问权限、需要 OWNER、空间已归档、成员已存在、成员不存在、不能移除 OWNER、必须保留 OWNER、非法空间角色。

### 5.6 数据模型

- workspace：id、name、description、status、personal、created_at、updated_at。删除 V1 中的 owner_user_id。
- workspace_member：id、workspace_id、user_id、role、status、joined_at、updated_at；UNIQUE (workspace_id, user_id)；部分唯一索引保证一个 OWNER。
- refresh_token：保持用户级，不增加 workspace_id；刷新时由请求携带并校验目标空间。
- 邀请表不在首版建立。

V1 已创建 workspace、workspace_member；上述调整通过新的 V3 迁移完成，不修改已发布的 V1。

### 5.7 审计与执行上下文

- TenantProvider 从 JWT 的 `wsid` 读取当前工作空间，替换现有的空实现，`@BizLog` 自动写入 workspace_id。
- 登录和空间选择事件在 token 签发前记录，workspace_id 为空或为目标空间，不伪装成空间内业务操作。
- 跨空间越权尝试记录安全审计。
- 平台管理员的跨空间进入和所有跨空间写操作记录审计。
- 后续 agent、asset、billing 的公开服务接口显式接收 workspaceId，由 API 层从 token 注入；Temporal Workflow 的启动参数携带 workspaceId，Activity 不信任模型输出中的空间标识。

### 5.8 实施顺序

1. V3 迁移：workspace 状态与个人空间标记、成员主键与状态、OWNER 唯一索引，移除 owner_user_id。
2. identity 的 workspace 包：实体、Mapper、WorkspaceService、WorkspaceAccessService、成员管理、单测。
3. Token 改造：空间 claims、登录选择、切换和刷新校验。
4. api 层：DTO、Controller、错误码、JwtTenantProvider、管理员空间接口。
5. 注册时自动创建个人空间。
6. 端到端验证：多空间选择、切换、跨空间越权、成员增删改、OWNER 保护、管理员跨空间操作、审计 workspace_id。

### 5.9 会话与存储库的边界

- 工作空间拥有存储条目；会话只绑定条目，不拥有文件。
- 会话默认由创建者使用；多人协作阶段再引入会话共享、条目审核和分工。
- 不能把“能访问工作空间”解释为“能查看所有会话”。会话可见性与存储条目可见性分别判断。
- 后续多人分工以存储条目和版本为协作边界，编剧、生成人员和审核人员不必共享同一个聊天上下文。

## 6. 动态 Agent Loop

主 Workflow 只约束可靠执行规则，不写死业务步骤。

```mermaid
flowchart TD
    A[用户消息] --> B[创建 AgentRun]
    B --> C[构建上下文]
    C --> D[调用主对话模型]
    D --> E{响应类型}
    E -->|工具调用| F[校验并记录调用]
    F --> G[执行工具或启动生成任务]
    G --> H[持久化工具结果]
    H --> D
    E -->|最终回复| I[保存消息并完成本轮]
    E -->|需要用户补充| J[保存问题并结束本轮等待下一轮]
```

示例路径：

- 创意讨论：直接回复，不生成媒体。
- 已有提示词：直接调用图片或视频工具。
- 修改剧本：读取存储条目的指定版本，修改部分内容并保存新版本。
- 图生视频：选择已有素材，直接提交视频任务。
- 完整短片：模型根据需求选择存储条目、参考图、分镜和生成工具。

工具操作的对象是工作空间存储条目。会话只维护当前绑定关系；同一剧本可以被多个会话引用，生成任务固定条目版本。

是否继续调用工具由模型输出和执行状态共同决定，不能仅以“本次没有工具调用”判断所有响应均已正常结束。响应不完整、调用超时和用户取消需要独立处理。

模型调用统一走 Responses API。每次调用由 Activity 显式构造完整 `input`：系统指令、历史消息、之前的 `function_call` 和 `function_call_output` 都在本次请求中重新提交。请求使用 HTTP SSE，读取 `response.output_text.delta`、`response.function_call_arguments.*`、`response.completed` 等事件；不使用同步响应、WebSocket 或 `previous_response_id`。助手文本来自 message 输出项，工具调用来自 function_call 输出项，工具结果以 function_call_output 回填后进入下一轮。

每轮记录提示词版本、工具定义版本、模型配置、上下文快照及调用结果。对于同一个会话分支，首版默认串行推进 AgentRun，避免并发回复覆盖状态；不同分支和不同会话可以独立执行。

前端可以增加图片、视频快捷入口，但它们应复用相同业务服务与任务语义，不另建一套生成规则。

## 7. 工具设计

工具是模型使用业务能力的接口，业务规则仍由服务端执行。

| 工具示例 | 职责 |
| --- | --- |
| list_items | 列出当前会话绑定的存储条目 |
| search_items | 按标题、类型或路径查询工作空间存储库 |
| read_item | 读取条目元数据、文本内容或指定版本；媒体返回资产信息 |
| create_item | 在工作空间存储库创建条目，并绑定当前会话 |
| update_item | 基于 base_version 修改条目，产生新版本 |
| bind_item / unbind_item | 管理会话与条目的绑定，不改变条目归属 |
| search_assets | 查询用户有权访问的素材 |
| inspect_asset | 获取媒体信息和可供模型使用的内容 |
| generate_image | 创建并执行图片生成任务 |
| generate_video | 创建并执行视频生成任务 |
| get_generation | 查询任务状态和产物 |
| cancel_generation | 请求取消生成任务 |

主模型可以直接生成剧本内容，通过 create_item 以 `kind = SCRIPT` 保存到工作空间存储库。`create_script`、`read_script`、`update_script` 可以作为基于条目的类型化封装，但底层必须复用同一套存储库和版本模型，不能建立第二套事实来源。工具不必再次调用同一个文本模型。

每个工具定义应包含参数 Schema、返回结构、副作用类型、权限要求、幂等策略、预算要求、超时策略，以及是否支持后台执行。

执行约束：

- 工具参数不直接构成授权；工作空间、会话和用户身份来自服务端执行上下文。
- 模型不能指定任意类名、SQL、代码或执行器；工具名映射到受控注册表。
- 条目路径必须在工作空间存储库根目录内，拒绝绝对路径和 `..`；文本大小、媒体类型和总存储量受限。
- 调用标识由 AgentRun、模型步骤和工具 call_id 等组成，重放同一调用不重复执行副作用。
- 重复的同一调用与用户明确要求“再生成一次”需要区分，后者创建新任务。
- 无数据依赖且无写入冲突的工具可以并行；同一存储条目修改必须携带 `base_version`，版本冲突作为结构化结果返回模型。
- 参数不合法、额度不足、权限不足等作为结构化结果处理，不进行无意义的基础设施重试。
- 技术异常先由执行层恢复；需要修改方案时，将可解释的失败交回模型。
- 限制调用轮数、总执行时间、并发数量和成本，避免无限循环。

工具返回必要的摘要、`item_id`、版本、`asset_id` 和绑定关系。完整剧本、媒体文件及大量结果保存在工作空间存储库或对象存储中，不放入模型上下文或 Temporal 历史。

## 8. Temporal 执行模型

优先采用 Temporal，Restate 等作为备选评估对象，不同时引入两套执行引擎。

### 8.1 Workflow 与 Activity 边界

- Workflow 管理执行顺序、分支、并行、持久化等待和取消传播。
- Activity 执行模型调用、数据库访问、文件传输和媒体处理等外部操作。
- 模型 Activity 通过 Responses API 发起 HTTP SSE 请求并读完整个流，再把完整文本、工具调用、用量和 `response_id` 返回 Workflow；Workflow 不直接解析供应商事件。
- 模型决策通过 Activity 返回并被记录；Workflow 根据已记录结果推进动态分支。
- 重放使用已完成调用的记录，不重新询问模型已决定过的步骤。
- 不在 Workflow 代码中直接访问网络、数据库或非确定性的外部状态。
- 不将整个 Agent Loop 放进一个大 Activity，以便按模型步骤和工具执行恢复。
- SDK 不能直接渗透进业务接口，供应商对象在 provider 层转换。

### 8.2 流程划分

| Workflow | 职责 |
| --- | --- |
| AgentRunWorkflow | 一轮对话或明确续跑的动态模型与工具调用循环 |
| ImageGenerationWorkflow | 图片生成、结果归档与费用处理 |
| VideoGenerationWorkflow | 视频提交、等待结果、归档与费用处理 |
| ContextCompactionWorkflow | 对固定范围的消息生成摘要并发布新上下文版本 |

作品级或多镜头 Workflow 后续按真实需求增加，不作为所有请求的必经流程。

视频工具内部可以有稳定步骤：提交、保存远端任务 ID、等待回调或 Timer、查询、归档、提取元数据、结算。工具内部的可靠执行顺序不限制主 Agent 的动态规划。

供应商等待使用持久化 Timer 或外部事件推进，避免一个 Activity 持续阻塞整个视频生成时长。文件传输和媒体处理等长 Activity 按需设置心跳与执行超时。

### 8.3 同步等待与后台执行

| 情况 | 执行方式 |
| --- | --- |
| 后续步骤依赖生成产物 | Workflow 持久化等待完成，将结果交回主模型 |
| 当前只需要启动任务 | 返回 task_id 和已受理状态，后台继续执行 |

后台生成采用独立生命周期的 Workflow，或对 Child Workflow 明确设置父流程结束策略。不能让对话完成意外终止已付费的视频任务。

后台任务完成后更新资产、任务卡片和消息。需要模型继续解释或创作时，创建新的续跑 AgentRun；已经完成的 AgentRun 不再等待后续 Signal。

工具返回“已受理”与“已完成”采用不同状态，模型不能把受理结果描述成生成成功。

### 8.4 重试、结果不确定和取消

Temporal 提供执行恢复，不自动保证外部副作用恰好一次。重点处理以下窗口：供应商已接单，但应用尚未保存远端任务 ID 就退出。

- 优先使用稳定的上游幂等标识；支持查询时先核对再决定是否重提。
- 无法确定结果时进入待核对状态，保留人工处理入口，不盲目重复付费请求。
- Activity 已持久化结果但尚未确认完成时，重试可以读取既有结果。
- 为付费提交显式配置 SDK 重试，避免与 Temporal 重试策略叠加。
- 归档失败只重试归档，不重新生成媒体。
- 用户取消需要记录申请、远端取消结果以及结算状态；本地超时不代表供应商未产生成本。
- 新采样或重新生成创建新的任务或执行尝试，保留与原任务的关联。
- 发布 Workflow 代码时评估历史重放兼容性，使用受控版本演进策略。

Workflow 历史只保存必要的状态与引用。对于过长的执行历史，后续使用适当的历史轮换策略，不将整段永久会话绑定到一个无限增长的 Workflow。

## 9. 移除 Kafka 与 CDC 后的可靠启动

首版不使用 Kafka、Debezium/CDC、RabbitMQ 或 NATS。Temporal Task Queue 承担 Workflow 和 Activity 的任务分发；Redis 也不作为业务生成任务的唯一可靠队列。

数据库提交与启动 Temporal 之间仍存在一致性窗口，因此保留轻量启动请求记录：

```text
事务保存用户消息、AgentRun 和 workflow_start_request
    → 提交数据库事务
    → 立即尝试启动 Temporal Workflow
    → 记录启动结果
```

启动补偿规则：

- Workflow ID 由业务执行 ID 派生，重复请求核对既有执行与参数。
- 消费或调用成功之前不将启动请求标记为已完成。
- worker 中的补偿任务处理尚未确认启动的记录，失败后退避再试。
- 数据库唯一约束和长期执行记录防止超出 Temporal 历史保留期后意外重新启动已完成业务。
- 补偿器只补齐启动动作，不轮询生成状态，也不替代 Temporal 的任务重试。
- 不为启动补偿单独创建 messaging、outbox 或 scheduler Maven 模块。

未来若出现搜索索引、数仓同步、多系统订阅和历史事件重放需求，再评估独立事件系统。届时根据需求选择 Kafka、RabbitMQ 或 NATS JetStream，不预先维护未使用的消息基础设施。

## 10. 存储职责与事实来源

| 存储 | 职责 |
| --- | --- |
| PostgreSQL | 用户消息、业务对象、工作空间存储条目元数据与文本版本、上下文索引、任务状态、资产元数据和账目 |
| RustFS / S3 | 媒体、衍生文件、大型不可变文本版本，以及确有需要外置的快照 |
| Temporal | 执行历史、步骤恢复和持久化等待 |
| Redis | 缓存、限流、短期流式数据与进度通知 |

业务查询以 PostgreSQL 为入口，执行恢复由 Temporal 管理。通过幂等 Activity 更新业务状态，提供异常对账机制；不能让数据库调度和 Temporal 各自推进同一段业务。

Redis 丢失不应导致完整消息、任务或额度账目丢失。媒体不以 Base64 长期存入 PostgreSQL、Redis 或 Temporal 历史。

## 11. 消息与协议记录

### 11.1 消息列表

建议建立 conversation、conversation_branch、message、message_part 等实体。

- conversation 属于工作空间，`created_by` 记录创建者；会话可见性与存储条目可见性分开判断。
- message 保存作者、顺序、分支、执行标识、状态和时间。
- message_part 支持文本、图片引用、视频引用和任务卡片。
- 编辑历史消息或重新生成回复形成新版本或分支，不覆盖原始记录。
- 后台任务完成可以更新关联卡片，或新增结果消息；变更需要可追踪。
- 列表使用稳定游标分页，避免把整段历史一次加载到客户端或模型。

### 11.2 协议层记录

前端可见消息与模型协议记录分开。除最终文本外，还需保留用于后续调用的工具请求、工具结果、响应阶段及其他必要输出项。

记录调用模型、参数、`response_id`、`request_id`、用量、执行尝试和错误分类。供应商响应应脱敏，大块媒体内容归档后转换为资产引用。

`response_id` 只用于查询、关联和排错，不能成为会话唯一存储，也不能用来隐式引用前文。每次调用都从持久化的消息、工具调用和工具结果显式构造完整 `input`；不使用 `previous_response_id`。加密或不透明的推理项只在协议要求的范围内保存和传递，不将其当作可读取的用户消息。

#### Responses API 调用约定

- 请求：`POST {baseUrl}/responses`，`stream: true`，`Accept: text/event-stream`。
- 请求体包含 `model`、显式 `input`、`max_output_tokens`、可选的 `tools`、`tool_choice` 和 `reasoning.effort`。
- `input` 是输出项数组：普通消息使用 `{role, content}`；历史工具调用使用 `{type: "function_call", call_id, name, arguments}`；工具结果使用 `{type: "function_call_output", call_id, output}`。
- 工具定义使用 Responses 格式：`{type: "function", name, description, parameters}`。
- 需要解析的 SSE 事件至少包括 `response.output_text.delta`、`response.output_item.added`、`response.output_item.done`、`response.function_call_arguments.delta`、`response.function_call_arguments.done`、`response.completed`、`response.failed` 和错误事件。
- `response.completed` 中的 `response.id` 记录为 `response_id`；后续请求仍然重新提交完整 `input`，绝不使用 `previous_response_id`。

### 11.3 流式输出

当前最小实现采用 HTTP SSE，入口为 `POST /api/v1/workspaces/{workspaceId}/conversations/{conversationId}/messages/stream`，响应类型为 `text/event-stream`。非流式消息接口保留，供不支持 SSE 或 Redis 暂时不可用的客户端轮询。

上游模型调用同样只使用 Responses API 的 HTTP SSE（`stream: true`），不调用同步响应接口，也不使用 WebSocket。下游 API 到浏览器使用 SSE，Worker 到 API 通过 Redis pub/sub 解耦；即使客户端使用非流式消息接口轮询，Worker 对上游仍然使用 SSE。

事件格式：

```text
event: accepted   data: {"type":"accepted","runId":"..."}
event: delta      data: {"type":"delta","content":"..."}
event: done       data: {"type":"done","assistantMessageId":"..."}
event: error      data: {"type":"error","message":"..."}
```

执行链路：

1. API 保存用户消息、AgentRun 和 workflow_start_request，并订阅该 run 的 Redis 频道。
2. Worker 的模型 Activity 将上游增量发布到 `visionforge:agent-run:{runId}`。
3. API 将 Redis 事件转换为 SSE 事件转发给客户端；收到 `done` 或 `error` 后关闭订阅。
4. Worker 持久化完整助手消息；SSE 只负责实时投递，不是业务事实来源。

约束：

- Redis pub/sub 是尽力而为，不缓存、不重放；客户端断线或 API 重启后，应重新读取消息和 AgentRun 状态，不能依赖补发增量。
- 当前事件只有 runId，没有 attempt_id 和顺序号；同一 run 的增量通过单线程订阅和同步分发保持顺序。需要断线续传时，再增加事件序号、短期缓存和 attempt_id。
- SSE 超时默认 5 分钟，可配置；超时只关闭推送，不取消 AgentRun 或已授权的生成任务。
- 浏览器不能使用 `EventSource` 携带 `Authorization` 头，应使用 `fetch` 流式读取。
- 工具进度、任务卡片和生成状态复用同一 SSE 通道扩展；当前最小实现只推送模型文本增量。
- 完整消息、工具调用和任务状态落 PostgreSQL；不逐 token 写业务表。
- 新尝试不能直接拼接到旧文本尾部；失败重试由新的 AgentRun 或明确的续跑产生。

## 12. 上下文构建与本地 compact

### 12.1 上下文组成

```text
本轮模型输入
  = 当前系统指令与工具定义
  + 用户确认的项目事实、绑定的存储条目版本和选定素材
  + 当前历史摘要
  + 最近完整消息及对应工具记录
  + 当前用户请求
```

完整消息回答“实际发生过什么”，模型上下文回答“这次调用需要什么”。二者不共用一个会被 compact 覆盖的内容字段。

角色设定、风格要求、存储条目版本、预算和用户确认结果独立保存，不依赖摘要重新推断。会话通过 `conversation_item` 绑定条目；上下文只引用条目 ID 和版本，不复制文件内容。资产标识必须在调用前解析为上游实际可使用的输入内容或访问方式。

每次调用生成不可变 context_snapshot，记录会话分支、消息范围、摘要版本、存储条目版本、资产版本、系统指令和工具定义版本。可追溯并不意味着再次运行模型会产生相同输出。

### 12.2 摘要生成流程

不使用 Responses compact 接口。compact 是一次普通的 Responses API 调用（HTTP SSE），由应用生成并持久化结构化摘要：

1. 计算输入预算，包括文本、媒体和工具定义，并预留输出及后续工具调用空间。
2. 在接近预算前触发摘要；初始阈值可以从可用预算的 70%～80% 试验，后续依据实际效果调整。
3. 固定需要压缩的消息区间，避免截断未完成的工具调用和结果对。
4. 从该区间生成摘要，保留用户目标、约束、确认决定、否定方案、待解决问题和来源标识。
5. 将关键约束与结构化业务记录核对，记录摘要模型、参数和提示词版本。
6. 通过版本校验发布新的上下文版本；摘要期间到达的新消息仍保留在摘要范围之后。
7. 保存原始消息和协议记录，compact 不负责删除历史。

建议 context_summary 保存 from_seq、to_seq、source_message_ids、源范围校验信息、模型、版本及生成时间。摘要不得把未经用户确认的模型建议提升为已确认事实。

### 12.3 压缩后的上下文边界

文本摘要是有损信息压缩，不使用 Responses compact 接口。

- 建立新的 context_epoch，从摘要和保留的消息重新构造输入。
- 完全不使用 `previous_response_id`；每次调用都显式提交摘要、保留消息和当前请求，避免重复上下文或版本混用。
- 不复用依赖旧前缀的加密推理项；旧协议记录可以继续用于排错。
- 新 epoch 内尽量追加新记录，减少无必要的前缀重写。
- 摘要以带来源的历史资料加入上下文，不作为新的系统权限或预算指令。
- 避免无限执行“摘要的摘要”；需要时从原始记录重建，并始终保留独立的项目事实。

首版先使用明确引用和数据库查询找回历史事实，不默认引入向量数据库。出现跨大量历史内容的语义检索需求后再评估。

## 13. 工作空间存储库与媒体资产

### 13.1 工作空间存储库

工作空间拥有存储库，脚本、分镜、提示词、图片、视频和衍生文件都是存储库条目。条目归属工作空间，不归属会话。

- 会话通过 `conversation_item` 绑定条目；同一个条目可以被多个会话引用，不复制文件。
- 会话删除只删除绑定，不删除存储条目；条目删除前检查绑定、活跃生成任务和审核引用。
- 条目使用不可变版本；更新产生新版本，当前版本由 `current_version` 指向。
- `pinned_version` 用于生成任务和审核快照；为空表示跟随最新版本。
- 条目记录 `created_by`、`origin_conversation_id` 和 `created_at`；版本记录 `source`、`created_by` 和 `tool_invocation_id`，用于追溯来源。`origin_conversation_id` 只表示首次来源，不构成访问控制；来源会话删除时置空，不级联删除条目。
- 文本内容保存在 `workspace_item_version.content`；图片、视频等二进制通过 `asset_id` 或对象存储键引用，不把 Base64 存入业务表、Redis 或 Temporal 历史。

建议实体：

```text
workspace_item：id、workspace_id、kind、title、path、mime_type、storage_kind、
                current_version、status、visibility、created_by、
                origin_conversation_id、created_at、updated_at、deleted_at
workspace_item_version：id、item_id、version、content、asset_id、object_key、
                        size_bytes、checksum、source、created_by、
                        tool_invocation_id、created_at
conversation_item：id、workspace_id、conversation_id、item_id、role、
                   pinned_version、added_by、added_at
```

`kind` 至少包括 SCRIPT、STORYBOARD、PROMPT、IMAGE、VIDEO、OTHER。`role` 至少包括 WORKING、ATTACHMENT、REFERENCE、OUTPUT。约束至少包括 `UNIQUE (workspace_id, path)`（未删除条目）、`UNIQUE (item_id, version)` 和 `UNIQUE (conversation_id, item_id)`。

接口草案：

```text
GET    /api/v1/workspaces/{workspaceId}/items
POST   /api/v1/workspaces/{workspaceId}/items
GET    /api/v1/workspaces/{workspaceId}/items/{itemId}
PATCH  /api/v1/workspaces/{workspaceId}/items/{itemId}
GET    /api/v1/workspaces/{workspaceId}/items/{itemId}/versions
POST   /api/v1/workspaces/{workspaceId}/conversations/{conversationId}/items
DELETE /api/v1/workspaces/{workspaceId}/conversations/{conversationId}/items/{itemId}
```

所有接口的 `workspaceId` 必须与 access token 的 `wsid` 一致；会话绑定接口还要校验会话可见性和条目可见性。

### 13.2 剧本、提示词和分镜

剧本、提示词和分镜不再单独建立一套文件存储，作为存储条目的类型表达：

- 剧本可以是 Markdown 或结构化 JSON；具体格式由工具和前端决定，不改变条目和版本模型。
- 每个版本记录来源消息、父版本、创建者和工具调用标识。
- 生成任务保存提交时冻结的条目版本、实际提示词、参数、引用素材、价格规则和授权预算；后续修改条目不改变已提交任务。
- 用户原始输入、优化后内容和实际提交参数都要保存。
- `create_script`、`read_script`、`update_script` 可以作为类型化工具封装，但底层必须复用 `workspace_item` / `workspace_item_version`。

### 13.3 媒体资产与对象存储

`asset` 负责二进制载荷和媒体元数据，`workspace_item` 负责库内条目：

- asset 记录工作空间、存储后端、bucket、object_key、类型、大小、校验信息、宽高、时长、来源任务和状态。
- 原始素材、生成结果、缩略图和封面分别保存为资产或明确的资产变体。
- 每次新生成创建新资产，不覆盖旧结果。
- 条目版本引用 `asset_id`，消息、剧本和生成任务引用条目版本或资产 ID，不把临时访问 URL 当作永久标识。
- 对象键由后端分配，文件名和存储路径不由模型或客户端直接控制。

### 13.4 上传、写入与结果归档

用户上传：创建上传会话、签发授权、客户端直传、服务端验证对象、创建 `workspace_item` 并写入初始版本；二进制文件同时创建 `asset`，如适用则绑定当前会话。

Agent 写入：`create_item` 在工作空间存储库创建条目并写入版本，同时绑定当前会话；`update_item` 基于 `base_version` 创建新版本，不原地覆盖。

生成结果归档：创建待归档记录、获取供应商结果、写入对象存储、校验元数据、将资产标为 READY、创建或更新条目版本，再完成交付与结算。

对象存储写入与数据库提交不是同一个事务，需要恢复机制：

- 已写文件但数据库未确认：根据稳定资产标识和校验信息完成确认或清理。
- 已有记录但对象缺失：标记异常并进入修复。
- 同一归档尝试重试：识别已保存对象；新的生成尝试使用新的资产标识。
- 供应商临时产物尽快归档，不作为唯一长期副本。

### 13.5 访问、可见性与生命周期

- 存储条目按工作空间隔离；所有查询必须带 `workspace_id`，该值来自服务端执行上下文。
- `conversation_item` 只表示会话上下文，不单独授予条目访问权。条目可见性由 `visibility` 和工作空间角色决定；会话可见性单独定义。
- 首版个人空间只有一个成员，可见性差异不会暴露；建议草稿默认 `PRIVATE`，显式共享或提交审核后改为 `WORKSPACE`。多人协作阶段再落地角色、审核和通知。
- 私有对象通过业务鉴权和短期授权访问；提供给供应商的素材必须可从其网络访问，授权有效期覆盖实际读取时间。
- 下载上游产物时限制来源、重定向、响应大小和处理时间，防止归档过程访问内部网络。
- 图片、视频格式和大小需要真实验证；媒体处理放入受控执行环境。
- 用户删除消息不直接删除共享条目；删除前检查会话绑定、生成任务和审核引用。
- 临时上传、孤儿对象和未完成分片有明确清理规则；正式资产按产品保留策略处理。
- 活跃 Workflow 引用的条目版本、资产和上下文快照不能被提前清理。
- 对象存储生命周期能力、预签名上传、分片和视频 Range 访问需要在目标 RustFS 版本验证。

### 13.6 后续多人分工

多人协作以工作空间存储库中的条目和版本为边界，不以共享聊天为前提：

- 编剧创建剧本条目，生成人员按固定版本制作图片或视频，审核人员对条目版本批准或驳回。
- 审核人只需要访问条目，不需要读取他人的私人会话。
- 后续可增加条目/项目级角色、评论、审批、任务分配和通知；首版不实现。
- 不把“工作空间成员”直接等同于“可以查看所有会话”；会话共享需要单独的产品决定。

## 14. 额度与成本

billing 独立于 Agent 的自然语言判断。

- 额度归属工作空间；首版个人空间即个人额度，多人协作阶段支持按成员和条目归因。
- 普通对话、摘要和媒体生成分别记录用量及供应商成本。
- 创建 AgentRun 时不提前按一次图片或视频任务预占费用。
- 执行付费工具前，根据实际模型、参数和用户授权预占额度。
- 持续记录 AgentRun 总预算和已使用金额，模型无法自行提高预算。
- 预占、扣费、释放和流水在数据库事务中完成，并使用唯一业务标识防重。
- 金额使用定点小数，额度使用明确的最小计量单位。
- 保存执行时的价格版本，后续调价不改变历史账目。
- 用户计费与供应商成本分别记录，允许表达“产生上游成本但最终交付失败”。

取消、超时和部分成功的收费规则需要在实现前明确。不能把网络超时或本地流程退出直接解释为无成本并立即释放全部额度。

## 15. 数据库迁移与候选实体

业务迁移脚本集中放在 database/src/main/resources/db/migration，采用统一版本序列。发布时由独立 migration Job 执行 Flyway，成功后部署 API 与 Worker。

- 已发布的版本化脚本不修改，结构修正通过新迁移完成。
- 避免同时使用其他自动建表机制维护结构。
- 发布变更尽量兼容旧应用，大规模数据回填独立执行。
- Temporal 自身表结构与业务迁移分开管理，采用其适用的升级流程。

候选实体如下，具体表名与拆分数量在详细设计阶段确定：

| 领域 | 候选实体 |
| --- | --- |
| 身份 | user_account、workspace、workspace_member |
| 会话 | conversation、conversation_branch、message、message_part |
| 工作空间存储 | workspace_item、workspace_item_version、conversation_item |
| Agent | agent_run、model_invocation、tool_invocation、workflow_start_request |
| 上下文 | context_snapshot、context_summary |
| 创作 | 剧本、分镜和提示词由 workspace_item 的 kind 表达，不单列 creative_document |
| 生成 | generation_task、generation_attempt、generation_output |
| 模型配置 | model_definition、provider_config、model_price_rule |
| 资产 | asset、asset_variant、asset_reference、upload_session |
| 费用 | credit_account、credit_reservation、credit_ledger、usage_record |
| 审计 | audit_log |

高频查询字段采用明确列，供应商扩展参数和脱敏快照可使用 JSONB。工作空间隔离、幂等和结算约束必须落实到服务端查询与数据库约束。`workspace_item` 是库内条目，`asset` 是二进制载荷；`conversation_item` 只建立绑定，不改变条目归属。

## 16. 部署、观测与许可

首版按 API、Worker、前端三个应用角色部署，使用 PostgreSQL、Redis、RustFS 和 Temporal。Temporal 的持久化与业务库逻辑隔离，资源规模根据压测确定。

配置及密钥由部署环境提供，日志脱敏。日志、指标和执行记录通过 conversation_id、agent_run_id、tool_invocation_id、generation_task_id 与 Workflow ID 关联。

重点观察模型调用成功率、工具失败分类、生成等待时间、待核对请求、结果归档失败、未结算任务、启动补偿积压、费用和存储增长。内测前完成数据库与对象存储的备份恢复验证。

前端保持 Next.js、TypeScript、Tailwind CSS，后续按需接入 shadcn/ui 和动画组件。项目自有代码沿用 Apache-2.0；第三方组件保留来源和许可，不能因为根目录存在 Apache-2.0 就统一变更其授权。

讨论中发现 Animate UI 的许可包含附加条件。引入具体版本时核对实际许可，不按纯 MIT 假设处理；这项选型尚未通过当前前端骨架落地。

## 17. 开发阶段与验收

| 阶段 | 主要交付 | 验收重点 |
| --- | --- | --- |
| 1. 基础工程 | 八模块边界、数据库迁移、身份和工作空间、Temporal 基础环境 | API/Worker 可启动，迁移可从空库执行 |
| 2. 对话与工具循环 | 消息持久化、上下文快照、Responses API HTTP SSE 调用、工作空间存储库、文件与剧本工具、动态分发 | 模型能直接回复或多轮调用工具；上游只使用 Responses API SSE；进程恢复不重复创建条目版本 |
| 3. 图片闭环 | 素材上传、图片工具、费用预占、生成与归档 | 重复请求不重复扣费，产物可以查看和下载 |
| 4. 上下文压缩 | 摘要、上下文版本、来源追溯和预算控制 | 压缩不丢失关键确认信息，不删除历史消息 |
| 5. 视频闭环 | 视频工具、长任务等待、取消、封面和后台完成通知 | AgentRun 结束后生成可继续，归档失败单独恢复 |
| 6. 内测准备 | 运营排查、故障演练、成本对账、压测与备份恢复 | 达到约定负载和恢复目标 |

实施前或实施过程中必须覆盖的故障场景：

1. 保存用户请求后、启动 Workflow 前退出，补偿能够恢复启动。
2. 同一个启动请求重复执行，最终只有一次有效业务执行。
3. 供应商接单后、本地保存远端 ID 前退出，进入核对而非盲目重复提交。
4. Activity 完成业务写入后中断，重试不重复修改存储条目或扣费。
5. 视频回调重复、乱序或缺失，任务能够正确推进。
6. 供应商成功但对象存储暂时不可用，仅恢复归档步骤。
7. 多个任务竞争有限额度，不出现超额消费。
8. Redis 重启后，消息、任务与账目仍可恢复查询。
9. 摘要期间收到新消息，新消息不会丢失或被错误纳入旧摘要。
10. 浏览器断线、AgentRun 结束或用户取消时，后台任务与费用状态符合明确规则。
11. Workflow 代码升级后，已有执行仍可恢复。
12. 用户尝试访问其他工作空间的消息、任务和素材时被拒绝。
13. 平台管理员可以跨空间执行任何操作，且所有跨空间操作被记录审计。
14. 同一工具调用重放不重复创建 `workspace_item_version`。
15. 并发更新同一存储条目时，`base_version` 冲突被拒绝，不静默覆盖。
16. 删除会话只删除 `conversation_item` 绑定，不删除工作空间存储条目。

## 18. 当前实现差距与后续决策

截至 2026-11-18，仓库已落地八模块布局、根 POM 依赖与插件版本（Java 21、Spring Boot 4.1.2 BOM、Temporal Java SDK 1.41.1 BOM）、Maven Wrapper 3.9.16、API/Worker 最小启动入口和 Temporal 自检 Workflow。Flyway 迁移已建立 identity 表（user_account、workspace、workspace_member、refresh_token、audit_log）；Compose 提供 PostgreSQL 和 Redis。

已实现：

- common：统一响应、错误码、UUIDv7、自研 `@BizLog`。
- identity：bcrypt 密码、JWT access/refresh、RBAC（ADMIN/USER）、工作空间与成员管理（见第 5 节）、登录选择空间、access token 绑定空间、refresh 校验成员关系、注册自动创建个人空间、成员增删改、空间更新归档、所有权转让、平台管理员跨空间操作和审计。
- agent：模型配置（baseUrl、加密 apiKey、模型 ID、reasoning effort、最大输入/输出）、conversation/message/agent_run/model_invocation 落库、`AgentRunWorkflow` 当前仍使用 `/chat/completions` 完成文本回复；该实现与目标 Responses API 不一致，待迁移。Worker 运行时并发/缓存/优雅停机和启动补偿已实现。
- asset：工作空间存储库已实现文本条目、不可变版本、会话绑定和可见性过滤；提供创建、读取、更新、版本列表和绑定接口，更新使用 `baseVersion` 检测并发冲突，单条文本上限 1 MB。V5 迁移创建 `workspace_item`、`workspace_item_version`、`conversation_item`。
- agent 工具注册表：已实现 `ToolDefinition`、`AgentTool`、`ToolRegistry`、`AgentToolExecutor` 和 `tool_invocation` 幂等记录；注册 `list_items`、`search_items`、`read_item`、`create_item`、`update_item`、`list_item_versions`、`bind_item`、`unbind_item` 八个工作空间工具。工具参数只包含条目 ID、路径和内容，工作空间/会话/用户来自服务端执行上下文。
- 流式：`POST /messages/stream` 已按 SSE 返回 accepted/delta/done/error；Worker 通过 Redis pub/sub 发布模型增量，助手消息以数据库为准；非流式接口保留。

尚未实现：

- Responses API + HTTP SSE 上游迁移；当前 provider 仍使用 Chat Completions，且保留了同步 `complete` 路径。
- Responses `function_call` / `function_call_output` 解析、工具调用和多步工具循环；工具注册表已就绪，但 AgentRunWorkflow 仍只调用一次模型并返回文本。
- 会话创建者/可见性过滤。当前对话列表按 workspace 查询，空间成员可以读取同空间会话；这与第 5 节、第 11 节的边界尚未对齐。
- 工具调用循环、上下文压缩、真实媒体生成、审核和多人分工。
- Dockerfile 仍为占位；Compose 尚未纳入 Temporal 和 RustFS。

需要通过实现验证或产品决策确定的事项：

- 工作空间存储条目的默认可见性、草稿与共享/审核边界。
- 会话默认私有还是空间共享；多人协作阶段是否引入 `conversation_member`。
- 剧本采用 Markdown、结构化 JSON 或两者兼有。
- Temporal 的部署方式与资源策略；SDK 与 Spring Boot 的版本组合已在根 POM 确定。
- 工具后台执行、自动续跑、取消及部分成功的交互与计费语义。
- 模型的实际能力配置、费用规则和不同错误的恢复策略。
- 摘要质量标准、上下文预算、历史和媒体的保留规则。
- 第三方 UI 组件的具体版本与许可范围。

上游对 OpenAI Responses API 的实现程度按已约定前提处理。目标协议已确定为 OpenAI Responses API + HTTP SSE：不使用 Chat Completions、同步上游响应、WebSocket、`previous_response_id` 或 Responses compact；compact 始终是一次普通的模型调用。这里不使用“OpenAI 兼容网关”这一说法，因为它通常指 Chat Completions 兼容，而本项目目标是 Responses API。Kafka 和 CDC 不作为上述待办的前置依赖。不能将本提案当作实现完成说明。
