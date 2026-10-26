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
| ItemTooltip | `4` | 持久 | 推送样式文档 | `GuiGraphics#renderTooltip` + `GuiEventListener#mouseScrolled` |
| Boss 血条 | `5` | 附加 | 创建带哨兵的 BossBar、绑定文档 | `BossHealthOverlay#render` |

新增面板类型需要同时修改：

1. `cloudcore-protocol` 的 `SurfaceType`；
2. `cloudcore-api` 的文档 `surface` 枚举；
3. `cloudcore-client` 的 `SurfaceAdapter`；
4. `cloudcore-server-core` / `cloudcore-bukkit` 的 `SurfaceProvider`；
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
  `player.openInventory(...)`。
- **客户端**：`Minecraft#setScreen(ContainerScreen)` 被 `@ModifyVariable` 替换为
  `CloudContainerScreen`。
- **槽位**：来自原版 `AbstractContainerMenu`；`SlotLayout` 覆盖 `slot.x/y`；未声明槽位移出屏幕。
- **点击**：`managed` / `readonly` / `passthrough` 由服务端 `InventoryClickEvent` 决定。
- **旧界面**：`InventoryOpenEvent` 生成合成文档，`passthrough`，不取消事件。
- **支持的容器**：箱子、陷阱箱、末影箱、木桶、潜影盒（需确认标题/槽位映射）、自定义插件容器。
  铁砧、村民交易、附魔台等交互模型特殊的容器默认跳过。
- **失败**：绑定超时 5 秒；客户端回退原版界面。

## 4. 玩家背包

- **绑定**：持久；服务端在握手完成、reload、权限变化时推送。
- **客户端**：`Minecraft#setScreen` 看到 `InventoryScreen` 时替换为 `CloudInventoryScreen`。
- **槽位**：来自 `InventoryMenu`，按组映射：
  - 合成输出 0（只读）
  - 合成输入 1–4
  - 护甲 5–8
  - 主物品栏 9–35
  - 快捷栏 36–44
  - 副手 45
- **配方书**：`recipe_book` 四档：
  - `hide`：隐藏；
  - `vanilla`：原版完整行为；
  - `skinned`（v1 默认）：保留原版列表/搜索/标签页/滚动，NanoVG 替换外框、按钮、标签、滚动条、
    字体；
  - `custom`（v2）：Cloud 画列表和分类，点击通过原版 `PlaceRecipe` 放置；REI/EMI 接管时让位。
- **玩家模型与效果**：由 `surface_options.player_inventory` 控制，仍由原版渲染。
- **绘制**：NanoVG 画背景/装饰，原版画物品、玩家模型、状态效果。
- **创造模式**：`CreativeModeInventoryScreen` 不在 v1 范围。
- **失败**：皮肤文档无效时完全使用原版背包，不部分应用。

## 5. HUD

- **绑定**：持久；`UI_OPEN(surface=HUD, bindMode=PERSISTENT, surfaceKey=layer)`。
- **客户端**：`HudElementRegistry` 注册一次，之后由会话状态驱动。
- **层**：背景、前景、最高层；映射到 `VanillaHudElements` 锚点。
- **限制**：只读；无槽位、无输入、无 Tooltip 滚动。
- **绘制**：整层由 NanoVG 渲染到 FBO 后合成；物品图标由原生桥绘制。
- **更新**：`ELEMENT_UPDATE`；没有可见元素时不重绘 FBO。
- **失败**：文档无效时移除该层，其他层继续。

## 6. ItemTooltip

- **绑定**：持久；全局一份样式文档，`surfaceKey=default`。
- **内容来源**：客户端原版物品提示 + `ItemTooltipCallback` 追加行。
- **样式来源**：服务端文档（外框、字体、颜色、内边距、行距、最大高度、滚动）。
- **绘制**：NanoVG 画外框/背景/滚动条，原版画文本内容。
- **滚动条样式**：轨道、滑块、悬停态贴图、九宫格、宽度、间距、最小滑块高度、颜色、是否可拖动，
  全部来自 `surface_options.tooltip.scrollbar`。
- **滚动**：
  - 内容超过可见高度时进入滚动模式；
  - 固定提示框位置；
  - 滚轮按 `scroll_step` 滚动，可选 100 ms 平滑；
  - 可拖动滑块，与滚轮共享偏移；
  - 悬停物品变化时偏移归零；
  - 内容未超高时不消费滚轮。
- **服务端逐行内容**：v1 不支持；可以通过物品 `lore` 组件追加。
- **失败**：无法接管时原版提示；滚动失效时退回不滚动。
- **兼容**：其他 Tooltip 模组、REI/EMI 的追加行应保留。

## 7. Boss 血条

- **绑定**：服务端创建 `KeyedBossBar`，标题加哨兵 `\u0000CCB:<token>\u0000`；
  `UI_OPEN(surface=BOSS_BAR, bindMode=PERSISTENT, surfaceKey=<hex token>)`。
- **客户端**：`BossHealthOverlay#render` 遍历事件，匹配哨兵的血条用 Cloud 文档绘制，其余原版绘制。
- **数据**：进度、颜色、样式来自 `BossEvent`；文档只负责外观。
- **外观**：NanoVG 画背景、填充、覆盖层；九宫格、填充方向、文字字体/颜色/阴影/对齐来自
  `surface_options.boss_bar.bar`；`tint_from_event` 决定是否用事件颜色给填充染色。标题默认原版绘制，
  可配置走 NanoVG。
- **堆叠**：顶部居中，间距与最大条数由 `surface_options.boss_bar` 控制。
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
4. 服务端实现 `SurfaceProvider`。
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
