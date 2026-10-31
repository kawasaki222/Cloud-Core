# 08 — 路线图

估算单位是**一名有经验开发者的工作日**，包含该里程碑的测试与文档。这些是规划数字，不是承诺。
全部里程碑约 61–78 人日；不含可选的 M9 约 53–68 人日。

## M-1 — 文档冻结（已完成）

交付：

- `README.md` + `docs/00`–`docs/19` + `TODO_HANDOFF.md`。
- 协议、DSL、数据模型、服务端、客户端、渲染、安全、测试、运维、工程指南、风险与决策记录。
- 所有未验证项都有验证方式和兜底；所有待决问题都记录影响和阻塞点。
- 中文文档，术语统一；协议/DSL/schema 三处字段一致。

验收：

- 文档内交叉引用全部有效。
- 协议操作码、DSL 字段、下发 schema 不矛盾。
- 渲染后端（NanoVG 默认、原生回退）在 README/00/01/05/06/14 中一致。
- URL/PAPI/动作引擎/输入框的边界在相关文档中一致。
- 本阶段不写代码。

## M0 — 脚手架、映射与 NanoVG 验证（4–6 天，已完成；三平台验证除外）

交付物：

- Gradle 多模块构建，Java 21 工具链，版本目录。
- 客户端使用 `loom.officialMojangMappings()`，移除 `yarn_mappings`。
- `cloudcore-client` 空入口、`fabric.mod.json`、混入配置骨架。
- 客户端日志骨架：`logs/cloudcore-client.log`、级别、轮转、脱敏占位。
- `cloudcore-bukkit`、`paper-api`、`plugin.yml`、空 `JavaPlugin`。
- `lwjgl-nanovg` 依赖与 Windows/Linux/macOS natives，版本对齐 Minecraft 自带 LWJGL。
- NanoVG 初始化、FBO 创建、合成一个纯色面板的 spike。
- CI：Windows 与 Linux，JDK 21。
- 完成 U1/U2/U28–U31 的 spike（见 `10-risks-and-verification.md`）。

验收：

- `./gradlew build` 产出可加载的模组 jar 和插件 jar。
- 两端在 1.21.11 上加载无报错。
- 三平台 NanoVG 初始化成功，FBO 合成无明显色差；失败时回退原生。
- CI 绿色。
- U1、U2、U28–U31 有明确结论并记录。

## M1 — 协议核心（4–5 天，已完成）

交付物：

- 信封、varint、压缩、分片、`WireValue`、`Limits`。
- 协议 1.1：`SurfaceType`、`BindMode`、`UI_OPEN` 新字段、特性位。
- 客户端 payload：`CustomPacketPayload`、`StreamCodec`、`PayloadTypeRegistry.playS2C` / `playC2S`。
- 服务端插件消息传输 + 握手。
- 黄金向量（内联在单元测试里）、手写随机模糊测试。
- `/cloudcore ping` 往返。

验收：

- 黄金向量通过；100 万随机帧无未捕获异常。
- 握手对真实客户端可用。
- 顺序假设有测试覆盖。

## M2 — 面板框架与独立页面（7–9 天，已完成；文本 shaping 除外）

交付物：

- `SurfaceRegistry`、`SurfaceAdapter`、`SurfaceProvider` 接口。
- `SurfaceType` 六类常量；未实现的面板返回 `UI_READY(state=4)`。
- `UiDocument` 模型、校验器、单文件页面载入、修订号。
- `CustomScreenAdapter`：`Minecraft#setScreen` 混入、`CloudScreen`。
- `RenderBackend`、`NanoVgBackend`、`NativeBackend`、`FboCompositor`、`RenderList`。
- 背景/前景 FBO 与合成；预乘 alpha 和 GL 状态恢复。
- 元素：`panel`、`label`、`image`、`button`，全部由 NanoVG 绘制。
- 内置 TTF 字体与 `FontRegistry` 最小实现。
- 故障隔离：NanoVG/FBO 失败回退原生，渲染异常恢复原版。
- 诊断：文档解析/面板/渲染/资源错误带 `CC-*` 写入客户端日志；服务端 `DIAGNOSTIC` 链路。

验收：

- 独立页面能打开、渲染、关闭；NanoVG 与原生回退外观一致。
- FBO 在 GUI 缩放 1–4 下无黑边/色差。
- 一个未实现的面板类型被优雅拒绝。
- 禁用原生库时自动回退原生后端。
- 渲染异常恢复原版，只记录一次。

## M3 — 容器面板与旧界面适配（5–6 天，已完成）

交付物：

- `ContainerSurfaceAdapter`、`CloudContainerScreen`、`SlotLayout`。
- 预绑定队列、5 秒过期、标题哨兵兜底。
- `managed` / `readonly` / `passthrough` 点击策略。
- 旧界面适配：`InventoryOpenEvent`、合成文档、跳过列表。
- `ELEMENT_UPDATE`：文本、可见、启用、进度、物品。
- 原生桥分层：NanoVG 背景 → 原版物品/槽位 → NanoVG 前景。

验收：

- 9×6 商店界面在 GUI 缩放 1–4 下槽位判定正确。
- Shift 点击、拖拽、数字键交换、双击收集正常。
- 第三方箱子插件换皮且点击逻辑不变。
- 原版客户端看到原界面。

## M4 — 页面组合与遍历（4–5 天，已完成；依赖图增量编译除外）

交付物：

- `PageComposer`：`includes`、合并规则、循环检测、依赖图。
- 单文件页面与目录页面两种形态。
- `repeat` 展开：服务端求值、实例 id、实例上限。
- 重载：片段变化只重编译依赖页面。
- 校验器新增组合与遍历规则。

验收：

- 3 个片段组合的页面与单文件版本行为一致。
- 修改一个片段只影响依赖它的页面。
- 64 项列表展开、点击能区分实例。
- 超过实例上限时拒绝并记录。

## M5 — 玩家背包与 HUD 面板（7–9 天，已完成；配方书 skinned/custom 除外）

交付物：

- `PlayerInventoryAdapter`、`CloudInventoryScreen`。
- `InventoryMenu` 槽位组映射；玩家模型与效果图标开关。
- 配方书 `skinned`：保留原版列表/搜索/标签页/滚动，换外框/按钮/标签/滚动条/字体；
  `custom` 留到 v2。
- `HudSurfaceAdapter`：`HudElementRegistry`、三层锚点、单层 NanoVG FBO。
- 背包：NanoVG 背景/前景，原版物品与玩家模型夹在中间。
- 持久会话：加入时推送、reload 时刷新、权限变化时关闭。
- HUD/背包文档禁止槽位（背包除外）。

验收：

- 背包皮肤应用后，槽位、Shift 点击、数字键交换与原版一致。
- 关闭皮肤后恢复原版背包。
- HUD 隐藏时不重绘 FBO、无绘制开销。
- 背包背景/物品/前景分层正确，无遮挡或错位。
- 配方书换皮后列表/搜索/标签页/合成放置仍正常；REI/EMI 接管时回退原版。
- 原版客户端不受影响。

## M6 — Tooltip 与 Boss 血条面板（6–8 天，已完成；滚动条拖动与 tooltip_scroll 除外）

交付物：

- `TooltipSurfaceAdapter`：捕获 `GuiGraphics#renderTooltip`；NanoVG 画外框/滚动条，原版画文本。
- Tooltip 滚动：滚轮路由、固定位置、平滑插值、悬停物品变化重置。
- 滚动条样式：轨道/滑块/悬停贴图、九宫格、宽度、间距、最小滑块、颜色、拖动。
- `surface_options.tooltip` 全部选项。
- `BossBarSurfaceAdapter`：哨兵匹配、`BossHealthOverlay` 钩子、堆叠、进度同步。
- Boss 血条样式：NanoVG 画背景/填充/覆盖层，原版画标题；九宫格/方向/事件颜色染色。
- 兼容性回退：其他模组接管时回退原版。

验收：

- 超过最大高度的物品提示可滚动，滚轮不误伤快捷栏。
- 未超高的提示不消费滚轮。
- 3 条 Boss 血条堆叠正确，不重复绘制。
- 未匹配的血条保持原版。
- 滚动条贴图与颜色可替换，拖动与滚轮偏移一致。
- Boss 血条背景/填充/覆盖层可替换，方向与进度正确。
- 禁用 Tooltip/Boss 面板后完全恢复原版。

## M7 — 元素、布局与字体（8–10 天，大部分完成；文本 shaping/wrap/fit 除外）

交付物：

- `row`、`column`、`grid`、`scroll`、`overlay`；弹性分配、对齐、裁剪栈。
- `text_field`（原生 `EditBox` + 可换皮 `CloudEditBox`）、`checkbox`、`slider`、`progress`、
  `icon_button`、`item_icon`、`spacer`。
- 提示、焦点框、键盘导航、输入法。
- 字体：`nanovg_ttf` / `nanovg_bitmap` / `native`，TTF/OTF 加载、回退链、行距、NanoVG 度量缓存。
- 字体位图生成工具与示例字体；CJK 覆盖测试。
- URL 贴图：客户端直连（默认）+ 服务端代理/auto 兜底；哈希缓存、私网拒绝、大小/超时限制。
- GIF：直连模式客户端解码，代理模式服务端解码；垂直精灵表、客户端轮播、帧上限。
- `image` 的 `animated` / `loop` / `speed`。

验收：

- 带滚动列表、搜索框、滑块的页面 60 fps。
- 中文字体在缩放 1–4 下清晰且布局正确。
- 输入框外框/背景/字体/占位符/焦点/错误态可换皮，IME、选区、剪贴板保持原版行为。
- 缺失字体回退不崩溃。
- 无裁剪泄漏。
- 合法 https 图片正常显示，内网/回环/非标准端口 URL 被拒绝。
- 客户端直连时图片字节不经过游戏连接；代理模式可手动开启。
- 内容哈希缓存命中，不重复下载。
- GIF 播放、暂停、循环正确，超限被拒绝且不影响页面加载。

## M8 — 动作列表与引擎接缝（4–5 天，部分完成；可替换的 ActionEngine 接缝与冷却未做）

交付物：

- 线性动作列表、条件、权限、冷却、延迟。
- `ActionEngine` 接口与 `LinearActionEngine`。
- `ActionHandler` 注册 API。
- 占位符刷新：`static` / `interval` / `tick` / `event` / `manual`，同步默认、异步白名单、预算与差异推送。
- 附属 API 稳定面：`PageBuilder`、`PageProvider`、`ActionHandler`、`PlaceholderResolver`、事件监听、
  testkit（跨 M8/M10 完成，见 `19-addon-api.md`）。
- 动作执行日志与错误返回。

验收：

- 每种动作执行一次且仅一次。
- 权限失败拒绝整次点击。
- 延迟动作在指定 tick 后执行。
- PAPI 变量按周期刷新，只发差异。
- 慢扩展被预算推迟，不阻塞 tick。
- 异步白名单外的命名空间仍同步解析。
- 引擎实现可替换，服务端不依赖具体实现。

## M9 — 动作引擎 v2（8–10 天，后期，服务端，未开始）

交付物：

- `GraphActionEngine`：序列、分支、循环、等待、并行、随机、变量作用域。
- 编译期校验与运行时预算。
- 等待事件恢复执行。
- 持久化玩家变量：默认 SQLite，可选 MySQL；连接池、迁移、异步 flush。
- 与遍历、打开/关闭面板的集成。

验收：

- 分支/循环/等待场景可复现。
- 单 tick 时间预算不被突破。
- 引擎崩溃不影响其他会话。
- 旧线性动作自动编译为序列节点。

## M10 — 加固与发布（6–8 天，未开始；`stats`/`surfaces`、互操作矩阵、发布流程待做）

交付物：

- 安全清单全部完成。
- `/cloudcore stats`、`/cloudcore surfaces`、`validate`、F3 叠加层。
- 示例内容包：商店、背包皮肤、HUD、Tooltip、Boss 血条。
- 兼容性矩阵：REI、EMI、JEI、Sodium/Iris、常见 HUD/Tooltip 模组。
- 三平台 NanoVG 原生库打包与回退验证；FBO 性能与色差检查。
- 日志轮转、脱敏、诊断包 `F10/ dump`、错误码覆盖测试。
- 发布流程、变更日志、校验和。
- 文档与实现一致。

验收：

- 100 个模拟会话 × 30 分钟无泄漏。
- 模糊测试干净。
- 六类面板全部通过手动矩阵。
- 三平台 NanoVG 初始化/回退/FBO 检查通过。
- 新管理员只靠文档能完成一套多片段页面。

## 任务依赖图

```
M0 ─► M1 ─► M2 ─┬─► M3 ─┬─► M4 ─┬─► M5 ─┐
                │        │        ├─► M6 ─┤
                │        └────────┴─► M7 ─┼─► M10
                └─────────────────────────┘
                         M8 ◄── M4 ──► M9
```

M2 之后 M3、M7 可以并行；M5/M6 依赖 M3 的槽位与绑定；M8 依赖 M4 的页面模型；M9 在 M8 之后。
M2 的 NanoVG/FBO 合成是后续所有渲染工作的前置条件，失败会阻塞整个客户端。

## 每个里程碑的完成定义

- `-Werror` 编译通过。
- 新协议行为有单元测试和黄金向量。
- 新页面语法有校验规则和失败样例。
- 新面板有适配器单元测试与手动清单。
- 文档与代码在同一提交更新。
- 没有无主 `TODO`。

## 发布版本号

- 模组/插件：`MAJOR.MINOR.PATCH`。
- 协议：`major.minor`，独立递增。
- 兼容承诺：主版本相同即可互操作；服务端支持落后一个次版本的客户端。
- 每次发布在 `plugin.yml` 与 `HELLO_ACK` 中公布协议版本。

## 进度风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Mojang 映射 API 与预期不符 | 1–3 天返工 | M0 就验证 U3/U5/U12/U13 |
| Tooltip 渲染被原版延迟或重写 | M6 超期 | 先做最小 spike，确认取消/替换路径 |
| Boss 血条私有方法签名变化 | M6 超期 | 优先 `@WrapOperation`，退化为 HEAD 接管 |
| 玩家背包配方书兼容 | M5 超期 | v1 明确隐藏配方书；v2 再补 |
| 遍历展开导致文档过大 | 性能问题 | 实例上限、整文档元素上限、后续增量补丁 |
| 多面板混入冲突 | 兼容性工作量 | 互操作矩阵从 M2 开始 |
| 映射再次改名（26.1） | 迁移成本 | 版本敏感代码集中，已使用 Mojang 映射 |
| NanoVG 原生库/版本不兼容 | M0/M2 阻塞 | 对齐 LWJGL、三平台 spike、原生回退 |
| FBO 预乘 alpha/合成色差 | M2 返工 | 先做纯色 + TTF 文本 spike，再进元素 |
| NanoVG 文本与原版文本不一致 | 视觉割裂 | 本地 CloudCore 目录同时提供 MC 字体；关键文本走原生 |
