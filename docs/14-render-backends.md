# 14 — 渲染后端与回退

默认渲染后端是 **NanoVG 离屏渲染 + 原生合成**。原生 `GuiGraphics` 后端保留为完整回退。
本文说明后端边界、选择规则、失败回退，以及为什么不再把原生作为默认。

## 1. 后端结构

```java
public interface RenderBackend {
    BackendId id();
    boolean available();
    void beginFrame(FrameContext context);
    void draw(RenderList list, Layer layer);
    void endFrame();
}
```

| 后端 | 用途 | 实现 |
| --- | --- | --- |
| `NanoVgBackend` | 默认 UI 绘制 | NVGContext + FontRegistry + FBO |
| `NativeBackend` | 回退/兼容 | `GuiGraphics` + `RenderPipelines.GUI_TEXTURED` |
| `SdfBackend` | 可选实验 | 自定义 `RenderPipeline` + SDF quad |

- 布局、命中测试、输入、状态更新完全独立于后端。
- `RenderList` 是后端无关的绘制指令：矩形、九宫格、文本、图片、裁剪、变换。
- 服务端和协议不感知后端；文档只声明效果，客户端不支持时降级。

## 2. 默认：NanoVG 离屏

```
RenderList
  ├─ background layer → FBO A → FboCompositor → screen
  ├─ NativeBridge: items / slots / EditBox text
  └─ foreground layer → FBO B → FboCompositor → screen
```

- NanoVG 画面板、静态文本、图片、九宫格、圆角、渐变、阴影、裁剪、变换。
- FBO 按逻辑分辨率乘整数倍（1x/2x）分配，窗口/GUI 缩放变化时重建。
- `FboCompositor` 把 FBO 颜色纹理包装成 `AbstractTexture`，用 `GuiGraphics.blit` 合成。
- 预乘 alpha、混合函数、GL 状态恢复必须实测；合成失败回退原生。
- 1.21.6+ 提取/绘制两阶段：NanoVG 在界面 `render` 的早期、合成入队之前完成 FBO，
  `GuiGraphics.blit` 只入队合成。

## 3. 原生桥

以下内容始终由原版绘制：

- 物品模型、方块模型、资源包模型、3D 变换、GUI 光照。
- 数量文字、耐久条、冷却遮罩、附魔光效、容器预览、盔甲纹饰。
- 模组自定义 item renderer 和 tooltip component。
- Tooltip 文本、属性/附魔行。
- `EditBox` 文本、光标、选区、IME。

NanoVG 只画它们的外框、背景和装饰。这样既换皮，又不重写 Minecraft 的内容体系。

## 4. 回退链

```
配置指定 → NanoVgBackend.available() → NativeBackend
```

回退触发条件：

- `lwjgl-nanovg` 或原生库加载失败。
- NVGContext 创建失败。
- FBO 创建/resize 失败。
- NanoVG 绘制抛异常/GL 状态无法恢复。
- 用户配置 `renderer: native`。
- 已知光影包/Iris 组合下出现严重色差，客户端配置强制回退。

回退是整帧级别的：同一帧内不混用 NanoVG 和原生绘制同一层，避免状态错乱。

## 5. 为什么不用其它方案

| 方案 | 不选的原因 |
| --- | --- |
| CEF / Ultralight | 包体、进程模型、输入法、安全面太重，和游戏 UI 帧预算不匹配 |
| ImGui | 即时模式适合调试工具，不适合像素风、可主题化的游戏 UI，IME 弱 |
| Skia / Skiko | 能力强，但 JNI、原生库体积、GPU 上下文集成成本高，收益不如 NanoVG |
| Java2D 逐帧光栅 | CPU 成本高、每帧上传纹理，只适合静态预渲染 |
| 纯原生 `GuiGraphics` 默认 | 圆角/渐变/阴影/矢量文本能力不足，服务端样式表达受限 |

SDF 后端保留为实验：不需要原生库，适合只想要圆角/描边/阴影的场景，但表现力和 API 稳定性不如
NanoVG，不作为默认。

## 6. 原生库与打包

- 依赖 `org.lwjgl:lwjgl-nanovg` 与 Windows/Linux/macOS natives。
- LWJGL 版本必须与 Minecraft 自带版本对齐，避免重复类和原生库冲突。
- 原生库从模组 jar 解压到临时目录；加载失败记录一次并回退。
- 客户端构建已把 `lwjgl-nanovg:3.3.3` 类与 Windows/Linux/macOS 原生库并入 jar；
  Windows x64 的 context + FBO 绘制/readback 已通过，Linux/macOS 尚未运行验证。
- 三个平台都要跑初始化与合成测试，尤其是 macOS 的 GL 上下文限制。

## 7. 测试矩阵

- Windows/Linux/macOS：NanoVG 初始化、FBO 创建、字体加载、合成。
- GUI 缩放 1–4、窗口/全屏、分辨率变化。
- Sodium/Iris 开/关、资源包开/关。
- 中文/日文/韩文输入法、长文本、Tooltip、物品槽位。
- 回退：禁用原生库、强制 FBO 失败、模拟 NanoVG 异常。
- 性能：480×270/960×540 FBO、单层/双层、脏矩形。

## 8. 结论

NanoVG 是默认渲染后端；原生 `GuiGraphics` 是完整回退；SDF 是可实验的中间方案。
物品、槽位、Tooltip 内容、输入法文本永远走原版。渲染后端只是“换画法”，不改变文档、协议和输入模型。
