🌐 **中文** · [**English**](README-EN.md)

<div align="center">

# Hilltop Village

> *「你守的不是塔，是八个不会跑的村民。」*

> ⚠️ **这是一个初中生用AI做的插件，有任何问题建议欢迎提交Issues，如果您喜欢这个项目，就star一下吧！**

[![License](https://img.shields.io/badge/License-PolyForm%20Noncommercial-blue)](LICENSE) [![Paper](https://img.shields.io/badge/Paper-1.21.4%2B-brightgreen)](#装上就能用) [![Java](https://img.shields.io/badge/Java-21-blue)](#装上就能用) [![Deps](https://img.shields.io/badge/Deps-zero%20external%20deps-c2410c)](#数字都能自己算) [![Version](https://img.shields.io/badge/Version-1.1.0--SNAPSHOT--fix2-lightgrey)](https://github.com/sqkl520/Minecraft-server-plugin-Hilltop-Defense/releases)

**把一张空地图变成能守二十波村庄的塔防战场，一局打完，被改动的方块逐格还原回第一格。**

<sub>只依赖 Paper API —— 没有编译期第三方库，plugin.yml 的 depend/softdepend 都是空列表；装了 ItemsAdder 可挂自定义模型，不装自动退回原版外观</sub>

「这地图还能撑几局？」——每张被爆炸炸过、被玩家改建过的竞技场图，服主迟早要问这一句。

原版 Minecraft 不会替你把地图还原回去：一局二十波的自爆甲虫和烈焰蛋之后，
被破坏的方块留在原地，地图一局比一局难看，最后只能整张重建。
这个插件存在的全部理由：把竞技场的每一次方块改动逐格记下来，结束时按快照回滚
（每 tick 最多 200 格，单世界记录上限 100 万格），外加一条不打友的规则——重锤与烈焰蛋对玩家零伤害。

```text
plugins/  ← 把 jar 丢进去，重启服务器
/hilltop setup   ← 站在竞技场世界里，跟着 8 步向导走
/hilltop start   ← 开局
```

跨版本要求：Paper 1.21.4+ / Java 21。编译依赖与测试都只挂在 Paper API 上（`grep -A4 "<dependency>" pom.xml` 可见唯一非 test 依赖就是 paper-api），Spigot 未实测——别按「应该能跑」来部署。

[看一局怎么跑](#一局从头到尾长什么样) · [30 秒上手](#装上就能用) · [凭什么信](#数字都能自己算) · [它不做什么](#它不做的三件事)

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Hilltop-Defense&type=date&legend=top-left" />
 </picture>
</a>

<sub>真实曲线，来自 star-history.com，图上仓库名就是本仓库</sub>

</div>

---

## 一局从头到尾长什么样

没有 GIF 可看，那就把一局的每个阶段原样列出来——从进服到弹回大厅，① 到 ⑤ 全流程。

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
     倒计时归零 → 玩家传送至【玩家出生点】、切换生存模式、发放开局套装（starter-kit）
     → 所有能量节点按配置血量激活，为附近玩家提供 Buff → 按配置生成待保护村民
     → 第一波怪物来袭
④ 波次循环
     怪物从【怪物刷怪点】出生  →  附近有玩家就打玩家，否则推进打【节点】
     每种怪物的刷新只数 = 配置 `count`（每人只数）× 在场人数（如第 1 波甲虫 6 / 猎手 2，
     2 人局实际刷出 12 只甲虫 + 4 只猎手），受 `total-mobs` 上限与全局/人均上限裁剪
     清空本波所有怪物  →  进入休整期（默认 30 秒）→ 期间阵亡玩家会在【核心】复活（需有活跃节点）
     休整结束  →  下一波刷新（人数不足则直接判负，不开空波）
⑤ 结束
   胜利：撑过全部波次（默认 20 波）
   失败：村民全部阵亡  或  节点全毁 + 全员阵亡  或  休整期人数不足
        （无存活玩家 + 无活跃节点时每 3 秒复检一次，3 秒内判负）
        ↓
   玩家留在场内看【本局战报】→ 倒计时（默认 20 秒）→ 传送回大厅
   → 背包/经验/血量原样归还 → 地图改动回滚 → 竞技场重置等待下一局
```

「每人只数 × 在场人数」这条乘法不是文案，是配置里读出来的：`count` 写在 `game-settings.yml` 的 `waves` 段，人数由 `min-players` / `max-players` 决定。复算：`grep -nE "^(min-players|max-players|victory-waves|wave-interval-seconds):" src/main/resources/game-settings.yml` → 20 / 21 / 23 / 25 行，值 2 / 6 / 20 / 30。

### 示例输出

进竞技场后侧边栏长这样，一屏读完波次、人数、节点余量、村民余量和你自己的战绩：

```
       村民守卫战
  波次: 7/20
  存活: 4
  等待复活: 1
  能量节点: 3/5
  村民: 6/8
  你的击杀: 42
  你的阵亡: 2
  本局水晶: 12
```

不想要就在 `game-settings.yml` 的 `scoreboard.enabled` 关掉，整块消失，不影响对局逻辑。<sub>开头那句标语里的「八个」和样例里的 `村民: 6/8` 都是典型规模：村民数量由你在向导第 5 步登记几个决定，节点数量同理。</sub>每局结束战绩累计进 `player-stats.yml`：`/hilltop stats [玩家名]` 看个人——战绩面板含**胜率**与 **K/D**（`StatsManager.getWinRate()`、i18n 键 `stats.win-loss` / `stats.kills-deaths`），`/hilltop top <类型>` 看七种维度排行榜（`kills` · `wins` · `games` · `best-wave` · `nodes` · `deaths` · `crystals`），各取**前 10 名**（`stats.top(type, 10)`）。中途 `/hilltop leave` 走的人只累计击杀/阵亡/修复节点数，**不计胜负**。

## 装上就能用

1. 下载最新 JAR，放进服务端的 `plugins/` 目录
2. 重启服务器（开发期也可 `/plugman load HilltopVillage`）
3. 按下面「从零到开一局」的 6 个阶段走完部署——这一步不能跳，插件不知道你的地图在哪

要求：**Paper 1.21.4+ / Java 21**。Jar 里带着 8 个 yml 模板（`ls src/main/resources/*.yml | wc -l` → 8，合计 2978 行），首次启动会在 `plugins/HilltopVillage/` 生成其中 7 个可编辑配置。

### 随 jar 发布 8 个模板，首次启动生成其中 7 个

8 个模板里 `plugin.yml` 打进 jar 供 Bukkit 解包用、不落进插件目录，其余 7 个首次启动写到 `plugins/HilltopVillage/`。带不带中文注释分两类——复算：`grep -c '#.*[一-鿿]' src/main/resources/*.yml` → 六个文件有中文注释行，`plugin.yml` 与 `spawnpoints.yml` 为 0：

| 文件 | 管什么 | 落进插件目录 |
|---|---|---|
| `arenas.yml` | **多竞技场定义 + 大厅设置**（世界、核心、两套坐标、村民、商店、per-arena 参数） | 是，带中文注释 |
| `game-settings.yml` | 全部游戏规则（21 个顶层键），可用 `/hilltop config` GUI 编辑 | 是，带中文注释 |
| `monsters-config.yml` | 3 种特化怪的实体类型与盔甲武器 | 是，带中文注释 |
| `shop.yml` | 村民商店的 16 件商品、价格与槽位 | 是，带中文注释 |
| `messages.yml` | 多语言文案，en / zh 两段 | 是，带中文注释 |
| `config.yml` | 纯内部状态文件，只剩 `language` 与向导进度两项，**不含任何游戏参数** | 是，带中文注释 |
| `spawnpoints.yml` | 旧版**商店村民**位置的迁移来源（已不再写入，新位置见 `arenas.yml` 的 `arenas.<id>.shop-npc`） | 是，纯机读模板（无中文注释） |
| `plugin.yml` | 命令声明与 7 个权限节点 | 否，打进 jar（纯机读模板，无中文注释） |

`nodes.yml`（节点坐标）、`player-goods.yml`（物品快照）、`player-stats.yml`（战绩）由插件运行期自己维护，第一次用到才创建。

> ⚠️ `config.yml` 从 1.0.9 起已瘦身为纯状态文件——想改游戏规则请改 `game-settings.yml` 或用 `/hilltop config` 的界面，别在它里面找参数。

### 只吃 Paper 和 Java 21 —— 零编译期第三方库

`plugin.yml` 里 `depend` 与 `softdepend` **都是空列表**——连软依赖都没声明。pom 的依赖只有两条：`paper-api`（provided）和 JUnit（test scope），没有一条会进产物。ItemsAdder 是**可选**而非依赖：代码用反射调它的自定义模型 API，没装就 warning 一句、退回原版物品——「零外部依赖」的准确边界是零编译期第三方库，装不装它都能跑。

复算：`grep -E "^(depend|softdepend):" src/main/resources/plugin.yml` → 两个空列表；`grep -rniE "okhttp|httpclient|Gson|Jackson" src/main/java --include=*.java | wc -l` → 0。

## 装完先自检：7 条命令各自证明什么

部署最容易出的错不是命令敲错，是「看着配好了，其实少一个点」。这 7 条按顺序跑一遍，每条只证明一个部件到位——任何一条结果不对，先别 `/hilltop start`。

| 检查项 | 命令 | 期望结果 |
|---|---|---|
| 竞技场已加载 | `/hilltop list` | 列出你的竞技场 |
| 玩家出生点已配置 | `/hilltop deploy listspawns` | 至少 1 个 |
| 怪物刷怪点已配置 | `/hilltop deploy listmobspawns` | 至少 1 个 |
| 节点已注册 | `/hilltop deploy reloadnodes` 后看回显 | 已知节点数 > 0 |
| 村民已登记 | `/hilltop deploy listvillagers` | 至少 1 个 |
| 商店已设置 | `/hilltop deploy setshop` 后到现场看 | 商店村民已生成；想重来用 `/hilltop deploy clearshop` |
| 整体状态 | `/hilltop status` | 波次 / 玩家 / 怪物 / 节点一屏可读 |

> 注：两套出生/刷怪坐标都为空时，`/hilltop start` 会**拒绝启动**并提示先完成部署——不会静默卡住。每类点各有各的落盘文件（节点 → `nodes.yml`、物品快照 → `player-goods.yml`、战绩 → `player-stats.yml`），所以「少的是哪一个」能单独查出来。

## 玩家要守什么、怎么守

**目标只有一句话**：撑过全部波次，同时别让村民被打光。玩家不放塔、不造建筑——防守手段是玩家自己的身体、走位和手上那几件道具。**资源循环是一条闭环**：击杀怪物 → 掉能量水晶 → 商店换道具与汁液 → 汁液修节点 → 节点继续给 Buff 帮你守村。

| 手段 | 怎么拿到 | 效果 |
|---|---|---|
| **神圣重锤** | 村民商店购买（管理员 `/hilltop hammer`） | 右键腾空蓄力、落地猛击，跳得越高伤害越高，三档范围伤害 + 粒子冲击波 + 余震减速；默认 8 秒冷却、30 次使用上限，耗尽碎裂需回购。跳砸与修节点只在对局进行中可用；模型可挂 ItemsAdder 自定义资源 |
| **烈焰蛋** | 商店购买（管理员 `/hilltop fireball`） | 右键投掷，焰火轨迹、命中爆炸范围伤害 + 击退 + 点燃，冷却可配；炸玩家 0 伤害只弹飞（对怪伤害未被零友伤改造削弱），村民完全豁免且不再触发近炸判定 |
| **世界树汁液** | 击杀下界恶魂 / 商店购买 | 右键失活的能量节点，一次消耗恢复 50 点生命 |
| **节点 Buff** | 站到活跃节点附近 | 抗性提升 I + 生命恢复，节点就是移动泉水 |

**阵亡之后**：进旁观，下一波刷新时在**核心**复活，无倒计时、无无敌时间；对局中想走就 `/hilltop leave`（观战提示里也带可点按钮）。掉线保留 60 秒重连窗口（可配，`rejoin-timeout-seconds: 0` 即关闭），窗口内重连直接回到战斗，超时则移出竞技场、物品快照留在磁盘等下次登录归还。

**零友伤不是口号，是逐技能判过的**：重锤落地伤害、烈焰蛋爆炸，对玩家一律 0 伤害只弹飞；玩家之间的弓、弩、近战挥砍也走同一条互伤门控（`mechanics/FriendlyFireListener.java`，双方同为在场成员才取消）。村民则连环境伤害（活埋/摔落/岩浆/溺水/爆炸/雷击/火/仙人掌）都全部无效——只有怪物能掉村民的血。想让玩家之间打不起来，靠的不是配置开关，是这一条。

**一个必须说清的例外**：自爆甲虫冲节点时的原版实体爆炸**不在零友伤范围内**（`monsters.explode-beetle.entity-explosion` 默认开），它走 `ENTITY_EXPLOSION`、来源是怪物而非玩家，会波及节点附近的玩家。想让爆炸只掉节点不伤人，把该开关关掉，甲虫就退化为纯视觉自爆、只结算节点伤害。这是玩家互伤为零、怪物爆炸仍可能伤到你——两件事，别混。

## 一局里真正在跑的十件事

每条都给「玩家/服主看到什么」和「在哪改」。

| # | 在跑的东西 | 你看到什么 | 在哪改（配置键 / 代码位置） |
|---|---|---|---|
| 1 | **波次进攻** | 怪物逐波来袭、难度递增，波间休整默认 30 秒；主线程每 3 tick 分批放 2 只，刷完才计数 | `victory-waves` / `wave-interval-seconds` / `waves` 段；`core/WaveManager.java` |
| 2 | **待保护村民** | 村民固定在部署位置不会走失，全灭即判负（数量由部署决定） | `villager-health`；`mechanics/VillagerSystem.java` |
| 3 | **能量节点** | 重生锚给附近玩家抗性 + 回血，可被怪打空、可用汁液修 | `nodes.*`（base-health / buff-radius / repair-*）；`mechanics/NodeSystem.java` |
| 4 | **死亡与复活** | 阵亡进旁观，下一波在核心复活；无活跃节点就无法复活 | `rejoin-timeout-seconds`；`core/GameManager.java` |
| 5 | **神圣重锤** | 右键腾空蓄力猛击，三档伤害随高度递增，8 秒冷却 / 30 次上限 | `hammer.*`；`mechanics/HammerListener.java` |
| 6 | **烈焰蛋** | 右键投掷 + 焰火轨迹 + 爆炸击退，玩家 0 伤、村民豁免 | `fireball.*`；`mechanics/FireballListener.java` |
| 7 | **村民商店** | 16 件默认商品分三区，右键商店村民开店，左键买 | `shop.yml`；`shop/ShopManager.java` |
| 8 | **三种特化怪** | 自爆甲虫冲节点、钩爪猎手远程抓人、飞行抛投者空投僵尸 | `monsters-config.yml` + `monsters` 段；`ai/` 包 |
| 9 | **多竞技场并行** | 大厅 + N 个独立世界同时开多局，`/hilltop gui` 选图 | `arenas.yml`；`arena/MultiArenaManager.java` |
| 10 | **记分板与排行榜** | 侧边栏八项实时数据，战绩落盘，`/hilltop top` 七种维度 | `scoreboard.enabled`；`stats/StatsManager.java` |

上面十条里有三条（节点、水晶、村民）互为生死：节点全毁 → 无法复活 → 守不住村民 → 判负。这是设计时故意让它们咬在一起的。难度递增写在配置里，不是硬编码——`waves` 段给第 1 / 5 / 10 波各配了独立档位（怪物总量上限 `total-mobs` = 8 / 14 / 20，composition 也逐档加难），其余波次走 `default` 档（10），改哪一档都只影响那一档。复算：`grep -A2 total-mobs src/main/resources/game-settings.yml`

## 机制：四条硬规则，改哪条都会翻车

不是功能清单，是四条决定输赢的规则：节点是血条、地图能还原、水晶有闭环、三种怪各抢一条战术——正是下面四个小节。每条都能指到配置键或代码行。

### 节点就是村庄的血条，全毁则无人能复活

能量节点用**重生锚**方块，建议放 4-5 个。管理员在地图上放好重生锚，在部署向导第 4 步走到锚旁边点 [添加节点]**手动注册**——**必须至少注册 1 个**：0 节点意味着全员无法复活，最后一个存活玩家阵亡的那一刻即全队判负（判负条件是「无存活玩家 **且** 无活跃节点」同时成立）。它有两层功能，原版那层一点没动：玩家可以照常用荧石充能、把它设为重生点；插件这一层把它当成可被打、可被修、能给 Buff 的实体。

| 规则 | 默认值 | 说明 |
|---|---|---|
| 增益光环半径 | 20 格 | 每个**活跃节点**（生命值 > 0）对范围内玩家持续给抗性提升 I + 生命恢复 |
| 节点血量 | 100 | 怪物普攻和自爆甲虫的自爆都在削它；归零即失活，光环消失 |
| 一次修复量 | 50 生命 / 1 个汁液 | 右键失活节点；汁液默认恶魂之泪（杀下界恶魂或商店购买），材料可改 `nodes.repair-cost-item` 或走 GUI 的能量节点设置 |
| 活跃的定义 | 血量 > 0 | 添加时全部登记为活跃；打空后失活，修好后重新激活 |

想加节点 = 多放重生锚再去第 4 步注册（对局中无法添加）；想删 = 节点管理 GUI 里点对应条目。对局中节点方块不可挖除（防误操作），部署期能正常破坏。多个节点的 Buff 会叠加但有等级上限（`nodes.buff-stack-cap`），不会叠成无敌。复算：`grep -nE "^  (base-health|buff-radius|repair-cost-item|repair-cost-amount|repair-amount-per-cost):" src/main/resources/game-settings.yml` → 124:100.0 / 134:20.0 / 126:GHAST_TEAR / 128:1 / 130:50.0。

### 地图不会一局一局烂掉 —— 但它有 100 万格上限

对局中被破坏或放置的方块，竞技场重置时**逐格自动回滚**。这是整个插件最硬的一条承诺，也是它最诚实的一条限制。

- **记录范围**：仅竞技场世界、仅对局进行中（管理员部署时的建造不受影响）
- **记录来源**：方块破坏、方块放置、实体爆炸、方块爆炸、自然变化（冰/雪融化）、水与熔岩的倾倒扩散
- **保真度**：保存完整 `BlockData`（含朝向、上下半部、含水）与方块实体 `TileState`（容器、告示牌），不是只记材质
- **分批回滚**：每 tick 最多 200 格，不触发连锁物理更新；**回滚完整成功前竞技场不开放下一局**，失败方块保留快照以便重试。这个数能用 JVM 参数调：`-Dhilltop.maprestore.blocksPerTick=400`——调大回滚更快、代价是单 tick 耗时更高；非法值（非数字或 ≤0）回退 200 并各打一条 WARNING，参数缺失/空白则静默按 200
- **上限**：单世界最多记录 100 万格改动（约 140–170 MB）。到顶**停止记录**并在控制台打一条 `SEVERE`，该局结束时的回滚将不完整。为什么要设这条线：服务器堆通常只有 3 GB，500 万条记录约需 700–850 MB，够直接 OOM——停在 100 万条并告警，比整个服务器崩掉好。正常对局几乎碰不到，真要更多就连同堆大小一并调整后自行改大上限
- **可关**：`game-settings.yml` 的 `map-restore.enabled`

```bash
grep -n "MAX_JOURNAL_ENTRIES_PER_WORLD = \|return 200" src/main/java/com/hilltopvillage/mechanics/MapRestoreManager.java
  # 106:private static final int MAX_JOURNAL_ENTRIES_PER_WORLD = 1_000_000;   ← 100 万格上限
  # 81 / 87 / 93:return 200;                                                  ← 每 tick 200 格
```

> ⚠️ 回滚日志是**内存态**的，不落盘。局内正常结束一定能回滚；服务器中途崩溃时这一局的 journal 不恢复。别把「回滚」理解成「断电也不丢」——这条区别写在机制层，不写成效果承诺。

**物品保护是另一回事，它确实落盘**：进竞技场时背包/经验/血量存进 `player-goods.yml`，离开时原样归还；写入用「先写唯一 `.tmp` 再原子 move」，解析失败的文件被改名成 `*.corrupt` 隔离，下次启动不会被坏文件卡死。所以「服务器重启后玩家登录还能拿回背包」这句话只适用于物品快照，不适用于地图方块。

### 水晶怎么来、怎么花

货币是能量水晶，来源和去向都只有一条路，所以经济不会通胀。**掉落**：击杀波次怪物必掉（`shop.currency-drop-chance` 默认 1.0 = 必掉），默认每次 1~2 个，区间由 `currency-drop-min` / `currency-drop-max` 控制；按怪种区分——自爆甲虫 1 个、钩爪猎手 1~2 个、飞行抛投者 2~3 个（越难打的怪越值，`monsters` 段里逐怪 `drop-min` / `drop-max`，写 -1 回退全局、写 0/0 即不掉）。空投僵尸**不掉币**，这是反挂机设计：挂着打空投刷不出经济。结算战报、侧边栏、`/hilltop stats` 与 `/hilltop top crystals` 都会显示本局/历史水晶。

**花**：村民商店默认 16 件商品、5 种 type（`HAMMER` / `FIREBALL` / `REPAIR_ITEM` / `MATERIAL` / `EQUIPMENT`），54 格 GUI 分区摆放——工具消耗品区（重锤 5 / 烈焰蛋×8 3 / 汁液×4 2 / 弓 15 / 箭×8 2）、武器区（铁剑 8 / 钻石剑 25 / 下界合金剑 48）、护甲区（铁套散件各 6 / 钻石套散件各 14，按件卖）。装备全部是**原版白板**，没有附魔。开局另发皮革四件套 + 石剑 + 面包 + 火把（`starter-kit` 段）。走到商店村民旁右键开店，左键图标购买。

**两道防伪**：能量水晶靠物品内的 PDC 标记识别，用铁砧或命名牌把普通绿宝石改成货币名**不会被当成钱**；手工发币请用 `/hilltop shop give <玩家> [数量]`，原版 `/give` 发的绿宝石商店不认。商店 GUI 内所有点击与拖拽一律取消（拿不走商品图标），购买有 250 毫秒冷却防连点重复扣费。

```bash
awk '/^  items:/{f=1;next} f&&/^  [^ ]/{f=0} f&&/^    [a-z_0-9]+:/{c++} END{print c" items"}' src/main/resources/shop.yml   # → 16 items
grep -nE "^  currency-drop-(chance|min|max):" src/main/resources/game-settings.yml   # 159/161/162 行：1.0 / 1 / 2
grep -n "BUY_COOLDOWN_MS" src/main/java/com/hilltopvillage/shop/ShopListener.java    # 35:= 250L
grep -rn "ITEM_KIND_KEY =" src/main/java --include=*.java | wc -l                    # → 3（重锤/烈焰蛋/汁液各一处）
```

**商店村民本身**：无敌（任何伤害无效）、不可推动，只作为商店入口；朝向只取玩家 yaw、pitch 归零（不低头抬头，旧存档里写坏的低头角度在重启恢复时自动纠正），生成后还会水平转身看向 8 格内最近的玩家。清除入口在**对局进行中会被拒绝**——商店 NPC 是插件级单例，清除会当场撤掉场内玩家正在用的商店，请先 `/hilltop stop`。

改完 `shop.yml` 执行 `/hilltop shop reload`，不用重启。商品条目的完整字段（`slot` / `price` / `type` / `amount` / `icon.material` / `name` / `lore` / `unbreakable`）与 YAML 全样例在 [村民商店自定义](docs/guides/shop-customization.md)。

> ⚠️ `shop.yml` 是「文件存在即不释放模板」——服务器上已有旧文件时，新版加的商品（弓、箭、剑、护甲）**不会自动出现**。升级后要么手动补条目，要么备份旧文件后删掉让它重新生成。

### 三种怪各自抢一条战术

四种敌人，各占一条战术位；三种特化怪的实体类型都可以换。

| 怪物 | 默认实体 | 抢哪条战术 | 一句话应对 |
|---|---|---|---|
| 普通怪物（僵尸等） | 原版 | 附近有玩家打玩家，否则推进打**节点** | 别把后背留给节点方向 |
| 自爆甲虫 | 洞穴蜘蛛 | 高速冲向最近节点自爆 | 优先保护它，别让它的中心命中节点 |
| 钩爪猎手 | 骷髅 | 远程钩爪把人拽过去 + 失明缓慢 | 别站低处；平地无高差时它退化为攻节点 |
| 飞行抛投者 | 幻翼 | 飞到玩家上空空投僵尸群 | 单个抛投者整局空投轮数有上限 |

> 注：3 是**特化机制怪**的种数（`grep -cE "^[a-z].*:" src/main/resources/monsters-config.yml` → 3），不是「全部敌人」。空投僵尸和普通波次怪沿用原版实体；`monsters` 段里另有三条 `display-egg`（洞穴蜘蛛/骷髅/幻翼刷怪蛋）作 GUI 展示物，复算：`grep -n "display-egg" src/main/resources/game-settings.yml` → 219/236/251。

甲虫的自爆附带**原版实体爆炸**，默认开（`monsters.explode-beetle.entity-explosion`），此时爆炸除节点伤害外仍会波及附近的玩家；关掉后只结算节点伤害（粒子与音效保留，退化为纯视觉自爆）。单个抛投者的空投轮数上限是 `spawning.airdrop-max-per-dropper`（默认 3，0 = 禁用空投），防止空投失控拖垮服务器。实体类型在 `monsters-config.yml` 改 `entity-type`（例如把 `explode-beetle` 换成 `ZOMBIE`），改后 `/hilltop reloadconfig` 生效。每种怪物还能挂 `itemsadder-id`（ItemsAdder 模型 ID，namespace 形如 `myplugin:boss`）或 `custom-model-data`（原版自定义模型数据，配合资源包）——留空/0 即原版外观。

## 从零到开一局：6 个阶段

首次部署请按顺序执行，每一阶段配一条最容易踩的坑。完整操作、边角规则和踩坑记录都下移到仓库内的指南文件，这里只留骨架。

| 阶段 | 你要做的事 | 唯一要盯的坑 | 详版 |
|---|---|---|---|
| 1 准备世界 | 大厅 + 竞技场**两个不同的世界**，至少 2 个 | `server.properties` 的 `level-name` 指错会让服务器新建一个空世界而不是加载你那张图 | [阶段 1-2](docs/guides/deploy-stages-1-2.md) |
| 2 配置竞技场 | 编辑 `arenas.yml`：世界、核心、等待区、per-arena 参数 | 手动改 `arenas.yml` **需要重启**（reloadconfig 不重读它） | [阶段 1-2](docs/guides/deploy-stages-1-2.md) |
| 2.5 进入竞技场世界 | 自己传过去：`/execute in <世界> run tp @s …` 或 Multiverse | **本插件没有传送进竞技场世界的命令**，而 `setup` / `deploy` 都要求你人已经站在目标世界里 | [阶段 1-2](docs/guides/deploy-stages-1-2.md) |
| 3 跑部署向导 | `/hilltop setup`，8 步走完 | 出生点、刷怪点、村民、商店位置**四者缺一不可** | [部署向导](docs/guides/setup-wizard.md) |
| 4 调整游戏参数 | `/hilltop config` 打开 GUI，或直接改 `game-settings.yml` | GUI 改完点「保存并应用」，**新配置下一局才生效** | 见下方「改参数」一节 |
| 5 验证部署 | 跑那 7 条自检命令 | 两套坐标都为空时 `start` 会被拒绝，不是 bug | 见「装完先自检」 |
| 6 开一局 | `/hilltop join` 或 `/hilltop gui` → 管理员 `/hilltop start` | 人数没到 `min-players` 不开局，且会明确报当前/所需人数 | [命令参考](docs/guides/commands-reference.md) |

大厅与竞技场必须是两个不同世界——插件据此分流保护规则与游戏逻辑。竞技场世界可以用 WorldEdit 复制、Multiverse，或直接复制世界文件夹创建。世界文件夹存在但没被 Paper 加载时，插件启动会自己 `Bukkit.createWorld` 把它拉起来（与 BedWars1058 的做法一致），所以 `level-name` 推荐设成大厅世界名；不改也行，插件会加载 `arenas.yml` 引用的那些世界。第一次传进竞技场后建议先飞一圈确认两件事：这张图确实是你准备的那张、当前是创造模式（后面放重生锚、摆村民都要用）。

### 8 步部署向导逐步在做什么

`/hilltop setup` 站在竞技场世界里执行。向导**按你当前所在的世界**认定要部署的竞技场，不限默认场；哪怕该场还没配核心、没有运行实例，也能直接开始。走完第 8 步后，`enabled: true` 的场会自动进启用队列（`enabled: false` 的场要先把开关拨回 true 并重启，`/hilltop enable` 无法绕过该开关）。

| 步 | 内容 | 你要点什么 |
|---|---|---|
| 1 | 确认世界 | 显示当前世界名，对不上就取消重传 |
| 2 | 设置核心 | 站到玩家重生点位置，点 [设为核心] |
| 3 | 生成点管理 | 玩家出生点 + 怪物刷怪点**两套独立坐标，各至少 1 个**；[打开生成点总览] 里点唯一的 [完成] |
| 4 | 节点注册 | **先在地图上放好重生锚**，走到旁边点 [添加节点]，至少 1 个 |
| 5 | 村民放置 | 走到位置点 [添加村民]，登记处立刻出一只不可伤害的预览村民（头顶带编号，仅本步可见） |
| 6 | 商店位置 | 站目标位置点 [设置商店位置]，**必填**，未设置无法完成部署 |
| 7 | 配置预览 | 总览核心/两套点/节点/村民/人数/波次，点 [确认保存] |
| 8 | 部署完成 | 显示启动入口与启用结果 |

**三个概念的分工**——搞混这三个是部署期最常见的事故：

| 概念 | 作用 | 被摧毁的后果 |
|---|---|---|
| 核心（Core） | 玩家的重生点，**本身没有血量** | 无（它只是复活坐标） |
| 能量节点（重生锚） | 真正的血条：给 Buff、可被打掉、可被修 | **全毁后阵亡玩家无法复活** |
| 村民 | 要守的对象，数量取决于你放了几个 | **全灭即游戏失败** |

粒子预览统一是彩色光柱 + 环绕螺旋，五类点位一色一义：出生点绿、刷怪点红、节点蓝、村民黄、商店金。想跳回已过的步骤用 `/hilltop setup goto <1-8>`。**强制项是硬的**：玩家出生点、怪物刷怪点、村民、商店位置四者缺一，点 [确认保存] 会被拒绝并自动跳回对应步骤补齐。**每步都自动落盘**：点一次完成就写一次 `arenas.yml`，当前步骤号连人带场一起持久化——中途退服、掉线或换竞技场均不丢进度，`setup.saved-exit` 会告诉你回哪个世界接着走。

### 配置骨架长什么样

`arenas.yml` 里一个竞技场就这几个键，其余全部可选：

```yaml
default-arena: arena1
lobby: {world: world, spawn: {x: 0.5, y: 100.0, z: 0.5, yaw: 0.0, pitch: 0.0}}
arenas:
  arena1:
    display-name: "&6&l天空堡垒"
    enabled: true
    world: hilltop_arena1      # 必须与大厅不同世界
    core: {x: 100.0, y: 64.0, z: 100.0}          # 玩家重生点
    waiting-location: {x: 100.5, y: 80.0, z: 100.5}
    spawn-points: []           # 玩家出生点（向导写入）
    mob-spawn-points: []       # 怪物刷怪点（第二套坐标，向导写入）
    # villagers: / shop-npc: 同样由向导写进这个文件，按竞技场持久化，重启自动恢复
    game: {min-players: 2, max-players: 6, victory-waves: 20, wave-interval-seconds: 30}
```

### 竞技场可以同时开多局，也能单独停用

竞技场采用「启用队列」模型：`/hilltop disable <竞技场>` 关场做维护，`/hilltop enable <竞技场>` 重新入队，不影响其他场。队列**串行处理**——一次只加载一个世界，避免同时加载多个世界把服务器卡住。

`/hilltop manage` 打开 54 格管理界面：看状态、删单条出生点/刷怪点/节点/村民/商店、改 per-arena 游戏参数、恢复初始状态。**一个世界只能对应一个竞技场**——加载时会校验世界唯一性、重复的拒绝加载，因为回滚日志以世界名为键，两场共用一个世界会让一方结束时回滚掉另一方正在打的局。商店 NPC 由插件统一管理（部署向导生成、服务器启停清理，启动时自动清掉崩溃残留的重复实体），**每局结束的地图回滚不会触碰任何 NPC**，商店在局间始终可用。「恢复初始状态」清的是坐标、村民、节点、商店、per-arena 参数并强制回滚方块，**保留**世界、显示名与 `enabled` 开关，有二次确认页，对局进行中会被拒绝。删除单条坐标按 GUI 的**过滤后序号**走，别照着它去改文件（损坏条目读取时被跳过，序号可能与原始行号不一致）。

### 深度文档都在仓库里，不在别处

上面每一阶段的完整操作、命令全表和配置逐项解释都在仓库内，随仓库一起发布（本地验证时先 `git add`，指南文件和本文件在同一个仓库里，中英各 6 份——复算：`git ls-files docs/guides | wc -l` → 12）：

- [准备世界与配置竞技场（阶段 1 / 2 / 2.5）](docs/guides/deploy-stages-1-2.md) —— 含 `level-name` 为什么会新建世界
- [8 步部署向导逐步骤说明](docs/guides/setup-wizard.md) —— 含 `setup` 全部子命令与五类粒子配色
- [竞技场生命周期：启用队列 / 管理 GUI / 恢复初始状态](docs/guides/arena-lifecycle.md)
- [村民商店自定义（shop.yml 全字段）](docs/guides/shop-customization.md)
- [完整命令参考（全部一级与二级子命令）](docs/guides/commands-reference.md)
- [配置文件逐项解释](docs/guides/config-reference.md)

## 改参数：哪些不重启，哪些必须重启

服主侧最常被问的一句是「改这个要不要重启」。答案按文件分三档，一条不漏：

| 改哪里 | 怎么改 | 生效时机 |
|---|---|---|
| `game-settings.yml` | `/hilltop config` GUI 保存，或直接编辑文件后 `/hilltop reloadconfig` | **下一局开始**生效（波次间隔与通关波数从下一个休整期起） |
| `monsters-config.yml` | 直接编辑后 `/hilltop reloadconfig` | 下一波刷新生效 |
| `messages.yml` | 直接编辑后 `/hilltop reloadconfig` | 下一条消息即用新文案 |
| `shop.yml` | 直接编辑后 `/hilltop shop reload` | 立即生效，不需要重启 |
| `arenas.yml` | **必须重启服务器** | reloadconfig 不重读它 |
| 管理 GUI 里改 per-arena 参数 | `/hilltop manage` → 游戏参数页 | 立即写入 `arenas.yml` 并推送到运行中的场，**无需重启**（对局进行中会被拒绝） |

`/hilltop config` 打开的是可视化配置中心：人数、波次组合、怪物属性、物品参数、节点与村民血量都在 GUI 里改，所见即所得，不必打开任何文件。两处容易被咬的情形：**手动编辑 `arenas.yml` 不重启不生效**；**对局进行中（含结算倒计时）改该场人数会被拒绝**，避免中途改动触发「人数不足判负」。

**波次超时保险默认是关的**：`wave-timeout-seconds: 0`。极端卡死时人工处理——`/hilltop wave clear` 强清当前波进休整、`/hilltop wave skip` 跳过休整立即开下一波。想恢复自动兜底就把它调回正数（如 120）。全员阵亡且无活跃节点时每 3 秒复检、3 秒内判负；场内无人超过 `empty-arena-end-seconds` 则该场自行结束，不空转。

复算：`grep -nE "^(wave-timeout-seconds|defeat-countdown-seconds|rejoin-timeout-seconds|empty-arena-end-seconds):" src/main/resources/game-settings.yml` → 30 / 33 / 37 / 40 行，值 0 / 20 / 60 / 60。

## 数字都能自己算

「这个数你怎么算的？」——每个把数字写进文档的人都该被这么问一次。下面每条都在**同一节**给了复算命令，克隆仓库后在**仓库根目录**原样可跑（Git-Bash / POSIX 语法，只用只读命令）。跑不出来的数字，就是不该写在这里的数字。

| 断言 | 数字 | 口径 |
|---|---|---|
| Java 源文件 / 源码行数 | **69** 个 / **27415** 行 | `src/main/java` 全量，含主类 |
| Bukkit 事件处理器 | **65** 个 `@EventHandler` | 分布在 17 个文件；大厅保护单文件 16 个 |
| 一级子命令 | **26** 个 | plugin.yml usage / 主 switch / Tab 补全 **三处名单逐行一致** |
| 权限节点 | **7** 个 | `hilltopvillage.admin` 含 6 个 children，`config.*` 默认 false |
| 默认商店商品 | **16** 件 / **5** 种 type | `shop.yml` 的 `items` 段 |
| 多语言文案 | **2108** 行，en / zh 各 **960** 键 | 两个顶层段键数相同；玩家 `/hilltop lang <en\|zh>` 切自己的，管理员执行改全服默认并落盘 |
| 波次配置 | **4** 组档位（`default` + `"1"` + `"5"` + `"10"`） | `victory-waves: 20` 是**通关目标波数**，不是配了 20 条波次表 |
| 随 jar 发布的模板 | **8** 个 yml / **2978** 行 | 首次启动在 `plugins/HilltopVillage/` 生成其中 7 个（plugin.yml 留 jar 内） |
| 包结构 | **13** 个包 + 1 个根主类 | `admin ai arena command config core diagnostic doc gui mechanics shop stats util` |

```bash
  # 规模与资源
find src/main/java -name '*.java' | wc -l                             # → 69
find src/main/java -name '*.java' -print0 | xargs -0 wc -l | tail -1  # → 27415 total
grep -rn "@EventHandler" src/main/java --include=*.java | wc -l       # → 65
ls -d src/main/java/com/hilltopvillage/*/ | wc -l                     # → 13 个包
ls src/main/resources/*.yml | wc -l                                   # → 8 个模板
wc -l src/main/resources/*.yml | tail -1                              # → 2978 total
  # 配置面
awk '/^permissions:/,0' src/main/resources/plugin.yml | grep -cE "^  [a-z]"   # → 7 权限节点
awk '/^  items:/{f=1;next} f&&/^  [^ ]/{f=0} f&&/^    [a-z_0-9]+:/{c++} END{print c}' src/main/resources/shop.yml  # → 16 商品
grep -oE "type: (MATERIAL|HAMMER|FIREBALL|REPAIR_ITEM|EQUIPMENT)" src/main/resources/shop.yml | sort -u | wc -l    # → 5 种 type
awk 'NR>=278 && /^  [^ ]/' src/main/resources/game-settings.yml | sed 's/:.*//'   # → default / "1" / "5" / "10"，4 组
wc -l src/main/resources/messages.yml                  # → 2108
grep -nE "^(en|zh):" src/main/resources/messages.yml   # → 14:en: / 1063:zh:（两段各 960 键）
```

三处命令名单互相对账，这条最能说明「文档即代码」不是形容词：`plugin.yml` 的 usage 串、`HilltopCommandHandler` 的主 switch case、`HilltopTabCompleter` 的一级补全数组，各 26 项，`diff` 为空。

```bash
sed -n '/^commands:/,/^permissions:/p' src/main/resources/plugin.yml \
  | grep -oE '<[a-z| >]+>' | tr '<| >' '\n' | grep -E '^[a-z]+$' | sort -u > /tmp/usage.txt
sed -n '117,598p' src/main/java/com/hilltopvillage/command/HilltopCommandHandler.java \
  | grep -oE 'case "[a-z]+"' | sed 's/case "//;s/"//' | sort -u > /tmp/switch.txt
diff /tmp/usage.txt /tmp/switch.txt          # → 空输出；两个文件各 26 行
```

命令面按用途分五类，一级 26 个刚好摊满（二级子命令另计，全表在 [commands-reference](docs/guides/commands-reference.md)）：

| 用途 | 一级子命令 |
|---|---|
| 玩家 | `join` `leave` `lobby` `gui` `stats` `top` `lang` |
| 对局与诊断 | `start` `stop` `wave` `status` `list` `arenas` `tps` `help` |
| 部署 | `setup` `deploy` `manage` `enable` `disable` |
| 配置 | `config` `reloadconfig` `shop` |
| 物品与建造 | `hammer` `fireball` `build` |

> 注：这一节刻意**不写**玩家数、服务器数、性能提升百分比这类没法复算的数字。测试目录里目前只有 1 个冒烟测试类，所以「完整测试覆盖」这句话也不会出现在这里；质量叙事走 CHANGELOG 的审查轮记录。

## 和同类插件的边界

核查日期 **2026-10-22**，每格都对着来源填：GitHub 侧取自 `api.github.com` / `gh api`，SpigotMC 侧取自**非官方**镜像 `api.spiget.org`（官网被 Cloudflare 挡，无法直接抓取），仅供量级参考、非官方口径。取不到的一律写「未核实」，不猜。

| 维度 | Hilltop Village | Village Defense | Villager Defense | Tower Defense X |
|---|---|---|---|---|
| 玩法形态 | 合作 PvE，玩家亲自守（动作向） | 合作 PvE 守村小游戏 | 合作 PvE 守村民 arena | 经典**建塔**塔防，仿 BTD |
| 波次 / 商店 / 地图回滚 / 多竞技场 | 全有 | 全有 | 波次有，其余未核实 | 全有 |
| **能量节点** | **有**（可摧毁、可修复、附带玩家 Buff） | **无同类机制**（仓库内检索不到 node/energy 文件） | 未核实 | **无** |
| **外部依赖** | **零**（`depend: []` + `softdepend: []`） | softdepend 6 项（PlaceholderAPI / ProtocolLib / ViaVersion 等） | 未核实 | 可独立运行，支持 MythicMobs 等 |
| 多语言 | en / zh 两套，各 960 键 | **13 种**社区语言（最强项） | 未核实 | 部分文案硬编码英文 |
| MC 版本 | Paper 1.21.4 | 1.8 → 26.3 | 1.16 → 1.21 | 1.7 → 26.1 |
| 许可 / 源码 | 源码公开，**PolyForm Noncommercial**（非商用） | GPL-3.0 | GPL-3.0 | 闭源付费 12 USD |
| 维护状态（基准 2026-10-22） | 活跃 | 活跃（2026-10-10 仍在提交） | 半活跃/趋停（最后 commit 2025-07-14） | 半活跃 |

**两句公道话**：Village Defense 是这条赛道上成熟度最高的 GPL-3.0 同类——2017 年上架、跨 1.8→26.3、13 种语言、MySQL 与 BungeeCord 全支持；本项目的「多竞技场 + 启用队列」模型就是照 BedWars1058 这类成熟实现的路子走的，不假装是自己发明的（烈焰蛋的灵感同样来自它的火球，其源码确有 `FireballListener`，大方承认）。Tower Defense X 把 BTD 式数值养成搬进 Minecraft，16 塔 × 16 怪加 Boss 与 Versus 控波，内容纵深是闭源付费项目才敢铺的（本项目不取那个形态）。但形态是玩家互相拆床的 PvP / 站着看塔打怪的建塔塔防，本项目是玩家协作守节点的 PvE。

**因此不拿来当卖点的四件事**：波次、商店、地图回滚、多竞技场——BedWars 类和 Village Defense 早就内建，写成差异点会被任何同行一句话打脸。真正的差异在三处事实层面：一是防守目标多一层，同类把胜负系在村民活着，这里在村民之外加了能量节点（有活跃索引、可被怪打空、能用汁液拉回来、顺带给 Buff），胜负因此变成节点阵地攻防；二是部署耦合度低，同类普遍挂着 PlaceholderAPI / ProtocolLib / ViaVersion 跑 BungeeCord 集群，这里一个 jar 直接跑 Paper 1.21.4；三是许可取向是主动选的，PolyForm Noncommercial 允许学习、自架服非商用游玩、禁止商用，这不是 GPL 的弱化版，是另一种法律姿态，写在这里当定位陈述而不是遮掩缺陷。

这不是建塔类塔防，是守人。玩家不放塔、不挂机看自动炮台打怪，伤害全部来自玩家自己的走位、跳跃和手上那把锤子。

> ⚠️ 未核实格子（发布前人工复核，或按上表显式不写）：Villager Defense 的商店/多语言/多竞技场/回滚是否有内建；本项目在 SpigotMC 的分发数据（未上架，无数据可引；竞品那格若引，口径为 Spiget 非官方镜像、截至 2026-10-22）；SleepingTowns 的插件本体——GitHub 与搜索均无法定位到对应仓库，Spigot ID 88442 经重定向后实为另一插件，故**不进对比表**。调研初期候选里的 BattleTowers / DefenseGate / TFE 三个名字，交叉验证后确认 GitHub 上不存在（API 404 + 搜索零命中），已全部剔除——查不到就说查不到，绝不引用不存在的东西。

## 大厅：进服第一眼，那里什么都破坏不了

玩家进服统一落在大厅，靠 `/hilltop join` 或 `/hilltop gui` 主动进竞技场。大厅不是「一个普通世界」，是一个被 16 个事件处理器封死的房间：破坏/放置方块、受伤、饥饿、天气、掉落物、容器交互在大厅世界内**全部被阻止**（燃烧/爆炸/点火/桶/伤害/移动各占一类）。

- **建造模式**：管理员 `/hilltop build` 临时解除保护用于整修大厅，再执行一次关闭
- **虚空保护**：大厅里掉出世界底部，自动传送回大厅出生点
- **出生点立即生效**：管理员站在大厅世界内执行 `/hilltop lobby setspawn`，把当前位置（含朝向）写进 `arenas.yml`，**无需重启**——新登录、大厅复活、`/hilltop lobby` 都走新点
- **准入保护**：竞技场世界对外部传送有准入校验，未加入对局的玩家用 `/tp`、`/execute in` 也进不去（提示「你还没有加入本竞技场」）；管理员豁免以便部署，`/hilltop join` 正常加入不受影响
- **配置位置**：`arenas.yml` 的 `lobby.world` / `lobby.spawn`；若留空或指向不存在的世界，插件自动回退到服务器主世界（`level-name` 那个），保证开箱可用

复算这 16 类拦截：`grep -c "@EventHandler" src/main/java/com/hilltopvillage/mechanics/LobbyProtectionListener.java` → 16。

## 它不做的三件事

**❌ 一、它不是建塔类塔防。** 玩家不能放塔、不能造自动攻击装置、不能挂机看炮台打怪。防守手段只有技能物品（重锤、烈焰蛋）、走位和节点 Buff。想要「摆一排塔然后旁观」那种玩法，Tower Defense X 那一类才是，这个插件会让你失望。

**❌ 二、没有附魔系统。** 商店里所有装备都是原版白板——铁剑就是铁剑，钻石套不带任何附魔。远程手段靠商店补的弓箭线（弓 15 水晶 / 箭×8 2 水晶），但附魔本身不在这一版里。

**❌ 三、地图回滚有 100 万格上限。** 单个世界记录到 100 万条方块改动后**停止记录**并打一条 `SEVERE`，该局结束时的回滚将不完整——地图会留下一部分没擦掉的改动。正常对局几乎碰不到；真要一张地图炸出上百万格，得连同堆大小一并调整后自行改大上限。还有一条更硬的：回滚日志在内存里，服务器中途崩溃时这一局的 journal 不恢复。

### 边界之外：部署期最容易踩的坑

下面六条都是已经有人踩过、而且踩了会误以为插件坏了的：

| 坑 | 现象 | 怎么办 |
|---|---|---|
| **没有传送进竞技场的命令** | `setup` / `deploy` 都要求你人已经站在目标世界，第一次部署无处可去 | 原版 `/execute in <世界> run tp @s …` 或 Multiverse；先 `/gamemode creative` 再传 |
| **单人局要按场配置** | 全局 `min-players: 2` 时一个人点 join 不开局 | 用 `/hilltop manage` 参数页把**该竞技场**的 `min-players` 改成 1（立即生效），或改 `arenas.yml` 后重启；GUI 里那个 `min-players` 是全局默认值，对已显式配置的场**不生效** |
| **存量 `shop.yml` 不自动出新商品** | 升级后商店还是老几样，以为新版商品没做出来 | 「文件存在即不释放模板」是刻意设计；手动补条目，或备份后删除让它重新生成 |
| **手动改 `arenas.yml` 不重启不生效** | 改完世界名/坐标，插件照旧跑老配置 | 重启服务器；只有管理 GUI 改参数是热生效 |
| **`rescan` 入口还在但已废弃** | 敲 `/hilltop setup rescan` 只回一句提示，什么都没扫 | 节点走第 4 步 [添加节点] 手动注册；废弃入口没有删掉，只会回提示 |
| **禁用后立即启用要等几秒** | `/hilltop enable` 排在队列里反复重试，控制台打提示 | 正常行为：上一局的延迟重置与地图回滚要跑完，隔离窗口结束才加载 |

这是一个「6 个阶段跑通才好玩」的插件，不是装上就自动好玩的产品——首局部署仍需一个人站到每一个点上。

## 被推翻的五个方案

这个插件现在的形状，有一半来自五组「先那样做、后来改掉」的往返。素材全在仓库的 `CHANGELOG.md` 版本段落里，下面只讲机制与判断——每条先说当初为什么那样做也说得通，再说代价。

**一、波次超时保险：从「默认开」退回「默认关」。** 当初加它是因为一种最难看的卡死：玩家全部存活、波却清不完，只能 `/stop`。于是做了一层兜底——某波超时后把残怪传送到最近存活玩家身边并高亮，宽限 15 秒仍未清则强制清除，写的时候默认 120 秒。听起来无懈可击，后来还是把默认值改回 **0**：自动清残怪会掩盖「AI 卡死的真因」，本该报信的信号被兜底吃掉了。代码路径与配置钩子**全部保留**，调回正数就复活；存量服务器磁盘上仍是旧默认 120 的会被归一化为 0，手工设过其它值的不受影响。**代价**：极端卡死需要服主自己上 `/hilltop wave clear` 或 `wave skip`。

**二、能量节点：从「开局自动扫描方块」改成「手动注册坐标 + 方块保护」。** 扫描方案很省事——游戏启动时扫核心周围一定范围内的信标/附魔台/末影箱/重生锚，摆了什么就算什么。省事的前提是地图作者按你的想象摆方块，而这个前提不成立：漏检加上扫描开销，最后改成管理员在第 4 步走到锚旁边点 [添加节点]，坐标存进 `nodes.yml`，重启后从文件加载而不是重新扫描。**代价**：0 节点必须由人补，忘注册 = 全场无人能复活，最后一名玩家阵亡即触发判负（「无存活玩家且无活跃节点」）。现在文档里那句「不扫描方块」，就是这次往返留下的口径。

**三、商店 NPC：从「每个竞技场自己持有」改成「插件级单例」。** 让 `ArenaInstance` 各持一个 shopManager 在面向对象上讲得漂亮——谁的场谁管生命周期。真出事的方式是：竞技场 reset 时调自己的 `cleanup()`，那个方法会遍历**所有世界**删除带商店标记的实体，而用于「跳过自己」的判断因为手上没有 NPC 引用而永不成立——一场结束，别的场的商店跟着蒸发。后来竞技场不再持有商店与 NPC 生命周期，一切走 `plugin.getShopManager()`。**代价**：对局进行中清除商店会被**拒绝**，因为它当场撤掉场内所有人正在用的商店。

**四、监听器：从「每竞技场注册一个」改成「全局单例按世界路由」。** 每场各注册一个 `VillagerSystem` 看似天然隔离。但 `disableArena` / `stopArena` 从不注销，于是反复开关竞技场会累积监听器——内存一路涨，村民受伤被重复处理。改成全局注册一次、按世界名路由回对应竞技场，再加 `instanceof Villager` 短路。**代价**：源文件里多了一处「本处不再 registerEvents，避免监听器泄漏」的注释当路标，而多场并行的事件分发逻辑从此变复杂。

**五、出生点与刷怪点：一套坐标拆两套，接线后炸进虚空。** 玩家出生点与怪物刷怪点原本共用一套坐标，后来拆成 `spawn-points` / `mob-spawn-points` 两套（旧存档只有前者时视为双用并记一条 warning）——拆的理由很硬：两者对地形的要求正好相反，玩家点要开阔安全，刷怪点贴着岛缘就行。这一条同时踩中「拆坐标」和「接线」两类坑：真正炸开是在 `spawn-radius-min/max` 被接线生效之后——每个刷怪点周围 20~40 格随机偏移，天空岛地图一偏移就飞出岛外，怪物刷进虚空成片 `fell out of the world`，表现成「第一波一个怪也没有」。**接线类改动会激活沉睡的配置面**，这条教训换来的是安全落点重试：向下扫描找第一个实心非液体方块取顶面、要求落点上方 2 格无实心、贴近世界底部判不安全返回 null，偏移候选重试八次后回退基准点。**代价**：出厂模板半径从 20/40 一律改成 1/3，老服的 `arenas.yml` 要手动改小或靠兜底检测。

## 反馈与贡献

**什么算 bug**：插件说到没做到——回滚不完整却没打告警、[确认保存] 被无理由拒绝、人数够时 `/hilltop start` 却说人数不足、升级后配置被吃掉。**什么算提案**：玩法与手感——新怪物战术、波次节奏、GUI 布局；装备成长线里「附魔」这一档已在计划中。

**提 issue 前先跑三样**，这三段输出能替双方省掉一轮来回：`grep -n "^## \[" CHANGELOG.md | head -3`（你的版本在不在最新段落）、`grep -cE "SEVERE|WARNING" logs/latest.log`（控制台有没有已说明原因的告警），加游戏内一条 `/hilltop status`（波次/玩家/怪物/节点一屏可读）。

**贡献代码**：fork → 改 → `mvn clean package` 跑通 → 开 PR。想动节点、商店、监听器这几块，先读上面「被推翻的五个方案」和 `CHANGELOG.md` 对应段落——那几组往返踩过的坑不必再踩一遍。贡献同样受 PolyForm 非商用许可约束。

## 权限：7 个节点，配置默认只有 OP 碰得到

| 权限节点 | 默认 | 说明 |
|---|---|---|
| `hilltopvillage.admin` | OP | 管理员权限，含下面 6 个 children |
| `hilltopvillage.config.admin` | OP | 配置中心 GUI 访问 |
| `hilltopvillage.config.rules` | **false** | 游戏规则配置 |
| `hilltopvillage.config.waves` | **false** | 波次配置 |
| `hilltopvillage.config.monsters` | **false** | 怪物属性配置 |
| `hilltopvillage.config.items` | **false** | 重锤/物品配置 |
| `hilltopvillage.config.nodes` | **false** | 能量节点配置 |

除 `admin` 与 `config.admin` 外全部默认 false——装插件的人不等于能改规则的人。另有 11 个玩家专属子命令（join / leave / lobby / gui / hammer / fireball / setup / deploy / config / manage / build）被列进控制台黑名单，从控制台执行会被拒。

```bash
awk '/^permissions:/,0' src/main/resources/plugin.yml | grep -cE "^  [a-z]"   # → 7
grep -n "CONSOLE_BLOCKED_COMMANDS" src/main/java/com/hilltopvillage/command/HilltopCommandHandler.java   # → 66 行，11 个
```

## 自己编译：一条 mvn 命令

```bash
mvn clean package
```

产物在 `target/HilltopVillage-<version>.jar`。要求 JDK 21 + Maven，依赖只有 `paper-api`（provided）与 JUnit（test scope），不会往 jar 里塞任何第三方类。

## 许可证：非商用，随便学随便改

PolyForm Noncommercial License 1.0.0 —— 学习、自架服非商用游玩、随意改都可以，**商用不行**。GitHub 的 license 字段把它识别成 other（`gh api repos/sqkl520/Minecraft-server-plugin-Hilltop-Defense --jq .license.spdx_id` → `NOASSERTION`，这条要联网、可跳过），这不是识别失败，是非商用许可的正常表现。任何使用、修改或分发，都必须保留项目根目录下的 `LICENSE` 和 `NOTICE` 文件，并确保其内容完整——`head -3 LICENSE` 第一行就是协议全名。

<div align="center">

**你走完 8 步向导，玩家就能守二十波。**
**打完一局，地图回到第一格。**

<br>

*「你守的不是塔，是八个不会跑的村民。」*

</div>
