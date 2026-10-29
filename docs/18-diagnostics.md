# 18 — 日志与诊断

目标：**所有配置错误、协议错误、资源错误、渲染回退、动作拒绝，都能在客户端日志里定位到原因**。
客户端日志是给插件作者和服务器管理员排障用的，不是给玩家看的。

## 1. 范围

- 客户端自己的错误：配置、文档解析、协议、会话、绑定、渲染、字体、贴图、URL、GIF、输入、动作结果。
- 服务端配置/页面错误：服务端判定后通过 `DIAGNOSTIC` 下发摘要，客户端写入同一份日志。
- 不负责：资源包/页面分发、启动器/自动更新。这些由服务器或插件作者自己的分发体系处理。

## 2. 原则

1. **在错误源头记录一次**，不要每层重复打印。
2. **稳定错误码**：`CC-<AREA>-<NNN>`，文档和日志共用，便于搜索。
3. **英文日志消息 + 结构化字段**；玩家可见文本走本地化。
4. **上下文齐全**：客户端版本、协议版本、服务端标识、uiId、surface、revision、elementId、
   actionId、traceId。
5. **ERROR 带完整堆栈**；INFO/DEBUG 只带摘要。
6. **去重与限流**：同一错误 10 秒内只完整输出一次，后续只加计数。
7. **默认脱敏**：不记录输入内容、令牌、完整 URL、玩家 IP、绝对服务端路径。
8. **日志不阻塞渲染线程**：异步队列，队列满时丢弃并计数。

## 3. 日志文件与格式

- 文件：`logs/cloudcore-client.log`。
- 轮转：默认 10 MiB × 5 份；可配置。
- 控制台同步输出，便于开发。
- 启动时写 banner：客户端版本、协议版本、Minecraft/Fabric/NanoVG 版本、渲染后端、FBO 尺寸、
  字体数量、资源根目录、日志级别。
- `-Dcloudcore.diagnostics=true` 把界面切换与滚轮观察日志提升到 INFO；默认只在 DEBUG 输出，
  避免正常游戏刷屏。U2/U12 复现时使用该开关。
- `-Dcloudcore.nanovg.spike=true` 在首次打开 `CloudScreen` 时运行 Windows NanoVG FBO spike，
  写入 `NanoVG spike: fbo texture=... pixel=...`。

格式：

```
2026-10-26T12:34:56.789Z ERROR [session/shop#3] [CC-DOC-003] document validation failed
  uiId=shop surface=container rev=7 field=elements[2].text expected=TextRuns actual=string
  trace=8f3a21
  java.lang.IllegalStateException: ...
    at com.cloudcore.client...
```

字段顺序固定，便于 grep：

`时间 级别 [类别/上下文] [错误码] 消息`，下一行缩进 `key=value`，再下一行堆栈。

## 4. 级别与类别

| 级别 | 用途 |
| --- | --- |
| `ERROR` | 功能不可用、回退、解析失败、异常 |
| `WARN` | 可恢复异常、资源缺失、预算超限、回退 |
| `INFO` | 启动、握手、会话开闭、后端选择 |
| `DEBUG` | 报文头、绑定、布局失效、资源缓存命中 |
| `TRACE` | 逐帧/逐元素，仅开发 |

类别：

`boot`、`config`、`net`、`protocol`、`doc`、`theme`、`font`、`texture`、`url`、`gif`、
`surface`、`bind`、`render`、`fbo`、`input`、`action`、`perf`、`security`、`dump`。

## 5. 错误码

| 前缀 | 范围 |
| --- | --- |
| `CC-BOOT-*` | 启动、原生库、版本 |
| `CC-CFG-*` | 客户端配置 |
| `CC-NET-*` | 连接、握手、分片 |
| `CC-PROTO-*` | 操作码、字段、限额 |
| `CC-DOC-*` | 文档解析、schema、校验 |
| `CC-THEME-*` | 主题、令牌 |
| `CC-FONT-*` | 字体加载、回退 |
| `CC-TEX-*` | 贴图加载、缺失、解码 |
| `CC-URL-*` | 远程图片、超时、允许列表、哈希 |
| `CC-GIF-*` | 动画帧、尺寸、延迟 |
| `CC-SURF-*` | 面板不支持、钩子失败 |
| `CC-BIND-*` | 预绑定、超时、令牌 |
| `CC-RENDER-*` | NanoVG/原生绘制异常 |
| `CC-FBO-*` | FBO 创建、合成、回退 |
| `CC-INPUT-*` | 输入路由、焦点、IME |
| `CC-UPDATE-*` | `ELEMENT_UPDATE` 顺序、元素、适配器、校验 |
| `CC-ACT-*` | 动作拒绝、冷却、权限、异常 |
| `CC-PERF-*` | 帧预算、缓存、限流 |
| `CC-SEC-*` | 脱敏、越界路径、非法 URL |

示例：

- `CC-DOC-003` 文档 schema 校验失败。
- `CC-DOC-007` 元素 id 重复。
- `CC-DOC-022` `progress.direction` 未知，按 `left` 绘制。
- `CC-DOC-023` `slider.value` 超出 `min..max`（默认 `0..1`），已钳制。
- `CC-DOC-024` 未知 `text_field.filter`，放行输入。
- `CC-DOC-025` `button`/`icon_button` 的 `icon` 类型不对，图标未绘制。
- `CC-URL-002` 非 https 或主机不在允许列表。
- `CC-URL-004` 下载超时。
- `CC-URL-006` SHA-256 不匹配。
- `CC-GIF-003` 帧数/尺寸超限。
- `CC-FBO-002` FBO 创建失败，已回退原生。
- `CC-FONT-004` 字体缺失，已回退。
- `CC-ACT-005` 动作冷却中。
- `CC-BIND-001` 绑定超时，已回退原版。
- `CC-INPUT-001` `text_field` 的 `filter: regex:...` 正则无效，已放行输入。
- `CC-INPUT-002` 未知的 `filter` 值，已放行输入并保留日志。
- `CC-UPDATE-002` `ELEMENT_UPDATE` 跳号或没有对应会话，已丢弃并等待完整文档。
- `CC-UPDATE-003` 更新引用了未知元素/属性，或应用后文档校验失败，整包拒绝。
- `CC-UPDATE-004` 当前面板适配器不支持原地更新。

完整码表在实现时生成，文档保留前缀和示例。

## 6. 必须覆盖的错误路径

| 路径 | 至少记录 |
| --- | --- |
| 客户端配置 JSON 解析失败 | 文件、行/列、字段、默认值、`CC-CFG-*` |
| 配置值越界 | 键、原值、夹取后的值 |
| 握手失败/版本不匹配 | 双方协议版本、特性位、`CC-NET-*` |
| 未知操作码/畸形帧 | 操作码、长度、来源、计数 |
| 文档 JSON 解析/schema/限额 | uiId、revision、JSON 路径、期望/实际 |
| 主题/字体令牌缺失 | 令牌名、页面、回退目标 |
| 字体加载失败 | 路径、字节数、回退链、`CC-FONT-*` |
| 本地贴图缺失 | 相对路径、扩展名、`CC-TEX-*` |
| URL/GIF 失败 | 脱敏 URL、HTTP 状态、耗时、`CC-URL-*`/`CC-GIF-*` |
| 面板不支持/钩子失败 | surface、异常、`CC-SURF-*` |
| 绑定超时 | token、等待时长、`CC-BIND-*` |
| NanoVG/FBO 回退 | 原因、GL 错误、FBO 尺寸、`CC-FBO-*` |
| 渲染异常 | 元素 id、阶段、堆栈 |
| 输入路由异常 | 焦点、命中元素、鼠标/键盘 |
| 动作拒绝 | actionId、status、reason、冷却/权限 |
| 性能超预算 | 阶段、耗时、元素数、FBO 尺寸 |
| 路径越界/非法 URL | 原始值脱敏、拒绝原因、`CC-SEC-*` |

## 7. 服务端诊断下发

新增 S2C `DIAGNOSTIC`：

```
u8     severity   # 0 info, 1 warn, 2 error, 3 fatal
string code       # CC-*，与客户端码表同一空间
u32    traceId
string scope      # page/ui/render/asset/action
string message
string detail     # 可选，≤ 4 KiB
```

- 服务端在页面编译失败、打开失败、动作异常、版本不匹配时发送。
- 启动阶段的配置/页面错误没有客户端连接，只能写服务端日志；玩家打开受影响页面时补发 `DIAGNOSTIC`。
  因此服务端日志和 `/cloudcore validate` 仍是启动错误的权威来源。
- `detail` 使用**相对路径**（`pages/shop/page.yml:12`），不发送绝对路径。
- 客户端写入日志，可选显示聊天提示；错误不改变当前会话状态。
- 同一 `(code, scope, message)` 10 秒内去重，只计数。
- 服务端配置 `diagnostics.send-client-details` 控制是否下发；生产可关闭，开发默认开。
- 服务端仍保留完整日志；客户端日志是排障副本，不是唯一来源。

## 8. 客户端配置

```json
{
  "logLevel": "info",
  "logFile": "logs/cloudcore-client.log",
  "logMaxSizeMiB": 10,
  "logRotate": 5,
  "logRedaction": true,
  "serverDetail": true,
  "diagnosticRingSize": 500,
  "packetHeaderRingSize": 200
}
```

- `logLevel`：`error|warn|info|debug|trace`。
- `logRedaction`：默认 `true`；关闭仅用于本地开发。
- `serverDetail`：是否记录服务端下发的 `detail`。
- 环形缓冲用于 `/cloudcore-client dump`。

## 9. 诊断包

快捷键或命令 `/cloudcore-client dump` 生成：

```
logs/cloudcore-dump-20261026-123456.zip
  client.log
  client-config.json
  system.txt          # OS、Java、MC、Fabric、NanoVG、GPU 驱动
  renderer.txt        # 后端、FBO、字体、缓存、最近回退
  packets.txt         # 最近 200 条报文头（操作码/长度/方向，不含 body）
  recent.log          # 最近 500 条结构化日志
  assets.txt          # 资源根目录、缺失文件清单（相对路径）
```

- 默认脱敏；不包含输入框内容、令牌、完整 URL、IP、绝对路径。
- 生成后聊天栏给出路径。
- 用于玩家/管理员提交排障材料。

## 10. 运行时控制

- `/cloudcore-client dump`：生成诊断包。
- `/cloudcore-client reloadassets`：重载本地 `CloudCore` 资源目录（等同 F9）。
- `/cloudcore-client log <level>`：计划中；当前用 log4j 配置或启动参数调整级别。
- 快捷键默认：`F9` 重载资源，`F10` 生成诊断包；在“选项 → 控制 → Cloud Core”里可改键或解绑。
- 若 Fabric 客户端命令 API 不可用，退化为纯快捷键 + 配置文件。

## 11. 脱敏规则

- 输入框内容：永不记录。
- 令牌/密码/会话密钥：永不记录。
- URL：只记录 `scheme://host/path`，去掉 query 和 fragment。
- IP：不记录玩家 IP；服务端地址只记录配置中的别名。
- 服务端路径：只记录相对路径；绝对路径需要显式开启且仅开发环境。
- 控制字符：日志前替换 `\n`、`\r`、`\t` 和 Unicode 方向控制符，防止日志注入。
- 堆栈：保留类名/方法名，不包含参数值。

## 12. 测试

- 每个 `CC-*` 前缀至少有一个触发用例，断言日志包含 code、上下文和字段。
- 脱敏测试：输入框、URL query、IP、绝对路径不出现在日志/诊断包。
- 轮转测试：超过大小生成新文件，旧文件保留。
- 限流测试：同一错误 1 秒内 100 次只完整输出一次。
- 跨平台：Windows/Linux/macOS 路径和换行。
- 诊断包：可解压、内容完整、无敏感数据。

## 13. 验收

- 一个坏页面在客户端日志里能看到 `CC-DOC-*`、uiId、revision、字段路径、服务端相对文件行号。
- 一个 URL 失败能看到脱敏 URL、错误类型、耗时、`CC-URL-*`，同一 URL 不刷屏。
- NanoVG 初始化失败能看到 `CC-FBO-*`、回退原因，界面仍可用。
- `/cloudcore-client dump` 生成的 zip 能直接发给作者定位问题。
