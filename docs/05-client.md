# 05 — 客户端实现（Fabric，Mojang 映射）

## 1. 模组元数据

```json
{
  "schemaVersion": 1,
  "id": "cloudcore",
  "version": "${version}",
  "name": "Cloud Core",
  "environment": "client",
  "entrypoints": {
    "client": ["com.cloudcore.client.CloudCoreClient"]
  },
  "mixins": ["cloudcore.mixins.json"],
  "depends": {
    "fabricloader": ">=0.19.5",
    "fabric-api": "*",
    "minecraft": "~1.21.11",
    "java": ">=21"
  }
}
```

构建使用 `loom.officialMojangMappings()`，不配置 `yarn_mappings`。混入兼容级别 `JAVA_21`。
`cloudcore.mixins.json` 只声明客户端混入。

渲染依赖：`org.lwjgl:lwjgl-nanovg` 及 Windows/Linux/macOS 原生库，随模组打包。LWJGL 版本必须与
Minecraft 自带版本对齐，避免重复类/原生库冲突；加载失败时回退 `NativeBackend`。

## 2. 初始化顺序

`CloudCoreClient.onInitializeClient()`：

1. 读取配置（`config/cloudcore-client.json`）。
2. 在 `PayloadTypeRegistry.playS2C()` 和 `playC2S()` 中注册 payload 类型（0.141.3 的 API 方法名
   仍是 Yarn 风格，Loom 不会重命名 Fabric 自己的方法）。
3. 注册全局接收器。
4. 注册 `NanoVgBackend` 的惰性初始化：首次绘制时在渲染线程创建 NVGContext、FBO、`FontRegistry`；
   失败则标记 `NativeBackend` 为唯一可用后端。
5. 注册 `SurfaceRegistry` 的六个适配器。
6. 注册 HUD 元素（如果 HUD 启用）。
7. 注册 `ClientTickEvents.END_CLIENT_TICK`，处理超时、绑定过期、贴图队列排空。
8. 注册 `ClientPlayConnectionEvents.DISCONNECT`，清空全部状态。

`HELLO` 在 `ClientPlayConnectionEvents.JOIN` 之后延迟一 tick 发送。

## 3. Payload 与传输

```java
public record CloudFramePayload(byte[] data) implements CustomPacketPayload {
    public static final Type<CloudFramePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("cloudcore", "main"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloudFramePayload> CODEC =
            StreamCodec.of(
                (buf, value) -> buf.writeBytes(value.data()),
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new CloudFramePayload(data);
                });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
```

注册：

```java
PayloadTypeRegistry.playS2C().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);
PayloadTypeRegistry.playC2S().register(CloudFramePayload.TYPE, CloudFramePayload.CODEC);

ClientPlayNetworking.registerGlobalReceiver(CloudFramePayload.TYPE,
        (payload, context) -> transport.receive(payload, context));
```

注意：

- Mojang 映射中编码器参数顺序是 `(buf, value)`，与 Yarn 的 `PacketCodec` 相反。
- `playC2S` 注册是 `ClientPlayNetworking.canSend` 需要的。
- `StreamCodec` 与 `RegistryFriendlyByteBuf` 的签名以 1.21.11 实际映射为准。

`ClientTransport`：

- `receive` 在网络线程只做信封解析、分片重组、入队。
- 任何状态变更通过 `Minecraft.getInstance().execute(...)` 投递。
- 解析失败只计数并丢弃，不抛异常。
- 客户端不作出站限流；只对 `change` 事件防抖。

### 3.1 会话表

```java
final class ClientSession {
    final String uiId;
    int revision;
    int bindToken;
    UiDocument document;      // 字段见 16-ui-document-schema.md
    LayoutTree layout;
    BindMode bindMode;
    RenderMode renderMode;
    long openedAtMs;
}
```

- `uiId` 在单条连接内唯一；同一 id 的第二次 `UI_OPEN` 替换会话。
- 未知 `uiId` 的 `UI_DEFINITION` 进入 `pendingDefinitions`（最多 8 个，LRU，60 秒），不直接丢弃。
- 文档 `revision` 必须严格大于缓存值；`ELEMENT_UPDATE` 只接受 `current + 1`，跳号（gap）或更旧
  （stale）都丢弃并等待下一次完整 `UI_DEFINITION`，不会把半套属性应用到界面。
- 同一 `uiId` 收到更高 revision 的 `UI_DEFINITION` 时按“更新”处理：重新解析、校验并
  `SurfaceAdapter.open`（`CloudScreen` 会换成新文档），不再发 `UI_READY` 失败。
- `ELEMENT_UPDATE` 先由 `DocumentUpdates` 生成新文档（未改动的元素和属性 map 共享），再交给
  `SurfaceAdapter.update`：自定义页面走 `CloudScreen.updateDocument`（保留输入框控件状态、重置
  本地滚动增量、重建渲染列表），HUD 直接替换层文档。未知元素/属性或校验失败整包拒绝。
- 点击带 `click` 事件的元素时发 `EVENT`（actionId 来自文档）；未命中的点击交给 `Screen` 默认处理。
  点击载荷带 `button` 与 `shift`，滑块带 `amount`（点击横坐标在轨道内的比例，`0..1`），
  复选框带 `checked`（取反后的新值）。
- `hover_enter` / `hover_leave` 只在光标跨过元素边界时各上报一次，且只对声明了事件的元素上报
  （没有悬停处理器的页面不会产生任何流量）。实现用 `ElementHitTester` 找悬停目标，
  `CloudScreen#updateHoverEvents` 比较前后目标：先给旧元素发 `hover_leave`，再给新元素发
  `hover_enter`，载荷为空。
- `scroll` 事件：滚轮改变 `scroll` 元素的偏移后上报**绝对**偏移（文档像素，`scroll` 键）。
  渲染时滚动量 = 文档属性 `scroll_y` + 本屏滚轮增量；`scroll_y` 通常由服务端状态驱动，
  所以新 revision 到达时本地增量归零、从服务端保存的偏移继续。命中测试用同一个合成值，
  滚动后点击仍落在正确的行上。未声明 `scroll` 事件的滚动元素只做本地滚动，不发包。
- 键盘导航：`Tab` / `Shift+Tab`（以及方向键）在声明了 `click` 的 `button`、`icon_button` 之间
  按文档顺序循环焦点，`Enter` / `Space` 触发该元素的 `click`（载荷与鼠标点击一致）。
  焦点框每帧绘制：内圈取 `style.border.focused`（默认 `#FF4FA3E8`），外圈固定深色，
  深浅背景都看得见。焦点是客户端状态，不经过文档；文本输入框获得焦点时 `Tab` 交给原版控件链。
  焦点框用绝对坐标计算（与 `LayoutBoxes` 一致），滚动容器内的按钮会按未滚动位置绘制。
- `text_field` 用原版 `EditBox` 的子类 `CloudEditBox`：`CloudScreen.init` 按 `LayoutBoxes` 计算位置并
  `addRenderableWidget`，`render` 里自行绘制控件；文档只画背景、边框和清除按钮。输入法/选择/剪贴板
  沿用原版，`change` 事件带 `value`，ENTER 触发 `submit`（按控件身份匹配，带 `padding` 也生效）。
- 断线清空全部会话，并释放 NanoVG image handle / 原生纹理。
  实现：`ClientPlayConnectionEvents.JOIN/DISCONNECT` 调用 `resetSessionState`，清空重组器、
  `pendingOpens`、`pendingDefinitions`，并 `SurfaceRegistry#reset(DISCONNECT)` 让每个面板丢弃文档
  （HUD/Tooltip/BossBar/Container/PlayerInventory 的静态文档与绑定，自定义页面关闭）。
  日志写 `Cleared session state on join|disconnect`。
- 文档不包含 `state`、动作体、`repeat` 或原始 URL。

## 4. 面板注册表

```java
public final class SurfaceRegistry {
    private final Map<SurfaceType, SurfaceAdapter> adapters = new EnumMap<>(SurfaceType.class);

    public void register(SurfaceAdapter adapter) { adapters.put(adapter.type(), adapter); }
    public SurfaceAdapter get(SurfaceType type) { return adapters.get(type); }
}
```

六个适配器：

| 适配器 | 目标 | 钩子 |
| --- | --- | --- |
| `CustomScreenAdapter` | 独立页面 | `Minecraft#setScreen` |
| `ContainerSurfaceAdapter` | 箱子类容器 | `Minecraft#setScreen` + `AbstractContainerScreen` |
| `PlayerInventoryAdapter` | 玩家背包 | `Minecraft#setScreen`（识别 `InventoryScreen`） |
| `HudSurfaceAdapter` | HUD | `HudElementRegistry` |
| `TooltipSurfaceAdapter` | 物品提示 | `GuiGraphics#renderTooltip` + `MouseHandler#onScroll` |
| `BossBarSurfaceAdapter` | Boss 血条 | `BossHealthOverlay#render` |

新增面板 = 新增适配器 + 注册 + 协议常量 + 特性位。

## 5. 混入清单

| 混入 | 目标 | 目的 |
| --- | --- | --- |
| `MinecraftMixin` | `Minecraft#setScreen` | 用 `@ModifyVariable` 替换界面参数 |
| `GuiGraphicsMixin` | `GuiGraphics#renderTooltip(...)` | 捕获并替换物品提示渲染 |
| `MouseHandlerMixin` | `MouseHandler#onScroll(long,double,double)` | Tooltip 可滚动时消费滚轮 |
| `BossHealthOverlayMixin` | `BossHealthOverlay#render` | 接管已绑定的 Boss 血条 |

混入只负责生命周期和捕获，不负责绘制；绘制统一走 `RenderBackend`。混入都设置明确的优先级，
并且只在对应面板启用时生效。1.21.11 的全局滚轮入口是
`MouseHandler#onScroll(long, double, double)`；`Screen` 不保证覆盖 `mouseScrolled`。
`GuiGraphics` 的 tooltip 入口是
`renderTooltip(Font, List<ClientTooltipComponent>, int, int, ClientTooltipPositioner, Identifier)`。
两者已核对（见 `10-risks-and-verification.md`），滚轮混入已在真实容器界面验证。

## 6. 界面替换

```java
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen cloudcore$replaceScreen(Screen screen) {
        if (!CloudCoreClient.isReady()) return screen;
        return ScreenReplacer.replace(screen);
    }
}
```

`ScreenReplacer.replace(screen)`：

```java
static Screen replace(Screen screen) {
    Minecraft minecraft = Minecraft.getInstance();
    SurfaceRegistry registry = CloudCoreClient.surfaces();

    if (screen instanceof InventoryScreen inventory) {
        return registry.get(SurfaceType.PLAYER_INVENTORY)
                .canHandle(OpenRequest.of(SurfaceType.PLAYER_INVENTORY), minecraft)
                ? new CloudInventoryScreen(inventory) : screen;
    }
    if (screen instanceof AbstractContainerScreen<?> container) {
        BindToken bind = BindQueue.consume();
        if (bind == null) return screen;
        return new CloudContainerScreen(container, bind.session());
    }
    return screen;
}
```

选择 `@ModifyVariable` 而不是 `@Inject(cancellable = true)`：它在原版初始化之前改写参数，避免重入和
一帧的原版界面闪烁。构造失败时返回原界面，发送 `CLIENT_ERROR`，并禁用该面板。

## 7. 容器界面

```java
public final class CloudContainerScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private final ClientSession session;
    private final LayoutTree layout;
}
```

构造时复制原界面的 `menu`、`title`、`imageWidth`、`imageHeight`、`playerInventory`。
`init()` 中设置 `leftPos`/`topPos`，调用 `super.init()`，再用 `SlotLayout.apply` 覆盖 `slot.x`/`slot.y`。
未声明的槽位移到 `(-10000, -10000)`，原版命中测试自然跳过。

渲染分层：

| 钩子 | 内容 |
| --- | --- |
| `renderBg` | NanoVG 背景层：文档背景与 `z < 100` 的元素，渲染到 FBO 后合成 |
| 原版槽位通道 | 物品、手持物品、槽位高亮、数量/冷却/附魔光效 |
| `render` 后段 | NanoVG 前景层：`z >= 100` 的元素、提示、焦点框 |

不重新实现物品渲染，原版已经处理数量文字、冷却、附魔光效。槽位命中仍按逻辑坐标映射。

## 8. 玩家背包界面

```java
public final class CloudInventoryScreen extends AbstractContainerScreen<InventoryMenu> {
    CloudInventoryScreen(Player player, UiDocument document) {
        super(player.inventoryMenu, player.getInventory(), Component.empty());
        this.imageWidth = document.width();   // 皮肤画布 = 文档尺寸
        this.imageHeight = document.height();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 文档自带文字；原版容器标题隐藏
    }
}
```

要点：

- `InventoryScreen` 在 1.21.11 中继承 `AbstractRecipeBookScreen<InventoryMenu>`（已用 javap 确认）。
  v1 皮肤直接继承 `AbstractContainerScreen<InventoryMenu>`，复用 `player.inventoryMenu`
  （容器 0），因此点击仍发送原版槽位包；配方书不注册，即 `recipe_book: hide`。
- `recipe_book` 其余三档（`vanilla`/`skinned`/`custom`）留在 v2：需要 `AbstractRecipeBookScreen`
  或复制其组件状态。
- 玩家模型、状态效果图标在 v1 不绘制；`show_player_model`/`show_effects` 尚未解析。
- 槽位来自 `InventoryMenu`：合成输出 0、合成输入 1–4、护甲 5–8、主物品栏 9–35、快捷栏 36–44、
  副手 45。`renderBg` 按 `menu.slots` 的 `slot.x/y` 画槽框，文档元素画在槽框之上。
- 服务端不发送打开报文；客户端本地按 `E` 打开时应用持久皮肤（`UI_OPEN` + `UI_DEFINITION` 先到）。
- 皮肤绑定状态在 `PlayerInventorySession`：`PERSISTENT` 持续到 `UI_CLOSE`，其他绑定只用一次；
  规则有单元测试 `PlayerInventorySessionTest`。
- 创造模式物品栏 `CreativeModeInventoryScreen` 不在 v1 范围。
- 服务端没有下发皮肤时，按容器标题查客户端本地面板（`match` 正则），见第 19 节。

## 9. HUD

```java
HudElementRegistry.addLast(
        Identifier.fromNamespaceAndPath("cloudcore", "hud"),
        (guiGraphics, deltaTracker) -> hudController.render(guiGraphics, deltaTracker));
```

- HUD 只读：没有点击、槽位、输入。
- 文档不允许声明 `inventory_grid`。
- 整层由 NanoVG 绘制到 FBO 后合成；没有原版物品时不拆前景/背景层。
- HUD 中的物品图标由 `NativeBridge` 绘制，夹在背景层与前景层之间。
- 层由 `UI_OPEN.surfaceKey` 决定，映射到 `VanillaHudElements` 锚点。
- `HudRenderCallback` 从 Fabric API 0.116 起废弃，不使用。

## 10. Tooltip 与滚轮

### 10.1 捕获

`TooltipMixin` 在物品提示的最终渲染重载上 `@Inject(at = HEAD, cancellable = true)`，拿到
`List<FormattedCharSequence>`（以及物品栈），交给 `TooltipSurfaceAdapter`。

- 只有启用了 Tooltip 面板、且是物品提示时才接管。
- 其他模组通过 `ItemTooltipCallback` 追加的行仍然在列表中。
- 如果另一个模组也取消了该重载，按混入优先级决定谁生效；无法接管时回退原版并记录一次。

### 10.2 布局与滚动

- 外框、背景、滚动条由 NanoVG 绘制到前景层；文本内容由原版绘制在上层。
- 面板内边距、边框、颜色、最大高度来自文档；文本行高由原版 `Font.lineHeight` 加 `line_gap` 决定。
- 可见高度 = `min(内容高度, surface_options.tooltip.max_height, 屏幕可用高度)`。
- 内容超过可见高度时进入滚动模式：
  - 固定提示框位置，避免滚动时跟着鼠标跳动；
  - 右侧绘制滚动条；
  - 滚轮按 `scroll_step` 调整偏移，可选 100 ms 平滑插值；
  - 偏移夹在 `[0, 内容高度 - 可见高度]`。
- 悬停物品变化时重置偏移。判断依据是物品 id、数量与数据组件；具体哈希方法在实现时确认。
- 内容未超高时，滚轮**不**被消费，保持原版行为（例如快捷栏切换）。

### 10.3 滚轮路由

```java
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cloudcore$scrollTooltip(long window, double xOffset, double yOffset,
                                         CallbackInfo ci) {
        if (TooltipSurfaceAdapter.consumeScroll(yOffset)) {
            ci.cancel();
        }
    }
}
```

- 只有 Tooltip 处于滚动模式且可见时才消费。
- 不改变聊天、快捷栏、其他界面的滚轮行为。
- 1.21.11 的全局入口是 `MouseHandler#onScroll(long, double, double)`；真实容器界面已验证
  混入能收到滚轮。`Screen#mouseScrolled` 可能被子类覆盖，不作为唯一钩子。
- 滚动条样式来自文档 `surface_options.tooltip.scrollbar`：轨道、滑块、悬停态贴图、九宫格、宽度、
  间距、最小滑块高度、颜色、`draggable`。
- 滚动条绘制在提示框内并裁剪到可见区域；`draggable: true` 时支持拖动滑块，和滚轮共享同一个偏移。
- 拖动只在提示可见时生效，不改变原版其他界面的鼠标行为。

## 11. Boss 血条

`BossHealthOverlayMixin` 在 `BossHealthOverlay#render(GuiGraphics)` 上工作：

- 遍历 `BossEvent` 时，标题匹配哨兵的条目交给 `BossBarSurfaceAdapter` 绘制。
- 其余条目保持原版绘制。
- 实现方式优先 `@WrapOperation` 包裹私有的 `drawBar` 调用；如果签名变化，退化为
  `@Inject(at = HEAD, cancellable = true)` 并自行遍历渲染。
- 进度、颜色、样式来自 `BossEvent`；文档只负责外观。
- NanoVG 绘制外框、背景、填充、覆盖层；`surface_options.boss_bar.bar` 控制九宫格、填充方向、
  文字字体/颜色/阴影/对齐。`tint_from_event: true` 时用 `BossEvent` 的颜色给填充染色。
- 填充按 `BossEvent#getProgress()` 裁剪；标题剥离哨兵后绘制。标题默认走原版 `Font`，文档指定
  `title_renderer: nanovg` 时改走 NanoVG 字体。
- NanoVG 层先合成，原版标题后绘制，避免文字被 FBO 覆盖。
- 多个血条按 `surface_options.boss_bar.spacing` 堆叠，最多 `max_bars`。
- 客户端的 `BossHealthOverlay#events` 是私有字段，不直接访问；通过渲染钩子参数获取事件。
- 服务端没有下发文档时，按血条名称查客户端本地面板（`match` 正则），见第 19 节。
- 多层血条是同一根条：哨兵带每层的剩余强度 `\0CCB:<token>:<p1>,<p2>,...\0<名称>`，
  客户端由内向外逐层绘制，外层打空后露出内层；层数 >1 时条内右侧显示 `2/3` 计数。
  服务端没有哨兵（本地面板）时，最外层跟随 `BossEvent` 进度，内层保持满值。

## 12. 布局引擎

两遍：测量（文本宽度、图片尺寸、弹性子项、滚动范围）和排布（绝对位置、锚点、百分比、弹性分配、
裁剪矩形）。

- 按 `(修订号, 画布宽, 画布高, GUI 缩放)` 缓存。
- 失效：界面初始化/尺寸变化、改变几何的更新、主题变化、遍历实例数量变化。
- NanoVG 文本的测量和绘制使用同一个 NanoVG 字体句柄；原版文本（EditBox、Tooltip 内容）使用同一个
  原版 `Font`。两套度量不能混用。
- 布局是整数运算，动画才用浮点并在绘制时取整。
- 遍历实例在服务端展开，客户端只排布具体元素；生成的 id 用于命中与事件。

## 13. 渲染后端

### 13.1 结构

```java
interface RenderBackend {
    BackendId id();
    boolean available();
    void beginFrame(FrameContext context);
    void draw(RenderList list, Layer layer);
    void endFrame();
}
```

- `NanoVgBackend`（目标默认，当前需 `-PcloudcoreNanoVg=true` 开启）：`NVGContext` + 离屏 FBO。
  已实现填色与图片（`local:`/`pack:` 用 `nvgCreateImageMem`），
  `builtin:` 图片和 `item_icon` 交给原生叠层。文本策略：没有 `font` 的文本继续用原版 `Font`
  （清晰、与界面一致）；声明了 `fonts:` 且 `source: local` 的文本由 NanoVG 用 TTF 绘制
  （`FontRegistry`，默认回退 `公共/字体/body.ttf`）。
- `NativeBackend`（回退）：同一份 `RenderList` 用 `GuiGraphics` 绘制。
- `FboCompositor`：把 FBO 颜色纹理包装成 `AbstractTexture`，注册给 `TextureManager`，用
  `GuiGraphics#blit(GUI_TEXTURED, ..., textureWidth, textureHeight)` 合成；`begin/end` 配对恢复
  帧缓冲与视口。FBO 行 0 在底部，因此 NanoVG 场景先做垂直翻转再合成。
- `DocumentRenderer`：一次绘制 = NanoVG（填充/图片/自定义字体文本）→ 合成 → 原生叠层
  （默认字体文本、`item_icon`、`builtin:` 图片）；NanoVG 不可用或中途失败时整帧回退
  `NativeBackend`。合成使用 `GUI_TEXTURED_PREMULTIPLIED_ALPHA`（NanoVG 输出预乘 alpha）。
- `NativeBridge`：物品、槽位、数量/冷却/附魔光效、tooltip 内容、`EditBox` 文本。
- 后端选择：配置 → `NanoVgBackend.available()` → `NativeBackend`。

### 13.2 分层

| 层级 | 绘制者 | 内容 |
| --- | --- | --- |
| 背景层 | NanoVG | 面板、背景图、九宫格、装饰、静态文本（`z < 100`） |
| 内容层 | NativeBridge | 物品、槽位、数量/冷却/光效、EditBox 文本、玩家模型 |
| 前景层 | NanoVG | `z >= 100` 的元素、焦点框、Tooltip 外框、Boss 外框 |
| 原版层 | NativeBridge | Tooltip 文本、Boss 标题、原生 tooltip 组件 |

没有原版内容的界面合并成一个 NanoVG 层，减少 FBO 和采样。

### 13.3 FBO 与合成

- 逻辑分辨率乘整数倍（1x/2x），窗口或 GUI 缩放变化时重建。
- NanoVG 输出预乘 alpha；合成时使用匹配的混合函数，具体以 1.21.11 实测为准。
- 渲染在独立 pass 完成，保存/恢复 `RenderSystem` / `GlStateManager` 状态。
- 1.21.6+ 是提取/绘制两阶段：NanoVG 在界面 `render` 的早期、合成入队之前完成 FBO 渲染，
  `GuiGraphics.blit` 只负责入队合成；不要在提取阶段插入未隔离的 GL 调用。
- FBO 创建、resize、销毁只在渲染线程。
- 合成失败或 FBO 不可用时，当前帧回退 `NativeBackend`。

## 14. 输入路由

顺序：

1. `mouseClicked`：Tooltip 滚动条 → 模态覆盖层 → 文档元素（逆绘制顺序）→ 槽位（`super`）。
2. `mouseReleased`：结束按压/滚动条拖动，发送 `drag_end`。
3. `mouseScrolled`：Tooltip 滚动 → 最内层滚动容器 → 继续传递。
4. `keyPressed`：焦点输入框 → 界面快捷键（Escape、E）。
5. `charTyped`：焦点输入框。
6. `mouseMoved`：悬停、提示计时、光标样式。

槽位必须继续走原版 `super.mouseClicked`，包括 Shift 快速移动、数字键交换、拖拽分配和副手交换。
`SlotLayout` 设置的位置会自动影响原版命中测试。

- HUD/Boss 不参与输入；Tooltip 只参与滚轮和滚动条拖动。
- 只有当前 screen 是输入所有者；容器打开时背包皮肤关闭。
- 模态覆盖层捕获输入直到关闭；点击外部清除文本焦点。
- Escape 先清焦点，再关闭界面（若允许）。

### 14.1 自定义输入框外观

输入逻辑用原版 `EditBox`，`CloudEditBox extends EditBox` 只替换 `renderWidget`：

- 构造时传入原版 `Font`；调用 `setBordered(false)` 关闭原生边框。
- 背景、九宫格、边框、焦点态、错误态由 `DocumentRenderListBuilder` 画；文字、光标、选区由
  `CloudEditBox` 画。编辑行为（`charTyped`、IME、剪贴板、键盘重复）全部走 `super`，不拦截。
- `style.cursor_color` / `style.selection_color` 是 ARGB；未设置时分别用文字颜色和原版
  `GuiGraphics#textHighlight`。`selection_color` 设置了才改用普通 `fill`，避免覆盖原版高亮管线。
- `EditBox` 的 `displayPos`、`highlightPos`、`textX`、`textY` 是私有字段，通过缓存的
  `VarHandle` 读取；其余状态（可编辑、阴影、最大长度、formatter、hint、焦点时间）由覆写公开
  setter 自行记录。这样不新增混入；Minecraft 升级重命名字段会在类初始化时失败。
- `padding` 用外层布局补偿原版内建的文本偏移；控件与元素框不重合，焦点框仍按元素外框画。
- 占位符仍用原版 `EditBox#setHint`，颜色取 `style.placeholder_color`，未设置时跟随文字颜色。
- `invalid` 来自文档属性（服务端 `set_state` 后重发文档或 `ELEMENT_UPDATE`）；文本字段的属性
  变化会回退完整文档并重建界面，输入框控件自身状态不会被增量更新打断。
- 清除按钮：`style.clear_button` 是贴图引用，画在元素右侧；`CloudScreen` 按同一几何把控件收窄，
  点击优先命中按钮并 `setValue("")`，`change` 因此照常触发。贴图不可用时画 `x` 占位。
- `sensitive: true` 时显示掩码，但 `getValue()` 仍是原文；提交时按文档声明发送。
- NanoVG 后端：背景、外框由 NanoVG 画，`EditBox` 文字/光标/选区叠在上层。

## 15. 贴图与字体

- NanoVG 模式：PNG/GIF 字节在工作线程准备，渲染线程用 `nvgCreateImageMem` 创建 NVG image handle；
  LRU 按 handle 和内容哈希管理。
- `NativeBackend` 模式：`DynamicTexture` + `TextureManager` 上传，键空间 `cloudcore:dynamic/<key>`。
- FBO 合成使用 `FboTexture extends AbstractTexture`，由 `TextureManager` 注册。
- 静态贴图是 `format=1`；动画贴图是 `format=2` 的垂直精灵表，NanoVG 按帧绘制子矩形；
  原生回退时在同一个 `DynamicTexture` 内按帧偏移 UV。
- 动画在界面隐藏或 `animations: false` 时暂停；`loop: false` 播完停在最后一帧。
- URL 贴图默认客户端直连：收到 `TEXTURE_REF` 后按 SHA-256 查本地缓存，未命中再异步下载。
- 下载策略：仅 https；解析后拒绝私网、回环、链路本地和保留地址；禁止非标准端口；最多 2 次重定向；
  1 MiB、512×512、3 秒超时；不发送 cookie 或认证头。
- 按魔数识别格式；PNG 直接使用，JPEG/GIF 用 `ImageIO` 解码；GIF 逐帧合成后打包成精灵表。
- 客户端缓存：磁盘 128 MiB（内容哈希命名）、解码后内存 32 MiB；加载时校验哈希，损坏则丢弃。
- 同一 URL 的并发请求去重；下载失败发送 `TEXTURE_REQUEST` 请求服务端代理；仍失败显示红色
  “网链请求失败”占位，并写客户端日志（脱敏 URL、错误类型、耗时、`CC-URL-*`）。
- 加载中显示白色占位；`remoteImages: false` 显示灰色“远程图片已禁用”，与网络失败区分。
- 失败缓存 30 秒、最多重试 1 次，避免反复打图床；同一 URL 只完整记录一次日志。
- 服务端只发引用，图片字节不占游戏连接。
- 客户端不解析 PlaceholderAPI 占位符；它只接收服务端解析后的文本。
- 主题令牌 `tx:` 按 `THEME_SET` 解析；文档保留令牌引用，换主题不需要重发文档。
- 本地资源：`.minecraft/resourcepacks/CloudCore/`，通过 `local:` 引用，客户端直接读文件系统；
  可按业务分类，例如 `CloudCore/副本/...`、`CloudCore/签到/...`、`CloudCore/公共/...`。
  `F9` 重载，不要求玩家在资源包列表启用。
- 字体来自本地 `CloudCore` 目录或资源包；`FontRegistry` 读取 TTF/OTF 字节，在渲染线程用
  `nvgCreateFontMem` 创建 NanoVG 字体；客户端不从服务端接收字体文件。
- 字体查找顺序：文档 `fonts` 定义 → 主题令牌 → `fallback` → 内置字体 → `native`。
- 原版文本（EditBox、Tooltip 内容、Boss 标题）使用原版 `Font`；需要视觉一致时资源包同时提供
  对应的 MC 字体。
- 缺失字体绘制占位并发送一次 `CLIENT_ERROR(code=10)`。

## 16. 配置与诊断

```json
{
  "enabled": true,
  "hud": true,
  "tooltip": true,
  "tooltipScroll": true,
  "playerInventory": true,
  "bossBar": true,
  "animations": true,
  "titleSentinelFallback": true,
  "debugOverlay": false,
  "textureCacheMiB": 32,
  "remoteImages": true,
  "imageCacheMiB": 128,
  "renderer": "nanovg",
  "fboScale": 2,
  "fallbackToNative": true,
  "logLevel": "info",
  "logFile": "logs/cloudcore-client.log",
  "logMaxSizeMiB": 10,
  "logRotate": 5,
  "logRedaction": true,
  "serverDetail": true,
  "diagnosticRingSize": 500
}
```

### 16.1 日志与诊断

- 文件：`logs/cloudcore-client.log`，默认 10 MiB × 5 轮转，控制台同步。
- 格式：`时间 级别 [类别/上下文] [CC-*] 消息`，下一行 `key=value`，ERROR 带堆栈。
- 级别：`error|warn|info|debug|trace`；类别见 `18-diagnostics.md`。
- 上下文：客户端版本、协议版本、服务端标识、uiId、surface、revision、elementId、actionId、traceId。
- 覆盖：配置解析、握手、文档 schema、主题/字体、本地贴图、URL/GIF、绑定、NanoVG/FBO 回退、
  渲染异常、输入、动作拒绝、性能预算、路径越界。
- 服务端 `DIAGNOSTIC` 写入同一日志；detail 只含相对路径，限流去重。
- 环形缓冲：最近 500 条日志 + 最近 200 条报文头（不含 body）。
- 快捷键/命令：`F9` 重载本地资源，`F10` 生成诊断包；`/cloudcore-client dump` 导出
  `logs/cloudcore-dump-*.zip`。
- 脱敏：不记录输入内容、令牌、完整 URL、IP、绝对路径；控制字符先清洗，防日志注入。
- 日志写入异步队列，不阻塞渲染线程；队列满时丢弃并计数。

F3 叠加层显示：活动会话（uiId、surface、修订号）、绑定令牌、元素数量、上一帧耗时、报文计数、
贴图缓存、悬停元素边界、Tooltip 滚动偏移、Boss 绑定数量、当前渲染后端、FBO 尺寸、NanoVG 字体数量。

## 17. 故障隔离

| 故障 | 行为 |
| --- | --- |
| 面板钩子安装失败 | 该面板禁用，发送 `CLIENT_ERROR(code=9)`，其余面板继续 |
| NanoVG 初始化失败 | 切到 `NativeBackend`，记录一次，不阻止界面 |
| FBO 创建/resize 失败 | 当前帧用 `NativeBackend`，下一帧重试 |
| FBO 合成异常 | 清空该层，回退原生绘制，记录一次 |
| 字体加载失败 | 回退内置字体，再回退 `native` |
| 渲染异常 | 捕获，记录一次，回退原版，发送 `CLIENT_ERROR` |
| 文档解析失败 | `UI_READY(state=2)`，保持原版 |
| 文档缺失 | `UI_READY(state=1)`，等待 2 秒 |
| 面板类型不支持 | `UI_READY(state=4)`，服务端回退 |
| 绑定超时 | 5 秒后丢弃绑定 |
| 字体缺失 | 回退并记录 |
| 服务器消失 | `DISCONNECT` 清空状态 |

渲染失败必须降级到原版，绝不能黑屏或卡住光标物品。所有失败路径都写入客户端日志，带 `CC-*` 和上下文；
诊断包见 `18-diagnostics.md`。

## 18. 线程与分配

- 网络线程不访问 Minecraft 对象，除非 payload 字节数组。
- 每帧不分配 `Component`/`Identifier`；缓存在元素运行时对象里。
- 布局只在失效时重算。
- 动画基于 `Util.getMillis()`，不基于 tick。
- Tooltip 文本行只在内容或样式变化时重建。
- NanoVG 上下文、FBO 创建/重建/销毁、字体创建、合成只在渲染线程。
- TTF/OTF 和图片字节可以在工作线程读取，上传/字体创建切回渲染线程。
- FBO 按逻辑分辨率整数倍分配；窗口/GUI 缩放变化才重建，不逐帧重建。

## 19. 客户端本地面板（`LocalPageRegistry`）

玩家可以把普通 Cloud Core 页面放进 `.minecraft/resourcepacks/CloudCore/页面/`，让它**在没有服务端**
的情况下接管一个容器界面或一条 Boss 血条。

```yaml
schema: 1
surface: container        # 或 boss_bar
id: my_chest_skin
match: "^我的箱子$"        # 正则，匹配容器标题 / 血条名称
size: [176, 166]
elements: []
```

- `match` 是 Java 正则，用 `Matcher#find()` 匹配玩家看到的标题（容器用 `getTitle().getString()`，
  血条用剥掉哨兵后的名称），不是整串完全相等。
- `surface` 必须是 `container` 或 `boss_bar`；其他面板不查本地面板。文件只读顶层
  `surface`/`match`/`id` 做索引，文档本身仍走 `PageLoader`，所以支持 `includes` 等全部页面能力。
- **服务端下发的页面优先**：只有在没有为这个 surface 下发过文档时才会查本地文件
  （`ContainerSurfaceAdapter.replace` / `BossBarSurfaceAdapter.render`），
  所以显式的 `UI_OPEN` 永远不会被本地文件顶掉。
- 本地面板没有会话，状态从空开始，只渲染自己声明的默认值；因此 `set_state` 对它无效。
- 编译结果按源文件缓存，编译失败记为失败值，只报一次日志而不是逐帧刷屏；`F9`
  （`ClientResources`）会清空索引与缓存后重读。
- 单文件上限 512 KiB，超出告警跳过；错误码 `CC-LOCAL-001`（目录不可读）、`-002`（编译失败）、
  `-003`（文件过大）、`-004`（未知 surface）、`-005`（`match` 正则非法）、`-006`（读取失败）。
- 只在渲染线程访问。
