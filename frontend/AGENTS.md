## 前端开发约束（强制）

以下规则适用于 `frontend/` 下的全部前端开发：

1. UI 组件体系采用 **Animate UI**（基于 Base UI 原语与 `motion` 动画原语），底层样式令牌与结构与 `shadcn`（`base-rhea`）兼容。
2. Animate UI 组件与原语优先通过 `pnpm dlx @animate-ui/cli` 或其官方 registry 获取，保持语义一致与可维护性。
3. 禁止手写造轮子式的通用基础组件库，凡 Animate UI / Base UI 能够表达的组件与交互，必须优先使用或组合现有组件。
4. 动画须保持简洁克制，服务于状态反馈（如抽屉、弹窗、折叠、悬浮按钮与标签切换），禁止干扰阅读与流式输出，并尊重系统 `reduced-motion` 设置。
5. Tailwind CSS 仅用于必要的布局、间距、响应式及组件适配，禁止用重复样式替代既有组件原语。

<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
