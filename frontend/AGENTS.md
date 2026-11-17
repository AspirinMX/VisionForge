## 前端开发约束（强制）

以下规则适用于 `frontend/` 下的全部前端开发：

1. 必须使用 shadcn，且固定使用 `--preset b27GcrRo`，不得替换为其他 preset。
2. 该 preset 只能通过 `pnpm dlx` 获取，禁止使用其他获取方式，也禁止手工仿造该 preset 的配置或样式。
3. 禁止引入或使用其他任何 UI 组件库。
4. 禁止手写组件库，不得自行仿写通用基础组件或另建一套 UI 组件体系。
5. 凡是 shadcn 组件能够表达的界面元素或交互，必须使用对应组件及其组合，禁止用自定义基础组件或堆砌 Tailwind 类重复实现。
6. Tailwind 仅用于必要的布局、间距、响应式及组件适配，禁止滥用。

<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
