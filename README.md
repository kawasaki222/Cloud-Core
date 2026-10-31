# Cloud Core

面向 **Minecraft 1.21.11** 的服务端权威自定义 UI 框架 —— Paper 服务端插件加 Fabric 客户端模组。

服务端描述界面、HUD、物品提示和 Boss 血条；客户端负责渲染并把输入回传给服务端。服务端永远不信任客户端。
原版客户端照常工作：它们只会看到原版界面。

## 当前状态

**M1 协议核心、M2 面板链路、M3 页面加载与动作引擎子集已完成并在真机联调。**

- 构建：`./gradlew build` 通过（JDK 21，378 个测试 / 72 个测试类，0 失败）。
- 协议：信封、DEFLATE、分片/重组、HELLO/HELLO_ACK、PING/PONG、UI_OPEN/UI_READY/UI_CLOSE/UI_CLOSED、
  `EVENT`/`EVENT_RESULT`、`ELEMENT_UPDATE`、`TEXTURE_REF`、`TEXTURE_REQUEST`/`TEXTURE_PUT`、
  `DIAGNOSTIC`；未知 `surface`/`BindMode`/`RenderMode` 前向兼容。
  真机验证：830 KB 文档拆成 27 片重组、断线/重连清理、1,000,000 随机帧解码。
- 附属插件 API：`CloudCoreApi`（`open`/`registerActionHandler`/`isModded`，经 `ServicesManager`），
  第二个插件注册自定义动作、占位符解析器、页面提供器与代码页，权限校验与错误返回已在真机验证；`PageBuilder` 可在代码里构造页面
  再 `open`，动作表与 YAML 页面走同一条发送路径。
- 文本里的 `%占位符%` 与 `${placeholder:...}` 在发送/刷新时展开：PlaceholderAPI（软依赖，可关闭）
  与附属插件注册的自定义解析器；页面/元素可配 `static`/`interval`/`tick`/`event`/`manual` 刷新策略，
  同步受预算约束，`async: true` 走工作线程。
- 会话状态按玩家持久化，后端可选 `file`（默认，`plugins/CloudCore/state/<uuid>.json`）、
  `sqlite`（驱动随插件打包）与 `mysql`（服务端自带 JDBC 驱动）；异步写、原子替换，
  `/cloudcore state-export` / `state-import` 在三个后端之间迁移。
- 服务端：Paper 插件（自包含 jar）、`managed`/`readonly`/`passthrough` 点击策略与
  `allow_player_inventory`、`inventory_grid` 槽位重排、事件限流、`command` 动作闸门、
  代理贴图（`textures.url.mode: client|proxy|auto`，磁盘缓存按最旧淘汰）、
  数据驱动饰品槽位（`slots.yml` + `/cloudcore accessories`）、装备资产绑定、
  `/cloudcore validate|reload|page|stats|surfaces|state-export|state-import|equipment|demo-*`
  （含 Tab 补全）；
  WatchService 热重载。
- 页面：YAML 单文件或 `includes` 组合、`repeat`（含嵌套）、`${...}` 模板、动作编译成 actionId；
  动作引擎子集（`message`/`close_ui`/`set_state`/`if`/`delay`/`command`/`open_page`/`sound`）与表达式求值；
  `set_state` 触发重编译并推送更高 revision 的文档更新。
- 客户端：六类面板（自定义页面、容器、玩家背包、HUD 三层带锚点、Tooltip 裁剪与滚轮、Boss 血条）；
  RenderList 支持文字/富文本、图片（`local:`/`pack:`/`builtin:`/`inline:`）、九宫格、背景贴图、
  图片图层叠加、裁剪/滚动（回传 `scroll` 绝对偏移）、悬停样式与 `hover_enter`/`hover_leave` 事件、
  `item_icon`、滑块（拖动 + `min`/`max`/`step`/`live`）、复选框、文本输入框（原版 `EditBox` 换皮：
  背景/九宫格、normal/invalid/focused 边框、`filter`、`sensitive` 掩码、`padding`、光标色/选区色/清除按钮）；
  键盘导航（`Tab` 焦点 + `Enter` 激活）；`disabled`/`pressed`/`right_click` 状态；
  GIF 逐帧播放；`url:` 贴图客户端直连下载（https/限额/sha256）与磁盘缓存；
  Tooltip 附属栏（`attachments`）、物品图标、`name`/`lore` 独立字体；Boss 血条多层逐层绘制与堆叠间距；
  玩家背包的实时玩家模型与状态效果图标、配方书 `hide`/`vanilla`；
  F3 调试叠加层显示活动文档；
  NanoVG 后端（`-PcloudcoreNanoVg=true`，填充/图片/自定义字体 + 原生叠层）与 FBO 合成已通过；
  客户端日志 `logs/cloudcore-client.log` 带 `CC-*` 码。
- 尚未验证/未实现：Linux/macOS 运行、NanoVG 默认化（当前默认仍是原生后端）、
  配方书 `skinned`/`custom`（当前回退 `vanilla`）、文本 shaping、Tooltip 滚动条拖动与
  `tooltip_scroll` 回传、`placeholders.async-namespaces` 白名单、SQL 后端连接池、
  内容哈希去重、与 REI/EMI 等模组的互操作矩阵。
  详见 `docs/TODO_HANDOFF.md` 与 `docs/10-risks-and-verification.md`。

## 示例页面

首次启动把下面这些页面写到 `plugins/CloudCore/pages/`（已存在的不覆盖），
用 `/cloudcore page <名字> <玩家>` 打开：

| 页面 | 演示内容 |
| --- | --- |
| `example` | `repeat`、模板与表达式、九宫格、滑块、输入框、复选框、图标按钮、URL 贴图、`if`/`else`、`delay`、`command` 闸门 |
| `shop` | 商店：`repeat` + 行布局 + 物品图标 + 逐项价格与动作 + 余额条 + 状态持久化 |
| `progress_demo` | 进度条四个方向、贴图背景/填充按进度裁剪、滑块驱动数值 |
| `scroll_demo` | 滚动容器与 `scroll` 事件回传、`hover_enter`/`hover_leave`、数字过滤与 `sensitive` 掩码 |
| `actions_demo` | `open_page` / `sound` / `close_ui`、`enabled: false` 禁用态、`right_click` |
| `papi_demo` | PlaceholderAPI 展开与未安装扩展时令牌原样显示 |
| `papi_refresh` | `interval` 周期刷新（同步/异步各一页）、`${placeholder:...}`、`refresh` 动作 |
| `slider_demo` | 滑块 `min`/`max`/`step`/`live`、负区间与步进吸附 |
| `tooltip_rpg` | Tooltip 皮肤：金边框、`name`/`lore` 字体、物品图标、`attachments` 套装栏 |
| `rpg_hud` | 中世纪 HUD：`replace: [health, food, xp, hotbar]`、`value_from`/`text_from` 实时数值 |
| `inventory_skin` | 玩家背包皮肤：`inventory_grid` 重排、玩家模型、状态效果、配方书 |
| `boss_dnf` | 多层 Boss 血条：一条 `BossBar` 逐层刷新、`bar.layers`、`demo_layers` |

`/cloudcore page <名字>` 打开自定义页面；`player_inventory` / `hud` / `tooltip` / `boss_bar`
这类常驻页在握手完成后自动下发，单页可用 `surface_options.on_join: false` 退出。

## 目标技术栈
| 组件 | 版本 / 说明 |
| --- | --- |
| Minecraft | 1.21.11 |
| Java | 21 |
| 映射 | **Mojang 官方映射**（客户端 `loom.officialMojangMappings()`；服务端同样使用 Mojang 名称） |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.141.3+1.21.11 |
| Fabric Loom | 1.14-SNAPSHOT |
| Paper API | 1.21.11-R0.1-SNAPSHOT |
| 渲染 | LWJGL NanoVG（`lwjgl-nanovg`）+ 原生合成；原生 `GuiGraphics` 作为回退 |

1.21.11 中 `ResourceLocation` 已被 Mojang 重命名为 `Identifier`；本项目文档和代码统一使用
`net.minecraft.resources.Identifier`。1.21.11 也是最后一个带 Yarn 映射的版本，但本项目从一开始就使用
Mojang 映射，因此 26.1 的迁移成本更低。详见
[`docs/10-risks-and-verification.md`](docs/10-risks-and-verification.md)。

## 可定制的面板

| 面板 | 类型 | 说明 |
| --- | --- | --- |
| 自定义页面 | 独立界面 | 凭空创建，不依赖容器 |
| 箱子界面 | 容器 | 替换任意箱子类容器的渲染，保留槽位语义 |
| 玩家背包界面 | 客户端界面 | 替换 `InventoryScreen` 的外观，槽位与点击保持原版 |
| HUD | 叠加层 | 按层注册，支持服务端状态更新 |
| ItemTooltip | 提示层 | 自定义样式，**支持滚轮滚动** |
| Boss 血条 | 叠加层 | 接管原版 `BossHealthOverlay` 的渲染 |

面板通过 `SurfaceAdapter` 扩展；以后新增面板类型只需实现适配器并提升协议次版本号，见
[`docs/11-surfaces.md`](docs/11-surfaces.md)。

其他能力：Tooltip 滚动条和 Boss 血条的贴图、九宫格、颜色全部由文档定义；`url:` 图片默认**客户端
直连**（服务端只发引用，图片字节不占游戏连接），可切换服务端代理模式；GIF 直连时客户端解码、代理时
服务端解码为垂直精灵表；PlaceholderAPI 变量支持 `static` / `interval` / `tick` / `event` / `manual` 刷新，
默认主线程同步并受预算约束，`async: true` 走工作线程，PAPI 只在 `async-namespaces` 白名单内的命名空间后台展开。所有配置、
协议、文档、资源、渲染和动作错误都写入客户端
`logs/cloudcore-client.log`（已实现 10 MiB × 5 轮转，只收 `cloudcore` logger），带 `CC-*` 错误码和上下文，
并可导出诊断包。绘制目标为
**NanoVG 离屏渲染 + 原生合成**：面板、静态文本、图片、
九宫格、圆角、渐变和特效由 NanoVG 画；物品、槽位、tooltip 内容和输入法仍由原版绘制；原生
`GuiGraphics` 后端作为回退。**当前默认仍是原生后端**，NanoVG 需在
`config/cloudcore-client.json` 里写 `"renderer": "nanovg"` 或加 `-Dcloudcore.nanovg=true` 启用。
输入框外观可由服务端换皮，输入逻辑、光标、选区与输入法保持原版；物品本体、
数量/冷却/附魔光效和属性 tooltip 组件同样保持原版，只定制槽位外框与布局。动作引擎分两阶段：
v1 线性动作（条件、权限、延迟、冷却），v2 服务端图执行器（分支、循环、等待、并行、持久变量）；
两者都在服务端执行，与渲染后端无关。

## 模块

| 模块 | 产物 | 依赖 | 职责 |
| --- | --- | --- | --- |
| `cloudcore-api` | 普通 jar | Gson（由运行环境提供） | UI 文档模型、元素类型、样式令牌、动作模型、校验规则 |
| `cloudcore-protocol` | 普通 jar | — | 二进制信封、报文类型、分片、限额、编解码测试 |
| `cloudcore-server-core` | 普通 jar | api、protocol | 页面组合、YAML 载入、编译、会话、状态、动作执行、占位符 SPI |
| `cloudcore-api-testkit` | 普通 jar | api | 附属插件测试工具包：`FakeCloudCoreApi`、`DocumentAssert`、`EventCapture` |
| `cloudcore-bukkit` | Paper 插件 jar | server-core | Bukkit 传输、容器适配、面板策略、饰品槽位、装备资产、代理贴图、命令、权限、PlaceholderAPI |
| `cloudcore-client` | Fabric 模组 jar | api、protocol | 网络、面板适配器、界面替换、布局、渲染、输入、HUD、贴图 |

`cloudcore-api`、`cloudcore-protocol` 和 `cloudcore-server-core` 中**不得出现任何 Minecraft 类**。
即使现在两端都用 Mojang 映射，客户端和服务端的 Minecraft 类路径依然不同（Paper 带自己的重映射，
Fabric 带 Loom 的产物），共享模块保持纯 Java 21 才能在两端复用、独立测试，并且不受映射迁移影响。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/00-project-charter.md`](docs/00-project-charter.md) | 目标、非目标、用户、成功标准、术语表 |
| [`docs/01-architecture.md`](docs/01-architecture.md) | 模块边界、面板抽象、运行时组件、数据流、线程模型 |
| [`docs/02-protocol.md`](docs/02-protocol.md) | 线上格式、操作码、字段布局、面板类型、限额、版本协商 |
| [`docs/03-ui-dsl.md`](docs/03-ui-dsl.md) | YAML 编写格式、元素目录、布局、遍历、字体、动作 |
| [`docs/04-server.md`](docs/04-server.md) | Paper 插件设计、页面组合、会话生命周期、面板策略 |
| [`docs/05-client.md`](docs/05-client.md) | Fabric 模组设计（Mojang 映射）、混入、面板适配器、输入路由 |
| [`docs/06-resources-and-rendering.md`](docs/06-resources-and-rendering.md) | 坐标系、九宫格、贴图、字体、Tooltip 滚动、Boss 血条、性能 |
| [`docs/07-security.md`](docs/07-security.md) | 威胁模型、校验矩阵、限流、文本清洗 |
| [`docs/08-roadmap.md`](docs/08-roadmap.md) | 里程碑、任务拆解、验收标准 |
| [`docs/09-testing.md`](docs/09-testing.md) | 测试金字塔、模糊测试、黄金向量、互操作矩阵 |
| [`docs/10-risks-and-verification.md`](docs/10-risks-and-verification.md) | 已核实事实、未验证假设、风险登记、决策记录 |
| [`docs/11-surfaces.md`](docs/11-surfaces.md) | 六类面板的钩子、生命周期、数据来源、扩展方式 |
| [`docs/12-page-composition.md`](docs/12-page-composition.md) | 多 YAML 组合、片段、合并规则、重载、遍历展开 |
| [`docs/13-action-engine.md`](docs/13-action-engine.md) | 后期动作引擎的模型、节点、作用域、执行与安全 |
| [`docs/14-render-backends.md`](docs/14-render-backends.md) | NanoVG 默认后端、原生回退、后端选择与失败策略 |
| [`docs/15-engineering-guide.md`](docs/15-engineering-guide.md) | 仓库结构、构建、编码规范、常见改动流程、发布 |
| [`docs/16-ui-document-schema.md`](docs/16-ui-document-schema.md) | 下发文档字段、类型、限额、示例与版本 |
| [`docs/17-operations.md`](docs/17-operations.md) | 部署、客户端分发、本地资源、SQLite/MySQL、故障手册 |
| [`docs/18-diagnostics.md`](docs/18-diagnostics.md) | 客户端日志、错误码、服务端诊断下发、诊断包 |
| [`docs/19-addon-api.md`](docs/19-addon-api.md) | 附属插件 API、代码构造页面、动作/占位符/页面提供器、兼容策略 |
| [`docs/TODO_HANDOFF.md`](docs/TODO_HANDOFF.md) | 当前状态与下一步动作 |

## 非目标

- 执行服务端下发的任意客户端脚本。
- 替换或改写原版物品栏协议。
- 为没有安装模组的客户端提供自定义外观。
- v1 支持创造模式物品栏（`CreativeModeInventoryScreen`）。
- 通过运行时推送字体文件实现字体热更新（字体来自本地 CloudCore 目录或资源包）。
- 资源包、页面、启动器的统一分发；这些由服务器或插件作者自己的体系负责。
- v1 支持 Folia / 区域化线程的服务端。

## 许可证

待定。
