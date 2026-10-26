# 10 — 风险、已核实事实与待验证项

本文档把**已知**和**假设**分开。假设清单里的任何一条，在完成验证之前都不能当作定论。

## 1. 已核实事实

### 1.1 工具链

| 项目 | 版本 / 说明 |
| --- | --- |
| Minecraft | 1.21.11 |
| Java | 21 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.141.3+1.21.11 |
| Fabric Loom | 1.14-SNAPSHOT（1.21.11 的映射迁移要求 Loom ≥ 1.13） |
| Paper API | 1.21.11-R0.1-SNAPSHOT |

### 1.2 Mojang 映射与 1.21.11 改名

- 客户端使用 `loom.officialMojangMappings()`；`gradle.properties` 不再需要 `yarn_mappings`。
- 1.21.11（快照 25w45a）把 `net.minecraft.resources.ResourceLocation` 重命名为
  `net.minecraft.resources.Identifier`；`ResourceKey.location()` 改为 `ResourceKey.identifier()`。
  工厂方法为 `Identifier.fromNamespaceAndPath(...)` / `Identifier.parse(...)`。
- Paper 1.21.11 也切换到了 Mojang 映射（PaperMC PR #13388）。
- 1.21.11 是最后一个提供 Yarn 映射的版本；从 26.1 起 Minecraft 不再混淆，Java 要求 25，
  Loom 插件 id 变为 `net.fabricmc.fabric-loom`。
- 26.1 会把 `GuiGraphics` 改名为 `GuiGraphicsExtractor`，`render` 改为 `extract`；本项目已用
  Mojang 映射，迁移主要是适配器重写，不是映射迁移。

### 1.3 Fabric 网络 API（Mojang 映射）

- `CustomPacketPayload`、`CustomPacketPayload.Type`、`StreamCodec`、
  `RegistryFriendlyByteBuf`。
- `PayloadTypeRegistry.playS2C()` / `playC2S()`（0.141.3 的方法名；Fabric 自己的方法不会被 Loom
  重命名，虽然参数类型已映射为 Mojang 名）。
- `ClientPlayNetworking.registerGlobalReceiver(Type, handler)`。
- `StreamCodec.of` 的编码器参数顺序是 `(buf, value)`，与 Yarn 的 `PacketCodec` 相反。
- `PayloadTypeRegistry.registerLarge(...)` 存在，但本项目不使用超大载荷。

### 1.4 HUD

`HudLayerRegistrationCallback` 在 1.21.6 被 `HudElementRegistry` 取代；`HudRenderCallback` 从
Fabric API 0.116 起废弃。元素是接收 `(GuiGraphics, DeltaTracker)` 的 `HudElement`。
`VanillaHudElements` 提供锚点。

### 1.5 1.21.6 渲染管线

GUI 渲染改为提取/绘制两阶段；`GuiGraphics` 调用写入渲染状态，文本颜色必须完整 ARGB。

### 1.6 1.21.11 的 GUI 绘制

`GuiGraphics#blit` 在 1.21.11 需要 `RenderPipeline` 参数（例如 `RenderPipelines.GUI_TEXTURED`），
并使用 `Identifier` 作为贴图键。

### 1.7 Paper 插件消息

- `PluginMessageRecipient#sendPluginMessage(Plugin, String channel, byte[])`。
- 必须先注册出站和入站通道，否则抛 `ChannelNotRegisteredException`。
- 最大消息长度 `Messenger.MAX_MESSAGE_SIZE`（1 MiB）。
- 通道使用命名空间键格式。
- 内部包成 `ClientboundCustomPayloadPacket`，载荷是 `DiscardedPayload`。

### 1.8 大小上限

- 客户端方向自定义载荷：1,048,576 字节。
- 服务端方向自定义载荷：32,767 字节。

### 1.9 Boss 血条

- `net.minecraft.client.gui.components.BossHealthOverlay`，构造参数 `Minecraft`，
  渲染入口 `render(GuiGraphics)`。
- 内部通过私有 `drawBar(GuiGraphics, int x, int y, BossEvent, int progress,
  Identifier[], Identifier[])` 绘制；1.21.11 的确切参数列表需确认。
- `Gui` 通过 `getBossOverlay()` 持有该 overlay。
- 事件类型 `BossEvent` / `LerpingBossEvent`，提供 `getName()`、`getProgress()`、`getColor()` 等。

### 1.10 玩家背包

- `net.minecraft.client.gui.screens.inventory.InventoryScreen`。
- 1.21.8 Javadoc 显示 `InventoryScreen extends AbstractRecipeBookScreen<InventoryMenu>`；
  1.21.11 需确认。
- `InventoryMenu` 槽位：0 合成输出，1–4 合成输入，5–8 护甲，9–35 主物品栏，36–44 快捷栏，
  45 副手。
- `AbstractContainerScreen` 提供 `renderBg`、`renderLabels`、`renderTooltip(GuiGraphics,int,int)`，
  字段 `leftPos`/`topPos`、`imageWidth`/`imageHeight`、`menu`、`font`。

### 1.11 文本与提示

- `GuiGraphics#renderTooltip(Font, Component, int, int)` 与列表/组件重载存在。
- 1.21.6+ 文本颜色为 ARGB。
- 原版可能在帧末延迟绘制 tooltip；取消/替换时必须配合该时序。

## 2. 未验证假设

| 编号 | 假设 | 为何重要 | 验证方式 | 兜底 |
| --- | --- | --- | --- | --- |
| U1 | Paper `DiscardedPayload` 字节能到达 Fabric 注册的 payload 类型 | 整个传输依赖 | `PING`/`PONG` 真客户端 | 无理想兜底；需重新设计传输 |
| U2 | `InventoryOpenEvent` 中的插件消息早于 `OpenScreen` | 容器预绑定 | 100 次顺序日志 | 标题哨兵 |
| U3 | 1.21.11 `GuiGraphics#blit` / `renderTooltip` 的确切重载 | 渲染器 | 编译期读映射源码 | 只改 `FboCompositor` / `NativeBackend` |
| U4 | `HudElementRegistry` 在 0.141.x 的包名与锚点 | HUD | 编译 + 运行 | 单锚点 `addLast` |
| U5 | `AbstractContainerScreen` 的 `leftPos/topPos` 与 `slot.x/y` 可写 | 槽位布局 | 3×3 网格 spike | 使用默认布局 |
| U6 | Paper 1.21.11 `setResourcePack` 重载 | 资源包 | Javadoc | 旧重载/外部 URL |
| U7 | `ItemStack` 由 `{id, count, components}` 构建 | 装饰物品 | spike | v1 去掉装饰物品 |
| U8 | `InventoryView#setTitle` 在打开事件中生效 | 标题哨兵 | 抓包 | 关闭哨兵 |
| U9 | 混入优先级 900 相对 REI/EMI | 界面替换 | 互操作 | 排除列表 |
| U10 | `canSend` 需要 `playC2S` 注册 | 发送 `HELLO` | 去掉注册观察 | 两侧都注册 |
| U11 | `StreamCodec.of` 的签名与参数顺序 | payload | 编译 | 按映射调整 |
| U12 | `Screen#mouseScrolled` 的参数列表 | Tooltip 滚动 | 编译 | 只改混入 |
| U13 | `BossHealthOverlay#drawBar` 可被 `@WrapOperation` 包裹 | Boss 面板 | 编译 + 运行 | HEAD 取消并自行渲染 |
| U14 | `InventoryScreen` 在 1.21.11 的父类与构造 | 背包面板 | 编译 | 自定义 `AbstractContainerScreen<InventoryMenu>` |
| U15 | 客户端打开背包一定经过 `Minecraft#setScreen` | 背包拦截 | 运行 | 仅在打开时应用，不拦截按键 |
| U16 | 原版 Tooltip 的延迟绘制时序可被安全替换 | Tooltip 面板 | spike + 视觉 | 只改样式，不接管 |
| U17 | 资源包字体在 1.21.11 的 provider 行为 | 字体 | 运行 | 只用内置位图字体 |
| U18 | `AbstractRecipeBookScreen` 的构造与状态 | 配方书 | 编译 | v1 隐藏配方书 |
| U19 | 带哨兵的 Paper BossBar 标题对模组客户端可见 | Boss 匹配 | 抓包 | 自绘 Boss 血条（v2） |
| U20 | Fabric API 0.141.3 能重映射到 officialMojangMappings | 构建 | M0 构建 | 固定 Loom 版本/升级 API |
| U21 | PlaceholderAPI 扩展在主线程解析安全，异步仅限白名单 | 占位符刷新 | 阅读扩展 + 压力测试 | 全部同步，取消异步 |
| U22 | 客户端 JRE 的 `ImageIO` 可解码 GIF/JPEG，且内存可控 | 直连模式 GIF | 启动测试 + 解码样例 | 内置纯 Java 解码器，或强制 proxy 模式 |
| U23 | 客户端直连能在 DNS/重定向层可靠阻断私网与回环 | 安全 | 安全测试用例 | 只允许预置 CDN 域名，或强制 proxy |
| U24 | Tooltip 滚动条拖动不与滚轮/其他模组冲突 | Tooltip 交互 | 互操作测试 | 只保留滚轮，关闭拖动 |
| U25 | Minecraft 客户端允许 `java.net.http.HttpClient` 异步下载且不触发线程/类加载问题 | 直连模式 | 最小客户端 spike | 使用独立低优先级线程池 |
| U26 | `EditBox` 的光标/选区颜色可被安全覆盖，且不影响 IME | 输入框换皮 | 编译 + IME 测试 | 只换外框，光标/选区保持原版 |
| U27 | `renderItemDecorations(Font, ...)` 可用于自定义数量字体 | 物品显示 | 编译 + 视觉 | 数量字体保持原版 |
| U28 | `lwjgl-nanovg` 版本与 1.21.11 自带 LWJGL 对齐，三平台原生库可加载 | NanoVG 默认 | 三平台初始化测试 | 回退 `NativeBackend` |
| U29 | FBO 颜色纹理可包装成 `AbstractTexture` 并通过 `GuiGraphics.blit` 合成 | 渲染链路 | 编译 + 运行 | 自定义 `RenderPipeline` 合成 |
| U30 | NanoVG 预乘 alpha 与 MC GUI 混合函数匹配 | 半透明边缘 | 视觉对比 | shader 反预乘或调整混合 |
| U31 | NanoVG FBO 渲染不会破坏 1.21.6 提取/绘制状态 | 渲染稳定性 | 运行 + 截图 | 在独立 pass/回退原生 |
| U32 | TTF/OTF 从资源包加载、CJK 覆盖与 NanoVG 度量符合布局 | 字体/布局 | 字体样例 + 中文测试 | 内置字体/原版 `Font` |
| U33 | Iris/Sodium 下 FBO 合成无色差/黑边 | 光影兼容 | 互操作矩阵 | 配置回退原生 |
| U34 | 服务端与客户端对文档 schema/限额的校验结果一致 | 数据模型 | 共享失败样例 + 黄金文档 | 以服务端为准，客户端防御性拒绝 |
| U35 | 客户端日志/诊断包在三平台路径、轮转、UTF-8 正常 | 排障 | 三平台故障用例 | 统一路径/编码，日志写入失败退回控制台 |
| U36 | 本地 CloudCore 目录的直接读取与 `F9` 重载在三平台稳定 | 资源 | 三平台资源测试 | 退回资源包/内置回退 |

验证记录格式：

```
U1: 已核实 2026-xx-xx — PingPongTest，客户端日志第 42 行
U13: 未通过 — drawBar 参数在 1.21.11 变化；改用 HEAD 接管
```

### 2.1 本轮实现记录（2026-10-26）

已通过编译、单元测试，并在**真实 Paper + Fabric 开发客户端**上完成一次本地联调
（Paper 1.21.11 build 132，Fabric Loader 0.19.5，Fabric API 0.141.3+1.21.11，离线模式）。

已核实：

- **U1 已核实**：Paper 插件消息能到达 Fabric 注册的 payload；客户端 `HELLO` 成功送达服务端，
  服务端 `HELLO_ACK` 成功送达客户端。实测同一 tick 内立即回 ACK 会丢失，服务端延后 1 tick
  后稳定送达；该时序已写入 `02-protocol.md` 实现说明。
- **PING/PONG 已核实**：`/cloudcore ping` 经客户端回包，服务端报告 `rtt=9ms`。
- **UI_OPEN / UI_DEFINITION / UI_READY / UI_CLOSED 已核实**：服务端发送 demo 文档，客户端
  解码、校验并打开 `CloudScreen`，回 `UI_READY`；按 Escape 后回 `UI_CLOSED(reason=0)`。
- **真机分片与压缩已核实**：`/cloudcore demo-big` 发送 64,776 字节文档，压缩后拆成 3 个
  FRAGMENT（24,018 + 24,018 + 1,002 字节），客户端重组并回 `UI_READY`。
- **原生 RenderList 已核实**：demo 页面实际显示 panel、label、button；截图与客户端日志一致。
- **U20 已核实**：Fabric API 0.141.3 能在 officialMojangMappings 下编译并启动。
- **U10 已核实并规避**：Fabric `canSend()` 只反映服务端声明的通道；Paper 不保证声明，
  发送路径不再用 `canSend()` 作为门控。
- 分片/重组、压缩分片、乱序、重复片、组冲突、超时、4 组上限、1 MiB 上限：
  `FragmentCodecTest`。
- 1,000,000 个随机帧解码：只允许 `ProtocolException`，无未捕获运行时异常。
- HELLO 黄金字节、HELLO_ACK、PING/PONG、UI_OPEN/UI_READY/UI_CLOSE/UI_CLOSED：
  `HandshakeCodecTest`、`UiCodecTest`。
- 文档模型与校验器、服务端 SurfaceProvider 注册表、客户端 SurfaceRegistry：
  对应 JUnit 测试。
- `./gradlew clean build` 通过（JDK 21，60+ 测试，0 失败）。

仍未验证：

- U2：`InventoryOpenEvent` 与 `OpenScreen` 的先后顺序；需要容器 spike。
- U3、U12、U13：`GuiGraphics` blit/tooltip、`Screen#mouseScrolled`、Boss 血条钩子。
- U28–U31：NanoVG 三平台原生库、FBO 合成、预乘 alpha、1.21.6 提取/绘制状态。
- NanoVG/FBO：`NanoVgBackend.available()` 仍为 `false`，`FboCompositor` 未实现。
- UI_DEFINITION 已能解码/校验并打开 `CloudScreen`；原生 RenderList 已支持 panel/label/button/
  text_field/checkbox/progress；image/item_icon、NanoVG/FBO、裁剪/滚动、混入替换仍未实现。

## 3. 风险登记

| 编号 | 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- | --- |
| R1 | Paper↔Fabric 插件消息互操作失效 | 中 | 高 | CI 集成测试；协议与传输解耦 |
| R2 | GUI 渲染 API 变动（1.21.6、26.1） | 高 | 中 | 单一适配器 |
| R3 | 与物品栏模组的混入冲突 | 高 | 中 | 窄钩子、回退、互操作矩阵 |
| R4 | 槽位判定在非默认缩放下错位 | 中 | 高 | 缩放 1–4 全程测试 |
| R5 | 旧界面适配破坏第三方插件 | 中 | 高 | `passthrough` 不取消；跳过列表 |
| R6 | Tooltip 接管与延迟渲染时序冲突 | 中 | 中 | 先 spike；失败时只做样式 |
| R7 | Boss 血条私有方法签名变化 | 中 | 中 | `@WrapOperation` + HEAD 兜底 |
| R8 | 玩家背包配方书兼容 | 中 | 中 | v1 隐藏，v2 继承 `AbstractRecipeBookScreen` |
| R9 | 遍历展开导致文档膨胀 | 中 | 中 | 实例上限、元素上限、后续增量补丁 |
| R10 | 多片段页面重载遗漏依赖 | 中 | 中 | 依赖图 + 集成测试 |
| R11 | 映射改名（26.1） | 必然 | 中 | 已用 Mojang 映射；适配器隔离 |
| R12 | 管理员写出不安全页面（命令注入） | 中 | 高 | 校验器警告、文档、权限 |
| R13 | 贴图/字体资源耗尽 | 低–中 | 中 | 字节与解码上限、LRU |
| R14 | 裁剪状态泄漏 | 中 | 高 | 成对 push/pop + 专项测试 |
| R15 | 多面板同时开启导致输入歧义 | 中 | 中 | 每类型单会话；焦点规则 |
| R16 | 动作引擎 v2 时间预算被长脚本打破 | 中 | 高 | 每 tick 预算、等待节点、取消机制 |
| R17 | 客户端直连被用于 SSRF、内网探测或玩家 IP 跟踪 | 低–中 | 高 | 仅 https、私网/回环检查、重定向复核、`remoteImages=false`、proxy 模式 |
| R18 | GIF 解码耗尽内存或 CPU（客户端或服务端） | 中 | 中 | 帧数/像素/延迟上限，边解码边中止 |
| R21 | 大量玩家同时拉图导致服务端上行打满 | 中 | 高 | 默认 `client` 直连，proxy 仅按需开启 |
| R22 | LWJGL NanoVG 版本与 Minecraft 自带 LWJGL 冲突 | 中 | 高 | 对齐版本、排除重复模块、三平台测试 |
| R23 | FBO 预乘 alpha/混合导致黑边或色差 | 中 | 中 | 视觉对比、调整混合、回退原生 |
| R24 | NanoVG 文本与原版 EditBox/Tooltip 字体不一致 | 高 | 中 | 资源包同时提供 MC 字体；关键文本走原生 |
| R25 | Iris/光影包下 FBO 合成异常 | 中 | 中 | 互操作矩阵；配置回退 |
| R26 | FBO 填充/采样成本拖低帧率 | 中 | 中 | 逻辑分辨率、脏矩形、静态层缓存、单层合并 |
| R27 | 26.1 的 FBO/RenderTarget/GuiGraphicsExtractor 变化 | 高 | 中 | 后端隔离，合成层单独适配 |
| R28 | DSL、下发 schema、协议三处字段漂移 | 中 | 高 | 字段定义以 `cloudcore-api` 为单一来源；黄金文档+校验器测试 |
| R29 | 客户端日志泄露敏感信息或刷屏 | 中 | 中 | 默认脱敏、限流去重、轮转、诊断包检查 |
| R30 | 本地 CloudCore 资源版本不一致导致红图 | 高 | 中 | 资源版本/哈希清单、`F9` 重载、明确日志与红色占位 |
| R31 | SQLite 并发写/锁竞争 | 中 | 中 | WAL、单写线程、防抖、生产切 MySQL |
| R32 | 公共 API 兼容性破坏附属插件 | 中 | 高 | 稳定 `api` 模块、语义化版本、废弃保留一个大版本 |
| R33 | 配方书换皮/自定义与 REI/EMI 冲突 | 高 | 中 | v1 只换皮、检测接管、回退原版 |
| R34 | 输入优先级在多面板下产生双重处理 | 中 | 中 | 单一 `InputOwner`、固定优先级、专项测试 |
| R19 | 慢 PlaceholderAPI 扩展拖垮 tick | 中 | 高 | 预算、推迟、异步白名单、缓存 |
| R20 | 滚动条/Boss 样式字段导致布局异常 | 低 | 低 | 范围校验 + 默认值 |

## 4. 决策记录

| 编号 | 决策 | 理由 | 后果 |
| --- | --- | --- | --- |
| ADR-001 | 共享模块不含 Minecraft 类 | 两端类路径不同；可独立测试 | 边界转换 |
| ADR-002 | 使用原版插件消息通道 | Paper 无需模组 | 依赖 U1 |
| ADR-003 | 二进制信封 + JSON 文档 | 严格边界 + 模式灵活性 | 两套编解码 |
| ADR-004 | v1 不做元素树增量补丁 | 文档小、可缓存 | 遍历变化下发完整文档 |
| ADR-005 | 预绑定为主，标题哨兵兜底 | 顺序确定 | 两条路径都要测 |
| ADR-006 | 槽位保持原版 | 兼容第三方插件 | 不自建物品移动 |
| ADR-007 | 使用 `HudElementRegistry` | 1.21.6+ 必需 | HUD 只支持 1.21.6+ |
| ADR-008 | 默认 NanoVG 离屏 + 原生合成；原生 `GuiGraphics` 回退 | 矢量能力、服务端样式表达、统一绘制 API | 需要原生库、FBO、字体与回退测试 |
| ADR-009 | 不做客户端脚本 | 安全与确定性 | 只有服务端动作 |
| ADR-010 | 客户端和服务端统一 Mojang 映射 | 1.21.11 已改名，Paper 也已切换 | 26.1 迁移更平滑 |
| ADR-011 | 面板抽象为 `SurfaceAdapter` | 六类面板共享文档/状态/输入模型 | 新面板需两端适配器 |
| ADR-012 | Boss 血条采用"接管原版"而非自绘 | 避免重复显示，保留原版同步 | 需要哨兵匹配 |
| ADR-013 | 遍历在服务端展开 | 客户端不执行模板逻辑 | 列表变化下发完整文档 |
| ADR-014 | 页面组合在加载期完成 | 客户端只接收单一文档 | 重载需要依赖图 |
| ADR-015 | 动作引擎以接口接缝预留 | v1 线性动作、v2 图执行 | 服务端不依赖具体实现 |
| ADR-016 | 玩家背包是持久客户端面板 | 服务端不打开该界面 | 只推皮肤，不接管菜单 |
| ADR-017 | Tooltip 内容客户端生成，服务端只给样式 | 物品提示来自原版与模组 | 服务端无法逐行控制 |
| ADR-018 | `url:` 图片默认客户端直连，服务端代理可选 | 代理模式下服务端上行 = 玩家数 × 图片字节，规模一大就堵 | 客户端需执行网络策略；隐私场景用 `proxy` |
| ADR-019 | GIF 直连模式客户端解码，代理模式服务端解码 | 图片字节与解码负载跟随所选传输模式 | 两侧都必须有帧/像素/延迟上限 |
| ADR-020 | PlaceholderAPI 默认同步、异步白名单 | PAPI 扩展不保证线程安全 | 慢扩展靠预算推迟 |
| ADR-021 | Tooltip 滚动条与 Boss 血条样式全部数据化 | 管理员可换皮，不需改代码 | 需要范围校验与默认样式 |
| ADR-022 | NanoVG 为默认渲染后端，NativeBackend 为完整回退，SDF 仅实验 | 矢量 UI 与服务端样式表达优先；物品/输入/tooltip 内容保持原版 | 后端可插拔，协议不感知 |
| ADR-024 | 原生桥固定负责物品、槽位、Tooltip 内容、EditBox 文本 | 重写这些的维护成本远高于收益 | NanoVG 只画外框/装饰/静态文本 |
| ADR-025 | 下发文档剥离 `state`、动作体、命令、`repeat`、`includes`、原始 URL | 客户端不需要，且不能泄露命令/权限 | 客户端只拿 actionId 与展开后的元素 |
| ADR-026 | 主题令牌在客户端按 `THEME_SET` 解析，服务端只校验存在 | 换主题不需重发文档 | 文档保留 `tx:` 引用 |
| ADR-027 | `url:` 编译为 `inline:` 键，URL/哈希由 `TEXTURE_REF` 下发 | 文档与 URL 解耦，支持缓存/代理/直连 | 客户端文档里没有原始 URL |
| ADR-028 | 正式素材放 `.minecraft/resourcepacks/CloudCore/`，可按业务分类，模组只留最小回退 | 商业服统一分发客户端包，插件不负责资源分发 | `local:` 直接读文件系统，`F9` 重载 |
| ADR-029 | 诊断客户端优先：所有错误写客户端日志，服务端 `DIAGNOSTIC` 下发摘要 | 作者/管理员排障需要完整上下文 | 需要脱敏、限流、诊断包 |
| ADR-030 | SQLite 作为零配置默认，MySQL 作为生产选项 | 单服/开发开箱即用，大服可切 MySQL | 需要存储抽象、迁移、连接池 |
| ADR-031 | 配方书 v1 只换皮（`skinned`），完全自定义留 v2 | 保留配方数据/搜索/放置逻辑，降低 REI/EMI 冲突 | `custom` 需要独立子面板与测试 |
| ADR-032 | 输入按固定优先级：Tooltip 滚动条 → 模态 → 元素 → 槽位 → 原版 | 确定性、少意外、和原版一致 | 需要 `InputOwner` 状态机与测试 |
| ADR-033 | `cloudcore-api` 作为合同，1.x 向后兼容；内部实现不承诺 | 商业 RPG 团队需要稳定附属 API | 需要测试工具包、废弃策略、独立版本 |
| ADR-023 | 输入框只换皮，不重写输入逻辑；物品/槽位/tooltip 保持原版 | IME、选区、模组渲染器和 tooltip 组件的维护成本过高 | 服务端可配外框/字体/颜色/状态，文字与输入法原生 |

## 5. 迁移到 Minecraft 26.1

1. 已使用 Mojang 映射，不需要 Yarn → Mojang 迁移。
2. Java 25 工具链，Loom 升级到 26.1 要求。
3. `GuiGraphics` → `GuiGraphicsExtractor`，`render` → `extract`；重写 `FboCompositor` 和
   `NativeBackend` 的合成/回退路径。NanoVG 本身的绘制 API 不受影响，但 FBO/RenderTarget 创建、
   绑定和混合状态需要重新适配。
4. 重新验证第 2 节；大部分是编译错误。
5. 协议不变，除非线上格式变化。

Paper 侧只需跟随 API 版本；插件不使用 NMS，映射变化影响很小。

## 6. 待决问题（M2 之前）

1. **默认字体**：内置位图字体的字形覆盖范围（拉丁 + CJK 子集）与体积取舍。
2. **玩家背包配方书**：v1 隐藏是否可接受，还是必须提前支持。
3. **Boss 血条**：是否只接管 Paper BossBar，还是允许第三方插件的 Boss 事件也通过哨兵接入。
4. **Tooltip**：是否需要服务端逐行追加内容（当前只支持样式 + 物品 lore）。
5. **面板优先级**：多个面板同时需要输入时（例如背包 + 覆盖层）的焦点规则。

## 参考来源

- Fabric 版本与 1.21.11 工具链：<https://fabricmc.net/develop>
- Fabric 网络（1.21.11）：<https://docs.fabricmc.net/zh_cn/1.21.11/develop/networking>
- Fabric GUI 绘制（1.21.11）：<https://docs.fabricmc.net/1.21.11/develop/rendering/gui-graphics>
- Fabric HUD（1.21.11）：<https://docs.fabricmc.net/1.21.11/develop/rendering/hud>
- Fabric 映射迁移：<https://docs.fabricmc.net/1.21.11/develop/porting/mappings/loom>
- 1.21.11 的 `ResourceLocation` → `Identifier`：<https://docs.neoforged.net/primer/docs/1.21.11/>
- NeoForge 21.11 发布说明：<https://neoforged.net/news/21.11release/>
- Paper 1.21.11 映射切换：<https://github.com/PaperMC/Paper/pull/13388>
- Paper 插件消息：<https://docs.papermc.io/paper/dev/plugin-messaging>
- `BossHealthOverlay` Javadoc：<https://aldak0.ru/javadoc/1.21.5-21.5.x/net/minecraft/client/gui/components/BossHealthOverlay.html>
- `InventoryScreen` Javadoc：<https://aldak0.ru/javadoc/1.21.8-21.8.x/net/minecraft/client/gui/screens/inventory/InventoryScreen.html>
- `HudLayerRegistrationCallback` 被取代：<https://github.com/orgs/FabricMC/discussions/4840>
