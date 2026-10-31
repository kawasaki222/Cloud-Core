# 待办 / 交接

最后更新：2026-10-31。

## 当前状态

**M1 协议核心、M2 面板链路、M3 页面加载与动作引擎子集均已完成真机联调；
附属插件 API、键盘导航、状态持久化已落地。M4 页面组合、M6 Tooltip/Boss、M7 元素与字体、
M8 占位符刷新的大部分也已落地。**

- 协议：信封/DEFLATE/分片重组/握手/PING-PONG/UI_OPEN-READY-CLOSE-CLOSED/EVENT-EVENT_RESULT/
  ELEMENT_UPDATE/TEXTURE_REF/TEXTURE_REQUEST/TEXTURE_PUT/DIAGNOSTIC 全部实现并有测试；
  未知 surface 与 bind/render 模式前向兼容。
- 服务端：Paper 插件自包含；容器三种点击策略 + `allow_player_inventory` 与拖拽判定、
  `inventory_grid` 槽位重排、事件限流（20/s 突发 40）、
  `command` 动作闸门、`open_page`/`sound`/`refresh` 动作、PlaceholderAPI 展开（软依赖）、
  页面/元素级周期刷新（`interval`/`tick`/`event`/`manual`，同步或异步）、
  会话状态持久化（`file`/`sqlite`/`mysql` 三个后端 + 跨后端导出/导入，异步写）、
  代理贴图（`client`/`proxy`/`auto` + allowlist + 磁盘缓存淘汰）、
  数据驱动饰品槽位（`slots.yml`）、装备资产绑定、
  `/cloudcore validate|reload|page|state-export|state-import|equipment|accessories|demo-*`
  并带 Tab 补全。
- 附属 API：`CloudCoreApi` 的 `open`（YAML 或代码页）、`registerActionHandler`、
  `registerPlaceholderResolver`、`registerPageProvider`、`isModded`；`PageBuilder` 在 `cloudcore-api`。
- 页面：YAML 单文件/`includes`/`repeat`（含嵌套）/模板；动作编译成 actionId 并只留在服务端；
  `set_state` 重编译后推送更高 revision（等值写入不推送）。
- 客户端：六类面板 + RenderList（文字/富文本/图片/图层叠加/九宫格/背景贴图/裁剪/滚动/悬停/物品图标/
  GIF/URL 贴图/滑块含拖动与 min-max-step-live/复选框/输入框状态含光标色、选区色、清除按钮、padding/
  进度条四方向含圆角分段/图标按钮/键盘焦点/pressed 态/disabled 态）；
  NanoVG 后端与 FBO 合成（opt-in）；多层 Boss 血条与堆叠间距；客户端本地面板（`match` 接管容器/血条）；
  Tooltip 附属栏（`attachments`）、物品图标、`name`/`lore` 独立字体；
  玩家背包的实时玩家模型、状态效果、配方书 `hide`/`vanilla`；
  F3 调试叠加层；客户端日志与诊断码。
- 协议：`ELEMENT_UPDATE`（元素/属性增量、revision 顺序、完整文档回退；自定义页真机验证）。
- 附属 API：`SurfaceProvider`/`registerSurfaceProvider`、四类事件监听、`setState`/`getState`、
  `cloudcore-api-testkit`。
- 构建：`./gradlew build` 通过，371 个测试 / 71 个测试类，0 失败。

## 已有内容

```
README.md
docs/
  00-project-charter.md
  01-architecture.md
  02-protocol.md
  03-ui-dsl.md
  04-server.md
  05-client.md
  06-resources-and-rendering.md
  07-security.md
  08-roadmap.md
  09-testing.md
  10-risks-and-verification.md
  11-surfaces.md
  12-page-composition.md
  13-action-engine.md
  14-render-backends.md
  15-engineering-guide.md
  16-ui-document-schema.md
  17-operations.md
  18-diagnostics.md
  19-addon-api.md
  TODO_HANDOFF.md
```

## 文档完整性检查

- [x] 章程、目标、非目标、术语（`00`）
- [x] 模块边界、面板抽象、渲染架构、线程（`01`）
- [x] 协议信封、操作码、面板类型、状态码、限额（`02`）
- [x] YAML 语法、元素、样式、字体、遍历、动作（`03`）
- [x] 服务端启动、配置、页面组合、会话、面板策略（`04`）
- [x] 客户端初始化、网络、混入、后端、输入（`05`）
- [x] NanoVG/FBO/字体/Tooltip/Boss/性能（`06`、`14`）
- [x] 威胁模型、校验、限流、敏感输入（`07`）
- [x] 里程碑、依赖、估算、验收（`08`）
- [x] 测试金字塔、模糊、互操作、浸泡（`09`）
- [x] 风险、未验证项、ADR、迁移（`10`）
- [x] 六类面板生命周期与扩展（`11`）
- [x] 多 YAML 组合、重载、遍历展开（`12`）
- [x] 动作引擎 v1/v2、作用域、等待、预算（`13`）
- [x] 工程结构、编码规范、常见改动流程（`15`）
- [x] 下发文档字段、类型、限额、示例（`16`）
- [x] 部署、本地资源、原生库、升级、故障手册（`17`）
- [x] 客户端日志、错误码、服务端诊断、诊断包（`18`）
- [x] 附属插件 API、代码构造页面、兼容策略（`19`）
- [x] M0：Gradle 多模块/协议核心/Paper 通道/Fabric payload 骨架
- [x] M1：分片与重组、握手、PING/PONG、UI_OPEN/UI_READY 编解码、黄金向量、随机帧测试
- [x] M1 接线：Paper 与 Fabric 的 HELLO/PING/PONG/分片/UI_OPEN 处理
- [x] M1 真实联调：Paper + Fabric 握手、`/cloudcore ping`（RTT 9ms）、UI 打开/关闭
- [x] 服务端主动 `UI_CLOSE`：客户端关闭 CloudScreen 并写日志
- [x] `DIAGNOSTIC` 下发链路：服务端测试码进入客户端文件日志
- [x] U2：容器打开事件中的 PING 先于 `ContainerScreen#setScreen`（真实客户端验证）
- [x] U12：`MouseHandler#onScroll` 在 ContainerScreen 上收到滚轮（真实客户端验证）
- [x] U3：`GuiGraphics#renderTooltip` 混入在悬停石头时实际命中（lines=1）
- [x] U19：Paper BossBar 哨兵标题在客户端可见
- [x] U13：`@WrapOperation` 命中 `BossHealthOverlay#render` 内的私有 `drawBar` 调用
- [x] M1 真机大报文：64,776 字节文档拆成 3 片；830 KB 文档拆成 27 片并重组成功
- [x] M1 压力：100 万随机帧解码（仅 ProtocolException）
- [x] M1 断线清理：重连后分片组、pending 会话与面板文档全部清空（日志 + 单元测试）
- [x] M2 骨架：SurfaceRegistry/SurfaceAdapter/SurfaceProvider、UiDocument、RenderBackend、CloudScreen
- [x] M2 文档链路：UI_DEFINITION 解码、校验、`CloudScreen` 打开
- [x] M3 核心：YAML 页面加载、`includes` 合并、`repeat` 展开、模板替换、JSON 回写（真机验证）
- [x] 事件链路：`EVENT` 编解码、点击命中、服务端动作表（`message`/`close_ui`，真机验证）
- [x] 状态更新：`set_state` → 重编译 → 更高 revision 的 `UI_DEFINITION`，客户端替换界面（真机验证）
- [x] `/cloudcore validate` 与 `/cloudcore reload`（重编译 + 推送更新，真机验证）
- [x] WatchService 热重载：文件变化 400ms 合并后自动重载并推送（真机验证）
- [x] 事件限流：每玩家令牌桶（20/s、突发 40），超限回 `RATE_LIMITED`（真机验证）
- [x] 动作引擎子集：表达式求值、`if`/`then`/`else`、`delay`（真机验证 if/表达式）
- [x] 模板表达式：属性/标签编译期求值、动作参数运行期求值、嵌套模板由内向外（单测 + 商店页真机验证）
- [x] 行/列/网格命中：容器布局同时用于渲染、命中与焦点（修复行内按钮点不到，单测覆盖）
- [x] 动作 `open_page`（关闭当前页并打开目标页）与 `sound`（音效/音量/音高）（真机验证）
- [x] 键盘导航：`Tab`/方向键循环焦点、`Enter`/`Space` 触发 `click`、两色焦点框（真机验证）
- [x] 会话状态持久化：`PlayerStateStore` 接口 + `state/<uuid>.json`、异步 flush、加入时加载（真机验证）
- [x] 持久化后端：SQLite（驱动随插件打包）与 MySQL（服务端提供驱动）；JSON 文件仍为默认
- [x] 持久化：schema 版本表（`cloudcore_schema`，`SCHEMA_VERSION = 1`，拒绝更新版本）与
      跨后端导出/导入（`/cloudcore state-export|state-import`，`{version,players}` JSON）
- [ ] 持久化剩余：连接池（当前每次操作开一条连接，单写线程串行）
- [x] `EVENT_RESULT` 下发（OK/UNKNOWN_ELEMENT/REJECTED，固定枚举名）
- [x] `command` 动作 + 配置闸门（默认拒绝，按首个词 allowlist；单测）
- [ ] M3 剩余：依赖图增量编译/内容哈希去重（WatchService 已有 400ms 合并，但未按依赖只重编译）
- [x] M2 最小原生渲染：panel/label/button/text_field/checkbox/progress 的 RenderList（真机截图验证）
- [x] `image` 的 `local:`/`pack:`/`builtin:` 贴图，以及 `item_icon`（原生物品、数量、缩放）（真机截图验证）
- [x] HUD 面板：Fabric `HudElementRegistry` + 持久文档 + UI_CLOSE 清除（真机截图验证）
- [x] HUD 回归测试：`HudLayerCache` 锁住“数值不变不重建”、视口变化重建、上一帧 live 文档作基线
      （`HudLayerCacheTest` + `HudValuesTest`）
- [x] Tooltip 面板：自定义背景 + 原生文本/图片（真机截图验证）
- [x] Tooltip 滚动：`max_height` 裁剪 + 滚轮偏移 + 滚动条（真机验证）
- [x] Tooltip 背景贴图/九宫格：`style.background` + `style.nine_slice`（真机验证，与渲染列表共用 blit）
- [x] BossBar 面板：自定义条 + 进度/颜色 + 哨兵标题剥离（真机截图验证）
- [x] Boss 血条样式：`boss_bar.bar` 的宽高/边框/背景（贴图+九宫格）/填充（贴图裁剪或颜色）/
      `tint_from_event`（真机验证）
- [x] Boss 标题字体/阴影/对齐与层数计数：`bar.text` 的 `font`/`shadow`/`align`/
      `show_layer_count`（`BossBarSurfaceAdapter`）；`font` 与 `title_renderer` 目前只接受原版字体
- [x] 多层血条：同一根条逐层绘制，哨兵携带每层剩余强度
      `\0CCB:<token>:<p1>,<p2>,...\0<名称>`；`bar.layers` 逐层样式，外层打空露出内层；
      `demo_layers` 用于 `/cloudcore page` 预览（真机截图验证）
- [x] 客户端本地面板：`LocalPageRegistry` 按 `match` 正则接管容器/Boss 血条，
      服务端不下发也能用，`F9` 重载；服务端下发优先（`docs/05-client.md` 第 19 节）
- [x] Boss 多条血条堆叠与间距：`spacing`/`max_bars`，只作用于哨兵血条（真机截图验证 3 条 → 2 条）
- [x] Container 面板：NEXT_CONTAINER 绑定的箱子换背景，原版槽位保留（真机截图验证）
- [x] 客户端日志文件：`logs/cloudcore-client.log` + 轮转（真机验证）
- [x] 客户端快捷键：`F9` 重载本地资源、`F10` 诊断包（真机验证：贴图热替换、zip 内容、聊天提示）
- [x] F3 调试叠加层：右侧中部显示活动文档的 uiId/surface/revision 与文档数（真机截图验证）
- [x] `/cloudcore stats` 与 `/cloudcore surfaces <player>`（`ServerMetrics` + 单测）
- [ ] 客户端命令：`/cloudcore-client reloadassets|dump` 已注册但未在聊天执行验证；
      `log <level>` 已实现（运行时改 `cloudcore` logger 级别，不动 root）
- [x] M2 渲染：NanoVG 后端、裁剪/滚动偏移（原生 + NanoVG 真机验证）
- [x] 滚轮滚动：光标下 `scroll` 元素的本地偏移与钳制（真机验证 + 单元测试）
- [x] `panel`/`button` 的背景贴图与九宫格；标签富文本按段着色
- [x] 按钮 `style.hover` 悬停样式（真机验证）
- [x] `text_field`：原版 EditBox 控件 + `change`/`submit` 事件与 `${value}` 替换（真机验证）
- [x] `text_field` 外观状态：背景贴图/九宫格、normal/invalid/focused 边框、文字颜色、
      `filter`（含 `regex:`）、`sensitive` 掩码（真机验证 + 单测）
- [x] `text_field` 的 `padding` 补偿：`[左,上,右,下]` 或单值，按内边距缩小 `EditBox`，
      所以文字不再贴左上角；焦点框按控件身份匹配，内缩后仍对齐元素框（单测 `InsetsTest`）
- [x] `text_field` 的 `cursor_color` / `selection_color` 与清除按钮：`CloudEditBox` 重写
      `renderWidget`，不新增混入；清除按钮占右侧、控件同步收窄，点击触发 `change`（单测 + 真机验证）
- [x] `checkbox`：`checked` 绘制 + `change{checked}` → `set_state` 往返（真机验证）
- [x] `icon_button` 与 `button.icon`：贴图引用或 `{item, count}`，物品图标按按钮缩放（真机验证 + 单测）
- [x] `progress`：四种方向、贴图背景/填充按进度裁剪、状态驱动（真机验证 + 单测）
- [x] `progress` 九宫格背景、`radius` 圆角、`segments`/`segment_gap` 分段（单测覆盖）
- [x] `disabled` 态：变暗、禁用悬停样式、不触发事件、跳过键盘焦点、输入框不可编辑（真机验证 + 单测）
- [x] `right_click`：优先 `right_click`，无则回退 `click`（真机验证）
- [x] `button`/`icon_button` 的 `pressed` 态：`style.pressed` 优先于 `style.hover`，
      按下时文字/图标下移 1px，松开或拖离即恢复（单测覆盖）
- [x] PlaceholderAPI（软依赖）：发送/刷新时展开 `text`/`placeholder`/富文本/标题里的 `%token%`
      （真机验证 + `DocumentTextTest`），`placeholders.enabled` 开关
- [x] 占位符周期刷新：`static`/`interval`/`tick`/`event`/`manual`，页面级默认 + 元素级覆盖、
      软预算（2 ms/玩家、10 ms 全局）、`async: true` 工作线程编译 + 回主线程 diff、
      `${placeholder:...}` 显式形式（`PlaceholderExpressions`）；真机验证同步/异步两种
- [x] `placeholders.async-namespaces` 白名单：异步刷新只展开白名单命名空间，其余回主线程
      （`PlaceholderAsyncNamespaceTest`）
- [ ] 占位符剩余：预算打满后的顺延行为只有代码路径
- [x] `slider`：`value` 填充 + 点击比例 `change{amount}` → `set_state` 往返（真机验证）
- [x] `slider` 拖动、`min`/`max`/`step`、`live` 连续上报；越界值在 API 层被钳制（单测 `SlidersTest`）
- [x] 附属插件 API：`CloudCoreApi.open/registerActionHandler/unregister/isModded` 经
      `ServicesManager`（第二个插件真机验证，含 SCHEDULED / UNKNOWN_PAGE / 权限拒绝）
- [x] 动作冷却：`ActionCooldown`（按玩家/页面/键隔离）+ 动作属性 `cooldown_ticks` +
      `cooldown` 动作（`ActionCooldownTest`）
- [x] 页面级权限：页面 `permission:` 在 `/cloudcore page`、API `open`、`open_page` 动作、
      进服下发与后台刷新处强制检查（`PageLoaderTest` 覆盖解析，`CompiledPage.permission()`）
- [x] 内置动作 `permission:` 参数；缺权限拒绝整次点击并记日志
- [x] `PageBuilder` 代码构造页面 + `api.open(player, CompiledPage)`（第二个插件真机验证）
- [x] `PlaceholderResolver`：`%ns_params%` 自定义解析器（先于 PlaceholderAPI，真机验证）
- [x] `PageProvider`：`open(player, id)` 现场构建页面（真机验证）
- [x] 附属 API `setState`/`getState`（`CloudCoreApi`）
- [x] 附属 API：`registerSurfaceProvider`/`unregisterSurfaceProvider`（接口移至 `cloudcore-api`）、
      四类事件监听、`cloudcore-api-testkit`（`FakeCloudCoreApi`/`DocumentAssert`/`EventCapture`）
- [x] `scroll` 事件回传：滚轮后上报绝对偏移，`scroll_y` 由状态驱动（真机验证 + 单测 `UiElementsTest`）
- [x] `hover_enter` / `hover_leave`：跨边界各一次，仅对声明了事件的元素（真机验证）
- [x] `set_state` 等值保护：写入相同值不重编译推送（避免悬停/滚动到边界时抖动）
- [x] 修复属性模板一律字符串化：整串 `${...}` 保留解析类型（`value` 以数字下发），未解析下发空串
- [x] `row`/`column`/`grid` 自动布局（gap/columns/cell，真机验证 + 单测）
- [x] 修复 `PageLoader` 忽略调用方状态的问题（玩家状态覆盖文档默认值）
- [x] 九宫格 `nine_slice`：9 块拆分，角固定/边单向/中心双向（真机验证）
- [x] `url:` 贴图：编译成 `inline:` + `TEXTURE_REF` + 客户端直连下载（真机验证，https/大小/超时/sha256）
- [x] 代理模式：`TEXTURE_REQUEST`/`TEXTURE_PUT`（proxy/auto、allowlist、静态盘缓存、GIF 精灵表）
- [x] GIF 动画：本地与直连图片逐帧播放（3 帧测试图真机验证）
- [x] 动画选项：`animated: off` 真机像素对比；`loop`/`speed` 解析层单测
- [x] 远端 GIF 真机验证（400×400/22 帧 PNG 精灵表；超 1 MiB 自动减帧/降采样）
- [x] 代理贴图收尾：服务端内存缓存 TTL、磁盘缓存按最旧淘汰（`cache-mb`）、
      客户端 `remoteImages: false` 时改走磁盘缓存，`imageCacheMiB` 可配
- [x] Tooltip 附属栏 `attachments`：`left`/`right`/`top`/`bottom`、`match`、背景/九宫格/边框/标题/行文本
- [x] Tooltip `name`/`lore` 独立 `x`/`y`/`font`/`line_gap`，`icon` 物品图标（取自悬停槽位）
- [ ] M2 渲染剩余：文本 shaping（中文断行仍依赖原版字体度量）
- [x] Tooltip 滚轮：`scroll_step` 与 `smooth_scroll` 接线（`TooltipScrollTest`）
- [ ] Tooltip 剩余：`follow_cursor`/`sync_scroll` 未接线、滚动条贴图与 `draggable` 拖动未实现、
      `tooltip_scroll` 事件未回传服务端
- [ ] `overlay` 元素只递归子元素，`modal`/`dim` 未实现
- [x] `image.fit`：`stretch`/`contain`/`cover`（`RenderCommand.ImageProbe`，原生与 NanoVG 两个后端）
- [x] `label` 的 `wrap`/`max_lines`：贪心折行（拉丁按词、CJK 按字符），带度量注入（`TextWrapTest`）
- [x] HUD / Tooltip / BossBar / Container 面板最小闭环
- [x] HUD 三层（background/foreground/overlay）与锚点/偏移（真机验证）
- [x] PlayerInventory 面板：持久皮肤、`CloudInventoryScreen`、`UI_CLOSE` 还原原版（真机截图验证）
- [x] 容器点击策略：`managed`/`readonly` 的 `InventoryClickEvent` 取消与日志（真机验证，服务端解析文档槽位）
- [x] 容器槽位布局：`inventory_grid` 的 origin/cell/columns 重排容器槽位（真机验证，含点击）
- [x] 玩家背包槽位重排：主物品栏 9–35 与快捷栏 36–44（真机验证；护甲/合成槽保持原版）
- [x] 容器点击策略 `passthrough` 真机验证
- [x] 容器点击策略补充：`allow_player_inventory` 接入文档/策略/服务端，`InventoryDragEvent` 与
      readonly / readonly-allow 两种情况真机验证（`demo-readonly-allow` / `demo-readonly`）
- [x] 代理模式：远端 GIF 真机验证与动画盘缓存
- [x] 玩家背包实时玩家模型预览（`show_player_model` + `player_model_x/y/scale`，真机验证）
- [x] 玩家背包状态效果图标（`show_effects`，真机验证：再生效果 + 倒计时）
- [x] 玩家背包配方书 `hide` / `vanilla`（真机验证：hide 连按钮一起隐藏；vanilla 按钮展开原版配方书）
- [ ] 玩家背包剩余：配方书 `skinned` / `custom`（当前回退 `vanilla`）
- [x] 协议前向兼容：未知 `surfaceId` 保留原始值并回 `UI_READY(state=4)`
- [x] 协议前向兼容：未知 `BindMode` / `RenderMode` 解码为 `UNKNOWN` 并回 `UI_READY(state=4)`
- [x] M0 NanoVG 初始化 + FBO 绘制 + readback（Windows x64，`cloudcore.nanovg.spike`）
- [x] NanoVG 类与 Windows/Linux/macOS 原生库并入客户端 jar
- [x] M0 渲染：FBO 纹理经 `GUI_TEXTURED` blit（显式贴图尺寸）合成上屏（Windows x64，截图验证）
- [x] NanoVG 生产后端（opt-in）：填充 + `local:`/`pack:` 图片 + 原生文本/物品叠层（Windows x64 截图验证）
- [x] NanoVG 字体：`fonts:` + `source: local` 的 TTF 由 NanoVG 绘制，未声明字体者仍走原版（Windows 截图验证）
- [x] U30 预乘 alpha：合成改用 `GUI_TEXTURED_PREMULTIPLIED_ALPHA`
- [ ] NanoVG 默认化：设备分辨率 FBO（清晰文字）、U31 状态隔离、Linux/macOS 验证
- [ ] M0 渲染：预乘 alpha/Linux/macOS 运行验证
- [x] 数据驱动饰品槽位：`slots.yml` 规则（`lore-contains`/`materials`/`permission`）、
      独立 9×N 容器页、关闭时写入 `accessories/<uuid>.yml`
- [x] 装备资产：`EquipmentAssets` 把 1.21.11 数据驱动 `equipment` 资产写进物品
      `equippable.model`；`/cloudcore equipment` 与 `equipment-stand` 命令

剩余的是实现前的验证项和待决问题，不是文档缺口。

## 下一步动作（按顺序）

1. **NanoVG 默认化**：设备分辨率 FBO（清晰文字）、U31 状态隔离、失败整帧回退、Linux/macOS 验证。
2. **玩家背包 v2**：配方书 `skinned`/`custom`（当前 `hide`/`vanilla` 已实现）。
3. **渲染与交互剩余**：文本 shaping、`label.wrap`/`max_lines`、`image.fit`、`overlay` 的 `modal`/`dim`、
   滚动容器内元素的焦点/输入框定位（当前按未滚动位置计算）。
4. **Tooltip 收尾**：`scroll_step`/`smooth_scroll` 接线、滚动条贴图与拖动、`tooltip_scroll` 回传。
5. **持久化收尾**：SQL 连接池；依赖图增量编译。
6. 之后按 `08-roadmap.md` 的 M9–M10 推进（动作引擎 v2、加固与发布）。

## 环境搭建

- JDK 21。
- Gradle wrapper 在脚手架阶段固定。
- IntelliJ IDEA + Minecraft Development 插件（混入支持）。
- 本地 Paper 1.21.11 服务端目录（`run-paper`；仓库不提供 Gradle 任务）。
- `./gradlew :cloudcore-client:runClient` 启动开发客户端。
- 需要显示器；客户端集成测试可在本地 Windows 或 Xvfb + 软件 GL 上跑。

## 已知约束

- 全项目 Java 21；26.1 将要求 Java 25。
- 1.21.11 中 `ResourceLocation` 已改名为 `Identifier`；文档与代码统一用 `Identifier`。
- `cloudcore-api`、`cloudcore-protocol`、`cloudcore-server-core` 不得 import Minecraft 或 Bukkit。
- 协议服务端接收上限 32,767 字节；C2S 报文不得随文档大小增长。
- 混入共 10 个：4 个行为混入（`Minecraft#setScreen`、`GuiGraphics#renderTooltip` 与延迟绘制入口
  `method_71277`、`MouseHandler#onScroll`、`BossHealthOverlay#render`）+ `Gui#renderPlayerHealth`/
  `renderHotbarAndDecorations`（HUD `replace`）+ 5 个只读 accessor（`Minecraft`、`FontManager`、
  `AbstractContainerScreen`、`RecipeBook`、`RecipeBookComponent`）。新增行为混入需要兼容性评审。
- 遍历在服务端展开；客户端不执行模板或遍历逻辑。
- 页面组合在加载期完成；客户端只接收单一文档。
- 正式素材放客户端 `.minecraft/resourcepacks/CloudCore/`，可按 `副本/`、`签到/`、`公共/` 分类；
  模组只留最小回退。
- 字体来自本地 CloudCore 目录/资源包，不能运行时推送；NanoVG 用 TTF/OTF，原版文本用 `Font`。
- 所有错误写客户端日志，默认脱敏；`F10`/`dump` 生成诊断包。
- 当前默认渲染后端是原生 `NativeBackend`；NanoVG 后端通过 `-PcloudcoreNanoVg`（客户端运行属性）
  启用，默认化计划中。`NVGContext`、FBO、字体创建和合成只能在渲染线程。
- NanoVG 原生库随模组打包，加载失败必须回退 `NativeBackend`。
- FBO 按逻辑分辨率整数倍分配，单层显存上限 8 MiB，窗口/GUI 缩放变化才重建。
- URL 贴图只允许 https；直连模式由客户端执行私网/回环/大小/超时策略，代理模式由服务端执行允许列表。
- GIF 直连模式客户端解码，代理模式服务端解码；两侧都有帧数/像素/延迟上限。
- PlaceholderAPI 默认主线程同步解析；`async: true` 的页面要求扩展线程安全，
  `async-namespaces` 白名单尚未实现。
- Tooltip 滚动条与 Boss 样式字段都有范围校验和默认值；其中 `scroll_step`/`smooth_scroll`、
  滚动条贴图与 `draggable` 拖动尚未接线。
- 状态存储后端 `file`/`sqlite`/`mysql` 共用同一份 JSON 文档，可互相导出导入；
  SQL 后端每次操作开一条连接，尚未引入连接池。

## 待决策事项

见 `10-risks-and-verification.md` 第 6 节。仍待定的是：

- 内置字体的字形覆盖范围（拉丁 + CJK 子集）与体积。
- 配方书 `skinned`/`custom` 的实现深度与 REI/EMI 冲突时的让位规则（`hide`/`vanilla` 已落地）。
- Boss 血条只接管 Paper BossBar，还是也支持第三方 Boss 事件。
- Tooltip 是否需要服务端逐行追加内容。
- 多面板同时需要输入时的焦点规则。
- URL 直连默认开启还是让玩家选择；默认允许列表怎么定。
- 哪些场景强制服务端代理（隐私/内网/鉴权图床）。
- GIF 64 帧上限是否满足实际美术需求。
- PAPI 同步预算（2 ms/玩家、10 ms 全局）是否适合目标玩家规模。
- NanoVG FBO 默认 1x 还是 2x；文字清晰度与 GPU 填充/显存成本的取舍。
- 是否在光影包环境下默认回退原生后端。

## M0 的完成定义

- 干净 JDK 21 环境下 `./gradlew build` 成功。
- 客户端在 1.21.11 上加载，入口打印日志，混入没有报错。
- NanoVG 在三平台初始化成功，FBO 合成一个纯色面板无异常；失败时回退原生。
- U28–U31 有结论并写入 `10-risks-and-verification.md`。
- 插件在 Paper 1.21.11 上启用，注册通道不抛异常。
- U1、U2 有结论并写入 `10-risks-and-verification.md`。
- CI 在 Windows 与 Linux 上绿色。
- 本文件更新实际命令名与偏差。

## 文件归属表

| 领域 | 模块 | 主要文档 |
| --- | --- | --- |
| 线上格式 | `cloudcore-protocol` | `02-protocol.md` |
| 文档模型 / 校验 | `cloudcore-api` | `03-ui-dsl.md`、`16-ui-document-schema.md` |
| 工程 / 构建 / 规范 | 全部 | `15-engineering-guide.md` |
| 部署 / 运维 | 全部 | `17-operations.md` |
| 测试策略 | 全部 | `09-testing.md` |
| 页面组合 / 遍历 | `cloudcore-server-core` | `12-page-composition.md` |
| 动作引擎 | `cloudcore-server-core` | `13-action-engine.md` |
| 服务端面板策略 | `cloudcore-bukkit` | `04-server.md`、`11-surfaces.md` |
| 客户端面板适配 | `cloudcore-client` | `05-client.md`、`11-surfaces.md` |
| 渲染 / 字体 / Tooltip / Boss | `cloudcore-client` | `06-resources-and-rendering.md`、`14-render-backends.md` |
| 安全评审 | 全部 | `07-security.md` |
