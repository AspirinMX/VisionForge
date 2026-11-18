# VisionForge 文档中心

本目录面向 VisionForge 维护者、前后端开发者与运维人员，作为仓库的单一权威技术资料库。当文档与代码、Flyway 迁移或测试存在差异时，**以当前代码、迁移和测试为准，并在同一次变更中同步修正文档**。

## 推荐阅读顺序

初次进入项目或开展跨模块研发前，建议按以下顺序阅读：

| 顺序 | 文档 | 目标读者 | 核心内容 |
| --- | --- | --- | --- |
| 1 | [ARCHITECTURE.md](ARCHITECTURE.md) | 架构师、全栈研发 | 8 模块依赖拓扑与可见性边界、Temporal 持久化工作流、Redis SSE 事件桥、RustFS 对象存储与加密安全边界 |
| 2 | [DESIGN_DETAILS.md](DESIGN_DETAILS.md) | 后端研发、前端研发 | V1–V15 数据模型演进、生成/计费/归档状态机、Context Compaction 与推理重放、分镜 Schema v1、前端路由隔离（Studio vs Admin）与 Animate UI 设计 |
| 3 | [API_REFERENCE.md](API_REFERENCE.md) | 前端研发、联调测试 | 全部 `/api/v1/**` 端点契约、统一包络、SSE 事件帧协议、上传直传流程、全部模块错误码矩阵与 Next.js BFF 代理约定 |
| 4 | [BACKEND_DEV_STANDARDS.md](BACKEND_DEV_STANDARDS.md) | 后端研发、Code Reviewer | 对标《阿里巴巴 Java 开发手册》风格的【强制 / 推荐 / 禁止】后端开发红线与工程实践 |
| 5 | [FRONTEND_DEV_STANDARDS.md](FRONTEND_DEV_STANDARDS.md) | 前端研发、Code Reviewer | Next.js 16 + React 19 + Animate UI、单飞令牌刷新、流式消费、布局防塌陷及无障碍【强制 / 推荐 / 禁止】前端开发规范 |
| 6 | [DEVELOPMENT_SETUP.md](DEVELOPMENT_SETUP.md) | 全体贡献者、运维 | 本地 Docker Compose 基础栈、Flyway 迁移、前后端启动、Mock 媒体网关、环境变量清单与常见排障指南 |

## 协作与提交规范

- 任何代码或文档提交必须遵守 [CONTRIBUTING.md](../CONTRIBUTING.md) 中的多行提交信息规范。
- 前端与仓库根级约束请同步查阅 [AGENTS.md](../AGENTS.md) 与 [frontend/AGENTS.md](../frontend/AGENTS.md)。
