# 13 — 动作引擎（v1 线性 / v2 图执行）

动作引擎分两阶段：v1 只有线性动作列表；v2 是服务端图执行器。本文档定义目标模型、接口接缝和迁移路径，
避免 v1 的实现堵死 v2。

## 0. 状态、边界与优先级

- **执行位置**：服务端主线程调度器；客户端永远不执行动作或脚本。
- **与渲染无关**：NanoVG/原生只影响绘制；动作产生的是会话状态、`ELEMENT_UPDATE`、
  `UI_OPEN`/`UI_CLOSE`、命令、消息、声音等。渲染后端切换不影响动作引擎。
- **v1（M8）**：线性动作列表、条件、权限、冷却、延迟、`ActionEngine` 接口、
  `LinearActionEngine`、`ActionHandler` 注册。
- **v2（M9，后期）**：图执行器、分支/循环/等待/并行/随机/子程序、玩家持久变量、事件恢复。
- **兼容性**：v1 的动作列表在 v2 编译为 `sequence` 节点，YAML 不需要重写。
- **依赖**：M4 的页面/遍历、M1 的协议事件；不依赖 M5/M6 的视觉效果。
- **优先级**：如果先要服务端逻辑可用，可以把 M8 提到 M5/M6 之前；v2 仍建议在 M8 之后。
- **不在范围**：客户端脚本、下载代码、反射调用任意类、任意文件/网络访问。

## 0. 当前实现（v1 子集）

- 文档编译：`PageLoader` 把 `on:` 编译成 actionId + 服务端动作表；动作体不下发客户端。
- 客户端：点击命中带 `click` 事件的元素后发 `EVENT`（`actionId` 来自文档）。
- 服务端：`message`（聊天消息，含编译期模板 + 运行期 `${state.x}` 替换）、`close_ui`、
  `set_state`（写会话状态 → 重新编译页面 → 以更高 revision 重发 `UI_DEFINITION`）已可执行；
  客户端收到同一 `uiId` 的更高 revision 会替换界面。
- `open_page`：`page: <页面名>`（支持 `${...}` 模板），关闭当前页后发送目标页；
  页面导航不再需要命令方块或附属插件。
- `sound`：`sound: "minecraft:ui.button.click"`、`volume`（0..4，默认 1）、`pitch`（0.5..2，默认 1）。
- `EVENT_RESULT`：执行完成回 `OK`；未知 actionId 回 `UNKNOWN_ELEMENT`；动作类型不支持回 `REJECTED`。
- 动作级 `permission:`：缺权限整次点击 `REJECTED`，后续动作不再执行。
- `command`：`command: "say hi"`（可带前导斜杠），`as: player|console`（默认 player）。
  默认关闭：`config.yml` 的 `actions.commands.enabled: false`；开启后只有
  `actions.commands.allowlist` 里的第一个词能执行，其余回 `REJECTED` 并写警告日志。
- 附属动作处理器：`CloudCoreApi.registerActionHandler`，未注册的动作名写警告并 `REJECTED`。
- 表达式：`${state.balance - 100}`、比较、`&&`/`||`/`!`、字符串拼接由
  `ExpressionEvaluator` 求值（`set_state`/`message`/`if` 的条件都走它，整数值不显示 `.0`）。
- `if`：`condition` + `then`/`else` 动作列表；`delay`：`ticks` + `then` 延迟执行。
  表达式变量 = 文档默认 state 叠加玩家状态，再叠加事件载荷（`value`/`checked`/`index` 等）。
- 尚未支持：`wait`/超时、动作预算与串联限制、`EVENT_RESULT` 之外的执行报告、动作冷却
  （`cooldown` 动作与配置里的 `per-element-cooldown-ms` 都还没有实现）、
  `ActionEngine` 接口接缝与 `GraphActionEngine`（动作直接在 `CloudCorePlugin` 里执行）。

## 1. 目标与非目标

**目标**

- 服务端声明式动作图：序列、分支、循环、等待、并行、随机、子程序。
- 变量作用域与持久化玩家变量。
- 事件驱动的恢复执行：点击、输入、界面关闭、定时器。
- 与页面、面板、遍历、状态更新集成。
- 编译期校验 + 运行期预算，长流程不阻塞服务器。

**非目标**

- 客户端执行任何动作或脚本。
- 下载代码、反射调用任意类、任意文件/网络访问。
- 通用编程语言。文法封闭，节点固定。

## 2. v1 与 v2 的关系

v1：

```yaml
on:
  click:
    - action: message
      value: "hello"
    - action: set_state
      key: count
      value: "${state.count + 1}"
```

v2 编译为：

```
sequence
  ├─ invoke(message, "hello")
  └─ invoke(set_state, count, state.count + 1)
```

线性列表是图的退化形式，二者共用 `ActionContext` 与 `ActionHandler`。

## 3. 执行模型

- **线程**：所有动作在主线程的 tick 调度器中执行。
- **时间预算**：每会话每 tick 最多 1 ms，全局最多 5 ms；超出时让出，下一 tick 继续。
- **执行单元**：一次 `submit` 产生一个 `ExecutionHandle`，持有指令指针、局部变量、等待状态。
- **等待**：`delay` 与 `wait_event` 不占线程，挂在调度器的等待队列。
- **取消**：会话关闭、玩家退出、页面替换、显式 `cancel` 都会取消执行。
- **幂等**：`close_ui`、`cancel` 重复执行无副作用。
- **错误**：单个动作抛异常 → 中止该执行，记录 trace，不影响其他会话。

```java
public interface ActionEngine {
    ExecutionHandle submit(ActionContext context, CompiledAction program);
    void tick(long serverTick);
    void cancel(ExecutionHandle handle, CancelReason reason);
    Optional<ExecutionState> inspect(ExecutionHandle handle);
}
```

## 4. 变量与作用域

| 作用域 | 生命周期 | 持久化 | 用途 |
| --- | --- | --- | --- |
| `event` | 单次事件 | 否 | 载荷、按钮、索引 |
| `local` | 单次执行 | 否 | 循环变量、临时值 |
| `session` | 页面会话 | 否 | 当前界面状态，模板中写作 `${state.x}` |
| `player` | 玩家属性 | 否 | 名称、等级等只读属性，写作 `${player.name}` |
| `player.vars` | 玩家持久变量 | 是（磁盘） | 任务进度、冷却、偏好，写作 `${player.vars.x}` |
| `global` | 服务器 | 由插件决定 | 只读经济/统计，经占位符或 API 读取 |

类型：`string`、`boolean`、`int`、`long`、`double`、`list`、`map`。赋值做显式类型转换；
无法转换是运行期错误，不是静默 `null`。

玩家变量存储使用 `StorageProvider`：

| 类型 | 用途 | 位置 |
| --- | --- | --- |
| `sqlite`（默认） | 单服/开发 | `plugins/CloudCore/cloudcore.db` |
| `mysql` | 生产/多服持久化 | 外部数据库 |

表结构：

```sql
cloudcore_meta(schema_version INT)
cloudcore_player_vars(
  uuid CHAR(36),
  var_key VARCHAR(64),
  var_type TINYINT,
  var_value MEDIUMTEXT,
  updated_at BIGINT,
  PRIMARY KEY (uuid, var_key)
)
```

- 写入防抖 5 秒；退出时同步 flush，带超时；所有 DB I/O 走独立 executor。
- 单玩家变量上限 256 个键，序列化后 ≤ 256 KiB。
- 变量名匹配 `[a-zA-Z0-9_.-]{1,64}`。
- 迁移按 `schema_version` 顺序执行；升级前备份。
- v1 不做多服实时共享：MySQL 先做持久化/备份；需要跨服时再加版本号乐观锁。
- 服务端不把玩家变量整体下发给客户端；只下发模板求值后的文档。

## 5. 节点

### 5.1 序列 `sequence`

```yaml
- sequence:
    - invoke: { action: message, value: "第一步" }
    - delay: 20
    - invoke: { action: message, value: "第二步" }
```

### 5.2 分支 `if`

```yaml
- if:
    condition: "${state.balance >= 100}"
    then:
      - invoke: { action: set_state, key: balance, value: "${state.balance - 100}" }
    else:
      - invoke: { action: message, value: "&c余额不足" }
```

### 5.3 多路分支 `switch`

```yaml
- switch:
    value: "${event.index}"
    cases:
      "0": [ ... ]
      "1": [ ... ]
    default: [ ... ]
```

### 5.4 循环 `for_each`

```yaml
- for_each:
    source: "${player.vars.tasks}"
    as: task
    index: i
    max_iterations: 64
    body:
      - invoke: { action: message, value: "${i}: ${task.name}" }
```

### 5.5 循环 `while`

```yaml
- while:
    condition: "${local.retries < 3}"
    max_iterations: 10
    body:
      - invoke: { action: set, key: "local.retries", value: "${local.retries + 1}" }
```

`max_iterations` 必填，上限 1024。

### 5.6 延迟 `delay`

```yaml
- delay: 40      # tick
```

最长 72000 tick（1 小时）；更长的等待用 `wait_event` 或玩家变量加定时器。

### 5.7 等待事件 `wait_event`

```yaml
- wait_event:
    timeout: 600
    on_timeout:
      - invoke: { action: message, value: "&7确认超时" }
    accept:
      element: confirm
      event: click
```

- 等待期间释放执行线程。
- 超时后走 `on_timeout`。
- 会话关闭或玩家退出时，等待中的执行被取消。
- 同一个执行只能有一个未完成的 `wait_event`。

### 5.8 并行 `parallel`

```yaml
- parallel:
    branches:
      - [ { delay: 20 }, { invoke: { action: message, value: "A" } } ]
      - [ { delay: 40 }, { invoke: { action: message, value: "B" } } ]
    policy: all      # all | any | race
```

并行分支共享 `local` 的只读视图；写入必须是显式 `set`，按到达顺序合并。

### 5.9 随机 `random`

```yaml
- random:
    choices:
      - weight: 70
        body: [ ... ]
      - weight: 30
        body: [ ... ]
```

使用服务端 `ThreadLocalRandom`；可注入固定种子用于测试。

### 5.10 子程序 `call`

```yaml
- call:
    program: shop/purchase
    args:
      item: "${state.selected}"
```

子程序在独立 `local` 作用域执行；返回通过 `return` 节点或最后一个表达式。

### 5.11 动作调用 `invoke`

```yaml
- invoke:
    action: player_command
    value: "shop buy ${state.selected}"
    permission: "shop.buy"
```

等价于 v1 的单个动作。

### 5.12 结果与通知

- `emit`：向客户端发送 `NOTIFICATION` 或更新元素。
- `close`：关闭当前会话。
- `open`：打开另一个页面/面板。
- `cancel`：取消指定执行。

## 6. 触发方式

| 触发 | 说明 |
| --- | --- |
| 元素事件 | 点击、输入、滑块、复选框、遍历项 |
| 页面生命周期 | `on_open`、`on_close`、`on_timeout` |
| 面板生命周期 | 背包/ HUD / Tooltip / Boss 的启用与禁用 |
| 服务端事件 | 玩家加入、退出、重生、换世界、命令 |
| 定时器 | `delay`、`wait_event`、周期性 tick 触发 |
| API | 插件调用 `CloudCoreApi.runProgram(...)` |

所有触发在服务端主线程或在调度器中排队；客户端不能直接触发任意程序，只能产生文档中声明的事件。

## 7. 编译与校验

1. YAML/JSON 解析为动作 AST。
2. 节点白名单校验；未知节点是编译错误。
3. 变量引用解析：确认作用域根合法。
4. 模板表达式解析。
5. 动作名解析为 `ActionHandler` 或内置节点。
6. 权限注解提取。
7. 循环上限、嵌套深度（≤ 16）、节点数（≤ 4096）。
8. 编译为指令列表，分配稳定 program id。
9. 校验 `wait_event` 引用的元素存在，事件类型匹配。

编译失败阻止页面加载；运行期错误只中止当前执行。

## 8. 运行期预算与公平性

- 每 tick 全局 5 ms，每会话 1 ms。
- 超出预算的执行让出，排到下一 tick 队尾。
- `delay`/`wait_event` 不消耗预算。
- 每玩家同时最多 8 个执行；超出返回 `TOO_MANY_EXECUTIONS`。
- 循环节点每次迭代检查预算，避免长循环饿死其他玩家。
- 服务端关闭时，所有执行取消并记录未完成的玩家变量。

## 9. 持久化

- 玩家变量在防抖后写入；崩溃最多丢失 5 秒。
- 执行状态默认**不**持久化；服务器重启后未完成的流程取消。
- 需要跨重启的长任务，用玩家变量记录阶段，玩家重新加入时由 `on_join` 程序恢复。
- 未来可选执行快照，但 v2 不做。

## 10. 与页面/面板/遍历的集成

- 页面 `on_open` / `on_close` 触发程序。
- 遍历项的点击携带 `index` 与实例 id；程序可以通过 `${event.index}` 读取。
- 动作可以修改 `session` 状态，触发模板重新求值；元素数量变化仍走文档修订。
- `refresh` 动作/`refresh_placeholders` 节点立即触发占位符重解析，仍受同步/异步规则与每 tick 预算约束。
- 打开/关闭面板是普通节点，遵守面板的生命周期与权限。
- Tooltip 滚动、Boss 血条事件默认不上报；页面显式开启后才进入事件流。

## 11. 安全

- 节点白名单，无 `eval`、无反射、无类加载。
- 变量大小上限；列表/映射元素上限 4096。
- 循环/递归上限；子程序调用深度 ≤ 16。
- 命令动作不接受原始事件载荷插值；校验器对可疑模板告警。
- `console_command` 需要页面级权限与管理员作者身份。
- 执行日志记录 program id、触发者、节点路径，不记录输入框内容。
- 取消与超时必须有明确记录，避免"动作静默消失"。

## 12. 示例：带确认的购买

```yaml
on:
  click:
    - sequence:
        - if:
            condition: "${state.balance < state.price}"
            then:
              - invoke: { action: message, value: "&c余额不足" }
              - cancel: true
        - invoke: { action: set, key: "local.confirmed", value: false }
        - open:
            page: shop_confirm
            surface: custom_screen
        - wait_event:
            timeout: 600
            accept:
              element: confirm_yes
              event: click
            on_timeout:
              - invoke: { action: close_ui }
        - invoke:
            action: player_command
            value: "shop buy ${state.selected}"
        - invoke: { action: set_state, key: balance, value: "${state.balance - state.price}" }
```

## 13. 迁移路径

1. M8 实现 `LinearActionEngine`，只支持 `invoke`、`delay`、条件、权限、冷却。
2. 文档中的线性动作列表编译为 `sequence`。
3. M9 实现 `GraphActionEngine`，新增节点。
4. 旧页面不需要改 YAML；编译器识别两种写法。
5. 特性位 `action_engine_v2` 决定服务端是否接受图节点。

## 14. 可观测性

- `/cloudcore actions <player>`：运行中的执行、当前节点、等待状态、已消耗预算。
- 计数器：执行数、超时、取消、预算让出、错误。
- trace id 写入日志，便于把一次点击和后续动作串起来。
- 调试模式可输出节点路径，但不输出变量内容（可能含玩家输入）。
