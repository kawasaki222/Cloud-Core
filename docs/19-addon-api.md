# 19 — 附属插件 API

目标是让商业 RPG 团队用自己的插件直接构造页面、注册动作、读取状态、扩展面板，而不依赖内部类或
YAML 反射。公共 API 是合同：1.x 不做破坏性变更。

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

```java
OpenOptions options = OpenOptions.builder()
        .surface(SurfaceType.CUSTOM_SCREEN)
        .bindMode(BindMode.NONE)
        .build();

OpenResult result = api.open(player, "rpg_shop", options);
```

- `open(Player, String pageId, OpenOptions)`：打开已加载的页面。
- `open(Player, UiDocument document, OpenOptions)`：打开代码构造的页面。
- 玩家没装模组返回 `NOT_MODDED`；非主线程返回 `SCHEDULED`。
- 权限、限流、会话上限由 CloudCore 统一处理。

## 4. 代码构造页面

`PageBuilder`/`UiDocumentBuilder` 提供 Java DSL，适合 RPG 动态页面：

```java
UiDocument doc = PageBuilder.create("rpg_shop")
        .surface(SurfaceType.CUSTOM_SCREEN)
        .size(320, 180)
        .theme("dark")
        .panel("root", 0, 0, 320, 180, "tx:panel")
        .label("title", 12, 10, "&6RPG 商店")
        .button("buy", 12, 40, 96, 20, "购买", "rpg_buy")
        .itemIcon("icon", 120, 40, "minecraft:diamond", 1)
        .build();
```

- 元素 id、事件名、actionId 由 builder 分配；重复 id 抛 `IllegalArgumentException`。
- 样式令牌与 YAML 共用。
- 结果文档走同一套校验器；不合法直接抛异常，不发送。
- 动态内容优先用代码构造；固定页面用 YAML。

## 5. 注册动作

```java
api.registerActionHandler("rpg_buy", (context, args) -> {
    Player player = context.player();
    String item = (String) args.get("item");
    // 业务逻辑
    context.refresh();
});
```

- 处理器在主线程执行，必须快速返回；慢操作自行调度。
- 声明权限：`ActionHandler#permission()`；CloudCore 在调用前检查。
- 抛异常 → `EVENT_RESULT(INTERNAL_ERROR)`，记录 uiId/elementId/actionId。
- 处理器名唯一；重复注册抛异常或替换，由 API 文档明确。

## 6. 注册占位符

```java
api.registerPlaceholderResolver("rpg", (player, params) -> switch (params) {
    case "level" -> String.valueOf(rpg.getLevel(player));
    default -> null;
});
```

- 命名空间匹配 `[a-z0-9_]{1,16}`。
- 返回 `null` 表示不处理，交回 PlaceholderAPI/其他解析器。
- 默认主线程调用；声明线程安全的解析器可加入异步白名单（服务端配置）。

## 7. 动态页面提供器

```java
api.registerPageProvider("rpg_shop", player -> buildShopFor(player));
```

- 玩家打开页面时调用；返回 `UiDocument` 或 `null`。
- 适合按玩家等级/任务生成不同布局。
- 结果有缓存，按修订号失效；不要每次返回新对象导致无意义重编译。
- 抛异常 → 返回打开失败并写诊断日志。

## 8. 自定义面板

```java
api.registerSurfaceProvider(new RpgSurfaceProvider());
```

- 需要客户端 mod 同时支持该 `SurfaceType`；服务端不能单独发明。
- 未实现的面板在客户端返回 `UI_READY(state=4)`，服务端回退原版。
- 新面板需要协议常量、特性位、客户端适配器；属于高级扩展。

## 9. 状态与事件

```java
api.setState(player, uiId, "selected", 3);
Optional<Object> value = api.getState(player, uiId, "selected");

api.addEventListener(SessionOpenEvent.class, event -> ...);
api.addEventListener(ElementEvent.class, event -> ...);
api.addEventListener(ActionResultEvent.class, event -> ...);
```

- 状态作用域：`session`、`player.vars`（v2）。
- 事件只读；不要在主线程外修改会话。
- 事件监听器异常被捕获，不影响 UI。

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

- `cloudcore-api-testkit`：内存 API、假玩家、文档断言、事件捕获。
- 附属插件可在无 Paper 的环境下测试页面构造和动作逻辑。
- 提供示例 addon：注册动作、占位符、动态页面、事件监听。

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
