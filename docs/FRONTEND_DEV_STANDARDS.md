# VisionForge 前端开发规范

本文档针对 VisionForge 前端技术栈（Next.js `16.4.6` App Router + `cacheComponents`、React `19.3.0`、TypeScript strict、`@animate-ui/react`、`motion`、`@base-ui/react`、Tailwind CSS v4）确立工程标准与代码评审基线，分级采用 **【强制】**、**【推荐】** 与 **【禁止】**。

---

## 一、UI 组件体系与 Animate UI 约定

1. **【强制】复用 Animate UI 底层组件体系**：全部交互原语（`Sidebar`、`Tabs`、`Dialog`、`Sheet`、`AlertDialog`、`DropdownMenu`、`Select`、`Tooltip`、`Collapsible`、`Switch`、`Checkbox`、`Progress` 等）位于 [`frontend/src/components/ui/`](../frontend/src/components/ui)，基于 `@animate-ui/react` 与 `@base-ui/react` 构建；业务页面优先组合已有 UI 组件。
2. **【禁止】手写重复基础组件库**：禁止在业务组件目录内另起一套 Modal、Dropdown、Tabs 或 Tooltip 实现。
3. **【强制】尊重系统 `reduced-motion` 设置**：[`AppProviders`](../frontend/src/components/providers/app-providers.tsx) 已在根部启用 `<MotionConfig reducedMotion="user">` 与 `<AnimateUIProvider reducedMotion="user">`。任何新增的 CSS 过渡/关键帧动画（如 `animate-spin`、`animate-pulse`、`transition-*`）旁必须补充 `motion-reduce:animate-none` 或 `motion-reduce:transition-none`。
4. **【禁止】使用 `transition-all`**：CSS 过渡须明确指定属性（如 `transition-colors`、`transition-opacity`、`transition-[color,box-shadow]`），避免触发高开销的全局重排重绘，且不与 Animate UI `motion` 驱动属性冲突。
5. **【推荐】保持克制极简的视觉调性**：
   - 使用中性语义化 OKLCH 变量（[`globals.css`](../frontend/src/app/globals.css)），避免刺眼装饰色或营销型视觉噪声；
   - 动效仅服务于清晰的状态反馈（如思考过程 `ShimmeringText`、工具条温和淡入、悬浮回到底部按钮 `AnimatePresence`、标签/侧栏指示器滑移），严禁在 Markdown 流式输出正文中加入逐字抖动动画。

---

## 二、路由边界与架构隔离

1. **【强制】严格维护 `(auth)` / `(studio)` / `(admin)` 三组路由隔离**：
   - 公开鉴权页放入 `(auth)`；
   - 创作者工作台与会话/任务/账户页面放入 `(studio)`，由 `StudioAuthGuard` + `ConversationsProvider` + `CreditProvider` + `AppShell` 保护；
   - 系统管理功能放入独立 [`(admin)`](../frontend/src/app/(admin)/layout.tsx) 路由组，使用 `AdminRoleGuard` + `AdminShell`。
2. **【禁止】将管理控制台功能混入创作工作台导航**：禁止在 `AppShell` 创作者侧栏直接平铺后台管理导航或内部调试文案；管理员仅可通过个人菜单低干扰入口或直接 URL 进入 `/admin/*`。
3. **【禁止】在 `(admin)` 布局挂载 Studio 业务上下文**：`(admin)` 不依赖也不触发 `/api/v1/conversations` 或 `/api/v1/account/credit` 等创作者接口请求。

---

## 三、Next.js 16、React 19 与 TypeScript 规范

1. **【强制】兼容 `cacheComponents: true`**：[`next.config.ts`](../frontend/next.config.ts) 启用了 `cacheComponents: true` 与 `partialPrefetching: true`。凡使用 `useSearchParams`、`useRouter`、浏览器存储或 Animate UI / Motion  hooks 的交互组件，必须在文件首行声明 `"use client"`；动态页或流式边界注意按 Next.js 16 静态预渲染约束放置 `<Suspense>` 或 Client 包装。
2. **【强制】TypeScript 严格类型检查**：`tsconfig.json` 开启 `strict: true`，禁止使用 `any`（不确定 JSON 负载请使用 `unknown` + 类型守卫缩窄，参考 `sse.ts`）。
3. **【强制】Base UI `render` 属性规范**：本项目的 Base UI 原语组件通过 `render={<Link ... />}` 或 `render={<Button ... />}` 组合触发器（如 `SidebarMenuButton`、`DialogClose`、`TooltipTrigger`），遵循语义化无嵌套交互标签规则，禁止出现 `<button><button>` 或 `<a><button>` 嵌套。
4. **【推荐】通过 `useAsyncData` 等受控 Hook 加载数据**：遵循异步取消标记（`cancelled`）与显式加载/错误状态分离（`ApiErrorAlert`），不要遗漏卸载后的状态竞态处理。

---

## 四、API 客户端、认证刷新与 SSE 流消费

1. **【强制】所有 API 调用收口在 `src/lib/api/`**：业务组件禁止散落裸 `fetch("/api/v1/...")`，必须通过 [`endpoints.ts`](../frontend/src/lib/api/endpoints.ts) 与 [`client.ts`](../frontend/src/lib/api/client.ts) 发起。
2. **【强制】保持同源相对路径 `/api/v1/...`**：前端浏览器请求全部通过同源相对路径经由 Next.js 代理路由 [`route.ts`](../frontend/src/app/api/v1/[...path]/route.ts) 转发，禁止在客户端代码写死后端主机名或绕过代理导致跨环境镜像失效。
3. **【强制】遵守 Single-Flight 令牌刷新机制**：由于后端 `/api/v1/auth/refresh` 会轮换并立刻作废旧 Refresh Token，禁止绕过 `token-store.ts` 的 `refreshAuthSession` 单飞锁自行并发刷新 Token；SSE 流式请求在遇 401 时亦须复用 `forceRefreshAccessToken()`。
4. **【强制】使用 `fetch` ReadableStream 消费 SSE**：[`sse.ts`](../frontend/src/lib/api/sse.ts) 已封装带认证头的 `POST` SSE 读取器；**禁止**改用原生 `EventSource`（无法携带 `Authorization: Bearer` 头及 JSON Body）。
5. **【强制】客户端分支只判断 `error.code`**：处理错误时使用 `ApiError.code`（如 `"AGENT_CONVERSATION_BUSY"`、`ASSET_FILE_VERSION_CONFLICT`），**禁止**字符串匹配中文错误提示 `message`。
6. **【推荐】长任务创建传递幂等键**：发起生成任务等写请求时附带 `Idempotency-Key`（默认由 `createIdempotencyKey()` 生成）。

---

## 五、布局安全与多媒体资源约定

1. **【强制】固定高度可滚动面板防塌陷**：使用 `react-resizable-panels` 的区域及嵌套的聊天消息流/悬浮输入栏时：
   - 外层壳 `SidebarInset` 须固定为 `flex h-svh min-w-0 flex-col overflow-hidden`；
   - `ResizablePanel` 必须保持 `style={{ overflow: "hidden", height: "100%" }}`（或等价固定高度流）且内部容器使用 `flex h-full min-h-0 flex-col`；
   - `ChatPane` 滚动画布必须具备 `min-h-0 flex-1 overflow-y-auto` 并为悬浮胶囊输入框预留底部安全留白，保证输入框始终悬浮在底部而非落入页面尾部滚动流。
2. **【禁止】持久化预签名媒体 URL**：图片与视频播放不得把短效 `downloadUrl` 写入本地长期缓存或路由参数；应按 `assetId` 按需向 `GET /api/v1/assets/{assetId}/download-url` 获取，并在加载失败（如过期）时支持重刷 URL（见 [`media-preview.tsx`](../frontend/src/components/common/media-preview.tsx)）。
3. **【强制】S3 直传严格对齐响应头**：向预签名 `uploadUrl` 发起 `PUT` 时必须原样附带 `UploadTicketResponse.requiredHeaders`（含精确 `Content-Type`），否则 RustFS / S3 签名校验或服务端 `/complete` 校验会失败。

---

## 六、提交前检查门禁

- **【强制】ESLint 零错误**：执行 `pnpm --dir frontend lint`。
- **【强制】TypeScript 零报错**：执行 `tsc --noEmit`（若因删除或移动 App Router 路由导致 `.next/dev/types/validator.ts` 陈旧报错，先清理陈旧类型缓存或执行 `npx next typegen`）。
- **【强制】Next.js 生产构建通过**：变更路由、边界或 Provider 后执行 `pnpm --dir frontend build` 确认全部路由静态生成与 SSR/Client 边界无异常。
- **【推荐】动效与可访问性自检**：增改动画后运行 `pnpm --dir frontend dlx @animate-ui/cli@latest audit` 检查是否存在遗漏 `reduced-motion` 或高成本 CSS 动画属性。
