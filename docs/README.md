# VisionForge 文档中心

本目录面向 VisionForge 维护者、前后端开发者与运维人员，作为仓库的技术与维护文档中心。当文档与代码、Flyway 迁移或测试存在差异时，**以当前代码、迁移和测试为准，并在同一次变更中同步修正文档**。

## 推荐阅读顺序

| 顺序 | 文档 | 适用对象 | 说明 |
| --- | --- | --- | --- |
| 1 | [ARCHITECTURE.md](ARCHITECTURE.md) | 架构师、全栈研发 | 系统拓扑、后端 8 模块边界、Temporal 编排、Redis SSE、对象存储与安全边界 |
| 2 | [DESIGN_DETAILS.md](DESIGN_DETAILS.md) | 后端研发、前端研发 | 核心表模型、状态机设计、上下文压缩、分镜 Schema、前端工作区与独立管理端设计 |
| 3 | [API_REFERENCE.md](API_REFERENCE.md) | 前端研发、联调测试 | `/api/v1/**` 全部 HTTP/SSE 接口契约、统一包络、错误码矩阵与代理转发约定 |
| 4 | [BACKEND_DEV_STANDARDS.md](BACKEND_DEV_STANDARDS.md) | 后端研发、评审人员 | 本项目后端 Java / Spring Boot / MyBatis-Plus / Temporal 开发规范（【强制 / 推荐 / 禁止】） |
| 5 | [FRONTEND_DEV_STANDARDS.md](FRONTEND_DEV_STANDARDS.md) | 前端研发、评审人员 | 本项目前端 Next.js / React / Animate UI 工程规范（【强制 / 推荐 / 禁止】） |
| 6 | [FRONTEND_DESIGN_SPEC.md](FRONTEND_DESIGN_SPEC.md) | 前端研发、设计评审 | 前端视觉几何（苹果式圆角与卡片分层）、全局动效、去开发化文案与后台配置中心重塑规范 |
| 7 | [DEVELOPMENT_SETUP.md](DEVELOPMENT_SETUP.md) | 全体贡献者、运维 | 本地基础设施搭建、数据库迁移、环境变量清单、Mock 网关与常见问题排查 |

## 仓库规则

- [CONTRIBUTING.md](../CONTRIBUTING.md)
- [AGENTS.md](../AGENTS.md)
- [frontend/AGENTS.md](../frontend/AGENTS.md)
