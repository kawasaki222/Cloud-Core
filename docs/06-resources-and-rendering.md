# 06 — 资源与渲染

## 0. 渲染栈

默认使用 **NanoVG 离屏渲染 + 原生合成**：

```
文档 / 布局 / 输入
        │
        ▼
   RenderList
        │
        ├─ NanoVgBackend（默认）
        │    ├─ NVGContext + FontRegistry
        │    ├─ background FBO ──► FboCompositor
        │    └─ foreground FBO ──► FboCompositor
        │
        └─ NativeBackend（回退）
             └─ GuiGraphics
```

- NanoVG 负责面板、静态文本、图片、九宫格、圆角、渐变、阴影、裁剪和特效。
- `FboCompositor` 把 FBO 通过原版 `GuiGraphics` 贴回屏幕。
- 原版桥负责物品、槽位、数量/冷却/附魔光效、Tooltip 内容、输入法文本和玩家模型。
- NanoVG 初始化失败或 FBO 不可用时，整条链路回退 `NativeBackend`。

### 0.1 为什么用 NanoVG

优点：

- 真正的 2D 矢量绘制：路径、描边、圆角、渐变、阴影、抗锯齿、裁剪、变换。
- 文本由 fontstash 管理，支持 TTF/OTF、字体图集、字号缩放。
- 一套 API 覆盖面板、按钮、进度条、Tooltip 外框、Boss 血条，不用为每种形状写贴图。
- 离屏 FBO 把 GL 状态隔离在一个 pass 里，合成仍走原版 GUI 管线。
- 资源包和服务器可以只提供贴图和字体，形状由代码生成。

缺点：

- 需要 `lwjgl-nanovg` 和三平台原生库，模组包体增加。
- FBO 每帧填充/采样有 GPU 成本，必须按逻辑分辨率渲染并做脏矩形/静态层缓存；不做 CPU 上传。
- 字体与输入法分裂：NanoVG 文本用 NanoVG 字体，EditBox/IME 用原版 `Font`，视觉一致性要额外处理。
- 资源包里的原版 GUI 贴图/字体不能直接被 NanoVG 使用，需要单独加载。
- 光影包/Iris、FBO alpha、1.21.6 提取/绘制两阶段都需要实测。
- NanoVG 不是复杂文本整形引擎；阿拉伯文、印度语系等需要额外处理。
- NanoVG 没有内置高斯模糊/毛玻璃；模糊阴影和背景模糊要用多层偏移、预烘焙贴图或额外 pass。

### 0.2 原生桥负责什么

- 物品模型、方块模型、资源包模型、3D 变换、GUI 光照。
- 数量文字、耐久条、冷却遮罩、附魔光效、容器预览、盔甲纹饰。
- 模组自定义 item renderer 和 tooltip component。
- 属性、附魔和模组追加的 Tooltip 行。
- `EditBox` 的文本、光标、选区和输入法组合。

这些不是“画一个图标”那么简单，重写等于复制 Minecraft 的物品与文本体系。NanoVG 只画外框、
背景和装饰，原版桥画内容和输入。

## 1. 坐标模型

| 空间 | 单位 | 所在位置 |
| --- | --- | --- |
| **文档空间** | 逻辑 GUI 像素，原点在 UI 画布左上角 | YAML、编译后的文档 |
| **FBO 空间** | 逻辑像素 × 整数倍（1x/2x） | NanoVG FBO |
| **屏幕空间** | GUI 缩放后的像素，原点在窗口左上角 | 合成位置、原版槽位坐标 |
| **帧缓冲空间** | 物理像素 | Minecraft 自己的 GUI 变换 |

映射：

```
fboX = documentX * fboScale
screenX = leftPos + documentX
```

- 容器/背包文档 `scale` 固定 `1.0`：槽位按屏幕空间定位，原版命中测试不应用姿态变换。
- 独立页面允许 `0.5..3.0`；推荐整数倍。
- HUD、Tooltip、Boss 血条使用屏幕空间加边缘锚点。
- FBO 尺寸 = 画布逻辑尺寸 × `fboScale`，窗口/GUI 缩放变化时重建。

## 2. 资源来源

| 前缀 | 加载方式 | 缓存 | 提供者 |
| --- | --- | --- | --- |
| `local:` | `.minecraft/resourcepacks/CloudCore/` 读取字节 → `nvgCreateImageMem` | NanoVG image handle | 客户端本地目录 |
| `inline:` | `TEXTURE_REF` 直连下载（或 `TEXTURE_PUT`）→ `DynamicTexture`/`nvgCreateImageMem` | 贴图缓存 | 客户端（直连）/服务端（代理） |
| `pack:` | 资源管理器读取 PNG 字节 → `nvgCreateImageMem` | NanoVG image handle | 资源包/其他模组 |
| `builtin:` | 模组 jar 读取 PNG 字节 → `nvgCreateImageMem` | NanoVG image handle | 模组（仅最小回退） |
| `url:` | 客户端直连（默认）或服务端代理 → 字节 → NanoVG image | 内容哈希缓存 | 远程 HTTPS |

解析顺序：`local` → `inline` → `pack` → `builtin`。

- 本地资源根目录固定，服务端只发相对路径；`F9` 重载并释放旧 image handle。
- NanoVG 使用自己的 image handle，不走 `TextureManager` 的 `Identifier`；`FboCompositor` 是唯一
  把 FBO 包装回原版纹理的地方。
- `tx:<name>` 主题令牌由客户端按 `THEME_SET` 解析；服务端编译时校验令牌存在。
- `url:` 默认客户端直连；服务端代理是可选模式。详见 `07-security.md`。
- 加载中显示白色占位；URL 失败显示红色“网链请求失败”；本地/资源包缺失显示红色“资源缺失”。
  每种失败每个键只完整记录一次，并写入 `logs/cloudcore-client.log`。
- 资源目录或资源包重载时释放旧 image handle 并重新加载。

## 3. 九宫格

NanoVG 用 `nvgImagePattern` + 子矩形绘制九宫格：

- 四角 1:1。
- 边和中心拉伸（默认）或平铺（`NVG_IMAGE_REPEATX/Y`）。
- `w < l + r` 时夹取，跳过中心，禁止负宽度。
- 切片坐标用整数，避免 UV 抖动。
- 圆角面板可以直接用 `nvgRoundedRect` + 渐变/阴影，不必强制九宫格；两种方式都支持。

## 4. 字体

字体由 `FontRegistry` 管理，默认给 NanoVG 使用 TTF/OTF。

```yaml
fonts:
  body:
    provider: nanovg_ttf      # nanovg_ttf | nanovg_bitmap | native
    source: local             # local | mod | resource
    id: "公共/字体/body.ttf"
    fallback: "公共/字体/fallback.ttf"
    line_gap: 1
  title:
    provider: nanovg_ttf
    source: resource
    id: "mypack:ui/title.ttf"
    fallback: "cloudcore:ui/body.ttf"
```

- `source: local`：从 `.minecraft/resourcepacks/CloudCore/` 读取；可按 `副本/`、`签到/`、`公共/`
  等业务目录分类。正式素材放这里。
- `source: mod`：模组 jar 内置，只用于最小回退。
- `nanovg_ttf`：读取 TTF/OTF 字节，`nvgCreateFontMem` 创建字体；字体创建在渲染线程。
- `nanovg_bitmap`：v2；位图字体页转成 NanoVG 图像字体或字形 quad。
- `native`：原版 `Font`，用于 Tooltip 内容、EditBox、部分 Boss 标题。
- 文本测量用 `nvgTextBounds` / `nvgTextMetrics`；换行用 `nvgTextBreakLines`，CJK 按字符回退。
- 行高 = 字体度量 + `line_gap`。
- 中文、日文、韩文需要字体覆盖对应字形；缺字回退链要配置。
- 原版文本和 NanoVG 文本是两套度量，不能混用。输入框文本与 Tooltip 内容由原版绘制。
- 字体热更新：`F9` 或资源包重载时重新创建字体；服务端不能直接推送字体文件。

## 5. 颜色与混合

- 颜色是 `0xAARRGGBB`；NanoVG 使用 `nvgRGBA(r,g,b,a)` 或 `nvgRGBAf`。
- NanoVG 输出**预乘 alpha**；FBO 合成时必须使用匹配的混合函数，否则半透明边缘会发黑/发白。
- 文本默认不透明；半透明文本在多数资源包上很难看。
- 面板染色是乘法；`0xFFFFFFFF` 表示不染色。
- 合成前清空 FBO 为透明，避免上一帧残留。

## 6. 动画

声明式，客户端执行：

```yaml
animation:
  on_open:
    - property: opacity
      from: 0
      to: 1
      duration_ms: 140
      easing: ease_out_cubic
```

支持属性：`opacity`、`tint`、`offset_x`、`offset_y`、`scale`、`scroll_to`、`rotation`（NanoVG 变换）。

- 基于 `Util.getMillis()`，不基于 tick。
- 单元素最多 4 个属性同时动画。
- 带槽位的元素禁止位移/缩放动画；`opacity`/`tint` 允许。
- `animations: false` 时所有时长归零。
- 不可见时不运行动画，也不重绘 FBO。
- 静态层可以缓存为 FBO 子纹理，动画层单独重绘。

## 7. 渲染管线与合成

1. NanoVG 在独立 pass 中渲染背景层到 FBO A。
2. `FboCompositor` 把 A 通过 `GuiGraphics.blit` 合成到屏幕。
3. 原版桥绘制物品、槽位、数量/冷却/光效、EditBox 文本。
4. NanoVG 渲染前景层到 FBO B（`z >= 100`、Tooltip 外框、Boss 外框）。
5. `FboCompositor` 合成 B。
6. 原版桥绘制 Tooltip 文本、Boss 标题、原生 tooltip 组件。

规则：

- 没有原版内容的界面合并成一个 NanoVG 层，减少 FBO。
- 1.21.6+ 是提取/绘制两阶段：NanoVG 在界面 `render` 的早期、合成入队之前完成 FBO 渲染，
  `GuiGraphics.blit` 只入队合成；不要在提取阶段插入未隔离的 GL 调用。
- 保存/恢复 `RenderSystem` / `GlStateManager` 状态：混合、深度、裁剪、framebuffer 绑定。
- FBO 使用 `FboTexture extends AbstractTexture` 包装，注册给 `TextureManager` 后由 `GuiGraphics.blit`
  使用；或使用等价的 `RenderPipeline` 合成路径（以 1.21.11 实测为准）。
- 合成失败时当前帧回退 `NativeBackend`。

## 8. 裁剪

- NanoVG 用 `nvgScissor` / `nvgIntersectScissor`；每次 `nvgSave` 配对 `nvgRestore`。
- 原版物品/文本用 `GuiGraphics.enableScissor` / `disableScissor`，与 NanoVG 的裁剪矩形共享同一逻辑坐标。
- 裁剪栈在层切换时重置，避免泄漏。
- 空交集直接跳过绘制。
- 滚动容器内的槽位由原版裁剪和 NanoVG 裁剪共同约束。

## 9. 物品渲染（原生桥）

- 真实槽位由原版槽位通道绘制；NanoVG 只画槽位外框和背景。
- 数量文字、耐久条、冷却遮罩、附魔光效由原版绘制；数量字体可通过
  `renderItemDecorations(Font, ...)` 指定（具体重载以 1.21.11 源码为准）。
- 装饰性物品图标由原版 `renderItem` 绘制，位置换算到屏幕坐标。
- 不重写物品模型、方块模型、资源包模型或自定义 item renderer。
- 物品 tooltip 由原版生成，NanoVG 只画外框。

## 10. Tooltip 渲染与滚动

- NanoVG 画外框、背景、滚动条；文本内容由原版 `Font` 绘制。
- 内容行由原版物品提示生成，其他模组通过 `ItemTooltipCallback` 追加的行也在其中。
- 可见高度 = `min(内容高度, max_height, 屏幕可用高度)`。
- 内容超高时固定提示框位置，滚轮按 `scroll_step` 滚动，可选 100 ms 平滑。
- 滚动条样式来自 `surface_options.tooltip.scrollbar`：轨道、滑块、悬停态、九宫格、宽度、间距、
  最小滑块高度、颜色、`draggable`。
- 内容未超高时不消费滚轮。
- 悬停物品变化时偏移归零。
- 绘制顺序：NanoVG 外框/滚动条 → 原版文本。滚动条在侧边，不与文本重叠。

## 11. Boss 血条渲染

- NanoVG 画背景、填充、覆盖层；标题默认原版 `Font`，可配置走 NanoVG。
- 进度来自 `BossEvent#getProgress()`；填充按 `direction` 裁剪。
- `tint_from_event` 用事件颜色给填充染色。
- 多个血条顶部居中堆叠，间距 `spacing`，最多 `max_bars`。
- 未匹配的血条保持原版绘制。
- 标题剥离哨兵后绘制。

## 12. HUD 渲染

- 整层由 NanoVG 绘制到 FBO 后合成。
- HUD 中的物品图标由原生桥绘制，夹在背景层与前景层之间。
- 只读，无槽位、无输入。
- 没有可见元素时不重绘 FBO。
- 状态更新走 `ELEMENT_UPDATE`；没有几何变化时不重算布局。

## 13. 性能预算

| 指标 | 目标 |
| --- | --- |
| 100 元素布局排布 | < 0.1 ms |
| NanoVG 背景层（100 元素） | < 0.4 ms |
| NanoVG 前景层（20 元素） | < 0.15 ms |
| FBO 合成（1x） | < 0.2 ms |
| FBO 合成（2x） | < 0.4 ms |
| 打开界面到首帧（文档已缓存） | < 50 ms |
| Tooltip 64 行滚动 | < 0.3 ms |
| Boss 血条 3 条 | < 0.15 ms |
| 空闲 HUD | 0 次 FBO 重绘 |

FBO 成本参考：

- 480×270×4 ≈ 0.5 MB/层，60 fps 约 31 MB/s 的帧缓冲填充带宽。
- 960×540×4 ≈ 2 MB/层，60 fps 约 124 MB/s 的帧缓冲填充带宽。
- 这是 GPU 显存带宽/填充率，不是 CPU→GPU 上传；NanoVG 渲染到 FBO 后直接采样合成，没有 readback。
- 两层 FBO 翻倍；优先合并层级、脏矩形、静态层缓存。
- 不要按 1920×1080 全分辨率渲染。

测量：

- 120 帧滚动平均，F3 叠加层输出后端、FBO 尺寸、每层耗时、填充像素数。
- `/cloudcore-client bench` 渲染 200 元素合成界面 10 秒并打印直方图。

## 14. 资源管线

- 正式美术和字体在 `.minecraft/resourcepacks/CloudCore/` 下按业务分类；服务端只下发相对路径。
- 模组 jar 只保留最小回退：白色占位、红色“资源缺失/网链请求失败”、默认字体。
- 资源包美术可以由服务器/启动器统一分发，但分发本身不在本插件范围。
- `FontRegistry` 负责本地/资源包/内置字体的加载与回退。
- CI 检查 `builtin:` 回退键存在；本地素材由管理员用 `F9` 和日志校验。
- 内联贴图单张不超过 512×512。
- NanoVG image handle 在 `F9` 或资源包重载时释放并重建。

## 14.1 动画贴图（GIF）

- 两种来源：客户端直连时客户端解码；服务端代理时服务端解码。
- 解码结果打包成垂直精灵表，作为一张 NanoVG image；按帧绘制子矩形。
- 帧延迟最小 50 ms；`speed` 乘在延迟上；`loop: false` 停在最后一帧。
- 上限：64 帧、单帧 512×512、解码后 16 MiB、编码后精灵表 ≤ 1 MiB。
- `animations: false`、界面隐藏时暂停。
- 失败显示红色“网链请求失败”或第一帧，并写客户端日志；不静默空白。

## 15. 无障碍与可读性

- 最小字号为 NanoVG 字体在 GUI 缩放下的 9 px 等效高度；更小触发校验警告。
- 默认主题正文与面板对比度至少 4.5:1。
- 文本保持为文本，不渲染成图片。
- 输入框和 Tooltip 文本仍由原版绘制，保证输入法、选择与朗读行为。
- 文档在 GUI 缩放 1 和 4 下都必须可用。
