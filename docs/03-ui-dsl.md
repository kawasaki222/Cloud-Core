# 03 — UI 定义语言

服务端用 YAML 编写页面。页面可以是一个文件，也可以由多个片段组合（见
`12-page-composition.md`）。服务端把页面编译成 `UiDocument`，序列化为 JSON，通过 `UI_DEFINITION`
下发。客户端只看到编译后的文档；下发字段与类型见 `16-ui-document-schema.md`。

## 1. 文件与 id

```
plugins/CloudCore/
  pages/
    shop/
      page.yml          # 页面清单，可 include 片段
      parts/
        header.yml
        grid.yml
        footer.yml
    hud_status.yml      # 单文件页面
    inventory_skin.yml
    tooltip_theme.yml
    boss_theme.yml
  themes/
    dark.yml
  fonts/                # 可选字体索引（指向客户端 local/pack 相对路径）
  textures/
```

- 页面 id 匹配 `[a-z0-9_.-]{1,48}`，默认取文件/目录名。
- 元素 id 在合并后的页面内唯一，匹配 `[a-zA-Z0-9_.-]{1,64}`。
- 每个文件都声明 `schema: 1`。

## 2. 页面结构

```yaml
schema: 1
id: shop
surface: container          # custom_screen | container | player_inventory | hud | tooltip | boss_bar
title: "&8商店"
theme: dark
size: [176, 166]
scale: 1.0
container:
  rows: 6
  interaction: managed
surface_options: {}
fonts: {}
state: {}
elements: []
includes: []                # 片段列表，见 12-page-composition.md
```

`surface` 决定服务端如何打开、客户端用哪个 `SurfaceAdapter`：

| 值 | 说明 |
| --- | --- |
| `custom_screen` | 独立页面，不绑定容器 |
| `container` | 箱子类容器，必须有 `container` 段 |
| `player_inventory` | 玩家背包皮肤，持久生效 |
| `hud` | HUD 层，持久生效 |
| `tooltip` | Tooltip 样式，持久生效 |
| `boss_bar` | Boss 血条样式，由服务端哨兵绑定 |

`container.interaction`：`managed` / `readonly` / `passthrough`，含义见 `04-server.md`。

`surface_options` 按面板类型分别定义：

```yaml
surface_options:
  hud:
    layer: foreground        # background | foreground | overlay
  player_inventory:
    recipe_book: skinned     # hide | vanilla | skinned | custom
    show_effects: true
    show_player_model: true
  tooltip:
    max_height: 160
    scroll_step: 12
    smooth_scroll: true
    line_gap: 2
    max_lines: 256
    follow_cursor: true
    scrollbar:
      mode: auto              # auto | always | never
      width: 6
      gap: 2
      track: "tx:scroll_track"
      thumb: "tx:scroll_thumb"
      thumb_hover: "tx:scroll_thumb_hover"
      min_thumb: 12
      nine_slice: [2, 2, 2, 2]
      color: "#FFFFFFFF"
      draggable: true
  boss_bar:
    position: top_center
    spacing: 19
    max_bars: 3
    title_renderer: native      # native | nanovg
    bar:
      width: 182
      height: 12
      background: "tx:boss_bg"
      fill: "tx:boss_fill"
      overlay: "tx:boss_overlay"
      nine_slice: [2, 2, 2, 2]
      tint_from_event: true
      direction: left_to_right
      show_name: true
      text:
        font: "tx:font.body"
        color: "#FFFFFFFF"
        shadow: true
        align: center
```

## 3. 坐标系

- 单位是应用客户端 GUI 缩放后的 **GUI 像素**。
- 原点为 UI 画布左上角。
- 容器界面默认居中；根元素 `anchor: screen` 可改用屏幕坐标。
- HUD、Tooltip、Boss 血条使用屏幕空间加边缘锚点。

### 3.1 锚点

```yaml
anchor: top_left | top_center | top_right
        middle_left | center | middle_right
        bottom_left | bottom_center | bottom_right
offset: [0, 0]
```

`width`/`height` 接受整数、`"N%"` 或 `fill`；`x`/`y` 接受整数偏移；`center` 是居中简写。

## 4. 容器

### 4.1 `panel`

```yaml
- type: panel
  id: root
  x: 0
  y: 0
  width: 176
  height: 166
  style:
    background: "tx:panel"
    nine_slice: [8, 8, 8, 8]
    padding: [8, 8, 8, 8]
```

### 4.2 `row` / `column`

```yaml
- type: column
  id: list
  gap: 4
  align: start
  justify: start
  children: []
```

### 4.2b `row` / `column`

```yaml
- type: row        # 或 column
  id: actions
  gap: 4
  children: []     # 子元素按自身 width/height 依次排列
```

已实现：`row` 沿 X 轴、`column` 沿 Y 轴依次排布子元素（子元素自身的 `x/y` 仍然叠加），`gap` 为间距。
单位是文档坐标；未声明尺寸的子元素不占位，需要作者显式给出。

### 4.3 `grid`

```yaml
- type: grid
  id: grid
  columns: 9
  cell: [18, 18]
  gap: 2          # v1 用单个数值（X/Y 相同）
  children: []
```

已实现：按 `columns` 换行、`cell` 决定步进、`gap` 为间距；`cell` 缺省时按宽度均分。

### 4.4 `scroll`

```yaml
- type: scroll
  id: scroller
  axis: vertical
  scrollbar: auto
  scroll_x: 0        # 服务端下发的初始偏移（v1 已实现）
  scroll_y: 26
  children: []
```

v1 已实现：子元素按 `scroll_x`/`scroll_y` 偏移，并裁剪到滚动框；两个渲染后端都支持嵌套裁剪
（原生用 `enableScissor` 求交，NanoVG 用 `nvgSave`/`nvgScissor`/`nvgRestore`）。
滚轮在光标位于滚动框内时改变本地偏移（`ScrollTargets` 命中测试，步长 12，范围
`0..内容高度-视口高度`），只影响客户端显示；`scroll` 事件回传服务端要等动作引擎。

### 4.5 `overlay`

```yaml
- type: overlay
  id: confirm
  modal: true
  dim: 0.6
  children: []
```

### 4.6 `repeat` —— 遍历

根据服务端状态中的列表展开多个实例。展开发生在服务端，客户端只接收展开后的元素树。

```yaml
- type: repeat
  id: item_rows
  source: "${state.items}"     # 必须是列表
  as: item                     # 单项变量名
  index: i                     # 下标变量名
  max: 64                      # 1..256
  gap: 2
  children:
    - type: button
      id: row
      width: 160
      height: 20
      text: "${i}. ${item.name}"
      on:
        click:
          - action: set_state
            key: selected
            value: "${item.id}"
```

规则：

- `source` 必须是 `${state.xxx}` 形式，指向列表；编译期无法验证运行时的列表类型。
- 生成的元素 id 为 `<repeatId>[<index>].<childId>`，例如 `item_rows[3].row`。id 稳定，点击可区分。
- 子元素模板中可以引用 `${item}`、`${item.field}`、`${i}`。
- 展开上限 256 个实例；整个文档元素上限 512。超限在运行时报错并拒绝更新。
- 列表变化触发重新展开。v1 会提升修订号并下发完整文档；后续动作引擎版本会改为增量增删。
- `repeat` 是作者语法；下发文档里只有展开后的普通元素，没有 `repeat` 节点。
- `repeat` 不能嵌套超过 2 层；`scroll` 内的 `repeat` 是推荐用法。
- 静态列表（写死的值）可以在编译期展开，但为了行为一致，v1 统一在状态求值时展开。

### 4.7 `tabs`（v2）

标签条加内容区，v1 不实现。

## 5. 元素（v1）

公共属性：

| 属性 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `type` | string | — | 必填 |
| `id` | string | 自动 | 可交互元素必须显式设置 |
| `x`、`y` | int | 0 | 相对父内容盒或锚点 |
| `width`、`height` | int / `%` / `fill` | 内容尺寸 | |
| `visible` | bool / 模板 | true | |
| `enabled` | bool / 模板 | true | |
| `z` | int | 0 | 父容器内绘制顺序 |
| `tooltip` | string | — | 悬停 400 ms 后显示 |
| `style` | map | 继承 | |
| `tooltip_ref` | string | — | 共享提示元素 id |
| `invalid` | bool / 模板 | false | 错误态，输入框显示 `border.invalid` |

### 5.1 `label`

```yaml
- type: label
  id: title
  text: "&e%player_name% &7的商店"
  font: "tx:font.title"
  color: "#FFE8C0"
  shadow: true
  align: left
  wrap: false
  max_lines: 2
```

颜色接受 `#RRGGBB` 或 `#AARRGGBB`。`%placeholder%` 在服务端解析；`&` 格式码在服务端翻译成文本段。

### 5.2 `image`

```yaml
- type: image
  id: banner
  texture: "pack:cloudcore:gui/shop/banner"
  width: 160
  height: 48
  fit: stretch
  tint: "#FFFFFFFF"
  nine_slice: [4, 4, 4, 4]   # 源贴图像素；已实现（NineSlice.split 生成 9 块）
  animated: auto       # auto | off
  loop: true
  speed: 1.0
```

贴图引用：

| 形式 | 解析方 | 说明 |
| --- | --- | --- |
| `local:<path>` | 客户端 CloudCore 目录 | `.minecraft/resourcepacks/CloudCore/<path>`，直接读文件系统 |
| `inline:<key>` | 客户端 NanoVG image 缓存（回退时 `TextureManager`） | 服务端通过 `TEXTURE_PUT` 推送 |
| `pack:<namespace>:<path>` | 客户端资源管理器 | 标准资源包或其他模组 |
| `builtin:<name>` | 模组 jar | 只保留白色/红色错误/默认字体等最小回退 |
| `url:<https URL>` | **客户端直连（默认）** / 服务端代理 | 服务端只发 `TEXTURE_REF`；图片字节不进游戏连接 |

解析顺序：`local` → `inline` → `pack` → `builtin`。`url:` 在编译时转成 `inline:<key>`，URL/哈希由
`TEXTURE_REF` 或 `TEXTURE_PUT` 下发。

本地资源规则：

- 根目录固定为 `.minecraft/resourcepacks/CloudCore/`；服务端只下发相对路径。
- 可以按业务分类：`CloudCore/副本/...`、`CloudCore/签到/...`、`CloudCore/公共/...`。
  例如 `local:副本/背景/主面板.png`、`local:签到/图标/签到.png`、`local:公共/字体/body.ttf`。
- 客户端直接读文件系统，不要求玩家在资源包列表启用；`F9` 快捷重载。
- 只允许 png/gif/ttf/otf 等白名单扩展名；拒绝 `..`、绝对路径、符号链接、控制字符、双向控制符、
  Windows 保留名和大小写冲突。
- 文件缺失显示红色“资源缺失”，日志记录相对路径和 `CC-TEX-*`/`CC-FONT-*`。

网络图片规则：

- 默认客户端直连：服务端只发 URL、SHA-256 和尺寸提示，图片流量不经过游戏连接。
- 服务端代理模式可选（`textures.url.mode=proxy`），用于隐私、受限网络或直连失败兜底。
- 只允许 `https://`；客户端拒绝私网、回环、链路本地、保留地址和非标准端口。
- 大小 ≤ 1 MiB、尺寸 ≤ 512×512、超时 3 秒、最多 2 次重定向。
- PNG/JPEG 解码为 PNG；GIF 在客户端本地解码（直连）或服务端解码（代理）。
- 按内容哈希缓存；同一 URL 只下载一次。
- 隐私：直连时图片主机能看到玩家 IP；需要隐私的服务器使用代理模式，或客户端设置
  `remoteImages=false` 后请求代理/显示占位。

动画图片：

```yaml
- type: image
  id: animated_banner
  texture: "url:https://cdn.example.com/banner.gif"
  width: 160
  height: 48
  animated: auto       # auto | off
  loop: true
  speed: 1.0
```

- `animated: auto` 时自动播放；`off` 只显示第一帧。
- `loop: false` 播放一次后停在最后一帧。
- `speed` 是播放倍率，范围 0.25..4.0。
- 直连模式下 GIF 由客户端解码、归一化延迟并逐帧上传纹理；代理模式下由服务端完成，
  客户端只做帧轮播。
- 已实现（客户端直连 + 本地文件）：`AnimatedTextures` 用 ImageIO 解码 GIF，帧按累计延迟循环，
  每次绘制按时间选帧；上限 64 帧、单帧 512×512、解码后 16 MiB、最小帧延迟 50 ms。
  `animated: auto|off`、`loop`、`speed`（0.25..4）已解析：`off` 固定首帧，`loop: false` 播完停在末帧，
  `speed` 按倍率缩放帧时间。
- `url:` 在编译时转成 `inline:<key>`；URL、哈希和尺寸通过 `TEXTURE_REF` 或 `TEXTURE_PUT` 另行下发，
  下发文档里不出现原始 URL。
- 上限：64 帧，单帧 ≤ 512×512，解码后总大小 ≤ 16 MiB，最小帧延迟 50 ms。

### 5.3 `button`

```yaml
- type: button
  id: buy
  width: 80
  height: 20
  text: "购买"
  icon: "local:公共/图标/cart.png"
  style:
    background: "tx:button_normal"
    text: { color: "#FFE8E8E8" }
  tooltip: "花费 %price% 金币"
  on:
    click: []
```

动作已实现：`message`、`set_state`、`close_ui`、`command`（配置闸门）、`if`（`condition`+`then`/`else`）、
`delay`（`ticks`+`then`）；`${...}` 支持表达式（算术/比较/`&&`/`||`/`!`/字符串拼接）。

`checkbox` 已实现：`checked` 属性（"true"/布尔）决定绘制，点击发 `change` 事件载荷 `{checked}`，
常用写法 `set_state key: notify value: "${checked}"`。

状态：`normal`、`hover`、`pressed`、`disabled`。已实现 `hover`：元素声明
`style.hover: { background: ... }` 时，客户端命中该元素就用悬停样式重绘（每帧只为变化的悬停目标重建一次列表）。

`on:` 已实现：编译期把每个事件编译成 actionId 写进文档的 `events`，动作体留在服务端。
YAML 1.1 会把裸 `on` 解析成布尔值，加载器在读取时把它规范成字符串键，作者照常写 `on:`。

### 5.4 `icon_button`

只有图标的方形按钮，事件与 `button` 相同。`icon` 接受两种写法：

- 贴图引用：`local:`、`pack:`、`builtin:`、`inline:`，居中缩放，四周留 2px；
- 物品描述：`{ item: "minecraft:diamond", count: 1 }`，用原版物品渲染（数量可选，不显示角标）。

```yaml
- type: icon_button
  id: close
  width: 20
  height: 20
  icon: "pack:cloudcore:ui/close"
  on:
    click:
      - action: close_ui
```

`button` 也可以带同一格式的 `icon`：图标画在左侧 14px，文字从 `x + 21` 开始。
已实现：背景（颜色/贴图/九宫格）、`style.hover`、图标与事件；未实现：`pressed`/`disabled` 样式。

### 5.5 `text_field`

```yaml
- type: text_field
  id: search
  width: 140
  height: 20
  placeholder: "搜索…"
  max_length: 32
  filter: printable
  sensitive: false
  style:
    background: "tx:input_bg"
    nine_slice: [2, 2, 2, 2]
    padding: [3, 3, 3, 3]
    border:
      normal: "#FF3A3A3A"
      focused: "#FF4FA3E8"
      invalid: "#FFFF5555"
    text:
      font: "tx:font.body"
      color: "#FFFFFFFF"
      shadow: false
    placeholder_color: "#80FFFFFF"
    cursor_color: "#FFFFFFFF"          # 尽力支持
    selection_color: "#804FA3E8"       # 尽力支持
    clear_button: "local:公共/图标/clear.png"
  on:
    submit: []
    change: []
```

输入逻辑、输入法、选择、剪贴板和键盘导航使用原版 `EditBox`；外观目前由文档控制背景，
控件本身用原版绘制（剪贴板/IME 行为保持原版）。`change` 事件载荷 `{value}`，ENTER 触发 `submit`，
动作里的 `${value}` 会替换成输入内容。

已实现：

- 背景：颜色，或 `local:`/`pack:`/`builtin:`/`inline:` 贴图加 `style.nine_slice`。
- 边框：`style.border.normal` 常显；元素带 `invalid: true` 时改用 `style.border.invalid`；
  获得焦点时客户端按 `style.border.focused`（默认 `#FF4FA3E8`）叠画 1px 焦点框——
  焦点是控件实时状态，不经过文档。
- 文字颜色：`style.text.color`（`EditBox#setTextColor`）。
- `max_length`（1..256，默认 32）。
- `filter`：`printable`（缺省；放行所有非控制字符，中文/输入法可用）、`digits`/`numeric`、
  `alnum`、`none`，或 `regex:<pattern>`（编译失败记 `CC-INPUT-001` 并放行）。
- `sensitive: true`：客户端用 `EditBox` formatter 把显示替换成 `*`；**服务端仍收到原文**用于校验，
  日志与诊断包绝不记录内容。掩码按等长字符渲染，光标宽度可能与原字不完全一致（尽力支持）。

尚未实现：`padding` 补偿、`cursor_color` / `selection_color`、清除按钮。
输入法候选窗位置由 GLFW/原版处理，不能完全自定义。

边界：

- `cursor_color` / `selection_color` 需要覆盖 `EditBox` 的渲染；标记为尽力支持，不支持时回退原版并记录。
- `padding` 通过外层布局补偿 `EditBox` 内建的文本偏移。
- 不要自己实现文本编辑；IME、选区、剪贴板、键盘重复都是长期坑。

`change` 客户端 250 ms 防抖。服务端仍会重新校验长度、过滤器和权限。

### 5.6 `checkbox`

```yaml
- type: checkbox
  id: remember
  width: 100
  height: 14
  text: "记住选择"
  checked: false
  on:
    change: []
```

### 5.7 `slider`

已实现（M2）：`value` 取 `0..1` 的小数，客户端画轨道 + 填充 + 滑块钮。点击轨道任意位置时，
点击横坐标在轨道内的比例作为 `amount`（`0..1`）随 `change` 事件上报；服务端动作可据此
`set_state`，重编译后的文档把新 `value` 推回客户端。

```yaml
- type: slider
  id: volume_slider
  x: 16
  y: 152
  width: 140
  height: 6
  value: "${state.volume}"
  on:
    change:
      - action: set_state
        key: volume
        value: "${amount}"
```

- 整串占位符（`"${state.volume}"`）保留被解析值的类型，所以 `value` 以数字而非字符串下发；
  客户端对字符串数字也做解析，兼容旧文档与 YAML 里加了引号的数字。
- 未解析的占位符下发为空串，不把 `${...}` 原文写进文档。

尚未实现（计划）：`min`/`max`/`step` 数值区间、拖动滑块（当前只响应点击）、`live`（拖动过程中
连续上报）。在这些补齐前，页面按 `0..1` 使用 `value` 与 `amount`。

### 5.8 `progress`

已实现：`value`（`0..1`）、`direction`（`left` 默认 / `right` / `up` / `down`）、
`texture_bg` 与 `texture_fill`（贴图引用，填充贴图按 `value` 裁剪而不是压扁）、
`style.background` 与 `style.fill_color`（颜色路径）。

```yaml
- type: progress
  id: hp
  x: 12
  y: 66
  width: 200
  height: 12
  value: "${state.hp}"
  direction: right
  texture_bg: "pack:minecraft:block/diamond_block"
  texture_fill: "pack:minecraft:block/emerald_block"
  style:
    fill_color: "#FF3C8CE7"     # 没有 texture_fill 时用这个颜色
```

- 填充区宽/高按 `value` 取整；贴图填充把整张图按进度条的宽高绘制后裁剪到填充区，
  所以纹理不会被拉伸变形。
- `value` 可以来自状态：`set_state` 后文档重编译，进度条随新值更新
  （见 `pages/progress_demo.yml` 的滑块 → 进度条联动）。
- 九宫格背景、圆角与分段（`segments`）尚未实现。

### 5.8b 背景贴图与富文本

- `panel`/`button` 的 `style.background` 可以是贴图引用（`local:`/`pack:`/`builtin:`/`inline:`），
  也可同时给 `style.nine_slice`；颜色仍是默认路径。
- 文本支持 `text: { runs: [ { text, color } ] }`，客户端按段绘制并保持原版字体；
  bold/italic 等字体样式尚未生效（走原版字体时按普通字重绘制）。

### 5.9 `inventory_grid`

```yaml
- type: inventory_grid
  id: grid
  slots: [0, 53]
  origin: [8, 18]
  cell: [18, 18]
  gap: [0, 0]
  columns: 9
  render_slots: true
  render_items: true
```

客户端实现（v1）：

- 1.21.11 的 `Slot#x/#y` 是 `final`，无法原地改坐标；客户端用 `new Slot(container, containerSlot, x, y)`
  重建菜单槽位，保留容器与槽位下标，点击仍按 index 走原版路径。
- 只重建 Cloud 容器自己的槽位（玩家背包之前的那些）；玩家背包皮肤只能重排主物品栏 9–35 与
  快捷栏 36–44（普通 `InventorySlot`）。盔甲/合成/结果/副手槽是 `Slot` 子类，保持原版位置。
- 网格未声明到的容器槽位移到 `(-10000, -10000)`，原版命中测试自然跳过。
- 纯几何（列/行、`origin`、`cell`、`gap`、`columns`）有单元测试 `SlotLayoutTest`。

`managed` 容器的槽位映射必须完整且不重叠。玩家背包皮肤需要覆盖 `InventoryMenu` 的相关槽位组：

| 组 | 槽位范围 | 说明 |
| --- | --- | --- |
| 合成输出 | 0 | 只读 |
| 合成输入 | 1–4 | |
| 护甲 | 5–8 | |
| 主物品栏 | 9–35 | |
| 快捷栏 | 36–44 | |
| 副手 | 45 | |

背包皮肤可以只声明部分组，未声明的槽位保持隐藏，但点击仍由原版处理（除非文档声明 `readonly`）。

### 5.10 `spacer`

在弹性容器中占位。

### 5.11 `item_icon`

装饰性物品图标，由原生桥绘制。

```yaml
- type: item_icon
  id: currency
  width: 16
  height: 16
  item:
    id: "minecraft:gold_ingot"
    count: 1
    components: {}
  scale: 1.0
  show_count: false
  show_durability: false
```

- `item` 只支持 id、count 和基础数据组件；复杂组件在实现时按 1.21.11 组件 API 校验，失败绘制占位。
- `show_count` / `show_durability` 控制原版装饰；附魔光效和冷却仍由原版处理。
- 这只是展示，不是真实槽位，不能被取出。

## 6. 样式、主题与字体

### 6.1 样式

```yaml
style:
  background: "tx:panel"
  nine_slice: [8, 8, 8, 8]
  border: { color: "#40FFFFFF", width: 1 }
  shadow: { color: "#80000000", offset: [0, 2], blur: 4 }
  padding: [8, 8, 8, 8]
  text: { color: "#FFFFFF", font: "tx:font.body", shadow: true }
  opacity: 1.0
  states:
    hover: { background: "tx:panel_hover" }
    pressed: { opacity: 0.85 }
    disabled: { opacity: 0.4 }
```

`shadow.blur` 在 NanoVG 下用多层偏移或预烘焙贴图实现，不是实时高斯模糊。

`tx:<name>` 是主题令牌；服务端编译时校验令牌存在，客户端按 `THEME_SET` 下发的内容解析。
下发文档保留令牌引用，因此换主题不需要重发整份文档。

### 6.2 主题

```yaml
schema: 1
id: dark
tokens:
  color.primary: "#FF4FA3E8"
  tex.panel: "local:公共/贴图/panel_dark.png"
  font.body: "body"
  font.title: "title"
  space.md: 8
```

字体令牌的值指向 `fonts:` 中的键；元素也可以直接写字体 id。

### 6.3 字体

字体在页面或主题的 `fonts:` 中声明。默认由 NanoVG 加载 TTF/OTF。

```yaml
fonts:
  body:
    provider: nanovg_ttf      # nanovg_ttf | nanovg_bitmap | native
    source: local             # local | mod | resource
    id: "公共/字体/body.ttf"   # local: 相对 CloudCore/
    fallback: "公共/字体/fallback.ttf"
    line_gap: 1
  title:
    provider: nanovg_ttf
    source: resource
    id: "mypack:ui/title.ttf"
    fallback: "cloudcore:ui/body.ttf"
```

规则：

- `source: local`：从 `.minecraft/resourcepacks/CloudCore/` 读取；服务端只发相对路径。
- `source: mod`：模组 jar 内置，只用于最小回退；正式素材放本地 CloudCore 目录。
- `source: resource`：标准资源包。
- `nanovg_ttf`：读取 TTF/OTF，用 `nvgCreateFontMem` 创建字体。
- `nanovg_bitmap`：v2；把位图字体页转成 NanoVG 图像字体或字形 quad。
- `native`：使用原版 `Font`，只用于必须原版绘制的文本（tooltip 内容、EditBox、部分 Boss 标题）。
- 字体令牌可被元素 `font:` 直接引用（`"tx:font.title"` 或字面 id）。
- NanoVG 文本的测量和绘制都走 NanoVG，使用同一个字体句柄。
- 缺失字体按 `fallback` 回退，再回退内置字体，再回退 `native`，并记录一次日志。
- 字体字节可以在工作线程读取，`nvgCreateFontMem` 必须在渲染线程调用。
- `F9` 重载本地资源时重新加载字体；服务端不能直接推送字体文件。
- 输入框编辑文本、tooltip 内容、物品数量文字使用原版 `Font`，不会自动匹配 NanoVG 字体；
  需要视觉一致时，在本地 CloudCore 目录或资源包里放一份对应的 MC 字体。

`label`、`button`、`title` 等静态文本由 NanoVG 绘制；`text_field` 的编辑文本和 Tooltip 内容由原版绘制。

## 7. 绑定与状态

状态保存在服务端，模板在服务端求值：

```yaml
state:
  selected: -1
  balance: 0
  items: []

elements:
  - type: label
    id: price_label
    text: "价格: ${state.balance}"
  - type: panel
    id: selected_highlight
    visible: "${state.selected >= 0}"
```

模板语法：

| 形式 | 含义 |
| --- | --- |
| `${state.key}` | 会话状态值 |
| `${player.name}` | 玩家属性 |
| `${placeholder:server_tps}` | PlaceholderAPI 展开 |
| `${item.field}` | 遍历项字段（仅在 `repeat` 内） |
| `${i}` | 遍历下标（仅在 `repeat` 内） |
| `${expr}` | 算术/比较子集：`+ - * / % == != < <= > >= && \|\| !`、括号、字符串字面量 |

求值器是封闭文法的小型递归下降解析器：没有函数调用、没有白名单之外的属性访问、没有循环。
解析失败即加载失败。

属性变化以 `ELEMENT_UPDATE` 下发；遍历实例数量变化时提升修订号并下发完整文档。

`state` 只存在于服务端；下发文档里没有 `state`，只有求值后的文本、布尔值和属性。

### 7.1 占位符刷新

`%foo%` 与 `${placeholder:foo}` 都由服务端解析，客户端不解析任何占位符。

页面级默认：

```yaml
placeholders:
  mode: interval          # static | interval | tick | manual | event
  interval_ticks: 20
  async: false
```

元素级覆盖：

```yaml
- type: label
  id: balance
  text: "余额: %player_balance%"
  refresh: { mode: interval, ticks: 10 }
```

| 模式 | 行为 | 成本 |
| --- | --- | --- |
| `static` | 打开/重载时解析一次 | 最低 |
| `interval` | 每 N tick 解析一次 | 低 |
| `tick` | 每 tick 解析 | 高，仅用于少量元素 |
| `event` | 元素事件后解析 | 低 |
| `manual` | 仅 `refresh` 动作或 API | 最低 |

同步与异步：

- 默认**同步**：主线程批量解析，每玩家 2 ms/tick、全局 10 ms/tick 预算；超出的元素推迟到下一 tick。
- 同一 tick 内相同原始字符串只解析一次，按玩家缓存。
- 只把变化的值作为 `ELEMENT_UPDATE` 下发，不重发整份文档。
- `async: true` 是高级选项：解析放到工作线程，结果在下一 tick 应用到主线程。PlaceholderAPI 的扩展
  不保证线程安全，因此只有服务端 `placeholders.async-namespaces` 中显式列出的命名空间才允许异步；
  未声明的命名空间仍走同步。
- 客户端永远不同步/异步解析占位符，它只收到解析后的文本。

## 8. 事件与动作

### 8.1 事件

| 事件 | 元素 | 载荷键 |
| --- | --- | --- |
| `click` | button、icon_button、遍历项 | `button`、`shift` |
| `right_click` | button、icon_button | `shift` |
| `hover_enter` / `hover_leave` | 任意可交互元素 | — |
| `change` | text_field、slider、checkbox | `value`、`checked`、`amount` |
| `submit` | text_field | `value` |
| `select` | 列表、tabs | `index` |
| `scroll` | scroll | `scroll` |
| `drag_end` | 可拖拽元素 | `dx`、`dy` |
| `tooltip_scroll` | Tooltip 面板 | `scroll` |

`hover_enter`/`hover_leave` 只在元素声明了对应事件时上报（页面作者显式选择，没有声明就没有流量），
每次跨过元素边界各一次，受服务端事件限流约束。`scroll` 的载荷是滚动后的**绝对**偏移
（文档像素）；服务端通常 `set_state` 保存它，文档再用 `scroll_y: "${state.offset}"` 渲染回去。
`tooltip_scroll` 默认不上报，Tooltip 的滚轮只改本地偏移。

### 8.2 动作

```yaml
on:
  click:
    - action: player_command
      value: "shop buy ${state.selected}"
    - action: set_state
      key: balance
      value: "${state.balance - 100}"
    - action: message
      value: "&a购买成功"
```

每条动作支持 `permission`、`condition`、`delay`（tick，0..200）。

| 类型 | 字段 | 效果 |
| --- | --- | --- |
| `player_command` | `value` | 以玩家身份执行 |
| `console_command` | `value` | 以控制台身份执行 |
| `message` | `value` | 聊天消息 |
| `actionbar` | `value` | 动作栏消息 |
| `title` | `title`、`subtitle`、`fade_in`、`stay`、`fade_out` | 标题 |
| `sound` | `id`、`volume`、`pitch` | 声音 |
| `open_ui` | `id`、`surface`? | 打开另一个页面/面板 |
| `close_ui` | `reason`? | 关闭当前会话 |
| `set_state` | `key`、`value` | 修改会话状态 |
| `set_element` | `id`、`property`、`value` | 直接覆盖属性 |
| `refresh` | — | 重新求值并推送差异 |
| `run_action` | `handler`、`args` | 调用注册的 `ActionHandler` |
| `cooldown` | `key`、`ticks` | 命名冷却 |

动作列表只在服务端保存；下发文档只包含每个元素的 `events: {event: actionId}` 映射，不包含命令、
动作体或权限。客户端点击后只发送 `(elementId, actionId)`。

简单动作列表是 v1 的模型。v2 动作引擎会把它编译为 `sequence` 节点，新增分支、循环、等待、
并行等节点，见 `13-action-engine.md`。

## 9. 校验规则

硬失败（拒绝页面）：

1. 未知 `type`、未知属性、类型错误。
2. 元素 id 重复；id 不符合正则。
3. `surface` 与 `container` 不匹配（例如 `hud` 声明了 `container`）。
4. `container` 页面没有容器段；非 `container` 页面声明了 `inventory_grid`（玩家背包除外）。
5. `inventory_grid` 范围越界、重叠，或槽位映射不完整（`managed`）。
6. `repeat` 的 `source` 不是 `${state.xxx}`，`max` 越界，嵌套超过 2 层。
7. 未知主题、令牌或字体 provider。
8. 动作类型未知或缺少必填字段。
9. 模板解析失败、未知根、循环引用 `tooltip_ref`。
10. 深度 > 32；元素 > 512；JSON > 1 MiB；片段数 > 64；include 深度 > 8。
11. `scroll` 同轴嵌套。
12. `modal: true` 用在非 `overlay` 元素上。
13. Tooltip / HUD / Boss 文档包含槽位或容器段。
14. `url:` 贴图不是 https、主机不在允许列表、或超出大小/尺寸/超时限制。
15. GIF 帧数、单帧尺寸、解码后总大小或帧延迟超限。
16. `refresh.mode` 未知，或 `interval_ticks` 不在 1..1200。
17. Tooltip 滚动条字段非法（`width <= 0`、`min_thumb` 大于可见高度）。

警告：

- 元素完全在父边界之外。
- 按钮没有 `on.click`。
- `text_field` 的 `max_length > 256`。
- 编译期无法解析的 `inline:` 贴图键。
- `repeat` 的 `max > 64`（性能提醒）。
- `refresh.mode: tick` 用于超过 16 个元素（性能提醒）。
- 页面引用 `url:` 贴图但没有配置允许列表（将在加载期失败，提前提示）。

## 10. 完整示例：商店（容器面板）

```yaml
schema: 1
id: shop_main
surface: container
title: "&8商店"
theme: dark
size: [176, 166]
container:
  rows: 6
  interaction: managed
fonts:
  body:
    provider: nanovg_ttf
    source: local
    id: "公共/字体/body.ttf"
    fallback: "公共/字体/fallback.ttf"
state:
  selected: -1
  balance: 0
elements:
  - type: panel
    id: root
    x: 0
    y: 0
    width: 176
    height: 166
    style:
      background: "tx:panel"
      nine_slice: [8, 8, 8, 8]
      padding: [8, 8, 8, 8]
    children:
      - type: label
        id: title
        x: 8
        y: 6
        text: "&e%player_name% &7的商店"
        font: "tx:font.title"
      - type: inventory_grid
        id: grid
        slots: [0, 44]
        origin: [8, 24]
        cell: [18, 18]
        columns: 9
      - type: label
        id: balance
        x: 8
        y: 140
        text: "余额: &6${state.balance}"
      - type: button
        id: buy
        x: 88
        y: 138
        width: 80
        height: 20
        text: "购买"
        enabled: "${state.selected >= 0}"
        on:
          click:
            - action: player_command
              value: "shop buy ${state.selected}"
            - action: message
              value: "&a已购买"
```

## 11. 完整示例：Tooltip 皮肤

```yaml
schema: 1
id: tooltip_theme
surface: tooltip
theme: dark
size: [0, 0]
surface_options:
  tooltip:
    max_height: 160
    scroll_step: 12
    smooth_scroll: true
    line_gap: 2
    max_lines: 256
    scrollbar:
      mode: auto
      width: 6
      gap: 2
      track: "tx:scroll_track"
      thumb: "tx:scroll_thumb"
      thumb_hover: "tx:scroll_thumb_hover"
      min_thumb: 12
      nine_slice: [2, 2, 2, 2]
      draggable: true
elements:
  - type: panel
    id: frame
    width: "100%"
    height: "100%"
    style:
      background: "tx:tooltip_bg"
      nine_slice: [4, 4, 4, 4]   # 源贴图像素；已实现（NineSlice.split 生成 9 块）
      padding: [4, 4, 4, 4]
```

Tooltip 的文本行由客户端从物品生成，文档只负责外框、字体、颜色、最大高度和滚动行为。

## 12. 完整示例：Boss 血条

```yaml
schema: 1
id: boss_theme
surface: boss_bar
theme: dark
size: [182, 12]
surface_options:
  boss_bar:
    position: top_center
    spacing: 19
    max_bars: 3
    title_renderer: native      # native | nanovg
    bar:
      width: 182
      height: 12
      background: "tx:boss_bg"
      fill: "tx:boss_fill"
      overlay: "tx:boss_overlay"
      nine_slice: [2, 2, 2, 2]
      tint_from_event: true
      direction: left_to_right
      show_name: true
      text:
        font: "tx:font.body"
        color: "#FFFFFFFF"
        shadow: true
        align: center
elements: []
```

`bar` 存在时，客户端绘制内置血条：填充按 `BossEvent#getProgress()` 裁剪，文字显示事件标题，
颜色与样式来自事件。`elements` 只用于额外装饰；不要再用 `progress` 画第二根填充条。

## 13. YAML 兼容别名

| 别名 | 规范名 |
| --- | --- |
| `类型` | `type` |
| `宽` / `高` | `width` / `height` |
| `文本` / `文字` | `text` |
| `贴图` / `材质` | `texture` |
| `可见` | `visible` |
| `子元素` | `children` |
| `事件` | `on` |
| `动作` | `action` |
| `左键` / `右键` | `click` / `right_click` |
| `遍历` | `repeat` |
| `片段` | `includes` |

别名只做键名归一化，不承诺兼容 DragonCore 的脚本函数。
