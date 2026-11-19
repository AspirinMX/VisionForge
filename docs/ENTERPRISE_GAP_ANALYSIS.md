# VisionForge 企业级生产缺口分析与演进路线

本文档基于对 VisionForge 后端（`common`、`identity`、`asset`、`billing`、`agent`、`api`、`worker`、`database`）与前端（`frontend/`）现状的代码级审计，梳理距离企业级生产系统尚存的核心缺口，并给出逐项落地方案与原子化提交计划。

---

## 一、核心基础设施与配置中心缺口

### G1. 引入 Nacos 作为企业级动态配置中心
- **现状问题**：当前所有运行期参数（`BILLING_INITIAL_CREDIT_UNITS`、上传尺寸限制、S3 Presign TTL、`CONTEXT_COMPACT_THRESHOLD`、`STREAM_TIMEOUT_MS`、合成超时与并发、限流阈值、告警阈值与邮件参数等）静态写入 `application.yaml` 与环境变量，修改必须重启 API 与 Worker。
- **目标方案**：
  - **基础设施**：在 [`docker-compose.yaml`](../docker-compose.yaml) 与 [`docker-compose.prod.yaml`](../docker-compose.prod.yaml) 中集成 Nacos Server（standalone 模式，开放 8848/9848，显式配置 `NACOS_AUTH_TOKEN` 等必填认证参数）。
  - **Java 客户端解耦集成**：由于后端采用 Spring Boot `4.1.2`，直接引入官方纯 Java `com.alibaba.nacos:nacos-client` 构建轻量配置适配模块，通过 `ConfigService` 实现 API 与 Worker 双端动态拉取、长轮询热监听与优雅降级（本地未启用 `NACOS_ENABLED` 时自动回退到 YAML/env 默认值）。
  - **控制面 API 与 UI**：提供 `/api/v1/admin/configs`（查询/编辑核心命名空间 `dataId`/`group` 配置、支持历史版本对比或热发布），并在管理后台增加独立「配置中心」界面直接可视化配置系统参数与开关。

### G2. 邮件与 Webhook 告警通知引擎
- **现状问题**：全仓库尚无邮件发送、Webhook 推送与告警持久化模块。[`AgentRunStartCompensator`](../worker/src/main/java/app/pagefault/visionforge/worker/temporal/AgentRunStartCompensator.java) 在启动补偿超限后仅打印警告；大模型/媒体网关失败（`AGENT_MODEL_PROVIDER_ERROR`、`AGENT_GENERATION_PROVIDER_ERROR`）、结果归档失败（`ARCHIVE_FAILED`）、额度对账异常（`PENDING_RECONCILIATION`）及敏感后台变更均无外部触达能力。
- **目标方案**：
  - **持久化模型（Flyway）**：新增告警渠道配置表 `alert_channel`（`EMAIL` / `WEBHOOK`、目标地址/收件人、Webhook 签名密钥、订阅事件类型列表、启用状态）与投递流水表 `alert_delivery`（状态、HTTP 响应码/错误、重试次数、关联 `traceId`）。
  - **异步派发与安全校验**：通过 `spring-boot-starter-mail`（SMTP）与标准 HTTP Client（附带 `X-VisionForge-Signature` HMAC-SHA256 签名与防 SSRF 校验）异步投递告警，失败不阻塞业务主事务。
  - **全链路埋点**：覆盖启动补偿失败、生图/生视频与模型网关失败、归档失败、管理员关键操作与系统错误。
  - **后台运维接口与 UI**：`/api/v1/admin/alerts/**`（渠道管理、一键测通、投递记录筛选）及 `/admin/alerts` 管理页面。

---

## 二、业务与管理 API 缺口

### G3. 身份认证、用户全生命周期管理与密码自助
- **现状问题**：
  - [`AdminUserController`](../api/src/main/java/app/pagefault/visionforge/api/identity/AdminUserController.java) 仅支持无过滤分页与 `PUT /{id}/role`；数据库存在的 `UserStatus.DISABLED` 无法通过 API 切换，亦缺乏管理员新建用户、强制重置密码、强制下线（吊销全部 Refresh Token）与按邮箱/角色/状态检索。
  - [`AuthController`](../api/src/main/java/app/pagefault/visionforge/api/identity/AuthController.java) 缺少用户自助修改昵称（`PATCH /api/v1/auth/me`）与修改密码（`POST /api/v1/auth/change-password`）。
- **目标方案**：补齐上述用户管理及账户安全接口，并确保账号禁用/改密同步吊销全部有效 `refresh_token` 及接入 `@BizLog`。

### G4. 审计日志可观测与导出
- **现状问题**：[`MybatisBizLogStore`](../api/src/main/java/app/pagefault/visionforge/api/audit/MybatisBizLogStore.java) 持续写入 `audit_log`，但无任何查询或导出接口，运维人员必须直连数据库排查问题。
- **目标方案**：新增 `GET /api/v1/admin/audit-logs`（支持按 `type`、`subType`、`operatorId`、`bizNo`、`traceId`、`success`、时间范围过滤与分页）与 `GET /api/v1/admin/audit-logs/export`（CSV 导出，转义公式注入字符），配套后台 `/admin/audit-logs` 审计页面。

### G5. 计费增强、管理端调额与余额兑换码（CDK）系统
- **现状问题**：当前用户额度只能在账户初始化时按固定默认值授予，缺乏运营人工调账、兑换码充值与对账导出手段。
- **目标方案**：
  - **管理员调额**：`POST /api/v1/admin/users/{id}/credit-adjust`（支持增发与扣减、幂等键、备注与 `ADJUST` 流水），以及管理端全站额度流水与统计视图。
  - **兑换码（CDK）系统**：新增兑换批次表与兑换记录表（`credit_redemption_code`、`credit_redemption_record`），支持生成随机安全码/自定义前缀、面额、最大可兑次数、每用户限兑、有效期与作废；用户端开放 `POST /api/v1/account/credit/redeem` 原子兑换（`REDEEM` 入账），同时补齐个人额度流水 CSV 导出。

### G6. 对话/运行真取消与管理端探测统计接口
- **现状问题**：
  - 无会话重命名 `PATCH /api/v1/conversations/{id}` 与安全删除 `DELETE /api/v1/conversations/{id}`；
  - 无进行中 `AgentRun` 的取消接口（`POST /api/v1/agent-runs/{runId}/cancel`），前端停止仅切断浏览器本地读取，后台 Workflow 仍在继续扣费；
  - 模型管理缺乏网关连通性测试 `POST /api/v1/admin/models/{id}/test`；管理端缺乏运维概览统计接口 `/api/v1/admin/overview`（汇总用户规模、运行中/异常任务数、`ARCHIVE_FAILED` 积压、未启动补偿失败数等）。

---

## 三、企业级安全与工程质量加固

### G7. Redis 限流保护、CORS 与安全响应头
- **现状问题**：
  - [`CommonErrorCode`](../common/src/main/java/app/pagefault/visionforge/common/error/CommonErrorCode.java) 已定义 `429 COMMON_RATE_LIMITED`，但登录、注册、兑换码核销与流式发消息尚未接入限流防护；
  - [`SecurityConfiguration`](../api/src/main/java/app/pagefault/visionforge/api/security/SecurityConfiguration.java) 尚未显式配置可控 CORS 白名单与基础安全响应头。
- **目标方案**：新增 Redis 原子滑动窗口限流过滤器（阈值可通过 Nacos 动态调控）、可控 CORS 与 Security Headers 配置。

---

## 四、前端用户体验（UX）痛点清单

| 编号 | 所在页面/组件 | 具体体验问题 | 修复方向 |
| --- | --- | --- | --- |
| **UX1** | [`chat-pane.tsx`](../frontend/src/components/studio/chat-pane.tsx) | 点击停止生成仅中断浏览器本地 `AbortController`，后端 Temporal Run 仍在运行计费并导致后续发送触发 `CONVERSATION_BUSY` | 调用真实 `POST /api/v1/agent-runs/{runId}/cancel` 并友好展示取消态与同步刷新 |
| **UX2** | [`app-shell.tsx`](../frontend/src/components/layout/app-shell.tsx)、[`conversations-provider.tsx`](../frontend/src/components/providers/conversations-provider.tsx) | ① 侧栏会话缺少重命名与删除菜单；② `conversationsApi.list()` 报错时侧栏直接落入空白“暂无历史会话”文案而非展示错误与重试 | 侧栏增加重命名/删除操作菜单及确认弹窗；暴露清晰加载失败提示与一键重试按钮 |
| **UX3** | [`tasks/page.tsx`](../frontend/src/app/(studio)/tasks/page.tsx) | ① 写死 `limit: 50`，历史任务多于 50 条时无法查看更多；② 全局任务中心仅展示生图/生视频，无法查看与管理 FFmpeg 合成任务 | 支持增量加载更多分页，并新增“视频合成任务”标签页联动查询/取消/重试 |
| **UX4** | [`files-panel.tsx`](../frontend/src/components/studio/files-panel.tsx) | ① 切走含未保存修改的文件无提醒；② 长标题在窄列表栏被截断缺悬浮全名；③ 缺文件删除与后端长度/大小（200/500/1MB）即时提示 | 增加切换未保存确认/一键丢弃、Tooltip 全名展示及字段即时校验提示 |
| **UX5** | [`media-preview.tsx`](../frontend/src/components/common/media-preview.tsx) | ① 图片/视频加载失败 `onError` 无重刷上限限制；② 悬停才能看到放大/下载按钮对触屏端不友好；③ 灯箱内缺少直接下载按钮与资产标识辅助 | 限制自动刷新重签上限并提供手动重试卡片；移动端/触屏始终可触达操作按钮；完善灯箱头部工具 |
| **UX6** | [`account/page.tsx`](../frontend/src/app/(studio)/account/page.tsx) 与 `/admin/**` | ① 个人中心无法修改密码、修改昵称或使用兑换码，也无法导出流水；② 管理后台缺少模型“测试连接”、用户筛选禁用、配置中心(Nacos)、告警中心、审计台与计费兑换页面 | 补全 `/account` 自助改密/改资料/CDK 兑换/流水导出；扩展 `/admin` 独立控制台多模块菜单与页面 |

---

## 五、分批实施与原子提交规划

遵循“单职责修复 + 测试验证 + 独立提交（绝不混杂）”原则推进：

1. **Commit 1（本文档）**：新增 `docs/ENTERPRISE_GAP_ANALYSIS.md` 并更新 [`docs/README.md`](README.md)。
2. **Commit 2**：Security 安全增强（Redis 限流、CORS 配置与安全响应头）。
3. **Commit 3**：身份认证与用户管理全生命周期完善（后端用户禁用/启用、代建用户、管理员重设密码、Token 按用户吊销、筛选列表、用户改昵称与改密码 + 单测）。
4. **Commit 4**：审计日志查询与 CSV 导出 API（后端筛选分页/导出 + 单测）。
5. **Commit 5**：对话与运行生命周期完善（会话重命名/删除、`AgentRun` 真实取消与 Temporal Cancel、模型连通性测试与后台运维概览接口 + 单测）。
6. **Commit 6**：计费增强、管理员调额与余额兑换码（CDK）系统（Flyway 迁移 + 后端调额/批量发码/原子兑换/账单导出 + 单测）。
7. **Commit 7**：Nacos 动态配置中心接入（Compose 接入 Nacos + `nacos-client` 动态适配与热监听 + `/api/v1/admin/configs` CRUD + 单测）。
8. **Commit 8**：邮件与 Webhook 告警通知引擎（Flyway 迁移 + SMTP/HMAC Webhook 异步分发 + 任务/补偿/网关异常埋点 + `/api/v1/admin/alerts/**` + 单测）。
9. **Commit 9**：前端 UX 全面修复（Chat 真取消、侧栏重命名/删除与错误重试、Files 未保存保护与实时校验、Tasks 分页与合成列表、MediaPreview 防死循环重试与触屏友好度、Account 改密/改资料/兑换/导出）。
10. **Commit 10**：独立管理后台 `/admin` 全功能控制台补全（总览看板、用户全操作与调额、模型测试、Nacos 配置中心、告警中心、CDK 兑换码、审计日志台）及同步更新相关文档。

## 实现与交付记录

以下缺口已全部完成实现并在本轮拆分提交中落地验证（数据库迁移已演进至 `V18`）：

| 编号 | 模块 / 能力 | 实现状态 | 说明 |
| --- | --- | --- | --- |
| G1 | 安全限流、CORS 显式白名单与安全响应头 | 已完成 | `SlidingWindowRateLimiter`、`RateLimitFilter`（429）、`SecurityConfiguration` CORS 与安全响应头 |
| G2 | 用户全生命周期管理与密码/会话治理 | 已完成 | 过滤查询、管理员建号、启停用、强制重置密码、全量吊销会话、自助改名与改密 |
| G3 | 操作审计日志查询与 CSV 防注入导出 | 已完成 | `/api/v1/admin/audit-logs`、`/export`、`CsvWriter`（UTF-8 BOM + 公式字符转义） |
| G4 | 会话/运行取消、模型连通性探测与管理总览 | 已完成 | `V16`、`PATCH/DELETE /conversations/{id}`、`POST /agent-runs/{id}/cancel`、模型探活、`GET /admin/overview` |
| G5 | 管理员调账、流水检索导出与 CDK 兑换系统 | 已完成 | `V17`、人工 `ADJUST` 调账、用户 CSV 导出、批次发行/作废与高并发 `FOR UPDATE` 防重兑 |
| G6 | Nacos 动态配置中心与热更新 | 已完成 | `nacos/nacos-server:v2.5.5`、`NacosConfigGateway` + `LocalConfigGateway`、`DynamicRuntimeConfig`、`GET/PUT/POST /admin/configs/**` |
| G7 | Email + 签名 Webhook 异步告警引擎 | 已完成 | `V18`、SSRF 防护、AES-GCM 密钥存储、HMAC-SHA256 签名、`AdminAlertController` 及关键故障/变更事件接入 |
| UX1–UX6 | Studio 前台体验修复 | 已完成 | 对话中止联动取消服务端 Run、侧边栏重命名/删除与失败重试、任务中心分页与合成任务 Tab、未保存草稿保护与校验计数、媒体重试上限与移动端按钮、账户编辑/改密/CDK 兑换/流水导出 |
| Admin UI | 独立 `/admin` 控制台补全 | 已完成 | `/admin` 运维总览、用户全操作、模型连通测试、`/admin/configs`、`/admin/alerts`、`/admin/billing`、`/admin/audit-logs` |

