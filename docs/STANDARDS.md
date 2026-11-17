# VisionForge 后端工程规范

状态：v1.0（生效中）

更新日期：2026-11-17

适用范围：`common`、`identity`、`asset`、`billing`、`agent`、`api`、`worker`、`database` 模块，以及所有 HTTP/JSON 接口契约。

规范级别：

- **必须（MUST）**：不遵守即视为缺陷，代码评审应阻断。
- **应该（SHOULD）**：默认遵守；偏离需在 PR 中说明理由。
- **可以（MAY）**：按场景选择。

变更流程：修改本规范需提交独立 PR，说明动机、影响范围与迁移方案；破坏性变更提升规范主版本并同步更新 `docs/PROPOSAL.md`。

---

## 1. 目的

本规范用于统一 VisionForge 后端的接口形态、错误契约、主键与时间、数据访问、日志审计和工程质量，降低前后端协作与长期维护成本。

本规范采用"务实 REST"：资源用 URL 和 HTTP 方法表达，状态码保持真实；但响应体统一使用包络结构，错误契约吸收 RFC 9457 的核心原则（稳定错误标识、一次返回全部校验错误、关联 ID、不泄露内部细节），不使用 `application/problem+json`。

前端实现、基础设施运维手册不在本规范范围内，但接口消费者必须遵守本规范定义的契约。

## 2. 架构与模块边界

### 2.1 模块职责

| 模块 | 职责 |
| --- | --- |
| common | 跨模块基础类型、错误定义、统一响应、审计日志基础设施 |
| identity | 用户、工作空间、成员、权限、认证与令牌 |
| asset | 上传、下载、对象存储、媒体元数据、资产生命周期 |
| billing | 模型用量、费用、额度预占、结算和流水 |
| agent | 会话、上下文、动态工具调用、创作对象与生成任务 |
| api | HTTP/SSE 入口、请求校验、认证接入、服务启动装配 |
| worker | Temporal Worker 启动、Workflow/Activity 注册、执行资源配置 |
| database | 业务数据库迁移脚本 |

### 2.2 依赖规则（必须）

- api、worker 负责装配业务模块，**不得互相依赖**。
- api 调用 agent 的公开应用服务；worker 注册 agent 提供的执行实现。
- agent 通过公开服务接口调用 identity、asset、billing。
- identity、asset、billing **不得反向依赖 agent**；关联关系只保存业务 ID。
- common 不得依赖任何业务模块，不得放 Web 启动配置或持久化实体。
- database 不得依赖业务模块；迁移发布不依赖 API 或 Worker 启动。
- **禁止跨模块直接访问对方的 Mapper、Repository 或数据库实体。**
- 每个业务模块只拥有自己的 mapper、entity、repository 和迁移语义；跨模块协作必须经过公开应用服务。

### 2.3 包结构

- 基础包：`app.pagefault.visionforge.<module>`。
- 业务模块内按领域分包（如 `identity.user`、`identity.auth`），不按技术层平铺。
- API 的请求/响应 DTO 放 api 模块；业务模块对外暴露命令对象和应用服务，不暴露 Web DTO。
- `common.audit` 是 common 中唯一允许包含 Spring AOP/SpEL 基础设施的例外，用于自研业务操作日志；它不得依赖任何业务模块。

### 2.4 边界固化（必须）

- 使用 ArchUnit 测试固化 2.2 的依赖规则，违反即构建失败。
- 新增跨模块类型前先评估：优先放业务模块；只有真正跨模块的基础契约才放 common。

## 3. API 设计（务实 REST）

### 3.1 基础约定

- 所有业务接口使用 `/api/v1` 前缀。
- URL 使用小写、复数名词：`/conversations`、`/generation-tasks`、`/assets`。
- 多词路径段使用连字符：`/generation-tasks/{id}`。
- 查询参数使用 camelCase：`?cursor=...&limit=20&sort=createdAt,desc`。
- JSON 字段使用 camelCase；数据库列使用 snake_case。
- 所有接口返回 `application/json; charset=UTF-8`，二进制/流式接口除外。

### 3.2 HTTP 方法与状态码

| 场景 | 方法 | 成功状态 |
| --- | --- | --- |
| 查询单个/列表 | GET | 200 |
| 创建资源 | POST | 201 + `Location` 头 |
| 接收长任务 | POST | 202 + `Location` 头（任务资源） |
| 全量替换 | PUT | 200 |
| 部分更新 | PATCH | 200 |
| 删除 | DELETE | 200（返回统一包络，`data` 为 null） |

**必须**保持 HTTP 状态真实：

- 400：请求格式/参数校验失败。
- 401：未认证、令牌无效或过期。
- 403：已认证但无权限。
- 404：资源不存在或当前用户不可见。
- 405：方法不支持。
- 409：业务状态冲突、并发版本冲突、重复请求。
- 415：内容类型不支持。
- 429：限流。
- 500：未预期的系统错误。
- 503：依赖不可用或维护中。

**不允许**用 200 包裹业务失败；**不使用** 422，校验失败统一用 400。**不使用** 204（会丢失统一包络）；删除成功返回 200 + 包络。跨工作空间访问不得用 403 暴露资源存在性，统一返回 404。

### 3.3 动作端点

无法自然表达为资源状态变更的操作，允许使用动作端点：

- `POST /agent-runs/{id}/cancel`
- `POST /messages/{id}/regenerate`
- `POST /generation-tasks/{id}/cancel`

动作端点**必须**使用 POST，**必须**在 OpenAPI 中说明副作用、幂等性和权限。不得为了"纯 REST"把动作硬编码成 PATCH 状态。

### 3.4 长任务与流式接口

- 图片/视频等长任务创建接口返回 **202 Accepted**，`Location` 指向任务资源；任务状态通过 GET 查询或 SSE 推送。
- SSE 使用独立事件协议，`data` 为 JSON，事件至少包含 `type`、`code`、`message`、`data`、`traceId`、`timestamp`；终止事件必须明确成功/失败/取消。
- 断线重连使用 `Last-Event-ID`；服务端**应该**支持从业务状态恢复，而不是从头重放全部 token。
- 流式响应不套普通 HTTP 包络；错误发生在流开始前用普通包络，流开始后用终止事件表达。

### 3.5 幂等

- 所有创建付费或长任务的 POST **应该**支持 `Idempotency-Key` 请求头。
- 幂等键按 `用户 + 工作空间 + 接口` 作用域存储，保存请求指纹与首次响应；重复请求返回首次结果，不重复执行副作用。
- 客户端重复提交与用户明确要求"再生成一次"必须区分：后者创建新任务。

### 3.6 分页

- 消息、资产、流水等持续增长的列表**必须**使用游标分页：
  `GET /messages?limit=20&cursor=...`，响应 `data = { items, nextCursor, hasMore }`。
- 后台管理类列表可以使用页式分页：`page`、`size`，响应 `data = { items, page, size, total }`。
- 默认 `limit=20`，最大 200；禁止无上限列表。
- 排序字段必须白名单化，禁止把客户端字段直接拼进 SQL。

### 3.7 版本与兼容

- URI 主版本：`/api/v1`。
- 新增字段、新增错误码、新增可选参数属于兼容变更。
- 删除/重命名字段、改变语义、改变状态码属于破坏性变更，必须发布新版本。
- 废弃接口必须返回 `Deprecation` 与 `Sunset` 响应头，并在文档中标注。

## 4. 统一响应格式

### 4.1 包络结构（必须）

所有 JSON 接口，无论成功或失败，**必须**返回同一结构：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {},
  "traceId": "5f2c9a1e4b2d4c61a80e2a8f5b7c1e34",
  "timestamp": "2026-11-17T12:00:00Z"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| code | string | `OK` 表示成功；失败时为命名空间错误码 |
| message | string | 面向人的说明；客户端不得依赖其内容做分支 |
| data | any | 成功时为业务数据；失败时可为 null 或错误详情 |
| traceId | string | 32 位十六进制请求关联 ID，与 `X-Request-Id` 一致 |
| timestamp | string | ISO-8601 UTC 时间 |

规则：

- 成功 `code` **必须**为 `OK`；失败 `code` **必须**为已登记的错误码。
- HTTP 状态码表达传输/语义结果，`code` 表达业务结果；二者不得矛盾。
- 成功响应的 `data` 为资源对象或分页对象；无数据时为 null。
- `message` 默认使用中文；错误码是稳定契约，`message` 可以优化但不得作为客户端分支依据。
- `traceId` 由请求过滤器生成或透传；日志与响应必须一致。
- `timestamp` 使用 UTC，序列化为 ISO-8601。

### 4.2 校验失败

```json
{
  "code": "COMMON_INVALID_ARGUMENT",
  "message": "请求参数不合法",
  "data": {
    "email": "必须是有效的邮箱格式",
    "password": "长度必须在 12 到 72 之间"
  },
  "traceId": "5f2c9a1e4b2d4c61a80e2a8f5b7c1e34",
  "timestamp": "2026-11-17T12:00:00Z"
}
```

- **必须**一次返回全部字段错误，禁止逐轮提示。
- `data` 的键为字段路径，值为可直接展示的说明。
- 禁止在错误详情中回显密码、令牌、密钥或完整请求体。

### 4.3 例外

- 文件下载/媒体流不包络，使用对应媒体类型与 `Content-Disposition`。
- SSE 使用 3.4 的事件协议。
- 健康检查等基础设施端点可以返回框架默认结构。

### 4.4 安全错误

401/403 由 Spring Security 的 `AuthenticationEntryPoint` / `AccessDeniedHandler` 输出同一包络，禁止返回空 body 或 HTML。

```json
{
  "code": "COMMON_UNAUTHORIZED",
  "message": "未认证或登录状态已失效",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
```

## 5. 错误码与异常

### 5.1 错误码契约

```java
public interface ErrorCode {
    String code();
    int httpStatus();
    String message();
}
```

- 错误码**必须**使用字符串命名空间，格式 `<MODULE>_<REASON>`。
- 已登记前缀：`COMMON_`、`IDENTITY_`、`ASSET_`、`BILLING_`、`AGENT_`、`GENERATION_`。
- 错误码一经发布**不得复用或改变语义**；重命名视为废弃 + 新增。
- 每个模块使用独立枚举实现 `ErrorCode`，在模块内集中维护。
- 错误码**必须**登记到接口文档（附录 B 或 OpenAPI 扩展）。

### 5.2 异常分类

| 类型 | 含义 | 日志级别 | 响应 |
| --- | --- | --- | --- |
| `BusinessException` | 可预期的业务失败（4xx） | WARN，无堆栈 | 使用异常携带的错误码与消息 |
| `SystemException` | 未预期缺陷或依赖故障（5xx） | ERROR，含堆栈 | 仅返回错误码通用消息 |
| 未知异常 | 未分类异常 | ERROR，含堆栈 | `COMMON_INTERNAL_ERROR` |

- 业务代码**必须**通过 `BusinessException`/`BizAssert` 表达可预期失败。
- **禁止**把底层异常消息直接返回给客户端。
- 校验失败、参数错误、权限不足、状态冲突都应映射为明确的错误码。
- 可重试分类：`COMMON_RATE_LIMITED`、`COMMON_SERVICE_UNAVAILABLE` 可以重试；参数错误、认证失败、状态冲突默认不自动重试。

### 5.3 初始通用错误码

| code | HTTP | message |
| --- | --- | --- |
| COMMON_INVALID_ARGUMENT | 400 | 请求参数不合法 |
| COMMON_UNAUTHORIZED | 401 | 未认证或登录状态已失效 |
| COMMON_FORBIDDEN | 403 | 没有执行该操作的权限 |
| COMMON_NOT_FOUND | 404 | 请求的资源不存在 |
| COMMON_METHOD_NOT_ALLOWED | 405 | 请求方法不被支持 |
| COMMON_CONFLICT | 409 | 请求与当前资源状态冲突 |
| COMMON_UNSUPPORTED_MEDIA_TYPE | 415 | 不支持的内容类型 |
| COMMON_RATE_LIMITED | 429 | 请求过于频繁，请稍后重试 |
| COMMON_INTERNAL_ERROR | 500 | 系统内部错误，请稍后重试 |
| COMMON_SERVICE_UNAVAILABLE | 503 | 服务暂时不可用，请稍后重试 |

## 6. 主键、时间与金额

### 6.1 主键：UUIDv7（必须）

- 所有新建业务表的主键**必须**使用 UUIDv7（RFC 9562），数据库类型 `UUID`。
- 应用侧使用 `common.id.UuidV7Generator` 生成 time-ordered UUIDv7：48-bit 毫秒时间戳 + 12-bit rand_a + 62-bit rand_b，`version=7`、`variant=2`；随机源使用 JDK `SecureRandom` 或 Hutool `RandomUtil`，时间源注入 `Clock`。
- Hutool 5.8.48 的 `IdUtil` 只有 UUIDv4、ObjectId 和 Snowflake，**不得**用 `IdUtil.randomUUID()`/`simpleUUID()` 生成业务主键；UUIDv7 位布局由项目实现并测试。
- 数据库默认值可以使用 PostgreSQL 18 的 `uuidv7()` 作为兜底；应用侧生成是主路径。
- **禁止**新表使用 UUIDv4 或 `gen_random_uuid()` 作为主键；随机主键会造成索引页分裂。
- Java 类型使用 `java.util.UUID`；对外 JSON 使用标准 UUID 字符串。
- 外键、索引、唯一约束使用 UUID 类型。
- UUID **不是**安全凭证；资源授权必须基于服务端身份与工作空间归属校验，不能依赖 ID 不可猜测。
- 对外不暴露自增 ID；如需可读的业务编号，单独设计业务编号字段，不复用主键。

### 6.2 时间

- 数据库使用 `TIMESTAMPTZ`，统一存储 UTC。
- Java 使用 `Instant` 或 `OffsetDateTime`；禁止使用 `java.util.Date`。
- API 时间使用 ISO-8601（`2026-11-17T12:00:00Z`）。
- 业务代码**应该**注入 `Clock` 以便测试；禁止在领域逻辑中散落 `System.currentTimeMillis()`。

### 6.3 金额与额度

- 金额使用 `NUMERIC(20,6)` + `BigDecimal`；**禁止** `double`/`float`。
- 额度使用明确的最小计量单位，数据库中使用 `BIGINT`。
- 价格规则、币种和用量单位必须显式存储；用户计费与供应商成本分别记录。

## 7. ORM 与数据访问（MyBatis-Plus）

### 7.1 版本与依赖

- 使用 `com.baomidou:mybatis-plus-spring-boot4-starter`，版本 `3.5.18.1`（Boot 4 专用 starter，依赖 `mybatis-spring 4.1.0`）。
- 根 POM 导入 `mybatis-plus-bom:3.5.18` 统一版本；子模块不重复声明版本。
- PostgreSQL 驱动由 Spring Boot BOM 管理。
- 复杂 SQL 允许使用 MyBatis XML；简单 CRUD 使用 MyBatis-Plus `BaseMapper`。

### 7.2 归属与可见性（必须）

- Entity、Mapper、XML 只属于拥有该表的业务模块。
- api/worker **不得**直接注入其他模块的 Mapper；跨模块操作通过公开应用服务。
- Controller 不得直接返回 Entity；必须映射为 api 模块的响应 DTO。
- 禁止跨模块 JOIN 查询；需要组合数据时由调用方组合应用服务结果。

### 7.3 Entity 约定

- 表名、列名使用 snake_case；Java 字段使用 camelCase。
- 使用 `@TableName`、`@TableId` 显式声明表与主键。
- 主键使用 `@TableId(value = "id", type = IdType.INPUT)`，由应用层 `IdGenerator` 在创建实体时生成 UUIDv7 并显式赋值。
- **不得**使用 `IdType.ASSIGN_ID`（其 `IdentifierGenerator.nextId()` 返回 `Number`）或 `IdType.ASSIGN_UUID`（生成 UUIDv4 字符串）。
- `IdType.INPUT` 下必须保证所有插入路径都显式赋值；`MetaObjectHandler` 仅可作为兜底，不作为唯一生成点。
- 通用审计字段：`created_at`、`updated_at`、`version`（乐观锁）、`deleted`（逻辑删除，按表需要）。
- Entity **应该**使用 `@Getter`/`@Setter` 或显式访问器；**禁止**在 Entity 上使用 `@Data`/`@EqualsAndHashCode`。
- 禁止把 Entity 作为 API 请求/响应类型。

### 7.4 查询与分页

- 优先使用 `LambdaQueryWrapper`，避免字符串拼接列名。
- `SELECT` **必须**明确列；禁止 `SELECT *`。
- 列表查询**必须**分页；使用 MyBatis-Plus 分页插件，单页上限 200。
- 复杂动态 SQL 放 XML；注解 SQL 只允许用于极简查询。
- 排序字段必须白名单校验；禁止客户端直接传 SQL 片段。
- 批量操作控制批大小；避免超大 `IN` 列表。

### 7.5 插件与配置

必须配置：

- `PaginationInnerInterceptor`（设置最大 limit）。
- `OptimisticLockerInnerInterceptor`（实体含 `@Version`）。
- `BlockAttackInnerInterceptor`（禁止无条件 update/delete）。

`IllegalSQLInnerInterceptor` 仅允许在开发环境启用。MyBatis 的 SQL 日志在开发环境开启，生产关闭参数值输出。

### 7.6 JSONB

- JSONB 列使用自定义 TypeHandler；**必须**验证 Boot 4（Jackson 3）兼容性。
- 如需 Jackson 2 TypeHandler，应显式引入兼容模块或重写为 Jackson 3 实现，禁止依赖隐式传递版本。
- JSONB 用于供应商扩展参数、脱敏快照等；高频查询字段仍使用明确列。

### 7.7 事务

- 事务边界在应用服务层，使用 `@Transactional`。
- 禁止在数据库事务中执行 LLM 调用、HTTP 请求、Temporal 启动等远程操作。
- 长任务采用"本地事务 + 启动补偿"模式，不以长事务保证一致性。
- 乐观锁冲突返回 `COMMON_CONFLICT`，由调用方决定重试或提示。

### 7.8 迁移

- 业务迁移脚本集中在 `database/src/main/resources/db/migration`，Flyway 统一版本序列。
- 已发布脚本**不得修改**；修正通过新迁移完成。
- 发布变更采用 expand/contract：先兼容旧应用，再回填，最后移除旧结构。
- 迁移由独立 Job 执行，不依赖 API/Worker 启动。
- Entity 变更必须与迁移脚本同步评审。

## 8. 日志规范

### 8.1 技术日志

- 使用 SLF4J API；禁止 `System.out` 和直接使用具体日志实现。
- 级别：
  - ERROR：系统异常、依赖故障、需要人工处理的失败。
  - WARN：可预期但需要关注的异常（业务失败、重试、降级）。
  - INFO：启动、关闭、关键状态变化。
  - DEBUG：仅开发环境；生产默认关闭。
- 生产环境**应该**使用结构化日志：Spring Boot 内置 ECS JSON（`logging.structured.format.console=ecs`）；开发环境保留可读格式。
- 日志**必须**包含 `traceId`；启用 Micrometer Tracing 后同时包含 `traceId`/`spanId`。
- **禁止**记录：密码、密码哈希、访问令牌、刷新令牌、`Authorization`/`Cookie` 头、完整请求/响应体。
- 邮箱、手机号、证件号等 PII 使用 `LogMasker` 脱敏后再记录。
- 异常使用 `log.error("...", exception)` 保留堆栈；业务异常使用 WARN 且不打印堆栈。

### 8.2 业务操作日志（自研 `@BizLog`）

不使用第三方业务日志组件；由项目自行实现，满足"谁在什么时间对什么做了什么、结果如何、关联哪次请求"。

#### 组件与放置

| 组件 | 职责 | 位置 |
| --- | --- | --- |
| `@BizLog` | 声明日志类型、业务对象、成功/失败模板、条件 | common.audit |
| `BizLogAspect` | AOP 环绕通知，解析模板与 SpEL | common.audit |
| `BizLogRecord` | 不可变审计记录模型 | common.audit |
| `OperatorProvider` | 获取当前操作人（id、名称） | 接口在 common，实现在 api/worker |
| `TenantContext` | 获取当前工作空间/租户 | 接口在 common，实现在 api/worker |
| `BizLogStore` | 审计记录持久化 | 接口在 common，实现在 api |
| `audit_log` | 审计记录表 | database 迁移 |

约束：

- `common.audit` 可以依赖 Spring AOP、SpEL、Context、TX；**不得**依赖 identity/asset/billing/agent。
- 操作人/租户/存储通过 SPI 注入，避免 common 依赖 Spring Security 或具体 ORM。

#### 注解与模板

```java
@BizLog(
    type = "IDENTITY_USER",
    subType = "REGISTER",
    bizNo = "#result.id",
    success = "用户「{{#result.displayName}}」注册成功",
    fail = "用户注册失败：{{#errorMessage}}",
    recordOnFailure = true
)
public UserAccount register(RegisterCommand command) { ... }
```

- `type`：模块/对象类别常量，例如 `IDENTITY_USER`、`GENERATION_TASK`。
- `subType`：动作常量，例如 `REGISTER`、`ROLE_CHANGE`、`CANCEL`。
- `bizNo`：SpEL 表达式，返回业务对象 ID（字符串）。
- `success` / `fail`：`{{#表达式}}` 模板；可用变量：方法参数、`#result`、`#errorMessage`、`#operator`、`#traceId`。
- `condition`：SpEL 布尔表达式，为 false 时不记录。
- `recordOnFailure`：默认 true；失败时保留异常并按 fail 模板记录。
- `sync`：默认 false（异步）；关键审计可设为 true。
- 模板中**禁止**输出密码、令牌、完整请求体；PII 必须使用 `#maskEmail(...)`、`#maskPhone(...)`、`#maskToken(...)` 等内置脱敏函数。
- 未通过代码评审的模板不得上线。

#### 执行语义

- 业务方法正常返回后记录成功日志；抛异常时按 `recordOnFailure` 记录失败日志，**不得吞掉原异常**。
- 默认异步执行：有界队列 + 命名线程池；队列满时记录技术日志并丢弃，不得阻塞业务。
- 审计写入使用独立事务/连接，不参与业务事务；业务回滚不影响失败审计记录。
- 审计写入失败只记录技术 ERROR 日志（带 traceId），不影响业务返回。
- 操作人来源：api 从 Spring Security 的 JWT 主体获取；worker/system 任务使用 `system` 操作人。
- 工作空间 ID 来自认证上下文；系统任务可为空，但必须显式。

#### 审计表 `audit_log`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | UUID | UUIDv7 主键 |
| type | TEXT | 模块/对象类型 |
| sub_type | TEXT | 动作 |
| biz_no | TEXT | 业务对象 ID |
| operator_id | UUID | 操作人 ID，系统任务为空 |
| operator_name | TEXT | 操作人显示名 |
| workspace_id | UUID | 工作空间 ID，可空 |
| success | BOOLEAN | 成功/失败 |
| detail | TEXT | 模板渲染后的可读描述 |
| extra | JSONB | 脱敏后的附加快照，可空 |
| trace_id | TEXT | 请求关联 ID |
| error_message | TEXT | 失败原因，可空 |
| created_at | TIMESTAMPTZ | 记录时间，默认 now() |

索引：`biz_no`、`operator_id`、`workspace_id`、`created_at`、`trace_id`。

- 审计表只追加，不提供修改/删除 API。
- `extra` 必须脱敏；禁止存储令牌、密码、完整请求体。
- 保留期默认 180 天，由后续清理任务执行；涉及合规要求时按产品策略调整。
- 字段级 diff 属于 v2 能力；v1 仅使用模板描述。

#### 必须记录的操作

登录成功/失败、登出、密码修改、角色变更、工作空间创建/成员变更、资产删除、生成任务提交/取消、额度预占/结算、管理员操作。普通读取不写审计日志。

## 9. 安全与认证（原则）

### 9.1 密码

- 使用 bcrypt（Spring Security `BCryptPasswordEncoder`），默认 cost 12，可通过配置降低测试环境成本。
- 密码长度 12–72 字符；bcrypt 有 72 字节上限。
- 数据库中只存 bcrypt 哈希；禁止可逆加密或明文。
- 登录失败返回统一 401，不区分"用户不存在/密码错误"，避免账号枚举。

### 9.2 令牌

- Access Token：JWT，短有效期（默认 15 分钟），包含 `sub`、`role`、`iss`、`aud`、`jti`、`exp`。
- Refresh Token：随机不透明令牌，数据库仅存 SHA-256 哈希；默认 30 天，使用后轮换，登出撤销。
- 初始使用 HS256，密钥来自环境变量（≥256 bit）；未来多服务/外部接入时迁移 RS256 + JWKS。
- 客户端使用 `Authorization: Bearer <accessToken>`。
- 访问令牌失效前无法主动召回；需要即时吊销时缩短有效期或引入 denylist，首版不实现。

### 9.3 RBAC

- 系统角色只有两类：`ADMIN`（管理员）、`USER`（普通用户）。
- Spring Security authorities 使用 `ROLE_ADMIN`、`ROLE_USER`；接口使用 `hasRole`/`@PreAuthorize`。
- 工作空间成员角色（`OWNER`/`ADMIN`/`MEMBER`）独立于系统角色，用于工作空间内授权。
- 授权必须在服务端执行；模型输出、客户端参数和 URL 中的 ID 都不能作为授权依据。
- 跨工作空间访问返回 404，不返回 403。

### 9.4 其他

- 密钥、JWT secret、数据库密码只通过环境变量注入，禁止提交仓库。
- CORS 只允许配置的来源；携带凭证时禁止通配符。
- 首版使用 Bearer 令牌，不需要 CSRF；若未来改为 Cookie 存储 refresh token，必须增加 CSRF 防护。
- 登录、注册等敏感接口后续必须接入限流和账号锁定策略。

## 10. 校验与国际化

- 使用 Jakarta Bean Validation；请求 DTO 使用 `@Valid`，方法参数校验使用 `@Validated`。
- 校验消息使用资源文件（默认 `zh_CN`），禁止在代码中拼接用户文案。
- 一次返回全部校验错误；字段路径稳定（如 `email`、`items[0].url`）。
- 自定义校验器放业务模块，通用校验注解放 common。
- 错误码与消息分离：`code` 面向程序，`message` 面向人。

## 11. 配置与密钥

- 非敏感配置放 `application.yaml`；敏感配置只通过环境变量注入。
- 生产 profile 缺失必需配置时**必须**启动失败（fail fast）。
- 环境变量命名：

| 变量 | 用途 |
| --- | --- |
| `SERVER_PORT` | API 端口 |
| `VISIONFORGE_DB_URL` / `_USER` / `_PASSWORD` | 业务数据库 |
| `JWT_SECRET` | JWT 签名密钥（Base64，≥256 bit） |
| `TEMPORAL_TARGET` / `_NAMESPACE` / `_TASK_QUEUE` | Temporal 连接 |
| `BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` / `_DISPLAY_NAME` | 首次管理员引导 |

- `.env.example` 只放占位值；真实 `.env` 被 `.gitignore` 忽略。
- 配置类使用 `@ConfigurationProperties` 并在启动时校验。

## 12. 可观测性

- 所有请求通过 `X-Request-Id`/`traceId` 关联；异步任务、Temporal Workflow/Activity、模型调用日志必须携带关联 ID。
- 关键关联字段：`conversation_id`、`agent_run_id`、`tool_invocation_id`、`generation_task_id`、`workflow_id`。
- 指标覆盖：HTTP 请求量/错误率/延迟、数据库连接池、Temporal 任务、模型调用成功率、生成等待时间、审计写入失败。
- 健康检查使用 actuator：`/actuator/health/liveness`、`/actuator/health/readiness`。
- 生产日志、指标、追踪中禁止出现 PII、令牌和密钥。
- Micrometer Tracing + OpenTelemetry 在部署阶段接入；接入前 `TraceContext` 继续使用相同 MDC key。

## 13. 测试与质量门

### 13.1 测试类型

- 单元测试：JUnit 5 + AssertJ + Mockito。
- 集成测试：Testcontainers PostgreSQL；Flyway 从空库迁移。
- 工作流测试：Temporal `TestWorkflowEnvironment`。
- Web 测试：MockMvc 验证包络、状态码、traceId、异常映射。
- 审计测试：`@BizLog` 切面、模板解析、失败隔离、落库。
- 架构测试：ArchUnit 验证模块依赖与 Mapper 可见性。

### 13.2 质量门（必须）

- `./mvnw test` 全绿；新增核心逻辑必须带测试。
- 迁移脚本必须能在空库执行，并重复执行幂等。
- ArchUnit 规则不得被绕过。
- 构建必须通过 Maven Enforcer（Java ≥ 21、Maven ≥ 3.9、无重复依赖）。
- 提交信息遵循 Conventional Commits（`feat:`、`fix:`、`build:`、`docs:`、`refactor:`、`test:`、`chore:`）。

### 13.3 代码风格

- UTF-8、4 空格缩进、无通配符 import。
- 类/方法/变量命名使用英文；注释解释"为什么"，不重复"做了什么"。
- 优先使用 record 表达不可变数据；Lombok 选择性使用（见 13.4）。

### 13.4 Lombok 使用规范

- record：DTO、响应、值对象、配置属性。
- Lombok：`@Slf4j`（日志）、`@RequiredArgsConstructor`（构造注入）、`@Builder`（多字段构建）、`@Getter/@Setter`（Entity）。
- **禁止**：Entity 上 `@Data`/`@EqualsAndHashCode`；`@SneakyThrows`；用 Lombok 生成可变 DTO。
- `lombok.config` 统一配置，不随模块漂移。

### 13.5 工具库

- 通用工具优先使用 JDK 标准库。
- 需要第三方工具时使用 `cn.hutool:hutool-core`（当前基线 5.8.48，版本由根 POM 统一管理），**不要**引入 `hutool-all`。
- UUIDv7 生成不依赖第三方 UUIDv7 API，由 `common.id.UuidV7Generator` 实现并测试。
- 新增第三方依赖必须评估许可证、维护状态、Boot 4 兼容性和依赖体积。

## 14. API 与数据版本兼容

- 新增字段/错误码/可选参数：兼容。
- 删除/重命名/改语义：需新 API 版本或迁移周期。
- 错误码不复用；废弃错误码保留文档并标注。
- 数据库迁移采用 expand/contract；大表回填独立执行，不与发布事务耦合。
- Temporal Workflow 代码变更必须评估历史重放兼容性。

## 15. 附录

### A. 响应示例

成功：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f",
    "email": "alice@example.com",
    "displayName": "Alice",
    "role": "USER"
  },
  "traceId": "5f2c9a1e4b2d4c61a80e2a8f5b7c1e34",
  "timestamp": "2026-11-17T12:00:00Z"
}
```

业务冲突：

```json
{
  "code": "IDENTITY_EMAIL_ALREADY_REGISTERED",
  "message": "该邮箱已注册",
  "data": null,
  "traceId": "5f2c9a1e4b2d4c61a80e2a8f5b7c1e34",
  "timestamp": "2026-11-17T12:00:00Z"
}
```

### B. 错误码登记模板

| 模块 | code | HTTP | 触发条件 | message |
| --- | --- | --- | --- | --- |
| common | COMMON_INVALID_ARGUMENT | 400 | 参数/请求体校验失败 | 请求参数不合法 |
| identity | IDENTITY_EMAIL_ALREADY_REGISTERED | 409 | 注册邮箱已存在 | 该邮箱已注册 |
| identity | IDENTITY_INVALID_CREDENTIALS | 401 | 登录凭证错误 | 邮箱或密码错误 |
| identity | IDENTITY_TOKEN_INVALID | 401 | access/refresh token 无效 | 登录状态已失效 |

### C. `@BizLog` 示例

```java
@BizLog(
    type = "IDENTITY_USER",
    subType = "ROLE_CHANGE",
    bizNo = "#targetUserId",
    success = "管理员「{{#operator.name}}」将用户 {{#targetUserId}} 角色改为 {{#newRole}}",
    fail = "角色修改失败：{{#errorMessage}}"
)
public void changeRole(UUID targetUserId, UserRole newRole) { ... }
```

### D. MyBatis-Plus 配置示例

```java
@Configuration
public class MybatisPlusConfiguration {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }
}
```

UUIDv7 生成器：由应用层 `IdGenerator`（`common.id`）生成，主键字段使用 `@TableId(type = IdType.INPUT)` 和 `UUID` 类型。**不要**实现 MyBatis-Plus `IdentifierGenerator` 来生成 UUIDv7：其 `nextId()` 返回 `Number`，`nextUUID()` 返回字符串，`ASSIGN_UUID` 生成的是 UUIDv4。

```java
public interface IdGenerator {
    UUID nextId();
}

public final class UuidV7Generator implements IdGenerator {

    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL; // 48 bits
    private static final long RAND_A_MASK = 0xFFFL;               // 12 bits
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL; // 62 bits
    private static final long VARIANT_10 = 0x8000_0000_0000_0000L;

    private final Clock clock;
    private final RandomGenerator random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UUID nextId() {
        long timestamp = clock.millis() & TIMESTAMP_MASK;
        long randA = random.nextLong() & RAND_A_MASK;
        long most = (timestamp << 16) | (0x7L << 12) | randA;

        long randB = random.nextLong() & RAND_B_MASK;
        long least = VARIANT_10 | randB;

        return new UUID(most, least);
    }
}
```

测试必须覆盖：`version() == 7`、`variant() == 2`、时间戳可提取、批量生成不重复。同一毫秒内不保证严格递增；对索引局部性足够，需要严格单调时在 rand_a 中加入计数器。

### E. `audit_log` DDL 草案（实施时通过 Flyway 迁移）

```sql
CREATE TABLE audit_log (
    id            UUID        NOT NULL,
    type          TEXT        NOT NULL,
    sub_type      TEXT        NOT NULL,
    biz_no        TEXT,
    operator_id   UUID,
    operator_name TEXT,
    workspace_id  UUID,
    success       BOOLEAN     NOT NULL,
    detail        TEXT,
    extra         JSONB,
    trace_id      TEXT,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_audit_log_biz_no ON audit_log (biz_no);
CREATE INDEX idx_audit_log_operator ON audit_log (operator_id);
CREATE INDEX idx_audit_log_workspace ON audit_log (workspace_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
CREATE INDEX idx_audit_log_trace ON audit_log (trace_id);
```

### F. 参考

- RFC 9562（UUIDv7）
- RFC 9457（Problem Details 原则：稳定错误标识、错误聚合、关联 ID、不泄露细节）
- Spring Framework Error Responses / Spring Security Method Security
- MyBatis-Plus 官方文档（Spring Boot 4 starter）
- PostgreSQL 18 文档（`uuidv7()`、`TIMESTAMPTZ`、`JSONB`）
- OWASP Password Storage Cheat Sheet（bcrypt）
- Elastic Common Schema（结构化日志）

### G. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1.0 | 2026-11-17 | 初版：务实 REST、统一包络、UUIDv7 自研生成器、MyBatis-Plus `IdType.INPUT`、自研 `@BizLog`、日志与质量规范 |
