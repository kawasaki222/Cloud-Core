# 04 — 服务端实现（Paper）

## 1. 启动流程

`CloudCorePlugin#onEnable` 按以下顺序执行。每一步单独兜底，单个坏页面不能阻止插件启用。

1. `saveDefaultConfig()`，校验配置值，把越界数字夹回合法区间。
2. 注册通道：为 `cloudcore:main` 同时调用 `registerOutgoingPluginChannel` 和
   `registerIncomingPluginChannel`。
3. 加载主题与字体索引。
4. 加载页面：扫描 `pages/`，解析清单、合并片段（见 `12-page-composition.md`），编译并校验。
5. 注册监听器：`PlayerJoinEvent`、`PlayerQuitEvent`、`InventoryOpenEvent`、
   `InventoryCloseEvent`、`PlayerChangedWorldEvent`、`PlayerRespawnEvent`、`PlayerCommandPreprocessEvent`。
6. 注册命令执行器与 Tab 补全。
7. 如果存在 PlaceholderAPI，注册 `CloudCoreExpansion`。
8. 启动会话 ticker（每 tick）和握手清扫任务（每 20 tick）。
9. 通过 `ServicesManager` 注册 `CloudCoreApi`。

`onDisable`：向所有会话发送 `UI_CLOSE(reason=SHUTDOWN)`，注销通道，停止 ticker。
资源包/启动器分发不在插件范围。

## 2. 配置

```yaml
protocol:
  channel: "cloudcore:main"
  min-client-version: "0.1.0"   # 模组版本；协议版本单独协商
  handshake-timeout-seconds: 10

ui:
  default-theme: dark
  max-sessions-per-player: 4
  close-on-world-change: true
  close-on-death: true

surfaces:
  container:
    enabled: true
    legacy:
      enabled: true
      interaction: passthrough
      skip: [CRAFTING, ANVIL, ENCHANTING, MERCHANT, BEACON, HOPPER, SHULKER_BOX]
      title-sentinel-fallback: true
  player_inventory:
    enabled: true
    document: inventory_skin
    apply-on-join: true
  hud:
    enabled: true
    layers:
      background: hud_background
      foreground: hud_foreground
  tooltip:
    enabled: true
    document: tooltip_theme
  boss_bar:
    enabled: true
    document: boss_theme
    adopt-sentinel: true

placeholders:
  default-mode: interval
  default-interval-ticks: 20
  async-namespaces: []
  per-player-budget-ms: 2
  global-budget-ms: 10

security:
  max-events-per-second: 40
  event-burst: 80
  per-element-cooldown-ms: 50
  max-definition-bytes: 1048576
  max-text-length: 512
  max-repeat-instances: 256

textures:
  inline-enabled: true
  max-texture-bytes: 262144
  max-texture-dimension: 512
  max-client-cache-bytes: 33554432
  url:
    enabled: true
    mode: client            # client | proxy | auto
    allowlist: ["https://cdn.example.com/"]   # proxy/auto 的服务端拉取白名单
    max-bytes: 1048576
    timeout-ms: 3000
    max-redirects: 2
    cache-dir: "cache/textures"
  gif:
    enabled: true
    max-frames: 64
    max-total-pixels: 4194304
    min-frame-delay-ms: 50

storage:
  type: sqlite                   # sqlite | mysql
  sqlite:
    file: "cloudcore.db"
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "cloudcore"
    user: "cloudcore"
    password: ""
    ssl: true
    pool-size: 8

diagnostics:
  send-client-details: true      # 生产可关；开发默认开
  include-relative-paths: true   # 只发相对路径
  max-detail-bytes: 4096
  rate-limit-per-second: 10

debug: false
```

说明：

- `max-sessions-per-player` 允许同时存在多个面板（HUD + 背包 + Tooltip + Boss），容器类临时会话单独计数。
- `surfaces.player_inventory` 不需要服务端打开界面，只推送皮肤文档。
- `surfaces.tooltip` 只推送样式文档，内容由客户端物品提示产生。
- `surfaces.boss_bar` 绑定原版 Boss 事件，通过标题哨兵匹配。
- `storage` 只用于动作引擎 v2 的玩家持久变量；默认 SQLite，生产建议 MySQL。

## 3. 命令与权限

```
/cloudcore ping [player]                发送 PING 并报告 RTT（控制台可指定玩家）
/cloudcore demo [player]                发送最小自绘演示页，验证传输与渲染链路
/cloudcore demo-big [player]            发送 >24 KiB 文档，验证真机分片与重组
/cloudcore demo-hud [player]            发送持久 HUD 文档，验证 HudElementRegistry 绘制
/cloudcore demo-tooltip [player]        发送 Tooltip 皮肤文档，验证原生文本+自定义背景
/cloudcore demo-container [player]      发送容器文档并打开测试箱子，验证容器换皮
/cloudcore demo-inventory [player]      发送背包皮肤文档，按 E 打开背包验证
/cloudcore probe [player]               打开测试箱子，验证 InventoryOpenEvent 时序（需配置）
/cloudcore boss [player]                发送带哨兵的测试 BossBar，验证客户端匹配
/cloudcore close [player]               关闭最近发送的测试 UI，验证服务端主动关闭
/cloudcore diagnostic [player]          下发一条测试 DIAGNOSTIC，验证客户端日志链路
/cloudcore reload                       重载主题、字体索引与页面（计划）
/cloudcore open <player> <page>         为玩家打开页面（计划）
/cloudcore close <player> [surface]     关闭某个面板或全部面板（计划）
/cloudcore list                         列出已加载页面、面板类型与修订号（计划）
/cloudcore validate <file>              编译并校验单个 YAML（计划）
/cloudcore stats                        计数器（计划）
/cloudcore debug <player> [on|off]      单玩家报文日志（计划）
/cloudcore texture push <player> <key> <file.png>（计划）
/cloudcore surfaces <player>            查看该玩家各面板的绑定状态（计划）
/cloudcore diagnostics <player>         发送一条测试诊断（计划）
```

当前已实现 `ping` / `demo`，使用 `cloudcore.admin`；其余为后续里程碑命令。

| 权限 | 默认 | 用途 |
| --- | --- | --- |
| `cloudcore.admin` | op | 当前 `ping` / `demo` 及后续基础命令 |
| `cloudcore.command` | op | 基础命令（计划拆分） |
| `cloudcore.open` | op | 打开任意页面 |
| `cloudcore.page.<id>` | op | 打开指定页面 |
| `cloudcore.surface.<name>` | op | 使用指定面板 |
| `cloudcore.reload` | op | 重载 |
| `cloudcore.debug` | op | 调试输出 |

页面级权限是 `cloudcore.page.<页面 id>`，除非页面显式设置 `permission:`。打开时检查一次，并每 5 秒
对活动会话复查。

## 4. 传输与握手

```java
public final class PluginMessageTransport implements PluginMessageListener {
    private static final String CHANNEL = "cloudcore:main";

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!CHANNEL.equals(channel)) return;
        Frame frame = FrameCodec.decode(data, Limits.C2S);
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> dispatch(player, frame));
            return;
        }
        dispatch(player, frame);
    }

    public void send(Player player, Frame frame) {
        int fragmentId = Fragmenter.randomFragmentId(random);
        for (Frame part : Fragmenter.fragment(frame, Limits.S2C, fragmentId)) {
            player.sendPluginMessage(plugin, CHANNEL,
                    FrameCodec.encode(part, Limits.S2C));
        }
    }
}
```

规则：

- `send` 只在主线程调用；调试模式下写入方检查并抛异常。
- 未完成握手的玩家只允许收到 `HELLO_ACK`。
- 只有收到主版本兼容的合法 `HELLO` 后，玩家才被标记为 `modded`。
- `HELLO_ACK` 在收到 `HELLO` 后至少延后 1 tick 发送；同一 tick 内发送时 Fabric 客户端
  接收器尚未完全就绪，实测会丢失。
- `HELLO.features` 决定该玩家能用哪些面板；服务端不得下发未声明的面板类型。

## 5. 页面加载与组合

- `PageStore` 扫描 `pages/`，识别单文件页面和目录页面。
- `PageComposer` 按 `includes` 深度优先合并片段；检测循环、深度和数量上限。
- `DocumentCompiler` 把组合后的页面编译成 `UiDocument`，分配稳定 `actionId`。
- `DocumentValidator` 统一校验，错误带文件名和行号。
- `DefinitionStore` 按修订号缓存 JSON 和 CRC；页面或任一依赖片段变化时，只重编译依赖它的页面。
- 编译是原子的：新文档构建成功后才替换旧修订号。
- 主题令牌在服务端校验存在；下发文档保留 `tx:` 引用，客户端按 `THEME_SET` 解析。主题按玩家下发一次，
  reload 或主题变化时重发。
- 动作体、命令、权限、`state`、`repeat` 不进入下发文档；客户端只收到展开后的元素和
  `events: {event: actionId}` 映射。

组合与合并规则详见 `12-page-composition.md`。

## 6. 会话生命周期

```
CREATED ──文档已发送──► BOUND ──UI_READY──► ACTIVE ──关闭──► CLOSING ──► CLOSED
    │                        │                    │
    └── 绑定超时 5 秒 ───────┴────────────────────┘
```

持久面板跳过 `BOUND`，`UI_READY` 后直接 `ACTIVE`。

```java
public record UiSession(
        UUID playerId,
        String uiId,
        SurfaceType surface,
        String surfaceKey,
        int revision,
        int bindToken,
        BindMode bindMode,
        InteractionMode interaction,
        Map<String, Object> state,
        Map<String, Long> cooldowns,
        long openedAtTick,
        SessionState state) {}
```

不变量：

- `bindToken` 是随机 `u32`，同一连接内不复用。
- `revision` 每个文档单调递增。
- 同类型临时面板每玩家最多一个；持久面板每类型最多一个。
- 关闭会话发送 `UI_CLOSE(reason)`；容器由 Bukkit 关闭。

### 6.5 页面命令

`/cloudcore validate`：遍历 `pages/`（`<目录>/page.yml` 与顶层 `*.yml`），逐页编译并报告
`[OK]`/`[FAIL] 名字: 原因`，不改动在线玩家界面。
`/cloudcore reload`：重新编译所有页面，并给正在查看该页面的玩家推送更高 revision 的文档更新；
会话状态保留（每个玩家按页面名保存）。
`/cloudcore page <名字> [玩家]`：从 `plugins/CloudCore/pages/<名字>/page.yml`（或 `<名字>.yml`）
加载 YAML，经 `PageLoader` 组合与展开后下发 `UI_OPEN` + `UI_DEFINITION`。当前只接受
`surface: custom_screen`；容器/HUD/Tooltip/Boss 仍走各自的演示与后续策略实现。
插件首次启动会把示例页写到 `pages/example.yml`。

## 7. 面板提供器

### 7.1 容器面板

```java
Inventory inv = Bukkit.createInventory(new CloudHolder(session), rows * 9, title);
player.openInventory(inv);
```

`CloudHolder` 实现 `InventoryHolder`，`getInventory()` 必须返回实际物品栏，不能返回 `null`。

点击策略（`InventoryClickEvent`，`LOWEST`，`ignoreCancelled = true`）：

| 模式 | 行为 |
| --- | --- |
| `managed` | 除非原始槽位映射到已声明网格槽位，否则取消 |
| `readonly` | 全部取消，除非 `allow_player_inventory: true` |
| `passthrough` | 永不取消；旧界面适配使用 |

实现要点（`ContainerClickPolicy` + `CloudInventoryHolder`）：

- 物品栏由 `Bukkit.createInventory(new CloudInventoryHolder(...), ...)` 创建，holder 携带
  玩家、`uiId`、交互模式和已声明槽位。
- 已声明槽位来自文档里 `inventory_grid` 元素的 `slots: [first, last]`；服务端解析自己下发的
  文档得到集合，保证客户端与服务端判定同源。
- 玩家背包（下层）槽位在 `managed`/`passthrough` 下不受限；`readonly` 默认全部取消。
- 交互模式无法解析时按 `readonly` 处理（fail closed），并写服务端日志。
- `InventoryDragEvent` 使用同一判定；任一目标槽位不允许就整体取消。
- 被取消的点击写一行日志：`CANCELLED click ui=... interaction=... top=... slot=... declared=...`。

### 7.2 玩家背包面板

服务端不打开背包，只推送皮肤：

```java
transport.send(player, UiPackets.openPersistent(
        session, SurfaceType.PLAYER_INVENTORY, "default", RenderMode.SURFACE_NATIVE));
transport.send(player, UiPackets.definition(session));
```

触发时机：

- 玩家完成握手后（`apply-on-join`）。
- `/cloudcore reload` 后。
- 页面修订号变化后。
- 玩家权限变化导致皮肤不再可用时，发送 `UI_CLOSE(reason=PERMISSION)`。

槽位与点击仍由原版 `InventoryMenu` 处理；服务端不重新实现背包逻辑。

### 7.3 HUD 面板

```java
transport.send(player, UiPackets.openPersistent(session, SurfaceType.HUD, layerName,
        RenderMode.SURFACE_NATIVE));
transport.send(player, UiPackets.definition(session));
```

HUD 文档不允许声明槽位。状态更新与普通会话一致，走 `ELEMENT_UPDATE`。

### 7.4 Tooltip 面板

一个玩家一份 Tooltip 样式文档：

```java
transport.send(player, UiPackets.openPersistent(session, SurfaceType.TOOLTIP, "default",
        RenderMode.SURFACE_NATIVE));
```

文档包含外框、字体、颜色、最大高度、滚动步长和滚动条样式。滚动条可自定义轨道、滑块、悬停态贴图、
宽度、间距、最小滑块高度、九宫格、颜色以及是否可拖动，定义在
`surface_options.tooltip.scrollbar`。Tooltip 的内容由客户端从物品生成，服务端不发送每一行文本。
服务端可以通过物品的 `lore` 组件追加内容。

`sync_scroll: true` 时，客户端把滚轮事件作为 `EVENT` 上报，服务端可以据此执行动作；默认不上报。

### 7.5 Boss 血条面板

```java
KeyedBossBar bar = Bukkit.createBossBar(key, Sentinels.wrap(token, title), color, style);
bar.addPlayer(player);
sessionRegistry.openBossBar(player, token, session);
transport.send(player, UiPackets.openPersistent(session, SurfaceType.BOSS_BAR,
        Integer.toHexString(token), RenderMode.SURFACE_NATIVE));
transport.send(player, UiPackets.definition(session));
```

- 标题前加上不可见哨兵，例如 `\u0000CCB:<token>\u0000`，客户端据此匹配。
- 哨兵必须在发送给原版客户端前保留；原版客户端会把它当作普通字符渲染。因此只对装了模组的玩家
  使用哨兵；原版玩家使用正常标题。
- 进度、颜色、样式仍由原版 `BossEvent` 同步；Cloud 文档只负责外观。
- 外框、填充、覆盖层贴图、九宫格、填充方向、文字字体/颜色、是否按事件颜色染色，定义在
  `surface_options.boss_bar.bar`。
- 血条移除或玩家退出时发送 `UI_CLOSE`。

Boss 血条的 `surfaceKey` 是 16 进制 token，不是 Paper 的 `NamespacedKey`。

## 8. 状态、遍历与差异

- 会话状态是 `Map<String, Object>`，值只允许 `String`、`Boolean`、`Integer`、`Long`、`Double`、
  `List`、`Map`。
- 模板求值时先展开 `repeat`：根据 `source` 列表生成实例，再对每个实例求值。
- 每个元素的已渲染属性保存在 `Map<String, Map<Property, WireValue>>`。
- 每 tick 收集变化，合并成一个 `ELEMENT_UPDATE`；空差异不发。
- 实例数量变化时，v1 提升修订号并下发完整文档；后续动作引擎版本改为增量增删元素。
- 客户端发送缓冲拥塞时丢弃更新；下一 tick 从权威状态重算，自动恢复。

### 8.1 占位符刷新

服务端按页面 `placeholders` 配置调度：

```yaml
placeholders:
  default-mode: interval
  default-interval-ticks: 20
  async-namespaces: []
  per-player-budget-ms: 2
  global-budget-ms: 10
```

- 每 tick 收集到期元素，按玩家去重原始字符串，批量调用 `PlaceholderAPI.setPlaceholders`。
- 默认在主线程同步解析；每玩家 2 ms、全局 10 ms 预算，超出推迟到下一 tick。
- 只对变化的属性发送 `ELEMENT_UPDATE`，不重发整份文档。
- `refresh` 动作/API 立即触发一次解析。
- `async: true` 且命名空间在 `async-namespaces` 中时，解析放到工作线程，结果下一 tick 应用到主线程。
  PlaceholderAPI 扩展不保证线程安全，未列出的命名空间一律同步。
- 解析结果做文本清洗与长度截断。
- 客户端不解析占位符。

## 9. 动作执行与引擎接缝

v1 使用线性动作列表：

```java
public interface ActionHandler {
    void execute(ActionContext context, Map<String, Object> args) throws ActionException;
}
```

后续动作引擎保持同一个 `ActionContext` 和 `ActionHandler`，把列表编译成 `ActionNode` 图。服务端只依赖
接口：

```java
public interface ActionEngine {
    ExecutionHandle submit(ActionContext context, CompiledAction program);
    void tick(long serverTick);
    void cancel(ExecutionHandle handle);
}
```

v1 的 `LinearActionEngine` 实现 `submit` 时立即执行，`tick` 只处理 `delay`。v2 的
`GraphActionEngine` 支持分支、循环、等待和持久化，见 `13-action-engine.md`。

内置处理器：

| 名称 | 实现细节 |
| --- | --- |
| `player_command` | `player.performCommand(stripSlash(cmd))` |
| `console_command` | `Bukkit.dispatchCommand(console, cmd)` |
| `message` | `player.sendMessage(...)` |
| `close_ui` | 关闭会话 |
| `open_ui` | 按页面声明的 surface 打开；可指定 `surface` |
| `set_state` | 校验键属于文档声明的状态并做类型转换 |
| `run_action` | 查找已注册处理器 |

失败策略：处理器抛异常会中止该次点击剩余动作，记录 uiId/elementId/action，返回
`EVENT_RESULT(INTERNAL_ERROR)`；会话保持打开。

## 10. 旧界面适配

```java
@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
public void onInventoryOpen(InventoryOpenEvent event) {
    if (!(event.getPlayer() instanceof Player player)) return;
    if (event.getInventory().getHolder() instanceof CloudHolder) return;
    PlayerProfile profile = handshake.profile(player);
    if (profile == null || !profile.modded()) return;
    if (!config.legacyEnabled()) return;
    if (config.legacySkip().contains(event.getInventory().getType())) return;

    UiDocument synthetic = LegacyDocumentFactory.create(event.getView());
    UiSession session = sessions.open(player, synthetic, BindMode.NEXT_CONTAINER,
            InteractionMode.PASSTHROUGH);
    transport.send(player, UiPackets.open(session, RenderMode.SCREEN));
    transport.send(player, UiPackets.definition(synthetic, session.revision()));
    if (config.titleSentinelFallback()) {
        event.getView().setTitle(Sentinels.wrap(session.bindToken(), event.getView().getTitle()));
    }
}
```

`LegacyDocumentFactory` 生成覆盖全部槽位的 `inventory_grid`，位置为原版偏移
`(8 + col*18, 18 + row*18)`，`interaction: passthrough`，没有任何 `on.click`。

约束：

- 不取消事件，不改变第三方插件的点击逻辑。
- 事件被其他插件取消时，立即关闭刚创建的会话。
- 标题变更只影响装饰，不影响数据。
- 物品内容由容器同步，Cloud 不重复传输。

**顺序假设：** `sendPluginMessage` 早于 `ClientboundOpenScreenPacket`。必须由集成测试覆盖。

## 11. 资源与字体

- 正式资源放在客户端的 `.minecraft/resourcepacks/CloudCore/`；服务端只下发相对路径
  （`local:...`），无法验证客户端是否真的存在。
- 内联贴图承载动态美术，不承载字体。
- 字体索引 `fonts/index.yml` 列出相对路径、来源（`local`/`pack`/`mod`）、回退链；服务端校验路径
  字符集、扩展名和大小，无法检查字形覆盖。
- 输入框编辑文本、Tooltip 内容仍用原版 `Font`；需要视觉一致时，本地 CloudCore 目录里同时放一份
  对应的 MC 字体。
- 资源包/启动器分发由服务器或插件作者自己的体系负责，不在本插件范围；插件只支持 `local:` 目录、
  `pack:` 引用和 `inline:` 推送。
- 客户端 `F9` 重载本地资源；服务端 `/cloudcore reload` 只重载页面/主题，不触碰客户端文件。

### 11.1 URL 贴图与 GIF

三种模式：

| 模式 | 服务端 | 客户端 |
| --- | --- | --- |
| `client`（默认） | 只发 `TEXTURE_REF`（URL、SHA-256、尺寸） | 异步直连下载、校验、解码、缓存 |
| `proxy` | 下载、校验、解码，`TEXTURE_PUT` 下发 | 只接收字节，不联网 |
| `auto` | 先发 `TEXTURE_REF`；收到 `TEXTURE_REQUEST` 再代理 | 先直连，失败后请求代理 |

- 默认 `client`：图片流量走 CDN/图床，不占游戏连接，玩家多时不会把服务端上行打满。
- `proxy` 适合隐私要求高、客户端出网受限或图片来源需要鉴权的场景；代价是服务端上行 =
  玩家数 × 图片字节。
- `auto` 是折中：正常走客户端，失败才由服务端兜底。
- 服务端 `allowlist` 只在 proxy/auto 的服务端拉取阶段生效；client 模式下由客户端执行自己的网络
  策略（https、拒绝私网/回环、大小/超时）。
- GIF：client 模式客户端本地解码；proxy 模式服务端解码为精灵表。两者都遵守 64 帧、单帧 512×512、
  16 MiB、最小延迟 50 ms 和 1 MiB 编码上限。
- 缓存：client 模式按内容哈希缓存在客户端；proxy 模式按 URL + SHA-256 缓存在服务端。
- 失败：client 模式回退到代理请求；仍失败则显示占位。

## 12. PlaceholderAPI

模板中的 `${placeholder:...}` 委托给 `PlaceholderAPI.setPlaceholders`，结果按玩家每 tick 缓存。
未闭合的占位符是编译期错误。

## 13. 公共 Java API

```java
public interface CloudCoreApi {
    OpenResult open(Player player, String pageId, OpenOptions options);
    boolean close(Player player, SurfaceType surface);
    Optional<UiSessionView> session(Player player, SurfaceType surface);

    void registerActionHandler(String name, ActionHandler handler);
    void registerPlaceholderResolver(String namespace, PlaceholderResolver resolver);
    void registerSurfaceProvider(SurfaceProvider provider);

    void setState(Player player, String uiId, String key, Object value);
    Optional<Object> getState(Player player, String uiId, String key);

    void pushTexture(Player player, String key, byte[] png) throws TextureException;
    void sendDiagnostic(Player player, Diagnostic diagnostic);
    void reload();
}
```

`Diagnostic`：`severity`、`code`、`scope`、`message`、`detail`、`traceId`。服务端会脱敏相对路径、
截断 detail、去重限流后通过 `DIAGNOSTIC` 下发，同时写入服务端日志。

附属插件 API 的完整合同见 `19-addon-api.md`：代码构造页面、动作/占位符/页面提供器、事件监听、
线程与兼容策略。

`OpenOptions`：`surface`、`bindMode`、`renderMode`、`interaction`、`theme`、`state` 初始值、
`closeExisting`。

规则：

- `open` 从非主线程调用时返回 `OpenResult.SCHEDULED`，实际工作放到下一 tick。
- 玩家没装模组时返回 `OpenResult.NOT_MODDED`，不抛异常。
- `pushTexture` 发送前校验尺寸和大小，按 `(key, crc32)` 缓存。

## 14. 调度与 Folia

- 所有状态在主线程。
- ticker 是单个重复任务；每会话工作量有上限。
- `BukkitSchedulerBridge` 是唯一提到调度器的地方；Folia 移植只替换这个类。

## 15. 诊断

`/cloudcore stats`：

```
sessions active=7 ephemeral=2 persistent=5
surfaces container=2 inventory=1 hud=1 tooltip=1 boss=2
s2c frames=812 fragments=97 bytes=214331
c2s frames=190 bytes=9120
rejects rate_limited=2 unknown_element=0 stale_revision=0 surface_unsupported=0
textures put=12 bytes=204800 evicted=1
bind pre=40 legacy=15 timeout=0
repeat expanded=128 max=64
```

`/cloudcore surfaces <player>` 列出每个持久面板的 uiId、修订号和最后更新时间。
调试模式记录帧头，不记录 body，也不记录输入框内容。

### 15.1 客户端诊断下发

- 页面编译失败、打开失败、动作异常、版本不匹配时发送 `DIAGNOSTIC`。
- 启动阶段没有客户端连接，错误只写服务端日志；玩家打开受影响页面时补发。
- `detail` 使用相对路径（`pages/shop/page.yml:12`），截断到 `max-detail-bytes`，去重限流。
- 客户端把诊断写入 `logs/cloudcore-client.log`，格式与客户端自身错误一致。
- 服务端日志保留完整信息；客户端日志是排障副本。
- `diagnostics.send-client-details: false` 时只写服务端日志，不下发细节。
- `/cloudcore diagnostics <player>` 可发一条测试诊断，验证链路。
- 审计和隐私规则见 `18-diagnostics.md`。
