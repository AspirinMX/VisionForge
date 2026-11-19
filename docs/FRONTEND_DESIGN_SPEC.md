# VisionForge 前端设计、动效与交互文案规范

本文档与 [`FRONTEND_DEV_STANDARDS.md`](FRONTEND_DEV_STANDARDS.md) 共同构成 VisionForge 前端（`(auth)`、`(studio)`、`(admin)`）的**视觉几何、动效体系、产品文案与管理端信息架构规范**，作为后续全站重构与新增页面评审的统一设计规范。

> **基线状态说明**：本规范于 2026-11-20 针对现有前端的视觉圆角、开发者术语泄露、动效割裂及后台 Nacos 配置错位问题制定。同日落地的改造提交已完成圆角与 Motion Token 体系、基础原语映射、多层卡片拆除、创作端与管理端文案去开发者化、画布/镜头列表/媒体加载连续性动效，以及结构化「系统参数配置」页面；Tabs 内容交叉淡入、告警与审计高级筛选收纳等细项继续按 §7 推进。

---

## 一、设计定位与核心原则

VisionForge 的核心界面（对话与分镜工作台 `(studio)`、运营管理后台 `(admin)`）均属于高频任务型工具界面（**Operate Mode**）。设计目标不是炫耀装饰或暴露系统架构，而是让界面退居创作与管理任务本身之后。

1. **几何即秩序（苹果式比例与同心圆角）**：圆角弧度必须与组件高度、容器层级及内外间距成比例并保持同心关系，辅以连续曲率平滑（Squircle）。
2. **动效即因果（全局物理规律）**：动效必须解释状态切换、空间位置与操作反馈，由统一的物理令牌驱动，禁止零散硬编码或全局失调。
3. **语言即产品（彻底去开发者化）**：面向创作者与运营人员表达人类意图，严禁在界面中暴露内部 ID、数据库枚举、版本号、底层引擎名称与原始 JSON。
4. **后台即业务控制面（聚焦业务参数）**：基础设施运维交还给专业控制台（如 Nacos 原生控制台），管理后台仅保留面向业务运营的语义化开关与参数表单。

---

## 二、苹果式圆角与几何系统

### 2.1 现有反模式（禁止项）

- **【禁止】固定弧度全覆盖**：禁止像当前 `shadcn (base-rhea)` 模板那样把 `rounded-2xl`（`18px`）无差别套用在 `20px` 高的 `Badge`、`24px` 高的 `xs` 按钮、`32px` 高的 `Input` 与 `400px+` 高的 `Textarea` / 表格容器上。
- **【禁止】忽略内外间距直接套用同级圆角**：禁止出现外层 `TabsList`（`p-[3px]`）与内层 `TabsTrigger` 共用同一半径，或 `Card`（`24px`）内部任意混用 `rounded-2xl` / `rounded-xl` / `rounded-lg` 的非同心嵌套。

### 2.2 尺寸分层圆角 Token（【目标规范】）

废除当前 [`globals.css`](../frontend/src/app/globals.css) 中“控件统一 `rounded-2xl`”的基准，按元素高度与容器角色建立 7 级比例阶梯：

| 语义 Token | 目标像素 | 适用控件与容器高度 | 典型组件 |
| --- | --- | --- | --- |
| `--radius-control-xs` | `4px` | `<= 18px` 微型元素 | `Checkbox`（`16px`）、内联 `<code>` 片段、树形微缩标识 |
| `--radius-control-sm` | `6px` | `20px–24px` 紧凑控件 | `Button`（`xs` / `icon-xs`）、小型态标签 `Badge`、表格行内小操作 |
| `--radius-control-md` | `8px` | `28px–36px` 标准表单与操作 | `Button`（`sm` / `default` / `lg`）、单行 `Input`、`SelectTrigger`、`TabsTrigger`、下拉菜单项 |
| `--radius-surface-sm` | `12px` | 中等内嵌区与轻量浮层 | 多行 `Textarea`、`Alert` 提示条、`DropdownMenuContent` / `SelectContent`、`TooltipContent`、媒体缩略图 |
| `--radius-surface-md` | `16px` | 业务卡片与数据面板 | `Card`、分镜镜头卡、任务卡片、数据表格外框、代码块容器 |
| `--radius-surface-lg` | `20px` | 大型模态与主浮层 | `Dialog`、`AlertDialog`、独立悬浮面板、`SidebarInset`（`inset` 变体） |
| `--radius-pill` | `9999px` | 明确需要胶囊语义的独立控件 | 悬浮胶囊主输入栏（`ChatPane` / 新建会话）、发送/附件圆形按钮、头像、进度条轨道 |

> **克制规则**：在桌面端高密度表单与表格场景下，常规操作按钮与输入框统一使用 `8px` 柔和方圆角（macOS 控件风格），不再全部搓成胶囊；仅主对话输入栏与图标圆钮保留胶囊形态以建立清晰焦点。

### 2.3 同心圆角数学约束（Concentric Radii Rule）

任何存在明确内边距（`padding`）的嵌套容器，其内部紧贴边角的子元素圆角必须严格满足同心公式：

$$R_{\text{inner}} = \max(R_{\text{outer}} - P, R_{\min})$$

其中 $P$ 为内外边界之间的间距（`padding`），$R_{\min} = 4\text{px}$。

- **CSS 变量继承约定**：容器通过 `--surface-radius` 与 `--surface-pad` 声明自身几何参数，子元素通过 `rounded-[max(calc(var(--surface-radius)-var(--surface-pad)),4px)]` 自动派生圆角：
  - 示例 1（`Tabs` 分段控件）：`TabsList` 设 `--surface-radius: 10px`、`--surface-pad: 3px`，则 `TabsTrigger` 与滑块指示器自动得 `7px`，严禁内外同值；
  - 示例 2（`DropdownMenu` / `SelectContent`）：弹出菜单容器 `12px` + `p-1`（`4px`），菜单项 `Item` 固定得 `8px`；
  - 示例 3（`Card` / `Dialog` 内部区块）：`Card` 外层 `16px` + `p-4`（`16px`）或 `Dialog` 外层 `20px` + `p-5`（`20px`）时，内部控件或嵌套容器取与自身尺寸匹配的 `8px`–`12px`，确保内外角圆心重合或保持均匀留白。

### 2.4 连续曲率平滑（Squircle）渐进增强

标准 CSS 圆角在直线与圆弧相切处存在曲率阶跃。针对 `>= 12px` 的容器（`Card`、`Dialog`、`AlertDialog`、悬浮胶囊、媒体缩略图、空状态框），在支持原生连续曲率的现代浏览器内核上统一渐进增强：

```css
@supports (corner-shape: squircle) {
  [data-squircle="true"] {
    corner-shape: squircle;
  }
}
```

不支持该特性的浏览器安全回退为按比例缩放后的标准 `border-radius`，严禁引入 `clip-path` SVG 切边或破坏子元素溢出的伪元素 Hack。

---

## 三、色彩、层级与排版秩序

### 3.1 中性克制 OKLCH 色彩纪律

- 沿用 [`globals.css`](../frontend/src/app/globals.css) 的中性灰阶 OKLCH 令牌，**禁止**引入大面积营销型彩色渐变、霓虹边框或高饱和度装饰底色。
- 主强调色仅允许用于**首要行动按钮、当前选中焦点、进行中脉冲与语义状态指示**（成功/警告/错误必须同时配合图标或文字语义，不得仅靠颜色传达状态）。

### 3.2 扁平分层与阴影节制

- 界面层次优先通过**中性底色阶梯**（`--background` / `--sidebar` / `--card` / `bg-muted/30`）与**`1px` 低对比描边**（`border-border` / `ring-1 ring-foreground/6`）建立。
- **【禁止】多层卡片嵌套**：同一视觉层级内只允许存在一层卡片/面板容器。禁止在 `Card`、分组面板或表格外框中再叠加第二层同时具备「背景 + 圆角 + 描边（或阴影）」的容器——**无论是否带阴影**。
  - 判断标准：内层容器若为了“把一组内容框起来”而重新声明 `rounded-*` 与 `border` / `bg-card` / `shadow-*` 的组合，即视为嵌套卡片。
  - 内部分层必须改用分组标题、间距、`Separator` / `divide-y` 与背景明度阶梯（`bg-muted/20`、`bg-muted/30`）表达，不得再画一张卡片。
  - 唯一例外：`Card` 内表示不同语义的**功能性内容容器**（代码块、表格外框、媒体预览）可使用 `--radius-surface-sm` 级别的浅底与描边，但不得再叠阴影或再套一层卡片；`Dialog` / `DropdownMenu` / `Sheet` 等脱离文档流的浮层本身不计入卡片层级，但其内容区同样不得用分组卡片分区。
  - 存量待整改位置：`(studio)/account/page.tsx` 额度卡片内的统计块、兑换码面板与流水容器；`components/admin/model-form-sheet.tsx` 的「主对话模型上下文与推理策略」「能力开关组」分组卡片及其内层「支持的推理档位」网格。
- 禁止在普通列表卡片上叠加重阴影；`shadow-lg` / `shadow-xl` 仅限脱离文档流的浮层（`Dialog`、`DropdownMenu`、悬浮胶囊输入框）。

### 3.3 排版密度与等宽字体边界

- 全站使用统一无衬线字体（`Inter` + 系统中文无衬线回退），标题阶梯紧凑（`14px`–`24px`），页面顶部栏 `PageHeader` 固定为 `14px`（`text-sm font-medium`），不再使用夸张字号挤压工作区。
- **数字等宽对齐（【强制】）**：所有计时、额度、任务数、分镜时长、分页页码、进度百分比、时间戳必须添加 `tabular-nums`，避免流式更新或轮询刷新时界面抖动。
- **等宽字体 `font-mono` 收敛（【强制】）**：`font-mono` 仅限展示代码块、兑换码（CDK）、上游模型标识（后台模型配置）与审计详情展开区的链路追踪 ID；禁止对镜头名称、用户名称、文件标题等业务文本使用等宽字体。

---

## 四、全局动效架构

### 4.1 四级 Motion Token 物理体系（【目标规范】）

禁止在业务组件中散写 `duration: 0.14 / 0.16` 等孤立数值，所有 `motion` 与 CSS 过渡必须消费统一定义在 `frontend/src/lib/motion.ts` 的 4 级规范：

| 层级 | 适用场景 | 物理参数 / 时长与曲线 | 退场原则 |
| --- | --- | --- | --- |
| **1. `MICRO`（微反馈）** | 按钮按压、开关拨动、勾选、折叠箭头旋转、Hover 高亮 | `120–150ms` 或紧致弹簧（无过冲 `bounce: 0`）；按压仅做极轻缩效 | 与入场对称快速复位 |
| **2. `CONTROL`（控件浮层）** | `Select`、`DropdownMenu`、`Tooltip`、悬浮回到底部按钮 | 入场 `180ms` `easeOutExpo` `[0.16, 1, 0.3, 1]`（位移 `<= 6px`） | 退场 `120ms` `[0.4, 0, 1, 1]`（快于入场） |
| **3. `SURFACE`（模态与抽屉）** | `Dialog`、`AlertDialog`、`Sheet` 及遮罩层 | 2D `opacity` + 微缩放（`0.97 -> 1`）或侧滑；入场 `220–260ms` `[0.16, 1, 0.3, 1]` | 退场 `160–180ms`；**静止态必须归零为 `transform: none`** |
| **4. `SPATIAL`（空间连续性）** | 工作台分栏展开/收起、画布全屏切换、分镜镜头重排、Tab 指示器滑移 | 阻尼弹簧（`stiffness: 380, damping: 36, mass: 1`），无回弹抖动 | 禁止 `display: none` 瞬切，需保持布局过渡连贯 |

### 4.2 Animate UI 集成修正与禁用清单

1. **【强制】规避 Animate UI 非侧边弹窗模糊缺陷**：`@animate-ui/react` 默认非侧边弹窗会在静止态保留 `perspective(500px) rotateX(0deg)` 与 `filter: blur(0px)` 内联属性，在部分 GPU 合成场景下触发文本重采样发虚（参见 [`modal-surface.ts`](../frontend/src/components/ui/modal-surface.ts) 修复）。`Dialog` 与 `AlertDialog` 保持 Flex 视口居中 + 纯 2D `opacity/scale` 动画，由统一 `SURFACE` Token 接管并同步遮罩淡入淡出。
2. **【强制】消除组件库内部割裂**：
   - 清理 [`globals.css`](../frontend/src/app/globals.css#L2) 中零使用的 `@import "tw-animate-css";`；
   - 移除 [`select.tsx`](../frontend/src/components/ui/select.tsx#L85) 上的 `data-[align-trigger=true]:animate-none`，使默认下拉弹出具备柔和入退场；
   - 统一 [`tabs.tsx`](../frontend/src/components/ui/tabs.tsx) 中 `default` 胶囊指示器与 `line` 下划线指示器的物理速率，并在切换内容区配合轻量交叉淡入。
3. **【禁止】`transition-all` 与长阻塞入场**：禁止使用 `transition-all`；禁止为常规页面/表单添加 `> 300ms` 的首屏开场动画，禁止对 Markdown 流式正文增加逐字跳动特效。

### 4.3 必须补齐的连续性动效场景（【目标规范】）

| 场景 | 现状问题 | 目标连续性方案 |
| --- | --- | --- |
| **工作台右侧画布开合 / 全屏** | `StudioWorkspace` 直接切换 `hidden`，画布瞬移闪烁 | 使用受控宽度/弹性过渡或平滑交叉显隐，保证聊天栏与画布比例切换顺滑可追踪 |
| **分镜镜头卡片增删与上下调序** | 列表瞬间替换，无法分辨哪张卡片上移/下移 | 为镜头列表项接入 `motion.div` `layout="position"`（搭配 `SPATIAL` 弹簧）与 `AnimatePresence` 轻量进退场 |
| **媒体骨架屏 -> 成图 / 视频加载** | `Skeleton` 突然替换为 `<img>`/`<video>` | 包裹轻量淡入，避免加载瞬闪 |
| **图片缩略图 -> 灯箱预览** | 点开预览直接弹窗突兀覆盖 | 配合 `SURFACE` 缩放曲线与柔和背景虚化遮罩入场 |
| **AI 思考折叠与工具状态跃迁** | 工具芯片仅写死 `0.14s` 出现，完成后状态瞬变 | 由统一 `MICRO` / `CONTROL` Token 驱动状态徽标与高度展开 |

### 4.4 减弱动效无障碍降级（`prefers-reduced-motion`）

- 根部 [`AppProviders`](../frontend/src/components/providers/app-providers.tsx) 持续保留 `<MotionConfig reducedMotion="user">` 与 `<AnimateUIProvider reducedMotion="user">`。
- 降级模式下**禁止移除状态反馈**：取消所有位移（`x/y`）、缩放与弹簧摆动，但保留柔和的透明度与色彩切换（`<= 150ms`），确保用户仍能明确感知加载完成、选中与弹窗开关；所有自定义 CSS 动画旁必须附带 `motion-reduce:animate-none` 或 `motion-reduce:transition-none`。

---

## 五、去开发者化 UI 与界面文案规范

### 5.1 核心铁律：用户界面禁止“念代码”

创作者关心的是**故事、镜头、画面、成片与可用次数**；运营管理员关心的是**用户账号、模型服务、财务与风控**。任何后端持久化细节不得泄漏到默认 UI。

### 5.2 前端禁用暴露黑名单（【强制】）

| 禁止类别 | 严禁出现在面向用户的 UI 文案/标签/组件中 | 合规替代表达 |
| --- | --- | --- |
| **内部版本与 HTTP 状态码** | `v12`、`v1`、`baseVersion`、`409`、`(409)`、`schemaVersion`、`5xx` | 工具栏仅示「已保存 / 未保存更改 / 已有新版本」；409 冲突称「内容已被他人或助手更新」；版本号仅收纳于二级「历史版本」时间线面板 |
| **内部实体编号与 UUID** | `shot_01`、`shot-1`、`scene-1`、`assetId`、`fileId`、`taskId`、`modelDefinitionId`、`operatorId`、`userId` 作为可见标题或手动输入框 | 分镜镜头以可读序号呈现「第 1 镜 / 镜头 1」（如无自定义名称）；输入资产列表展示**可读素材名称与缩略图**，严禁渲染 36 位 UUID；去掉所有手输 ID 的调试输入框（如 `或指定模型编号`、`输入素材编号导入`、`输入任务编号补充追踪`） |
| **Schema / DTO 字段括号** | `(firstFrame)`、`(selectedVideo)`、`(usedRange)`、`(prompt)`、`(generateAudio)`、`(watermark)`、`CompositionSpec`、`priceUnits`、`extra`、`bizNo` | 直接使用自然中文标签：首帧画面、已选片段、截取时段、画面描述、同步生成音效、添加平台水印、成片规格、消耗额度 |
| **底层引擎与调度术语** | `FFmpeg`、`h264`、`Preset: veryfast`、`CRF: 23`、`Temporal Workflow`、`Agent Run` | 改称「导出成片 / 视频合成」；导出规格收口为「分辨率（720p / 1080p）」与「画质档位（标准 / 高清）」预设，底层编码参数内置托管 |
| **未翻译后端枚举** | `ADJUST`、`ARCHIVE_FAILED`、`PENDING_RECONCILIATION`、`UNKNOWN`、`USER_UPLOAD`、`AGENT`、`TOOL`、`GENERATED`、`FIRST_FRAME`、`REFERENCE`、`WARNING`/`CRITICAL`（未映射直出）、`WORKFLOW_START_EXHAUSTED` | 100% 映射为中文业务状态（见 5.3 对照表）；后台枚举仅允许作为内部筛选值，不得直接渲染英文常量给用户 |
| **源码视图默认外露** | 分镜工具栏显眼的 `JSON` 切换按钮、`编辑原始 JSON`、新建分镜描述写 `STORYBOARD JSON 文件` | 常规创作界面不提供 JSON 源码编辑器；极少数开发者调试诉求若需保留，须折叠进次级「高级调试」菜单或仅限开发者模式开启 |
| **后端解释型啰嗦文案** | `"向「Administrator」（admin@example.com）追加或扣减可用额度，流水类型记为 ADJUST。"`、`"为确保账号间数据隔离，系统对不可见资源统一返回 404。"`、逐行重复的 `CREDIT_ENTRY_HINT` | 说人话、只说一次（见 5.4 文案重构模板） |

### 5.3 前后端协同术语清洗约束

- 后端 [`ToolDisplayFormatter.java`](../agent/src/main/java/app/pagefault/visionforge/agent/runtime/ToolDisplayFormatter.java) 生成的对话工具芯片文案同样受本规范约束：禁止向前端推送 `镜头 shot_01` 或 `分镜 v12`（应使用镜头序数或分镜标题）。
- 全局媒体生成/合成任务状态统一采用创作者可理解的分层命名：
  - `ARCHIVE_FAILED` -> 「保存异常（可重试保存）」而非「待归档修复」；
  - `UNKNOWN` -> 「状态确认中」而非「待核账」；
  - `重试归档 (不重扣费)` -> 「重新保存结果」（仅在说明中简短提示“不重复消耗额度”）。

### 5.4 典型页面文案重写示例

| 页面 / 弹窗 | 重塑前（禁止） | 重塑后（标准） |
| --- | --- | --- |
| **管理员用户调额** | 标题：`人工调整用户额度`<br>描述：`向「Administrator」（admin@example.com）追加或扣减可用额度，流水类型记为 ADJUST。` | 标题：`调整额度 · Administrator`<br>描述：`正数增加可用额度，负数扣减；变更将立即生效并记入账单流水。` |
| **个人账户汇总** | `最近任务 priceUnits 汇总`；流水每行附带 `管理员人工调账记录` / `冻结，不影响总余额` | `近期任务额度消耗`；流水行仅保留类型标签 + 变动额度 + 关联业务/备注 + 时间 |
| **分镜新建弹窗** | `创建新的 STORYBOARD JSON 文件（可与历史版本并存比较）。` | `新建一份分镜草案，可在画布中编辑镜头并生成画面。` |
| **404 异常页** | `为确保账号间数据隔离，系统对不可见资源统一返回 404。请确认链接是否正确或切换回你的会话列表。` | `该页面或项目可能已被删除，或当前账号无权查看。` |
| **图片灯箱底部** | `资产 ID：019a...` | 展示素材标题 / 分辨率与格式（若已知）及「复制链接」「下载原图」 |
| **后台审计/告警详情** | 主列表直出 `bizNo` / `TraceId` 列与全英文 `operatorId` | 列表优先展示**时间、操作人、操作摘要、执行结果**；`TraceId` / `bizNo` / `extra` 统一收入详情弹窗底部折叠的「技术诊断信息」中 |

---

## 六、管理后台「系统参数配置中心」重塑规范

### 6.1 废除 Nacos 控制台套壳（【强制】）

- **【禁止】** 在管理后台侧栏（[`admin-shell.tsx`](../frontend/src/components/admin/admin-shell.tsx)）、总览卡片（[`admin/page.tsx`](../frontend/src/app/(admin)/admin/page.tsx)）及配置页面（[`admin/configs/page.tsx`](../frontend/src/app/(admin)/admin/configs/page.tsx)）中出现「Nacos 配置中心」字样、Server Addr、Namespace、Group、MD5、配置来源或 `visionforge-*.json` 原始文本编辑器。
- Nacos 的集群与原始文件管理归属基础设施原生控制台（`:8848/nacos`）；管理后台 `/admin/configs`（推荐别名或迁移路径 `/admin/settings`）必须重塑为 **「系统参数配置」**。

### 6.2 结构化业务参数映射规范（【目标规范】）

后端 [`DynamicRuntimeConfig`](../common/src/main/java/app/pagefault/visionforge/common/config/DynamicRuntimeConfig.java) 当前托管的 18 项运行期参数，前端必须按业务域重组为结构化表单控件，并完成单位人本化换算（禁止让管理员心算 `0.8` 比例或 `300000` 毫秒）：

#### 分组 1：注册与新用户配额
| 参数原键 | 前端标签 | 控件类型 | 显示单位 / 格式与校验 |
| --- | --- | --- | --- |
| `registrationEnabled` | 开放新用户自助注册 | `Switch` 开关 | 布尔开关；关闭时弹出影响提示（不影响后台新建用户） |
| `initialCreditUnits` | 新用户初始赠送额度 | 数字 `Input` | 非负整数，单位 `额度` |

#### 分组 2：安全防护与接口限流
| 参数原键 | 前端标签 | 控件类型 | 显示单位 / 格式与校验 |
| --- | --- | --- | --- |
| `rateLimitEnabled` | 启用全站接口限流 | `Switch` 开关 | 布尔开关；关闭后下方 4 项频率输入置灰禁用 |
| `rateLimitAuthPerMinute` | 登录与注册频率上限 | 数字 `Input` | 正整数，后缀 `次 / 分钟` |
| `rateLimitRedeemPerMinute` | 兑换码核销频率上限 | 数字 `Input` | 正整数，后缀 `次 / 分钟` |
| `rateLimitGenerationPerMinute` | 媒体生成提交频率上限 | 数字 `Input` | 正整数，后缀 `次 / 分钟` |
| `rateLimitUploadPerMinute` | 素材上传频率上限 | 数字 `Input` | 正整数，后缀 `次 / 分钟` |

#### 分组 3：AI 对话与上下文策略
| 参数原键 | 前端标签 | 控件类型 | 显示单位 / 格式与校验 |
| --- | --- | --- | --- |
| `contextCompactThreshold` | 上下文自动压缩触发阈值 | 滑块 / 百分比数字框 | UI 显示 `10%–95%`（如默认 `80%`），保存时自动换算为 `0.10–0.95` 小数 |
| `contextCompactKeepRatio` | 压缩后保留近期对话比例 | 滑块 / 百分比数字框 | UI 显示 `5%–60%`（如默认 `25%`），保存时自动换算为小数 |
| `contextSummaryMaxOutputTokens` | 历史摘要最大输出长度 | 数字 `Input` | 正整数，后缀 `Tokens` |
| `streamTimeoutMs` | 单轮对话流式响应超时 | 数字 `Input` | UI 显示单位 **`秒`**（如默认 `300 秒`），提交时自动换算为毫秒 `ms` |

#### 分组 4：全局告警与邮件服务器（建议并入「告警中心 · 全局设置」或保留独立参数卡）
| 参数原键 | 前端标签 | 控件类型 | 显示单位 / 格式与校验 |
| --- | --- | --- | --- |
| `alertsEnabled` | 启用全站异常告警投递 | `Switch` 开关 | 布尔开关 |
| `smtpHost` | SMTP 服务器地址 | 文本 `Input` | 域名或主机名（如 `smtp.example.com`） |
| `smtpPort` | SMTP 端口 | 数字 `Input` | 整数 `1–65535`（默认 `587`） |
| `smtpUsername` | 发信认证账号 | 文本 `Input` | 邮箱或用户名 |
| `smtpFrom` | 发件人展示地址 | 邮箱 `Input` | 校验邮箱格式 |
| `smtpSslEnabled` | 启用 SSL/TLS 加密连接 | `Switch` 开关 | 布尔开关 |
| `webhookAllowPrivateNetwork` | 允许 Webhook 投递至内网地址 | 高危 `Switch` 开关 | 显式安全警示标注；开启时须二次确认防 SSRF 风险 |

### 6.3 交互与容错要求

- **按组独立保存与恢复默认**：每个配置分组提供「保存设置」与「恢复推荐默认值」操作；存在未保存更改时给出明确状态标识，禁止展示原始 JSON 差异框或 MD5 值。
- **同步异常提示降噪**：若底层配置同步服务不可达，仅在页面顶部以温和提示告知「当前处于本地运行配置模式，修改将在服务恢复同步后由后端接管」，不展示连接串或集群坐标。

---

## 七、后续改造分批路线与验收清单

后续实际改造代码时，建议按以下四个原子阶段推进，每阶段提交前须完成对应自检。截至 2026-11-20，阶段 1、阶段 2 与阶段 4 已主要落地，阶段 3 已覆盖画布开合、镜头重排与媒体淡入：

1. **阶段 1（设计底座重塑）**：重写 [`globals.css`](../frontend/src/app/globals.css) 圆角阶梯与 Squircle 支持，修正 [`components/ui/*`](../frontend/src/components/ui) 固定 `rounded-2xl` 与同心半径，建立 `src/lib/motion.ts` 4 级动效体系并修复 `Dialog` / `Select` / `Tabs` 冲突，清理 `tw-animate-css`，并拆除存量多层卡片嵌套（[`(studio)/account/page.tsx`](../frontend/src/app/(studio)/account/page.tsx) 额度面板、[`components/admin/model-form-sheet.tsx`](../frontend/src/components/admin/model-form-sheet.tsx) 编辑抽屉）。
2. **阶段 2（工作台与后端表述去开发化）**：改造分镜、素材、任务、文件面板、灯箱及 [`ToolDisplayFormatter.java`](../agent/src/main/java/app/pagefault/visionforge/agent/runtime/ToolDisplayFormatter.java)，消灭 `vN`、`shot_01`、UUID、FFmpeg 与 JSON 源码直出，统一精简个人中心与后台文案。
3. **阶段 3（空间连续性动效落地）**：打通工作台分栏开合/全屏过渡、分镜卡片重排 `layout` 动画、骨架屏交叉淡入与灯箱预览流畅转场。
4. **阶段 4（系统参数配置中心重塑）**：废除后台「Nacos 配置中心」，交付结构化「系统参数配置」页面及单位自动换算/高危开关确认（并同步更新 [`DESIGN_DETAILS.md`](DESIGN_DETAILS.md) 与 [`API_REFERENCE.md`](API_REFERENCE.md)）。

每阶段提交前必须通过门禁：
- `pnpm --dir frontend lint` 零错误；
- `pnpm --dir frontend exec tsc --noEmit` 零报错；
- `pnpm --dir frontend build` 全路由通过；
- `pnpm --dir frontend dlx @animate-ui/cli@latest audit` 无高成本属性或遗漏 `reduced-motion`。
