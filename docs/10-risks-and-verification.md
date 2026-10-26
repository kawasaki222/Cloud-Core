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

- 两个重载含义不同（已用 `javap -c` 确认）：
  - 10 参：`blit(pipeline, id, x, y, u, v, width, height, texW, texH)` 源区域和目标是
    **1:1**（`uWidth=width`），放大贴图会变成边缘拉伸/重复；
  - 12 参：`blit(pipeline, id, x, y, u, v, w, h, srcW, srcH, texW, texH)` 把源区域
    `(u,v,srcW,srcH)` **拉伸**到 `(x,y,w,h)`，缩放图片和九宫格必须用这个。
  `textureWidth/Height` 从 `AbstractTexture#getTexture().getWidth(0)` 取。
- 旧的 `blit(Identifier, int, int, int, int, float, float, float, float)` 在 1.21.11 对未进图集的
  贴图（`DynamicTexture`、FBO 纹理）**不显示**：已实测本地 PNG 与 NanoVG FBO 都因此不可见，
  换成上面的重载后同时恢复。`local:`、`pack:`、`builtin:` 图片与 NanoVG 合成都走这一条。

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
- 内部有两个私有重载（1.21.11 已确认）：
  `drawBar(GuiGraphics, int, int, BossEvent)` 和
  `drawBar(GuiGraphics, int, int, BossEvent, int, Identifier[], Identifier[])`。
- `Gui` 通过 `getBossOverlay()` 持有该 overlay。
- 事件类型 `BossEvent` / `LerpingBossEvent`，提供 `getName()`、`getProgress()`、`getColor()` 等。

### 1.10 玩家背包

- `net.minecraft.client.gui.screens.inventory.InventoryScreen`。
- 1.21.11 已用 javap 确认：`InventoryScreen extends AbstractRecipeBookScreen<InventoryMenu>`，
  构造函数为 `InventoryScreen(Player)`，内部使用 `player.inventoryMenu`。
- `InventoryMenu` 槽位：0 合成输出，1–4 合成输入，5–8 护甲，9–35 主物品栏，36–44 快捷栏，
  45 副手。
- `AbstractContainerScreen` 提供 `renderBg`、`renderLabels`、`renderTooltip(GuiGraphics,int,int)`，
  字段 `leftPos`/`topPos`、`imageWidth`/`imageHeight`、`menu`、`font`。
  1.21.11 的 `GuiGraphics#pose` 返回 `org.joml.Matrix3x2fStack`（`pushMatrix`/`popMatrix`），
  不是 `PoseStack`。

### 1.11 文本与提示

- 1.21.11 的 `GuiGraphics` 只暴露一个 tooltip 入口（已确认）：
  `renderTooltip(Font, List<ClientTooltipComponent>, int, int, ClientTooltipPositioner, Identifier)`。
  `AbstractContainerScreen` 另有 `renderTooltip(GuiGraphics, int, int)`。
- 1.21.6+ 文本颜色为 ARGB。
- 原版可能在帧末延迟绘制 tooltip；取消/替换时必须配合该时序。
- 滚轮入口在 `GuiEventListener#mouseScrolled(double, double, double, double)`；
  `Screen` 本身不覆盖，`AbstractContainerScreen` 覆盖。混入目标需要按此选择。

## 2. 未验证假设

| 编号 | 假设 | 为何重要 | 验证方式 | 兜底 |
| --- | --- | --- | --- | --- |
| U1 | Paper `DiscardedPayload` 字节能到达 Fabric 注册的 payload 类型 | 整个传输依赖 | `PING`/`PONG` 真客户端 | 无理想兜底；需重新设计传输 |
| U2 | `InventoryOpenEvent` 中的插件消息早于 `OpenScreen` | 容器预绑定 | 2026-10-26 真实 Paper/Fabric 顺序日志已核实（PING 先于 `ContainerScreen`） | 标题哨兵 |
| U3 | 1.21.11 `GuiGraphics#blit` / `renderTooltip` 的确切重载 | 渲染器 | 2026-10-26 `javap` + 真实 Tooltip 混入 | 只改 `FboCompositor` / `NativeBackend` |
| U3a | 已核实：`blit(RenderPipeline, Identifier, int, int, float, float, int, int, int, int, int)` 等重载；`renderTooltip(Font, List<ClientTooltipComponent>, int, int, ClientTooltipPositioner, Identifier)` | 渲染器 | 2026-10-26 静态 + 运行验证 | 无 |
| U4 | `HudElementRegistry` 在 0.141.x 的包名与锚点 | HUD | 编译 + 运行 | 单锚点 `addLast` |
| U5 | `AbstractContainerScreen` 的 `leftPos/topPos` 与 `slot.x/y` 可写 | 槽位布局 | 3×3 网格 spike | 使用默认布局 |
| U6 | Paper 1.21.11 `setResourcePack` 重载 | 资源包 | Javadoc | 旧重载/外部 URL |
| U7 | `ItemStack` 由 `{id, count, components}` 构建 | 装饰物品 | spike | v1 去掉装饰物品 |
| U8 | `InventoryView#setTitle` 在打开事件中生效 | 标题哨兵 | 抓包 | 关闭哨兵 |
| U9 | 混入优先级 900 相对 REI/EMI | 界面替换 | 互操作 | 排除列表 |
| U10 | `canSend` 需要 `playC2S` 注册 | 发送 `HELLO` | 去掉注册观察 | 两侧都注册 |
| U11 | `StreamCodec.of` 的签名与参数顺序 | payload | 编译 | 按映射调整 |
| U12 | `MouseHandler#onScroll(long, double, double)` 是全局滚轮入口；`Screen` 不保证覆盖 `mouseScrolled` | Tooltip 滚动 | 2026-10-26 真实客户端已核实（ContainerScreen 上滚轮进入混入） | 只改混入 |
| U13 | `BossHealthOverlay` 的私有 `drawBar` 调用可被 `@WrapOperation` 包裹 | Boss 面板 | 2026-10-26 真实客户端已用 `@WrapOperation` 命中 `render` 内的 4 参数调用 | HEAD 取消并自行渲染 |
| U14 | `InventoryScreen` 在 1.21.11 的父类与构造 | 背包面板 | 编译 | 自定义 `AbstractContainerScreen<InventoryMenu>` |
| U15 | 客户端打开背包一定经过 `Minecraft#setScreen` | 背包拦截 | 运行 | 仅在打开时应用，不拦截按键 |
| U16 | 原版 Tooltip 的延迟绘制时序可被安全替换 | Tooltip 面板 | spike + 视觉 | 只改样式，不接管 |
| U17 | 资源包字体在 1.21.11 的 provider 行为 | 字体 | 运行 | 只用内置位图字体 |
| U18 | `AbstractRecipeBookScreen` 的构造与状态 | 配方书 | 编译 | v1 隐藏配方书 |
| U19 | 带哨兵的 Paper BossBar 标题对模组客户端可见 | Boss 匹配 | 2026-10-26 真实客户端已观察到 `\u0000CCB:probe\u0000` | 自绘 Boss 血条（v2） |
| U20 | Fabric API 0.141.3 能重映射到 officialMojangMappings | 构建 | M0 构建 | 固定 Loom 版本/升级 API |
| U21 | PlaceholderAPI 扩展在主线程解析安全，异步仅限白名单 | 占位符刷新 | 阅读扩展 + 压力测试 | 全部同步，取消异步 |
| U22 | 客户端 JRE 的 `ImageIO` 可解码 GIF/JPEG，且内存可控 | 直连模式 GIF | 启动测试 + 解码样例 | 内置纯 Java 解码器，或强制 proxy 模式 |
| U23 | 客户端直连能在 DNS/重定向层可靠阻断私网与回环 | 安全 | 安全测试用例 | 只允许预置 CDN 域名，或强制 proxy |
| U24 | Tooltip 滚动条拖动不与滚轮/其他模组冲突 | Tooltip 交互 | 互操作测试 | 只保留滚轮，关闭拖动 |
| U25 | Minecraft 客户端允许 `java.net.http.HttpClient` 异步下载且不触发线程/类加载问题 | 直连模式 | 最小客户端 spike | 使用独立低优先级线程池 |
| U26 | `EditBox` 的光标/选区颜色可被安全覆盖，且不影响 IME | 输入框换皮 | 编译 + IME 测试 | 只换外框，光标/选区保持原版 |
| U27 | `renderItemDecorations(Font, ...)` 可用于自定义数量字体 | 物品显示 | 编译 + 视觉 | 数量字体保持原版 |
| U28 | `lwjgl-nanovg` 版本与 1.21.11 自带 LWJGL 对齐，三平台原生库可加载 | NanoVG 默认 | Windows x64 已验证；Linux/macOS 待测 | 回退 `NativeBackend` |
| U29 | FBO 颜色纹理可包装成 `AbstractTexture` 并通过 `GuiGraphics.blit` 合成 | 渲染链路 | 2026-10-27 Windows x64 实测：`nvg` FBO 红色矩形经 `GUI_TEXTURED` blit 上屏 | 其余平台待测 |
| U30 | NanoVG 预乘 alpha 与 MC GUI 混合函数匹配 | 半透明边缘 | 2026-10-27 改用 `GUI_TEXTURED_PREMULTIPLIED_ALPHA` 合成，面板/文字透明度正常 | 其余平台与缩放待测 |
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
- **U2 已核实**：`InventoryOpenEvent` 中发送的 PING 先到达客户端，随后才出现
  `Screen changed: net.minecraft.client.gui.screens.inventory.ContainerScreen`；容器预绑定顺序成立。
- **Minecraft#setScreen 混入已核实**：客户端日志实际打印 `ContainerScreen` 切换，未发生 mixin 错误。
- **U12 已核实**：`MouseHandler#onScroll(long,double,double)` 混入在 `ContainerScreen` 上收到
  `Scroll observed ... y=1.0`；全局滚轮入口成立，Tooltip 应在这里消费滚轮而不是只依赖 `Screen`。
- **U13 / U19 已核实**：`BossHealthOverlayMixin` 用 `@WrapOperation` 命中 `render` 内对私有
  `drawBar(GuiGraphics,int,int,BossEvent)` 的调用；Paper `BossBar` 的 `\u0000CCB:probe\u0000`
  标题在客户端实际可见并被日志捕获。
- **U3 已核实**：`GuiGraphicsMixin` 注入 1.21.11 唯一的
  `renderTooltip(Font, List<ClientTooltipComponent>, int, int, ClientTooltipPositioner, Identifier)`；
  悬停石头时客户端实际打印 `Tooltip observed lines=1`。
- **客户端文件日志已核实**：log4j2 appender 写入 `logs/cloudcore-client.log`，只挂载到
  `cloudcore` logger，不掠夺 Minecraft/Fabric 日志；10 MiB × 5 轮转已配置。
- **服务端主动关闭已核实**：`/cloudcore close` 发送 `UI_CLOSE`，客户端关闭 `CloudScreen`
  并记录 `UI_CLOSE ui=cloudcore_demo reason=0`；服务端日志显示对应 S2C 帧。
- **DIAGNOSTIC 链路已核实**：`/cloudcore diagnostic` 下发 `CC-PROBE-001`，客户端文件日志写入
  `ERROR [cloudcore] [CC-PROBE-001] scope=probe trace=...`。
- **HUD 面板已核实**：`/cloudcore demo-hud` 通过 Fabric `HudElementRegistry` 注册的 HUD 元素
  实际显示 panel/label；`UI_CLOSE` 后清除文档，HUD 消失。当前使用原生 RenderList，不支持 HUD 输入。
- **Tooltip 面板已核实**：`/cloudcore demo-tooltip` 后悬停石头，`TooltipSurfaceAdapter` 用文档
  背景色绘制自定义提示框，原生文本仍由 `ClientTooltipComponent` 输出；滚动/九宫格尚未实现。
- **BossBar 面板已核实**：`/cloudcore boss` 发送 UI_OPEN + 文档后，`BossBarSurfaceAdapter` 用
  `BossEvent` 进度/颜色绘制自定义条，`@WrapOperation` 跳过原版 `drawBar`；`@Redirect` 去除哨兵，
  标题实际显示为 `Cloud Core Boss`。九宫格/样式数据化尚未实现。
- **Container 面板已核实**：`/cloudcore demo-container` 先发 UI_OPEN/文档，再打开 27 格箱子；
  `Minecraft#setScreen` 的 `@ModifyVariable` 替换为 `CloudContainerScreen`，自定义背景下仍显示
  3 行容器槽位与玩家背包槽位。
- **容器点击策略已核实**：文档声明 `inventory_grid.slots=[0,8]`，服务端从同一份文档解析出
  已声明槽位。`managed` 模式下点击 0 号槽取出钻石、点击 13 号槽被取消并写
  `CANCELLED click ... slot=13`；`/cloudcore demo-readonly` 的 `readonly` 模式点击 0 号槽同样被取消。
  另测 `/cloudcore demo-passthrough`：点 0 号槽直接取走物品、无 `CANCELLED` 日志。
  未验证：`allow_player_inventory: true`、拖拽（`InventoryDragEvent`）真机。
- **PlayerInventory 面板已核实**：`/cloudcore demo-inventory` 下发持久皮肤后按 `E`，
  `Minecraft#setScreen` 的 `@ModifyVariable` 把原版 `InventoryScreen` 换成
  `CloudInventoryScreen`（`AbstractContainerScreen<InventoryMenu>`，复用 `player.inventoryMenu`）。
  截图确认 176×166 皮肤面板、文档底色、槽框、护甲/副手空槽图标与文档文字同时显示；
  关闭再打开仍是皮肤（`PERSISTENT`），`/cloudcore close` 后再按 `E` 回到原版带配方书的背包。
  配方书与玩家模型在皮肤里不绘制（v1 `hide`）。截图：
  `inventory-print6.png`、`inventory-reopen.png`、`inventory-closed.png`。
- **本地贴图已核实**：`LocalTextureRegistry` 从
  `resourcepacks/CloudCore/test/red.png` 读取 PNG、注册 `DynamicTexture`，demo 页面实际显示图片；
  路径逃逸/缺失分别记录 `CC-ASSET-001/002`。改用 `GUI_TEXTURED` + 显式贴图尺寸的 blit 后可见。
- **贴图来源已核实**：`/cloudcore demo-assets` 同时显示
  `item_icon`（钻石带数量、2 倍翡翠、下界合金剑）、`pack:minecraft:block/diamond_block`、
  `builtin:error`（洋红/黑棋盘）；缺失的 `pack:cloudcore:missing/texture` 不绘制并写
  `[CC-ASSET-006]`。截图：`assets-demo5.png`。
- **1 MiB 真机分片已核实**：`/cloudcore demo-huge` 生成约 830 KB 文档（620,000 随机字节的 base64），
  压缩后拆成 27 个分片（每片 24,018 字节，上限 32,767），客户端重组后打开页面并正常渲染。
- **断线清理已核实**：`JOIN`/`DISCONNECT` 都调用 `resetSessionState`，日志
  `Cleared session state on join|disconnect`；面板文档、分片组与 pending 会话全部清空，
  重连后不会残留上一个服务器的 HUD/皮肤。规则有 `SurfaceRegistryTest`、`PlayerInventorySessionTest`。
- **U28/U29 已核实（Windows x64）**：`NanoVgSpike` 加载 `lwjgl-nanovg:3.3.3`，
  创建 GL3 context 与 64×64 FBO，绘制红色矩形并 `glReadPixels` 得到 `(255,0,0,255)`；
  客户端 jar 已合并 NanoVG 类与 Windows/Linux/macOS 原生库（1.1 MiB）。
  FBO 纹理包装成 `AbstractTexture` 后用 `GUI_TEXTURED` blit 成功上屏
  （`-PcloudcoreNanoVgSpike=true`，截图 `nanovg-composite3.png` 左下红块）。
- **页面热重载已核实**：启动日志 `Watching plugins\CloudCore\pages`；打开示例页后直接改
  `example.yml` 的标题，约 1 秒内服务端 `Reloaded 1 pages, refreshed 1 open views`，界面标题
  变为“示例页面 自动重载”（`watch-reload.png`），无需任何命令。
- **Tooltip 滚动已核实**：把示例 Tooltip 的 `max_height` 设为 12 后，悬停物品的自定义提示框
  被裁剪成 12px（日志 `scrollable=true`），右侧出现滚动条；滚轮一格后文本整体上移
  （`tooltip-small1.png`、`tooltip-small2.png`）。正常文档用默认 160 高度不受影响。
- **事件限流已核实**：对示例页“+1”按钮连发 120 次点击后，服务端记录 14 条
  `Rate limited EVENT`，其余正常执行；`EventRateLimiterTest` 覆盖突发、补充速率、按玩家隔离与空闲上限。
- **GIF 动画已核实**：`local:test/anim.gif`（3 帧、400 ms/帧）在 `/cloudcore demo-assets` 中
  连续截图显示颜色从绿变蓝，日志 `Decoded GIF ... frames=3 total=1200ms`；
  `AnimatedTexturesTest` 覆盖 GIF 识别与标识符净化（本地路径含中文/冒号也不会生成非法 Identifier）。
  远端 GIF 走同一解码路径但未真机验证。`animated: off` 已用同一张图两次截图做像素比较
  （GIF 区域零差异），`loop`/`speed` 只有解析层单测。
- **悬停样式已核实**：示例页的“关闭”按钮在光标移入后由 `#9F3C6E` 变为 `style.hover` 的
  `#D05C8E`（`hover.png`）；`DocumentRenderListBuilderTest` 覆盖悬停背景选择。
- **command 动作闸门已核实**：默认配置下点“喊话”写
  `Blocked command action ...` 并回 `REJECTED`；把 `actions.commands.enabled` 设为 true、
  allowlist 加 `say` 后同一按钮执行 `say ...`（日志 `Ran action command ...`）。
  `CommandAllowlistTest` 覆盖默认拒绝、精确匹配与非法输入。
- **背景贴图与富文本已核实**：`/cloudcore demo-assets` 的 `panel` 用
  `style.background: pack:...` + `style.nine_slice` 画出与 `image` 九宫格一致的边框；
  富文本标签“Rich text runs”按段着色（`panel-texture.png`、`rich-text.png`）。
  字重/斜体等样式尚未映射。
- **HUD 层与锚点已核实**：`/cloudcore demo-hud` 的 `anchor: top_center` + `offset_y: 20`
  把面板放到顶部居中；`/cloudcore demo-hud-bg` 的背景层红条与快捷栏重叠，快捷栏绘制在它上面
  （`hud-layers2.png`），证明 `addFirst` 的背景层确实排在原版 HUD 之前。
  `HudSurfaceAdapterTest` 覆盖锚点计算。
- **validate / reload 已核实**：`/cloudcore validate` 输出 `[OK] example`；编辑 `pages/example.yml`
  标题后 `/cloudcore reload` 输出 `Reloaded 1 pages, refreshed 1 open views`，在线玩家页面
  自动更新为“示例页面 v2”且 URL 贴图仍在（`reload-page.png`）。
- **URL 贴图直连已核实**：示例页的 `url:https://httpbin.org/image/png` 被编译成
  `inline:8362b9a8b653ca04`，服务端发 `TEXTURE_REF`，客户端后台线程下载 8090 字节、渲染线程注册
  贴图，界面在页面已打开的情况下自动重建并显示图片（`url-image.png`）。非 https 在协议解码
  与插件发送前都会被拒绝；sha256 校验与失败日志有单元测试/代码路径，但未做失败真机注入。
- **状态更新链路已核实**：示例页点 `+1` 后服务端日志
  `Updated page 'example' revision=2 state={clicks=1}`，客户端
  `Updating ui=example revision=2` 并重新打开页面；会话状态按玩家保存，重开页面沿用。
- **事件链路已核实**：示例页点击 `repeat` 行后服务端日志
  `EVENT ui=example element=rows[0].row`，聊天收到编译期替换后的
  `选择了 铁剑（120 金）`；点“关闭”按钮触发 `close_ui`，服务端发 `UI_CLOSE`，界面关闭
  （`action-click-row.png`、`action-message.png`）。`EventCodecTest` 覆盖编解码与非法载荷。
- **YAML 页面链路已核实**：`/cloudcore page example <玩家>` 从 `plugins/CloudCore/pages/example.yml`
  加载（含 `state`、`repeat`、`${...}` 模板与九宫格图片），3 条 `repeat` 实例按高度+gap 堆叠显示，
  中文文本正常（`page-example3.png`）。`PageLoaderTest` 覆盖合并顺序、片段禁用字段、循环引用、
  repeat 源类型；`UiDocumentJsonTest` 覆盖 JSON 往返。
- **未知 BindMode/RenderMode 已核实（解码）**：`UiCodecTest` 把 `UI_OPEN` 的绑定/渲染模式
  字节改成未定义值后仍能解码为 `UNKNOWN`，不再抛 `ProtocolException`；客户端的
  `OpenRequest#hasUnknownMode` 有单元测试，命中时回 `UI_READY(state=4)`。
- **九宫格已核实**：`/cloudcore demo-assets` 的 `nine_slice: [4,4,4,4]` 图片按
  `NineSlice.split` 拆成 9 块，角保持 4px、边单向拉伸、中心双向拉伸；同一页的 32×32
  钻石块整图缩放也正常（`nine-slice2.png`）。同时修正了 blit 重载：放大贴图必须用 12 参重载，
  10 参会按 1:1 采样导致平铺。
- **滚轮滚动已核实**：`/cloudcore demo-scroll` 中把光标移到滚动框上滚动 4 格，
  内容从 `scroll_y=26` 继续上移到上限，编辑框下列被裁剪（`scroll-wheel.png`）。
  `ScrollTargets` 的命中与钳制有单元测试；事件回传服务端未实现。
- **槽位布局已核实**：`/cloudcore demo-container` 的文档声明三个 `inventory_grid`（0–8、9–17、18–26，
  `origin` 右移 16px），打开箱子后 27 个容器槽位与其中物品一起移动，玩家背包槽位保持原版；
  点击移动后的 0 号槽能取出钻石（`chest-layout.png`、`chest-layout-click2.png`）。
  玩家背包皮肤可按 `inventory_grid` 重排主物品栏 9–35 与快捷栏 36–44（`inventory-grid2.png`），
  护甲/合成/结果/副手槽保持原版。
- **滚动裁剪已核实**：`/cloudcore demo-scroll` 的 `scroll` 元素按 `scroll_y: 26` 偏移并把
  子元素裁剪到 200×80 的框内；原生后端（`scroll-native.png`）与 NanoVG 后端
  （`scroll-nanovg.png`）显示一致。嵌套裁剪由 `ClipPush`/`ClipPop` 的交集实现。
  本地滚轮滚动尚未接入。
- **NanoVG 字体已核实（Windows x64，opt-in）**：`FontRegistry` 从
  `resourcepacks/CloudCore/公共/字体/body.ttf` 读取 TTF，`nvgCreateFontMem` 后
  `/cloudcore demo-assets` 的 `font: body` 标签由 NanoVG 绘制，未声明字体的文本仍由原版绘制。
  FBO 按逻辑分辨率分配，自定义字体在 GUI scale 2 下略软；设备分辨率 FBO 的清晰化仍待做
  （第一次尝试因坐标空间不一致回退）。截图：`nanovg-font1.png`。
- **NanoVG 生产后端已核实（Windows x64，opt-in）**：`-PcloudcoreNanoVg=true` 时
  `NanoVgBackend` 创建 NVGContext，按逻辑分辨率建 FBO（427×240），画填充与
  `local:`/`pack:` 图片，然后合成；文本与 `item_icon` 走原生叠层。
  `/cloudcore demo-assets` 截图 `nanovg-backend3.png`：面板、钻石块贴图与本地 PNG 位置正确、
  方向正确；物品与文字由原版绘制。默认仍走 `NativeBackend`，Linux/macOS 未测。
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
- `./gradlew build` 通过（JDK 21，147 个测试 / 32 个测试类，0 失败）。

仍未验证：

- NanoVG：填充/图片/合成已在 Windows 验证（opt-in）；文字仍走原版 `Font`，未接 NanoVG 字体；
  U30 预乘 alpha、U31 状态隔离、Linux/macOS 未测；默认后端仍是 `NativeBackend`。
- U28–U31：Linux/macOS、FBO 屏幕合成、预乘 alpha、1.21.6 提取/绘制状态。
- `inline:`/`pack:` 图片、GIF、item_icon、文本整形、裁剪/滚动、真实布局容器、其余五类面板的实际替换。
- 当前 screen/scroll/tooltip/boss 混入只是诊断探针，尚未替换绘制或输入。
- `BindMode` / `RenderMode` 的未知值仍会拒绝整帧；`surfaceId` 已改为保留原始 id 并回
  `UI_READY(state=4)`。

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
