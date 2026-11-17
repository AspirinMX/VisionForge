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
docker compose up -d --wait postgres
./mvnw -pl database flyway:migrate
```

默认连接 `jdbc:postgresql://localhost:5432/visionforge`，用户名和密码均为 `visionforge`。Flyway 可通过 `-Dvisionforge.db.url=... -Dvisionforge.db.user=... -Dvisionforge.db.password=...` 覆盖；应用使用 `VISIONFORGE_DB_URL`、`VISIONFORGE_DB_USER`、`VISIONFORGE_DB_PASSWORD` 环境变量。

### 认证与角色

API 提供 `/api/v1/auth/register`、`/api/v1/auth/login`、`/api/v1/auth/refresh`、`/api/v1/auth/logout`、`/api/v1/auth/me`。Access Token 为短有效期 JWT；Refresh Token 轮换并以哈希形式存储。系统角色为 `ADMIN` 和 `USER`。

启动 API 前必须设置 `JWT_SECRET`（Base64 编码，至少 32 字节）：

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
```

如需初始化首个管理员，再设置 `BOOTSTRAP_ADMIN_EMAIL` 和 `BOOTSTRAP_ADMIN_PASSWORD`（至少 12 个字符）。账号不存在时会创建，已存在时会提升为 `ADMIN`。Worker 使用相同的数据库配置，需在 PostgreSQL 可用后启动。

## 部署

部署文件位于仓库根目录。Dockerfile 和 Compose 文件仍为占位配置，可运行的服务定义将在后续添加。
