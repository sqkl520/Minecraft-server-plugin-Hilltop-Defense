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
    waiting-location: {x: 100.5, y: 80.0, z: 100.5}   # 开局前等待区
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

> 注：手动编辑 `arenas.yml` 需要重启服务器；`/hilltop reloadconfig` 重载的是 game-settings.yml + monsters-config.yml + messages.yml。详见同目录 `commands-reference.md` 的 `/hilltop reloadconfig` 一行，以及 `arena-lifecycle.md` 的「参数编辑无需重启」。

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
| `game-settings.yml` / `monsters-config.yml` / `messages.yml` | `/hilltop reloadconfig`（管理员） |
| `arenas.yml`（手动编辑） | **重启服务器** |
| `arenas.yml` 的 per-arena 游戏参数（经 `/hilltop manage` 参数页） | 立即写入并推送到运行中的竞技场，人数下一局生效 |
| `shop.yml` | `/hilltop shop reload`（管理员） |
| 经 `/hilltop config` GUI 保存的游戏规则 | 点 [保存并应用配置]，**新配置在下一局开始时生效** |
