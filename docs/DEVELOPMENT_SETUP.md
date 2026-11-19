# VisionForge 本地开发、环境配置与运维排障指南

本文档覆盖本地开发环境搭建、全量环境变量参考（对齐 [`.env.example`](../.env.example) 与 [`application.yaml`](../api/src/main/resources/application.yaml)）、本地免付费媒体 Mock 使用方式，以及构建测试与常见排障步骤。

---

## 1. 环境依赖

| 组件 | 要求 | 用途 |
| --- | --- | --- |
| **JDK** | `>= 21`（由根 `pom.xml` Enforcer 校验） | 编译运行 8 个后端模块 |
| **Maven** | 仓库内置 `./mvnw`（Maven `>= 3.9`） | 无需单独安装本机 Maven |
| **Node.js & pnpm** | 推荐 Node `>= 20` 与 `pnpm 12.21.0` | 前端开发、Lint、构建 |
| **Docker & Compose** | Docker Engine + Compose v2 | 启动 Postgres、Redis、RustFS、Temporal |
| **FFmpeg / ffprobe** | 建议安装并在 `PATH` 可见 | 本地运行 Worker 视频合成及 Mock 视频生成 |

---

## 2. 本地开发启动流水线

### 2.1 启动开发基础设施

```sh
cp .env.example .env
docker compose up -d --wait postgres redis rustfs temporal temporal-ui
docker compose up rustfs-init
```

各基础服务默认端口：

- PostgreSQL 18：`localhost:5432`（库/用户/密码默认 `visionforge`）
- Redis 7：`localhost:6379`
- RustFS S3 & Web Console：`http://localhost:9000` / `http://localhost:9001`（默认账号密码 `rustfsadmin`）
- Temporal gRPC & UI：`localhost:7233` / `http://localhost:8233`
- 系统参数配置持久化在 PostgreSQL 的 `system_config` 表，管理端 `/admin/configs` 直接读写；API 与 Worker 按 `CONFIG_REFRESH_INTERVAL_MS` 轮询版本并刷新本地缓存。

### 2.2 执行 Flyway 业务数据库迁移

迁移脚本位于 `database/src/main/resources/db/migration/`，不会在 API 或 Worker 启动时自动执行：

```sh
./mvnw -pl database flyway:migrate
```

如需连接非默认数据库地址，可传 Maven 属性覆盖：

```sh
./mvnw -pl database flyway:migrate \
  -Dvisionforge.db.url=jdbc:postgresql://localhost:5432/visionforge \
  -Dvisionforge.db.user=visionforge \
  -Dvisionforge.db.password=visionforge
```

### 2.3 生成必要密钥并启动 API 与 Worker

`JWT_SECRET`（至少 32 字节 Base64）与 `MODEL_CONFIG_SECRET`（严格 32 字节 Base64）为强制配置：

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
# 可选：首次启动自动创建或提升指定邮箱为 ADMIN
export BOOTSTRAP_ADMIN_EMAIL="admin@example.com"
export BOOTSTRAP_ADMIN_PASSWORD="StrongPassword123!"
export BOOTSTRAP_ADMIN_DISPLAY_NAME="Administrator"
```

在两个终端中运行：

```sh
./mvnw -pl api spring-boot:run
./mvnw -pl worker spring-boot:run
```

> [!TIP]
> 在 Windows PowerShell 中设置临时环境变量时，可使用：
> `$env:JWT_SECRET = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))`
> 同样方式设置 `$env:MODEL_CONFIG_SECRET`（精确 32 字节 Base64）。

### 2.4 启动前端

```sh
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

前端默认运行在 [http://localhost:3000](http://localhost:3000)。`/api/v1/*` 请求默认代理至 `http://127.0.0.1:8080`；如需指向其他后端端口/地址，设置环境变量 `VISIONFORGE_API_BASE_URL` 即可，无需重新构建前端。

---

## 3. 全量环境变量清单

### 3.1 数据库与基础设施连接

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `VISIONFORGE_DB_URL` | `jdbc:postgresql://localhost:5432/visionforge` | 业务数据库 JDBC URL |
| `VISIONFORGE_DB_USER` / `_PASSWORD` | `visionforge` | 数据库凭据 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 地址（SSE 实时流必需） |
| `STREAM_TIMEOUT_MS` | `300000` | 单次 SSE `SseEmitter` 最大连接时长（毫秒） |
| `TEMPORAL_TARGET` | `localhost:7233` | Temporal gRPC 服务地址 |
| `TEMPORAL_NAMESPACE` | `default` | Temporal Namespace |
| `TEMPORAL_TASK_QUEUE` | `vision-forge` | Worker 监听任务队列名 |
| `SERVER_PORT` | `8080` | `api` 服务监听端口 |

### 3.2 安全、模型配置与计费

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | **必填** | Base64 密钥（>= 32 字节），用于 HS256 JWT 签验 |
| `BCRYPT_STRENGTH` | `12` | 密码哈希 cost（测试环境可调低） |
| `CORS_ENABLED` / `CORS_ALLOWED_ORIGINS` | `true` / `http://localhost:3000,http://127.0.0.1:3000` | API 跨域白名单控制与启用开关 |
| `RATE_LIMIT_ENABLED` / `RATE_LIMIT_*_PER_MINUTE` | `true` / `auth=20`, `redeem=10`, `generation=30`, `upload=30` | Redis 滑动窗口限流配置（分钟配额） |
| `RATE_LIMIT_TRUST_FORWARDED_HEADERS` | `false` | 仅在受信任反向代理后启用 `X-Forwarded-For` 解析 |
| `MODEL_CONFIG_SECRET` | **必填** | Base64 密钥（精确 32 字节），用于 AES-GCM 加解密模型 API Key |
| `BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` / `_DISPLAY_NAME` | 空 | 首次引导管理员账号（密码长度须在 12–72 字符） |
| `BILLING_INITIAL_CREDIT_UNITS` | `10000` | 用户额度账户首次读取或任务初始化时授予的初始额度 |
| `UPSTREAM_USER_AGENT` | 空 | 覆盖发往 OpenAI / 方舟 / 百炼及结果下载的 `User-Agent` |

### 3.3 对象存储（RustFS / S3）

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `STORAGE_BACKEND` | `s3` | 存储驱动 |
| `STORAGE_ENDPOINT` | `http://localhost:9000` | API / Worker 服务端访问 S3 内网地址 |
| `STORAGE_PUBLIC_ENDPOINT` | `http://localhost:9000` | 签发给浏览器直传与下载的公网可达地址 |
| `STORAGE_REGION` / `STORAGE_BUCKET` | `us-east-1` / `visionforge` | 区域与默认桶名 |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | 空（`.env.example` 为 `rustfsadmin`） | S3 访问密钥对 |
| `STORAGE_PATH_STYLE` | `true` | RustFS / MinIO 需设为 `true` 启用 Path-Style |
| `STORAGE_PRESIGN_TTL` / `STORAGE_UPLOAD_SESSION_TTL` | `15m` / `1h` | 预签名 URL 有效期与上传会话 TTL |
| `STORAGE_MAX_UPLOAD_BYTES` | `2147483648` | 单文件最大上传字节数（2 GiB） |

### 3.4 Worker、上下文压缩与 FFmpeg 合成

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `CONTEXT_COMPACT_THRESHOLD` | `0.8` | 触发本地上下文压缩的预算占比阈值 |
| `CONTEXT_COMPACT_KEEP_RATIO` | `0.25` | 压缩时尾部保留未压缩消息的预算占比 |
| `CONTEXT_SUMMARY_MAX_OUTPUT_TOKENS` | `1024` | 压缩摘要最大输出 Token |
| `FFMPEG_PATH` / `FFPROBE_PATH` | `ffmpeg` / `ffprobe` | 可执行文件路径（Mock 脚本亦会读取 `FFMPEG_PATH`） |
| `COMPOSITION_PART_SIZE_BYTES` | `8388608` | 合成输出 S3 分片大小（>= 5 MiB） |
| `COMPOSITION_TIMEOUT` / `COMPOSITION_PRESIGN_TTL` | `30m` / `2h` | 成片超时与源片读取预签名有效期 |
| `MEDIA_ALLOW_HTTP` | `false` | **仅限本地开发**：允许 Worker 通过明文 HTTP 下载结果 |
| `WORKER_MAX_CONCURRENT_ACTIVITIES` / `WORKFLOW_TASKS` | `20` / `20` | Worker Activity 与 Workflow Task 最大并发 |
| `WORKER_START_COMPENSATION_*` | `enabled=true`, `interval-ms=5000`, `batch-size=20`, `max-attempts=20` | 启动补偿扫描配置 |
| `CONFIG_REFRESH_INTERVAL_MS` | `5000` | API 与 Worker 检查 `system_config` 版本变化的间隔（毫秒） |
| `ALERT_ENABLED` / `ALERT_MAIL_*` | `true` / 空（端口默认 `587`） | 全局告警开关、SMTP 主机/端口/发件人与密码（非密钥项亦可在 `/admin/configs` 热更新） |
| `ALERT_WEBHOOK_ALLOW_PRIVATE_NETWORK` | `false` | 是否允许 Webhook 指向内网/回环地址（默认拦截以防 SSRF） |

---

## 4. 本地媒体 Mock（免付费联调）

`scripts/mock_media_server.py` 模拟了 OpenAI Images、Volcengine Ark Images/Video 与 Alibaba DashScope Image/Video 协议端口：

```sh
python scripts/mock_media_server.py --port 8000
```

在 Worker 进程启用 `MEDIA_ALLOW_HTTP=true` 后，进入独立管理后台 [`/admin/models`](http://localhost:3000/admin/models) 按需配置协议基址：

- `OPENAI_IMAGES`：`http://127.0.0.1:8000/v1`
- `ARK_IMAGES` / `ARK_VIDEO_TASKS`：`http://127.0.0.1:8000`
- `DASHSCOPE_*`：`http://127.0.0.1:8000/api/v1`

支持提示词故障注入标记：`[mock:fail]`（触发上游失败）、`[mock:slow]`（延迟响应）、`[mock:empty]`（空结果，用于测边界）。默认按请求 `duration`（未传时默认 5 秒，可通过 `--video-duration` 调整）使用 `--ffmpeg-path`、`FFMPEG_PATH` 或 `PATH` 中的 `ffmpeg` 生成对应时长的 MP4 并缓存；也可通过 `--video-file <path>` 直接指定现成样片。

---

## 5. 全栈生产容器部署

`docker-compose.prod.yaml` 内含一次性 Flyway `migrate` 容器以及三个非 root 构建镜像（`Dockerfile.api`、`Dockerfile.worker`、`Dockerfile.frontend`）：

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
docker compose -f docker-compose.prod.yaml up -d --build
```

部署注意：
1. 将 `STORAGE_PUBLIC_ENDPOINT` 修改为浏览器真正可达的对象存储网关地址（如 HTTPS 反代域名）；
2. 将 `TEMPORAL_CORS_ORIGINS` 配置为实际的 Temporal UI 访问域名；
3. 当前 Compose 使用 `temporalio/auto-setup:1.29.5` 初始化默认命名空间，生产集群建议使用独立托管 Temporal 服务。

---

## 6. 常用验证命令与故障排查

### 验证命令

```sh
# 后端全模块测试
./mvnw -B test

# 按模块运行（如仅测 agent 与 api）
./mvnw -B -pl agent,api test

# 前端规范、类型检查与生产构建
pnpm --dir frontend lint
frontend\node_modules\.bin\tsc.cmd --project frontend/tsconfig.json --noEmit
pnpm --dir frontend build
```

### 常见问题排查

| 现象 | 根因 | 处理方案 |
| --- | --- | --- |
| 启动 API/Worker 直接报错 `MODEL_CONFIG_SECRET must be exactly 32 bytes` | Base64 解码字节数不足或超出 32 字节 | 使用 `openssl rand -base64 32` 重新生成严格 32 字节密钥 |
| 上传文件卡在直传阶段或 `complete` 失败 | `STORAGE_PUBLIC_ENDPOINT` 不可达或 `PUT` 请求未带完全一致的 `Content-Type` | 核对 `STORAGE_PUBLIC_ENDPOINT` 与前端 PUT 头匹配 `requiredHeaders` |
| 非管理员访问 `/admin` 显示拒绝访问 | 该用户 `role` 为 `USER` | 配置 `BOOTSTRAP_ADMIN_*` 或由现有管理员在 `/admin/users` 授角色 |
| `tsc` 报错引用旧删除路径的 `.next/dev/types/validator.ts` | 移动或删除 Next.js App Router 文件后残余 `.next/dev/types` 缓存 | 删除 `.next/dev/types` 并重新运行 `npx next typegen` 或重启 `pnpm dev` |
