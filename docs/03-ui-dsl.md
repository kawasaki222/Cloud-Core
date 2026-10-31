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
  on_join: true              # false = 进服时不下发这一页（只对常驻类表面有效）
  hud:
    layer: foreground        # background | foreground | overlay
    anchor: bottom_center    # 默认 top_left
    offset_x: 0
    offset_y: 0
    priority: 0              # 同一层多份文档时的绘制优先级
    replace: [health, food, xp, hotbar]   # 取消对应的原版 HUD 绘制
  player_inventory:
    recipe_book: hide        # hide | vanilla | skinned | custom（skinned/custom 暂回退 vanilla）
    show_effects: true       # 复用原版效果图标
    show_player_model: true  # 实时玩家预览；可配 player_model_x/y/scale
  tooltip:
    max_height: 300           # auto/always 的裁剪高度；never 只受屏幕高度限制
    padding: 14               # 文本与外框的距离
    scroll: never             # never | auto | always（也可用 scrollable: true/false）
    name:                     # 物品名相对内容原点的位置与字体
      x: 36
      y: 10
      font: "cloudcore:tooltip_body"
    lore:                     # 其余行；y 缺省时紧接物品名
      x: 14
      y: 30
      line_gap: 0
      font: "cloudcore:tooltip_body"
    icon:                     # 物品图标，可省略或 icon: false
      show: true
      x: 10
      y: 8
      size: 20
    attachments: []           # 附属栏，见 11.1
    scrollbar:
      mode: auto              # auto | always | never，默认 never
      width: 4
      track_color: "#70101828"
      thumb_color: "#FFFFD479"
  boss_bar:
    position: top_center      # 目前只按屏幕宽度居中，该字段尚未接线
    spacing: 19               # 相邻哨兵血条垂直间距；0 = 沿用原版
    max_bars: 3               # 最多绘制几条哨兵血条；0 = 不限制
    title_renderer: native    # native | nanovg（nanovg 尚未接线）
    bar:
      width: 182
      height: 12
      background: "tx:boss_bg"
      fill: "tx:boss_fill"
      overlay: "tx:boss_overlay"
      nine_slice: [2, 2, 2, 2]
      tint_from_event: true
      direction: left_to_right   # 目前固定从左到右，该字段尚未接线
      show_name: true
      text:
        font: "tx:font.body"
        color: "#FFFFFFFF"
        shadow: true
        align: center
```

`container` 段还接受两个容器专用开关（`docs/16` §10）：

```yaml
container:
  rows: 6
  interaction: managed          # managed | readonly | passthrough
  hide_player_inventory: false  # true = 不绘制下方玩家背包槽位
  allow_player_inventory: false # readonly 时允许操作玩家自己的槽位
```

`hotbar` 元素的实时数值由客户端按 `value_from`/`text_from` 读取，不需要服务端每帧下发：

```yaml
- type: progress
  id: hp_bar
  value_from: health        # health | food | xp | air | armor | level | health_full | food_full
- type: label
  id: hp_text
  text_from: health         # 同上，另加 name
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
子元素的 `x` 是相对“布局位置”的**额外偏移**：`row` 里通常写 `x: 0`，横向间距由前一个子元素的
`width` + `gap` 决定（货架/商店这类一行多列用宽度控制列位，不要手写绝对 x）。

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

**当前实现**：`overlay` 作为普通容器递归绘制子元素，`modal`/`dim` 未实现（没有遮罩、没有输入
捕获）；子元素的坐标与父容器一致。

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
- `scroll` 内放 `repeat` 是推荐用法；嵌套 `repeat` 当前没有深度上限（目标规则是 2 层）。
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

- `wrap: true`：按文本宽度折行。拉丁文在空格处断开，中文/日文/韩文按字符断开；单个超长单词
  才按字符切。`\n` 始终另起一行。宽度取元素声明的 `width`（未声明时不折行）。
- `max_lines: N`（1..64）：最多 N 行，内容按元素框裁剪，不会压到后续元素。
- 折行用当前后端绘制文本时的同一套度量（原生用原版 `Font`，NanoVG 用其字体句柄），
  所以换行位置与实际绘制一致。
- 富文本 `runs` 逐段折行：段与段之间在行尾处换行，颜色跟随各自的文本。
- 富文本的 `bold`/`italic` 尚未映射。

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

`fit` 取 `stretch`（默认，拉伸到 `width`/`height`，缺省 64×64）、`contain`（保持比例放进框内，
居中留边）或 `cover`（保持比例填满框，超出部分按元素框裁剪）。声明了 `nine_slice` 的贴图始终拉伸，
比例由九宫格本身保证。`tint`、`nine_slice`、`animated`/`loop`/`speed` 均可用。`fit` 只作用于基准
贴图，`layers` 仍按各自几何拉伸。

#### 图层叠加

`image` 可以只给一个基准贴图，再用 `layers` 从后往前叠加任意数量的透明层。每层有自己的
`texture`、`tint`、偏移和动画参数；没有写 `x/y/width/height` 时继承基准层的几何。

```yaml
- type: image
  id: slot_socket
  x: 20
  y: 40
  width: 18
  height: 18
  texture: "builtin:slot"
  layers:
    - texture: "builtin:slot_glow"
      tint: "#40FFD060"       # AARRGGBB；白色不改变原图，透明色负责淡出
    - texture: "local:公共/图标/锁定.png"
      x: 4                   # 相对基准层左上角
      y: 4
      width: 10
      height: 10
      tint: "#FF808080"
```

- `tint` 是乘法着色：`#FFFFFFFF` 原样绘制，`#80FFFFFF` 半透明，`#FFFFC040` 偏暖。
- 渲染顺序是基准层、`layers[0]`、`layers[1]`……，后面的层覆盖前面的层。
- 每层单独支持 `nine_slice`、`animated`、`loop`、`speed`；GIF 层可以独立播放。
- 基准层也可以带 `tint`，适合给同一套白模贴图做不同品质色。
- 图层总数受页面命令预算限制；超出预算的层会被忽略，不会破坏其余页面。

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
- 只允许 `https://`；客户端拒绝私网、回环、链路本地、保留地址、带用户信息的 URL 和非 443 端口，
  不跟随重定向。
- 大小 ≤ 8 MiB、超时 10 秒（尺寸提示由 `TEXTURE_REF` 携带，解码失败即丢弃）。
- PNG 直接使用（`NativeImage`）；GIF 在客户端本地解码（直连）或服务端解码为精灵表（代理）。
  当前不支持 JPEG。
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
`delay`（`ticks`+`then`）、`open_page`（`page`，可带 `${...}`）、`sound`（`sound`/`volume`/`pitch`）；
`${...}` 支持表达式（算术/比较/`&&`/`||`/`!`/字符串拼接）。
每条动作可带 `permission:`：缺权限时整次点击拒绝（`REJECTED`），后续动作不再执行。

`checkbox` 已实现：`checked` 属性（"true"/布尔）决定绘制，点击发 `change` 事件载荷 `{checked}`，
常用写法 `set_state key: notify value: "${checked}"`。

状态：`normal`、`hover`、`pressed`、`disabled`。已实现 `hover`：元素声明
`style.hover: { background: ... }` 时，客户端命中该元素就用悬停样式重绘（每帧只为变化的悬停目标重建一次列表）。
已实现 `pressed`：左键按下且光标仍在元素上时用 `style.pressed` 重绘（背景、`nine_slice`、文字随之下移 1px，
做出「按进去」的手感），松开或把光标拖离即恢复。每个键按 `pressed` → `hover` → 基础样式 的顺序回退，
所以只写想改的那个键即可：

```yaml
style:
  background: "#FF1E2430"
  hover:   { background: "#FF2A3342" }
  pressed: { background: "#FF151A22" }   # 只覆盖背景，其余沿用 hover/基础样式
```

已实现 `disabled`：元素写 `enabled: false` 时整体降低一半不透明度、不再使用悬停样式、
点击不触发事件（服务端收不到 `EVENT`），键盘焦点也会跳过；输入框同时变为不可编辑。

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
已实现：背景（颜色/贴图/九宫格）、`style.hover`、`style.pressed`、图标与事件。

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
    cursor_color: "#FFFFFFFF"
    selection_color: "#804FA3E8"
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

- `style.padding`：`[左, 上, 右, 下]`（与 `nine_slice`、`fill_inset` 同一顺序），
  或写单个数字表示四边相同。客户端把输入框控件按内边距内缩，文字因此落在作者期望的位置；
  控件与元素框不再重合，焦点框仍对齐元素外框。
- `style.placeholder_color` / `style.cursor_color` / `style.selection_color`：占位文字、光标与
  选区颜色，`#AARRGGBB` / `#RRGGBB`。
  客户端用 `CloudEditBox extends EditBox` 重写 `renderWidget`，不新增混入；输入法、选区、
  剪贴板、键盘重复仍由原版处理。未设置时分别沿用文字颜色和原版选区高亮；主题令牌（`tx:`）
  不会被客户端解析，写了会回退原版并在诊断里可查。
- `style.clear_button`：清除按钮的贴图引用（`local:`/`pack:`/`builtin:`/`inline:`）；
  模组自带 `builtin:clear` 是一个 8×8 的叉。按钮占据元素右侧内边距，输入控件按同一宽度收窄，
  长文本不会压到按钮下面；点击后调用 `EditBox#setValue("")`，照常触发 `change`（`value` 为空串），
  不额外发事件。贴图引用不可用时画一个 `x` 作为可点击占位；元素 `enabled: false` 时按钮不响应。
- `submit` 现在按控件身份匹配，因此带 `padding` 的输入框也能触发 `submit`。

已实现全部外观字段；输入法候选窗位置由 GLFW/原版处理，不能完全自定义。

边界：

- `cursor_color` / `selection_color` 依赖 `EditBox` 的私有状态（`displayPos`、`highlightPos`、
  `textX`/`textY`）。客户端用缓存的 `VarHandle` 读取，不新增混入；Minecraft 升级若重命名字段，
  会在类初始化时直接失败，而不是静默画错。
- `clear_button` 的宽度按字段高度计算（8–14 px），设计稿需要更大按钮时请同时提高输入框高度。
- `padding` 通过缩小控件尺寸补偿 `EditBox` 内建的文本偏移（`EditBox` 无法直接接受内边距参数）；
  单边上限 32px，超过按 32 处理，避免把可输入区域挤没。
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

`value` 是**数值**，取值范围由 `min`/`max` 决定（默认 `0..1`）。客户端按比例画轨道 + 填充 +
滑块钮，并把位置换算回 `0..1` 的 `amount` 上报；服务端动作据此 `set_state`，重编译后的文档
把新 `value` 推回客户端。

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

带区间与步进的写法：

```yaml
- type: slider
  id: dpi_slider
  x: 16
  y: 90
  width: 280
  height: 6
  min: 0
  max: 100
  step: 5
  live: true
  value: "${state.dpi}"
  on:
    change:
      - action: set_state
        key: dpi
        value: "${value}"
```

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `value` | `min` | 文档里的真实数值，落在 `[min, max]` |
| `min` | `0` | 区间下界 |
| `max` | `1` | 区间上界；`max <= min` 时按 `min + 1` 处理 |
| `step` | 无 | 正数时把上报值吸附到 `min + n*step`；缺省或非正数表示连续 |
| `live` | `false` | `true` 时拖动过程中连续上报，否则只在松开鼠标时上报一次 |

`change` 载荷带两个字段：

- `amount`：`0..1` 的位置比例，供只关心比例的旧动作使用；
- `value`：按 `min`/`max`/`step` 换算后的真实数值。

拖动行为：按下即定位到光标处并开始拖动，松开时必定上报一次（`live: false` 时也只有这一次）。
每次上报都受服务端事件限流约束；`live: true` 的滑块在高频拖动下可能被限流丢弃部分中间值，
最终值仍会到达。

- 整串占位符（`"${state.volume}"`）保留被解析值的类型，所以 `value` 以数字而非字符串下发；
  客户端对字符串数字也做解析，兼容旧文档与 YAML 里加了引号的数字。
- 未解析的占位符下发为空串，不把 `${...}` 原文写进文档。

### 5.8 `progress`

已实现：`value`（`0..1`）、`direction`（`left` 默认 / `right` / `up` / `down`）、
`texture_bg` 与 `texture_fill`（贴图引用，填充贴图按 `value` 裁剪而不是压扁）、
`style.background` 与 `style.fill_color`（颜色路径）、`nine_slice`（背景贴图九宫格）、
`radius` 圆角、`segments`/`segment_gap` 分段。

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
- `nine_slice: [l, t, r, b]` 只作用于 `texture_bg`：有边框的轨道贴图边框和端头不拉伸，
  只有中间被拉长。颜色背景走 `radius`。
- `radius` 接受像素数或百分比（按短边算），会被钳到短边的一半，所以小血条不会变成圆形；
  圆角逐行绘制，一条 12px 高的条只多出十几条填充指令。
- `segments: 10` 把条切成 10 格，每格独立填充：`value = 0.35` 是 3 格满 + 第 4 格半格，
  适合「10 颗心」「5 段怒气」这类离散显示。`segment_gap`（默认 1，仅分段时生效）是格间空隙。

```yaml
- type: progress
  id: rage
  width: 180
  height: 14
  value: "${state.rage}"
  segments: 5
  segment_gap: 2
  radius: 3
  style:
    background: "#FF101418"
    fill_color: "#FFE06030"
```

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

**当前实现**：`tx:` 令牌只在颜色解析处识别（`Colors.parse` 对无法解析的令牌回退默认值），
服务端没有令牌表、也没有存在性校验；`THEME_SET` 只有操作码定义，收发两端均未接线。
文档里的 `theme:` 字段会原样下发并参与 diff 比较，但客户端目前不消费它。

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

字体在页面或主题的 `fonts:` 中声明。TTF/OTF 由 NanoVG 加载（`nanovg_ttf`），
只在 NanoVG 后端启用时生效；默认原生后端下未声明字体的文本走原版 `Font`。

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

表达式在两处生效：

- **属性与标签**（`${state.balance / 300}`、`"已点击 ${state.clicks + 1} 次"`）：编译页面时求值，
  `set_state` 刷新会重新编译，所以表达式随状态更新；结果保持类型（数字/布尔/字符串）。
- **动作参数**（`value`、`condition`、`command`…）：编译期只替换 `repeat` 作用域里的变量，
  其余 `${...}` 原样留给运行期，用点击时的状态求值。
- 嵌套模板由内向外解析：`"${state.balance - ${entry.price}}"` 先变成
  `"${state.balance - 120}"`，运行期再算出数字。`repeat` 里给每个商品写同一个按钮模板就靠这个。

属性变化优先以**更高 revision 的 `ELEMENT_UPDATE`** 下发，只携带改动的元素/属性；元素结构、
几何、事件、文档级字段或 `text_field` 变化时回退完整 `UI_DEFINITION`（客户端替换界面）。
客户端只接受连续 revision，跳号会丢弃并等待下一次完整文档。协议细节见 `02-protocol.md`。

`state` 只存在于服务端；下发文档里没有 `state`，只有求值后的文本、布尔值和属性。

### 7.1 占位符刷新

`%foo%` 与 `${placeholder:foo}` 都由服务端解析，客户端不解析任何占位符。

已实现（PlaceholderAPI，软依赖）：

- 安装了 PlaceholderAPI 时，`text` 与 `placeholder` 属性（含富文本 `runs[].text`）和文档标题里的
  `%token%` 在**发送与刷新时**各展开一次；颜色、尺寸、贴图引用与事件不会被改写。
- 配置 `placeholders.enabled: false` 可关闭；没有安装 PlaceholderAPI 时 `%token%` 原样显示，
  启动日志写 `PlaceholderAPI not installed; %placeholders% stay as text`。
- 未知令牌（未安装的扩展）保持原样；扩展抛异常时写一次警告，文本按未展开发送。
- `${placeholder:foo}` 与 `%foo%` 等价，但显式形式不会被页面模板当成状态表达式吃掉；两种写法
  在同一个字符串里可以混用。展开结果不会再次当作占位符解释。
- 展开只作用于 YAML 页面（`/cloudcore page`、`set_state` 刷新与周期刷新）；诊断用的内置 demo
  文档不展开。

周期刷新：页面级默认加元素级覆盖：

```yaml
placeholders:
  mode: interval          # static | interval | tick | manual | event
  interval_ticks: 20      # 1..72000，默认 20
  async: false            # true：编译与展开在工作线程，diff/下发回主线程
elements:
  - type: label
    id: balance
    text: "余额: %player_balance%"
    refresh: { mode: interval, ticks: 10 }   # 覆盖该元素的间隔
```

| 模式 | 行为 | 成本 |
| --- | --- | --- |
| `static` | 打开/重载时解析一次 | 最低 |
| `interval` | 每 N tick 解析一次 | 低 |
| `tick` | 每 tick 解析 | 高，仅用于少量元素 |
| `event` | 该页面有元素事件后解析一次 | 低 |
| `manual` | 仅 `refresh` 动作或 API | 最低 |

- 调度器每 tick 检查一次；任一页面/元素到期就重编译整页、展开占位符，再用
  `ELEMENT_UPDATE` 只发变化属性。当前只对 `custom_screen` 与 `hud` 启用周期刷新，
  其他面板会记录警告并退回“只在打开/`set_state` 时解析”。
- 默认**同步**：主线程执行，软预算 `placeholders.player-budget-ms`（默认 2）与
  `placeholders.global-budget-ms`（默认 10）；超预算的页面顺延，不阻塞服务器 tick。
- `async: true`：页面编译和 PlaceholderAPI 展开在异步线程，结果回到主线程做 diff/下发；
  同一页面同时最多一个在途任务。若期间 `set_state` 改了状态，快照版本不匹配，结果直接丢弃，
  避免旧快照覆盖新状态。异步路径要求用到的 PlaceholderAPI 扩展线程安全；当前没有实现
  `placeholders.async-namespaces` 白名单，页面作者自行保证。
- `event` 模式只标记“该页面需要刷新”，不按元素单独展开；刷新仍以整页为粒度。
- 内置动作 `refresh` 立即重编译并下发当前页，适合 `manual` 页面上的按钮。
- 客户端永远不解析占位符，只收到解析后的文本。

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
`tooltip_scroll` 默认不上报，Tooltip 的滚轮只改本地偏移（该事件尚未接线）；
`select` 与 `drag_end` 已定义但客户端尚未发送（滑块拖动沿用 `change`）。

`right_click` 已实现：右键优先找声明了 `right_click` 的元素，没有就回退到同一个元素的 `click`
（载荷 `button=1`），所以一个按钮可以同时有主/次动作。

### 8.2 动作

```yaml
on:
  click:
    - action: message
      value: "shop buy ${state.selected}"
    - action: set_state
      key: balance
      value: "${state.balance - 100}"
    - action: message
      value: "&a购买成功"
```

每条动作支持 `permission`、`condition`、`delay`（tick，0..200）与 `cooldown_ticks`
（正整数，同一元素上同一类型的动作在这么多 tick 内只执行一次；被挡下的点击按成功返回，不报错）。

**已实现**的动作：`message`、`close_ui`（别名 `close`）、`set_state`、`if`/`then`/`else`、
`delay`、`command`（默认关闭 + allowlist）、`open_page`、`sound`、`refresh`。

| 类型 | 字段 | 效果 | 状态 |
| --- | --- | --- | --- |
| `message` | `value` | 聊天消息 | 已实现 |
| `sound` | `id`/`sound`、`volume`、`pitch` | 声音 | 已实现 |
| `open_page` | `page`、`surface`? | 关闭当前页并打开目标页 | 已实现 |
| `close_ui` | — | 关闭当前会话 | 已实现 |
| `set_state` | `key`、`value` | 修改会话状态 | 已实现 |
| `refresh` | — | 重新求值并推送差异 | 已实现 |
| `if` | `condition`、`then`、`else` | 条件分支 | 已实现 |
| `delay` | `ticks`、`then` | 延迟执行 | 已实现 |
| `command` | `command`、`as` | 以玩家/控制台身份执行，受闸门限制 | 已实现 |
| `player_command` | `value` | 以玩家身份执行 | 计划 |
| `console_command` | `value` | 以控制台身份执行 | 计划 |
| `actionbar` | `value` | 动作栏消息 | 计划 |
| `title` | `title`、`subtitle`、`fade_in`、`stay`、`fade_out` | 标题 | 计划 |
| `open_ui` | `id`、`surface`? | 打开另一个页面/面板 | 计划（`open_page` 已覆盖该用途） |
| `set_element` | `id`、`property`、`value` | 直接覆盖属性 | 计划 |
| `run_action` | `handler`、`args` | 调用注册的 `ActionHandler` | 计划（实际写法是直接 `action: <注册名>`） |
| `cooldown` | `key`、`ticks` | 命名冷却；冷却中整次点击 `REJECTED` | 已实现 |

动作级 `permission:`、`condition:`、`delay`、`cooldown_ticks` 与 `cooldown` 动作均已实现；
冷却按玩家、页面与键隔离，玩家退出时清空。配置里的 `per-element-cooldown-ms` 键不存在，
冷却只由页面声明。

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
6. `repeat` 的 `source` 不是 `${state.xxx}`，`max` 越界，嵌套超过 2 层（嵌套限制未实现）。
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

**当前实现的实际校验**：`PageLoader` 强制元素 ≤ 512、片段 ≤ 64、include 深度 ≤ 8、
`repeat` 实例 ≤ 256、文件 ≤ 256 KiB；`UiDocumentValidator`（客户端侧）用更宽的 10,000 元素 /
64 层作为防御上限。第 7、11、12、14、15、17 条（主题令牌、`scroll` 同轴嵌套、`modal`、
`url:`/GIF 限额、滚动条字段）中，只有 `CC-DOC-022..025` 的属性警告、`refresh.mode` 解析和
`repeat`/`include` 限额真正生效；其余是目标规则。

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
            - action: set_state
              key: balance
              value: "${state.balance - 100}"
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
    max_height: 300
    padding: 14
    name:
      x: 36
      y: 10
      font: "cloudcore:tooltip_body"
    lore:
      x: 14
      y: 30
      line_gap: 0
      font: "cloudcore:tooltip_body"
    icon:
      show: true
      x: 10
      y: 8
      size: 20
    scrollbar:
      mode: auto
      width: 4
      track_color: "#70101828"
      thumb_color: "#FFFFD479"
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

Tooltip 的文本行由客户端从物品生成，文档负责外框、最大高度、滚动以及物品名/lore 的位置与字体。`name`/`lore` 的 `x`/`y` 相对内边距后的内容原点，`lore.y` 缺省时紧接物品名；`font` 必须是 Minecraft 字体 id（如 `cloudcore:tooltip_body`），或在 `fonts` 中声明 `source: native` 的条目。NanoVG TTF 字体不能用于原生 tooltip 文本。`icon` 可写布尔值或对象：`show: false` 隐藏；`x`/`y` 为图标相对内容原点的位置，`size` 缺省 16；图标取自当前悬停的容器槽位，非容器界面没有槽位时不显示。`scrollbar.mode: never` 时不裁剪短内容，内容高于屏幕时仍会强制滚动；`auto` 只在超过 `max_height` 时出现滚动条；`always` 始终显示。

### 11.1 Tooltip 附属栏（attachments）

`surface_options.tooltip.attachments` 是围绕 tooltip 的附加面板列表，每个面板可独立设置位置、尺寸、背景、边框和文字。面板跟随实际 tooltip 位置移动，并被限制在屏幕内；主 tooltip 最后绘制，重叠时盖住附属栏。

```yaml
surface_options:
  tooltip:
    attachments:
      - side: left                 # left | right | top | bottom
        match: "霜之哀伤·战甲"     # 物品显示名包含该文本时才显示；省略则所有 tooltip 都显示
        gap: 6                     # 与 tooltip 边缘的距离
        offset_x: 0                # 在 side/gap 基础上的额外偏移
        offset_y: 0
        align: center              # 沿 tooltip 边缘对齐：start | center | end
        width: 170                 # 省略则按文字自动计算
        height: 0                  # 0/省略则按标题和行数自动计算
        padding: 14
        background: "local:textures/rpg_tooltip_bg.png"  # 也可以是 #AARRGGBB 颜色
        nine_slice: [18, 18, 18, 18]
        border_color: "#80FFD479"  # 同时用作标题下分隔线颜色
        border_width: 0            # 0 表示不画边框
        title: "套装 · 霜之哀伤"
        title_color: "#FFFFD479"
        color: "#FFCCCCCC"
        line_gap: 1
        font: "cloudcore:tooltip_body"
        lines:
          - "1/5  霜之哀伤"
          - "2 件：攻击力 +20"
          - "4 件：暴击率 +8%"
          - "5 件：冰霜伤害 +30"
          - "霜之哀伤，饥渴难耐。"
```

字段说明：

| 字段 | 默认 | 说明 |
|---|---|---|
| `side` | `right` | `left` / `right` / `top` / `bottom` |
| `match` | 空 | 当前悬停物品显示名包含该文本时才显示；空表示始终显示 |
| `gap` | `4` | 与主 tooltip 边缘的间距 |
| `offset_x` / `offset_y` | `0` | 额外偏移，可用于微调或让多个附属栏错开 |
| `align` | `center` | 沿主 tooltip 边缘的 `start` / `center` / `end` |
| `width` / `height` | 自动 | 显式像素值；`height: 0` 或省略时按内容自动计算 |
| `padding` | `8` | 背景内边距 |
| `background` | 深色底 | `#AARRGGBB` 颜色，或 `local:` / `pack:` / `tx:` 贴图引用 |
| `nine_slice` | 无 | 贴图九宫格边距 `[左, 上, 右, 下]` |
| `border_color` / `border_width` | `0` / `1` | 边框颜色与线宽；`border_color` 也用作标题下分隔线 |
| `separator` | 有标题时 `true` | 是否在标题下画分隔线 |
| `title` / `title_color` | 空 / 行颜色 | 标题文本与颜色 |
| `color` | `#FFFFFFFF` | 所有 `lines` 的默认颜色 |
| `lines` | 空 | 字符串，或 `{ text: "...", color: "#..." }` 单独指定颜色 |
| `line_gap` | `0` | 行间距 |
| `font` | tooltip 默认字体 | 原生字体 id，或 `fonts` 中 `source: native` 的字体 |

- 可以配置多个条目，例如左侧放套装件数、右侧放套装效果；不同 `match` 可针对不同套装。
- `width`/`height` 写死且小于文字时，文字会被裁剪；不确定时省略让面板自动撑开。
- 背景贴图失败时退回默认深色底，不会阻止 tooltip 显示。


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
      fill_inset: [2, 2, 2, 2]
      tint_from_event: true
      tint_alpha: 0.62
      direction: left_to_right
      show_name: true
      text:
        font: "tx:font.body"
        color: "#FFFFFFFF"
        shadow: true
        align: center
elements: []
```

`bar` 存在时，客户端绘制内置血条：填充按 `BossEvent#getProgress()` 裁剪，事件标题画在血条内部
（`show_name` / `text.color` / `text.shadow` / `text.align` / `text.show_layer_count` /
`text.counter_color`，原版字体），血条按屏幕宽度自动居中，自定义宽度不会偏向右侧。
`text.font`、`position`、`direction` 与 `title_renderer` 尚未接线（固定原版字体、屏幕居中、从左到右）。

- `fill_inset: [l, t, r, b]`：填充缩进到边框以内，避免盖住 `background` 的边框；纹理背景通常
  取与 `nine_slice` 相同的值。
- `fill` 为贴图且 `tint_from_event: true` 时，先画中性贴图，再叠一层 `tint_alpha`（默认 0.62）
  的事件颜色：一张银灰填充图即可对应红/黄/蓝多条血条。
- `overlay` 最后整条绘制（不裁剪），用于高光或透明中心的框；`nine_slice` 同时作用于背景与 overlay。
- **多层血条 = 同一根条逐层刷新**，不是多根条并排：哨兵带每层剩余强度
  `\u0000CCB:<token>:<p1>,<p2>,...\u0000<名称>`（千分比 0..1000），客户端拿这一条 `BossBar`
  由内向外逐层绘制，外层打空后露出内层；层数 >1 时血条内可显示 `2/3` 计数。
  每层外观写 `bar.layers`，只写与 `bar` 不同的键（`tint` / `fill` / `name` / `text`），
  `tint` 优先于 `BossEvent` 颜色；文字块与 `bar.text` 合并而不是整体覆盖。
  想要屏幕上并排多根条，那是服务端建多个 `BossBar` 的另一种做法，与本机制无关。

`demo_layers` 只用于预览：服务端创建一条带分层哨兵的 `BossBar`，在 `demo_duration` 内把各层
依次打空后移除；`demo_name` 指定 Boss 名称。生产页面应删掉这一段，由 Boss 插件在自己切阶段时
改哨兵里的数值。完整可运行的例子见
`cloudcore-bukkit/src/main/resources/pages/boss_dnf.yml`。

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
