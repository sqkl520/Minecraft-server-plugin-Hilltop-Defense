[English](README-EN.md) | 简体中文

> ⚠️ **这是一个初中生用AI做的插件，有任何问题建议欢迎提交Issues，如果您喜欢这个项目，就star一下吧！**

# Hilltop Village

一个面向 Minecraft 服务器的合作 PvE 塔防插件。玩家需要团结协作，保护村庄核心，抵御一波波不断增强的怪物进攻。

## 功能特性

**玩法**

- **合作塔防玩法** —— 多名玩家组队，守护村民不被怪物杀光，撑过全部波次即为胜利
- **波次进攻系统** —— 怪物逐波来袭，难度递增，每波之间有休整期
- **能量节点系统** —— 重生锚方块作为能量节点，为附近玩家提供抗性提升 + 生命恢复 Buff；可被怪物破坏、可用世界树汁液修复；**全部被摧毁后玩家无法复活**
- **待保护村民** —— 怪物会攻击村民，**村民全部阵亡即游戏失败**（数量由管理员部署决定）
- **死亡与重生** —— 阵亡后旁观等待下一波刷新，在核心复活；无活跃节点则无法复活
- **神圣重锤** —— 右键腾空蓄力猛击，从高处砸向地面，造成三档范围伤害 + 粒子冲击波 + 余震减速，支持 ItemsAdder 自定义模型
- **烈焰蛋** —— 仿起床战争 Fireball，右键投掷、飞行焰火粒子轨迹、命中爆炸范围伤害 + 击退 + 点燃，冷却可配置
- **村民商店** —— 击杀怪物掉落能量水晶（概率可配），右键商店村民打开商店 GUI 购买道具；商品、价格、槽位全部可在 `shop.yml` 自定义
- **特殊怪物 AI** —— 自爆甲虫（冲向节点自爆）、钩爪猎手（远程钩抓玩家 + 失明）、飞行抛投者（空降僵尸群），每种怪物可配盔甲/武器/自定义模型

**架构与体验**

- **多竞技场支持** —— 大厅 + 多个独立竞技场世界，可同时开多局互不干扰；玩家通过 `/hilltop gui` 选图加入
- **大厅系统** —— 进服统一落在大厅；大厅内禁止破坏/受伤/饥饿/天气，管理员可用 `/hilltop build` 临时解除
- **侧边栏记分板** —— 实时显示波次、存活、等待复活、节点、村民、个人击杀
- **玩家统计与排行榜** —— 胜率/K/D/最高波次等持久化，支持 `/hilltop stats` 与 `/hilltop top`
- **物品保护** —— 进竞技场时保存背包/经验/血量，离开时归还；快照落盘，服务器崩溃也不丢
- **掉线重连** —— 对局中掉线保留 60 秒（可配）重连窗口
- **地图恢复** —— 对局结束自动回滚被破坏/放置的方块，地图不会逐局劣化
- **图形化配置中心** —— 管理员通过 GUI 可视化配置游戏规则、波次组合、怪物属性、物品参数和能量节点，所见即所得
- **8 步部署向导** —— `/hilltop setup` 引导完成核心/生成点/节点/村民/商店部署，每步自动保存
- **多语言支持** —— 内置中英文双语，玩家可自行切换 `/hilltop lang <en|zh>`
- **生成点粒子展示** —— 部署后触发 30 秒彩色粒子动画展示所有生成位置

## 能量节点详解

能量节点使用**重生锚**方块。管理员在地图上放置多个重生锚（建议4-5个），插件会自动识别并注册为能量节点。

**重生锚的双重功能**：
- **原版功能** —— 玩家可以充能后设置为重生点（死亡后在此复活）
- **节点功能** —— 为附近玩家提供抗性提升 + 生命恢复 Buff，可被怪物攻击，可用世界树汁液修复

**节点机制：**

- **增益光环** —— 每个活跃节点为半径 20 格（可配）内的玩家持续提供 **抗性提升 I + 生命恢复** Buff；靠近节点的玩家更耐打、回血更快。**活跃节点 = 生命值大于 0 的节点**：添加时所有节点全部登记为活跃；被怪物打空血后失活（光环消失），使用世界树汁液修复后重新激活
- **可被摧毁** —— 节点有生命值（默认 100），怪物的普通攻击和自爆甲虫的自爆都会削减节点生命；生命归零后节点失活，光环消失
- **可修复** —— 使用"世界树汁液"（默认恶魂之泪 GHAST_TEAR，击杀下界恶魂获得，也可在村民商店购买世界树汁液商品；材料可在 game-settings.yml 的 `nodes.repair-cost-item` 修改，管理员也可用 `/hilltop config` → 能量节点设置修改）右键失活节点，恢复 50 点生命（每次消耗 1 个）
- **增减节点** —— 想增加节点 = 在世界中多放重生锚，然后在部署向导 Step 4 中点击 [添加当前节点] 注册；想移除节点 = 在节点管理 GUI 中点击对应节点删除

> 重生锚的充能和重生功能完全保留，玩家可以正常使用荧石为重生锚充能并设置为重生点。

## 商店系统

击杀波次怪物有概率掉落**能量水晶**（货币，默认 40% 概率掉 1 个，`game-settings.yml` 的 `shop.currency-drop-chance` / `shop.currency-drop-amount` 可调）。走到商店村民旁**右键**即可打开商店 GUI，左键商品图标完成购买。

**经济闭环**：怪物掉能量水晶 → 右键商店村民购买道具/世界树汁液 → 汁液右键失活节点修复 → 节点重新提供 Buff 帮助守村。

**商店村民部署**：

- 部署向导第 5 步（`/hilltop setup`）走到目标位置点 [设置商店位置]，或点 [跳过]（本图无商店）
- 位置按竞技场持久化在 `arenas.yml` 的 `arenas.<id>.shop-npc` 节点，服务器重启自动恢复；启动时自动清理崩溃残留的重复实体
- 商店村民无敌（任何伤害无效）、不可推动交互，仅作为商店入口

**shop.yml 自定义商品**（`plugins/HilltopVillage/shop.yml`）：

```yaml
shop:
  currency:
    material: EMERALD      # 货币物品材质
    name: "&a能量水晶"      # 货币显示名（支持颜色码）
  npc:
    name: "&6商店村民"      # NPC 头顶名称
  gui:
    title: "&8村民商店"      # GUI 标题（留空走语言文件 shop.gui.title）
    size: 27               # GUI 尺寸（9/18/27/45/54）
  items:
    sacred_hammer:
      slot: 10             # GUI 槽位号（0 ~ size-1）
      price: 5             # 价格（能量水晶数量）
      type: HAMMER         # HAMMER / FIREBALL / REPAIR_ITEM / MATERIAL
      amount: 1            # 一次购买给的数量（HAMMER 固定 1，忽略）
      icon:
        material: NETHERITE_AXE
      name: "&6神圣重锤"     # 显示名（支持颜色码）
      lore:                # lore 行（价格行自动追加）
        - "&7右键腾空蓄力，落地猛击"
```

- `type` 四种取值：`HAMMER`（神圣重锤，无视 amount 固定 1 个）/ `FIREBALL`（烈焰蛋）/ `REPAIR_ITEM`（世界树汁液）/ `MATERIAL`（任意原版物品，按 `icon.material` 给 amount 个）
- 修改后执行 `/hilltop shop reload`（管理员）无需重启即可生效
- 防刷保护：商店 GUI 内所有点击/拖拽一律取消（拿不走商品图标），购买有 250ms 冷却防连点重复扣费


## 部署指南（服主必读）

从零到开一局，共 6 个阶段。**首次部署请按顺序执行**。

### 阶段 1：准备世界

插件采用「大厅 + 竞技场」分离架构（多竞技场模式），至少需要 2 个世界：

| 世界 | 作用 | 说明 |
|------|------|------|
| **大厅世界** | 玩家进服等待、浏览菜单 | 例如 `lobby` |
| **竞技场世界** | 实际对局地图 | 例如 `hilltop_arena1`，每个竞技场一个独立世界 |

竞技场世界可以用 WorldEdit 复制、Multiverse 或直接复制世界文件夹创建。

> 大厅与竞技场**必须是不同世界** —— 插件据此区分保护规则与游戏逻辑。

#### 关于 `server.properties` 的 `level-name`

`level-name` 指定**服务器根目录下哪个文件夹被加载为主世界**（默认 `world`）。名字不匹配时服务器会新建一个世界，而不是加载你想要的那个。

Paper 启动时**只自动加载这一个世界**，其他世界即使文件夹存在也不会加载。

**本插件会自动加载缺少的世界**：只要世界文件夹（含 `level.dat`）存在于服务器根目录，插件启动时会用 `Bukkit.createWorld` 加载它（与 BedWars1058 的做法一致）。因此：

- **推荐**：把 `level-name` 设为你的**大厅世界名**，玩家进服直接落在大厅，无传送闪烁
  ```properties
  level-name=lobby
  ```
- **也可以不改**：插件会加载 `arenas.yml` 里引用的竞技场/大厅世界
- **大厅兜底**：若 `lobby.world` 留空或指向不存在的世界，插件自动回退到服务器主世界（`level-name` 世界），保证开箱可用

### 阶段 2：配置竞技场

安装插件后，编辑 `plugins/HilltopVillage/arenas.yml`：

```yaml
default-arena: arena1

lobby:
  world: world          # 大厅世界名
  spawn:                # 大厅出生点（玩家进服落点）
    x: 0.5
    y: 100.0
    z: 0.5
    yaw: 0.0
    pitch: 0.0

arenas:
  arena1:
    display-name: "&6&l天空堡垒"
    enabled: true
    world: hilltop_arena1        # 竞技场世界（与大厅不同）
    waiting-location:            # 等待区（玩家加入后、开局前的落点）
      x: 100.5
      y: 80.0
      z: 100.5
    core:                        # 核心位置（玩家重生点）
      x: 100.0
      y: 64.0
      z: 100.0
    spawn-points: []             # 生成点（用部署向导填，见阶段 3）
    game:
      min-players: 2
      max-players: 6
      victory-waves: 20
      wave-interval-seconds: 30
```

> 生效方式：重启服务器，或 `/hilltop reloadconfig` 后重启竞技场。

### 阶段 3：运行部署向导

进入**竞技场世界**，执行：

```
/hilltop setup
```

| 步骤 | 内容 | 操作 |
|------|------|------|
| 1/8 确认世界 | 显示当前所在世界名 | 点击 [确认] |
| 2/8 设置核心 | 站在村庄核心位置（玩家重生点） | 点击 [设为核心] |
| 3/8 生成点管理 | 怪物的出生位置（**必须至少 1 个**） | 走到每个生成点位置，点击聊天栏 [添加当前位置]；点 [打开生成点管理] 查看/删除；在 GUI 中点 [完成] |
| 4/8 节点管理 | 能量节点（重生锚）的注册 | 先在地图上**放置重生锚方块**，走到旁边点击 [添加当前节点]；点 [完成] |
| 5/8 村民放置 | 待保护村民的位置（**全部阵亡 = 游戏失败**，**至少 1 个**） | 走到目标位置点击 [添加村民]，或点 [村民管理] 打开 GUI 查看/删除；完成后点 [完成] |
| 6/8 商店位置 | 村民商店 NPC 的摆放位置（**必填**） | 站在目标位置点击 [设置商店位置] |
| 7/8 配置预览 | 总览核心/生成点/节点/村民/人数/波次 | GUI 中点 [确认保存] |
| 8/8 部署完成 | 显示启动入口 | 点击聊天栏的 /hilltop start |

> **强制项**：生成点、村民、商店位置三者缺一不可。缺少任意一项时点 [确认保存] 会被拒绝，并自动跳回对应步骤补齐。

**三个关键概念**

| 概念 | 作用 | 被摧毁的后果 |
|------|------|-------------|
| **核心**（Core） | 玩家的**重生点**，本身无血量 | 无（仅作为复活坐标） |
| **能量节点**（重生锚） | 真正的"血条"：为附近玩家提供抗性+回血 Buff，可被怪物攻击、可被汁液修复 | **全部被摧毁后，玩家阵亡将无法复活** |
| **村民** | 需要保护的村民，数量取决于你放了多少个 | **全部阵亡即游戏失败** |

### 阶段 4：调整游戏参数

```
/hilltop config
```

可视化 GUI 调整人数、波次、怪物属性、物品参数、节点与村民血量等。
修改后点 [保存并应用配置] —— **新配置在下一局开始时生效**。

也可直接编辑 `plugins/HilltopVillage/game-settings.yml` 后执行 `/hilltop reloadconfig`。

### 竞技场维护（启用 / 禁用）

竞技场采用 **BedWars1058 式的「启用队列」** 模型，可以单独关闭某个竞技场做维护，不影响其他竞技场：

```
/hilltop disable <竞技场>    # 关闭竞技场（维护）
/hilltop enable  <竞技场>    # 重新启用（加入启用队列）
```

| 命令 | 行为 |
|------|------|
| `/hilltop disable` | 踢出玩家 → 从可加入列表移除 → 状态变为「已禁用」。**对局进行中会被拒绝**（请等本局结束） |
| `/hilltop enable` | 加入**启用队列** → 加载世界 → 初始化 → 状态变为「就绪」，之后才可加入 |

**启用队列是串行处理的** —— 一次只加载一个竞技场，加载完成后自动处理下一个，避免一次性加载多个世界卡住服务器（与 BedWars1058 的 `enableQueue` 一致）。

> **禁用后立即启用会有几秒等待**：`/hilltop disable` 只是停止对局，该竞技场上一局遗留的延迟重置与地图回滚仍需跑完。为避免旧实例的重置清掉新一局，插件会把这个竞技场放入**隔离窗口**（默认 `arenas.yml` 的 `settings.arena-reset-delay` + 2 秒；若禁用时正处于结算阶段，还会加上 `defeat-countdown-seconds`）。窗口结束前执行 `/hilltop enable` **不会失败**，但会排在队列里自动重试，控制台会打印一次提示。

### 竞技场管理 GUI（`/hilltop manage`）

管理员执行 `/hilltop manage` 打开一个 54 格菜单，**所有已配置的竞技场都会出现在列表里** —— 包括 `enabled: false`、世界文件夹缺失、以及从未部署过的场次（未部署的会标注缺少核心坐标，且「启用」按钮会被拒绝，需先走 `/hilltop setup`）。

| 视图 | 能做什么 |
|------|---------|
| **列表** | 查看全部竞技场，左键进入详情 |
| **详情** | 状态 / 世界 / 人数 / 波次 / 部署计数 / 商店位置 / 参数摘要；启用、禁用、传送核心、进入各列表 |
| **生成点 / 节点 / 村民列表** | 分页 45 条一页；**左键传送**过去、**右键删除**单条、**Shift+左键**用粒子预览位置 |
| **游戏参数** | 编辑该竞技场的 `min-players` / `max-players` / `victory-waves` / `wave-interval-seconds`，左键后在聊天栏输入新值 |
| **恢复初始状态** | 清空该竞技场的全部部署数据 + 游戏参数 + 强制回滚地图方块，之后可重新部署 |

> **「恢复初始状态」会做什么**：删除该竞技场的核心坐标、全部生成点、全部村民、全部能量节点、商店 NPC（位置与实体）、per-arena 游戏参数与半径偏移，并强制回滚该世界的地图方块。**保留**世界、显示名与 `enabled` 开关，因此重置后该竞技场仍留在列表里显示为「未部署」。操作前有二次确认页（列出完整清除清单与实际在场人数），并有二次取消按钮。**对局进行中会被拒绝**，需先 `/hilltop stop <竞技场>`。因核心坐标被清空，重置后该竞技场不会自动重新启用，需重新部署后再 `/hilltop enable`。

> **参数编辑无需重启**：通过管理 GUI 修改的游戏参数会立即写入 `arenas.yml` 并推送到运行中的竞技场 —— 人数从下一局起生效，波次间隔与通关波数从下一个休整期起生效。**手动编辑 `arenas.yml` 仍然需要重启服务器**（与其它竞技场参数一致）。对局进行中（含结算倒计时）会被拒绝，避免中途改动人数触发「人数不足判负」。

> **删除单条坐标为「过滤后序号」**：坐标损坏的条目在读取时会被跳过，因此 GUI 上的序号可能与 `arenas.yml` 的原始行号不一致。请始终在 GUI 里删除，不要照着 GUI 的序号去改文件。

> **一个世界只能对应一个竞技场**：插件在加载 `arenas.yml` 时会校验世界唯一性，重复世界的定义会被拒绝加载并打印明确警告（世界名不区分大小写）。这条约束是地图回滚正确性的前提 —— 回滚日志与恢复任务都以世界名为键，两个竞技场共用一个世界会导致一方结束时回滚掉另一方正在进行中的对局。

> **人数下限（`min-players`）以每个竞技场自己的配置为准**：`arenas.yml` 的 `game.min-players` 是该竞技场的权威值（例如把某个竞技场配成 `1` 即可单人开局）；未显式配置的竞技场才继承 `game-settings.yml` 的全局 `min-players`。注意：配置 GUI 里的 `min-players` 属于**全局默认值**，对已在 `arenas.yml` 写明的竞技场不生效。修改方式有两种 —— 用 `/hilltop manage` 的参数页（**立即生效**，人数下一局生效），或直接编辑 `arenas.yml` 后**重启服务器**。

> **商店 NPC 由插件统一管理**：大厅与各竞技场的商店村民由部署向导生成、由服务器启停负责清理。**每局结束的地图回滚不会触碰任何 NPC**，商店在局间始终可用；只有 `/hilltop manage` 的「恢复初始状态」会清掉**该竞技场所在世界**的商店 NPC（位置与实体），不会影响大厅与其它竞技场。

**竞技场生命周期状态**（`/hilltop arenas` 可查看）：

| 状态 | 含义 |
|------|------|
| **已禁用** | `enabled: false`，或被 `/hilltop disable` 关闭 |
| **排队启用中** | 已在启用队列中等待 |
| **加载中...** | 正在加载世界 / 初始化 |
| **就绪** | 可以加入、可以开局 |
| **未部署** | 世界在，但还没配置生成点（运行 `/hilltop setup`） |
| **地图缺失** | 配置里写了，但世界存档不存在 |

> 只有「就绪」的竞技场才可能自动开局 —— 未部署/已禁用的竞技场即使有人加入也不会启动游戏。

### 阶段 5：验证部署

| 检查项 | 命令 | 期望结果 |
|--------|------|---------|
| 竞技场已加载 | `/hilltop list` | 列出你的竞技场 |
| 生成点已配置 | `/hilltop deploy listspawns` | 至少 1 个生成点 |
| 节点已注册 | 部署菜单 `/hilltop deploy` | 已知节点数 > 0 |
| 村民已注册 | `/hilltop deploy listvillagers` | 至少 1 个村民 |
| 整体状态 | `/hilltop status` | 显示波次/玩家/怪物/节点 |

> 若生成点为空，`/hilltop start` 会拒绝启动并提示先完成部署。

### 阶段 6：开一局

```
/hilltop join      # 玩家加入（或 /hilltop gui 打开选择菜单）
/hilltop start     # 管理员开局（需满足 min-players）
```

**手动命令速查（熟手）**

```
/hilltop deploy setcore        # 站在核心位置执行
/hilltop deploy addspawn       # 站在生成点位置执行（可多次）
/hilltop deploy listspawns     # 查看已设生成点
/hilltop deploy showparticles  # 粒子效果确认位置
/hilltop deploy reloadnodes    # 重新加载节点配置
/hilltop deploy addvillager    # 站在村民位置执行（可多次）
/hilltop deploy listvillagers  # 查看已登记村民
```

## 游戏流程

### 一局是怎么跑起来的

```
① 玩家进服
     传送至【大厅世界】出生点，获得大厅保护（不可破坏/受伤/饥饿）

② 玩家加入竞技场
   /hilltop join  或  /hilltop gui 选图
     → 背包/经验/血量被保存（落盘），清空背包准备战斗
     → 传送至竞技场【等待区】
     → 侧边栏记分板出现（波次 / 存活 / 节点 / 村民 / 个人击杀）
     → 人数达到 min-players 后自动开始倒计时

③ 开局
     倒计时归零 → 玩家传送至【生成点】、切换生存模式
     → 所有能量节点按配置血量激活，为附近玩家提供 Buff
     → 按配置生成待保护村民
     → 第一波怪物来袭

④ 波次循环
     怪物从生成点出生  →  附近有玩家就打玩家，否则推进打【节点】
     清空本波所有怪物  →  进入休整期（默认 30 秒）
                        →  期间阵亡玩家会在【核心】复活（需有活跃节点）
     休整结束  →  下一波刷新

⑤ 结束
   胜利：撑过全部波次（默认 20 波）
   失败：村民全部阵亡  或  节点全毁 + 全员阵亡
        ↓
   玩家留在场内看【本局战报】→ 倒计时（默认 20 秒）→ 传送回大厅
   → 背包/经验/血量原样归还 → 地图改动回滚 → 竞技场重置等待下一局
```

### 玩家要做什么

**目标**：撑过全部波次，同时保护村民不被打光。

| 手段 | 获取方式 | 效果 |
|------|---------|------|
| **神圣重锤** | `/hilltop hammer` 或商店购买 | 右键腾空蓄力，落地猛击造成三档范围伤害（跳得越高伤害越高） |
| **烈焰蛋** | `/hilltop fireball` 或商店购买 | 右键投掷，命中爆炸造成范围伤害 + 击退 + 点燃 |
| **世界树汁液** | 击杀下界恶魂 / 商店购买 | 右键失活的能量节点修复它 |
| **节点 Buff** | 站在活跃节点附近 | 抗性提升 + 生命恢复（节点 = 移动泉水） |

**资源循环**：击杀怪物 → 掉落能量水晶 → 右键商店村民购买道具 → 修复节点 / 补充弹药 → 继续防守。

### 怪物类型

| 怪物 | 行为 |
|------|------|
| **普通怪物**（僵尸等） | 附近有玩家时优先攻击玩家，否则推进攻击**能量节点** |
| **自爆甲虫** | 高速冲向最近节点并自爆（优先保护节点！） |
| **钩爪猎手** | 远程钩爪拖拽玩家 + 失明（小心被传送） |
| **飞行抛投者** | 飞到玩家上空空投僵尸群 |

### 死亡与复活规则

| 情况 | 结果 |
|------|------|
| 阵亡（尚有活跃节点） | 进入旁观，**等待下一波刷新**时在**核心**复活（无倒计时、无无敌时间） |
| 阵亡（节点已全部被摧毁） | 进入旁观，**无法复活** —— 必须等队友用汁液修复某个节点，下一波才能复活 |
| 对局中掉线 | 保留 **60 秒**（可配）重连窗口；窗口内重连直接回到战斗 |
| 重连超时 | 移出竞技场，物品快照保留在磁盘，下次登录自动归还 |

### 记分板

进入竞技场后自动显示侧边栏：

```
       村民守卫战
  波次: 7/20
  存活: 4
  等待复活: 1
  能量节点: 3/5
  村民: 6/8
  你的击杀: 42
  你的阵亡: 2
```

可在 `game-settings.yml` 的 `scoreboard.enabled` 关闭。

### 统计与排行榜

每局结束后自动累计到 `plugins/HilltopVillage/player-stats.yml`：

```
/hilltop stats            # 查看自己的统计
/hilltop stats Steve      # 查看他人统计
/hilltop top kills        # 击杀排行榜（前 10）
/hilltop top wins         # 胜场排行榜
```

排行榜类型：`kills` · `wins` · `games` · `best-wave` · `nodes` · `deaths`

> 中途离开只累计击杀/阵亡/修复节点数，**不计胜负**。

### 地图恢复

对局中被破坏或放置的方块会在竞技场重置时**自动回滚**，地图不会因爆炸和玩家建造逐局劣化。

- 记录范围：**仅竞技场世界**且**对局进行中**的改动（管理员部署时的建造不受影响）
- 记录来源：方块破坏、方块放置、实体爆炸、方块爆炸、自然变化（冰/雪融化）、**水/熔岩的倾倒与扩散**
- 记录保真度：保存完整方块数据（`BlockData`，含朝向、上下半部、含水等）与方块实体数据（容器、告示牌等 `TileState`），不是只记材质
- 回滚分批执行（每 tick 最多 200 方块），不触发连锁物理更新；回滚**完整成功前竞技场不会开放下一局**，失败的方块会保留快照以便重试
- 可在 `game-settings.yml` 的 `map-restore.enabled` 关闭

### 物品保护

进入竞技场时背包/经验/血量被保存，离开时原样归还。快照**落盘存储**，即使服务器崩溃，重启后玩家登录也会自动归还。

## 大厅

玩家进服后统一在大厅等待，通过 `/hilltop join` 或 `/hilltop gui` 主动加入竞技场。

- **大厅保护**：破坏/放置方块、受伤、饥饿、天气、掉落物、容器交互在**大厅世界**内全部被阻止
- **建造模式**：管理员执行 `/hilltop build` 可临时解除大厅保护，用于整修大厅（再次执行关闭）
- **虚空保护**：大厅内掉出世界底部会自动传送回大厅出生点
- 大厅世界与大堂出生点在 `arenas.yml` 的 `lobby.world` / `lobby.spawn` 配置

> 击杀怪物捡起能量水晶，右键商店村民打开商店，可购买重锤/烈焰蛋/世界树汁液补充弹药与修复材料（详见"商店系统"章节）。

## 命令

| 命令 | 说明 |
|------|------|
| `/hilltop join [竞技场]` | 加入竞技场（不填则自动匹配可用竞技场） |
| `/hilltop leave` | 退出当前竞技场 |
| `/hilltop lobby` | 返回大厅 |
| `/hilltop gui` | 打开竞技场选择菜单（带状态与人数） |
| `/hilltop status [竞技场]` | 查看游戏状态（波次/玩家/怪物/节点/等待复活） |
| `/hilltop stats [玩家名]` | 查看自己或他人的统计 |
| `/hilltop top <类型>` | 查看排行榜（kills/wins/games/best-wave/nodes/deaths） |
| `/hilltop hammer` | 获取神圣重锤 |
| `/hilltop fireball` | 获取烈焰蛋 |
| `/hilltop lang <en\|zh>` | 切换语言 |
| `/hilltop start [竞技场]` | 启动游戏（管理员） |
| `/hilltop stop [竞技场]` | 强制停止游戏（管理员） |
| `/hilltop help` | 查看命令帮助 |
| `/hilltop list` | 列出所有竞技场 |
| `/hilltop arenas` | 查看详细竞技场状态与故障原因（管理员） |
| `/hilltop manage` | 打开竞技场管理 GUI：查看任意竞技场、删除单个节点/生成点/村民、编辑游戏参数、恢复初始状态（管理员） |
| `/hilltop enable [竞技场]` | 将竞技场加入启用队列（管理员） |
| `/hilltop disable [竞技场]` | 禁用竞技场用于维护（管理员） |
| `/hilltop deploy` | 打开部署菜单（管理员） |
| `/hilltop setup` | 8 步引导式部署向导（管理员） |
| `/hilltop config` | 打开配置中心 GUI（管理员） |
| `/hilltop reloadconfig` | 重载游戏配置文件（管理员） |
| `/hilltop shop reload` | 重载村民商店配置（管理员） |
| `/hilltop build` | 切换大厅建造模式（管理员，用于整修大厅） |
| `/hilltop tps` | TPS/MSPT 诊断（管理员） |

### 部署子命令 (`/hilltop deploy`)

| 命令 | 说明 |
|------|------|
| `help` | 查看命令帮助 |
| `setcore` | 将当前位置设为游戏核心 |
| `addspawn` | 添加当前位置为怪物生成点 |
| `listspawns` | 列出所有已设置的生成点 |
| `delspawn <id>` | 删除指定编号的生成点 |
| `clearspawns` | 清除所有生成点（需 `confirmclear` 确认） |
| `reloadnodes` | 重新扫描能量节点 |
| `showparticles` | 展示所有生成点粒子效果（30 秒） |
| `addvillager` | 将当前位置登记为村民位置（可多次，村民全灭 = 失败） |
| `listvillagers` | 列出所有已登记的村民 |
| `delvillager <id>` | 删除指定编号的村民 |
| `clearvillagers` | 清除所有村民 |

### 部署向导子命令 (`/hilltop setup`)

向导进行中可用（一般通过聊天栏可点击按钮触发，无需手动输入）：

| 命令 | 说明 |
|------|------|
| `confirm-world` | 确认游戏世界（步骤 1） |
| `confirm-core` | 将当前位置设为村庄核心（步骤 2） |
| `addspawn` | 将当前位置添加为怪物生成点（步骤 3，反馈带序号） |
| `spawngui` | 打开生成点管理 GUI（步骤 3，可随时重开） |
| `spawns-done` | 完成生成点设置（步骤 3，仅向后兼容保留；请使用 GUI 内 [完成]） |
| `confirm-nodes` | 确认能量节点扫描结果（步骤 4） |
| `rescan` | 重新扫描能量节点并重发步骤 4 说明（步骤 4） |
| `villagers-done` | 完成村民放置，进入商店步骤（步骤 5） |
| `confirm-shop` | 将当前位置设为商店村民位置并立即生成 NPC（步骤 6） |
| `skip-shop` | 跳过商店部署，本图不生成商店村民（步骤 6） |

> 步骤 3 采用聊天栏优先交互：先走到生成点位置，点击聊天栏的 [添加当前位置] 按钮即可记录（反馈为"生成点 #N 已添加"），[打开生成点管理] 可随时查看/删除已添加的生成点（54 格 GUI，每页 45 个）。完成入口在管理 GUI 内的 [完成] 按钮（slot 51），聊天栏不放 [完成] 按钮以免误触。
> 步骤 4 说明能量节点 = 世界中放置的重生锚（自动识别），放置方块后可点 [重新扫描] 重新统计。
> 步骤 5 村民为**失败条件**：全部阵亡即游戏失败，请至少放置 1 个。走位点击 [添加村民] 记录，[查看村民列表] 核对，[完成] 进入下一步。
> 步骤 6 商店位置为可选：跳过则本图不生成商店村民。商店村民位置按竞技场持久化在 `arenas.yml` 的 `arenas.<id>.shop-npc` 节点，服务器重启后自动恢复；插件启动时会自动清理残留的重复商店村民实体。
> `/hilltop start` 前置校验：游戏已在进行中会提示先 stop；人数不足 min-players 会明确提示当前/所需人数，不再静默失败。

### 配置子命令 (`/hilltop config`)

| 命令 | 说明 |
|------|------|
| `nodes repairitem <材料>` | 设置节点修复物品 |

## 权限

| 权限节点 | 默认 | 说明 |
|------|------|------|
| `hilltopvillage.player` | true | 普通玩家基础权限 |
| `hilltopvillage.admin` | OP | 管理员权限（包含所有子权限） |
| `hilltopvillage.config.admin` | OP | 配置中心 GUI 访问权限 |
| `hilltopvillage.config.rules` | false | 游戏规则配置权限 |
| `hilltopvillage.config.waves` | false | 波次配置权限 |
| `hilltopvillage.config.monsters` | false | 怪物属性配置权限 |
| `hilltopvillage.config.items` | false | 重锤/物品配置权限 |
| `hilltopvillage.config.nodes` | false | 能量节点配置权限 |

## 安装

1. 下载最新版本的 JAR 文件，放入服务器的 `plugins/` 目录
2. 重启服务器（或 `/plugman load HilltopVillage`）
3. 按 **[部署指南](#部署指南服主必读)** 的 6 个阶段完成部署

> 插件要求：**Paper 1.21.4+** / **Java 21**。首次启动会自动生成 `config.yml`、`arenas.yml`、`game-settings.yml`、`messages.yml`、`shop.yml`、`nodes.yml`、`spawnpoints.yml`。

### 生成的文件一览

| 文件 | 作用 |
|------|------|
| `config.yml` | 语言等基础设置 |
| `arenas.yml` | **多竞技场定义 + 大厅设置**（核心配置） |
| `game-settings.yml` | 所有游戏参数（可用 `/hilltop config` GUI 编辑） |
| `messages.yml` | 多语言文本 |
| `shop.yml` | 村民商店商品与价格 |
| `nodes.yml` | 能量节点坐标（部署向导写入） |
| `spawnpoints.yml` | 旧版商店村民位置的迁移来源（已不再写入；新位置见 `arenas.yml` 的 `arenas.<id>.shop-npc`） |
| `player-goods.yml` | 玩家物品快照（崩溃保护，自动维护） |
| `player-stats.yml` | 玩家统计（自动维护） |

## 配置

### arenas.yml —— 竞技场与大厅（核心配置）

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
    spawn-points: []           # 怪物生成点（部署向导写入）
    villagers: []              # 村民位置（部署向导写入）
    game:
      min-players: 2
      max-players: 6
      victory-waves: 20
      wave-interval-seconds: 30
```

### game-settings.yml —— 游戏参数

可用 `/hilltop config` 的 GUI 编辑，或直接改文件后 `/hilltop reloadconfig`。

```yaml
# 基础
min-players: 2                   # 开始游戏所需最少玩家（仅对「未在 arenas.yml 显式配置」的竞技场生效，见下方说明）
max-players: 6                   # 单局最大玩家数
victory-waves: 20                # 胜利所需波次
wave-interval-seconds: 30        # 波次之间休整时间

# 阶段 1-3 新增
defeat-countdown-seconds: 20     # 失败后显示战报到传送回大厅的倒计时
villager-health: 20.0            # 村民最大生命值（全部阵亡 = 失败）
rejoin-timeout-seconds: 60       # 掉线重连窗口，0 = 关闭重连

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
```

### config.yml

内部状态文件，**不含任何游戏参数**（游戏参数见 `game-settings.yml`），只保存语言与部署向导进度，插件自动维护。

```yaml
language: "zh"    # zh / en
```

完整说明请参考 `plugins/HilltopVillage/` 下的各配置文件（文件内均带中文注释）。

## 特殊怪物

| 怪物 | 实体类型 | 技能 |
|------|------|------|
| **自爆甲虫** | 洞穴蜘蛛 | 冲向能量节点自爆，造成大量节点伤害 |
| **钩爪猎手** | 骷髅 | 远程射出钩爪，将玩家传送到身边并施加失明+缓慢 |
| **飞行抛投者** | 幻翼 | 飞至玩家上方空投僵尸群 |

> 上表为默认实体类型，可在 `monsters-config.yml` 中通过 `entity-type` 修改（如把 `explode-beetle` 改为 `ZOMBIE`），改后执行 `/hilltop reloadconfig` 生效。

## 依赖

- Paper 1.19+
- Java 17+
- ItemsAdder（可选，用于自定义物品/怪物模型）

## 构建

```bash
mvn clean package
```

JAR 文件将生成在 `target/` 目录。

## 许可证

本项目采用 [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/)。

任何使用、修改或分发，都必须保留项目根目录下的 `LICENSE` 和 `NOTICE` 文件，并确保其内容完整。

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
 </picture>
</a>