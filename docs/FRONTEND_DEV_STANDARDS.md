# VisionForge 前端开发规范

本文档针对 VisionForge 前端技术栈（Next.js `16.4.6` App Router + `cacheComponents`、React `19.3.0`、TypeScript strict、`@animate-ui/react`、`motion`、`@base-ui/react`、Tailwind CSS v4）确立工程标准与代码评审基线，分级采用 **【强制】**、**【推荐】** 与 **【禁止】**。

设计几何（苹果式圆角与同心半径）、全局动效体系、去开发者化交互文案与管理后台配置中心重塑细则详见配套规范：[`FRONTEND_DESIGN_SPEC.md`](FRONTEND_DESIGN_SPEC.md)。其中视觉、动效与文案重塑条款作为后续重构与新开发验收红线；当前仓库既有代码将按该规范分批改造对齐。

---

## 一、UI 组件体系、几何圆角与动效规范

1. **【强制】复用 Animate UI / Base UI 底层组件体系**：全部交互原语（`Sidebar`、`Tabs`、`Dialog`、`Sheet`、`AlertDialog`、`DropdownMenu`、`Select`、`Tooltip`、`Collapsible`、`Switch`、`Checkbox`、`Progress` 等）位于 [`frontend/src/components/ui/`](../frontend/src/components/ui)，基于 `@animate-ui/react` 与 `@base-ui/react` 构建；业务页面优先组合已有 UI 组件。
2. **【禁止】手写重复基础组件库**：禁止在业务组件目录内另起一套 Modal、Dropdown、Tabs 或 Tooltip 实现。
3. **【强制】遵循苹果式比例与同心圆角体系**：按 [`FRONTEND_DESIGN_SPEC.md`](FRONTEND_DESIGN_SPEC.md#二苹果式圆角与几何系统) 根据控件高度与容器层级选用对应圆角 Token，严禁无视控件尺寸全局硬编码 `rounded-2xl`；嵌套容器必须满足同心圆角减法约束，大中容器（`>= 12px`）优先启用 `corner-shape: squircle` 渐进增强。
4. **【禁止】多层卡片嵌套**：同一视觉层级只允许存在一层卡片/面板。禁止在 `Card`、分组面板或表格外框中再叠加一层同时具备背景、圆角与描边（或阴影）的容器，不论是否带阴影；内部分层改用分组标题、间距与 `divide-y` / `Separator`，代码块、表格外框、媒体预览等功能性内容容器只能使用浅底与描边。详见 [`FRONTEND_DESIGN_SPEC.md`](FRONTEND_DESIGN_SPEC.md#32-扁平分层与阴影节制)。
5. **【强制】建立统一全局 Motion Token 与空间连续性**：不得仅依靠局部 `@animate-ui/react` 包装，不得在业务组件散写孤立 `duration`；动效须服务于状态因果与空间连续性（分栏开合、列表重排、骨架淡入），并保留 `Dialog`/`AlertDialog` 的纯 2D Flex 居中防模糊约束（禁止在静止态保留 `perspective` 或 `filter: blur(0px)`），消除 `tw-animate-css` 冗余与 `animate-none` 等局部冲突覆盖。
6. **【强制】尊重系统 `reduced-motion` 设置**：[`AppProviders`](../frontend/src/components/providers/app-providers.tsx) 已在根部启用 `<MotionConfig reducedMotion="user">` 与 `<AnimateUIProvider reducedMotion="user">`。任何新增的 CSS 过渡/关键帧动画（如 `animate-spin`、`animate-pulse`、`transition-*`）旁必须补充 `motion-reduce:animate-none` 或 `motion-reduce:transition-none`，并在降级模式下保留必要状态确认。
7. **【禁止】使用 `transition-all`**：CSS 过渡须明确指定属性（如 `transition-colors`、`transition-opacity`、`transition-[color,box-shadow]`），避免触发高开销的全局重排重绘，且不与 `motion` 驱动属性冲突。
8. **【推荐】保持克制极简的视觉调性**：
   - 使用中性语义化 OKLCH 变量（[`globals.css`](../frontend/src/app/globals.css)），避免刺眼装饰色或营销型视觉噪声；
   - 动效仅服务于清晰的状态反馈，严禁在 Markdown 流式输出正文中加入逐字抖动动画；所有时间、额度、计数、序号统一使用 `tabular-nums`。

---

## 二、去开发者化 UI 与产品文案红线

1. **【禁止】向用户暴露内部编号、版本号与底层引擎细节**：除明确的“历史版本时间线”二级面板与“审计技术诊断折叠区”外，禁止在默认界面呈现数据库/乐观锁版本号（`v12`）、分镜内部 ID（`shot_01` / `scene-1`）、36 位 UUID（`assetId` / `fileId` / `modelDefinitionId`）、HTTP 状态码（`409`）、后端枚举常量（`ADJUST`、`ARCHIVE_FAILED`、`USER_UPLOAD`）或 `FFmpeg` / `Preset` / `CRF` 等引擎参数。
2. **【禁止】表单标签附加 JSON Schema 字段名与冗长解释文案**：禁止出现 `(firstFrame)`、`(selectedVideo)`、`(prompt)`、`priceUnits` 等括号标注，禁止在常规创作主路径暴露“JSON 源码切换”；界面文案须简洁直接，禁止科普后端实现机制或重复陈述流水类型。
3. **【禁止】在管理后台复制基础设施控制台**：禁止在 `(admin)` 界面展示外部配置中心字样、Server Addr、Namespace、Group、MD5 或裸 JSON 文本编辑框；系统运行参数统一持久化在 `system_config`，并以面向业务运维的结构化「系统参数配置」表单呈现（详见 [`FRONTEND_DESIGN_SPEC.md`](FRONTEND_DESIGN_SPEC.md#六管理后台系统参数配置中心重塑规范)）。

---

## 三、路由边界与架构隔离

1. **【强制】严格维护 `(auth)` / `(studio)` / `(admin)` 三组路由隔离**：
   - 公开鉴权页放入 `(auth)`；
   - 创作者工作台与会话/任务/账户页面放入 `(studio)`，由 `StudioAuthGuard` + `ConversationsProvider` + `CreditProvider` + `AppShell` 保护；
   - 系统管理功能放入独立 [`(admin)`](../frontend/src/app/(admin)/layout.tsx) 路由组，使用 `AdminRoleGuard` + `AdminShell`。
2. **【禁止】将管理控制台功能混入创作工作台导航**：禁止在 `AppShell` 创作者侧栏直接平铺后台管理导航或内部调试文案；管理员仅可通过个人菜单低干扰入口或直接 URL 进入 `/admin/*`。
3. **【禁止】在 `(admin)` 布局挂载 Studio 业务上下文**：`(admin)` 不依赖也不触发 `/api/v1/conversations` 或 `/api/v1/account/credit` 等创作者接口请求。

---

## 四、Next.js 16、React 19 与 TypeScript 规范

1. **【强制】兼容 `cacheComponents: true`**：[`next.config.ts`](../frontend/next.config.ts) 启用了 `cacheComponents: true` 与 `partialPrefetching: true`。凡使用 `useSearchParams`、`useRouter`、浏览器存储或 Animate UI / Motion  hooks 的交互组件，必须在文件首行声明 `"use client"`；动态页或流式边界注意按 Next.js 16 静态预渲染约束放置 `<Suspense>` 或 Client 包装。
2. **【强制】TypeScript 严格类型检查**：`tsconfig.json` 开启 `strict: true`，禁止使用 `any`（不确定 JSON 负载请使用 `unknown` + 类型守卫缩窄，参考 `sse.ts`）。
3. **【强制】Base UI `render` 属性规范**：本项目的 Base UI 原语组件通过 `render={<Link ... />}` 或 `render={<Button ... />}` 组合触发器（如 `SidebarMenuButton`、`DialogClose`、`TooltipTrigger`），遵循语义化无嵌套交互标签规则，禁止出现 `<button><button>` 或 `<a><button>` 嵌套。
4. **【推荐】通过 `useAsyncData` 等受控 Hook 加载数据**：遵循异步取消标记（`cancelled`）与显式加载/错误状态分离（`ApiErrorAlert`），不要遗漏卸载后的状态竞态处理。

---

## 五、API 客户端、认证刷新与 SSE 流消费

1. **【强制】所有 API 调用收口在 `src/lib/api/`**：业务组件禁止散落裸 `fetch("/api/v1/...")`，必须通过 [`endpoints.ts`](../frontend/src/lib/api/endpoints.ts) 与 [`client.ts`](../frontend/src/lib/api/client.ts) 发起。
2. **【强制】保持同源相对路径 `/api/v1/...`**：前端浏览器请求全部通过同源相对路径经由 Next.js 代理路由 [`route.ts`](../frontend/src/app/api/v1/[...path]/route.ts) 转发，禁止在客户端代码写死后端主机名或绕过代理导致跨环境镜像失效。
3. **【强制】遵守 Single-Flight 令牌刷新机制**：由于后端 `/api/v1/auth/refresh` 会轮换并立刻作废旧 Refresh Token，禁止绕过 `token-store.ts` 的 `refreshAuthSession` 单飞锁自行并发刷新 Token；SSE 流式请求在遇 401 时亦须复用 `forceRefreshAccessToken()`。
4. **【强制】使用 `fetch` ReadableStream 消费 SSE**：[`sse.ts`](../frontend/src/lib/api/sse.ts) 已封装带认证头的 `POST` SSE 读取器；**禁止**改用原生 `EventSource`（无法携带 `Authorization: Bearer` 头及 JSON Body）。
5. **【强制】客户端分支只判断 `error.code`**：处理错误时使用 `ApiError.code`（如 `"AGENT_CONVERSATION_BUSY"`、`ASSET_FILE_VERSION_CONFLICT`），**禁止**字符串匹配中文错误提示 `message`。
6. **【推荐】长任务创建传递幂等键**：发起生成任务等写请求时附带 `Idempotency-Key`（默认由 `createIdempotencyKey()` 生成）。

---

## 六、布局安全与多媒体资源约定

1. **【强制】固定高度可滚动面板防塌陷**：使用 `react-resizable-panels` 的区域及嵌套的聊天消息流/悬浮输入栏时：
   - 外层壳 `SidebarInset` 须固定为 `flex h-svh min-w-0 flex-col overflow-hidden`；
   - `ResizablePanel` 必须保持 `style={{ overflow: "hidden", height: "100%" }}`（或等价固定高度流）且内部容器使用 `flex h-full min-h-0 flex-col`；
   - `ChatPane` 滚动画布必须具备 `min-h-0 flex-1 overflow-y-auto` 并为悬浮胶囊输入框预留底部安全留白，保证输入框始终悬浮在底部而非落入页面尾部滚动流。
2. **【禁止】持久化预签名媒体 URL**：图片与视频播放不得把短效 `downloadUrl` 写入本地长期缓存或路由参数；应按 `assetId` 按需向 `GET /api/v1/assets/{assetId}/download-url` 获取，并在加载失败（如过期）时支持重刷 URL（见 [`media-preview.tsx`](../frontend/src/components/common/media-preview.tsx)）。
3. **【强制】S3 直传严格对齐响应头**：向预签名 `uploadUrl` 发起 `PUT` 时必须原样附带 `UploadTicketResponse.requiredHeaders`（含精确 `Content-Type`），否则 RustFS / S3 签名校验或服务端 `/complete` 校验会失败。

---

## 七、提交前检查门禁

- **【强制】ESLint 零错误**：执行 `pnpm --dir frontend lint`。
- **【强制】TypeScript 零报错**：执行 `tsc --noEmit`（若因删除或移动 App Router 路由导致 `.next/dev/types/validator.ts` 陈旧报错，先清理陈旧类型缓存或执行 `npx next typegen`）。
- **【强制】Next.js 生产构建通过**：变更路由、边界或 Provider 后执行 `pnpm --dir frontend build` 确认全部路由静态生成与 SSR/Client 边界无异常。
- **【推荐】动效与可访问性自检**：增改动画后运行 `pnpm --dir frontend dlx @animate-ui/cli@latest audit` 检查是否存在遗漏 `reduced-motion` 或高成本 CSS 动画属性。
