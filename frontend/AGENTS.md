## 前端开发约束（强制）

以下规则适用于 `frontend/` 下的全部前端开发，完整细则见 [`docs/FRONTEND_DEV_STANDARDS.md`](../docs/FRONTEND_DEV_STANDARDS.md) 与 [`docs/FRONTEND_DESIGN_SPEC.md`](../docs/FRONTEND_DESIGN_SPEC.md)：

1. UI 组件体系基于 **Animate UI**（`@animate-ui/react`）与 `@base-ui/react` 原语构建，严禁在业务目录造轮子替代通用原语。
2. 几何与圆角遵循**苹果式比例阶梯与同心圆角约束**（优先启用 `corner-shape: squircle` 渐进增强），禁止无视控件高度全量硬编码 `rounded-2xl`；**禁止多层卡片嵌套**——`Card`、分组面板或表格外框内不得再叠一层同时具备背景、圆角与描边（或阴影）的容器，内部分层改用分组标题、间距与 `divide-y` / `Separator`。
3. 动效必须纳入统一的 **全局 Motion Token 体系**（`MICRO` / `CONTROL` / `SURFACE` / `SPATIAL`），保证工作区开关与列表重排的空间连续性，保持 `Dialog`/`AlertDialog` 纯 2D Flex 居中防模糊，并尊重系统 `reduced-motion` 设置。
4. 界面严格**去开发者化**：禁止向用户暴露内部版本号（`vN`）、镜头/资产/任务 ID（`shot_01` / UUID）、JSON Schema 字段名、`FFmpeg` 编码参数、后端枚举常量或冗长自述文案；管理后台禁用 Nacos 控制台套壳，改为结构化「系统参数配置」。
5. Tailwind CSS 仅用于布局、间距、响应式与令牌消费，禁止使用 `transition-all` 或用重复内联样式替代组件原语。

<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
