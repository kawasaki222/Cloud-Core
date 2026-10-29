# 19 — 附属插件 API

目标是让商业 RPG 团队用自己的插件直接构造页面、注册动作、读取状态、扩展面板，而不依赖内部类或
YAML 反射。公共 API 是合同：1.x 不做破坏性变更。

当前实现状态：`open`（按页面 id 或代码页）、`registerActionHandler` / `unregisterActionHandler`、
`registerPlaceholderResolver` / `unregisterPlaceholderResolver`、
`registerPageProvider` / `unregisterPageProvider`、`setState`/`getState`、`isModded`、
`registerSurfaceProvider` / `unregisterSurfaceProvider`、事件监听与 `cloudcore-api-testkit`
均已实现；前一组已在第二个插件真机验证，surface 提供器与事件监听目前由单元测试覆盖
（`10-risks-and-verification.md`）。`PageBuilder` 已在 `cloudcore-api`。

## 1. 稳定面与内部面

| 面 | 模块 | 承诺 |
| --- | --- | --- |
| 公共 API | `cloudcore-api` | 语义化版本；1.x 向后兼容 |
| Bukkit 适配 | `cloudcore-bukkit` 的 `*Api` 接口 | 稳定；只暴露 Bukkit `Player` 便利方法 |
| 协议 | `cloudcore-protocol` | 协议主/次版本独立 |
| 文档 schema | `cloudcore-api` | schema 主版本独立 |
| 内部实现 | `server-core`、客户端 | 不承诺二进制兼容 |

附属插件只依赖 `cloudcore-api`（以及可选的 Bukkit 便利接口），不 `import` 内部实现。

## 2. 获取 API

```java
CloudCoreApi api = Bukkit.getServicesManager().load(CloudCoreApi.class);
if (api == null) {
    getLogger().warning("CloudCore not installed");
    return;
}
```

生命周期：插件 `onEnable` 时获取；CloudCore 重载不替换 API 实例。

## 3. 打开页面

已实现（`cloudcore-bukkit` 的 `com.cloudcore.bukkit.api`，通过 `ServicesManager` 提供）：

```java
OpenOptions options = OpenOptions.builder()
        .surface(SurfaceType.CUSTOM_SCREEN)
        .bindMode(BindMode.NONE)
        .build();

OpenResult result = api.open(player, "rpg_shop", options);
```

- `open(Player, String pageId)` / `open(Player, String pageId, OpenOptions)`：打开已加载的页面。
- 返回值：`OPENED`、`SCHEDULED`（非主线程，已排到下一 tick）、`NOT_MODDED`（客户端没完成
  握手）、`UNKNOWN_PAGE`、`UNSUPPORTED_SURFACE`（v1 只打开自定义页面）、`FAILED`（页面编译失败）。
- 会话状态、revision 与贴图引用和 `/cloudcore page` 走同一条路径。
- `open(Player, UiDocument, OpenOptions)`（代码构造页面）属于 PageBuilder，计划中。

## 4. 代码构造页面

已实现。`PageBuilder`（`cloudcore-api` 的 `com.cloudcore.api.document`）产出与 YAML 编译结果同类的
`CompiledPage`：同一份文档模型、同一张动作表、同一个校验器，发送路径也相同。

```java
CompiledPage page = PageBuilder.create("rpg_shop", SurfaceType.CUSTOM_SCREEN, 240, 120)
        .theme("dark")
        .panel("root", 0, 0, 240, 120, "#CC202830")
        .label("title", 12, 10, "RPG 商店")
        .button("buy", 12, 40, 100, 20, "购买", "rpg_buy", Map.of("item", "minecraft:diamond"))
        .itemIcon("icon", 120, 40, "minecraft:diamond", 1)
        .build();

OpenResult result = api.open(player, page);
```

- 方法：`panel`、`label`、`button`（带动作与参数）、`itemIcon`、`on(elementId, event, action[, params])`、
  `attribute(elementId, key, value)`（写任意元素属性，如 `nine_slice`、`checked`）、`state`、`theme`。
- 同一个事件绑定两次会追加动作（等价 YAML 的 `on: {click: [...]}`）。
- 重复 id、未知元素 id、非正尺寸、校验失败都会抛 `IllegalArgumentException`，不会发出坏文档。
- 只支持普通属性：`url:` 贴图是 YAML 编译期的功能，代码页请用
  `local:`/`pack:`/`builtin:`/`inline:` 或颜色。
- 代码页没有文件可重编译，所以 `set_state` 不会刷新它；需要新内容时重新 `build()` 并 `open`。

## 5. 注册动作

已实现。处理器在主线程执行，注册名大小写不敏感、只允许 `[a-z0-9_.-]{1,64}`，
内置动作名（`message`/`set_state`/`close_ui`/`if`/`delay`/`command`）不可占用。

```java
api.registerActionHandler("rpg_buy", new ActionHandler() {
    @Override
    public void handle(ActionContext context, Map<String, Object> args) {
        String item = (String) args.get("item");
        // 业务逻辑；读 context.state()，改完调用 context.refresh()
        context.message("已购买 " + item);
        context.refresh();
    }

    @Override
    public String permission() {
        return "rpg.buy";
    }
});
```

- `ActionContext`：`player()`、`uiId()`、`elementId()`（如 `rows[2].buy`）、`state()`（本次点击
  之前的页面状态快照）、`message(String)`、`refresh()`（用当前状态重编译并推送新 revision）。
- 声明权限：`ActionHandler#permission()` 非空时先检查；不通过则**整次点击**记为
  `REJECTED`，后续动作不再执行，并写 `Denied action ...` 日志。
- 抛异常 → 记录 `uiId`/`elementId`/动作名与堆栈，本次点击 `REJECTED`。
- 注册重复名抛 `IllegalArgumentException`；`unregisterActionHandler` 返回是否移除。
- 页面里未注册的动作名：写 `Unsupported action ...` 警告并按 `REJECTED` 处理，不静默。

```yaml
- type: button
  id: buy
  text: "购买"
  on:
    click:
      - action: rpg_buy
        item: "minecraft:diamond"
```

内置动作也支持 `permission:` 参数：`- { action: message, value: "...", permission: "rpg.vip" }`。

## 6. 注册占位符

已实现。`%namespace_params%` 先交给注册的解析器，未命中再交给 PlaceholderAPI：

```java
api.registerPlaceholderResolver("rpg", (player, params) -> switch (params) {
    case "level" -> String.valueOf(rpg.getLevel(player));
    default -> null;
});
```

- 命名空间匹配 `[a-z0-9_]{1,16}`，大小写不敏感；重复注册抛 `IllegalArgumentException`。
- 令牌按第一个 `_` 拆分：`%rpg_level%` → 命名空间 `rpg`、参数 `level`；
  `%rpg%` → 参数为空串。令牌本身不合法（空格、横线、超长）时跳过。
- 返回 `null` 表示不处理，令牌交给下一个解析器/PlaceholderAPI，最终仍未解析则原样显示。
- 解析器抛异常只记一次警告，令牌按原样发送。
- 主线程、发送/刷新时调用；`placeholders.enabled: false` 只关闭 PlaceholderAPI 这一段，
  自定义解析器仍然生效。

## 7. 动态页面提供器

已实现。`open(player, "rpg_shop")` 会先查页面提供器，命中就按玩家现场构建：

```java
api.registerPageProvider("rpg_shop", player -> PageBuilder
        .create("rpg_shop", SurfaceType.CUSTOM_SCREEN, 240, 120)
        .panel("root", 0, 0, 240, 120, "#CC202830")
        .label("hi", 12, 10, "hello %rpg_name%")
        .build());
```

- 提供器优先于同名 YAML 文件；返回 `null` 或抛异常 → `OpenResult.FAILED` 并写日志。
- 每次打开都会调用；缓存与否由提供器自己决定。
- 构建出的页面与 `PageBuilder` 页面完全相同：动作表、占位符展开、revision 计数走同一路径；
  没有文件可重编译，`set_state` 不会刷新它。

## 8. 自定义面板

```java
api.registerSurfaceProvider(new RpgSurfaceProvider());
```

- 接口在 `cloudcore-api` 的 `com.cloudcore.api.surface.SurfaceProvider`，附属插件只依赖 API 即可实现。
- 生命周期：`open` 在文档发送前调用（返回空表示拒绝），`apply` 在 `UI_DEFINITION` 排队后调用，
  `close` 在服务端 `UI_CLOSE` 或客户端 `UI_CLOSED` 时调用；`close` 必须幂等。
- 一个面板类型只能注册一个提供器；`UNKNOWN` 会被拒绝。
- 服务端仍负责发送 `UI_OPEN`/`UI_DEFINITION` 并拥有文档；提供器可以绑定自己的后备状态
  （物品栏、BossBar、外部会话）并通过 `SurfaceSession.token()` 决定关联令牌。
- 客户端 mod 必须已经认识该 `SurfaceType`；未实现的类型在客户端返回 `UI_READY(state=4)`，
  服务端回退原版。发明全新面板需要协议常量、特性位与客户端适配器（`11-surfaces.md`）。
- 未注册提供器时，内置面板走原有路径，行为不变；插件卸载会清空注册表。

## 9. 状态与事件

```java
api.setState(player, uiId, "selected", 3);
Optional<Object> value = api.getState(player, uiId, "selected");

api.addEventListener(SessionOpenEvent.class, event -> ...);
api.addEventListener(SessionCloseEvent.class, event -> ...);
api.addEventListener(ElementEvent.class, event -> ...);
api.addEventListener(ActionResultEvent.class, event -> ...);
boolean removed = api.removeEventListener(ElementEvent.class, listener);
```

- 事件类型在 `cloudcore-api` 的 `com.cloudcore.api.event`：
  - `SessionOpenEvent(playerId, uiId, surface, documentId, revision)`：`UI_OPEN` 发送后；
  - `SessionCloseEvent(playerId, uiId, reason)`：`reason` 为 `server` 或 `client`；
  - `ElementEvent(playerId, uiId, elementId, actionId, payload)`：客户端事件解码后、动作执行前，
    `payload` 是只读的普通 Java 值（String/Double/Boolean），不含协议类型；
  - `ActionResultEvent(playerId, uiId, elementId, actionId, status)`：动作执行后，`status` 与
    `EVENT_RESULT` 一致（0 正常、1 未知元素、2 拒绝、3 限流）。
- 监听器在主线程同步执行；异常被 `AddonEventBus` 捕获并写服务端警告，不影响 UI 和其他监听器。
- 事件只读；修改会话请调用 API。
- 状态作用域：`session`、`player.vars`（v2）。

## 10. 线程与调度

- API 调用默认在主线程。
- `open`、`close`、`setState` 可从任意线程调用：内部会切回主线程，返回 `SCHEDULED`。
- 数据库/网络在独立 executor；结果回主线程应用。
- 不要在 render 线程调用 API；客户端没有服务端 API。

## 11. 版本与兼容

- `cloudcore-api` 语义化版本，1.x 不做破坏性变更。
- 废弃 API 保留至少一个大版本，加 `@Deprecated` 和迁移说明。
- 协议、schema、API 三者版本独立；发布说明分别列出。
- 附属插件声明支持范围，例如 `cloudcore-api >=1.2 <2.0`。
- 不承诺内部类、反射字段、NMS 访问。

## 12. 测试工具包

已实现：`cloudcore-api-testkit`（纯 Java，无 Paper/Minecraft 依赖）。

```java
testImplementation(project(":cloudcore-api-testkit"))
```

- `FakeCloudCoreApi`：内存实现 `cloudcore-api` 的 `CloudCoreApi`，记录 `open` 调用、
  按玩家/`uiId` 保存状态、统计 `reload`，用于测试附属插件的页面与动作逻辑。
- `DocumentAssert`：`assertValid`、`assertElement`、`assertText`、`assertElementCount`，
  失败抛 `AssertionError`，不绑定测试框架。
- `EventCapture<T>`：实现 `Consumer<T>`，可直接传给事件总线，提供 `events()`/`last()`/`clear()`。
- 独立示例 addon 工程尚未提供（挂在 `docs/10` 的剩余项）。

## 13. 限制

- v1 元素类型封闭；附属插件不能给客户端注入新元素，只能用现有元素组合。
- 新面板需要客户端 mod 更新；服务端不能单方面扩展。
- 客户端脚本仍然禁止；动作只在服务端执行。
- 资源/页面分发不在 API 范围；附属插件自己负责打包。

## 14. 示例：RPG 任务面板

```java
public final class RpgQuestAddon extends JavaPlugin {
    private CloudCoreApi api;

    @Override
    public void onEnable() {
        api = getServer().getServicesManager().load(CloudCoreApi.class);
        if (api == null) { getLogger().warning("CloudCore missing"); return; }

        api.registerActionHandler("rpg_accept_quest", (ctx, args) -> {
            quests.accept(ctx.player(), (String) args.get("quest"));
            ctx.refresh();
        });

        api.registerPlaceholderResolver("rpg", (player, params) ->
                "level".equals(params) ? String.valueOf(quests.level(player)) : null);

        api.registerPageProvider("rpg_quests", player -> QuestPages.build(api, player));
    }
}
```

## 15. 验收

- 示例 addon 只依赖 `cloudcore-api` 编译通过。
- 1.x 内新增字段/方法不破坏旧 addon。
- 动作权限、异常、限流行为与内置动作一致。
- 附属插件不接触 `server-core`、NMS 或客户端类。
