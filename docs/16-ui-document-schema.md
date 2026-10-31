# 16 — UI 文档数据模型

本文定义**下发到客户端的 `UiDocument`** 的字段、类型和边界。作者写的 YAML 是另一层：
`includes`、`repeat`、`state`、动作体、`url:` 都在服务端被处理，不会原样出现在客户端。

## 1. 两种形态

| 形态 | 位置 | 内容 |
| --- | --- | --- |
| 作者 YAML | `plugins/CloudCore/pages/` | 多文件、片段、`repeat`、`state`、动作、`url:` |
| 下发文档 JSON | `UI_DEFINITION` | 已合并、已展开、已求值的元素树；主题令牌；字体；面板选项；事件→actionId |
| 服务端私有定义 | `DefinitionStore` | 动作体、命令、权限、状态初始值、作者文件路径 |

客户端只接收下发文档。它不解析 YAML、不求值模板、不执行动作、不展开 `repeat`。

## 2. 顶层字段

```json
{
  "schema": 1,
  "id": "shop_main",
  "surface": "container",
  "title": { "runs": [ { "text": "商店", "color": "#FFE8C0" } ] },
  "theme": "dark",
  "size": [176, 166],
  "scale": 1.0,
  "container": { "rows": 6, "interaction": "managed" },
  "surface_options": {},
  "fonts": {},
  "elements": []
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `schema` | int | 是 | 当前为 `1` |
| `id` | string | 是 | 页面 id |
| `surface` | enum | 是 | `custom_screen` / `container` / `player_inventory` / `hud` / `tooltip` / `boss_bar` |
| `title` | TextRuns | 是 | 纯文本/样式段，不含事件 |
| `theme` | string | 否 | 主题 id；客户端按 `THEME_SET` 解析令牌 |
| `size` | `[int, int]` | 是 | 逻辑画布尺寸 |
| `scale` | float | 否 | `1.0` 为默认；容器/背包固定 `1.0` |
| `container` | ContainerSpec | 条件 | `surface=container` 必填 |
| `surface_options` | object | 否 | 按面板类型定义 |
| `fonts` | map<string, FontSpec> | 否 | 字体名 → 定义 |
| `elements` | Element[] | 是 | 已展开、已求值 |

**不在下发文档中的内容**：`state`、`placeholders`、`actions`、`includes`、`repeat`、`permission`、
`url:`、作者文件路径、服务器命令。它们只存在于服务端。

## 3. TextRuns

```json
{
  "runs": [
    { "text": "余额: ", "color": "#FFE6E6E6" },
    { "text": "120", "color": "#FFFFAA00", "bold": true }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `text` | string | 文本段 |
| `color` | string | `#AARRGGBB` |
| `bold` / `italic` / `underline` / `strikethrough` / `obfuscated` | bool | 样式 |
| `font` | string | 可选，覆盖元素字体 |

服务端在编译时把 `&` 格式码、翻译键和占位符结果转成 TextRuns；客户端不再解析。

## 4. Element 公共字段

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `id` | string | — | 合并后全局唯一 |
| `type` | enum | — | 元素类型 |
| `x` / `y` | int | 0 | 相对父内容盒 |
| `width` / `height` | int / string | 内容尺寸 | 百分比如 `"60%"`；`"fill"` |
| `anchor` | enum | `top_left` | 锚点 |
| `visible` | bool | true | 已求值 |
| `enabled` | bool | true | 已求值 |
| `invalid` | bool | false | 输入框错误态 |
| `z` | int | 0 | 父容器内绘制顺序 |
| `tooltip` | string | null | 悬停提示 |
| `tooltip_ref` | string | null | 共享提示 id |
| `style` | Style | null | 样式 |
| `events` | map<string,int> | null | 事件名 → `actionId` |
| `children` | Element[] | null | 容器子元素 |

`events` 只含 actionId，不含命令、权限、动作体。客户端点击后发送 `(elementId, actionId)`。

## 5. 元素类型

### 5.1 容器

| 类型 | 字段 |
| --- | --- |
| `panel` | 公共字段 + `children` |
| `row` / `column` | `gap`、`align`、`justify`、`children` |
| `grid` | `columns`、`cell`、`gap`、`children` |
| `scroll` | `axis`、`scrollbar`、`children` |
| `overlay` | `modal`、`dim`、`children`（当前只递归子元素，`modal`/`dim` 未实现） |

### 5.2 内容元素

| 类型 | 字段 |
| --- | --- |
| `label` | `text`(TextRuns)、`font`、`color`、`shadow`、`align`、`text_from`、`wrap`、`max_lines` |
| `image` | `texture`、`fit`（`stretch`/`contain`/`cover`）、`tint`、`nine_slice`、`layers`、`animated`、`loop`、`speed` |
| `button` | `text`(TextRuns)、`icon`（贴图引用或 `{item, count}`）、`style`、`events` |
| `icon_button` | `icon`、`style`、`events` |
| `text_field` | `placeholder`、`max_length`、`filter`、`sensitive`、`invalid`、`style`（含 `padding`、`cursor_color`、`selection_color`、`clear_button`）、`events` |
| `checkbox` | `text`(TextRuns)、`checked`、`events` |
| `slider` | `value`、`min`/`max`/`step`/`live`、`events` |
| `progress` | `value`、`value_from`、`texture_bg`、`texture_fill`、`direction`（left/right/up/down）、`style` |
| `inventory_grid` | `slots`、`origin`、`cell`、`gap`、`columns`、`render_slots`、`render_items` |
| `item_icon` | `item`（id/count/components）、`scale`、`show_count`、`show_durability` |
| `hotbar` | 公共字段；由 HUD 适配器用玩家实时物品绘制，渲染列表本身不产出 |
| `spacer` | 公共字段 |

`repeat` 不是下发元素类型：服务端展开后生成 `<repeatId>[index].<childId>` 的普通元素。

## 6. Style

```json
{
  "background": "inline:panel_dark",
  "nine_slice": [8, 8, 8, 8],
  "border": { "color": "#40FFFFFF", "width": 1 },
  "shadow": { "color": "#80000000", "offset": [0, 2], "blur": 4 },
  "padding": [8, 8, 8, 8],
  "margin": [0, 0, 0, 0],
  "text": { "color": "#FFFFFFFF", "font": "body", "shadow": true },
  "opacity": 1.0,
  "states": {
    "hover": { "background": "inline:panel_hover" },
    "pressed": { "opacity": 0.85 },
    "disabled": { "opacity": 0.4 }
  }
}
```

- 贴图字段是纹理引用（见第 8 节）。
- 颜色字符串是 `#AARRGGBB`。
- `tx:<name>` 是主题令牌；客户端按 `THEME_SET` 解析，服务端在编译时校验令牌存在。
- `states` 的键是 `normal` / `hover` / `pressed` / `disabled`。

## 7. FontSpec

```json
{
  "provider": "nanovg_ttf",
  "source": "local",
  "id": "公共/字体/body.ttf",
  "fallback": "公共/字体/fallback.ttf",
  "line_gap": 1,
  "shadow": false
}
```

| 字段 | 值 |
| --- | --- |
| `provider` | `nanovg_ttf` / `nanovg_bitmap` / `native` |
| `source` | `local` / `mod` / `resource` |
| `id` | 字体文件标识 |
| `fallback` | 下一个字体名或 id |
| `line_gap` | 行距 |
| `shadow` | 默认阴影 |

`native` 表示使用原版 `Font`，只用于必须原版绘制的文本（EditBox、Tooltip 内容、部分 Boss 标题）。

## 8. 纹理引用

下发文档使用四种引用：

| 形式 | 含义 |
| --- | --- |
| `local:path` | `.minecraft/resourcepacks/CloudCore/<path>`，可含业务分类目录；客户端已实现 PNG（`NativeImage`）加载与 `DynamicTexture` 绘制，GIF 由动画解码器处理，JPEG 不支持 |
| `inline:key` | 服务端通过 `TEXTURE_PUT` / `TEXTURE_REF` 关联的贴图 |
| `pack:namespace:path` | 资源包贴图 |
| `builtin:name` | 模组 jar 内置，仅最小回退 |

作者写的 `url:` 在编译时转成 `inline:<key>`，并生成 `TEXTURE_REF`（URL、SHA-256、尺寸）或
`TEXTURE_PUT`（代理模式）。客户端看不到 URL 的原始语法。

## 9. SurfaceOptions

`surface_options` 里的贴图字段也可以使用 `tx:` 主题令牌，解析规则与 Style 相同。
**当前 `tx:` 只在客户端颜色解析处识别，无法解析的令牌回退默认值；服务端不做令牌校验，
`THEME_SET` 未接线。**

### 9.1 HUD

```json
{
  "layer": "background",
  "anchor": "bottom_center",
  "offset_x": 0,
  "offset_y": -4,
  "priority": 0,
  "replace": ["health", "food", "xp", "hotbar"]
}
```

`replace` 取值：`health`（心/护甲/氧气）、`food`（饥饿）、`xp`（经验条）、`hotbar`（快捷栏，
连带选中物品名与攻击指示器）。

### 9.2 玩家背包

```json
{
  "recipe_book": "hide",
  "show_effects": true,
  "show_player_model": true,
  "player_model_x": 50,
  "player_model_y": 78,
  "player_model_scale": 30
}
```

`recipe_book` 取 `hide`（默认）/`vanilla`/`skinned`/`custom`；`skinned` 与 `custom` 当前回退到
`vanilla`。`player_model_*` 是相对面板左上角的文档像素坐标，`scale` 范围约 8..120。

### 9.3 Tooltip

```json
{
  "max_height": 160,
  "scroll_step": 12,
  "smooth_scroll": true,
  "line_gap": 2,
  "max_lines": 256,
  "follow_cursor": true,
  "sync_scroll": false,
  "scrollbar": {
    "mode": "auto",
    "width": 6,
    "gap": 2,
    "track": "inline:scroll_track",
    "thumb": "inline:scroll_thumb",
    "thumb_hover": "inline:scroll_thumb_hover",
    "min_thumb": 12,
    "nine_slice": [2, 2, 2, 2],
    "color": "#FFFFFFFF",
    "draggable": true
  }
}
```

当前客户端读取 `mode`（或顶层 `scroll`/`scrollable`）、`width`、`min_thumb`、`track_color`、
`thumb_color`、贴图形式的 `track`/`thumb`/`thumb_hover` 与 `nine_slice`、`draggable`；
`gap` 尚未使用。顶层 `scroll_step`（默认 12 px）、`smooth_scroll`（默认开）与 `sync_scroll`（默认关）已接线。
`scroll` 顶层开关与 `backdrop_color`、`padding`、`name`/`lore`/`icon`、`attachments`
均已接线（见 `03-ui-dsl.md` 第 11 节）。

### 9.4 Boss 血条

```json
{
  "position": "top_center",
  "spacing": 19,
  "max_bars": 3,
  "title_renderer": "native",
  "bar": {
    "width": 182,
    "height": 12,
    "background": "inline:boss_bg",
    "fill": "inline:boss_fill",
    "overlay": "inline:boss_overlay",
    "nine_slice": [2, 2, 2, 2],
    "fill_inset": [2, 2, 2, 2],
    "tint_from_event": true,
    "tint_alpha": 0.62,
    "direction": "left_to_right",
    "show_name": true,
    "text": { "font": "body", "color": "#FFFFFFFF", "shadow": true, "align": "center" }
  }
}
```

`position`、`title_renderer`、`direction` 已定义但未接线（当前固定屏幕居中、原版字体、
从左到右）。`text` 还支持 `show_layer_count` 与 `counter_color`（多层血条的 `2/3` 计数）。

## 10. ContainerSpec

```json
{
  "rows": 6,
  "interaction": "managed",
  "hide_player_inventory": false,
  "allow_player_inventory": false
}
```

`rows` 1..6；`interaction` 为 `managed` / `readonly` / `passthrough`；
`hide_player_inventory` 让容器页不绘制下方玩家背包槽位；`allow_player_inventory`
让 `readonly` 容器放行玩家自己槽位上的点击与拖拽。

## 11. 限额

| 项目 | 上限 | 强制点 |
| --- | --- | --- |
| 文档 JSON | 1 MiB | 协议 `Limits`（S2C 帧 1 MiB） |
| 页面文件 | 256 KiB | `PageLoader.MAX_FILE_BYTES` |
| 元素数 | 512 | `PageLoader.MAX_ELEMENTS`（客户端校验器用 10,000 作为防御上限） |
| 树深 | 32 | 目标值；客户端校验器当前用 64 |
| `repeat` 实例 | 256/单 repeat | `PageLoader.MAX_REPEAT_INSTANCES` |
| 片段文件 | 64 | `PageLoader.MAX_FRAGMENT_FILES` |
| include 深度 | 8 | `PageLoader.MAX_INCLUDE_DEPTH` |
| `inventory_grid` 槽位 | 容器大小 | 校验器 |
| 字体数/文档 | 16 | 目标值，未强制 |
| 纹理引用数/文档 | 256 | 目标值，未强制 |

超过限额：服务端拒绝编译或拒绝更新；客户端防御性拒绝。

## 12. 校验

- 同一 schema 主版本内，未知字段**忽略**（支持次版本追加）；未知元素类型保留并告警
  （前向兼容），未知枚举值拒绝。
- 类型、范围、限额严格校验；不允许用未知字段绕过上限。
- 元素 id 唯一；事件 → actionId 存在。
- 面板与文档类型匹配：HUD/Tooltip/Boss 不允许槽位；容器必须有 `container`。
- 客户端再次校验，但只做防御性检查，不替代服务端。
- 尚未强制（设计基线）：槽位映射完整/不重叠、主题令牌存在、字体 provider 合法性、
  字体数与纹理引用数上限。
- 元素属性警告（不拒绝文档，`/cloudcore validate` 会逐条列出）：
  - `CC-DOC-022` `progress.direction` 不是 left/right/up/down（按 left 绘制）；
  - `CC-DOC-023` `slider.value` 超出 `min..max`（默认 `0..1`；客户端钳制，占位符不在此检查）；
  - `CC-DOC-024` 未知 `text_field.filter`（放行输入，客户端记 `CC-INPUT-002`）；
  - `CC-DOC-025` `icon` 既不是贴图引用字符串也不是 `{item, count}`（不绘制图标）。

## 13. 版本

- `schema` 主版本变化 = 不兼容，客户端拒绝。
- 次版本变化 = 追加字段，旧客户端忽略未知字段。
- 协议次版本与 schema 次版本独立；两者都要在 `HELLO`/`HELLO_ACK` 协商。
- 任何字段删除/语义变化必须提升 schema 主版本，并在 `10-risks-and-verification.md` 记录迁移。

## 14. 示例

作者 YAML：

```yaml
id: shop
surface: container
container: { rows: 6, interaction: managed }
state: { balance: 0 }
elements:
  - type: label
    id: balance
    text: "余额: ${state.balance}"
    style: { text: { color: "#FFFFFFFF", font: "body" } }
  - type: button
    id: buy
    text: "购买"
    on:
      click:
        - action: message
          value: "shop buy"
```

下发文档（示意）：

```json
{
  "schema": 1,
  "id": "shop",
  "surface": "container",
  "title": { "runs": [ { "text": "商店", "color": "#FFE8C0" } ] },
  "theme": "dark",
  "size": [176, 166],
  "scale": 1.0,
  "container": { "rows": 6, "interaction": "managed" },
  "surface_options": {},
  "fonts": {
    "body": {
      "provider": "nanovg_ttf",
      "source": "local",
      "id": "公共/字体/body.ttf",
      "fallback": "公共/字体/fallback.ttf",
      "line_gap": 1,
      "shadow": false
    }
  },
  "elements": [
    {
      "id": "balance",
      "type": "label",
      "text": { "runs": [ { "text": "余额: 0", "color": "#FFFFFFFF" } ] },
      "style": { "text": { "color": "#FFFFFFFF", "font": "body" } }
    },
    {
      "id": "buy",
      "type": "button",
      "text": { "runs": [ { "text": "购买", "color": "#FFFFFFFF" } ] },
      "events": { "click": 38412 }
    }
  ]
}
```

动作体和命令字符串只保留在服务端的编译结果里；客户端只拿到 `actionId`。
