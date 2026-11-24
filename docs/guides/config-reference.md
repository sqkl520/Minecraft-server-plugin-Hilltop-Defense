[English](config-reference-EN.md) | 简体中文

# 配置文件逐项解释

本文由 README 对应章节下移而来，README 侧保留摘要与入口链接。原 README 的 starter-kit 段落在 YAML 围栏之外（渲染会散成段落），此处把闭合围栏移到 starter-kit 之后修复，正文逐字未动。

## arenas.yml —— 竞技场与大厅（核心配置）

每个竞技场一个节点，包含世界、等待区、核心、生成点、人数与波次。

```yaml
default-arena: arena1          # /hilltop join 不带参数时加入的竞技场

lobby:
  world: world                 # 大厅世界
  spawn: {x: 0.5, y: 100.0, z: 0.5, yaw: 0.0, pitch: 0.0}

arenas:
  arena1:
    display-name: "&6&l天空堡垒"
    enabled: true
    world: hilltop_arena1      # 竞技场世界（必须与大厅不同）
    waiting-location: {x: 100.5, y: 80.0, z: 100.5}   # 开局前等待区（可选；缺省回退 core）
    core: {x: 100.0, y: 64.0, z: 100.0}               # 玩家重生点
    spawn-points: []           # 玩家出生点（部署向导写入）
    mob-spawn-points: []       # 怪物刷怪点（部署向导/命令写入）
    villagers: []              # 村民位置（部署向导写入）
    game:
      min-players: 2
      max-players: 6
      victory-waves: 20
      wave-interval-seconds: 30
```

> 注：手动编辑 `arenas.yml` 后执行 `/hilltop reloadconfig` 即可重新读取（进行中的对局仍沿用开局时的设置）；`/hilltop reloadconfig` 同时重载 game-settings.yml + monsters-config.yml + messages.yml + display.yml。详见同目录 `commands-reference.md` 的 `/hilltop reloadconfig` 一行，以及 `arena-lifecycle.md` 的「参数编辑无需重启」。

## game-settings.yml —— 游戏参数

可用 `/hilltop config` 的 GUI 编辑，或直接改文件后 `/hilltop reloadconfig`。

```yaml
# 基础
min-players: 2                   # 开始游戏所需最少玩家（仅对「未在 arenas.yml 显式配置」的竞技场生效，见下方说明）
max-players: 6                   # 单局最大玩家数
victory-waves: 20                # 胜利所需波次
wave-interval-seconds: 30        # 波次之间休整时间
wave-timeout-seconds: 0          # 波次超时保险：默认关闭（0）；遇到异常情况用 /hilltop wave clear 或 wave skip 手动处理。改回正数（如 120）可重新启用"残怪传送到玩家并高亮 → 宽限 15 秒强制清除"的自动保险

# 阶段 1-3 新增
defeat-countdown-seconds: 20     # 失败后显示战报到传送回大厅的倒计时
villager-health: 20.0            # 村民最大生命值（全部阵亡 = 失败）
rejoin-timeout-seconds: 60       # 掉线重连窗口，0 = 关闭重连
empty-arena-end-seconds: 120     # 空置自动结束：对局中在线成员为 0 持续此时长后自动结束本局，0 或负数 = 禁用（不推荐）

map-restore:
  enabled: true                  # 对局结束回滚被破坏/放置的方块
scoreboard:
  enabled: true                  # 侧边栏记分板

# 节点
nodes:
  base-health: 100.0             # 节点初始血量
  buff-radius: 20.0              # Buff 生效半径
  repair-cost-item: GHAST_TEAR   # 修复物品
  repair-cost-amount: 1          # 每次修复消耗几个
  repair-amount-per-cost: 50.0   # 每消耗一个恢复多少血量
  buff-effects:                  # 节点增益（格式 "效果名:等级"，0 = I 级）
    - "RESISTANCE:0"
    - "REGENERATION:0"
  buff-refresh-ticks: 60         # 增益刷新周期
  buff-stack-cap: 3              # 多节点叠加的等级上限
  buff-duration-ticks: 100       # 单次增益持续时间

# 生成
spawning:
  spawn-radius-min: 20
  spawn-radius-max: 40
  spawn-y-offset: 0              # 生成高度偏移
  mob-cap-per-player: 15
  global-mob-cap: 80
  airdrop-max-per-dropper: 3     # 单个飞行抛投者整局空投轮数上限（防止空投失控卡服），0=禁用空投

# 开局初始套装（开局传送后向每名在场玩家发放；背包满则掉脚下）
# 默认：石剑 + 皮革四件套 + 面包x16 + 火把x8
starter-kit:
  enabled: true                  # 总开关：false = 不发放
  items:                         # material = 原版 Material 名，amount = 数量
    - material: STONE_SWORD
      amount: 1
    - material: LEATHER_HELMET
      amount: 1
    - material: LEATHER_CHESTPLATE
      amount: 1
    - material: LEATHER_LEGGINGS
      amount: 1
    - material: LEATHER_BOOTS
      amount: 1
    - material: BREAD
      amount: 16
    - material: TORCH
      amount: 8
```

## game-settings.yml 的 `waves` 段 —— 波次配比

`game-settings.yml` 末尾的 `waves` 段（对应 GUI 的「波次组合」页）决定每一波刷什么、刷多少。

```yaml
waves:
  default:                       # 未特别配置的波次使用此模板（出厂只单独配置 1 / 5 / 10 三波）
    total-mobs: 10               # 每人精确总数上限（写 0 = 本波不刷怪）
    compositions:
      explode-beetle: {count: 4, weight: 3}
      hook-claw-hunter: {count: 3, weight: 2}
      flying-dropper: {count: 3, weight: 1}
  "1":                           # 数字键 = 波次编号（带引号，YAML 要求键是字符串）
    total-mobs: 8
    compositions: {explode-beetle: {count: 6, weight: 5}, hook-claw-hunter: {count: 2, weight: 1}}
```

三条要点：

| 字段 | 语义 |
|---|---|
| `count` | **每人只数**：实际刷出 `count × 在场人数`。第 1 波甲虫 `count: 6`、2 人局 → 12 只 |
| `total-mobs` | **每人精确总数上限**：`sum(count) × 人数` 超过它时按比例裁剪，不足不补齐。三层裁剪取最小（`total-mobs` → `mob-cap-per-player` → `global-mob-cap`） |
| `weight` | 兼容权重：**仅当本波所有 `count` 全 0 / 缺失**时才回退旧权重随机路径；正常路径忽略它 |

**`total-mobs: 0` 可以停用一个波次**喵。它让 `sum(count) × 人数` 被裁到 0 只，`WaveManager` 的 `maxTotal <= 0` 分支立刻回调，一只不刷 —— 这是「跳过某一波」的唯一受支持写法（**不要**用「把 `waves.default` 整段删掉」来表达「不刷怪」：段缺失按「恢复出厂」处理，会被写回）喵。

**删除与重载的语义**（1.1.0 起）：

- 在 GUI 里删掉一个波次后**必须点 [保存并应用配置]**；删完不保存就 `/hilltop reloadconfig`，该波次会按磁盘内容**重新出现**（磁盘才是权威）喵。
- 反过来，手工写进本文件的新波次编号（如 `waves: {"7": ...}`）在 `/hilltop reloadconfig` 或重启后**会真的生效**（旧版本会静默丢弃，1.1.0 已修）喵。
- 只写 `total-mobs`、不写 `compositions` 的波次会退化成「刷 5+2×波数 只原版僵尸」，本版会在控制台打一条汇总 WARNING 点名该波喵。

## display.yml —— 侧边栏与 Tab 栏布局

侧边栏记分板和玩家列表（Tab）的**布局**配置。文件由服主手写，插件**只读不写**，所以注释会长期保留。

```yaml
scoreboard:
  enabled: true              # 与 game-settings.yml 的同名开关取「与」关系
  title: scoreboard.title    # 标题文案的 i18n 键（值必须是键名，不是文本）
  refresh-ticks: 10          # 整板重算周期，5-200
  lines:
    - key: scoreboard.wave
      args: [wave, totalWaves]
      when: in-game
```

**四种行型**（每行恰好一种）：

| 写法 | 含义 |
|------|------|
| `separator: 20` | 一条分隔线（数量 = 宽度，1-40） |
| `blank: true` | 空行 |
| `key: <i18n 键>` | 静态行，文本来自 `messages.yml` |
| `anim: [键1, 键2, ...]` | 动画行，2-8 帧循环，配 `frame-ticks` 控制每帧时长 |

**可选字段**：`args`（该行 `{0}{1}...` 的取值来源，顺序即位置）、`when`（可见条件）、`frame-ticks`（仅 `anim`）。

**条件名**：`always` `waiting` `starting` `running` `intermission` `in-game` `finished` `start-countdown` `waiting-for-players` `alive` `spectator`

**参数名**：`wave` `totalWaves` `alivePlayers` `spectators` `activeNodes` `totalNodes` `aliveVillagers` `totalVillagers` `playerKills` `playerDeaths` `playerCrystals` `startCountdown` `playerCount` `minPlayers` `remainingMobs` `nextWaveSeconds` `arenaName` `gameState`

**上限**：侧边栏最多 15 行，Tab 头/尾各最多 8 行。

**容错**：写错的条件名 → 该行永不显示（不报错）；写错的参数名 → 该行被跳过；数值越界 → 夹到边界。全部都会在控制台打一条英文 WARNING。整份文件坏掉时沿用内置默认布局，侧边栏不会变空白。

> **关于 `nextWaveSeconds`**：`GameManager` 没有落地休整期剩余秒数，该值返回的是**配置的波次间隔**而非真实倒计时，只在休整期刚开始时相等。默认布局不引用它。

### `display.yml` 的 `tab:` 段 —— 玩家列表（Tab）

```yaml
tab:
  enabled: true
  refresh-ticks: 20          # 头/尾刷新周期 tick，1-200（出厂 20 = 1 秒）
  header-footer-mode: auto   # auto / always / never（见下）
  contexts:
    lobby: true
    arena: true
    spectator: true
```

`player-name.alive/dead/spectator` 指向 `messages.yml` 的名字格式键（值必须是键名，不是字面量文本）。

**`header-footer-mode` —— 与代理侧 TAB 争不争「表头/表尾」**

`sendPlayerListHeaderAndFooter` 在协议层是「每个玩家唯一一份值，没有作者概念」，谁后发谁生效。代理侧装 TAB 的群组服上，TAB 会以很短的周期重发自己的表头，而本插件也每 20 tick 发一次，结果是玩家看到表头每秒整块替换、两层内容互相覆盖。关键事实是**不能指望 TAB 挡掉别人**：TAB 的反覆盖（block 其它插件的 header/footer）在 Velocity 安装下按官方说明是不可用的，本插件发出的每个包都会真的到达客户端。

| 取值 | 行为 | 什么时候选 |
|------|------|-----------|
| `auto`（出厂） | 检测到 TAB-Bridge 就不发头/尾，检测不到就照常发 | 默认。单机服没有 TAB-Bridge，所以 `auto` 恒等于 `always`；群组服代理侧跑 TAB 时自动让渡 |
| `always` | 永远发 | 代理侧 TAB 的 header/footer 已用 `%server%` 条件关掉，想让本插件自己渲染 |
| `never` | 永远不发，并把此前发过的擦一次 | 运维明知代理在管头/尾时，用来彻底让出 |

`auto` 探测的是 **TAB-Bridge**（子服侧的后端插件），不是 TAB——TAB 是代理插件，子服上永远没有它；判据是 `isPluginEnabled("TAB-Bridge")`。检测到之后本插件停止写这个字段，并把此前写过的内容擦掉一次（发一个空值再清缓存）——只清缓存不发空值会把旧表头永久冻结在玩家屏幕上。

> **别用 `tab.enabled: false` 来「交出 Tab」**：它的语义是「整个 tab 都停」，名字与分带排序一起停，而且**刻意不擦除**已发出的头/尾（只清缓存不再发包），刚关掉时玩家可能还留着一块冻结的旧表头。要让出头/尾用 `header-footer-mode: never`；只想让出某一个上下文用对应的 `contexts.X: false`（它会擦一次头/尾并把名字与排序复位成客户端默认）。

### PlaceholderAPI 占位符支持

`messages.yml` 的文本在渲染前会过一遍 PlaceholderAPI，所以可以在消息里写 `%player_name%`、`%vault_eco_balance%` 这类占位符。PAPI 以 `provided` 引入、**不打包**进 JAR；没装 PAPI 时安静降级，原样返回文本。

具体生效的渲染点：**侧边栏正文行、Tab 头/尾的每一行、以及 `player-name` 指向的名字格式**。侧边栏的**标题**（`scoreboard.title`）走的是另一条渲染路径，目前**不**经过 PAPI 解析——标题里写占位符会被原样显示。

**解析用的「上下文玩家」按位置不同**，这是最容易搞错的地方：

- `player-name`（即 `playerListName`）→ **被渲染的那名玩家本人**。名字是目标的全局属性，没有观看者维度。
- 侧边栏 / 头 / 尾 → **看这份内容的那名玩家**（他自己屏幕上显示什么）。

**两个陷阱：**

- **只装 PlaceholderAPI 不足以让 `%UserPrefix_prefix%` 工作**——那是 UserPrefix 插件注册的扩展，必须在同一台子服上另装 UserPrefix（并让它与代理侧共用同一套 LuckPerms 数据）。PAPI 对未注册的扩展原样返回输入串，所以「装了 PAPI 还是没解析」不是 bug，是缺了对应扩展。
- **解析不掉的占位符会被剔除，而不是原样发出**：结果里残留的 `%identifier%` 片段会被删掉，并对每个 `(来源文本, token)` 在控制台告警一次（不是每帧刷屏）。`100%`、`50%折扣` 这类不含占位符形状的文本不受影响。

> 出厂配置里一个 `%` 都没有，本插件先扫 `%` 再决定是否调用 PAPI——默认部署下 PAPI 一次都不会被调到。

## config.yml

内部状态文件，**不含任何游戏参数**（游戏参数见 `game-settings.yml`），只保存语言与部署向导进度，插件自动维护。

```yaml
language: "zh"    # zh / en
```

完整说明请参考 `plugins/HilltopVillage/` 下的各配置文件（文件内均带中文注释）。

> 注：`game-settings.yml` 里 `min-players` 那句「见下方说明」指的是 —— 它是**全局默认值**，仅对未在 `arenas.yml` 中显式写出 `game.min-players` 的竞技场生效；已写明的竞技场以该竞技场自己的值为权威（配成 `1` 即可单人开局），配置 GUI 里的 `min-players` 对这些竞技场不生效。

## 改完怎么生效

| 改的是什么 | 生效方式 |
|------|------|
| `game-settings.yml` / `monsters-config.yml` / `messages.yml` / `display.yml` | `/hilltop reloadconfig`（管理员） |
| `arenas.yml`（手动编辑） | `/hilltop reloadconfig`（进行中的对局仍沿用开局时的设置） |
| `arenas.yml` 的 per-arena 游戏参数（经 `/hilltop manage` 参数页） | 立即写入并推送到运行中的竞技场，人数下一局生效 |
| `shop.yml` | `/hilltop shop reload`（管理员） |
| 经 `/hilltop config` GUI 保存的游戏规则 | 点 [保存并应用配置]，**新配置在下一局开始时生效** |
