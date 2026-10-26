# 待办 / 交接

最后更新：2026-10-26。

## 当前状态

**文档基线完成；M1 协议核心与真实 Paper/Fabric 联调已通过，M2 最小面板链路已落地并可显示原生页面。**

- Gradle 9.2.0 wrapper、5 模块、JDK 21 toolchain、Loom 1.14 + Mojang 映射。
- `cloudcore-protocol`：Frame/FrameCodec/Deflate/VarInt/WireValue、分片/重组、Hello/HelloAck/Ping/Pong、
  UI_OPEN/UI_READY + JUnit 测试（含黄金向量、10,000 随机帧）。
- `cloudcore-bukkit`：Paper 通道、HELLO/HELLO_ACK、PING/PONG、`/cloudcore ping`、
  每玩家分片重组、UI_READY 日志。
- `cloudcore-client`：Fabric 入口、payload 注册、JOIN 发送 HELLO、PONG、S2C 分片重组、
  UI_OPEN 分发、未实现面板回 UI_READY(state=4)。
- `cloudcore-api`：`UiDocument`/`UiElement`/`TextRuns` 最小模型、校验器、单文件页面载入接口。
- M2 骨架：客户端 `SurfaceRegistry`/`SurfaceAdapter`/`CloudScreen`、`RenderBackend`/`NativeBackend`、
  `NanoVgBackend`/`FboCompositor` 空实现；服务端 `SurfaceProvider`/`SurfaceProviderRegistry`。
- `UI_DEFINITION` JSON 已能解码、校验并打开 `CloudScreen`；`DocumentRenderListBuilder` 已能用原生后端
  绘制 panel/label/button/text_field/checkbox/progress，图片、物品图标、裁剪/滚动和混入未实现。
- 客户端 jar 已合并 `api` + `protocol` 类，插件 jar 已合并 `api` + `protocol` + `server-core` 类；
  两者仍是独立可部署 jar，Gson 由 Minecraft/Paper 提供。
- 客户端文件日志已启用：`logs/cloudcore-client.log`，只收 `cloudcore` logger，10 MiB × 5 轮转；
  安装失败只回退控制台。
- 已联调（2026-10-26，Paper 1.21.11 build 132 + Fabric 开发客户端，离线模式）：
  `HELLO`/`HELLO_ACK`、`/cloudcore ping`（RTT 9ms）、`UI_OPEN`/`UI_DEFINITION`/`UI_READY`、
  Escape 关闭后的 `UI_CLOSED`，以及 demo 页面 panel/label/button 的实际显示。
- 同一 tick 内回 `HELLO_ACK` 会丢失；服务端延迟 1 tick 后稳定，已写入协议实现说明。
- 六类面板的最小闭环已完成：自定义页面、容器、玩家背包、HUD、Tooltip、Boss 血条。
- 未验证：NanoVG/FBO 屏幕合成、U28–U31 的跨平台部分、容器点击策略。

- 客户端与服务端统一使用 **Mojang 映射**，不使用 Yarn。
- 六类面板：HUD、箱子界面、玩家背包界面、ItemTooltip、自定义页面、Boss 血条。
- 面板可扩展，新增类型不改协议主干。
- 页面支持多 YAML 组合与单文件两种形态。
- 支持遍历（`repeat`）与自定义字体。
- ItemTooltip 支持滚轮滚动。
- 动作引擎分两阶段：v1 线性动作（M8）、v2 服务端图执行器（M9）；与 NanoVG/原生渲染无关。
- v1 已定义 `ActionEngine` 接缝，v2 的图节点编译为 `sequence`，旧 YAML 不需要重写。
- Tooltip 滚动条与 Boss 血条样式全部数据化。
- `url:` 图片默认客户端直连，服务端代理可选；GIF 按模式在客户端或服务端解码。
- PAPI 变量支持定时刷新，默认同步、异步白名单。
- 绘制默认使用 **NanoVG 离屏渲染 + 原生合成**；原生 `GuiGraphics` 后端作为回退。
- 物品、槽位、Tooltip 内容、输入法文本继续由原版绘制。
- 所有配置/协议/文档/资源/渲染/动作错误写入客户端 `logs/cloudcore-client.log`，带 `CC-*` 码，
  服务端通过 `DIAGNOSTIC` 下发摘要，`F10` 导出诊断包。

## 已有内容

```
README.md
docs/
  00-project-charter.md
  01-architecture.md
  02-protocol.md
  03-ui-dsl.md
  04-server.md
  05-client.md
  06-resources-and-rendering.md
  07-security.md
  08-roadmap.md
  09-testing.md
  10-risks-and-verification.md
  11-surfaces.md
  12-page-composition.md
  13-action-engine.md
  14-render-backends.md
  15-engineering-guide.md
  16-ui-document-schema.md
  17-operations.md
  18-diagnostics.md
  19-addon-api.md
  TODO_HANDOFF.md
```

## 文档完整性检查

- [x] 章程、目标、非目标、术语（`00`）
- [x] 模块边界、面板抽象、渲染架构、线程（`01`）
- [x] 协议信封、操作码、面板类型、状态码、限额（`02`）
- [x] YAML 语法、元素、样式、字体、遍历、动作（`03`）
- [x] 服务端启动、配置、页面组合、会话、面板策略（`04`）
- [x] 客户端初始化、网络、混入、后端、输入（`05`）
- [x] NanoVG/FBO/字体/Tooltip/Boss/性能（`06`、`14`）
- [x] 威胁模型、校验、限流、敏感输入（`07`）
- [x] 里程碑、依赖、估算、验收（`08`）
- [x] 测试金字塔、模糊、互操作、浸泡（`09`）
- [x] 风险、未验证项、ADR、迁移（`10`）
- [x] 六类面板生命周期与扩展（`11`）
- [x] 多 YAML 组合、重载、遍历展开（`12`）
- [x] 动作引擎 v1/v2、作用域、等待、预算（`13`）
- [x] 工程结构、编码规范、常见改动流程（`15`）
- [x] 下发文档字段、类型、限额、示例（`16`）
- [x] 部署、本地资源、原生库、升级、故障手册（`17`）
- [x] 客户端日志、错误码、服务端诊断、诊断包（`18`）
- [x] 附属插件 API、代码构造页面、兼容策略（`19`）
- [x] M0：Gradle 多模块/协议核心/Paper 通道/Fabric payload 骨架
- [x] M1：分片与重组、握手、PING/PONG、UI_OPEN/UI_READY 编解码、黄金向量、随机帧测试
- [x] M1 接线：Paper 与 Fabric 的 HELLO/PING/PONG/分片/UI_OPEN 处理
- [x] M1 真实联调：Paper + Fabric 握手、`/cloudcore ping`（RTT 9ms）、UI 打开/关闭
- [x] 服务端主动 `UI_CLOSE`：客户端关闭 CloudScreen 并写日志
- [x] `DIAGNOSTIC` 下发链路：服务端测试码进入客户端文件日志
- [x] U2：容器打开事件中的 PING 先于 `ContainerScreen#setScreen`（真实客户端验证）
- [x] U12：`MouseHandler#onScroll` 在 ContainerScreen 上收到滚轮（真实客户端验证）
- [x] U3：`GuiGraphics#renderTooltip` 混入在悬停石头时实际命中（lines=1）
- [x] U19：Paper BossBar 哨兵标题在客户端可见
- [x] U13：`@WrapOperation` 命中 `BossHealthOverlay#render` 内的私有 `drawBar` 调用
- [x] M1 真机大报文：64,776 字节文档拆成 3 片；830 KB 文档拆成 27 片并重组成功
- [x] M1 压力：100 万随机帧解码（仅 ProtocolException）
- [x] M1 断线清理：重连后分片组、pending 会话与面板文档全部清空（日志 + 单元测试）
- [x] M2 骨架：SurfaceRegistry/SurfaceAdapter/SurfaceProvider、UiDocument、RenderBackend、CloudScreen
- [x] M2 文档链路：UI_DEFINITION 解码、校验、`CloudScreen` 打开
- [x] M3 核心：YAML 页面加载、`includes` 合并、`repeat` 展开、模板替换、JSON 回写（真机验证）
- [x] 事件链路：`EVENT` 编解码、点击命中、服务端动作表（`message`/`close_ui`，真机验证）
- [x] 状态更新：`set_state` → 重编译 → 更高 revision 的 `UI_DEFINITION`，客户端替换界面（真机验证）
- [ ] M3 剩余：热重载/依赖图、`/cloudcore validate`、`command`/条件/表达式与 `EVENT_RESULT`
- [x] M2 最小原生渲染：panel/label/button/text_field/checkbox/progress 的 RenderList（真机截图验证）
- [x] `image` 的 `local:`/`pack:`/`builtin:` 贴图，以及 `item_icon`（原生物品、数量、缩放）（真机截图验证）
- [x] HUD 面板：Fabric `HudElementRegistry` + 持久文档 + UI_CLOSE 清除（真机截图验证）
- [x] Tooltip 面板：自定义背景 + 原生文本/图片（真机截图验证）
- [x] BossBar 面板：自定义条 + 进度/颜色 + 哨兵标题剥离（真机截图验证）
- [x] Container 面板：NEXT_CONTAINER 绑定的箱子换背景，原版槽位保留（真机截图验证）
- [x] 客户端日志文件：`logs/cloudcore-client.log` + 轮转（真机验证）
- [x] M2 渲染：NanoVG 后端、裁剪/滚动偏移（原生 + NanoVG 真机验证）
- [x] 滚轮滚动：光标下 `scroll` 元素的本地偏移与钳制（真机验证 + 单元测试）
- [x] 九宫格 `nine_slice`：9 块拆分，角固定/边单向/中心双向（真机验证）
- [x] `url:` 贴图：编译成 `inline:` + `TEXTURE_REF` + 客户端直连下载（真机验证，https/大小/超时/sha256）
- [ ] 代理模式：`TEXTURE_REQUEST`/`TEXTURE_PUT`
- [ ] M2 渲染剩余：GIF、文本 shaping、`scroll` 事件回传
- [x] HUD / Tooltip / BossBar / Container 面板最小闭环
- [x] PlayerInventory 面板：持久皮肤、`CloudInventoryScreen`、`UI_CLOSE` 还原原版（真机截图验证）
- [x] 容器点击策略：`managed`/`readonly` 的 `InventoryClickEvent` 取消与日志（真机验证，服务端解析文档槽位）
- [x] 容器槽位布局：`inventory_grid` 的 origin/cell/columns 重排容器槽位（真机验证，含点击）
- [ ] 玩家背包槽位重排（需要保留 Slot 子类语义）
- [ ] 容器点击策略补充：`passthrough`、`allow_player_inventory`、`InventoryDragEvent` 的真机验证
- [ ] 玩家背包配方书（v1 隐藏；v2 再补 `skinned`/`custom`）与玩家模型/状态效果选项
- [x] 协议前向兼容：未知 `surfaceId` 保留原始值并回 `UI_READY(state=4)`
- [x] 协议前向兼容：未知 `BindMode` / `RenderMode` 解码为 `UNKNOWN` 并回 `UI_READY(state=4)`
- [x] M0 NanoVG 初始化 + FBO 绘制 + readback（Windows x64，`cloudcore.nanovg.spike`）
- [x] NanoVG 类与 Windows/Linux/macOS 原生库并入客户端 jar
- [x] M0 渲染：FBO 纹理经 `GUI_TEXTURED` blit（显式贴图尺寸）合成上屏（Windows x64，截图验证）
- [x] NanoVG 生产后端（opt-in）：填充 + `local:`/`pack:` 图片 + 原生文本/物品叠层（Windows x64 截图验证）
- [x] NanoVG 字体：`fonts:` + `source: local` 的 TTF 由 NanoVG 绘制，未声明字体者仍走原版（Windows 截图验证）
- [x] U30 预乘 alpha：合成改用 `GUI_TEXTURED_PREMULTIPLIED_ALPHA`
- [ ] NanoVG 默认化：设备分辨率 FBO（清晰文字）、U31 状态隔离、Linux/macOS 验证
- [ ] M0 渲染：预乘 alpha/Linux/macOS 运行验证

剩余的是实现前的验证项和待决问题，不是文档缺口。

## 下一步动作（按顺序）

1. **M1 剩余压力**：
   - 超时/乱序/重复分片的真机注入（单元测试已覆盖，真机未做）。
2. **M0 的 spike**（各半天到一天）：
   - U29/U30/U31：把已通过的 Windows FBO spike 包装成 `AbstractTexture` 并用 `GuiGraphics.blit`
     合成到屏幕，验证预乘 alpha 与 1.21.6 提取/绘制状态；补 Linux/macOS。
   - 先做一个纯色面板 + 一张 TTF 文本的 NanoVG 合成 spike，确认没有色差和状态泄漏。
3. **M2 实际渲染**：
   - 补齐 `inline:`/`pack:` 图片、GIF、`item_icon`、text shaping、裁剪、scroll 和真实布局容器。
   - 接入 `NanoVgBackend`/`FboCompositor`；失败时整帧回退 `NativeBackend`。
   - 容器与玩家背包的点击策略（`managed`/`readonly`/`passthrough`）自动化交互测试。   - 玩家背包 v2：配方书四档、玩家模型与状态效果。
4. 之后按 `08-roadmap.md` 的 M3–M10 推进。

## 环境搭建

- JDK 21。
- Gradle wrapper 在脚手架阶段固定。
- IntelliJ IDEA + Minecraft Development 插件（混入支持）。
- 本地 Paper 1.21.11 服务端目录（`run-paper`）。
- `./gradlew :cloudcore-client:runClient` 启动开发客户端。
- 需要显示器；客户端集成测试可在本地 Windows 或 Xvfb + 软件 GL 上跑。

## 已知约束

- 全项目 Java 21；26.1 将要求 Java 25。
- 1.21.11 中 `ResourceLocation` 已改名为 `Identifier`；文档与代码统一用 `Identifier`。
- `cloudcore-api`、`cloudcore-protocol`、`cloudcore-server-core` 不得 import Minecraft 或 Bukkit。
- 协议服务端接收上限 32,767 字节；C2S 报文不得随文档大小增长。
- 四个混入：`Minecraft#setScreen`、`GuiGraphics#renderTooltip`、`MouseHandler#onScroll`、
  `BossHealthOverlay#render`。新增混入需要兼容性评审。
- 遍历在服务端展开；客户端不执行模板或遍历逻辑。
- 页面组合在加载期完成；客户端只接收单一文档。
- 正式素材放客户端 `.minecraft/resourcepacks/CloudCore/`，可按 `副本/`、`签到/`、`公共/` 分类；
  模组只留最小回退。
- 字体来自本地 CloudCore 目录/资源包，不能运行时推送；NanoVG 用 TTF/OTF，原版文本用 `Font`。
- 所有错误写客户端日志，默认脱敏；`F10`/`dump` 生成诊断包。
- 默认渲染后端是 NanoVG：`NVGContext`、FBO、字体创建和合成只能在渲染线程。
- NanoVG 原生库随模组打包，加载失败必须回退 `NativeBackend`。
- FBO 按逻辑分辨率整数倍分配，单层显存上限 8 MiB，窗口/GUI 缩放变化才重建。
- URL 贴图只允许 https；直连模式由客户端执行私网/回环/大小/超时策略，代理模式由服务端执行允许列表。
- GIF 直连模式客户端解码，代理模式服务端解码；两侧都有帧数/像素/延迟上限。
- PlaceholderAPI 默认主线程同步解析；异步仅限 `async-namespaces` 白名单。
- Tooltip 滚动条与 Boss 样式字段都有范围校验和默认值。

## 待决策事项

见 `10-risks-and-verification.md` 第 6 节。会阻塞 M2/M6 的是：

- 内置字体的字形覆盖范围（拉丁 + CJK 子集）与体积。
- 玩家背包 v1 是否接受隐藏配方书。
- Boss 血条只接管 Paper BossBar，还是也支持第三方 Boss 事件。
- Tooltip 是否需要服务端逐行追加内容。
- 多面板同时需要输入时的焦点规则。
- URL 直连默认开启还是让玩家选择；默认允许列表怎么定。
- 哪些场景强制服务端代理（隐私/内网/鉴权图床）。
- GIF 64 帧上限是否满足实际美术需求。
- PAPI 同步预算（2 ms/玩家、10 ms 全局）是否适合目标玩家规模。
- NanoVG FBO 默认 1x 还是 2x；文字清晰度与 GPU 填充/显存成本的取舍。
- 是否在光影包环境下默认回退原生后端。

## M0 的完成定义

- 干净 JDK 21 环境下 `./gradlew build` 成功。
- 客户端在 1.21.11 上加载，入口打印日志，混入没有报错。
- NanoVG 在三平台初始化成功，FBO 合成一个纯色面板无异常；失败时回退原生。
- U28–U31 有结论并写入 `10-risks-and-verification.md`。
- 插件在 Paper 1.21.11 上启用，注册通道不抛异常。
- U1、U2 有结论并写入 `10-risks-and-verification.md`。
- CI 在 Windows 与 Linux 上绿色。
- 本文件更新实际命令名与偏差。

## 文件归属表

| 领域 | 模块 | 主要文档 |
| --- | --- | --- |
| 线上格式 | `cloudcore-protocol` | `02-protocol.md` |
| 文档模型 / 校验 | `cloudcore-api` | `03-ui-dsl.md`、`16-ui-document-schema.md` |
| 工程 / 构建 / 规范 | 全部 | `15-engineering-guide.md` |
| 部署 / 运维 | 全部 | `17-operations.md` |
| 测试策略 | 全部 | `09-testing.md` |
| 页面组合 / 遍历 | `cloudcore-server-core` | `12-page-composition.md` |
| 动作引擎 | `cloudcore-server-core` | `13-action-engine.md` |
| 服务端面板策略 | `cloudcore-bukkit` | `04-server.md`、`11-surfaces.md` |
| 客户端面板适配 | `cloudcore-client` | `05-client.md`、`11-surfaces.md` |
| 渲染 / 字体 / Tooltip / Boss | `cloudcore-client` | `06-resources-and-rendering.md`、`14-render-backends.md` |
| 安全评审 | 全部 | `07-security.md` |
