# 01 — 架构

## 1. 系统上下文

```
                    ┌──────────────────────────── Paper 服务端 ───────────────────────────┐
                    │                                                                      │
  其他插件 ────────►│  Bukkit 事件 ──► cloudcore-bukkit ──► cloudcore-server-core          │
  （箱子/背包）     │                          │                    │                    │
                    │                          │              cloudcore-api               │
                    │                          ▼                    │                    │
                    │                   cloudcore-protocol ◄──────────┘                    │
                    └──────────────────────────┬───────────────────────────────────────────┘
                                               │  插件消息通道  cloudcore:main
                                               ▼
                    ┌──────────────────── Fabric 客户端 ──────────────────────────────────┐
                    │  cloudcore-client                                                   │
                    │    ├─ SurfaceRegistry ──► 六类 SurfaceAdapter                       │
                    │    │        ├─ CustomScreen / Container / PlayerInventory           │
                    │    │        ├─ Hud / Tooltip / BossBar                              │
                    │    ├─ LayoutEngine ──► NanoVgBackend ──► FboCompositor ──► GuiGraphics │
                    │    └─ InputRouter ──► cloudcore-protocol ──► 服务端                 │
                    └──────────────────────────────────────────────────────────────────────┘
```

同一条通道上跑两条独立字节流：S2C（服务端 → 客户端）和 C2S（客户端 → 服务端）。两者使用同一个信封，
详见 `02-protocol.md`。

## 2. 模块

### `cloudcore-api` —— 文档模型（纯 Java）

- `UiDocument`、`Element`、`ElementType`、`Style`、`Theme`、`FontSpec`、`ActionSpec`、`Transition`。
- 页面组合模型：`PageManifest`、`FragmentRef`、`MergeRule`。
- 遍历模型：`RepeatSpec`。
- 通过 Gson 注解做 JSON 绑定；服务端序列化、客户端反序列化使用同一批类。
- 下发文档的字段与类型定义见 `16-ui-document-schema.md`；作者 YAML 见 `03-ui-dsl.md`。
- 包含校验器（`DocumentValidator`），畸形 YAML 在加载时报错。
- 不 import Minecraft，不 import Bukkit，不做 I/O。

### `cloudcore-protocol` —— 线上格式（纯 Java）

- `Frame`、`Opcode`、`MessageCodec`、`FragmentAssembler`、`Limits`、`WireValue`。
- `SurfaceType` 枚举与 `BindMode`。
- 只有这个模块知道字节布局。
- 通过 `ByteSink` / `ByteSource` 抽象同时服务 Paper 的 `byte[]` 与 Fabric 的
  `RegistryFriendlyByteBuf`：

```java
public interface ByteSink    { void writeByte(int v); void writeVarInt(int v); void writeBytes(byte[] b); /* … */ }
public interface ByteSource  { int readByte(); int readVarInt(); byte[] readBytes(int max); /* … */ }
```

### `cloudcore-server-core` —— 策略与状态（纯 Java + Gson）

- `YamlLoader` → `PageComposer`（合并片段）→ `DocumentCompiler` → `UiDocument`。
- `DefinitionStore`：带修订号的文档，按修订号缓存编译后的 JSON。
- `SessionRegistry`：玩家 → 活动会话、绑定令牌、序号、限流桶。
- `SurfacePolicy`：决定某玩家某面板使用哪份文档、何时打开、何时关闭。
- `StateModel`：每个会话的变量，转换成 `ELEMENT_UPDATE`。
- `ActionExecutor`：v1 的线性动作执行；后期替换为 `ActionEngine` 实现，接口不变。
- `PlaceholderResolver` 接口；Bukkit 模块提供 PlaceholderAPI 实现。

### `cloudcore-bukkit` —— 平台绑定

实现全部集中在 `CloudCorePlugin` 与几个协作类里，没有按职责拆成 `transport/`、`listener/` 等子包：

- `CloudCorePlugin`：生命周期、配置、通道注册、命令与 Tab 补全、动作执行、表面路由、
  页面热重载、状态持久化调度。
- `CloudInventoryHolder`：容器持有者（玩家、`uiId`、交互模式、已声明槽位）。
- `PageWatcher`：`pages/` 的 WatchService 监听（400ms 合并）。
- `Placeholders`：PlaceholderAPI 与附属解析器展开。
- `api/`：`CloudCoreApi` 实现（`ActionHandler`、`PlaceholderResolver`、`PageProvider`、
  `OpenOptions`、`OpenResult`、`ActionContext`）。
- `item/EquipmentAssets`：把 1.21.11 数据驱动 `equipment` 资产写进物品的 `equippable.model`。
- `slot/`：`SlotCatalog`、`SlotDefinition`、`AccessorySlots`（`slots.yml` 驱动的独立槽位页）。
- `texture/TextureProxy`：`TEXTURE_REQUEST`/`TEXTURE_PUT` 的下载、校验、磁盘缓存。

调度统一走 `getServer().getScheduler()`；没有单独的 `BukkitSchedulerBridge` 抽象，
Folia 移植时这一处需要重新抽出来。

### `cloudcore-client` —— 表现层（Mojang 映射）

- `CloudCoreClient`：模组初始化、配置、通道注册、会话状态。
- `mixin/`：4 个行为混入（`Minecraft`、`GuiGraphics`、`MouseHandler`、`BossHealthOverlay`）
  + `GuiMixin`（HUD `replace`）+ 5 个只读 accessor。
- `surface/`：`SurfaceRegistry`、六个适配器、`DocumentUpdates`、`HudValues`。
- `screen/`：`CloudScreen`、`CloudContainerScreen`、`CloudInventoryScreen`、
  `CloudDebugOverlay`、`SlotLayout`、`EntityPortrait`。
- `render/`：`DocumentRenderListBuilder`（布局 + RenderList）、`NanoVgBackend`、`NativeBackend`、
  `FboCompositor`、`LocalTextureRegistry`、`LocalPageRegistry`、`RemoteTextureCache`、
  `TextureDiskCache`、`AnimatedTextures`、`BuiltinTextures`、`FontRegistry`。
- 布局、命中测试与滚动偏移共用 `DocumentRenderListBuilder` 的几何计算，没有独立的 `LayoutEngine`。
- `RenderBackend`：`NativeBackend`（当前默认）与 `NanoVgBackend`（opt-in，目标默认）。
- `NanoVgBackend`：NVGContext、字体图集、离屏 FBO、背景/前景两个绘制阶段。
- `FboCompositor`：把 FBO 通过 `GuiGraphics` 贴回屏幕，处理预乘 alpha、缩放和 GL 状态恢复。
- 原生物品/槽位/提示文本的绘制直接由各适配器调用 `GuiGraphics`，没有独立的 `NativeBridge` 类。
- `FontRegistry`：从本地 CloudCore 目录、资源包或模组 jar 回退加载 TTF/OTF，创建 NanoVG 字体。
- 输入路由在 `CloudScreen`/各适配器里（命中测试 `ElementHitTester`、焦点、悬停、滚轮、键盘）。
- 贴图管理在 `LocalTextureRegistry`/`BuiltinTextures`/`RemoteTextureCache`/`TextureDiskCache`/
  `AnimatedTextures`，负责 `DynamicTexture` 上传、解码与磁盘缓存。
- `CloudDebugOverlay`：F3 叠加层，显示活动文档的 uiId/surface/revision 与文档数。
- `ClientLogFile` / `ClientDump` / `FrameLog`：结构化日志、脱敏、诊断包导出。
- `ClientResources`：读取 `.minecraft/resourcepacks/CloudCore/`，支持业务分类和 `F9` 重载。

### 渲染架构

```
文档 / 布局 / 输入
        │
        ▼
   RenderList ──► RenderBackend
                    ├─ NanoVgBackend
                    │    ├─ NVGContext + FontRegistry
                    │    ├─ background FBO ──► FboCompositor
                    │    └─ foreground FBO ──► FboCompositor
                    └─ NativeBackend（回退）
                         └─ GuiGraphics
```

- `NanoVgBackend` 负责面板、静态文本、图片、九宫格、圆角、渐变、裁剪和特效。
- `FboCompositor` 把 NanoVG 渲染的纹理通过原版 GUI 管线贴回屏幕。
- `NativeBridge` 负责物品、槽位、数量/冷却/附魔光效、tooltip 内容、输入法文本。
- `FontRegistry` 为 NanoVG 提供 TTF/OTF；原版 `Font` 只用于必须原版绘制的文本。
- `NativeBackend` 是完整回退，用 `GuiGraphics` 绘制同一份 `RenderList`。
- 后端选择顺序：配置 → 初始化检测 → `NanoVG → Native`。服务端不感知后端。

## 3. 面板抽象（Surface）

### 3.1 类型

| 值 | 名称 | 生命周期 | 绑定方式 |
| --- | --- | --- | --- |
| `0` | `CUSTOM_SCREEN` | 临时 | `UI_OPEN(bindMode=NONE)` 直接开屏 |
| `1` | `CONTAINER` | 临时 | 预绑定 + 下一个 `AbstractContainerScreen` |
| `2` | `PLAYER_INVENTORY` | 持久 | 玩家加入时推送；客户端打开背包时应用 |
| `3` | `HUD` | 持久 | 按层注册，服务端可随时更新 |
| `4` | `TOOLTIP` | 持久 | 全局配置；内容由客户端物品提示产生 |
| `5` | `BOSS_BAR` | 附加 | 通过标题哨兵匹配原版 `BossEvent` |

新增面板类型需要：协议 `SurfaceType` 常量、客户端 `SurfaceAdapter`、服务端 `SurfaceProvider`、
一个特性位。协议次版本号提升，主版本不变。

### 3.2 客户端适配器接口

```java
public interface SurfaceAdapter {
    SurfaceType type();

    /** 判断当前环境能否承载该请求（例如容器界面类型是否受支持）。 */
    boolean canHandle(OpenRequest request, Minecraft minecraft);

    /** 建立会话；持久面板在这里注册渲染钩子。 */
    void open(OpenRequest request, UiRuntime runtime);

    /** 拆除会话；必须幂等。 */
    void close(String uiId, CloseReason reason);

    /** 临时面板在这里绘制；持久面板通常由各自的钩子绘制。 */
    default void render(RenderContext context) {}
}
```

### 3.3 服务端提供器接口

```java
public interface SurfaceProvider {
    SurfaceType type();

    /** 为该玩家打开一份面板文档，返回会话；失败返回空。 */
    Optional<UiSession> open(Player player, String documentId, SurfaceOptions options);

    /** 持久面板的配置推送（背包、HUD、Tooltip）。 */
    void apply(Player player, UiSession session);

    void close(Player player, String uiId);
}
```

### 3.4 面板与文档的关系

- 每个会话只绑定一个 `UiDocument`。
- 文档可以声明 `surface: container | player_inventory | hud | tooltip | boss_bar | custom_screen`；
  打开时服务端校验声明与请求一致。
- 同一份文档可以被不同玩家以不同修订号打开；文档本身不可变。

## 4. 依赖规则

```
api  ◄── server-core ──► bukkit
 │           ▲
 │           │
protocol ◄───┘
 │
 └──────── client ──► (Minecraft, Fabric API)
```

- `api` 与 `protocol` 不得依赖对方的兄弟模块。
- `server-core` 不得 import `org.bukkit.*`。
- `client` 在 `mc` 包、混入和面板适配器之外不得 import `net.minecraft.*`。
- 任何需要 Minecraft 类的共享类型都是设计异味：把它建模进 `api`。
- 即使两端都使用 Mojang 映射，也不把 Minecraft 类型放进共享模块——Paper 的类路径与 Fabric 不同，
  而且共享模块需要能脱离游戏做单元测试。

## 5. 构建布局

```
cloud-core/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  cloudcore-api/
  cloudcore-protocol/
  cloudcore-server-core/
  cloudcore-bukkit/                # paper-api，compileOnly
  cloudcore-client/                # fabric-loom + officialMojangMappings
  docs/
```

客户端构建：

```kotlin
dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())

    // 版本必须与 Minecraft 自带 LWJGL 对齐；不要重复引入 lwjgl 核心 natives
    implementation("org.lwjgl:lwjgl-nanovg:$lwjglVersion")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-macos")
}
```

- `gradle.properties` 不再需要 `yarn_mappings`。
- `lwjglVersion` 必须与 Minecraft 自带版本一致；在 M0 确认，不要凭印象填。
- Loom 只应用于 `cloudcore-client`。
- `cloudcore-bukkit` 把 `server-core` + `protocol` + `api` 打进插件 jar。
- 客户端打包 `protocol` + `api`，不包含 `server-core`。
- 不做 relocate：共享类只存在于各自 jar 内，不会暴露在共享类路径上。

## 6. 运行时流程

### 6.1 加入与握手

```
客户端                                  服务端
  │  监听通道 cloudcore:main            │
  │──────────── HELLO ───────────────────►│  记录能力、协议范围、语言、支持的面板
  │◄─────────── HELLO_ACK ────────────────│  服务端协议、特性位、限额
  │                                       │
  │  （10 秒内没有 HELLO → 按原版玩家处理）
```

### 6.2 容器面板（箱子界面）

```
插件 ──openUi(player, "shop", CONTAINER)──► ServerCore
                                             │ 组合页面 → 编译文档
                                             │ session = 预绑定会话
                                             ├──► UI_OPEN(surface=CONTAINER,
                                             │            bindMode=NEXT_CONTAINER, token)
                                             ├──► Bukkit: inventory.open(player)
                                             │       └── OpenScreen 报文
客户端 ◄─────────────────────────────────────┘
  Minecraft#setScreen(ContainerScreen)
    ├─ 待绑定队列匹配 → CloudContainerScreen
    ├─ 文档未缓存 → 请求 UI_DEFINITION
    └─ UI_READY
```

### 6.3 独立页面

```
服务端: UI_OPEN(surface=CUSTOM_SCREEN, bindMode=NONE)
客户端: Minecraft#setScreen(new CloudScreen(null, doc, token))
```

### 6.4 玩家背包界面

```
服务端（玩家加入 / 权限变化 / reload）:
  UI_OPEN(surface=PLAYER_INVENTORY, bindMode=PERSISTENT, document=inventory_skin)

客户端:
  Minecraft#setScreen(new InventoryScreen(player))
    └─ PlayerInventoryAdapter 检测到持久皮肤
        → CloudInventoryScreen（继承 AbstractContainerScreen<InventoryMenu>）
```

服务端不打开这个界面，只提供服务端下发的皮肤配置。槽位与点击仍走原版 `InventoryMenu`。

### 6.5 HUD

```
服务端: UI_OPEN(surface=HUD, bindMode=PERSISTENT, surfaceKey=background|foreground|overlay)
客户端: HudElementRegistry.addLast/addFirst 注册一次，之后由 ELEMENT_UPDATE 驱动
```

### 6.6 Tooltip

```
服务端: UI_OPEN(surface=TOOLTIP, bindMode=PERSISTENT, document=tooltip_theme)
客户端: TooltipSurfaceAdapter
  ├─ 捕获物品提示行（原版生成）
  ├─ NanoVG 画外框、滚动条、背景；原版画文本内容
  ├─ 文档定义最大高度、滚动步长、滚动条样式
  └─ 内容超高时接管滚轮，滚动偏移随悬停物品重置
```

### 6.7 Boss 血条

```
服务端:
  BossBar bar = Bukkit.createBossBar(Sentinel.wrap(token, title), ...)
  UI_OPEN(surface=BOSS_BAR, bindMode=PERSISTENT, surfaceKey=token, document=boss_theme)

客户端:
  BossHealthOverlay#render(GuiGraphics)
    └─ 遍历 BossEvent，标题匹配哨兵 → NanoVG 画背景/填充，原版画标题；其余原版绘制
```

原生脱离原版 `BossEvent` 的自绘血条留到 v2；v1 采用"接管原版 Boss 血条"的方式，避免和原版
血条重复显示。

### 6.8 事件往返

```
客户端: 点击 button#buy
  ├─ 本地: 标记按下，播放 80 ms 按压动画
  └─► EVENT(requestId, uiId, elementId, actionId, payload)

服务端: 限流 → 会话 → 元素 → 动作 → 权限 → 冷却
  ├─ 执行：可能产生 ELEMENT_UPDATE / UI_OPEN / UI_CLOSE
  └─ EVENT_RESULT

客户端: 按 requestId 关联结果
```

### 6.9 渲染与合成

```
layout + state → RenderList
  ├─ NanoVgBackend: background layer → FBO A
  ├─ FboCompositor: A → screen
  ├─ NativeBridge: items / slots / EditBox text
  ├─ NanoVgBackend: foreground layer → FBO B
  └─ FboCompositor: B → screen
```

- 没有原版物品或输入框的界面只用一层 FBO。
- 容器/背包：背景层 → 原版物品 → 前景层；槽位命中仍按逻辑坐标。
- Tooltip：NanoVG 画外框/滚动条，原版画文本内容。
- Boss：NanoVG 画背景/填充，原版画标题；文档可以指定标题也走 NanoVG。
- `FboCompositor` 负责预乘 alpha、整数倍缩放和 GL 状态恢复。
- NanoVG 初始化失败或 FBO 不可用时，整条链路切到 `NativeBackend`。

### 6.10 诊断

```
服务端页面/动作错误
  ├─ 服务端完整日志（文件/行/字段）
  └─ DIAGNOSTIC（相对路径、脱敏、限流）→ 客户端 logs/cloudcore-client.log

客户端解析/资源/渲染错误
  ├─ 客户端 logs/cloudcore-client.log（CC-* + 上下文）
  └─ CLIENT_ERROR（摘要）→ 服务端日志

F10 / dump → logs/cloudcore-dump-*.zip（日志、配置、系统信息、报文头、脱敏）
```

细节见 `18-diagnostics.md`。

## 7. 线程模型

| 端 | 线程 | 规则 |
| --- | --- | --- |
| 服务端 | Bukkit 主线程 | 所有会话/状态变更；监听器检查 `Bukkit.isPrimaryThread()` |
| 服务端 | Netty | 只做分帧与限流计数（原子），不触碰 Bukkit 状态 |
| 客户端 | Netty | 解码、入队；不触碰 `Minecraft` 世界状态 |
| 客户端 | 渲染线程 | 所有界面变更通过 `Minecraft#execute`；NanoVG 上下文、FBO 创建/销毁、字体创建、合成只在这里 |
| 客户端 | 工作线程 | PNG/GIF/字体字节读取可在这里；上传和 `nvgCreateFontMem` 切回渲染线程 |

## 8. 失败处理

| 失败 | 行为 |
| --- | --- |
| 未知操作码 | 丢弃，计数，每个会话只记录一次 |
| 帧超限 | 丢弃分片组并记录，不断开连接 |
| 修订号回退 | 忽略更新，请求一次完整文档 |
| 文档 CRC 不匹配 | 重试一次；再次失败关闭会话 |
| 绑定超时（5 秒） | 关闭会话，客户端回退原版 |
| 面板钩子异常 | 该面板本次禁用，回退原版；发送 `CLIENT_ERROR` |
| NanoVG 初始化失败 | 切到 `NativeBackend`，记录一次，不阻止界面 |
| FBO 创建/ resize 失败 | 本次界面用 `NativeBackend`，下一帧重试 |
| NanoVG 绘制异常 | 清空该层，回退原生绘制，记录一次 |
| 字体加载失败 | 回退内置字体；仍失败则回退原版 `Font` |
| 动作抛异常 | 捕获，记录，返回 `INTERNAL_ERROR`，不破坏事件循环 |
| 客户端错误上报 | 标记会话；累计 3 次后关闭并提示 |

## 9. 扩展点

- 新增面板：实现 `SurfaceAdapter` + `SurfaceProvider` + 协议常量 + 特性位。
- 新增元素：v1 元素集封闭；新增需要协议次版本号提升，服务端不得给旧客户端发明元素。
- 新增动作：`ActionHandler` 注册（v1），动作引擎节点（v2）。
- 占位符：`PlaceholderResolver`。
- 主题与字体：`ThemeProvider`、`FontProvider`。
- 新增渲染后端：实现 `RenderBackend` 并注册；协议与文档不感知，服务端不能强制后端。

## 10. 兼容性契约

| 客户端模组 | 服务端插件 | 结果 |
| --- | --- | --- |
| 无 | 有 | 原版容器、原版 HUD、原版 Tooltip、原版 Boss 血条 |
| 有 | 无 | 模组静默，无通道流量，无视觉变化 |
| 有 | 有，主版本相同 | 按双方特性位启用面板；未声明的面板保持原版 |
| 有 | 有，主版本不同 | 服务端对该玩家禁用 Cloud 界面并记录版本警告 |
| 有 | 有，客户端过旧 | 服务端只启用客户端声明支持的面板与特性 |
