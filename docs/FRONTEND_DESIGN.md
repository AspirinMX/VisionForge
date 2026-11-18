# VisionForge（幻影工坊）前端设计方案与开发计划

更新日期：2026-11-18。状态：已确认设计方向，待按阶段实施。

> 本设计稿基于 `docs/PROPOSAL.md`、`docs/STANDARDS.md`、`docs/MEDIA_PIPELINE_DESIGN.md`、`docs/PRODUCT_ROADMAP.md`、`frontend/AGENTS.md`，以及 `api/`、`agent/`、`asset/`、`billing/`、`frontend/` 当前实际代码编制。实现期间若补充或调整接口，应同步维护本文档。

---

## 1. 业务定位与本次边界约定

VisionForge（幻影工坊）是以**对话为主入口、以分镜为组织核心、支持多模态素材输入与多镜头顺序成片**的图片与视频创作工作台。

### 1.1 已确认的前端决策

1. **后端不改、缺失读接口前端占位**：本次专注前端交付，不修改后端 Java 代码。对尚未暴露 HTTP 接口的只读能力（普通用户模型列表、额度余额流水、会话资产列表、成片任务列表、消息附图记录、活跃 AgentRun 查询），前端采用**本地隔离缓存 + 明确占位提示**兜底，并预留清晰接口适配层。
2. **根路径 `/` 留空待用，直接按鉴权分流**：`/` 暂不建设 Landing Page（预留给后续营销落地页），当前访问 `/` 时未登录重定向至 `/login`，已登录重定向至 `/conversations`。
3. **登录 / 注册采用单卡居中布局**：`/login` 与 `/register` 使用纯正居中的单张卡片表单，不采用左右分栏介绍布局。
4. **对话页面采用 Gemini / ChatGPT 风格交互拓扑**：
   - 默认打开为**左侧可折叠历史侧栏 + 中央克制宽度的对话主列 + 底部悬浮圆角多模态输入框**；
   - 当用户需要查看、编辑或制作剧本、分镜、素材快捷生成、任务状态或成片导出时，可在右侧按需展开 **Studio Canvas（工作区画布面板）**，形成类似 Gemini Canvas / ChatGPT Canvas 的左对话、右创作面板协作体验；面板关闭时立即恢复纯净的对话视野。

### 1.2 核心业务边界（与后端严格对齐）

- **用户与会话隔离**：无工作空间实体、无 `/w/{workspaceId}` 路径、无空间切换。所有文件、上传资产、生成任务和成片均归属单个会话与当前登录用户；越权或不存在统一呈现为 `404`。
- **对话驱动与五步成片**：默认通过对话与主模型协作完成“剧本（`SCRIPT` Markdown）→ 分镜（`STORYBOARD` JSON）→ 分镜首帧图 → 分镜视频候选 → FFmpeg 顺序成片”，同时也支持在 Canvas 面板内直达单图生成、图生视频、剧本/分镜修订与成片合成。
- **数据格式与版本不可变**：剧本与分镜每次更新都必须携带 `baseVersion` 乐观锁；`videoCandidates` 只追加不覆盖；成片冻结提交时刻的分镜版本、`selectedVideo` 与 `usedRange`。
- **任务与计费语义严谨**：`ARCHIVE_FAILED` 只引导「重试归档」（不重复生成、不重复扣费）；`UNKNOWN`（待核账）禁止自动重提；浏览器断开 SSE 或刷新页面不取消后台已受理的 `AgentRun`、`MediaGenerationWorkflow` 或 `VideoCompositionWorkflow`。

---

## 2. 技术底座与硬性开发规范

| 项目 | 选型与约束 |
| --- | --- |
| 框架 | Next.js `16.4.6`（App Router、`cacheComponents: true`、`partialPrefetching: true`、Turbopack）、React `19.3.0`、TypeScript `5` |
| 样式与组件 | Tailwind CSS `4.3.3` + **强制 `shadcn`（固定 `--preset b27GcrRo`，仅通过 `pnpm dlx` 获取）** |
| 组件禁令（`frontend/AGENTS.md`） | 禁止引入任何其他 UI 组件库（含 Animate UI）；禁止自建通用基础组件库；凡 `shadcn` 能表达的交互必须使用对应组件及组合；Tailwind 仅用于布局、间距、响应式和组件适配 |
| Next.js 16 兼容 | 启用 `cacheComponents: true` 后，动态数据读取与鉴权态必须放在 `<Suspense>` 边界或客户端鉴权层内，避免顶层 `layout.tsx` 读取请求态阻塞静态壳预渲染 |
| 接口代理与流式 | 通过 `frontend/next.config.ts` 的 `rewrites()` 将 `/api/v1/:path*` 转发至后端（默认 `http://127.0.0.1:8080/api/v1/:path*`），同源消除浏览器 CORS 问题同时保持 `POST /messages/stream` 的原生流式透传；S3 预签名 PUT/GET 直连对象存储 |
| 令牌与流式协议 | Access Token 存于内存（辅以受控会话持久化策略），遇到 `401` 自动串行调用 `POST /api/v1/auth/refresh` 轮换刷新并重放请求；SSE 必须使用 `fetch` + `ReadableStream` 携带 `Authorization: Bearer` 读取，禁用 `EventSource` |
| 统一错误与校验 | 解包 `ApiResponse<T>`（`code` / `message` / `data` / `traceId` / `timestamp`）；`400 COMMON_INVALID_ARGUMENT` 将 `data` 中的 `{ field: message }` 映射回各表单字段一次性展示；系统错误展示可读提示并附可复制 `traceId` |

---

## 3. 后端现状核对与前端占位策略（重要）

经逐一核对 `api/` 现有控制器、DTO 与服务实现，以下 6 项在本次**不修改后端**的前提下采用明确的占位与兼容方案：

| 编号 | 后端代码现状 | 前端占位 / 兼容方案 | 真实联调边界说明 |
| --- | --- | --- | --- |
| 1 | `AdminModelController`（`/api/v1/admin/models`）仅限 `ROLE_ADMIN`，暂无普通用户的模型列表接口 | 快捷生成表单提交 `POST /api/v1/generation-tasks` 时不强制传 `modelDefinitionId`（后端自动选用该能力类型的默认启用模型）；界面展示「使用系统默认启用模型」说明与可选高级「指定模型 UUID」输入框；价格预估区标明“待接入公开模型定价接口，实际扣费以服务端预占为准” | 管理员账号在 `/admin/models` 可正常调用全量模型接口；普通用户切勿调用 `/api/v1/admin/**` |
| 2 | `CreditService.balance(userId)` 尚未暴露 HTTP 查询接口 | 侧栏底部与 `/account` 页面展示**额度占位卡片**（说明新账户初始额度按服务端 `BILLING_INITIAL_CREDIT_UNITS` 配置，默认 `10000` 单位，当前前端展示“实时余额查询待接口接入”占位态）；当提交生成任务返回额度不足错误（如 `BILLING_INSUFFICIENT_CREDIT`）时如实拦截提示 | 不虚构实时扣减数字或假流水账单，仅在本地记录本次浏览器会话已发起的任务 `priceUnits` 作为参考 |
| 3 | 无 `GET /api/v1/conversations/{id}/assets` 列表接口；`ConversationFileVersionResponse` 暂未包含 `assetId` | 1) 直传 `complete` 成功后将 `AssetResponse` 写入本地隔离缓存 `vf_assets:{userId}:{conversationId}`，保证同浏览器刷新不丢；2) 通过 `GET /api/v1/generation-tasks?conversationId=` 收集所有 `SUCCEEDED` 产物的 `assetId` 与 `fileId` 映射；3) 预览文件库中的 `IMAGE`/`VIDEO` 文件时优先匹配任务产物中的 `assetId` 获取 `download-url`，同时提供手动输入 `assetId` 挂载预览的兜底入口 | 跨设备刷新后，仅上传过但未关联任何生成任务的原始文件无法从服务端重新列举（待后端补接口后无缝替换） |
| 4 | `CompositionTaskController` 缺少 `GET /api/v1/composition-tasks?conversationId=` 列表接口；`ConversationController` 缺少 `DELETE` 删除接口 | 1) 发起成片或重试成片时将 `taskId` 持久化到本地缓存 `vf_compositions:{userId}:{conversationId}`，进入工作台时自动并发拉取 `GET /api/v1/composition-tasks/{id}` 刷新最新阶段，并提供粘贴 `taskId` 手动恢复追踪；2) 侧栏不渲染虚假的会话删除按钮 | 已提交过的成片任务可通过 `taskId` 查询、取消与重试，全部真实调用后端 |
| 5 | `MessageResponse` 暂不返回 `message_part`；缺少按会话查活跃 `AgentRun` 的列表接口 | 1) 发送带附图消息成功后，本地按 `userMessageId` 记录附带的 `assetIds` 以便刷新时回显附件缩略图；2) 发送消息获得 `runId` 后写入本地活跃运行表，刷新页面若发现未终态 `runId` 则继续轮询 `GET /api/v1/agent-runs/{runId}` 至 `COMPLETED`/`FAILED` 后刷新消息列表；3) 若发送时触发 `409 AGENT_CONVERSATION_BUSY`，提示用户当前会话仍在后台推进中并提供一键刷新轮询 | 跨设备登录无法自动看到历史消息上的附件角标（占位局限明确标注） |
| 6 | `AgentRunStreamService` 当前推送 `accepted` / `reasoning` / `reasoning_summary` / `delta` / `done` / `error`，不含工具调用进度事件 | 对话流实时呈现思考过程与正文；在流式结束后及右侧操作期间，按受控间隔轮询会话文件列表、`GenerationTask` 列表与 `CompositionTask` 详情，确保工具后台创建的剧本、分镜和生成任务能自动刷新上屏 | 不伪造虚构的工具进度条，以真实持久化的文件版本与任务状态为准 |

---

## 4. 页面路由规划（做哪些页面）

共规划 **9 个页面路由**，全部置于 `frontend/src/app/` App Router 结构下：

| 路由 | 页面名称 | 权限要求 | 核心用途 |
| --- | --- | --- | --- |
| `/` | 根分流入口 | 公开 | 暂不做 Landing Page；仅判定鉴权状态，未登录跳 `/login`，已登录跳 `/conversations` |
| `/login` | 登录页 | 公开 | 居中单卡登录表单 |
| `/register` | 注册页 | 公开 | 居中单卡注册表单 |
| `/conversations` | 对话起始页（Gemini 新对话首页） | 已登录 | 共享左侧边栏 + 中央空白起始对话台（问候语、起手式提示芯片、居中多模态输入框） |
| `/conversations/[conversationId]` | **核心对话工作台（Chat + Canvas）** | 已登录（会话本人） | **核心主页面**：左侧共享历史侧栏 + 中央 Gemini/ChatGPT 对话流 + 右侧按需展开的 Studio Canvas 四合一面版（分镜与成片 / 剧本与文件 / 素材与生成 / 任务中心） |
| `/tasks` | 全局任务中心 | 已登录 | 共享左侧边栏 + 跨会话生成任务管理台（筛选、取消、重试归档、跳回所属会话） |
| `/account` | 账户与额度信息页 | 已登录 | 共享左侧边栏 + 个人资料（`/auth/me`）、额度占位与本地统计卡片、退出登录 |
| `/admin/models` | 模型配置运维台 | 仅 `ADMIN` | 共享左侧边栏 + 按 `CHAT`/`IMAGE`/`VIDEO` 分类管理模型、协议、执行模式、单价与默认模型 |
| `/admin/users` | 用户与角色管理台 | 仅 `ADMIN` | 共享左侧边栏 + 分页用户列表与 `USER`/`ADMIN` 角色切换 |
| `not-found.tsx` | 404 资源不可达页 | 全局 | 会话/任务不存在或无权访问（404）时展示统一安全提示与返回入口 |

---

## 5. 各页面功能与布局详设（有哪些功能、有怎样的布局）

### 5.1 共享应用壳层（`(studio)` Layout Shell）

应用于 `/conversations`、`/conversations/[conversationId]`、`/tasks`、`/account`、`/admin/*`。

#### 布局结构（桌面端）

```text
+-----------------------+---------------------------------------------------------------------+
| [VF] VisionForge  [≡] |  Top Header Bar (48px)                                              |
|-----------------------|---------------------------------------------------------------------|
| [+ 新建对话]           |                                                                     |
| [🔍 搜索会话...]       |                                                                     |
|                       |                                                                     |
| 今天                   |                     Page Content Area                               |
|  > 橘猫第一次出门短片   |        (在 /conversations/[id] 中分为：居中的 Chat 主区             |
|  > 咖啡海报视觉方案     |         + 右侧可折叠展开的 Studio Canvas 工作区面板)                 |
|                       |                                                                     |
| 更早                   |                                                                     |
|  > 科幻城市概念测试     |                                                                     |
|                       |                                                                     |
|-----------------------|                                                                     |
| ☰ 全局任务中心 (/tasks) |                                                                     |
| ◈ 账户与额度 (/account) |                                                                     |
| ⚙ 模型配置 (仅ADMIN)   |                                                                     |
| ⚙ 用户管理 (仅ADMIN)   |                                                                     |
| [头像] 昵称  [退出]     |                                                                     |
+-----------------------+---------------------------------------------------------------------+
```

- **响应式断点**：
  - `>= 1024px`：侧栏使用 `shadcn` `Sidebar`（`264px` 宽），支持顶部按钮或快捷键 `Ctrl/Cmd + B` 折叠为图标模式或完全收起。
  - `< 1024px`：侧栏自动切换为抽屉（`Sheet`），通过顶栏左上角菜单图标呼出，不挤压主内容。
- **视觉定位**：中性低噪声工作台色彩层级（侧栏略深/微冷中性层，主对话画布高可读纯净底色），状态色严格语义化（进行中青蓝、成功翠绿、待核账/部分成功琥珀、失败/拒绝红），状态切换过渡控制在 `150–250ms`。

---

### 5.2 `/login` 与 `/register`（居中单卡登录/注册）

#### 布局结构

```text
+-----------------------------------------------------------------------------+
|                                                                             |
|                                                                             |
|                      +-------------------------------+                      |
|                      |       [VF] VisionForge        |                      |
|                      |    对话驱动的 AI 视频与图像工坊   |                      |
|                      |-------------------------------|                      |
|                      |  邮箱                          |                      |
|                      |  [ name@example.com        ]  |                      |
|                      |  密码                          |                      |
|                      |  [ ••••••••••••            ]  |                      |
|                      |  [!] 后端错误/字段校验提示区     |                      |
|                      |  [         登 录         ]     |                      |
|                      |-------------------------------|                      |
|                      |  还没有账号？ 去注册 ->         |                      |
|                      +-------------------------------+                      |
|                                                                             |
|                                                                             |
+-----------------------------------------------------------------------------+
```

- **容器规范**：全视口 `min-h-screen` 居中弹性布局（`flex items-center justify-center p-4`），卡片采用 `shadcn` `Card`（`w-full max-w-[400px]`），顶部居中放置极简品牌徽标与名称，无左右分栏、无营销横幅、无虚假统计数据。
- **核心功能**：
  - **登录（`/login`）**：邮箱、密码（`Input` + 密码显隐切换），提交调用 `POST /api/v1/auth/login`；失败时在 `Alert` 中展示服务端 `message`（统一 401 提示不区分账号/密码错误）及 `traceId`；成功写入令牌与用户信息并跳入 `/conversations`。
  - **注册（`/register`）**：邮箱、显示名（最多 100 字符）、密码（实时提示必须 12–72 个字符）、确认密码；提交调用 `POST /api/v1/auth/register`，支持 `400 COMMON_INVALID_ARGUMENT` 按字段名精准渲染内联报错，处理 `409 IDENTITY_EMAIL_ALREADY_REGISTERED`；注册成功后自动调用登录或平滑跳回 `/login` 带出邮箱。

---

### 5.3 `/conversations`（Gemini / ChatGPT 风格新对话起始页）

#### 布局结构

```text
+------------------+--------------------------------------------------------------------------+
| Sidebar (264px)  | Top Bar: [≡]  新对话                           [额度占位] [个人菜单]    |
|                  |--------------------------------------------------------------------------|
|                  |                                                                          |
|                  |                     你好，准备创作什么作品？                                |
|                  |            一句话描述创意，或直接上传参考图开始制作。                          |
|                  |                                                                          |
|                  |     +----------------------------------------------------------+     |
|                  |     | [已选参考图缩略条，最多8张]                                 |     |
|                  |     | 输入你想生成的短片、分镜或图片需求...                        |     |
|                  |     |                                                          |     |
|                  |     | [+ 附图(本地上传)]  [快捷生图/生视频]           [发送 ↑]  |     |
|                  |     +----------------------------------------------------------+     |
|                  |                                                                          |
|                  |     [🎬 一句话生成短片]  [🖼 参考图海报]  [🎞 首帧生视频]  [📝 写分场剧本]  |
|                  |                                                                          |
+------------------+--------------------------------------------------------------------------+
```

- **核心功能**：
  - 用户直接在此输入首句话（或先选本地图片，前端自动先调用 `POST /api/v1/conversations` 创建新会话 -> 若有附件则上传图片并 `complete` -> 跳转 `/conversations/[newId]` 并立即发起首轮流式对话 `POST /messages/stream`），完全复刻 Gemini / ChatGPT「打开即输入，输入即建会话」的零阻力体验。
  - 点击下方 4 个起手式提示芯片（Prompt Chips），直接填充结构化高质量开场提示词到输入框供用户微调或直接发送。

---

### 5.4 `/conversations/[conversationId]`（核心工作台：Chat + Studio Canvas）

这是应用的核心页面，具备**「纯净对话模式」与「Chat + Canvas 分屏协作模式」**两种平滑切换形态：

#### 形态 A：默认纯净对话布局（Canvas 收起时）

```text
+------------------+--------------------------------------------------------------------------+
| Sidebar (264px)  | [≡]  橘猫第一次出门短片          [打开 Studio Canvas ◧] [任务Badge] [额度] |
|                  |--------------------------------------------------------------------------|
|                  |              +--------------------------------------------+              |
|                  |              | (仅运行中显示) Agent 正在执行本轮创作...       |              |
|                  |              +--------------------------------------------+              |
|                  |                                                                          |
|                  |              +--------------------------------------------+              |
|                  |              | [用户头像] 帮我写一个橘猫第一次出门的15秒短片 |              |
|                  |              |           [附图缩略图1] [附图缩略图2]         |              |
|                  |              +--------------------------------------------+              |
|                  |              | [VF] ▸ 思考过程 (已完成 / 流式展开中...)       |              |
|                  |              |      好的，我将先为您创作剧本与分镜...      |              |
|                  |              |      [📄 查看/编辑剧本] [🎞 打开分镜看板]   |              |
|                  |              +--------------------------------------------+              |
|                  |                                                                          |
|                  |     +----------------------------------------------------------+     |
|                  |     | [已附图片 x2 (可移除)]                                     |     |
|                  |     | 继续对话，例如“把第二镜改成特写并重做视频”...               |     |
|                  |     | [+ 上传附图] [≡ 选会话已有图] [⚡快捷面板]        [发送 ↑]  |     |
|                  |     +----------------------------------------------------------+     |
|                  |     * 附图仅参与本轮调用；长任务在后台独立执行，刷新页面不会取消。          |
+------------------+--------------------------------------------------------------------------+
```

#### 形态 B：Canvas 展开协作布局（类似 Gemini Canvas / ChatGPT Canvas）

```text
+--------+-----------------------------------+------------------------------------------------+
|SideBar | [≡] 橘猫第一次出门短片             | Studio Canvas   [⤢ 全屏] [✕ 关闭Canvas]         |
|(可折叠)|-----------------------------------+------------------------------------------------+
|        |                                   | Tabs: [分镜与成片] [剧本与文件] [素材生成] [任务]  |
|        |  LEFT: 对话主列 (默认 44%)         +------------------------------------------------+
|        |  (max-w-full, 自适应窄列排版)      |                                                |
|        |                                   | RIGHT: 工作区工作室面板 (默认 56%, 可拖拽调宽)   |
|        |  • 消息时间流 (ScrollArea)         |                                                |
|        |  • 思考过程折叠块 (reasoning)       |   Tab1 分镜与成片：成片导出条 + 镜头序列卡片/JSON |
|        |  • Markdown 助手回复               |   Tab2 剧本与文件：目录 + Markdown编辑 + 版本对比|
|        |  • 关联动作链接(一键定位右侧Tab)    |   Tab3 素材与生成：直传 RustFS + 快捷生图/视频   |
|        |                                   |   Tab4 任务中心：Generation/Composition 状态操作 |
|        |-----------------------------------|                                                |
|        |  底部多模态输入控制台 (最多8图)     |                                                |
+--------+-----------------------------------+------------------------------------------------+
```

- **布局与响应式适配**：
  - **桌面端（`>= 1024px`）**：使用 `shadcn` `ResizablePanelGroup` + `ResizablePanel` + `ResizableHandle` 实现左 Chat（建议默认 44%，最小 30%）+ 右 Canvas（默认 56%，最小 38%）；点击 Canvas 顶部「全屏（`⤢`）」可将工作区扩大至 100% 主区宽度（方便对比多段 16:9 视频候选或精细调剪辑区间），再次点击还原分屏。
  - **平板 / 移动端（`< 1024px`）**：主视图保持全宽对话；打开 Canvas 时通过顶部 Segmented Tabs 或全屏滑出 `Sheet` 切换至对应工作室子视图，避免双栏挤压导致输入框和分镜卡片不可用。

#### 左侧对话主列功能详设

1. **流式消费与思考过程渲染**：
   - 使用 `fetch` 发起 `POST /api/v1/conversations/{conversationId}/messages/stream`（`Accept: text/event-stream`），逐事件解析 `accepted` / `reasoning` / `reasoning_summary` / `delta` / `done` / `error`。
   - 渲染独立的「思考过程（`Collapsible`）」区块，区分明文推理（`reasoning`）与加密推理摘要（`reasoning_summary`）；正文到达期间流式渲染 Markdown。
   - 提供「使用非流式提交」切换开关（针对 Redis 不可用场景回退 `POST /messages` + 轮询 `GET /api/v1/agent-runs/{runId}`）。
2. **后台运行态恢复与防并发冲突**：
   - 当会话中存在正在进行的 `AgentRun`（如正在执行 `await_generation` 持久化等待图片/视频交付）时，顶部展示非阻断状态条（`RUNNING / PENDING`），禁用重复发送（避免触发 `409 CONVERSATION_BUSY`）并自动轮询至终态。
   - 若服务端返回 `409`，明确告知“上一轮执行尚在进行中（含生成任务等待），请稍候或查看任务中心”。
3. **多模态图片附件（上限 8 张）**：
   - 支持拖拽本地图片到输入框、从本机选图直接走 S3 预签名上传流程、或从当前会话已准备好的 `IMAGE` 资产中选取；展示缩略图队列及已选数量 `n/8`，支持一键移除。
4. **对话 ↔ Canvas 上下文快捷联动**：
   - 对话消息末尾可附带“打开最新剧本 / 查看最新分镜 / 查看本会话任务”快捷入口按钮（点击自动打开右侧 Canvas 并定位到对应 Tab 与文件）。
   - 在 Canvas 中点击某镜头的「让 Agent 修改此镜」或某张图片的「带此图提问」，会自动把该图片 `assetId` 或镜头编号描述填入左侧输入框。

#### 右侧 Studio Canvas 四大 Tab 功能详设

##### Tab 1：分镜与成片（Storyboard & Composition）

1. **顶部元信息与操作栏**：
   - 若有多个 `STORYBOARD` 文件支持下拉切换（`Select`）；若暂无分镜，展示引导空状态（提供“在对话中让助手创建分镜”提示 + “手动新建空白标准分镜”按钮）。
   - 显示全局元信息：`title`、`aspectRatio`、`language`、`style`、当前版本号 `vN`（点击打开版本历史）。
   - 模式切换：`[可视化分镜看板]` / `[JSON 源码编辑器]`。
   - 操作按钮：`校验分镜`（本地按 `StoryboardValidator` 规则预检：`schemaVersion=1`、`shots[]` 非空且<=200、`shotId` 正则 `[A-Za-z0-9_-]{1,64}` 唯一、`order` 唯一、`durationSeconds` 1..60、`firstFramePrompt`/`videoPrompt` 必填、候选与 `usedRange` 合法）、`保存新版本`（带 `baseVersion` 调用 `PATCH`，`409` 时弹窗对比最新服务端版本）、`合成成片`。
2. **顺序成片进度与播放追溯区**：
   - 点击「合成成片」打开 `Sheet`：校验全部分镜是否均已选定 `selectedVideo`（未选齐时清晰列出缺片镜头 `shotId`），配置输出规格（`width: 1280`、`height: 720`、`fps: 30`、`videoCodec: h264`、`crf: 23`、`preset: veryfast`），提交 `POST /api/v1/composition-tasks`。
   - 存在已提交的成片任务时，顶部展示阶段步进器：`PENDING → VALIDATING → COMPOSING → UPLOADING → PUBLISHING → SUCCEEDED`，支持进行中「取消合成」与失败/取消后「使用冻结输入重试」。
   - 成片 `SUCCEEDED` 后展示原生 HTML5 `<video>` 播放器（调用 `GET /api/v1/assets/{outputAssetId}/download-url` 获取短时预签名 GET URL 并支持过期自动刷新）、下载按钮，以及**镜头追溯明细表**（展示 `compositionTask.inputs[]` 中每镜的 `shotId`、`generationTaskId`、`assetId`、`[startSeconds, endSeconds]`）。
3. **分镜镜头列表（按 `order` 排序）**：
   - 每张镜头 `Card` 包含：
     - 头部：`#order`、`shotId`、`sceneId`、时长 `durationSeconds`s、景别 `shotSize`（`EXTREME_WIDE`..`EXTREME_CLOSE`）、运镜 `camera`（`STATIC`..`CRANE`）、状态 `status` 徽章、`LOCKED` 锁定开关（锁定状态下禁止本地编辑与批量重做）。
     - 首帧与选中视频区：`firstFrame` 图片预览（点击放大预览）与当前 `selectedVideo` 标记、裁剪区间 `[startSeconds, endSeconds]` 标签。
     - **双提示词严格分离区**：分别展示/编辑 `visual`、`firstFramePrompt`（标注“仅首帧生图”）、`videoPrompt`（标注“仅视频生成”），以及折叠的 `dialogue` / `voiceOver` / `sound` / `references`（`CHARACTER` / `PRODUCT` / `SCENE` / `STYLE`）。
     - **单镜直达操作栏**：
       - `生成首帧`：打开快捷生图预填（自动带入 `firstFramePrompt`、`references`、`shotId`、`storyboardFileId`、`storyboardVersion`）。
       - `生成视频候选`：打开快捷生视频预填（自动带入 `videoPrompt`、`firstFrame.assetId` 作为 `FIRST_FRAME`、`shotId`、`storyboardFileId`、`storyboardVersion`）。
       - `候选对比与选片 (${videoCandidates.length})`：打开 `Sheet` 对比播放该镜头所有候选片段，切换 `selectedVideo`，修改 `usedRange` 起止秒数，点击“应用并保存新版本”。

##### Tab 2：剧本与文件（Scripts & Workspace Files）

- **布局**：上方/左侧文件列表（按 `全部 / SCRIPT / STORYBOARD / PROMPT / IMAGE / VIDEO / OTHER` 筛选 + 搜索 `GET /files/search` +「新建文件」弹窗），右侧阅读与编辑主区。
- **功能**：
  - 文本文件（`SCRIPT`、`PROMPT`、`STORYBOARD`、`OTHER`）：支持 Markdown 渲染预览 / 源码编辑切换；提供标准 `SCRIPT` Markdown 模板一键插入；保存时带 `baseVersion` 调用 `PATCH`，若遇 `409 FILE_VERSION_CONFLICT` 展示版本差异提示（拉取服务端最新内容与当前草稿并排比较）。
  - 媒体文件（`IMAGE`、`VIDEO`）：展示元数据、来源及对应产物预览/下载（`assetId` 通过关联生成任务输出或本地上传映射解析）。
  - 版本历史抽屉：拉取 `GET /files/{id}/versions`，点击任一历史版本（`GET /files/{id}/versions/{version}`）只读查看或“基于此版本内容创建新版本”。

##### Tab 3：素材与快捷生成（Assets & Quick Generate）

- **上半区：S3 预签名直传与会话素材库**
  - 拖拽/选择文件上传（校验图片/视频/文档 MIME 与大小）-> `POST /uploads` -> 直传 `PUT` 至 `uploadUrl`（带 `requiredHeaders` 与上传进度）-> `POST /uploads/{sessionId}/complete` 标记 `READY`。
  - 资产卡片网格：通过 `download-url` 获取临时签名地址渲染缩略图与播放器，支持一键「附加到对话」「设为生图参考图」「设为视频首帧」「下载原文件」「复制 assetId」。
- **下半区：快捷生图 / 生视频面板（复用 `POST /api/v1/generation-tasks`）**
  - 模式切换：`图片生成 (IMAGE)` / `视频生成 (VIDEO)`。
  - 提示词输入 + 可选绑定分镜与镜头（`storyboardFileId` + `shotId`）。
  - 参数区：提供结构化常用参数（如图片 `size` / `quality` / `outputFormat`；视频 `resolution` / `ratio` / `durationSeconds` / `generateAudio` / `watermark` / `seed`）+ 可展开的 JSON 高级参数编辑与校验；明确拦截当前适配器不支持的能力组合提示（如 `OPENAI_IMAGES` 暂不支持 `MASK` 局部重绘）。
  - 输入素材绑定（最多 8 个）：按类型限制可选 `role`（图片：`REFERENCE`/`INIT_IMAGE`/`MASK`；视频：`FIRST_FRAME`/`LAST_FRAME`/`REFERENCE`）。
  - 模型与防重：模型默认留空由后端路由默认启用模型（也可在高级选项中填入指定 `modelDefinitionId`）；每次全新提交或「再生成」自动生成新 `Idempotency-Key`（重试上次未发出请求复用同 Key）。

##### Tab 4：会话任务中心（Conversation Tasks）

- 筛选条：`全部` / `进行中` / `待归档修复 (ARCHIVE_FAILED)` / `待核账 (UNKNOWN)` / `已交付 (SUCCEEDED)` / `已失败或取消`。
- 列表展示当前会话的 `GenerationTask`（来自 `GET /api/v1/generation-tasks?conversationId=`）与本地追踪的 `CompositionTask` 详情（来自 `GET /api/v1/composition-tasks/{id}`）。
- 清晰展示 4 维状态标签：`status`、`deliveryStatus`、`billingStatus`、`cancelStatus` 及 `providerStatus`、`priceUnits`、关联 `shotId`。
- 精准操作规则：
  - `PENDING / SUBMITTING / RUNNING` → 「请求取消」（`POST /cancel`）。
  - `ARCHIVE_FAILED` → 「重试归档」（`POST /retry-archive`，明确提示不重新调用模型、不重复扣费）。
  - `UNKNOWN` → **禁止重发生成请求**，展示待核账说明与 `taskId` 复制。
  - `SUCCEEDED` → 预览/跳转输出资产，并提供「同参再生成（携带 `sourceTaskId`）」。

---

### 5.5 `/tasks`（全局任务中心）与 `/account`（个人账户页）

- **`/tasks` 全局任务中心**：
  - 布局：继承共享 `AppShell`，右侧为全宽工作台：顶部状态与类型筛选器 + `shadcn` `Table`（列：任务类型、关联会话、关联镜头、执行状态、交付状态、账务状态、预估/消耗额度、创建时间、操作）；点击任意行滑出右侧 `Sheet` 展示完整参数、输入素材、输出资产预览及操作按钮（取消 / 重试归档 / 跳转回所属 `/conversations/[conversationId]`）。
- **`/account` 个人账户与额度信息**：
  - 布局：继承共享 `AppShell`，右侧居中单列卡片流（`max-w-3xl`）：
    1. 基本信息卡（`/api/v1/auth/me`：`displayName`、`email`、`role`、`status`、`createdAt`）。
    2. 额度与用量占位卡（明确标注待后端补充 `/billing/balance` 后展示真实可用/冻结额度与账本流水；附带当前前端会话已知任务的 `priceUnits` 汇总视图）。
    3. 会话安全卡（调用 `POST /api/v1/auth/logout` 安全退出当前登录态）。

---

### 5.6 `/admin/models` 与 `/admin/users`（系统运维后台，仅 `ADMIN`）

- **`/admin/models` 模型与协议配置台**：
  - 布局：继承共享 `AppShell`，顶部能力分组 Tabs（`全部` / `CHAT` / `IMAGE` / `VIDEO`）+ 右侧「+ 新增模型」按钮；主体 `Table` 展示显示名、Model ID、能力类型、协议（`OPENAI_RESPONSES` / `OPENAI_IMAGES` / `ARK_VIDEO_TASKS` / `STUB` 等）、执行模式（`INLINE_RESULT` / `REMOTE_TASK`）、`priceUnits`、图片输入支持、API Key 已配置状态、启用开关与默认标记。
  - 编辑/创建使用右侧 `Sheet` 表单，严格同步 `ModelDefinitionService` 校验规则（`CHAT` 强制 token 上限与 `OPENAI_RESPONSES + INLINE_RESULT`、`IMAGE`/`VIDEO` 过滤对应允许协议、`defaultModel` 不允许与 `enabled=false` 共存、编辑留空 `apiKey` 保留原密钥）。
- **`/admin/users` 用户与角色管理台**：
  - 布局：继承共享 `AppShell`，标准分页表格（`GET /api/v1/admin/users?page=1&size=20`）+ 页码控制器 + 行内角色变更下拉触发 `AlertDialog` 二次确认后调用 `PUT /api/v1/admin/users/{id}/role`。

---

## 6. 实施阶段与验收标准

| 阶段 | 主要交付 | 验收要点 |
| --- | --- | --- |
| **Phase 0：底座初始化** | 通过 `pnpm dlx shadcn@latest init --preset b27GcrRo` 初始化样式与基础组件；配置 `next.config.ts` `/api/v1/*` 代理；实现 `ApiResponse` 解包客户端、Token 内存态与并发 401 refresh 队列、全字段校验映射、统一 Toast/Alert 报错（含 `traceId`） | `shadcn` preset 完整无手工仿写；`pnpm --dir frontend lint/build` 通过；`cacheComponents: true` 构建无错误 |
| **Phase 1：鉴权与壳层** | `/` 自动分流、居中单卡 `/login` 与 `/register`、`not-found.tsx`、共享 `AppShell`（Gemini/ChatGPT 风格侧栏 + `/conversations` 起始页）、`/account`、`/admin/models`、`/admin/users` | 登录/注册全字段校验映射、Token 自动刷新、ADMIN/USER 权限路由隔离 |
| **Phase 2：Gemini 式对话与文件工作区** | `/conversations/[conversationId]` 居中对话主列、`fetch` SSE 流式解析（`reasoning` / `reasoning_summary` / `delta` / `done` / `error`）、非流式降级与刷新后 `AgentRun` 轮询恢复、最多 8 图多模态附件直传、Canvas 抽屉展开与「剧本与文件」读写/乐观锁冲突处理 | SSE 流式与断线恢复正常；`409` 文件版本冲突正确提示对比；RustFS 直传跑通 |
| **Phase 3：分镜 Canvas、快捷生成、任务中心与成片** | Canvas「分镜与成片」（可视化镜头卡片 + JSON 源码切换 + 双提示词编辑 + 单镜生图/视频快捷入口 + 多视频候选并排对比选片与 `usedRange` 调节 + 成片合成提交/取消/重试/播放追溯）、「素材与快捷生成」、「任务中心」（Canvas 内 + 全局 `/tasks`） | 完整跑通上传参考图、对话/快捷生成、候选对比选片、顺序成片播放下载、`ARCHIVE_FAILED` 重试归档与 `UNKNOWN` 防重提 |
| **Phase 4：视觉验收与文档同步** | 骨架屏/空态/错误态细节打磨；桌面端（1440px / 1280px）与移动端（390px）实机检查与问题修复；全量 `lint` / `build` 校验 | `pnpm --dir frontend lint` 与 `pnpm --dir frontend build` 全绿；前后端联调边界清晰标注（不把占位或离线 `STUB` 冒充真实网关联调） |
