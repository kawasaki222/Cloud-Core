# 02 — 线上协议

版本：**1.1**（协议主版本 1，次版本 1）。

1.1 相对 1.0 的变化：`UI_OPEN` 增加面板类型与 `surfaceKey`；新增 `PERSISTENT` 绑定模式；
新增面板相关特性位与限额；明确 Tooltip / Boss 血条的数据来源；新增 `TEXTURE_REF`、动画贴图字段。
1.1 已实现并在真实 Paper + Fabric 客户端联调；次版本内只追加字段，不改变既有语义。

`ELEMENT_UPDATE`（`0x14`）、`TEXTURE_REQUEST`（`0x84`）、`TEXTURE_PUT`（`0x28`）是 1.1 内的追加报文，
客户端与服务端都已实现；`NOTIFICATION`（`0x16`）、`THEME_SET`（`0x2A`）、`SERVER_CONFIG`（`0x31`）、
`CLIENT_ERROR`（`0x86`）、`METRICS`（`0x87`）、`SCREEN_PROBE`（`0x88`）只有操作码与字段定义，
收发两端均未接线。

## 1. 传输

Cloud Core 只使用一条 Minecraft 插件消息通道：

```
cloudcore:main
```

- 通道必须使用命名空间键格式（`namespace:path`，全小写）。Paper 会拒绝其他格式。
- Paper 侧：必须在 `onEnable` 期间同时调用 `Messenger#registerOutgoingPluginChannel` 和
  `registerIncomingPluginChannel`，否则 `sendPluginMessage` 会抛 `ChannelNotRegisteredException`。
- Paper 会把载荷序列化成 `ClientboundCustomPayloadPacket`，内部是携带通道 id 和原始字节的
  `DiscardedPayload`。线上字节与本文档定义的完全一致。
- Fabric 客户端注册同 id 的 `CustomPacketPayload.Type`，其 `StreamCodec` 读写剩余的全部字节。
- 客户端 → 服务端：Fabric 写出同一个信封；Paper 以
  `onPluginMessageReceived("cloudcore:main", player, byte[])` 交付。

> ⚠️ 两条承重假设，M1 完成前必须用集成测试证明：(a) Paper 的 `DiscardedPayload` 字节能原样到达
> Fabric 注册的 payload 类型；(b) 在 `InventoryOpenEvent` 中写出的 `UI_OPEN` 早于 `OpenScreen`
> 报文到达。详见 `10-risks-and-verification.md`。

### 1.1 大小上限

| 方向 | 硬上限 | 本项目上限 |
| --- | --- | --- |
| S2C 报文（客户端自定义载荷） | 1,048,576 字节 | 逻辑帧 ≤ 1,048,576 字节 |
| S2C 分片 | — | 分片载荷 24,000 字节 |
| C2S 报文（服务端自定义载荷） | 32,767 字节 | 逻辑帧 ≤ 32,767 字节 |
| Paper `Messenger.MAX_MESSAGE_SIZE` | 1,048,576 字节 | 同上 |
| 解压后的逻辑帧 | — | 1,048,576 字节 |
| 字符串 | — | 32,767 字节 UTF-8 |
| UI 文档（未压缩 JSON） | — | 1,048,576 字节 |
| 遍历实例总数（单文档） | — | 256 |
| Tooltip 行数 | — | 256 |
| 动画贴图帧数 | — | 64 |
| 动画贴图解码后总大小 | — | 16 MiB |
| URL 贴图（客户端直连或服务端代理） | — | 8 MiB，10 秒超时，不跟随重定向；仅 https/443 |
| 客户端图片缓存 | — | 磁盘 128 MiB（`imageCacheMiB`，可配 1–1024） |
| 占位符解析预算 | — | 每玩家 2 ms/tick，全局 10 ms/tick（软预算，超出的页面顺延） |
| `DIAGNOSTIC.detail` | — | 4 KiB，10 条/秒，10 秒去重 |

客户端发送的一切都必须小：事件和确认只有几十字节。大块数据永远走 S2C。

## 2. 信封

每个逻辑报文都是一个帧：

```
+0   u16    magic        0x4343（'C','C'）
+2   u8     version      协议主版本（1）
+3   u8     flags        bit0 COMPRESSED，bit1 FRAGMENT
+4   varint opcode
+5   varint bodyLength   本帧 body 长度（若压缩，则为解压后的长度）
+..  bytes  body
```

- 多字节整数**大端**，除显式标注的 `varint`。
- `varint` 是 Minecraft VarInt：每字节 7 个数据位，最高位为续位，最多 5 字节。
- `string` = `varint 字节长度` + UTF-8 字节。
- `bytes` = `varint 长度` + 原始字节。
- `bool` = `u8`（0 或 1）。
- `magic != 0x4343` 的帧记录一次并忽略，绝不因此断开连接。

### 2.1 标志位

| 位 | 名称 | 含义 |
| --- | --- | --- |
| 0 | `COMPRESSED` | body 是原始 DEFLATE |
| 1 | `FRAGMENT` | body 是分片头加分片数据 |
| 2–7 | 保留 | 必须为零 |

### 2.2 压缩

- `bodyLength >= 256` 且操作码允许时启用。
- 原始 DEFLATE：`new Deflater(Deflater.DEFAULT_COMPRESSION, true)`。
- 解压输出上限 1,048,576 字节，超过立即中止。
- 头部 `bodyLength` 是解压后的长度。
- 压缩按逻辑报文进行，在分片之前应用。

### 2.3 分片

```
body（flags |= FRAGMENT）:
  u16   fragmentId
  u8    index
  u8    count
  varint totalBodyLength
  bytes chunk
```

- `totalBodyLength` 是逻辑报文解压后的长度；`chunk` 是分片数据，`bytes` 自带 varint 长度。
- FRAGMENT 帧信封头里的 `bodyLength` 是**分片体长度**，不是逻辑长度；逻辑长度以
  `totalBodyLength` 为准。
- 压缩在分片之前完成：所有分片设置 `COMPRESSED` 时，各 `chunk` 依次拼接成一条原始
  DEFLATE 流，**不是**每个分片单独一条压缩流；重组完成后统一解压并校验 `totalBodyLength`。
- 分片标志必须一致；同一组内 `COMPRESSED`、`count`、`totalBodyLength`、操作码不一致即中止。
- 每方向最多 4 个并发分片组，每组从首片起 5 秒超时。
- 接受乱序；重复索引内容相同则忽略，内容不同则中止该组。
- `count` 至少为 2；单分片报文不得设置 `FRAGMENT`。
- 重组后总长度超过 1 MiB 中止；压缩流的临时拼接长度允许少量 DEFLATE 膨胀余量。

示例：`fragmentId=0x1234`，8 字节 body 拆成 2 片，每片 4 字节（未压缩）：

```
第一片:
43 43 01 02 10 0a
12 34 00 02 08 04 01 02 03 04
第二片:
43 43 01 02 10 0a
12 34 01 02 08 04 05 06 07 08
```

`0x10` 是 `UI_OPEN`，`0x02` 是 `FRAGMENT`，`0x0a` 是分片体长度。

## 3. 面板类型

`SurfaceType`（`u8`）：

| 值 | 名称 | 生命周期 | `surfaceKey` 含义 |
| --- | --- | --- | --- |
| `0` | `CUSTOM_SCREEN` | 临时 | 文档 id |
| `1` | `CONTAINER` | 临时 | 容器类型名或会话键 |
| `2` | `PLAYER_INVENTORY` | 持久 | `default` |
| `3` | `HUD` | 持久 | 层 id（`background` / `foreground` / `overlay`） |
| `4` | `TOOLTIP` | 持久 | `default` 或物品分组键 |
| `5` | `BOSS_BAR` | 附加 | 哨兵令牌（16 进制） |
| `6–127` | 保留 | — | 后续面板 |
| `128–255` | 实验 | — | 不进入正式版本 |

客户端遇到未知 `surface` 值时不丢弃整帧，而是保留原始 `u8` 并在 `UI_READY(surface=原值,
state=4)` 中回传；`cloudcore-api` 内部用 `SurfaceType.UNKNOWN` 表示，不能当作可打开面板。
`BindMode` / `RenderMode` 的未知值同样不丢弃整帧：解码为 `UNKNOWN(255)`，客户端回
`UI_READY(surface=原值, state=4)` 并在日志里记录原始行为，服务端据此知道该客户端版本过旧。

`BindMode`（`u8`）：

| 值 | 名称 | 含义 |
| --- | --- | --- |
| `0` | `NONE` | 独立界面，不绑定容器 |
| `1` | `NEXT_CONTAINER` | 下一个 `AbstractContainerScreen` 归我们 |
| `2` | `CURRENT_CONTAINER` | 替换当前已打开的容器界面 |
| `3` | `PERSISTENT` | 持久会话，不随单次打开结束 |
| `255` | `UNKNOWN` | 本客户端不认识的绑定方式；拒绝打开并回 `UI_READY(state=4)` |

`RenderMode`（`u8`）：

| 值 | 含义 |
| --- | --- |
| `0` | `SCREEN` —— 整屏替换 |
| `1` | `OVERLAY` —— 绘制在当前界面之上 |
| `2` | `REPLACE_CURRENT` —— 替换当前任意界面 |
| `3` | `SURFACE_NATIVE` —— 由面板适配器决定绘制位置（HUD/Tooltip/Boss） |
| `255` | `UNKNOWN` —— 本客户端不认识；拒绝打开并回 `UI_READY(state=4)` |

`UI_OPEN.flags`：

| 位 | 含义 |
| --- | --- |
| 0 | `VANILLA_FALLBACK` —— 客户端无法承载时保留原版 |
| 1 | `ALLOW_CLOSE_KEY` —— Escape 可关闭界面 |
| 2 | `REPLACE_EXISTING` —— 同一 `surfaceKey` 已有会话时替换 |
| 3 | `SILENT_FAILURE` —— 失败时不向玩家发聊天提示 |

## 4. 特性位

`HELLO.features` 与 `HELLO_ACK.acceptedFeatures`：

| 位 | 能力 |
| --- | --- |
| 0 | 内联贴图（`TEXTURE_PUT`） |
| 1 | HUD 面板 |
| 2 | 玩家背包面板 |
| 3 | Tooltip 面板（样式） |
| 4 | Tooltip 滚动 |
| 5 | Boss 血条面板 |
| 6 | 自定义字体令牌 |
| 7 | 页面组合（服务端能力，仅信息位） |
| 8 | 遍历展开（客户端接收展开结果，仅信息位） |
| 9 | 动作引擎 v2（见 `13-action-engine.md`） |
| 10 | 动画贴图（GIF 转精灵表） |
| 11 | URL 贴图（`TEXTURE_REF`） |
| 12 | 占位符定时刷新（服务端能力，仅信息位） |
| 13 | 客户端直连 URL 下载 |
| 14 | 客户端本地 GIF 解码 |
| 15 | NanoVG 渲染后端（信息位；不影响文档语义） |

只有双方都置位的特性才会使用。

## 5. 报文目录

### 5.1 服务端 → 客户端

| 操作码 | 名称 | Body |
| --- | --- | --- |
| `0x01` | `HELLO_ACK` | 见下 |
| `0x10` | `UI_OPEN` | 见下 |
| `0x11` | `UI_CLOSE` | `string uiId, u8 reason` |
| `0x12` | `UI_DEFINITION` | `string uiId, varint revision, u8 encoding, bytes data` |
| `0x14` | `ELEMENT_UPDATE` | `string uiId, varint revision, varint count, entry[count]`（已实现，`ElementUpdateCodec`） |
| `0x15` | `EVENT_RESULT` | `varint requestId, u8 status, string message`（已实现，`EventResultCodec`） |
| `0x16` | `NOTIFICATION` | `u8 kind, string title, string body, varint durationMs`（未实现） |
| `0x17` | `DIAGNOSTIC` | `u8 severity, string code, u32 traceId, string scope, string message, string detail`（已实现，`DiagnosticCodec`） |
| `0x28` | `TEXTURE_PUT` | `string key, u16 width, u16 height, u8 format, u32 crc32, bytes data, u16 frameCount, u8 loop, u16[] frameDelaysMs`（已实现，`TexturePutCodec`） |
| `0x29` | `TEXTURE_RELEASE` | `string key`（未实现） |
| `0x2A` | `THEME_SET` | `string themeId, u8 encoding, bytes json`（未实现） |
| `0x2B` | `TEXTURE_REF` | `string key, string url, string sha256, u16 width, u16 height, u8 flags, u32 maxBytes, u16 ttlSeconds`（已实现，`TextureRefCodec`） |
| `0x30` | `PING` | `u32 nonce, u64 serverTimeMs` |
| `0x31` | `SERVER_CONFIG` | `u8 flags, u8 debugLevel, string language`（未实现） |

`NOTIFICATION.kind`：`0` 信息，`1` 成功，`2` 警告，`3` 错误。

`DIAGNOSTIC.severity`：`0` info，`1` warn，`2` error，`3` fatal。`code` 是 `CC-*` 字符串；
`scope` 为 `page` / `ui` / `asset` / `action` / `render`。`detail` 使用相对路径，≤ 4 KiB；同一
`(code, scope, message)` 10 秒内去重，只计数。客户端写入 `logs/cloudcore-client.log`，不改变会话状态。

`UI_DEFINITION.data` 是 `16-ui-document-schema.md` 定义的 JSON。它不包含 `state`、动作体、`repeat`、
`includes` 或原始 URL。

`UI_DEFINITION.encoding` / `THEME_SET.encoding`：

| 值 | 含义 |
| --- | --- |
| `0` | UTF-8 JSON |
| `1` | UTF-8 JSON，外层再用信封压缩 |
| `2–255` | 保留 |

`UI_OPEN` body：

```
string uiId
varint revision
u8     surface
u8     bindMode
u32    token
u8     renderMode
u8     flags
string surfaceKey
```

`HELLO_ACK` body：

```
u16 protoMajor, u16 protoMinor
u16 acceptedFeatures
string serverId
u32 maxFrameBytes
varint compressionThreshold
string serverMessage
```

`UI_CLOSE.reason`：

| 值 | 含义 |
| --- | --- |
| `0` | 服务端正常关闭 |
| `1` | 权限被撤销 |
| `2` | 被另一个界面替换 |
| `3` | 内部错误 |
| `4` | 服务端关闭 |
| `5` | 面板类型不再受支持（客户端降级） |

`EVENT_RESULT.status`：

| 值 | 名称 | 含义 |
| --- | --- | --- |
| `0` | `OK` | 已执行 |
| `1` | `REJECTED` | 通用拒绝 |
| `2` | `INTERNAL_ERROR` | 处理器异常 |
| `3` | `RATE_LIMITED` | 超出限流 |
| `4` | `STALE_REVISION` | 修订号过期 |
| `5` | `UNKNOWN_ELEMENT` | 元素/动作不存在 |
| `6` | `NO_PERMISSION` | 权限不足 |
| `7` | `COOLDOWN` | 冷却中 |
| `8` | `BUSY` | 会话忙/已有执行 |
| `9` | `INVALID_PAYLOAD` | 载荷类型或大小不合法 |

`message` 是固定枚举名，不携带内部细节或自由文本。

`ELEMENT_UPDATE` 条目：

```
string elementId
u8     propertyId
WireValue value
```

| 属性 id | 名称 | 接受的 WireValue |
| --- | --- | --- |
| `1` | `text` | string 或 TextRuns map |
| `2` | `visible` | bool |
| `3` | `enabled` | bool |
| `4` | `texture` | string |
| `5` | `item` | map |
| `6` | `progress` | f32（进度条的兼容别名，客户端写入 `value`） |
| `7` | `value` | WireValue |
| `8` | `tooltip` | string |
| `9` | `style_override` | map |
| `10` | `selected` | bool |
| `11` | `invalid` | bool（输入框错误态） |
| `12` | `checked` | bool（复选框） |
| `13` | `scroll_y` | number |
| `14` | `color` | string |
| `15` | `placeholder` | string |

`count` 上限是 `Limits.maxWireEntries`（当前 4096）；编码后 body 仍受 `maxDecompressedBytes` 约束。
`revision` 必须大于 0。`entries` 为空是合法的 no-op，但服务端不会发送空包。

服务端只在 `CUSTOM_SCREEN` 与 `HUD` 上尝试增量：文档结构、元素顺序、id、几何、`z`、事件、
子元素数量、标题/主题/尺寸/字体等文档级字段发生变化时回退完整 `UI_DEFINITION`；任何
`text_field` 的属性变化也回退，因为输入框的文字、选区和焦点在客户端控件里，不能只改文档。
没有变化时不发包（revision 也不递增）。

客户端应用规则：

- 只接受 `revision == current + 1`。更旧的是 stale，跳号的是 gap，两种情况都丢弃当前包并等待
  下一次完整 `UI_DEFINITION`；不会把半套更新应用到界面上。
- 未知 `elementId`、未知 `propertyId` 或应用后校验失败会整包拒绝并记录 `CC-UPDATE-003`。
- 客户端适配器不支持原地更新时记录 `CC-UPDATE-004`；容器/背包/tooltip 仍走完整文档。
- 属性 `1` 的 string 值写 `text`，非 string（富文本 run 数组）写 `runs`；`NullValue` 表示删除该属性。

HUD 的层由 `UI_OPEN.surfaceKey` 表示：`background`、`foreground`、`overlay`。HUD 文档同样使用
`UI_DEFINITION` / `ELEMENT_UPDATE` / `UI_CLOSE`，不单独定义一套报文。

`TEXTURE_PUT.format`：

| 值 | 含义 | `data` |
| --- | --- | --- |
| `1` | 静态 PNG | 单帧 PNG |
| `2` | 动画贴图 | 垂直精灵表 PNG，高度 = `height * frameCount` |

- 静态贴图 `frameCount = 1`，`frameDelaysMs` 为空。
- 动画贴图 `frameCount` 为 1..64，`loop = 0` 表示无限循环，1..255 表示播放次数。
- 帧延迟由服务端从 GIF 读取并归一化；小于 50 ms 的延迟夹到 50 ms。
- 客户端按 `frameDelaysMs` 轮播，在精灵表内按帧偏移 UV。
- `u16[]` 编码为 `varint 数量` + `u16[数量]`；静态贴图的数组长度为 0。
- 编码后的精灵表仍受单帧 1 MiB 上限约束；超出时服务端降采样或减帧，否则拒绝该贴图。

`TEXTURE_REF`：

```
string key
string url          # 只允许 https
string sha256       # 空表示未知；非空时客户端必须校验
u16    width        # 0 表示未知
u16    height
u8     flags        # bit0 animated, bit1 sha256 present
u32    maxBytes
u16    ttlSeconds   # 0 表示按内容哈希永久缓存
```

- 这是 URL 贴图的**默认**下发方式：服务端只发引用，不发送图片字节。
- 客户端按 `sha256` 查本地缓存；命中则直接使用。
- 未命中且客户端允许直连时，异步下载、校验、解码、缓存；图片流量不经过游戏连接。
- 未命中且客户端禁止直连、或下载失败时，客户端发送 `TEXTURE_REQUEST(key)` 请求服务端代理；
  服务端按 `textures.url.mode` 决定是否回复 `TEXTURE_PUT`。
- 服务端代理模式下不发 `TEXTURE_REF`，直接发 `TEXTURE_PUT`。
- 当前实现（客户端直连）：`PageLoader` 把 `url:` 改写成 `inline:<sha1 前 16 位>`，插件在 `UI_OPEN` 后
  发 `TEXTURE_REF`；客户端 `RemoteTextureCache` 后台下载（https、≤ maxBytes、10 秒超时、
  sha256 非空时校验），渲染线程注册 `DynamicTexture` 并在下载完成后重建渲染列表。
  `TEXTURE_REQUEST`/`TEXTURE_PUT` 代理路径已实现：`textures.url.mode=proxy` 时打开页面直接
  `TEXTURE_PUT`；`auto` 时先发 `TEXTURE_REF`，客户端直连失败或 `remoteImages=false` 才请求代理。
  代理只允许配置中的 https 前缀，服务端解码为 PNG；GIF 转成垂直精灵表。
- `ttlSeconds` 只影响 URL 缓存；内容哈希命中的缓存不因 TTL 失效。

### 5.2 客户端 → 服务端

| 操作码 | 名称 | Body |
| --- | --- | --- |
| `0x80` | `HELLO` | `u16 protoMajor, u16 protoMinor, string modVersion, u16 features, string locale, u16 guiScale, u16 scaledWidth, u16 scaledHeight` |
| `0x81` | `UI_READY` | `string uiId, varint revision, u32 token, u8 surface, u8 state, string detail` |
| `0x82` | `EVENT` | `varint requestId, string uiId, string elementId, u16 actionId, varint payloadLen, WireValue payload`（已实现，`EventCodec`） |
| `0x83` | `UI_CLOSED` | `string uiId, u8 reason` |
| `0x84` | `TEXTURE_REQUEST` | `string key`（请求服务端代理或重发内联贴图）（已实现，`TextureRequestCodec`） |
| `0x85` | `PONG` | `u32 nonce, u64 clientTimeMs` |
| `0x86` | `CLIENT_ERROR` | `u16 code, string uiId, string detail`（未实现） |
| `0x87` | `METRICS` | `u16 fps, u16 avgFrameMicros, varint elementCount, varint packetCount`（未实现） |
| `0x88` | `SCREEN_PROBE` | `string screenClass, i32 menuId, string title, i32 slotCount`（未实现） |

`UI_READY.state`：`0` 正常，`1` 文档缺失，`2` 文档无效，`3` 绑定失败，
`4` 面板类型不支持。

`UI_CLOSED.reason`：`0` 玩家关闭，`1` 服务端关闭，`2` 容器被外部关闭，`3` 客户端错误，
`4` 被其他模组替换。

`CLIENT_ERROR.code`：

| 码 | 含义 |
| --- | --- |
| `1` | 畸形帧 |
| `2` | 未知操作码 |
| `3` | 文档解析失败 |
| `4` | 更新引用了未知元素 |
| `5` | 不支持的协议版本 |
| `6` | 贴图解码失败 |
| `7` | 客户端内部异常 |
| `8` | 不支持的面板类型 |
| `9` | 面板钩子安装失败 |
| `10` | 字体缺失 |

`SCREEN_PROBE` 仅用于诊断和兜底：`title` 截断到 64 字节并去除格式码，绝不用于授权判断。

### 5.3 会话状态与合法报文

| 会话状态 | 允许的 C2S | 允许的 S2C |
| --- | --- | --- |
| `CREATED` | — | `UI_OPEN` / `UI_DEFINITION` / `UI_CLOSE` |
| `BOUND` | `UI_READY` / `TEXTURE_REQUEST` / `PONG` / `CLIENT_ERROR` | `UI_DEFINITION` / `UI_CLOSE` / `DIAGNOSTIC` |
| `ACTIVE` | `EVENT` / `UI_CLOSED` / `TEXTURE_REQUEST` / `PONG` / `CLIENT_ERROR` / `METRICS` | `ELEMENT_UPDATE` / `EVENT_RESULT` / `NOTIFICATION` / `DIAGNOSTIC` / `UI_CLOSE` / `TEXTURE_*` / `THEME_SET` |
| `CLOSING` / `CLOSED` | — | — |

不属于当前状态的报文丢弃并计数，不改变会话状态。握手（`HELLO`/`HELLO_ACK`）在所有会话之外。

## 6. 类型化值（`WireValue`）

| 标签 | 类型 | 编码 |
| --- | --- | --- |
| `0x00` | null | — |
| `0x01` | boolean | `u8` |
| `0x02` | int32 | zig-zag varint |
| `0x03` | int64 | zig-zag varint（最多 10 字节） |
| `0x04` | float32 | 4 字节 IEEE-754 大端 |
| `0x05` | float64 | 8 字节 IEEE-754 大端 |
| `0x06` | string | `varint 长度` + UTF-8 |
| `0x07` | list | `varint 数量` + 元素 |
| `0x08` | map | `varint 数量` + 键值对 |

深度上限 16，元素总数上限 4096，解码后总大小上限 32,768 字节。违反者中止报文。

## 7. 事件

`EVENT.payload` 是 `WireValue` map。约定键：

| 键 | 类型 | 产生者 |
| --- | --- | --- |
| `value` | string | 输入框、下拉框 |
| `checked` | boolean | 复选框 |
| `index` | int32 | 列表、遍历项、标签页 |
| `amount` | float32 | 滑块 |
| `scroll` | float32 | 滚动容器、Tooltip |
| `button` | int32 | 点击 |
| `shift` | boolean | 修饰键 |
| `drag` | map | 拖拽结束 |
| `key` | int32 | 按键 |

`actionId` 由服务端编译时分配，由元素 id + 事件名派生，保存于文档。重排 YAML 不会改变它。
实现：`PageLoader` 用 `hash(pageId|elementId|event) mod 65534 + 1` 生成，冲突时顺延；动作体只留在服务端
（`CompiledPage.actions()`），文档里只有 `events: {click: actionId}`。
客户端点击命中元素后用文档里的 actionId 发 `EVENT`，payload 至少含 `button`、`shift`。
服务端当前执行 `message`、`close_ui`、`set_state`；执行后回 `EVENT_RESULT`
（未知 action 回 `UNKNOWN_ELEMENT`，动作不支持回 `REJECTED`），`message` 只用固定枚举名。

`requestId` 客户端单调递增，每条连接从 1 开始。1 秒内重复的 `requestId` 返回缓存结果。

Tooltip 的滚轮事件默认**不**上报服务端；只有文档显式声明 `sync_scroll: true` 时才发送
`EVENT(elementId="tooltip", actionId=…)`，并受限流。

## 8. 版本与协商

- 主版本变化即不兼容。
- 次版本变化是增量的：新增操作码、body 末尾追加字段、新增特性位。
- 面板类型是 `u8` 枚举，新增类型必须同时出现在双方枚举中；客户端不认识的面板返回
  `UI_READY(state=4)`，服务端回退原版。
- 客户端必须先声明支持的面板特性，服务端才下发对应 `UI_OPEN`。

## 9. 示例

`HELLO`：协议 1.1，模组版本 `0.1.0`，特性位 `0x000F`（内联贴图、HUD、背包、Tooltip），
语言 `zh_cn`，GUI 缩放 2，缩放后尺寸 640×360：

```
43 43            magic
01               协议主版本 1
00               标志位
80 01            操作码 0x80（128）
18               body 长度 24
00 01 00 01      protoMajor=1, protoMinor=1
05 30 2e 31 2e 30   "0.1.0"
00 0f            features
05 7a 68 5f 63 6e   "zh_cn"
00 02 02 80 01 68   guiScale=2, 640×360
```

整帧 31 字节：7 字节头部加 24 字节 body。

## 10. 实现说明

- 编解码器位于 `cloudcore-protocol`，不依赖任何 Minecraft 类型。
- 客户端用 `ByteSource`/`ByteSink` 包装 `RegistryFriendlyByteBuf`；服务端包装 `byte[]`。
- 写入：body → 压缩 → 分片。读取：每一步都受剩余帧大小和类型上限约束，先检查再分配。
- 分片重组在协议层完成；上层只看到解压后的逻辑帧。畸形分片只丢弃该组并计数，不断连。
- `fragmentId` 是 `u16` 随机值；与在途组冲突时拒绝新组。
- 服务端在收到 `HELLO` 后至少延迟 1 tick 再发 `HELLO_ACK`。实测同一 tick 内发送时，Fabric
  客户端刚在 `JOIN` 回调里发出 `HELLO`，其 S2C 接收器尚未完全就绪，ACK 会丢失；延迟 1 tick
  后稳定送达。这是传输实现约束，不改变线上格式。
- 计数器（`unknown_opcode`、`bad_fragment`、`crc_fail`、`rate_limited`、`surface_unsupported`）
  `/cloudcore stats` 输出 `ServerMetrics` 的聚合计数（帧、字节、限流、拒绝、贴图、绑定、刷新）；
  客户端侧只有未知操作码日志与 `UI_READY(state=4)`，F3 叠加层显示活动文档而非计数器。
