# VisionForge 后端开发规范

本文档定义 VisionForge Java 21 后端（`common`、`identity`、`asset`、`billing`、`agent`、`api`、`worker`、`database`）的工程约定与质量红线，按 **【强制】**（评审阻塞项）、**【推荐】**（默认遵循）与 **【禁止】**（明确禁止的做法）三级规范执行。

---

## 一、模块边界与依赖治理

1. **【强制】遵守单向无环模块拓扑**：`common` 不依赖任何业务模块；`identity`、`asset`、`billing` 仅依赖 `common`；`agent` 组合前三者；`api` 与 `worker` 分别装配系统，二者互相独立；`database` 独立维护迁移脚本，不依赖任何后端业务代码。
2. **【强制】通过接口解耦反向调用**：当底层模块（如 `asset`）或装配层（`api`）需要感知会话归属或操作人/存储 SPI 时，通过接口注入（如 [`ConversationReferenceChecker`](../asset/src/main/java/app/pagefault/visionforge/asset/library/ConversationReferenceChecker.java)、`OperatorProvider`、`BizLogStore`）。
3. **【禁止】跨模块注入 Mapper 或跨库区 JOIN**：业务模块只能调用其他模块公开的应用服务方法；禁止跨模块注入 `@Mapper` 接口或持久化 Entity，禁止 SQL 直接跨模块表关联查询。
4. **【强制】Maven 依赖收敛**：所有第三方与内部模块版本统一托管于根 [`pom.xml`](../pom.xml) 的 `<dependencyManagement>`，子模块禁止私自覆盖版本号；Maven Enforcer 强制要求 JDK `>= 21`、Maven `>= 3.9` 且禁止重复依赖版本（`<banDuplicatePomDependencyVersions/>`）。

---

## 二、Java 语言特性与代码风格

1. **【强制】不可变数据传输使用 Java `record`**：所有 API 请求/响应 DTO、命令对象、配置属性（`@ConfigurationProperties`）、值对象统一采用 `record`。
2. **【推荐】有限且明确地使用 Lombok**：仅允许在日志（`@Slf4j`）、构造注入（`@RequiredArgsConstructor`）、多字段对象构建（`@Builder`）与持久层实体（`@Getter`/`@Setter`）按 [`lombok.config`](../lombok.config) 使用。
3. **【禁止】实体滥用 `@Data` 与受检异常吞没**：禁止在 MyBatis Entity 上使用 `@Data` / `@EqualsAndHashCode`；禁止使用 `@SneakyThrows`；禁止在业务代码中引入 `hutool-all`（通用工具优先 JDK 标准库，必要时仅使用 `cn.hutool:hutool-core`）。
4. **【推荐】注入 `Clock` 管理业务时间**：领域服务记录时间戳应通过注入的 `java.time.Clock` 读取，方便单元测试；Java 时间字段统一使用 `Instant` 或 `OffsetDateTime`。
5. **【禁止】`java.util.Date` 与裸浮点表示金额/额度**：时间严禁使用 `java.util.Date`；计费额度以最小单位 `long`/`BIGINT` 表示，小数时长/金额使用 `BigDecimal`（`NUMERIC`），严禁 `double`/`float`。

---

## 三、主键、ORM 与数据库变更

1. **【强制】统一采用应用侧 UUIDv7 主键**：新建业务表主键统一使用 `UUID` 类型并由应用层 [`UuidV7Generator`](../common/src/main/java/app/pagefault/visionforge/common/id/UuidV7Generator.java) 注入（48-bit 毫秒时间戳，`version = 7`、`variant = 2`）。
2. **【强制】Entity 使用 `IdType.INPUT`**：所有 `@TableId` 必须标注 `type = IdType.INPUT`，由领域服务构造实体时显式赋 UUIDv7。
3. **【禁止】使用 `IdType.ASSIGN_ID` / `ASSIGN_UUID` / Hutool UUID / `gen_random_uuid()`**：随机 UUIDv4 会严重破坏 B-Tree 索引局部性。
4. **【强制】PostgreSQL UUID TypeHandler 注册**：每个拥有独立 `@MapperScan` 的模块必须注册 UUID 处理器（如 `UuidTypeHandler`、`AgentUuidTypeHandler`、`AssetUuidTypeHandler`、`BillingUuidTypeHandler`）。
5. **【强制】保留核心 MyBatis-Plus 拦截器插件**：生产与 API/Worker 配置必须启用 `PaginationInnerInterceptor(DbType.POSTGRE_SQL)`（单页最大 `200`）、`OptimisticLockerInnerInterceptor` 与 `BlockAttackInnerInterceptor`（阻断无条件全表更新/删除）。
6. **【强制】查询安全与分页**：优先使用 `LambdaQueryWrapper`；禁用 `SELECT *`；列表接口必须设置默认与最大条数上限；客户端排序字段必须经过白名单映射，禁止拼接原始 SQL 片段。
7. **【强制】Flyway 脚本不可变**：`database/src/main/resources/db/migration/` 中已提交执行的版本脚本严禁修改内容；结构修复或演进必须追加新版本号迁移，并确保空库迁移可一次跑通。

---

## 四、事务边界与 Temporal 持久化执行

1. **【强制】短本地事务与远程隔离**：`@Transactional` 仅包裹本地数据库读写与启动请求落库。
2. **【禁止】在数据库事务中执行任何外部 I/O**：严禁在 `@Transactional` 方法内部调用大模型 API、媒体网关、S3 上传下载或 FFmpeg 子进程，防止连接池被长时间耗尽。
3. **【强制】采用 Outbox 启动补偿**：长任务必须在同一事务写业务主表（初始 `PENDING`）与 `workflow_start_request`，提交后再投递 Temporal，由 [`AgentRunStartCompensator`](../worker/src/main/java/app/pagefault/visionforge/worker/temporal/AgentRunStartCompensator.java) 兜底重启投递。
4. **【强制】付费 Activity 禁止盲目自动重试**：调用大模型与提交付费生图/生视频的 Activity（如 `invokeModel`、`submit`）以及对应的 SDK 客户端（如 `OpenAiResponsesModelClient.maxRetries(0)`）**必须**设为 `MaximumAttempts = 1`，防止网络超时导致双倍计费；允许 `poll` 与终态结算 `finalize` Activity 设置有限重试。
5. **【强制】Workflow 非阻塞等待**：需要等待远端或子任务完成的逻辑（如 `AgentRunWorkflowImpl.awaitGeneration`）必须通过 `Workflow.sleep(...)` + 短超时检查 Activity 实现，严禁在 Activity 线程里 `Thread.sleep` 长阻塞。
6. **【强制】Workflow 演进重放兼容**：修改运行中可能存在历史实例的 Workflow 控制流或参数签名时，必须评估 Temporal Determinism（确定性重放）兼容性。

---

## 五、API 契约、鉴权隔离与错误管理

1. **【强制】统一 `ApiResponse<T>` 包络与 HTTP 状态语义**：除 SSE 与文件二进制流外，所有接口必须返回 `ApiResponse`；校验失败通过 `@Valid` + `GlobalExceptionHandler` 一次聚合返回全部字段错误（`400 COMMON_INVALID_ARGUMENT`）。
2. **【禁止】以 HTTP 200 包裹业务失败或跨用户返回 403**：业务失败必须通过 `BusinessException` / `BizAssert` 映射真实 4xx/5xx 状态码；访问他人会话或资产一律返回 `404`，避免暴露资源 ID 存在性。
3. **【强制】错误码模块化与兼容稳定**：各模块维护独立 `ErrorCode` 枚举并加固定前缀（`COMMON_`、`IDENTITY_`、`ASSET_`、`BILLING_`、`AGENT_`），已发布错误码严禁更改含义或删除复用。
4. **【强制】服务端权限权威性**：鉴权与资源所有权永远从 JWT Claims 与数据库归属字段获取；前端传参、URL ID、大模型工具参数或模型返回均不可信（例如 Agent 6 个文件工具的 `conversationId` 与 `userId` 必须取自服务端执行上下文，不信任模型入参）。

---

## 六、日志、脱敏与业务审计

1. **【强制】敏感凭据零落盘、零回显**：严禁在日志、异常消息、API 响应或工具错误文案中输出密码明文、JWT / Refresh Token、`Authorization` 头或上游模型 `apiKey`；PII 字段日志输出前须经过 [`LogMasker`](../common/src/main/java/app/pagefault/visionforge/common/logging/LogMasker.java)。
2. **【强制】技术日志规范**：统一使用 SLF4J；可预期 `BusinessException` 记录 `WARN` 不打堆栈，`SystemException` 与未捕获异常记录 `ERROR` 并保留异常栈且携带 `traceId`（MDC）。
3. **【强制】关键写操作接入 `@BizLog`**：登录/登出/注册、角色变更、生图/生视频与合成提交/取消/重算等核心敏感变更操作须通过 `@BizLog` 异步独立事务写入 `audit_log`，且不得因审计落库异常回滚主业务。

---

## 七、测试要求

- **【强制】变更伴随单测**：新增核心服务、校验器（如 `StoryboardValidator`）、状态流转、工具格式化或加解密逻辑须附带 JUnit 5 + AssertJ / Mockito 测试。
- **【强制】Workflow 状态覆盖**：Workflow 编排修改须通过 Temporal `TestWorkflowEnvironment`（参见 `AgentRunWorkflowTest`、`MediaGenerationWorkflowTest`）覆盖正常完成、失败处理与等待终态分支。
- **【推荐】提交前执行模块全量回归**：至少运行受影响模块测试与依赖方测试（如 `./mvnw -B -pl common,identity,asset,billing,agent,api,worker test`）。
