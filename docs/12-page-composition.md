# 12 — 页面组合与遍历展开

一个页面可以由**一个 YAML 文件**直接描述，也可以由**多个片段**组合。组合在服务端加载期完成，客户端
永远只收到一份编译后的 `UiDocument`；字段定义见 `16-ui-document-schema.md`。

## 1. 模型

| 概念 | 说明 |
| --- | --- |
| 页面（Page） | 作者视角的完整界面，有 `id`、`surface`、`size` 等顶层字段 |
| 片段（Fragment） | 被页面包含的 YAML 文件，提供元素、状态、字体、面板选项的一部分 |
| 组合（Composition） | 按 `includes` 顺序深度优先合并，生成单一文档 |
| 展开（Expansion） | 运行期把 `repeat` 按列表状态展开为多个具体元素 |

组合是**静态**的（加载期），展开是**动态**的（状态变化时）。

实现状态（v1）：

- `cloudcore-api` 的 `PageLoader` 读取 YAML、合并 `includes`（深度 8、文件数 64、单文件 256 KiB）、
  展开 `repeat`（实例 id `<repeatId>[n].<childId>`、`gap` 堆叠、上限 256）、替换
  `${state.x}`/`${item.field}`/`${i}`，最后走同一套 `UiDocumentValidator`。
- 片段不得声明 `id`/`surface`/`container`/`size`/`scale`/`title`/`permission`/`theme`，否则报错。
- `UiDocumentJson.write` 把编译结果写回线上下发的 JSON；已做往返测试。
- Paper 插件启动时创建 `plugins/CloudCore/pages/` 并写入 12 张示例页；`/cloudcore page <名字> [玩家]`
  加载并下发，按页面自己声明的 `surface` 走表面路由（`custom_screen`/`hud`/`tooltip`/`boss_bar`/
  `player_inventory` 都可以）。
- 已实现：`PageWatcher` 监听 `pages/`（含子目录，`pages.watch` 默认开），`.yml`/`.yaml` 变化 400ms
  合并后回调主线程重载并推送更新；`/cloudcore validate`、`/cloudcore reload`、动作绑定（actionId +
  服务端动作表）。
- 尚未实现：依赖图增量编译（当前任何页面变化都全量重载所有页面）、内容哈希去重
  （`PageWatcher` 只按文件事件与时间窗合并，不比较内容）。

## 2. 目录结构

```
pages/
  shop/
    page.yml              # 页面清单
    parts/
      header.yml          # 片段
      grid.yml
      footer.yml
      common/
        buttons.yml       # 嵌套片段
  hud_status.yml          # 单文件页面
  inventory_skin.yml
  tooltip_theme.yml
  boss_theme.yml
```

- 目录页面：目录中存在 `page.yml` 或 `page.yaml` 即为页面清单。
- 单文件页面：`pages/` 下直接放 `.yml`，文件自身就是清单。
- 片段路径相对于页面目录，必须位于 `pages/` 之内。

## 3. 页面清单

```yaml
schema: 1
id: shop
surface: container
title: "&8商店"
theme: dark
size: [176, 166]
container:
  rows: 6
  interaction: managed
state:
  balance: 0
includes:
  - parts/header.yml
  - parts/grid.yml
  - parts/footer.yml
elements:
  - type: label
    id: page_marker
    text: " "
```

`id`、`surface`、`container`、`size`、`scale`、`title`、`permission` 只允许出现在页面清单中。
片段如果声明这些字段，校验器报错。

## 4. 片段

```yaml
# parts/header.yml
schema: 1
state:
  title: "商店"
fonts:
  title:
    provider: nanovg_ttf
    source: local
    id: "公共/字体/title.ttf"
    fallback: "公共/字体/body.ttf"
elements:
  - type: label
    id: header_title
    x: 8
    y: 6
    text: "${state.title}"
```

片段可以包含：

- `elements`
- `state`
- `fonts`
- `surface_options`（部分字段）
- `includes`（嵌套片段）

片段不能包含：`id`、`surface`、`container`、`size`、`scale`、`title`、`permission`、`theme`。
主题只能由页面声明，避免片段擅自换肤。

## 5. 合并规则

按 `includes` 声明顺序深度优先处理：

1. 先处理片段自身的 `includes`，再处理片段内容。
2. 元素**追加**到页面元素列表；顺序即声明顺序。
3. 元素 id 在合并后全局唯一；重复即错误。
4. `state` 浅合并：后出现的覆盖先出现的；页面顶层 `state` 覆盖片段；**调用方传入的玩家状态最后覆盖**
   （所以 `set_state` 的结果在重编译后仍然生效）。
5. `fonts` 按名称合并：后出现的覆盖先出现的。
6. `surface_options` 按面板类型深合并：后出现的覆盖先出现的字段。
7. `theme` 只来自页面；片段设置即错误。
8. 页面顶层 `elements` 在所有片段之后追加。

示例：

```
page.yml
  includes: [a.yml, b.yml]
    a.yml includes c.yml
  elements: [page_label]
```

合并顺序：`c` 的元素 → `a` 的元素 → `b` 的元素 → `page_label`。
`state` 覆盖顺序相同（后者优先）。

## 6. 名称与命名空间

- 元素 id 是合并后的全局 id，不做自动前缀。
- 为了避免片段冲突，推荐片段作者使用前缀，例如 `header_title`、`grid_slot`。
- 如果以后需要自动命名空间，会以 `namespace:element` 的形式引入，并保持向后兼容；v1 不做。
- `repeat` 生成实例 id 的规则见第 8 节。

## 7. 重载与依赖图

**目标形态**：`PageStore` 为每个页面记录其传递依赖的片段集合。

- `WatchService` 检测文件变化，按内容哈希去重。
- 一个片段变化 → 重编译所有传递依赖它的页面。
- 重编译是原子的：成功才替换修订号；失败保留旧版本并记录文件名与行号。
- 页面清单自身变化 → 重新计算依赖图。
- 删除片段 → 依赖它的页面编译失败，保留旧版本并在 `/cloudcore validate` 中报告。
- `/cloudcore reload` 全量重载，但仍走同一套校验。

**当前实现**：`PageWatcher` 只在 400ms 静默窗后触发一次全量 `reloadPages`，
没有依赖图、没有内容哈希；重编译失败时保留旧修订号并写服务端日志。
依赖图增量编译仍是待办。

依赖图示例：

```
shop ──► header.yml ──► common/title.yml
     └─► grid.yml
inventory_skin ──► common/buttons.yml
```

修改 `common/buttons.yml` 只会重建 `inventory_skin`，不会动 `shop`。

## 8. 遍历展开

`repeat` 在状态求值阶段展开：

```yaml
- type: repeat
  id: rows
  source: "${state.items}"
  as: item
  index: i
  max: 64
  gap: 2
  children:
    - type: button
      id: row
      text: "${i}. ${item.name}"
      on:
        click:
          - action: set_state
            key: selected
            value: "${item.id}"
```

规则：

- 实例 id：`<repeatId>[<index>].<childId>`，例如 `rows[7].row`。
- 子元素内的 `${item}`、`${item.field}`、`${i}` 在实例作用域求值。
- 展开结果参与文档元素总数上限（512）。
- 单 `repeat` 实例上限 256，嵌套最多 2 层。
- 列表长度变化：
  - 元素数量不变：只推送属性差异。
  - 元素数量变化：v1 提升修订号并下发完整文档；v2 动作引擎支持增删补丁。
- 实例 id 稳定意味着点击第 7 项时，`actionId` 与实例 id 能正确对应。

静态列表建议：

```yaml
state:
  tabs: [ "全部", "武器", "防具" ]
```

仍然在运行期展开，保证与动态列表行为一致。

## 9. 限额

| 项目 | 上限 |
| --- | --- |
| 单页面片段文件数 | 64 |
| include 深度 | 8 |
| 单文件大小 | 256 KiB |
| 合并后文档 JSON | 1 MiB |
| 合并后元素数 | 512 |
| 单 repeat 实例 | 256 |
| repeat 嵌套深度 | 2 |
| 展开后元素总数 | 512 |

超限时拒绝页面或拒绝本次状态更新，并记录具体文件、片段、repeat id。

## 10. 示例：多片段商店

`pages/shop/page.yml`：

```yaml
schema: 1
id: shop
surface: container
title: "&8商店"
theme: dark
size: [176, 166]
container:
  rows: 6
  interaction: managed
state:
  balance: 0
  selected: -1
includes:
  - parts/header.yml
  - parts/grid.yml
  - parts/footer.yml
```

`parts/header.yml`：

```yaml
schema: 1
elements:
  - type: label
    id: header_title
    x: 8
    y: 6
    text: "&e%player_name% &7的商店"
    font: "tx:font.title"
```

`parts/grid.yml`：

```yaml
schema: 1
elements:
  - type: inventory_grid
    id: shop_grid
    slots: [0, 44]
    origin: [8, 24]
    cell: [18, 18]
    columns: 9
```

`parts/footer.yml`：

```yaml
schema: 1
state:
  selected: -1
elements:
  - type: label
    id: footer_balance
    x: 8
    y: 140
    text: "余额: &6${state.balance}"
  - type: button
    id: footer_buy
    x: 88
    y: 138
    width: 80
    height: 20
    text: "购买"
    enabled: "${state.selected >= 0}"
    on:
      click:
        - action: message
          value: "shop buy ${state.selected}"
```

合并后与单文件版本行为一致。

## 11. 校验与 CLI

`/cloudcore validate <file>` 与启动加载共用同一个校验器，输出：

```
[ERROR] pages/shop/parts/grid.yml:12  duplicate element id "shop_grid"
[ERROR] pages/shop/page.yml:8         include cycle: parts/a.yml -> parts/b.yml -> parts/a.yml
[WARN ] pages/shop/parts/footer.yml:3  button "footer_buy" has no on.click
```

校验阶段：

1. 文件存在、可读、UTF-8。
2. schema 版本。
3. 片段字段白名单。
4. include 路径安全（不允许 `..`、绝对路径、逃出 `pages/`）。
5. 循环检测。
6. 合并。
7. 文档校验（元素、面板、字体、动作、模板）。
8. 限额。

## 12. 从单文件迁移

单文件页面就是 `elements` 内联的清单。拆分片段时：

1. 把 `elements` 按区域复制到片段。
2. 在页面清单中按顺序写 `includes`。
3. 删除页面清单中的对应元素。
4. 检查 id 是否重复。
5. 运行 `validate`。

客户端无感知：合并后仍是同一份文档，修订号从 1 开始重新计数即可（客户端按会话处理）。
