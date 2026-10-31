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

下面是 `plugins/CloudCore/config.yml` 的**实际**键（插件只读这些；未列出的键即使写进文件也不会生效）：

```yaml
# 为排查容器时序，打开后每个 InventoryOpenEvent 都会发一个 PING
inventory-probe: false

pages:
  watch: true                   # WatchService 热重载（400ms 合并）

apply-on-join:
  # 留空 = 自动下发 pages/ 下所有常驻类页面（hud / player_inventory / tooltip / boss_bar）
  # 非空 = 只下发列出的页面（文件名，不含 .yml）
  pages: []

placeholders:
  enabled: true
  player-budget-ms: 2           # 每个玩家每次 tick 的软预算
  global-budget-ms: 10          # 全局软预算

textures:
  url:
    mode: client                # client | proxy | auto
    allowlist: []               # 仅 https；proxy/auto 的服务端拉取白名单，条目是 URL 前缀
    max-bytes: 8388608
    timeout-seconds: 10
    ttl-seconds: 3600           # 0 = 按内容哈希永久缓存
    cache-mb: 256               # 代理磁盘缓存上限，按最旧文件淘汰

state:
  persist: true                 # false = 状态只在本次在线期间存在
  backend: file                 # file | sqlite | mysql
  sqlite:
    file: state.db
  mysql:
    driver: com.mysql.cj.jdbc.Driver
    url: ""
    username: ""
    password: ""

actions:
  commands:
    enabled: false              # command 动作总开关
    allowlist: []               # 允许的命令首个词（不含前导斜杠，忽略大小写）

debug: false                    # 单玩家报文头日志
```

注意：`apply-on-join.pages` 留空时是"自动扫描常驻页"，单页可用
`surface_options.on_join: false` 退出；只有非空时才是白名单。代理缓存的磁盘目录固定为
`plugins/CloudCore/texture-cache/`，不由配置控制。

以下是**设计基线**里出现、但尚未接线的配置段（写进 `config.yml` 不会生效）：
`protocol.*`（通道名固定 `cloudcore:main`）、`ui.*`、`security.*`（限流硬编码为 20/s、突发 40，
与 `docs/07-security.md` 一致）、`textures.inline-enabled`/`max-texture-*`/`gif.*`、
`storage.*`（实际键是 `state.*`）、`diagnostics.*`。新增这些键前需要先实现读取与校验。

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
/cloudcore demo-huge [player]           发送约 830 KB 文档，验证多分片重组
/cloudcore demo-assets [player]         贴图与物品图标演示（local:/pack:/builtin:/item_icon/GIF）
/cloudcore demo-layout [player]         row/column/grid 布局演示
/cloudcore demo-scroll [player]         滚动容器与裁剪演示
/cloudcore demo-hud [player]            发送持久 HUD 文档，验证 HudElementRegistry 绘制
/cloudcore demo-hud-rpg [player]        下发 rpg_hud 页面（替换原版血条/饱食度/经验/快捷栏）
/cloudcore demo-hud-bg [player]         下发背景层 HUD 文档
/cloudcore demo-tooltip [player]        发送 Tooltip 皮肤文档，验证原生文本+自定义背景
/cloudcore demo-container [player]      发送容器文档并打开测试箱子，验证容器换皮
/cloudcore demo-readonly [player]       readonly 容器，验证点击/拖拽全部取消
/cloudcore demo-readonly-allow [player] readonly + allow_player_inventory，验证玩家背包可操作
/cloudcore demo-passthrough [player]    passthrough 容器，验证点击不被取消
/cloudcore demo-inventory [player]      下发背包皮肤；按 E 打开背包验证
/cloudcore demo-inventory-vanilla [player] 撤下背包皮肤，验证回到原版界面
/cloudcore demo-boss-multi [player]     下发 3 条哨兵 BossBar（spacing 2、max_bars 16），验证堆叠
/cloudcore probe [player]               打开测试箱子，验证 InventoryOpenEvent 时序（需配置）
/cloudcore boss [player]                发送带哨兵的测试 BossBar，验证客户端匹配
/cloudcore close [player]               关闭最近发送的测试 UI，验证服务端主动关闭
/cloudcore diagnostic [player]          下发一条测试 DIAGNOSTIC，验证客户端日志链路
/cloudcore page <名字> [player]         从 pages/ 加载 YAML 并下发
/cloudcore validate                     编译并校验全部页面，输出 [OK]/[FAIL]
/cloudcore reload                       重读 config.yml、重载页面并推送更新
/cloudcore state-export <file.json>     导出全部玩家页面状态到 plugins/CloudCore/exports/
/cloudcore state-import <file.json>     从导出文件导入；在线玩家保持内存状态，重进后生效
/cloudcore accessories [player]         打开 slots.yml 定义的自定义槽位页
/cloudcore equipment <asset> [player]   把装备资产写进主手物品的 equippable.model
/cloudcore equipment-stand <asset> [player] 生成穿戴该资产的盔甲架，便于预览
```

以上全部已实现，统一使用 `cloudcore.admin`；`/cloudcore` 带 Tab 补全（子命令、页面名、
`state-import` 的导出文件、玩家名）。

计划中的命令（尚未实现）：

```
/cloudcore list                         列出已加载页面、面板类型与修订号
/cloudcore stats                        计数器
/cloudcore debug <player> [on|off]      单玩家报文日志
/cloudcore texture push <player> <key> <file.png>
/cloudcore surfaces <player>            查看该玩家各面板的绑定状态
```

| 权限 | 默认 | 用途 |
| --- | --- | --- |
| `cloudcore.admin` | op | 当前全部命令（`plugin.yml` 里唯一声明的权限） |
| `cloudcore.command` | op | 基础命令（计划拆分） |
| `cloudcore.open` | op | 打开任意页面 |
| `cloudcore.page.<id>` | op | 打开指定页面 |
| `cloudcore.surface.<name>` | op | 使用指定面板 |
| `cloudcore.reload` | op | 重载 |
| `cloudcore.debug` | op | 调试输出 |
| `cloudcore.slot.<名字>` | op | `slots.yml` 里槽位声明的 `permission` |

页面级权限 `cloudcore.page.<页面 id>` 与页面 `permission:` 目前只解析进 `CompiledPage`，
**尚未在打开/复查时强制检查**；已强制的是动作级 `permission:`（缺权限整次点击 `REJECTED`）。
这是当前实现与设计基线的已知差距。

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

### 6.4 配置

`plugins/CloudCore/config.yml`：

```yaml
inventory-probe: false      # 打开后每次 InventoryOpenEvent 发一个 PING（排查容器时序）
pages:
  watch: true               # 页面文件变化后自动重载（400ms 合并）并推送更新
placeholders:
  enabled: true             # 安装了 PlaceholderAPI 时展开页面文本里的 %占位符%
  player-budget-ms: 2       # 每个玩家每 tick 的同步刷新软预算
  global-budget-ms: 10      # 全服每 tick 的同步刷新软预算
state:
  persist: true             # 会话状态按玩家持久化（异步写、原子替换）
  backend: file             # file | sqlite | mysql
actions:
  commands:
    enabled: false          # command 动作总开关
    allowlist: []           # 允许的命令首个词
```

### 6.5 页面命令

`/cloudcore validate`：遍历 `pages/`（`<目录>/page.yml` 与顶层 `*.yml`），逐页编译并报告
`[OK]`/`[FAIL] 名字: 原因`，不改动在线玩家界面。
`/cloudcore reload`：重新读取 `config.yml`，再重新编译所有页面，并给正在查看该页面的玩家推送更高
revision 的文档更新；会话状态保留（每个玩家按页面名保存）。
`/cloudcore state-export` / `state-import`：在当前 `file`/`sqlite`/`mysql` 后端之间导出、导入同一份
`{version,players}` JSON；导入按玩家整体替换，不做字段合并。两者都在异步线程执行。
`/cloudcore page <名字> [玩家]`：从 `plugins/CloudCore/pages/<名字>/page.yml`（或 `<名字>.yml`）
加载 YAML，经 `PageLoader` 组合与展开后下发 `UI_OPEN` + `UI_DEFINITION`。页面按自己声明的
`surface` 走第 7 节的表面路由，所以同一命令也能下发 HUD / Tooltip / Boss 血条 / 背包皮肤；
容器类页面由打开容器触发。
插件首次启动会把 12 张示例页写到 `pages/`（`example`、`shop`、`progress_demo`、`scroll_demo`、
`actions_demo`、`papi_demo`、`papi_refresh`、`tooltip_rpg`、`rpg_hud`、`inventory_skin`、
`slider_demo`、`boss_dnf`），已存在的文件不会被覆盖。

### 6.6 会话状态持久化

- `state.persist: true`（默认）时，每个玩家的页面状态保存到
  `plugins/CloudCore/state/<uuid>.json`：`{"version":1,"pages":{"<页面名>":{"键":值}}}`。
- 值只保存文档用到的类型：数字、布尔、字符串（其他类型按字符串保存，超长截断到 4096）；
  每个文件最多 64 页、每页 256 个键，读取时超过 1 MiB 或版本更高会拒绝并写警告日志。
- 写入在 worker 线程：`set_state` 只把会话标脏，后台任务每 5 秒（以及退出、插件禁用时）
  把脏会话原子写盘（先写 `.json.tmp` 再 `ATOMIC_MOVE`），主线程从不等磁盘。
- 玩家加入时异步读取并合并到会话：页面里已有的值不会被覆盖；读取失败只写警告，页面按文档默认值打开。
- `PlayerStateStore` 是接口：`file`（默认，`state/<uuid>.json`）、`sqlite`（驱动随插件打包，
  `state.sqlite.file`）、`mysql`（服务端需自带 JDBC 驱动，`state.mysql.*`）。SQL 后端把同一份
  `{version,pages}` JSON 存在 `cloudcore_player_state` 表，便于和文件实现互相迁移。SQL 后端还维护
  `cloudcore_schema` 表（当前 `SCHEMA_VERSION = 1`），启动时拒绝比本构建更新的 schema，为后续迁移留出入口。

## 7. 面板提供器

### 7.0 表面路由

页面在哪一层显示，由页面自己声明的 `surface` 决定，服务端不做逐表面的特判。
`CloudCorePlugin#openForSurface` 是唯一知道“每个表面怎么绑定”的地方：

| `surface` | BindMode | RenderMode | surfaceKey |
| --- | --- | --- | --- |
| `hud` | `PERSISTENT` | `SURFACE_NATIVE` | `surface_options.layer`（默认 `foreground`） |
| `tooltip` | `PERSISTENT` | `SURFACE_NATIVE` | 页面名 |
| `boss_bar` | `PERSISTENT` | `SURFACE_NATIVE` | 页面名 |
| `player_inventory` | `PERSISTENT` | `SCREEN` | 页面名 |
| `container` | `NEXT_CONTAINER` | `SCREEN` | 页面名 |
| `custom_screen` | `NONE` | `SCREEN` | 页面名 |

因此新增一个表面只需要在客户端实现 `SurfaceAdapter` 并在上表补一行，YAML 页面与
`apply-on-join` 都不需要改。`surface: hud` 的页面可额外用 `surface_options.replace`
取消原版血条/饱食度/经验条/快捷栏的绘制。

进服自动下发：无需配置名单。握手完成后 2 tick，服务端扫描 `pages/`，把所有
「常驻类」表面自动下发一次：

| 表面 | 进服是否自动下发 |
| --- | --- |
| `hud` | 是 |
| `player_inventory` | 是 |
| `tooltip` | 是 |
| `boss_bar` | 是 |
| `custom_screen` | 否，由命令或动作触发 |
| `container` | 否，由打开容器触发 |

单页退出：在页面的 `surface_options` 写 `on_join: false`。
需要固定名单时，`config.yml` 的 `apply-on-join.pages` 非空即覆盖自动扫描。

### 7.1 容器面板

```java
Inventory inv = Bukkit.createInventory(new CloudInventoryHolder(playerId, uiId, interaction,
        declaredSlots), rows * 9, title);
player.openInventory(inv);
```

`CloudInventoryHolder` 实现 `InventoryHolder`，`getInventory()` 返回实际物品栏，不能返回 `null`。

点击策略（`InventoryClickEvent`，`LOWEST`，`ignoreCancelled = true`）：

| 模式 | 行为 |
| --- | --- |
| `managed` | 除非原始槽位映射到已声明网格槽位，否则取消 |
| `readonly` | 全部取消，除非 `allow_player_inventory: true` |
| `passthrough` | 永不取消；旧界面适配使用（`/cloudcore demo-passthrough` 可验证） |

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

HUD 文档不允许声明槽位。状态更新与普通会话一致：重编译后优先发 `ELEMENT_UPDATE` 差异，
结构/几何变化才回退完整文档。

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
- 外框、填充、覆盖层贴图、九宫格、填充缩进、文字颜色/阴影/对齐、是否按事件颜色染色，定义在
  `surface_options.boss_bar.bar`。名称画在血条内部，哨兵会被剥掉。
- **多层血条是同一根条，不是多根**：哨兵带每层剩余强度
  `\u0000CCB:<token>:<p1>,<p2>,...\u0000<名称>`（每个 `p` 是该层剩余强度，千分比 0..1000）。
  客户端拿同一条 `BossBar` 由内向外逐层绘制：外层打空后露出内层，整条打空后才继续下一阶段。
  服务端阶段切换时只改哨兵里的数值与 `setProgress`，不要为每层另建 `BossBar`
  （那会变成屏幕上并排的多根条）。
- 每层的外观写在 `surface_options.boss_bar.bar.layers`，只写与 `bar` 不同的键（`tint` / `fill` /
  `name` / `text`），其余继承。`tint` 优先于 `BossEvent` 颜色。
- 预览：页面 `surface_options.boss_bar.demo_layers` 列出每层名称/进度/颜色，`demo_name` 给 Boss 名；
  `/cloudcore page <页面>` 会创建这条分层哨兵血条，并在 `demo_duration` 秒内把各层依次打空后移除。
  生产页面不要带这一段。
- 血条移除或玩家退出时发送 `UI_CLOSE`。

Boss 血条的 `surfaceKey` 是 16 进制 token，不是 Paper 的 `NamespacedKey`。

### 7.6 自定义槽位页

`plugins/CloudCore/slots.yml` 定义一组带规则的自定义槽位。它不是把原版玩家背包的槽位
硬塞进去，而是打开一个独立的 9×N 容器页；这样服务端能精确控制每个槽位的放置规则、权限
和持久化，也不会与其它改背包插件抢同一个 `InventoryMenu`。

```yaml
title: "Cloud Core 饰品"
rows: 3
slots:
  necklace:
    position: 10          # 0..rows*9-1
    label: "项链"
    accepts:
      lore-contains: ["项链", "Necklace"]
      materials: ["DIAMOND", "EMERALD"]   # 可选，按 Bukkit Material 名匹配
    permission: "cloudcore.slot.necklace" # 可选
  ring_left:
    position: 12
    label: "左戒"
    accepts:
      lore-contains: ["戒指", "Ring"]
```

打开方式：

```text
/cloudcore accessories [player]
```

规则：

- `accepts` 整个省略时，任意物品都可以放入该槽。
- `lore-contains` 匹配物品显示名或任意一行 lore，命中一个字符串即可。
- `materials` 与 `lore-contains` 是 AND 关系；权限是额外约束。
- 客户端不能绕过规则：点击、热键交换、副手交换和拖拽都会在 `LOWEST` 优先级检查。
- 从玩家背包按住 Shift 点击到受保护槽位会直接取消，因为 Bukkit 不提供目标槽位；这是
  有意的失败关闭（fail closed）。
- 关闭页面时内容写入 `plugins/CloudCore/accessories/<uuid>.yml`；下次打开自动恢复。
- 页面贴图默认使用内置 `builtin:slot` + `builtin:slot_glow` 图层，服务器不需要额外资源包；
  想换皮时在 `slots.yml` 之外替换页面生成器或使用自定义客户端资源即可。

### 7.7 装备资产

1.21.11 的盔甲外观可由数据驱动 `equipment` 资产描述（`assets/<namespace>/equipment/<path>.json`），
无需客户端混入。Cloud Core 只负责把资产 id 写进物品的 `equippable.model` 组件：

```text
/cloudcore equipment cloudcore:valkyrie [player]        # 写到主手物品
/cloudcore equipment-stand cloudcore:valkyrie [player]  # 生成穿戴该资产的盔甲架
```

- 资产 id 必须是命名空间形式（`cloudcore:valkyrie`）；非盔甲/不可穿戴物品返回 false 并回提示。
- 模组内置一个最小示例 `assets/cloudcore/equipment/valkyrie.json` 与两张贴图，仅用于回退演示；
  正式素材放资源包。
- 槽位按材料推断（头盔 → `HEAD`、胸甲/鞘翅 → `CHEST`、护腿 → `LEGS`、靴子 → `FEET`）。

## 8. 状态、遍历与差异

- 会话状态是 `Map<String, Object>`，值只允许 `String`、`Boolean`、`Integer`、`Long`、`Double`、
  `List`、`Map`。
- 模板求值时先展开 `repeat`：根据 `source` 列表生成实例，再对每个实例求值。
- 每个元素的已渲染属性保存在 `Map<String, Map<Property, WireValue>>`。
- 当前实现：变化后整页重编译，再用 `ElementDiffer` 与上一份已发送文档比较：
  文本/可见/启用/进度/物品等属性变化走 `ELEMENT_UPDATE`（更高 revision，空差异不发）；
  结构、几何、事件、字体或主题变化回退整份 `UI_DEFINITION`。等值写入不发送（`set_state` 保护）。
- `ELEMENT_UPDATE` 只对 `custom_screen` 与 `hud` 启用，其余表面整文档重发。
- 实例数量变化时，v1 提升修订号并下发完整文档；后续动作引擎版本改为增量增删元素。
- 客户端发送缓冲拥塞时丢弃更新；下一 tick 从权威状态重算，自动恢复。

### 8.1 占位符刷新

刷新策略写在页面 YAML 里（页面级默认 + 元素级覆盖），服务端配置只保留开关和预算：

```yaml
# 页面
placeholders:
  mode: interval          # static | interval | tick | manual | event
  interval_ticks: 20
  async: true             # 编译与展开在工作线程，diff/下发回主线程
# 元素
refresh: { mode: interval, ticks: 10 }
```

- 调度器每 tick 检查一次；到期就重编译整页、展开占位符，再用 `ELEMENT_UPDATE` 只发变化属性。
- 当前只对 `custom_screen` 与 `hud` 启用周期性/事件刷新；其他面板会记录警告并退回
  “打开/`set_state` 时解析”。
- 同步刷新受 `player-budget-ms`（默认 2）与 `global-budget-ms`（默认 10）软预算约束，超出的页面
  顺延到下一次到期。
- `async: true` 时编译与 PlaceholderAPI 展开在工作线程，结果回主线程 diff/下发；每页最多一个在途
  任务，期间 `set_state` 改了状态则丢弃旧快照。异步页面要求用到的 PlaceholderAPI 扩展线程安全；
  `async-namespaces` 白名单尚未实现。
- 内置 `refresh` 动作立即重编译并下发当前页，供 `manual` 页面使用。
- `%token%` 与 `${placeholder:token}` 两种写法等价，见 `03-ui-dsl.md` §7.1。
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
    if (event.getInventory().getHolder() instanceof CloudInventoryHolder) return;
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

已实现（软依赖，通过反射访问 `PlaceholderAPI.setPlaceholders`）：

- 页面发送（`/cloudcore page`、API `open`）与刷新（`set_state` 后重编译）时，把 `text`、
  `placeholder` 属性（含富文本 `runs[].text`）与文档标题里的 `%token%` 交给 PlaceholderAPI 展开。
- 开关 `placeholders.enabled`（默认 `true`）。没装 PlaceholderAPI 时页面照常工作，`%token%` 原样显示，
  日志写 `PlaceholderAPI not installed; %placeholders% stay as text`。
- 未知令牌保持原样；扩展抛异常只记一次警告，文本按未展开发送。颜色/尺寸/贴图引用不会被改写。
- 页面/元素可配 `placeholders.mode`（`static`/`interval`/`tick`/`event`/`manual`）做周期刷新，
  刷新时同样展开一次，再用 `ELEMENT_UPDATE` 只发差异。当前只对 `custom_screen` 与 `hud` 启用
  周期/事件刷新。
- `%token%` 与 `${placeholder:token}` 两种写法等价（`PlaceholderExpressions`）；后者支持附件插件
  注册的自定义命名空间。

## 13. 公共 Java API

已实现：`open(Player, String pageId[, OpenOptions])`、`open(Player, CompiledPage[, OpenOptions])`、
`registerActionHandler` / `unregisterActionHandler`、`registerPlaceholderResolver` /
`unregisterPlaceholderResolver`、`registerPageProvider` / `unregisterPageProvider`、
`setState`/`getState`、`registerSurfaceProvider`/`unregisterSurfaceProvider`、
`addEventListener`/`removeEventListener`、`isModded`（详见 `19-addon-api.md`）。
下面代码块里其余方法（`close`、`session`、`pushTexture`、`sendDiagnostic`、`reload`）是计划形态。

```java
public interface CloudCoreApi {
    OpenResult open(Player player, String pageId, OpenOptions options);
    boolean close(Player player, SurfaceType surface);
    Optional<UiSessionView> session(Player player, SurfaceType surface);

    void registerActionHandler(String name, ActionHandler handler);
    void registerPlaceholderResolver(String namespace, PlaceholderResolver resolver);
    void registerSurfaceProvider(SurfaceProvider provider);
    boolean unregisterSurfaceProvider(SurfaceType surface);
    <T> void addEventListener(Class<T> type, Consumer<? super T> listener);
    <T> boolean removeEventListener(Class<T> type, Consumer<? super T> listener);

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

`OpenOptions` 当前只有两个字段：`surface`、`bindMode`（`CloudCoreApi#open` 只接受
`custom_screen`，其他表面返回 `UNSUPPORTED_SURFACE`）。设计基线里的 `theme`、`state` 初始值、
`closeExisting` 尚未实现。

规则：

- `open` 从非主线程调用时返回 `OpenResult.SCHEDULED`，实际工作放到下一 tick。
- 玩家没装模组时返回 `OpenResult.NOT_MODDED`，不抛异常。
- 页面级 `permission:` 解析进 `CompiledPage`，但 API `open` 与 `/cloudcore page` 目前都不检查它。

## 14. 调度与 Folia

- 所有状态在主线程。
- ticker 是单个重复任务；每会话工作量有上限。
- 调度直接调用 `getServer().getScheduler()`（`runTask`/`runTaskLater`/`runTaskTimer`/
  `runTaskAsynchronously`），没有单独的桥接类；Folia 移植需要先把这些调用收敛到一个接缝。

## 15. 诊断

**尚未实现**：`/cloudcore stats` 与 `/cloudcore surfaces <player>`。设计中的输出形态：

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

当前可用的排障信息：服务端日志（`Rate limited EVENT`、`CANCELLED click ...`、`Unsupported action ...`、
`Updated page ... incrementally`、页面编译失败）、`/cloudcore validate`、客户端 `F3` 叠加层与
`logs/cloudcore-client.log`。调试模式记录帧头，不记录 body，也不记录输入框内容。

### 15.1 客户端诊断下发

- 页面编译失败、打开失败、动作异常、版本不匹配时发送 `DIAGNOSTIC`。
- 启动阶段没有客户端连接，错误只写服务端日志；玩家打开受影响页面时补发。
- `detail` 使用相对路径（`pages/shop/page.yml:12`），截断并去重限流。
- 客户端把诊断写入 `logs/cloudcore-client.log`，格式与客户端自身错误一致。
- 服务端日志保留完整信息；客户端日志是排障副本。
- 没有 `diagnostics.send-client-details` 开关（该配置段尚未接线），当前总会下发。
- `/cloudcore diagnostics <player>` 可发一条测试诊断，验证链路。
- 审计和隐私规则见 `18-diagnostics.md`。
