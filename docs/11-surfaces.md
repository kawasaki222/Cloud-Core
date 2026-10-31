# 11 — 面板（Surface）设计

面板是 Cloud Core 的一等公民。每种可定制目标都有独立的生命周期、绑定方式和客户端钩子，但共享同一套
文档模型、状态更新和事件协议。

## 1. 总览

| 面板 | 类型值 | 生命周期 | 服务端职责 | 客户端钩子 |
| --- | --- | --- | --- | --- |
| 自定义页面 | `0` | 临时 | 发送文档、会话、状态 | `Minecraft#setScreen` |
| 箱子/容器 | `1` | 临时 | 打开后备物品栏、点击策略 | `Minecraft#setScreen` |
| 玩家背包 | `2` | 持久 | 推送皮肤、权限检查 | `Minecraft#setScreen` 识别 `InventoryScreen` |
| HUD | `3` | 持久 | 推送层文档、状态更新 | `HudElementRegistry` |
| ItemTooltip | `4` | 持久 | 推送样式文档 | `GuiGraphics#renderTooltip` + `MouseHandler#onScroll` |
| Boss 血条 | `5` | 附加 | 创建带哨兵的 BossBar、绑定文档 | `BossHealthOverlay#render` |

新增面板类型需要同时修改：

1. `cloudcore-protocol` 的 `SurfaceType`；
2. `cloudcore-api` 的文档 `surface` 枚举；
3. `cloudcore-client` 的 `SurfaceAdapter`；
4. `SurfaceProvider`（接口在 `cloudcore-api`，由 `server-core`/`bukkit` 或附属插件实现并注册）；
5. 协议特性位与校验规则；
6. 测试矩阵。

## 2. 自定义页面

- **绑定**：`UI_OPEN(surface=CUSTOM_SCREEN, bindMode=NONE)`。
- **客户端**：`Minecraft#setScreen(new CloudScreen(...))`，没有 `AbstractContainerMenu`。
- **槽位**：不允许；文档校验会拒绝 `inventory_grid`。
- **输入**：全部由 Cloud 输入路由处理；Escape/`E` 关闭。
- **失败**：构造异常回退到原界面或关闭。

## 3. 箱子/容器

- **绑定**：先发 `UI_OPEN(surface=CONTAINER, bindMode=NEXT_CONTAINER, token)`，再
  `player.openInventory(...)`。U2 已在真实客户端核实：`InventoryOpenEvent` 中的插件消息先于
  `ContainerScreen` 的 `setScreen` 到达。
- **客户端**：`Minecraft#setScreen(ContainerScreen)` 被 `@ModifyVariable` 替换为
  `CloudContainerScreen`。已实现最小替换：自定义背景 + 原版槽位绘制；点击仍走原版 menu。
- **槽位**：来自原版 `AbstractContainerMenu`；`SlotLayout` 覆盖 `slot.x/y`；未声明槽位移出屏幕。
- **点击**：`managed` / `readonly` / `passthrough` 由服务端 `InventoryClickEvent` 决定。
- **旧界面**：`InventoryOpenEvent` 生成合成文档，`passthrough`，不取消事件。
- **支持的容器**：箱子、陷阱箱、末影箱、木桶、潜影盒（需确认标题/槽位映射）、自定义插件容器。
  铁砧、村民交易、附魔台等交互模型特殊的容器默认跳过。
- **失败**：绑定超时 5 秒；客户端回退原版界面。

## 4. 玩家背包

- **绑定**：持久；服务端在握手完成、reload、权限变化时推送。
- **客户端**：`Minecraft#setScreen` 看到 `InventoryScreen` 时替换为 `CloudInventoryScreen`。
  已实现皮肤：`CloudInventoryScreen` 继承 `AbstractRecipeBookScreen<InventoryMenu>`，
  复用 `player.inventoryMenu`（容器 0），画布坐标即文档坐标；绘制自绘面板底色、每个槽位的槽框，
  再叠加文档元素，并按 `surface_options` 决定配方书按钮、玩家预览和状态效果。
  皮肤在 `BindMode.PERSISTENT` 下持续生效到 `UI_CLOSE`，非持久绑定只作用于下一次打开。
  `UI_CLOSE` 后下一次按 `E` 回到原版背包；已打开的界面保持到玩家关闭。
- **槽位**：来自 `InventoryMenu`，按组映射：
  - 合成输出 0（只读）
  - 合成输入 1–4
  - 护甲 5–8
  - 主物品栏 9–35
  - 快捷栏 36–44
  - 副手 45
- **配方书**：`recipe_book` 四档：
  - `hide`（默认）：组件 `setVisible(false)`，并移除原版右上角按钮；`E` 打开的背包没有配方书入口；
  - `vanilla`：保留原版配方书组件、搜索/标签页/滚动和按钮，点击按钮正常展开；
  - `skinned`（当前回退到 `vanilla`）：原版列表/搜索/标签页/滚动，后续用 NanoVG 替换外框、按钮、
    标签、滚动条、字体；
  - `custom`（当前回退到 `vanilla`）：后续由 Cloud 画列表和分类，点击通过原版 `PlaceRecipe` 放置；
    REI/EMI 接管时让位。
- **玩家模型**：`surface_options.player_inventory.show_player_model: true` 时用 1.21.11 的
  `GuiGraphics.submitEntityRenderState` 绘制实时玩家预览，位置/大小可由 `player_model_x`、
  `player_model_y`、`player_model_scale` 调整（默认 `50/78/30`，文档坐标）。
- **玩家效果**：`surface_options.player_inventory.show_effects: true` 时复用原版
  `EffectsInInventory`，在背包右侧绘制状态效果图标与剩余时间；关闭时不绘制。
- **绘制**：自绘面板与槽框 + 文档元素（RenderList）；物品、数量、冷却、附魔光效仍由原版绘制，
  空槽图标由原版 `Slot#getNoItemIcon` 提供。
- **创造模式**：`CreativeModeInventoryScreen` 不在 v1 范围。
- **失败**：皮肤文档无效时完全使用原版背包，不部分应用。

## 5. HUD

- **绑定**：持久；`UI_OPEN(surface=HUD, bindMode=PERSISTENT, surfaceKey=layer)`。
- **客户端**：`HudElementRegistry` 注册一次，之后由会话状态驱动。
- **层**：`surfaceKey` 取 `background` / `foreground` / `overlay`；实现用 Fabric
  `HudElementRegistry.addFirst`（背景，排在原版元素之前）与 `addLast`（前景、覆盖层，依次在前一个之后）。
- **锚点**：`surface_options.anchor`（`top_left`/`top_center`/`top_right`/`center*`/`bottom*`，
  默认 `top_left`）加 `offset_x`/`offset_y`；文档声明自己的尺寸，锚点是相对该尺寸计算。
- **替换原版**：`surface_options.replace: [health, food, xp, hotbar]` 让 `GuiMixin` 取消对应原版绘制，
  文档自己画。`hotbar` 元素由 HUD 适配器用玩家实时物品绘制（框贴图 + 物品图标 + 数量 + 选中槽）。
- **实时数值**：元素上的 `value_from`/`text_from` 每帧读取玩家血量/饥饿/经验/氧气/护甲/等级，
  数值不变时不重建文档（`HudValues`）。
- **限制**：只读；无槽位、无输入、无 Tooltip 滚动。
- **绘制**：整层由 NanoVG 渲染到 FBO 后合成（NanoVG 启用时；当前默认是原生后端）；
  物品图标由原生桥绘制。
- **更新**：状态变化时服务端重编译并下发更高 revision 的完整文档（HUD 支持 `ELEMENT_UPDATE` 增量）；
  没有可见元素时不重绘 FBO。
- **失败**：文档无效时移除该层，其他层继续。

## 6. ItemTooltip

- **绑定**：持久；全局一份样式文档，`surfaceKey=default`。
- **内容来源**：客户端原版物品提示 + `ItemTooltipCallback` 追加行。
- **样式来源**：服务端文档（外框、字体、颜色、内边距、行距、最大高度、滚动）。
- **绘制**：客户端画外框/背景/滚动条与裁剪，文本与图片仍用原版
  `ClientTooltipComponent#renderText/#renderImage` 绘制。
- 已实现：`surface_options.max_height`（默认 160）限制高度；超出时按 `line_gap` 裁剪并用
  `MouseHandler#onScroll` 的滚轮改变偏移（步长 12，悬停内容变化时归零）；右侧滚动条
  可用 `scrollbar.track_color`/`thumb_color`、`scrollbar_width` 配色。
  背景取文档里第一个带 `style.background` 的元素：贴图引用（可加 `style.nine_slice`）按提示框
  尺寸九宫格拉伸，颜色引用则直接填充；都没有时用默认深色。
  `tooltip_scroll` 事件回传服务端仍待动作引擎。
- **滚动条样式**：已接线的是 `scrollbar.width`（或顶层 `scrollbar_width`）、`track_color`、
  `thumb_color`。`gap`、贴图形式的 `track`/`thumb`/`thumb_hover`、`min_thumb`、`nine_slice`、
  `draggable` 已定义但未实现。
- **滚动**：
  - 内容超过可见高度时进入滚动模式；
  - 固定提示框位置；
  - 滚轮按 `scroll_step`（默认 12）滚动，`smooth_scroll` 默认开；
  - 悬停物品变化时偏移归零；
  - 内容未超高时不消费滚轮。
- **未实现**：拖动滑块（`draggable`）、滚动条贴图、`tooltip_scroll` 事件回传服务端。
- **服务端逐行内容**：v1 不支持；可以通过物品 `lore` 组件追加。
- **失败**：无法接管时原版提示；滚动失效时退回不滚动。
- **兼容**：其他 Tooltip 模组、REI/EMI 的追加行应保留。

## 7. Boss 血条

- **绑定**：服务端创建 `KeyedBossBar`，标题加哨兵 `\u0000CCB:<token>\u0000`；
  `UI_OPEN(surface=BOSS_BAR, bindMode=PERSISTENT, surfaceKey=<hex token>)`。
  U13/U19 已在真实客户端验证：私有 `drawBar` 可注入，Paper 哨兵标题可被客户端观察。
- **客户端**：`BossHealthOverlay#render` 遍历事件，匹配哨兵的血条用 Cloud 文档绘制，其余原版绘制。
- **数据**：进度与颜色来自 `BossEvent`；文档只负责外观。
- **外观**（已实现）：`surface_options.boss_bar.bar` 支持 `width`（默认 182）、`height`（默认 5）、
  `border`（1px 外框颜色）、`background`（颜色，或贴图引用 + `nine_slice`）、`fill`
  （贴图引用按进度裁剪，或颜色）、`fill_inset`、`overlay`、`tint_from_event`（默认 `true`；
  `false` 时用 `fill` 颜色或默认蓝）、`tint_alpha`（默认 0.62）、`show_name`、
  `text.color`/`shadow`/`align`/`show_layer_count`/`counter_color`。
  标题由适配器画在条内（原版字体），原版名称通过 `@Redirect` 剥离。
- **堆叠**（已实现）：`surface_options.boss_bar.spacing` 控制相邻哨兵血条的垂直间距
  （默认 `0` 沿用原版），`max_bars` 限制最多绘制多少条（默认 `0` 不限制）；只影响哨兵血条，
  不改变原版血条的位置。真机验证：3 条哨兵、`max_bars: 2`、`spacing: 16` 时只显示前两条。
- 未接线：`position`（固定屏幕居中）、`title_renderer: nanovg`（固定原版字体）、
  `direction`（固定从左到右）。
- **原版客户端**：不使用哨兵，看到普通标题。
- **失败**：匹配失败时原版绘制；文档无效时原版绘制。
- **未来**：自绘、脱离原版 `BossEvent` 的血条（v2）。

## 8. 面板之间的交互

| 组合 | 规则 |
| --- | --- |
| 背包 + 容器 | 容器打开时背包界面关闭；容器关闭后不自动重开背包 |
| HUD + 任意界面 | HUD 始终绘制，不参与命中测试 |
| Tooltip + 任意界面 | Tooltip 跟随光标；滚动时消费滚轮，其他界面元素不受影响 |
| Boss + 任意界面 | 顶部叠加；不参与命中测试 |
| 多个覆盖层 | 按 `z` 与打开顺序；模态层阻塞下层输入 |

焦点规则：

| 输入 | 优先级 |
| --- | --- |
| 鼠标点击 | Tooltip 滚动条 → 模态覆盖层 → Cloud 元素（`z` 逆序）→ 原版槽位 → 原版界面 |
| 滚轮 | 可滚动 Tooltip → 最内层 `scroll` → 原版（快捷栏切换） |
| 键盘 | 焦点输入框 → 页面快捷键（Escape 清焦点、E 关闭、Tab 循环）→ 原版 |
| 拖动 | 滚动条/Cloud 元素捕获鼠标直到释放 |

- HUD、Boss 血条永不参与输入；Tooltip 只参与滚轮和滚动条拖动。
- 只有一个 screen 是输入所有者；容器打开时背包皮肤关闭，关闭后不自动重开。
- 模态层捕获输入直到关闭；点击外部清除文本焦点。
- Cloud 元素与槽位重叠时元素优先；文档应避免重叠或声明穿透。
- Escape 先清焦点，再关闭界面（若允许）。

## 9. 扩展步骤

1. 在协议中分配新的 `SurfaceType` 值（`6–127`）。
2. 增加特性位。
3. 客户端实现 `SurfaceAdapter`：
   - `canHandle` 检查环境；
   - `open` 安装钩子；
   - `close` 幂等拆除；
   - `render` 或钩子绘制。
4. 服务端实现 `SurfaceProvider`（`cloudcore-api` 的 `com.cloudcore.api.surface`），
   经 `CloudCoreApi#registerSurfaceProvider` 注册。
5. 文档 `surface` 枚举与校验规则。
6. 单元测试、手动矩阵、互操作检查。

禁止事项：

- 新面板不得改变已有面板的槽位或输入语义。
- 新面板不得在客户端执行文档中的任意代码。
- 新面板必须有原版回退路径。

## 10. 数据流总结

```
服务端状态 ──模板/遍历──► 文档修订 ──UI_DEFINITION──► 客户端缓存
     │                                              │
     └──差异──► ELEMENT_UPDATE ────────────────────►│
                                                    ▼
                                            面板适配器绘制
                                                    ▲
客户端输入 ──EVENT──► 校验/动作 ─────────────────────┘
```

面板只改变"绘制到哪里、输入从哪里来"，不改变文档、状态和校验模型。
