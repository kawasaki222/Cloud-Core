# Cloud Core — 项目规则

本文件是项目级最高优先级规则。新开对话、换模型、换会话都必须先读这里，并遵守。

## 0. 每次写完必须推送

- 任何文件写入、代码改动、文档改动完成后，必须 `git add` → `git commit` → `git push origin main`。
- 推送失败必须明确报告失败原因，不得假装成功。
- 不强制推送，不改写历史，不删除远程分支。
- 提交前至少跑 `./gradlew build`（或说明为什么跑不了）。
- 仓库：`https://github.com/kawasaki222/Cloud-Core.git`

## 1. 项目定位

Cloud Core 是面向 **Minecraft 1.21.11** 的服务端权威自定义 UI 框架：Paper 服务端插件 + Fabric 客户端模组。
商业 RPG 服务端用它下发页面、HUD、箱子/背包界面、Tooltip、Boss 血条，客户端渲染并把输入回传服务端。
不是为某一个服务器定制；配置、主题、素材、动作都应可由服务器管理员/附属插件替换。

## 2. 技术栈（锁定）

| 项目 | 值 |
| --- | --- |
| Java | 21 |
| Gradle | 9.2.0（wrapper；Loom 1.14 要求） |
| Minecraft | 1.21.11 |
| 映射 | Mojang 官方映射（`loom.officialMojangMappings()`），不用 Yarn |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.141.3+1.21.11 |
| Fabric Loom | 1.14-SNAPSHOT |
| Paper API | 1.21.11-R0.1-SNAPSHOT |
| 渲染 | NanoVG 离屏 + 原生合成；`NativeBackend` 回退 |
| 存储 | SQLite 默认，MySQL 可选 |

## 3. 模块与依赖

```
cloudcore-api          纯 Java 模型/API，无 Minecraft/Bukkit
cloudcore-api-testkit  附属插件测试工具包（纯 Java），依赖 api
cloudcore-protocol     二进制协议；依赖 api
cloudcore-server-core  页面/会话/状态/动作；依赖 api + protocol，无 Bukkit
cloudcore-bukkit       Paper 插件；依赖 server-core
cloudcore-client       Fabric 模组；依赖 api + protocol
```

- `api`、`protocol`、`server-core`、`api-testkit` 不得 import `net.minecraft.*`、`org.bukkit.*`。
- 服务端状态只在主线程改；客户端渲染状态只在渲染线程改。
- 新增报文必须同步更新 `Limits`、黄金向量、协议测试和 `docs/02-protocol.md`。
- 新增页面字段必须同步更新 `docs/03-ui-dsl.md`、`docs/16-ui-document-schema.md` 和校验器。

## 4. 关键设计约束

- 服务端权威：客户端只发事件，不提交结果。
- 下发文档不含 `state`、动作体、命令、`permission`、`repeat`、`includes`、原始 URL；只含展开后的元素和
  `events: {event: actionId}`。
- 主题令牌 `tx:` 由客户端按 `THEME_SET` 解析，服务端只校验存在。
- `url:` 图片默认客户端直连；服务端代理可选；客户端不直接联网时走代理或占位。
- 物品、槽位、数量/冷却/附魔光效、Tooltip 内容、EditBox 文本/IME 保持原版绘制。
- 输入框外观由服务端换皮，输入逻辑/IME 不重写。
- 正式素材放客户端 `.minecraft/resourcepacks/CloudCore/`，可分类（`副本/`、`签到/`、`公共/`）；
  服务端只发 `local:...` 相对路径；模组 jar 只留最小回退。
- 所有错误写客户端 `logs/cloudcore-client.log`，带 `CC-*` 码和上下文；服务端用 `DIAGNOSTIC` 下发摘要；
  `F10`/`dump` 生成脱敏诊断包。禁止静默失败。
- SQLite 默认，MySQL 可选；动作引擎 v2 的玩家变量持久化，异步写、迁移、连接池。
- `cloudcore-api` 是合同：1.x 向后兼容，附属插件只依赖它，不碰内部实现/NMS/客户端类。

## 5. 工作流

1. 先读 `docs/` 对应章节和 `docs/10-risks-and-verification.md` 的未验证项。
2. 实现前确认相关 API 签名；不确定就标注并写 spike，不凭印象编。
3. 代码与文档同一提交更新。
4. 测试：单元 → 属性/模糊 → 集成；没有测试的行为不算完成。
5. 提交信息：`type(scope): summary`，例如 `feat(protocol): add frame codec`。
6. 推送后再报告完成；推送失败要说明。

## 6. 构建命令

```bash
# 需要 JDK 21。Gradle wrapper 已提交。
./gradlew build
./gradlew :cloudcore-protocol:test
./gradlew :cloudcore-bukkit:build
./gradlew :cloudcore-client:build
```

Windows 用 `gradlew.bat`。如果 `JAVA_HOME` 不是 21，设置为本机 JDK 21 路径后再运行。
不要提交 `.gradle/`、`build/`、`run/`、`logs/`、IDE 文件。

## 7. 代码规范

- Java 21；数据用 `record`，封闭报文用 `sealed interface`。
- 公共 API 不接受 `null`；内部可空字段显式标注。
- 日志用 SLF4J；不记录报文 body、输入内容、令牌、IP、绝对路径。
- 协议读取先检查长度上限再分配；错误用状态码/异常类型，不静默吞掉。
- 命名表达意图；注释只写代码无法表达的约束（版本/线程/顺序/限额）。
- 不引入未经说明的依赖；新增依赖更新版本目录和文档。

## 8. 文档

- 文档在 `docs/00`–`docs/19` + `TODO_HANDOFF.md`；中文，代码/字段名英文。
- 当前文档是设计基线；实现偏离时必须先改文档再改代码，或同一提交一起改。
- `docs/10-risks-and-verification.md` 的 U 项未验证前，不得当成事实。
- 新增面板/元素/动作/报文时，按 `docs/15-engineering-guide.md` 的“必须更新”表同步。

## 9. 当前状态

- M0/M1/M2 完成并在真实 Paper + Fabric 开发客户端联调；M3 页面加载、动作引擎子集、
  附属插件 API、键盘导航、状态持久化已落地。M4 页面组合、M5 玩家背包/HUD、M6 Tooltip/Boss、
  M7 元素与字体、M8 占位符刷新的大部分也已落地。详见 `README.md`「当前状态」、
  `docs/10-risks-and-verification.md` 第 2.1 节与 `docs/TODO_HANDOFF.md`。
- 已实现：`ELEMENT_UPDATE` 增量、贴图代理（`TEXTURE_REQUEST`/`TEXTURE_PUT` + 磁盘缓存）、
  `file`/`sqlite`/`mysql` 状态持久化与跨后端导出导入、玩家背包皮肤（玩家模型/状态效果/
  配方书 `hide`/`vanilla`）、Tooltip 附属栏、Boss 多层血条与堆叠。
- 后续：NanoVG 默认化与设备分辨率清晰文字、配方书 `skinned`/`custom`、文本 shaping、
  Tooltip 滚动条拖动与 `tooltip_scroll` 回传、`async-namespaces` 白名单、SQL 连接池、
  依赖图增量编译、Linux/macOS 与 REI/EMI 互操作验证。
- 注意：`tx:` 主题令牌与 `THEME_SET`、`NOTIFICATION`/`SERVER_CONFIG`/`METRICS`/`CLIENT_ERROR`/
  `SCREEN_PROBE`、动作冷却、`/cloudcore stats|surfaces|list|debug`、页面级权限强制都还没有实现；
  代码与文档不一致时以代码为准，并同步修文档。
- 每次改动仍按第 0 节：构建 → 提交 → 推送；验证结论写进 `docs/10`。
