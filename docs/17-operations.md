# 17 — 部署与运维

本文覆盖安装、客户端分发、本地资源、原生库、配置基线、升级、监控、备份、故障处理和支持矩阵。
配置字段的完整说明在 `04-server.md` 和 `05-client.md`。

## 1. 部署拓扑

```
玩家客户端（Fabric + cloudcore-client + NanoVG natives
            + .minecraft/resourcepacks/CloudCore）
        │  Minecraft 连接（已加密）
        ▼
Paper 1.21.11 服务端
  ├─ cloudcore-bukkit（插件）
  ├─ pages/ themes/ fonts/
  └─ 可选：PlaceholderAPI
```

- 原版客户端可以加入，只会看到原版界面；没有 Fabric 模组就没有任何 Cloud UI。
- 服务端不要求安装 Fabric；Cloud 的服务端是 Paper 插件。
- 图片默认由客户端直连；需要隐私或客户端出网受限时切换服务端代理。
- 页面、主题、资源包、启动器的统一分发由服务器/插件作者自己的体系负责，不在本插件范围。

## 2. 服务端安装

1. Paper 1.21.11 + Java 21。
2. 把 `cloudcore-bukkit-<version>.jar` 放入 `plugins/`。
3. 启动一次，生成 `plugins/CloudCore/config.yml` 与 `pages/`（首次会写入六张示例页：
   `example`、`shop`、`progress_demo`、`scroll_demo`、`actions_demo`、`papi_demo`，已存在的文件不覆盖）。
4. 编辑配置与页面；`/cloudcore validate` 校验全部页面。
5. `/cloudcore reload`（同时重读 `config.yml`）或重启。
6. 确认通道注册成功：启动日志无 `ChannelNotRegisteredException`。

目录：

```
plugins/CloudCore/
  config.yml
  pages/              # YAML 页面；子目录（<名字>/page.yml）也可以
  state/<uuid>.json   # 每个玩家的会话状态（异步写、原子替换；state.persist: false 可关闭）
  themes/             # 计划中
  fonts/              # 可选字体索引（NanoVG 后端）
  cache/textures/     # 计划中：代理模式图片缓存
```

`state/` 里的文件只包含文档用到的状态值（数字/布尔/字符串），删除某个文件等于把该玩家的页面状态
重置为文档默认值；服务器迁移时连同 `pages/` 一起备份即可。

## 3. 客户端分发

- 由插件作者自行发布 Fabric 模组 jar；本插件不提供启动器、更新器或 CDN。
- 依赖 Fabric Loader、Fabric API、Minecraft 1.21.11。
- 模组内置 NanoVG 三平台 natives；玩家不需要额外安装运行库。
- 版本策略：协议主版本相同即可互操作；服务端支持落后一个次版本的客户端。
- 客户端配置：`config/cloudcore-client.json`（首次启动自动写入）：
  `{"renderer": "native", "debug": false}`。`renderer` 取 `native`（默认）或 `nanovg`；
  启动参数 `-Dcloudcore.nanovg=true/false` 优先于文件，便于启动器/测试覆盖。
  文件损坏或取值未知时按默认值启动并记 `CC-CFG-001/002`。
- 客户端缓存：`.minecraft/cloudcore/image-cache/`（内容哈希）计划中；本地贴图缓存由
  `LocalTextureRegistry` 直接读文件系统。

## 4. 客户端本地资源

```
.minecraft/resourcepacks/CloudCore/
  副本/
    背景/主面板.png
  签到/
    图标/签到.png
  公共/
    贴图/panel.png
    字体/body.ttf
    图标/cart.png
```

- 目录可以按业务自由分类；服务端文档写 `local:副本/背景/主面板.png`、`local:公共/字体/body.ttf`，
  客户端解析到 `CloudCore/` 根目录下。
- 这个目录不是标准资源包布局，Minecraft 资源包列表可能忽略它；Cloud 直接读文件系统，不依赖它。
- 客户端直接读文件系统，不要求玩家在资源包列表启用。
- `F9` 重载本地资源，清空 texture/font/image 缓存并重绘；不重连、不重载服务端页面。
- 文件缺失显示红色“资源缺失”，日志写相对路径和 `CC-TEX-*`/`CC-FONT-*`。
- 字体单份 ≤ 8 MiB；贴图 ≤ 8 MiB、2048×2048；只允许 png/gif/ttf/otf；拒绝 `..`、绝对路径、
  符号链接、控制字符和 Windows 保留名。
- 资源包/启动器分发由服务器统一处理；本插件只负责读取本地目录。
- 原版文本（EditBox、Tooltip 内容）使用原版 `Font`；需要视觉一致时本地目录同时提供 MC 字体。

## 4.1 客户端日志与诊断

- `logs/cloudcore-client.log`，10 MiB × 5 轮转；开发可调 `logLevel`。
- 所有配置/协议/文档/资源/渲染/动作错误都写入日志，带 `CC-*` 错误码和上下文。
- 服务端 `DIAGNOSTIC` 的摘要也写入同一日志；detail 只含相对路径。
- `F10` 或 `/cloudcore-client dump` 生成 `logs/cloudcore-dump-*.zip`，包含日志、配置、系统信息、
  最近报文头（不含 body）、最近错误；默认脱敏。
- 玩家反馈问题时优先收集诊断包，而不是只截一张报错图。
- 细节见 `18-diagnostics.md`。

## 5. NanoVG 原生库

- 依赖 `lwjgl-nanovg` + Windows/Linux/macOS natives，随模组打包。
- LWJGL 版本必须与 Minecraft 自带版本一致；不要重复引入核心 natives。
- 原生库解压失败、加载失败、FBO 创建失败 → `NativeBackend`。
- macOS 需要确认 GL 上下文与线程；三平台都要实测。
- 光影包/Iris 下出现 FBO 色差时，客户端配置 `renderer=native` 或服务端接受降级。

## 6. 配置基线

服务端关键项：

| 键 | 建议 |
| --- | --- |
| `protocol.handshake-timeout-seconds` | 10 |
| `ui.max-sessions-per-player` | 4 |
| `surfaces.*.enabled` | 按需 |
| `surfaces.container.legacy.skip` | 保留默认特殊容器 |
| `textures.url.mode` | `client`（默认）或 `proxy` |
| `textures.url.allowlist` | 代理模式必须配置 |
| `placeholders.per-player-budget-ms` | 2 |
| `placeholders.global-budget-ms` | 10 |
| `security.max-events-per-second` | 40 |
| `security.max-definition-bytes` | 1048576 |
| `storage.type` | `sqlite`（开发）/ `mysql`（生产） |
| `storage.mysql.pool-size` | 8–16，按并发调整 |
| `diagnostics.send-client-details` | 开发 true，生产按需 |

客户端关键项：

| 键 | 建议 |
| --- | --- |
| `renderer` | `nanovg`，故障时 `native` |
| `fboScale` | `2`（清晰度）或 `1`（性能） |
| `fallbackToNative` | `true` |
| `remoteImages` | `true`；隐私敏感用户可关 |
| `imageCacheMiB` | 128 |
| `textureCacheMiB` | 32 |
| `animations` | 低端机可关 |
| `debugOverlay` | 排障时开 |
| `logLevel` | 默认 `info`，排障时 `debug` |
| `logRedaction` | 生产必须 `true` |
| `serverDetail` | 是否记录服务端诊断摘要 |

## 7. 升级与兼容

| 客户端 | 服务端 | 结果 |
| --- | --- | --- |
| 无模组 | 有插件 | 原版界面 |
| 有模组 | 无插件 | 无通道流量 |
| 主版本相同 | 主版本相同 | 按特性位启用 |
| 主版本不同 | — | 服务端禁用 Cloud 界面 |
| 客户端过旧 | 新服务端 | 只启用客户端声明的特性 |

升级步骤：

1. 备份 `plugins/CloudCore/`。
2. 阅读发布说明的协议版本和最低客户端版本。
3. 先升级服务端，再升级客户端；或先在测试服验证。
4. 检查 `/cloudcore stats` 的 `surface_unsupported`、`rate_limited`、`crc_fail`。
5. 26.1 迁移见 `10-risks-and-verification.md` 第 5 节。

## 8. 监控与诊断

服务端：

- `/cloudcore stats`：会话、报文、拒绝、贴图、绑定、遍历。
- `/cloudcore surfaces <player>`：各面板 uiId、修订号、更新时间。
- `/cloudcore validate <file>`：页面错误。
- 日志：握手失败、版本不匹配、通道异常、动作异常、PAPI 超预算。

客户端：

- F3 叠加层：后端、FBO 尺寸、字体数、会话、报文、贴图缓存、Tooltip 偏移、Boss 绑定。
- `renderer=native` 用于区分“内容问题”和“NanoVG/FBO 问题”。
- 客户端错误通过 `CLIENT_ERROR` 上报，服务端计数。
- 客户端日志：`logs/cloudcore-client.log`；排障时让玩家按 `F10` 导出 `cloudcore-dump-*.zip`，
  里面包含日志、配置、系统信息、最近报文头，默认脱敏。
- 服务端 `DIAGNOSTIC` 摘要也写入客户端日志；`/cloudcore diagnostics <player>` 可验证链路。

建议告警：

- `surface_unsupported` 持续增长 → 客户端版本过旧。
- `rate_limited` 增长 → 页面事件过于频繁。
- `crc_fail` / `bad_fragment` → 网络或协议实现问题。
- FBO 回退次数 → 驱动/光影包/原生库问题。
- PAPI 预算持续打满 → 扩展过慢。

## 9. 备份与回滚

备份：

- `config.yml`、`pages/`、`themes/`、`fonts/`、`cloudcore.db`。
- MySQL 用 `mysqldump`；记录 schema 版本。
- 客户端缓存和日志不需要备份；诊断包按需收集。
- 动作引擎 v2 的玩家变量写入有 5 秒防抖，升级前先停服。

回滚：

- 保留上一版插件与模组 jar。
- 页面编译失败时旧修订号仍生效，修文件后 `/cloudcore reload`。
- 客户端回滚需要同时考虑协议主版本；主版本不同会禁用 Cloud 界面。

## 10. 故障手册

| 现象 | 排查 |
| --- | --- |
| 玩家看到原版界面 | 模组未装/版本不匹配/握手失败；看服务端 `HELLO` 日志 |
| 界面打开但空白 | 文档未下发/绑定失败；看 `bind` 计数和客户端 `UI_READY` |
| 箱子标题异常 | 标题哨兵兜底触发；检查预绑定顺序 |
| 半透明黑边/白边 | FBO 预乘 alpha 混合；切 `renderer=native` 验证 |
| 文字发虚 | FBO scale 过低或 GUI 缩放；调 `fboScale` |
| 中文缺字 | 字体未覆盖 CJK；换字体或补字形 |
| 图片不显示 | URL 不允许/超时/哈希不匹配；看客户端日志与 `TEXTURE_REQUEST` |
| 光影包下界面异常 | FBO/管线冲突；配置回退原生 |
| PAPI 变量不刷新 | `placeholders.mode`、预算、权限；看 `/cloudcore stats` |
| Tooltip 不能滚 | 内容未超高、`scrollbar.mode`、其他模组接管 |

## 11. 性能调优

服务端：

- 降低 `max-sessions-per-player`、事件速率、PAPI 预算。
- 页面拆分为片段便于局部重载，但不要过度嵌套。
- 遍历实例上限按实际列表大小设置。
- 代理模式图片缓存设上限并定期清理。

客户端：

- `fboScale: 1` 降低上传带宽；`2` 提升文字清晰度。
- `textureCacheMiB`/`imageCacheMiB` 按显存调整。
- 关闭动画或降低动画帧率。
- 静态层缓存、脏矩形、单层合并由实现负责；配置只做兜底。

## 12. 安全运维

- 代理模式只允许 https + 允许列表；拒绝私网/回环。
- 客户端直连时，玩家 IP 会暴露给图床；隐私场景用代理或关闭 `remoteImages`。
- 只从模组 jar 加载 NanoVG 原生库，校验哈希。
- 字体单文件 ≤ 8 MiB；不接受服务端直接推送的字体文件。
- 权限最小化：`cloudcore.surface.*`、`cloudcore.page.*` 按需授予。
- 审计 `console_command` 动作；不要接受原始事件载荷插值。
- 日志不记录输入框内容、报文 body、IP。

## 13. 发布清单

- [ ] 三平台 NanoVG 初始化/回退通过。
- [ ] FBO 色差/alpha 检查。
- [ ] 协议黄金向量、模糊测试。
- [ ] 六类面板手动矩阵。
- [ ] 本地资源/字体/URL/GIF 检查。
- [ ] 客户端日志覆盖所有错误路径，诊断包可用。
- [ ] SQLite/MySQL 存储与迁移检查（动作引擎 v2）。
- [ ] 互操作矩阵（REI/EMI/JEI/Sodium/Iris）。
- [ ] 文档与实现一致。
- [ ] 校验和、变更日志、协议版本、最低客户端版本。

## 14. 支持矩阵

| 维度 | 支持 |
| --- | --- |
| Minecraft | 1.21.11 |
| 服务端 | Paper 1.21.11 |
| 客户端 | Fabric Loader 0.19.5+ / Fabric API 0.141.3+ |
| Java | 21 |
| 操作系统 | Windows、Linux、macOS（NanoVG 三平台） |
| 原版客户端 | 可用，无自定义界面 |
| 26.1 | 计划中，见迁移文档 |
