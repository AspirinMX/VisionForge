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

在 IDE 中将根目录的 `pom.xml` 导入为 Maven 工程。安装 Maven 后，可在仓库根目录验证模块结构：

```sh
mvn validate
```

后端目前为模块骨架，尚未配置 Spring Boot 启动及业务依赖。

## 部署

部署文件位于仓库根目录。Dockerfile 和 Compose 文件仍为占位配置，可运行的服务定义将在后续添加。
