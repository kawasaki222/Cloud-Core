# 15 — 工程指南

本文是开发前的最后一层落地说明：仓库结构、依赖、编码规范、常见改动流程、调试与发布。目标是让新加入的
开发者在不问人的情况下能跑起来、加一个元素/报文/面板，并知道该更新哪些文档。

## 1. 仓库结构

```
cloud-core/
  settings.gradle.kts
  build.gradle.kts
  gradle/
    libs.versions.toml
    wrapper/
  cloudcore-api/
    src/main/java/com/cloudcore/api/
      document/        UiDocument、Element、Style、FontSpec
      action/          ActionSpec、ActionHandler 接口
      validation/      DocumentValidator、错误码
      json/            Gson 适配器、schema 版本
  cloudcore-protocol/
    src/main/java/com/cloudcore/protocol/
      frame/           Frame、Opcode、编解码、分片
      packet/          各报文的 read/write
      surface/         SurfaceType、BindMode、RenderMode
      value/           WireValue
      Limits.java
  cloudcore-server-core/
    src/main/java/com/cloudcore/server/
      page/            PageStore、PageComposer、依赖图
      compile/         DocumentCompiler、actionId 分配
      session/         SessionRegistry、状态机、绑定
      state/           StateModel、模板求值、repeat 展开
      action/          ActionEngine、LinearActionEngine、内置处理器
      placeholder/     PlaceholderResolver SPI
      event/           AddonEventBus、监听器异常隔离
      surface/         SurfaceProviderRegistry、SurfacePolicy
  cloudcore-api-testkit/
    src/main/java/com/cloudcore/testkit/
      FakeCloudCoreApi  内存 API（open/state/reload 记录）
      DocumentAssert    文档断言
      EventCapture      事件捕获
  cloudcore-bukkit/
    src/main/java/com/cloudcore/bukkit/
      plugin/           CloudCorePlugin、配置
      transport/        PluginMessageTransport、握手
      listener/         Inventory、Player、Reload
      inventory/        CloudHolder、点击策略、旧界面适配
      placeholder/      PlaceholderAPI 适配
      command/          命令与权限
  cloudcore-client/
    src/main/java/com/cloudcore/client/
      CloudCoreClient
      net/               payload、ClientTransport、分片
      session/           ClientSessionTable、修订号
      surface/           SurfaceRegistry、六个适配器
      render/            RenderList、RenderBackend、NanoVgBackend、NativeBackend
      render/fbo/        FboCompositor、FboTexture
      render/native/     NativeBridge（物品/槽位/EditBox/Tooltip 文本）
      font/              FontRegistry
      layout/            LayoutEngine、SlotLayout
      input/             InputRouter、命中测试
      texture/           ImageCache、TextureManager、GIF
      mixin/             MinecraftMixin、TooltipMixin、ScreenScrollMixin、BossHealthOverlayMixin
      hud/               HudController
      tooltip/           TooltipSurfaceAdapter
      bossbar/           BossBarSurfaceAdapter
      config/            ClientConfig
      diagnostics/       ClientLog、LogRedactor、F3 叠加层、计数器、诊断包
    src/main/resources/
      fabric.mod.json
      cloudcore.mixins.json
      assets/cloudcore/fallback/...   # 模组 jar 内，仅最小回退
  docs/
```

## 2. 版本与依赖

| 项目 | 版本 | 说明 |
| --- | --- | --- |
| Java | 21 | toolchain 强制 |
| Gradle | 脚手架时固定 wrapper | 不在里程碑中途升级 |
| Fabric Loom | 1.14-SNAPSHOT | 客户端模块 |
| Minecraft | 1.21.11 | Mojang 官方映射 |
| Fabric Loader | 0.19.5 | |
| Fabric API | 0.141.3+1.21.11 | |
| Paper API | 1.21.11-R0.1-SNAPSHOT | compileOnly |
| LWJGL NanoVG | 与 Minecraft 自带 LWJGL 对齐 | M0 确认，不要猜 |
| Gson | 由运行环境提供 | Paper/Fabric 都自带 |
| JUnit 5 / jqwik | 最新稳定 | 测试 |

规则：

- 版本只写在 `gradle/libs.versions.toml`，模块不硬编码。
- LWJGL 核心模块由 Minecraft 提供；只引入 `lwjgl-nanovg:3.3.3` 和对应 natives，避免重复类/原生库。
  客户端 jar 会合并这些类和原生库，运行时仍使用 Minecraft 提供的 LWJGL 核心。
- 发布前跑依赖许可证审计；新增依赖必须在 `15` 或 `10` 记录理由。

## 3. Gradle 配置要点

- 根 `build.gradle.kts`：Java 21 toolchain、编译参数、测试、lint、`-Werror`。
- `cloudcore-api` / `protocol` / `server-core`：`java-library`，无 Minecraft 依赖。
- `cloudcore-bukkit`：`paper-api` 为 `compileOnly`，打包 `api` + `protocol` + `server-core`。
- `cloudcore-client`：Fabric Loom、Mojang 映射、`implementation` NanoVG、`runtimeOnly` 三平台 natives。
- 客户端 jar 不打包 `server-core`；服务端 jar 不打包客户端类。
- 不做 relocate：共享类只存在于各自 jar。
- CI 检查：`api`/`protocol`/`server-core` 不得出现 `net.minecraft`、`org.bukkit` import。

## 4. 编码规范

- Java 21；数据载体优先 `record`，封闭报文类型用 `sealed interface`。
- 公共 API 不接受 `null`；内部可空字段使用项目统一的空值注解。
- 日志用 SLF4J；服务端记录 UUID + 名字，不记录 IP、报文 body、输入框内容。
- 每个错误路径使用稳定 `CC-*` 错误码和结构化字段；客户端日志见 `18-diagnostics.md`。
- 日志默认脱敏：URL 去 query、路径用相对路径、清洗控制字符。
- 协议错误返回状态码，不跨线程抛异常；所有入站数据先检查长度再分配。
- 服务端状态只在主线程修改；客户端状态只在渲染线程修改。
- 不引入反射式序列化；Gson 字段显式；同主版本忽略未知字段，未知元素类型拒绝。
- 注释只写代码无法表达的约束（版本、线程、顺序、限额）。
- 每个 `TODO` 必须带 owner 和里程碑，否则 CI 失败。

## 5. 开发环境

1. JDK 21，`JAVA_HOME` 指向它。
2. 克隆仓库，`./gradlew build`。
3. 启动开发客户端：`./gradlew :cloudcore-client:runClient`。
4. 启动测试服务端：`./gradlew :cloudcore-bukkit:runPaper`（或本地 Paper 目录）。
5. 客户端加入 `localhost`，服务端加载插件。
6. 修改页面 YAML 后执行 `/cloudcore reload`；修改客户端代码需要重启客户端。
7. 调试渲染：F3 叠加层显示后端、FBO、字体、会话、计数器；客户端配置 `renderer: native` 可切回退。

## 6. 常见改动流程

### 6.1 新增报文

1. 在 `Opcode` 分配号（S2C `0x00–0x7F`，C2S `0x80–0xFF`）。
2. 在 `packet/` 实现 `read` / `write`，所有长度读取使用 `Limits`。
3. 更新 `02-protocol.md` 的操作码表和字段说明。
4. 增加黄金向量与往返测试。
5. 如果改变语义/字段顺序，提升协议次版本；主版本变化需迁移方案。
6. 更新 `Limits` 与超限测试。

### 6.2 新增元素

1. `cloudcore-api` 增加元素类型和字段；更新 `16-ui-document-schema.md`。
2. 服务端校验器加规则与失败样例。
3. 客户端布局、渲染、命中测试、输入路由。
4. 如果是交互元素，定义事件与 `actionId` 映射。
5. 更新 `03-ui-dsl.md` 元素目录、示例、限额。
6. 增加单元测试和手动矩阵条目。

### 6.3 新增面板

1. 协议分配 `SurfaceType` 值，增加特性位。
2. 客户端实现 `SurfaceAdapter`，服务端实现 `SurfaceProvider`（接口在 `cloudcore-api` 的
   `com.cloudcore.api.surface`，通过 `CloudCoreApi#registerSurfaceProvider` 注册）。
3. 定义生命周期、绑定方式、数据来源、失败回退。
4. 更新 `11-surfaces.md`、`02-protocol.md`、`05-client.md`。
5. 加互操作与回退测试。

### 6.4 新增动作

1. `cloudcore-server-core` 实现 `ActionHandler`，声明参数 schema 与权限。
2. 编译期校验动作名与参数。
3. 更新 `03-ui-dsl.md` 动作表；v2 节点更新 `13-action-engine.md`。
4. 加执行次数、权限失败、冷却、异常测试。

### 6.5 新增字体/贴图

1. 字体放客户端本地 CloudCore 目录、资源包或模组最小回退；更新 `04-server.md` 字体索引和 `06` 字体来源。
2. 贴图走 `builtin` / `pack` / `inline` / `url`；`url` 编译成纹理键 + `TEXTURE_REF`。
3. 更新示例文档与 CI 资源检查。

## 7. 调试与诊断

- 服务端：`/cloudcore stats`、`/cloudcore surfaces`、`/cloudcore validate`、`/cloudcore debug`。
- 客户端：`logs/cloudcore-client.log`、F3 叠加层、`renderer` 切换、FBO/字体计数器、
  `F10` 诊断包。
- 协议：调试模式只记录帧头（操作码、长度、uiId），不记录 body。
- 渲染：先切 `NativeBackend` 判断是内容问题还是 NanoVG/FBO 问题。
- 性能：`/cloudcore-client bench`；记录 CPU/GPU/JVM/Fabric API 版本。

## 8. 测试与 CI

- 单元：协议、组合、遍历、校验器、模板、布局数学。
- 属性/模糊：随机帧、随机 YAML、限额边界。
- 集成：`runPaper` + 测试客户端场景。
- 客户端：`renderer=nanovg|native` 各跑一遍。
- 夜间：完整模糊、30 分钟浸泡、三平台 NanoVG 初始化。

## 9. 提交与评审

- 小提交；一个提交只做一件事。
- 代码与文档同一提交；协议变更必须同时更新黄金向量和 `02`。
- 评审清单：线程、限额、失败回退、空值、日志脱敏、文档一致性。
- 不跳过 hooks；失败先修根因。

## 10. 发布

- 版本：`MAJOR.MINOR.PATCH`；协议单独 `major.minor`。
- 产物：Paper 插件 jar、Fabric 模组 jar、示例内容包、校验和。
- 发布前：三平台 NanoVG、资源包、回退、互操作矩阵、文档一致性。
- 发布说明必须写：协议版本、最低客户端版本、已知限制。

## 11. 文档维护映射

| 改动 | 必须更新 |
| --- | --- |
| 报文/字段/限额 | `02-protocol.md`、`16-ui-document-schema.md` |
| YAML 语法/元素/动作 | `03-ui-dsl.md`、`16-ui-document-schema.md` |
| 页面组合/遍历 | `12-page-composition.md` |
| 面板生命周期 | `11-surfaces.md`、`04-server.md`、`05-client.md` |
| 渲染后端/FBO/字体 | `06-resources-and-rendering.md`、`14-render-backends.md` |
| 动作引擎 | `13-action-engine.md` |
| 部署/资源包/运维 | `17-operations.md` |
| 日志/错误码/诊断包 | `18-diagnostics.md` |
| 附属插件 API | `19-addon-api.md` |
| 风险/未验证项 | `10-risks-and-verification.md` |

## 12. 完成定义

- 编译、测试、CI 通过。
- 新行为有测试和文档。
- 没有无主 `TODO`。
- 失败路径有回退。
- 性能数字有测量条件。
- 安全清单相关项已勾选。
