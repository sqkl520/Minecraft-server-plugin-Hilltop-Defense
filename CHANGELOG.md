# Changelog


## [1.1.0-SNAPSHOT-fix3] - 2026-11-11

> 1.1.0 的第三个修复版：计分板与 Tab 栏改造轮 + round5 深入审计轮的合并交付。
> 本版首次引入 `display.yml`（侧边栏与 Tab 栏的布局配置文件），并修复侧边栏 8 条渲染缺陷
> （其中 1 条会导致行序错乱的 Major）。round5 的 10 项缺陷（waves 波次载入重建 / 键集对齐 /
> 配置菜单白名单 / 数值钳制 / 结点只读守卫 / 只读测试守卫 / 白名单补键等）同版落地。
> 测试套件首次入库：78 个测试文件、**846 项**（Failures 0 / Errors 0 / Skipped 1）。
> 审查产物：`docs/01-reviews/scoreboard-tab/`（11 份报告）；`docs/reports/round5/`（38 份报告）。
>
> ⚠️ **本版与 `1.1.0-SNAPSHOT-fix2` tag 的对应关系说明**：`1.1.0-SNAPSHOT-fix2` 这个 tag
> 实际指向 2026-10-28 的 README 提交（`af2c69f`），**不包含**计分板改造与 round5 的任何内容；
> 其名下 GitHub Release 的 JAR 同样不含。本版 `1.1.0-SNAPSHOT-fix3` 是第一个真正包含
> 上述全部改动的 tag。若你此前装过 fix2 的包，建议直接跳到本版。

### Added（2026-10-31 ~ 11-11 — 计分板与 Tab 栏改造）

- **新增 `display.yml` 展示配置**：侧边栏与 Tab 栏的布局从硬编码改为配置驱动。四种行型——分隔线（`separator: N`）、空行（`blank: true`）、静态文本（`key: <i18n 键>`）、动画（`anim: [键1, 键2]` + `frame-ticks`）。每行可用 `when` 条件（11 个）与 `args` 参数（18 个）绑定运行时数据。文件由服主手写，**插件只读不写**，因此注释长期保留
- **新增 Tab 栏管理（`TabListManager`）**：每个玩家独立的头/尾（大厅 / 对局 / 观战三种上下文），玩家列表名按状态着色（存活 / 阵亡待复活 / 观战 / 管理员），排序用 Paper 官方 `Player#setPlayerListOrder(int)` 做分带——不篡改玩家名、不伪造前缀
- **Tab 的上下文开关管全接管**：`tab.contexts.lobby/arena/spectator` 关掉时不仅停发头/尾，还会主动把名字与排序复位成客户端默认，避免与别的插件抢（此前只停头尾、仍覆盖名字）
- **新增 `/hub` 与 `/lobby` 两个顶层命令**：任意世界执行都能回大厅；`/lobby setspawn` 设大厅出生点（`/hub` 等价于 `/hilltop lobby`）
- **新增 17 个 i18n 键**（en / zh 双语）：`scoreboard.separator` / `next-wave` / `mobs` / `arena` / `state` / `tip.loading` / `tip.fallback`，`tab.header.*` 5 个，`tab.name.*` 4 个。既有键文本一律未改（哈希基线不可重生成）
- **`/hilltop reloadconfig` 覆盖 `display.yml`**：布局、文案、开关全部热生效，无需重启

### Fixed（2026-10-31 — 侧边栏渲染缺陷，8 项）

- **【Major】侧边栏行序错乱修复**：`setScore` 此前只在「首次建行」那一帧调用，行数变化（开局倒计时行出现/消失、玩家数据行整体增删）后旧行分数不重算，产生重复分数与行序随机跳动。现改为无条件执行 + `int[]` 缓存比对，仅值变时下发
- **【Major】缩行残留幽灵空行修复**：行数减少时只 `unregister()` 队伍、未 `resetScores()` 分数条目。`Team#unregister()` 只解除条目归属、**不删** objective 上的 score（已字节码核实），被移除的行以空白形式永久占据侧边栏。现改为先 `resetScores` 再 `unregister`
- **【Major】签名遗漏玩家级可见性输入**：`when: alive` / `when: spectator` 依赖每个玩家自己的状态，但这两个输入不在刷新签名里。「A 死亡、B 复活」这类两人互换在聚合值上完全抵消（alive 不变、spectators 不变），签名命中短路后整帧不重绘，玩家看到错误的个人数据行
- **【Major】刷新任务异常后永久停更**：`CraftScheduler` 对抛未捕获异常的任务会记录一次并**静默取消**，而 `updateTask` 字段仍指向那个已被取消的句柄，后续 `show()` 全被误判为「任务还活着」。现加 `try/catch` + `isCancelled()` 判据，异常只跳过该帧不冻结整局
- **隐藏右侧红色分数**：调用 `Objective#numberFormat(NumberFormat.blank())`。此前每行尾部一直显示红色 1~15 数字（Paper 1.20.3 起引入），玩家会误读成正文
- **删除 64 字符截断**：MC 1.13 起队伍 prefix/suffix 是 Adventure `Component`，`CraftTeam#setPrefix` 无任何长度前置条件。原 `substring(0, 64)` 会切断颜色码 `§` 或 UTF-16 代理对（中文/emoji），导致行尾乱码且永不自愈
- **`scoreboard.enabled` 热关闭生效**：开关此前只在 `show()` 读一次，中途关闭对已显示的侧边栏无效。现刷新任务每轮先判开关，关闭即拆板
- **竞技场重置误伤其它场玩家修复**：`clear()` 未校验计分板归属，A 场重置会把已转场到 B 场的玩家侧边栏一并拆掉。现加 `getScoreboard() == state.board` 守卫
- **其它**：行数上限 10→15（超限打 WARN 而非静默裁尾，原会静默丢弃玩家个人数据行）；重复 `show()` 时显式注销旧 `Objective`；村民计数从「每玩家一次全量遍历」降为「每竞技场一次」

### Fixed（2026-11-10 — round5 深入审计，10 项）

- **【P1】waves 波次载入重建（F2 + 症状 2）**：`default.compositions` 缺失时的回填链修正，修复「波次配比在特定组合下整条丢失」
- **【P2】waves 键集对齐（症状 1）+ F3 default 配比对齐**：内存键集与磁盘键集精确一致（`memoryWaveKeysMatchDiskExactly` 由红转绿），修复「GUI 改了波次、重启后部分键复活/丢失」
- **【N1】配置菜单点击白名单**：裸 `else` → `else if (action == LEFT)`，修复右键误触（全库 16 处）
- **【N2】数值输入钳制**：`NumericInputHandler` 补 min/max 钳制重载，修复超范围输入直接落盘
- **【F6】`monsters-config.yml` 白名单补键**
- **【F7】删除 `GameConfig.nodeBlockTypes` 死字段**
- **保存按钮消费返回值**：`MainConfigMenu` 保存失败时提示（新增 `config.menu.main.save-failed`），不再静默
- **`NodeSystem` 删除空表补默认值**：空 `buff-effects` 不再被静默填充默认值，改为按键存在性判据
- **只读测试守卫**：`YamlFileGateTest` 的只读用例从「依赖文件权限」改为「写进不可写目录」，总数恒定
- **日志英文化与等级修正**：全库 `getLogger()` 输出统一英文；`arenas.yml` 写盘失败 6 个入口统一 SEVERE；`MapRestoreManager` 逐块失败日志每 tick 最多 5 条

### Fixed（2026-11-19 — Tab 栏审计批 1，3 项）

- **【D1】Tab 动画速度修正**：`refreshAllUnchecked()` 的动画时钟由「每次刷新 +1」改为按真实周期累加（`tick += scheduledPeriod`）。`display.yml` 的 `frame-ticks` 单位是 Minecraft tick，此前配 `refresh-ticks: 20` + `frame-ticks: 20` 的动画每 400 tick（20 秒）才换一帧，比侧边栏慢 20 倍；现与 `ArenaScoreboard#tickCounter` 口径一致
- **【D2】未接管的上下文不再抹掉别的插件的头/尾**：`pushHeaderFooter()` 在 `tab.contexts.*` 为 `false` 时不再每帧发空值，改为「缓存里还留着本插件发过的值时擦一次，然后彻底放手」。此前关闭某个上下文等于告诉别的插件「你的头/尾每秒被清空一次」
- **【D3】`restoreAll()` 分项还原**：插件关闭时不再对「只发过头/尾」的玩家调 `playerListName(null)` + `setPlayerListOrder(0)`。名字/排序仅在对应缓存（`lastName`/`lastOrder`）有条目时才复位，头/尾仅在 `lastHeader`/`lastFooter` 有条目时才擦
- **测试**：`TabListManagerTest` 新增 9 项（D1 3 项 / D2 3 项 / D3 3 项），该类 24 → 33 项；全库套件 953 项，Failures 0 / Errors 0 / Skipped 1

### Fixed（2026-11-19 — 侧边栏审计批 1，1 项）

- **【S08-F2】侧边栏开关重新打开后不再永久丢失**：`scoreboard.enabled` 被关闭时 `refreshAll()` 拆掉所有板并 `stopTask()`，此后任务已死、`refreshAll()` 不再被调用，管理员改回 `true` 时**没有任何路径为已在场的玩家重建侧边栏**——玩家整局看不到节点 / 村民 / 波次，只能离场重进、重连或等下一局。现新增 `lastEnabled` 翻转判定 + 关闭期间的轻量恢复探针（固定 1 秒轮询一次布尔值，恢复或场上无人即自取消），在 `false → true` 时对每名在线竞技场成员调一次 `show()` 并重启刷新任务；关闭期间入场也会挂上探针。关闭行为（全量拆板 + 停任务）保持不变
- **测试**：`ArenaScoreboardTest` 新增 4 项（翻转恢复 / 不重复重建 / 无人在场自取消 / 关闭期入场挂探针），该类 4 → 8 项；全库套件 953 → 957 项

### Fixed（2026-11-19 — 玩家统计持久化审计批 2，3 项）

- **【A】0 字节统计文件不再静默放过**：`StatsManager.load()` 对「文件存在但解析出空文档」的情况原本直接返回空缓存 —— 零日志、不隔离。`loadFromString("")` 不抛异常，所以那个宽 `catch` 兜不住它；而该类自己的注释早就写着「该文件在真实部署目录中曾被实测为 0 字节形态」，即这个已知发生过的事故形态一直是静默通过的。现与解析失败同等处理：SEVERE + 隔离 + 空数据启动。区分两种「空」——「文件无 YAML 文档」判为损坏，「能解析但没有 `players` 段」判为合法的空数据集，不隔离
- **【B】瞬时读错误不再被当成文件损坏**：原实现用同一个 `catch (Exception)` 同时接 `Files.readString` 的 `IOException`（杀毒软件/备份占用、ACL 变更、共享冲突）和解析异常。前者内容完好、重试即可，却被当成损坏把好文件改名成 `.corrupt`，随后第一次保存用空数据永久覆盖。现分离两类：IO 失败进入本次运行的**只读模式**（拒绝写盘并明确播报，与 `YamlFileGate` 同一哲学：读不出来的文件绝不覆盖），解析失败才隔离
- **【C】落盘改真原子 + 修正失实 javadoc**：`writeAtomically()` 的注释写着「临时文件 + 原子替换」，但 `Files.move` 没带 `ATOMIC_MOVE` —— Windows 上非原子 `REPLACE_EXISTING` 是先删后改名，崩在中间就是**整个文件消失**。现补上 `ATOMIC_MOVE`，并在文件系统不支持时（`AtomicMoveNotSupportedException`）回退到非原子移动、只 WARNING 一次告知保证减弱；javadoc 改为诚实的「尽力而为」
- **测试**：`StatsManagerTest` 15 → 22 项，全库套件 957 → 964 项

### Fixed（2026-11-19 — 管理工具审计批 3，3 项）

- **【S03-F2】怪物属性菜单的四件护甲改用各自的循环表**：`MonsterSettingsMenu` 的 `addArmorSlot` 四个槽位此前共用一份「全头盔」数组 `ARMOR_CYCLE`，点「胸甲」槽会把 `LEATHER_HELMET` 写进 `chestplate` 字段；更糟的是管理员通过 SHIFT 输入或手改 YAML 配好的真实胸甲不在表内，第一次左键就被覆盖且再也循环不回来。现拆成 `HELMET_CYCLE` / `CHESTPLATE_CYCLE` / `LEGGINGS_CYCLE` / `BOOTS_CYCLE` 四张表（档位顺序一致：皮革 → 锁链 → 铁 → 钻石 → 下界合金 → 金 → 空），并由 `nextArmorMaterial` 取代 `cycleMaterial`：**当前值不在本槽位预设表里时拒绝改动**并提示改用 SHIFT 手动输入（不动比静默换档更安全——护甲直接影响战斗数值，必须由管理员显式决定）。`WaveSettingsMenu.MonsterPropMenu` 的同一份「全头盔」数组 `ARMOR_LIST` 同版修复（新增 `addArmorCycle` / `nextArmorMat`，四张新表；该页没有 SHIFT 输入，表外值同样只提示不改）
- **【S05-F1】三个单条删除方法不再丢弃写盘结果**：`AdminDeployManager` 的 `removeSpawnPoint` / `removeMobSpawnPoint` / `removeVillager` 此前无条件 `return true`，把 `saveSpawnPoints()` 的结果整个丢掉——`arenas.yml` 只读或 IO 失败时管理员收到「已删除」，重启后点位原样回来。现直接返回落盘结果，并把三个命令入口（`delspawn` / `delmobspawn` / `delvillager`）的**序号校验前置**：序号非法报「ID 不存在」，校验通过后拿到 `false` 则如实报 `deploy.save-failed`，两种失败模式不再混淆
- **【S02-F3】部署向导删除点位不再「没删也报已删」**：`SetupWizard` 的 `setup-spawn-<i>` 与 `setup-mobspawn-<i>` 两个点击分支此前丢弃返回值，写盘失败照样播「已移除」并继续刷新界面。现与村民分支（一直有校验）同口径：失败即播 `setup.save-failed` 并提前返回，不复位页码、不刷新、不自动保存
- **【S09-F1】损坏的 `shop.yml` 不再拖垮整个插件**：`ShopConfigLoader.load()` 对每种不可用读取都抛 `IllegalStateException`，而 `ShopManager.reload()` 首句就是 `this.config = loader.load()` 无 try/catch，`onEnable` 又不给商店套容错——于是「一份空的 shop.yml」= onEnable 中止 = **没有竞技场、没有世界、没有命令、没有监听器**。现 `reload()` 改为不抛异常并返回 `boolean`：失败时打一条英文 SEVERE，**已有上一份有效配置就保留它**、**首次启动则落回内置空商店**（`EMERALD` / 27 格 / 0 件商品；空商店在 `ShopGUI` 里只有占位板，点击拿不到商品，购买链自然拒绝而不 NPE）。`onEnable` 刻意**不加** blanket catch（隐藏未来的 bug），`/hilltop shop reload` 改为按返回值播报成功或失败（新增双语键 `shop-reload-failed`）
- **测试**：`MonsterArmorCycleTest` 新增 15 项（四槽位各走自己的表 / 表外值不被覆盖 / SHIFT 仍可输入任意材质 / 四表档位对齐 / 波次菜单同一份副本）；`AdminDeployWriteResultTest` 新增 6 项（三个 remove 方法在只读闸门下返回 false / 序号非法仍返回 false 且内存不变 / 成功侧与失败标记对照）；`ShopReloadDegradeTest` 新增 7 项（首启坏文件得到空商店且不抛 / 已有快照时坏文件保留旧快照 / 返回值区分成功失败 / 空商店购买链安全拒绝 / 货币工厂不崩）；既有 `ShopConfigCorruptFileTest` 的两条 `assertThrows(shop::reload)` 改为 `assertFalse(shop.reload())`（契约从「抛异常」变为「返回 false」）；全库套件 963 → 991 项

### Changed（2026-11-11 — 工程基建）

- **测试套件首次入库**：78 个测试文件、846 项（MockBukkit + Mockito 基础设施 + 探针测试）
- **新增 `build.sh` / `build.cmd`**：自动探测并切换 JDK 21 后再调 Maven，解决本机默认 `JAVA_HOME` 是 17 导致的 `maven-enforcer-plugin` 拦截
- **新增 CI**（`.github/workflows/build.yml`）：Linux + JDK 21 上跑 `clean test`，只构建不分发
- **新增 `.gitattributes`**：`*.sh` 强制 LF / `*.cmd` 强制 CRLF / 源码与文档统一 LF
- **`.gitignore` 新增 `/.work/`**：多智能体流水线的过程产物不入库

### ⚠️ 升级提示（升级到本版必读）

- **新增 `display.yml`**：首次启动会自动生成到 `plugins/HilltopVillage/`。**你不需要做任何事**——出厂默认布局与你此前看到的 10 行完全一致，观感不变
- **两处行为变更**：
  1. `waves` 段的载入语义改为「键集精确对齐」，磁盘上多余的 waves 子键会被清理
  2. `nodes.buff-effects` 改为按键存在性判据，空表不再被静默补默认值
- **侧边栏右侧的红色数字会消失**：这是本版刻意消除的（此前每个行尾都挂着一个 1~15 的红字，容易误读）
- **Tab 栏会开始有内容**：此前完全空白。若你在用别的插件管 tab，把 `display.yml` 的 `tab.enabled` 设为 `false`，或对应上下文设为 `false`
- **`/hub` 与 `/lobby` 是新增命令**：`plugin.yml` 已声明，无需改任何权限配置即可使用
- 服务端无需其它动作；升级前建议备份 `plugins/HilltopVillage/`

## [1.1.0-SNAPSHOT-fix2] - 2026-10-22

> 1.1.0 的第二个修复版：fix6 全量审查轮（12 专家通道全库审查 + V1 横向对账 + V2 对抗证伪，合并清单 ~40 条：P0=0/P1=3/P2=11/P3=~26）的四路并行修复 + 四路锐评轮裁定修正。
> 审查产物：`docs/01-reviews/fix6/`（fix6-MASTER-BACKLOG.md 总报告）；修复规格书：`fix6-REPAIR-PLAN.md`（124KB）；锐评报告：`docs/01-reviews/fix6-critique/`。
> 本段落另附 2026-11-01 ~ 11-04 的三轮全库审计与测试套件建设（round1 ~ round3，产物在 `docs/reports/round1~3/`）：不改版本号、不改产出行为，只为已落地的修复补回归守护。
> 再附 2026-11-10 的 round5 深入轮（P1 / P2 + F3 / N1 + N2 / F4 + F5 / F6 / 只读测试守卫 / F7，产物在 `docs/reports/round5/`）：不改版本号，其中 `waves` 与 `nodes.buff-effects` 的载入语义属行为变更，服主请看文末「升级提示」。
> 原 `1.0.9-SNAPSHOT` / `-fix1`~`-fix4` / `1.1.0-SNAPSHOT` / `-fix1` 的包与 GitHub Release 保留不动。

### Fixed（2026-10-22 — Y1 状态机语义（含 2 条 P1））

- **【P1】`/hilltop stop` 强停判负链封堵**：`stopGame()` 与 `resetNow()` 双触发面照 `reset()` 既有范式改为「先捕获 sessionWave 快照 → `gameManager.stopGame()` 归零状态 → 再踢人」——修复「踢人循环期间人数跌破 min-players 触发 `endGame(false)`：剩余玩家收假『对局失败』战报、后被踢者按 defeat 落库记负场（一局两口径）」；修复前 fix5 审查曾误判为「强停=中性」
- **【P1】节点世界名大小写分裂修复**：`NodeSystem` 4 处 equals → equalsIgnoreCase + **写回过滤第 5 点**（saveNodesToConfig 的 `arenaWorlds.contains`，fix6 锐评 Y1-N1 补漏）——修复「服主改 arenas.yml 世界名大小写后，nodes.yml 节点静默全丢 / 删除节点不落盘 / 幽灵节点复活」
- **强停踢人静默修复**：踢人前广播 `game-force-stopped`（被踢者不再无声弹回大厅）
- **回滚窗 start 误导修复**：`/hilltop start` 在地图回滚期间报专属文案（新增 `game-start-restoring`，含回滚失败态提示）取代误导的「人数不足」
- **结算窗死亡接管统一**：死亡门控从 `GameManager.isGameRunning()` 收化为「竞技场状态 ∈ RUNNING/WAVE_INTERMISSION/VICTORY/DEFEAT」——经 `endGame()` 的结算窗死亡正常记死亡（此前少记一次且物品短暂落地）

### Fixed（2026-10-22 — Y2 输入校验与经济链（含 1 条 P1））

- **【P1】非法材质吃币链封堵（六个改点）**：`/hilltop config nodes repairitem water` 等非物品材质全链拒绝（HCH 命令入口 + ConfigManager 磁盘读取 + 旧键迁移三处加 `isItem()`；同族扩至锤材质/烈焰蛋材质/商店 icon/货币材质共六改点，全部与既有 `!material.isItem()` 范式对齐）
- **购买退款纵深**：`ShopListener.purchase` 对发货构造异常兜底退款（提取 `refundCurrency` 共用方法，含 0 价防刷钱与背包溢出掉落）——任何工厂异常都不再吃玩家货币

### Fixed（2026-10-22 — Y3 UX 与文档（10 项 + P3 随修 5 项））

- **【编号口径统一·重要】delspawn/delmobspawn/delvillager 编号全面 1 基化**：列表显示、删除接收、回显、usage 文案与 SetupWizard/管理 GUI 全部统一从 1 开始（修复「跨界面按显示编号敲命令会删错条目」；`delspawn 0` 现在明确报不存在）
- **商店 NPC 迁移文案错靶修复**：跨世界被他场对局阻塞时改为定向文案（新增 `findBlockingArenaId`，指出实际需 stop 的场 ID）
- **等待期体验**：侧边栏显示「等待玩家 x/y」与「开局倒计时 N 秒」（中途加入者立即可见）；参数页补「对局中/结算中被拒」预披露
- **选场 GUI 图例修正**：删死变量 borderMaterial（图例自 1.0 起承诺的四色边框从未渲染），图例改述实际
- **生成器修正**：「每位玩家同时只能持有一把锤子」失实句删除；部署流程链补节点/村民/商店三向导必填项
- **`/hilltop tps` 状态本地化**：`tps.state` 不再直吐 GameState 英文枚举（GameState.i18nKey() 统一）
- **P3 随修**：开局重复消息合并（R07-02）；`/hilltop help` 开放给所有玩家（内容按权限分档）+ Tab 补全；死亡零惩罚披露（R07-09）；旁观期说明（R07-11）；reloadnodes 菜单描述修正 + 节点入口指引（R09-09）

### Fixed（2026-10-22 — Y4 死代码清扫与质量）

- **死代码大清扫（A/B 两组 ~33 项，全部附四通道零调用证明）**：openDeployMenu 整链（含 37 个 deploy.menu-* 死键）、DisplayEntityManager 半类（spawnHammerImpact/cleanupQueue 链/cleanupAll）、stopParticleShow、MapRestoreManager.discard（含 complete 签名收窄）、SetupWizard.invalidateAllDeploymentContexts、IArena 四死成员、AdminDeployManager 两死 getter 等；`getEnableQueue`/`isResetting` 按评估保留（潜在 UX 接线面）
- **Locale 45 处补齐**：全库 `toUpperCase()/toLowerCase()` 无参调用全部补 `Locale.ROOT`——杜绝 tr_TR 等 JVM locale 下命令分发/材质解析失效
- **javadoc 同步 5 处**（删除后引用清理）+ 杂项（HCH:104 乱码修复、ArenaManager 重复 import 清理）

### Fixed（2026-10-22 — 锐评轮快修）

- **nodes 写回过滤大小写漏修**（fix6 锐评 Y1-N1，P2 级）：`saveNodesToConfig` 的过滤补 equalsIgnoreCase 匹配——F1-03 的第 5 个点位（规格书漏列、锐评补报）
- **回滚失败态文案适配**（Y1-N2）：`game-start-restoring` 补「若长时间无变化需排查控制台日志」
- **README 双语同步**：`/hilltop help` 标注从「（管理员）」改为「所有玩家可用（内容按权限分档）」（Y3-N2）；sessionWave javadoc 同步四处捕获现状（Y1-N3）

### Changed（2026-10-24 ~ 10-26 — 日志英文化、日志等级修正、UX 修正与深度指南）

- **日志全部英文化（121 处 / 19 个文件）**：全库 `getLogger()` / `Bukkit.getLogger()` 输出的中文日志统一改为英文——日志面向服主与运维，不受 `/hilltop lang` 影响；玩家可见文案（`messages.yml`）仍保持中英双语不变。同步统一风格：首字母大写（以文件名字面量起头的保持小写）、句尾不加句号、`+` 拼接保留可 grep 字面量、美式拼写、禁止工单号进日志
- **日志等级修正（10 处）**：`arenas.yml` 写盘失败的 6 个入口统一为 SEVERE（原 3 处 WARNING）；战绩补录失败升 SEVERE；可重试的回滚失败降 WARNING；竞技场世界冲突/世界加载失败/文档生成失败/两个关服装饰性清理降 WARNING
- **消除出厂即触发的假告警**：`empty-arena-end-seconds` 模板默认由 60 改为 **120**（先留足重连窗口再判空置），`GameConfig` 字段默认同步；旧出厂组合 60/60 静默不提示；该提示本身由 WARNING 降为 INFO（是建议而非故障）
- **回滚失败日志防刷屏**：`MapRestoreManager` 逐块失败明细保留带坐标的 WARNING，但每 tick 最多 5 条，超出部分汇总为一条「本 tick 另有 N 个方块失败」
- **`/hilltop status` 显示竞技场**：头部新增 `Arena: <id>` 行，走回退链解析时标注「（默认）」；`status-players` 措辞由「在线玩家」改为「**场内玩家**」（大厅玩家不计入）
- **开局人数不足提示补指引**：`game-start-need-players` 明确「玩家需先 `/hilltop join` 进入竞技场（大厅里的人不计入）」
- **`/hilltop build` 加世界守卫**：建造模式只对大厅世界生效，在其它世界执行会被拒绝并提示大厅世界名；已开启建造模式的玩家仍允许关闭（避免卡在开启状态）
- **深度指南（`docs/guides/`）**：部署阶段、向导、竞技场生命周期、商店自定义、命令全表、配置逐项共 6 份中文指南 + 6 份英文指南（`docs/guides/en/`）随仓库发布，README 在部署指南、商店系统、命令、配置四处提供入口链接
- **文档勘误**：`spawnpoints.yml` 迁移来源更正为商店村民位置；`blocksPerTick` 非法值回退口径按代码三分支写明（非数字/≤0 各打 WARNING，缺失/空白静默按 200）
- **不涉及版本号变更**；日志与提示文案变更无需服主操作，服务端无需任何动作

### Changed（2026-10-31 — 计分板与 Tab 栏改造）

- **新增 `display.yml` 展示配置**：侧边栏与 Tab 栏布局从硬编码改为配置驱动；行型支持分隔线 / 空行 / 静态文本 / 动画（2-8 帧循环），每行可用 `when` 条件与 `args` 参数绑定；文件为纯手写，插件只读不写，注释长期保留
- **侧边栏隐藏右侧红色分数**：调用 `Objective#numberFormat(NumberFormat.blank())`，此前每行尾部一直显示红色 1~15 数字（Paper 1.20.3 起引入）
- **侧边栏行数上限 10 → 15**：超出上限由静默裁尾改为打一条英文 WARNING，不再无声丢弃玩家个人数据行
- **删除 64 字符截断**：MC 1.13 起队伍 prefix/suffix 为 Component，无长度上限；原 `substring(0, 64)` 会切断颜色码 `§` 与 UTF-16 代理对，导致行尾乱码
- **新增 Tab 栏管理**：每个玩家独立的自定义头/尾（按大厅 / 对局 / 观战三种上下文），玩家列表名按状态着色（存活 / 阵亡待复活 / 观战 / 管理员），并用 Paper 官方 `Player#setPlayerListOrder` 做分带排序——不篡改玩家名造假排序
- **`/hilltop reloadconfig` 覆盖 `display.yml`**：布局、文案、开关全部热生效，无需重启
- **新增 i18n 键 17 个**（en / zh 双语）：`scoreboard.separator` / `next-wave` / `mobs` / `arena` / `state` / `tip.*`，`tab.header.*` 5 个，`tab.name.*` 4 个；既有键文本一律未改（哈希基线不可重生成）
- **不涉及版本号变更**

### Fixed（2026-10-31 — 侧边栏渲染缺陷（5 项，含 1 条 Major））

- **【Major】侧边栏行序错乱修复**：`setScore` 此前只在新建行时调用一次，行数变化（开局倒计时行出现/消失、玩家数据行整体增删）后旧行分数不重算，出现重复分数与行序跳动——现改为无条件执行 + `int[]` 缓存比对，仅值变时下发
- **【Major】缩行残留幽灵空行修复**：行数减少时只 `unregister()` 队伍、未 `resetScores()` 分数条目，被移除的行以空白形式永久占据侧边栏——现改为先 `resetScores` 再 `unregister`
- **`scoreboard.enabled` 热关闭生效**：开关此前只在 `show()` 读一次，中途关闭对已显示的侧边栏无效；现刷新任务每轮先判开关，关闭即拆板
- **竞技场重置误伤其它场玩家修复**：`clear()` 未校验计分板归属，A 场重置会把已转场到 B 场的玩家侧边栏一并拆掉——现加 `getScoreboard() == state.board` 守卫
- **重复 `show()` 时旧计分板对象清理**：此前直接覆盖 map 条目，旧 `Objective` 从未注销；现显式 `unregister()`
- **村民计数从每玩家一次降为每竞技场一次**：`getAliveCount()` 是全量遍历 + 逐只 `isValid()`，此前每秒按玩家数重复执行；现改为每轮建一次不可变快照再分发给各玩家

### Added（2026-11-03 — 测试基础设施与回归套件）

- **测试依赖三件套（`pom.xml`，全部 test scope）**：`mockbukkit-v1.21:4.45.0`（其 pom 声明 paper-api `1.21.4-R0.1-SNAPSHOT`，与本项目主依赖同级）、`mockito-core:5.8.0`（inline mock maker，JDK 21 的 byte-buddy agent 门槛经探针实测通过）、`junit-jupiter` 5.10.1 → **5.12.1**（MockBukkit 4.45.0 的运行时声明，对齐后全链路只有单一 junit-platform 1.12.1）
- **不升 MockBukkit 4.116.3 的理由**：它声明 `junit-jupiter-api:6.0.3`，会把整个构建拽到 junit-platform 6.x 线；4.93 / 4.100 / 4.110 本地只有 jar 没有 pom，无法离线核验，一律弃用
- **离线可解析**：`mvn -o dependency:tree -Dscope=test` 干净通过，构建全程未联网；`paper-api` 保持 `provided`，MockBukkit 的传递依赖被本项目自身声明覆盖
- **发布产物未被污染**：JAR 内匹配 `mockbukkit|mockito|junit|bytebuddy|hamcrest` 的条目数为 **0**，`plugin.yml` 的 version 仍为 `1.1.0-SNAPSHOT-fix2`
- **夹具 `BareHost`**：`onEnable` 为空的插件宿主，供纯配置/菜单类测试使用。注意 `MockBukkit.load` 返回的是生成的 `BareHostProxy extends BareHost` —— `instanceof` 与 `getDataFolder()` 可用，但 `getClass()` 不等，已写进 javadoc，避免后人写 `assertEquals(BareHost.class, host.getClass())`
- **夹具 `MockBukkitTestBase`**：mock/unmock 生命周期、`loadBareHost()` / `loadFullPlugin()` / `loadFullPluginWithArenas()` 三种宿主加载、世界供给、日志捕获。MockBukkit 是进程级单例，故该类**按设计不线程安全**
- **规模：44 个新增测试类 / 572 条新增用例**，全量从 round1 结束的 97 → **669**；4 波次 / 12 个 Agent 装配，索引 51 个测试类。行为级覆盖 **50 / 75 = 66.7%**，剩余 25 个 = 18 个零引用 + 6 个仅 mock / 枚举常量 / 注释 + 1 个部分覆盖（`arena/ArenaInstance` 只有静态方法有断言）
- **`JavaPlugin.getPlugin()` 的可测性突破**：Paper 1.21.4 的该方法**不走 `Bukkit.getPluginManager()`**，而是从类加载器反查 `ConfiguredPluginClassLoader`（`javap -p -c` 字节码确认：`instanceof` 不匹配即抛 `IllegalArgumentException`），MockBukkit 的 loader 永远满足不了，`BareHost` 与 `loadFullPlugin()` 都失败；但它是**静态方法**，可用 `mockStatic(JavaPlugin.class)` 钉成「已启用的 `BareHost`」绕开 —— 五个配置菜单由此首次可构造、渲染、点击与 `reopen()`，成本约 2.7 s 每测试类一次。**这一条推翻了 round2「构造函数硬阻塞、判为不划算」的结论**
- **两条更便宜的捷径已被排除**：普通 `mock(HilltopVillagePlugin.class)` 撞 `IllegalPluginAccess`（`reopen()` 里的 `registerEvents`）；补 `isEnabled()=true` 撞 `getPluginLoader()` 返回 null 的 NPE。故 `plugin` 必须是「已启用的真实插件」，`BareHost` 是最便宜的那个
- **`PlayerMock` 背包是 41 格不是 36 格**：`getStorageContents()` 只报 36，填满 36 格后 `addItem` 会把新物品塞进 36–40 号空槽并返回空 overflow —— 这正是 round2 判定「商店溢出不可构造」的真正原因；填满 41 格即立刻返回真溢出，且材质无需相同、PDC 不会被合并吞掉
- **`mockStatic` 不外泄**：本类跑完后接 62 条既有 shop 测试 → `74/74` 绿，证明静态桩不向下游类泄漏；但它是线程局部的，若将来开启 surefire `parallel` 需重新验证，当前单 fork 串行为安全状态

### Changed（2026-11-04 — 审计框架、变异纪律与假绿硬约束）

- **round1 全库审计**：14 个源码切片 + 16 条 unverified 共 30 个审计单元，产出 **103 条 finding**；其中 **28 条**进过锐评波（Skeptic / Devil's Advocate / Arbiter / 交叉模式扫描四通道，早批次 10 条中撤销 3 条 = 撤销率 30%）；判为可修的 **11 条缺陷全部修复并经独立验收 PASS**（0 FAIL、0 `escalation_required`），这 11 条本身落在 16 个 Java 文件 / 366 行；全轮（含「把剩下的都修完」追加批次 fix-036~039）合计改动 **36 个文件 / 1906 行**，测试 94 → 97
- **主Agent自己的补丁同样送验**：`slice-09-logic-003` 引入的 `waveSpawning` 标志，主Agent 自行在 `endGame()` 补的复位行被 `verify-013` 的探针证明无效（`spawnWave` 抛异常时没有任何代码会调到 `endGame()`，标志永久卡 true），最终改为在真实调用点加 try/catch、复位后原样上抛
- **round2 测试建设**：572 条新用例；自查变异 **114 次**，独立复核抽样 **15/15 精确复现**，被证明太弱并修正 **8 次**；假绿 / 空洞 / `@Disabled` 测试 **0**，生产代码残留改动 **0**（除 `round2-12` 声明的 1 行）
- **变异纪律**：每条修复都要有一次「破坏它 → 测试必须变红」的证明。round3 累计 **22 次变异 / 20 次捕获**，2 次未捕获经证明均为**等价变异**（`GameRulesMenu` 的 `spawn-radius` 三道冗余守卫，去掉任一道行为不变），拒绝为等价变异硬造测试——那等于测一个不存在的代码形状
- **反向变异（本轮方法论提升）**：除「撤销修复」外，另做「把修复改成过度收紧 → 测试必须变红」，如 join 守卫改 `if (true)`、`isPending()` 改恒 true。常规做法只验「修好了」，很容易写出过度收紧的实现而测试照样绿；这两次反向变异证明守卫在**双向**都正确
- **三条真实假绿路径被写成硬约束**：① MockBukkit 把 `UnimplementedOperationException` 转成 **SKIPPED 而非 FAILED**，Maven 照样 `BUILD SUCCESS`（初次探针即踩到 `Skipped: 1` + 成功行）；② Maven 增量编译会静默跳过被变异的源文件（`Nothing to compile - all classes are up to date`），每次变异后须确认输出含 `Compiling 75 source files ... target\classes`；③ 删除测试源后 `target/test-classes` 残留陈旧字节码仍在跑，须连同类文件一起删
- **全量三跑（组 D 时点）**：`Tests run: 718, Failures: 0, Errors: 0, Skipped: 1`，三跑条数逐字一致；组 E 补测 `MonsterPropMenu` 后为 **723**，组 F（R-9 修复 + R-11 补测）后最终为 **730**（`mvn clean test -o` → `[WARNING] Tests run: 730, Failures: 0, Errors: 0, Skipped: 1` / `BUILD SUCCESS` / 29:53 min，编译含 `Compiling 75 source files ... target\classes` 与 `Compiling 63 source files ... target\test-classes`；162 份 XML 独立累加 `tests=730 / errors=0 / skipped=1`）。第 3 跑是计划外追加，用于证明变异还原后生产代码确实复绿；还原用 `_mutation-backups` 里的原文件 `cp`，**未用 `git checkout`**（这些修复从未提交，checkout 会把它们整个抹掉）；组 F 三次变异同样 `cp` 还原 + `md5sum -c OK`
- **skip 审计不采信聚合行**：逐叶子解析三跑的 154 份 surefire XML 独立累加，唯一 skip 是 `YamlFilesTest.writeToReadOnlyFileThrows` 的 Windows 只读语义 `TestAbortedException`（round2 既知 skip，本轮无新增）；全库 `UnimplementedOperationException` 命中 **0**，`@Disabled` 计数 **0**
- **`JAVA_HOME` 编译错配（R-6，已修复）**：`pom.xml` 新增 `maven-enforcer-plugin:3.5.0`（`require-jdk-21` 绑定 `validate`、`[21,)`），裸 `mvn` 在最早阶段即失败并给出含真实 JDK 版本 / `JAVA_HOME` / 两条解决路径的中文提示（改前 JDK 17 只有编译期 `错误: 无效的目标发行版：21`，改后 `RequireJavaVersion failed` 在 `validate` 拦下，主Agent 独立复现 `PIPE_EXIT=1`）；新建 `build.sh` 自动探测并切换到 JDK 21（`HV_JDK21_HOME` / `HV_JDK21_GLOB` 可覆盖，零参数默认 `clean package`），新建 `.mvn/jvm.config` 三行 UTF-8 参数保证中文提示不乱码（不用于切 JDK）；pom 属性 `<java.version>` 改名为 `<java.target.version>` 并同步 compiler source/target，因为同名属性会遮蔽 JVM 系统属性 `${java.version}`、首次负面实测把「当前检测到」误报成 21（JVM 实际 17），改名后才恢复真实的 17.0.20.1——此 bug 只有做负面验证才能发现。正面证据：默认 shell 为 JDK 17 时 `./build.sh -o compile` 自动探测 JDK 21 → `Rule 0: ... passed` / `BUILD SUCCESS`；删 `inputFiles.lst` 强制重编译得 `Compiling 75 source files with javac [debug target 21]` / `BUILD SUCCESS`；显式 JDK 21 下 `mvn -o -B compile` 与 `mvn -o -B test-compile` 均 `BUILD SUCCESS`（enforcer 约 +0.3–0.5 s）；JDK 25 的 `validate` 通过（符合 `[21,)`）；脚本四条分支与 `bash -n`、三文件 LF 行尾均已核验。未选 `maven-toolchains-plugin`（依赖项目外 `~/.m2/toolchains.xml`，缺文件只警告不生效）。版本号仍为 `1.1.0-SNAPSHOT-fix2`，未 bump
- **R-6 残余如实保留**：新机器 / CI 首次需联网下载 enforcer 3.5.0（本机已预热、`-o` 可用）；`build.sh` 依赖 bash + `cygpath`，原生 cmd / PowerShell 不能直接执行；默认 glob 写死 `/d/Java/zulu21.48.17-*` 且只接受主版本 21；JDK 22+ 未验证全量编译与测试；`-Denforcer.skip=true` 仍可绕过。构建段文档同步与新入口全量回归**均已完成**：`CLAUDE.md` / README / README-EN / `docs/07-guides/JDK21-BUILD-GUIDE.md` 等已改为推荐 `./build.sh`（`.claude/agents/*.md` 与 `ralph*` 仍写手动 export，属次级漂移留待作者裁决）；默认 shell 为 JDK 17、未显式 `export` 时 `./build.sh -o clean test` → `Tests run: 730, Failures: 0, Errors: 0, Skipped: 1` / `BUILD SUCCESS`（`_logs/final-r6-verify.log`）。证据报告见 `docs/reports/round3/round3-06-r6-toolchain.md`
- **生产代码零漂移**：9 份受测文件的 md5 与 `_mutation-backups/*/original-md5.txt` 逐份一致，且与主Agent前置核验逐字相同；恢复后再跑 clean 全量复绿作为反证

### Fixed（2026-11-04 — 补完 6 条无守护的审计修复）

- **`slice-06-logic-002`（跨场 join 判负链）→ `HilltopCommandHandlerArenaGuardTest`（4 条）**：RUNNING 时跨场 join 不得把原场推成 `DEFEAT`，断言直接盯缺陷的真实伤害（队友被记负）而非实现细节；配「WAITING 仍须换场成功」的反向对照，撤销修复（恢复 `&& inCurrentWorld` 合取）即红
- **`slice-03-logic-004`（禁用 QUEUED 场播报）→ 同测试类**：`arena-disabled-ok` 与 `arena-already-disabled` **正反成对**断言，单改任一半都会被抓住；另配「真·已禁用仍应报 already-disabled」对照，防「恒报成功」的过度修复
- **`slice-08-logic-002`（`spawn-radius-max` 减号把值往上跳）→ `SpawnRadiusMaxRightClickTest`（11 条）**：min=100、max=40 时按右键由 40 跳成 105（+65）的缺陷签名被精确复现；修复后的正确行为是**不动**，而「只钳位不改 setter」的克制版同样满足，故另设「正常区间仍 -5」「恰在有效下界时放行」「连续右键单调不增」「全组合 `after <= spawnMax`」四条区分实现
- **`slice-08-logic-004`（主手槽缺 SHIFT 分支）→ `MonsterMainHandShiftTest`（12 条）**：输入值刻意选 `BLAZE_ROD` —— 它不在 `WEAPON_CYCLE` 的 12 个预设内，使「循环到下一个预设」的实现无法偶然对上；另守住 LEFT 仍循环、空输入回落、表外材质回落、MIDDLE 行为与副手对照
- **`slice-08-logic-009`（菜单 `else` 兜底收窄为显式 LEFT）→ `ConfigMenuLeftWhitelistTest`（12）+ `WaveMenusLeftWhitelistTest`（4）+ `MonsterPropMenuLeftWhitelistTest`（5）**：`grep` 实测源码共 **6 处** `else if (clickType == ClickType.LEFT)`，本轮守护 **6 处全覆盖**（组 D 时点行号 `GameRulesMenu:139` / `ItemSettingsMenu:194` / `MonsterSettingsMenu:336` / `NodeSettingsMenu:97` / `WaveSettingsMenu:331` / `WaveSettingsMenu:568`，最后一处见下条；组 F 插入 3 行注释后 `WaveSettingsMenu` 两处平移为 `:334` / `:571`）；每处跑 9 种非白名单 `ClickType`（比 round1 审计点名的 5 种更严），并成对断言同一槽位按 LEFT 仍 `+step`——只断言「MIDDLE 不改」的话，把整段分支删掉的实现同样会绿；**组 F 另在 `WaveSettingsMenu.java:264` 新判定并修复了同型第 7 处**（round3 R-9，从未被编号为 round1 finding，**不计入上述 round1 的 6 处**）——新增 `WaveMenusLeftWhitelistTest.MonsterRowCountSlot` 3 条，M1 还原裸 `else` → `Tests run: 7, Failures: 1`（`expected: <6> but was: <7>`，只红正向半），M2 改成 `else if (LEFT && false)` → `Tests run: 7, Failures: 1`（`expected: <7> but was: <6>`，只红反向对照半），正反落点相反喵
- **`slice-12-dc-004`（商店溢出掉落被重置销毁）→ `ShopOverflowPaidDropTest`（6 条）**：拆成打标侧 / 跳过侧 / 端到端三组 `@Nested`。打标侧钉住 `ShopListener` 的**两个** `dropItemNaturally` 调用点都写 `PAID_DROP_KEY`；跳过侧钉住 `ArenaInstance.clearDroppedItems` 按该键放行而其余仍删（含「带别的 PDC 键仍被删」对照，判据是键本身而非「有 PDC 就放行」）；端到端走真实 `arena1` 的「购买溢出 → `resetNow()` → 货还在」
- **测试量 669 → 730（round3 共 61 条）**：组 A 4 条 + 组 B 39 条 + 组 C 6 条 + 组 E 5 条 + 组 F 7 条（`WaveMenusLeftWhitelistTest.MonsterRowCountSlot` 3 + `WavePaginationClampTest` 4）；组 E 时点为 723，组 F 全量 `Tests run: 730, Failures: 0, Errors: 0, Skipped: 1`，新类 `Skipped` 全 0，唯一 skip 仍是 `YamlFilesTest.writeToReadOnlyFileThrows` 的 Windows 只读语义，`UnimplementedOperationException` 命中 0
- **`MonsterPropMenu#addDblItem` 补测完成（关闭 R-7）**：`slice-08-logic-009` 的第 6 处调用点（组 D 时点 `WaveSettingsMenu.java:568`，组 F 后为 `:571`）此前无测试守护，还原成裸 `else` 时全量仍绿；新增 `MonsterPropMenuLeftWhitelistTest`（5 条 / 3 个 `@Nested`，覆盖 slot 19 health / 46 explode-multiplier / 47 经 `addIntItem` 委托），全量随之 718 → **723**，第 6 处由此**双向**受守护——正向变异（还原裸 `else`）3 条红并精确复现缺陷签名 `3.0→3.5` / `40→50` / `60→80`，反向变异（`LEFT && false`）恰红在同 3 条的**对照半**，落点相反，证明正反两半各自独立承重
- **R-9 生产代码修复（行为变更，关闭 R-9）**：`src/main/java/com/hilltopvillage/config/menu/WaveSettingsMenu.java:264` 的 `WaveDetailMenu` 怪物组成行由裸 `} else {` 收窄为 `} else if (clickType == ClickType.LEFT) {`，并补中文注释说明「`slice-08-logic-009` 同型漏网点（round3 R-9）」——此前 MIDDLE / DROP / NUMBER_KEY / SWAP_OFFHAND / DOUBLE_CLICK 等非白名单点击会静默把该怪种数量 +1 并写配置，修复后这些点击不再改怪物数量，LEFT / RIGHT / SHIFT 分支行为不变；同文件显式 LEFT 白名单由此为 3 处（`:264` / `:334` / `:571`），剩余 `:84` / `:91` / `:111` 三处裸 `else` 并不同时具备「接收 `ClickType`」与「写配置」两个条件，非同型缺陷故未动；守护见 `WaveMenusLeftWhitelistTest.MonsterRowCountSlot` 3 条与 M1/M2 正反两次变异（各 `Tests run: 7, Failures: 1`，落点相反），三次变异均 `cp` 还原 + `md5sum -c OK`，当前 md5 `87a7b8742651485d64aaaa9d0847a6de`
- **波次列表行点击白名单收窄（行为变更，作者 2026-11-04 裁决）**：`src/main/java/com/hilltopvillage/config/menu/WaveSettingsMenu.java:91` 的波次列表行由裸 `} else {` 收窄为 `} else if (clickType == ClickType.LEFT) {` —— 此前 MIDDLE / DROP / NUMBER_KEY / SWAP_OFFHAND / DOUBLE_CLICK 等**所有**非 SHIFT 点击都会关掉当前菜单并打开波次详情菜单（不写配置，属「丢出键 / 中键意外跳转」的 UX 缺陷），修复后只有 LEFT 打开详情；SHIFT_LEFT / SHIFT_RIGHT 的删除波次分支（P2-33 的 `ConfigManager.removeWave` 记账）一字未动，行为保持不变。守护见 `WaveMenusLeftWhitelistTest.WaveRowLeftOnly` 3 条（正向半扫 8 种非白名单 ClickType，断言 `admin.getOpenInventory().getTopInventory().getHolder()` 仍是同一个列表菜单实例；反向对照半断言 LEFT 后 holder 变成 `WaveDetailMenu`），M1 还原裸 `else` → `Tests run: 10, Failures: 1`（只红正向半），M2 改成 `LEFT && false` → `Tests run: 10, Failures: 1`（只红反向对照半），落点相反；全量 `Tests run: 733, Failures: 0, Errors: 0, Skipped: 1`（唯一 skip 仍是 `YamlFilesTest.writeToReadOnlyFileThrows` 的 Windows 只读语义），变异均 `cp` 还原 + `md5sum -c OK`，md5 `49e9f5a8f28cd1a12db13fd29149fe82`
- **R-11 分页钳制补测（关闭 R-11）**：新建 `WavePaginationClampTest`（4 条 / 2 个 `@Nested`：`RealPaginationPath` 2、`SinglePageStaleValue` 2）；策略 A 不碰反射——往 `GameConfig#getWaves()` 放 22 个最简 `WaveConfig`（`SLOTS_PER_PAGE=21`），真实点 slot 53 翻到第 1 页，再把波数删回 3 并 `reopen()`；策略 B 在 3 波次场景用反射把私有 `page` 设为 `7` / `-3`，验证上界与下界；判别性断言用外部可观测的渲染结果（slot 9 波次图标与 clickAction），不直接断言私有字段。M3 把 `WaveSettingsMenu.java:53` 的 `page = Math.max(0, Math.min(page, totalPages - 1))` 降级为 `page = Math.max(0, page)` → `Tests run: 4, Failures: 2`，红在两条正向场景、两条对照半仍绿；本轮未改分页生产逻辑
- **R-2 商店「部分可容纳」切分形态补测（round4 组 D）**：新建 `src/test/java/com/hilltopvillage/shop/ShopOverflowPartialFitTest.java`（6 条 / 3 个 `@Nested` + 1 条夹具自检）。此前 `ShopOverflowPaidDropTest` 只观测「背包全满 → 整件溢出」，**未**观测「背包还能装一部分」时 `addItem` 的拆分语义。探针先钉死三条夹具前提：① `BOW` 的 `maxStackSize` 是 **1**，plan 原文假设的「BOW 60/64 半格」在 MockBukkit 里根本不存在，故改用 `ARROW` / `EMERALD` 造半格；② `countCurrency` 只看 `getStorageContents()`（36 格）+ 副手，**不含 4 个护甲槽**；③ `addItem` 先填空槽、再并半满叠。断言一律「包内合计 + 地上件数 + 地上数量」三点并列（购买侧：半格 56 + 买 8 → 恰好 64 零掉落；半格 60 + 买 8 → 并入 4、地上恰好 1 件 4。退款侧：残留 70 + 退款 15 → 整件 15 落地；副手 60 + 退款 15 → 并入 4、地上恰好 1 件 11），只断言「地上有货」会漏掉「重复掉落」与「入包部分被吞」两种相反缺陷。残余风险如实登记：`InventoryMock` 只按材质合并且合并会丢 PDC，故「入包部分是否保留 PDC」在本环境**不可断言**
- **F-3 删波次「菜单半边 × 磁盘半边」接缝补测（round4 组 D，缺口真实存在）**：对账确认两半各自有测试却从未接上——`WaveMenusLeftWhitelistTest#shiftClicksStillDeleteTheWave` 的 `ConfigManager` 是 `MenuTestSupport.config(cfg)` 造的 **mock**（只能证「菜单会调 removeWave」，证不了「磁盘干净」）；`ConfigManagerTest#removeWaveTombstonesTheKeyOnDisk` 是**直接调 API**、不经菜单。新建 `src/test/java/com/hilltopvillage/config/menu/WaveDeleteEndToEndTest.java`（3 条），用**真实** `ConfigManager` + 真实 `WaveSettingsMenu` 走「点 SHIFT_LEFT → 真实 removeWave → 真实 saveToFile → 磁盘 → 新开 ConfigManager 重载」。关键断言是 `hasUnsavedChanges()` 必须在删除后变 true —— 否则主菜单保存按钮（`MainConfigMenu` 的 LIME_WOOL）不出现，`removedWaveKeys` 墓碑永远不会落盘
- **F3-D1（新发现真实缺陷，本轮只测不改）**：`GameConfig` 构造器**无条件预置**波次 1 / 5 / 10，而 `ConfigManager#loadFromFile` 的 `wavesBeforeReload` 回填只在「内存里已有该编号」时才留 —— 于是**删除出厂波次 1/5/10 后重载（`loadFromFile` 或重启）必然复活**，且下一次 `saveToFile()` 会把它写回磁盘。探针实测：`removeWave(1)` → 磁盘 `[default,5,10]` → `loadFromFile()` → 内存 `[1,5,10]` → `saveToFile()` → 磁盘 `[default,5,10,1]`。同理，管理员手写进 `game-settings.yml` 的**新**波次（非 1/5/10）在**首次**载入时会被静默丢弃且零日志（`wavesBeforeReload` 里没有它），随后任一次保存都不会写回。两条都属于「配置权威来源分裂」，建议单独立项裁决（改 `GameConfig` 预置策略或让 `loadFromFile` 无条件下从磁盘补建）
- **PDC namespace 全库复核（round4 组 B 发现的隐患，组 D 复核影响面）**：生产代码有**两种**键构造风格 —— `new NamespacedKey("hilltopvillage", ...)` 字面量（`ShopListener.PAID_DROP_KEY` 及 5 个 AI goal 的 `GoalKey`）与 `new NamespacedKey(plugin, ...)` 宿主派生（`NpcManager` / `ShopManager` / `WaveManager` / `HammerListener` / `FireballListener` / `NodeListener` / `VillagerSystem` / `VillagerPreviewManager` / `SetupWizard`）。后者在 `loadBareHost()` 默认名 `HilltopVillageTestHost` 下会得到 `hilltopvillagetesthost:*`，与线上的 `hilltopvillage:*` **不是同一个键**。逐个复核结论：真正受影响（用默认 `loadBareHost()` 且断言宿主派生键）的只有 **1 个类 2 处**（`ShopManagerTest:161` / `:198` 用 `new NamespacedKey(host, ...)` **跟随宿主名**因此自洽）；`NpcManagerTest` 全类已显式命名 `HilltopVillage` 并加断言钉住（`:134`），`ShopListenerEntityTest` 同（`:344` / `:489` 直接写字面量键），这两个类**不受影响**。`ShopOverflowPaidDropTest` 的 6 条**亦不受影响**：`PAID_DROP_KEY` 是硬编码字面量，与宿主无关。故 round3 的守卫**建立在线上真实存在的键上**，不需 fix
- **测试量 733 → 802（round4 共 69 条：组 A 0 + 组 B 53 + 组 C 7 + 组 D 9）**：组 B 为 `NpcManagerTest` 37 条 + `ShopListenerEntityTest` 16 条；组 C 为 `ArenaGuardMultiPlayerTest` 3 条 + `ArenaStatusLoadingObservabilityTest` 4 条；组 D 为 `ShopOverflowPartialFitTest` 6 条（购买侧 2 / 退款侧 2 / 对照 1 / 夹具自检 1）+ `WaveDeleteEndToEndTest` 3 条；组 A 只交付 `build.cmd` / CI workflow / GUIDE，无 Java 测试。全量三份独立证据一致 `Tests run: 802, Failures: 0, Errors: 0, Skipped: 1`（组 D 变异前、组 D 全部还原后终跑、主Agent 后台复核）
- **仍无守护的残余项（如实记录，不声称已覆盖）**：`slice-08-logic-006`（分页钳制）与 `WaveDetailMenu` 怪物行同型 `else` 两项**已由组 F 关闭**，不再列为缺口；仍成立的是 `slice-08-logic-009` 的「4 处 vs 6 处」历史口径不一致（以源码 grep 的 **6 处**为准）与 `slice-12-dc-004` 只观测「全满 → 全溢出」、未观测「部分可容纳」的切分形态；组 F 新产生的残余 5 条：① R-9 只覆盖 `wave 1` 第一个怪种行（slot 27），所有行共享同一循环与 lambda，将来若有行手写 lambda 绕过则抓不到；② R-9 未单独测 SHIFT_LEFT / SHIFT_RIGHT 的聊天输入与属性页路径；③ R-11 删波次是直接改 `GameConfig` map 模拟，生产路径为 `ConfigManager.removeWave` + `reopen()`，测试钉住渲染入口契约但不覆盖 `removeWave` 的磁盘记账；④ `WaveSettingsMenu.java:91` 的非 SHIFT 兜底会用 MIDDLE / DROP 打开详情菜单，不写配置、按派发要求未动，是否算 UX 问题需作者裁决；⑤ 其它文件仍有任意 ClickType 都执行动作的 `ct -> ...` 形状（`MonsterSettingsMenu.java:146/177/296`、`WaveSettingsMenu.java:390/613`），属既有设计意图且超出本轮单文件约束，未修改

### Docs（2026-11-04 — 三轮审计与测试报告归档）

- **`docs/reports/round1/`（88 份）**：侦察切片表、30 个审计单元产物、4 通道锐评、39 份修复报告、11 份独立验收报告与 `round1-ledger.md`；`round1-final-report.md` 只做排序 / 索引 / 统计，不改子 Agent 措辞与裁决
- **`docs/reports/round2/`（18 份 + `_mutation-backups/`）**：测试基础设施报告、9 份分层测试报告、独立质量审核、失败定因、最终验证与 `round2-ledger.md`；`round2-final-report.md` 逐类列出 51 个测试类的用例数与其守护的修复 ID
- **`docs/reports/round3/`（组 D 时点 5 份；组 E / F 追加 `round3-04-tests-monsterprop.md`、`round3-05-r9-r11.md` 后共 7 份，另含 `_logs/`、`_mutation-backups/`）**：三组测试报告 + `round3-ledger.md` + `round3-final-report.md`（含逐条守护矩阵、残余风险 R-1~R-13、假绿风险专章）+ 组 E 补测报告 + 组 F 的 `round3-05-r9-r11.md`（R-9 生产修复与 R-11 分页钳制补测，最终报告已追加 §9 组 F 追记、ledger 已追加 §8）；`_logs/` 留存组 D 三份全量日志与三套 surefire XML 快照（各 154 份），并留存组 F 全量日志 `r9-r11-full.log`
- **产物边界**：round1 / round2 的最终报告只做排序、索引与统计，不改子 Agent 的措辞与裁决；round3 的最终报告在装配之外另做守护矩阵的**独立核验**（逐条打开测试源码找判别性断言，不采信组的自我声明）。凡「组自报」的变异结论一律标注来源；判别力不足的证据（如 round3 run 2 不 clean、`Nothing to compile`，本质只是重跑同一批字节码）在报告内显式写明而非当作独立结论

### Fixed（round5 P1 — waves 波次载入重建（F2 + 症状 2））

- **【P1】F2 compositions 丢失修复**：`loadFromFile()` 的 `waves` 段改为「波次的所有字段都从磁盘重建」，不再「就地覆盖内存里已有的 `MobComposition`」——旧实现对内存里不存在的怪种走 `comp == null → continue`，于是经 GUI 给第 1 波新增 `flying-dropper` 后 reload 会静默丢回两个怪种（Add Monster 在出厂配置下的唯一起步用法，开箱即坏）
- **【P1】症状 2 磁盘新波次被丢弃修复**：`wc == null` 分支不再依赖 carry-over（拿不到就 `continue`），改为按磁盘内容新建 `WaveConfig`——管理员手写或 GUI 新建的波次（如 `waves."7"`）现在真的会在 reload 后生效，而不是无声消失
- **新增 `isSpawnableMobKey` 判据**（`ConfigManager`）：合法怪种 = monsters 段的键（小写归一）∪ 可生成的 `EntityType`（复用既有 `parseSpawnableEntityType`，含 `Mob` 子类守卫）。此前判据只认「内存里已有该怪种」，既丢内容又打一条说反话的 warning；现在 `waves.3.compositions.zombie` 这类**运行时本来就支持**的写法会被接受，`arrow` / `item` 等非 Mob 类型仍被拒
- **新增 `readWaveInt` 类型判别**：`total-mobs` / `count` / `weight` 一律走 `contains` + `instanceof Number`，禁止裸 `getInt`——后者对缺键与字符串 `"9"` 都静默返回 0，会让该波空转（`total-mobs: 0`）或整波落进原版僵尸兜底（`weight` 全 0）
- **值层默认值**：`total-mobs` 缺键回落 `waves.default` 的上限；`count` 缺键默认 `0`；`weight` 缺键默认 **`1`**（不是 0，否则 `WaveManager` 的 `totalWeight <= 0` 会把整波换成 `spawnDefaultWave` 的原版僵尸）；负数钳 0
- **W2 静默面封堵（新增 warning）**：只写 `total-mobs`、没有 `compositions`（或全部条目被丢弃）的波次会打一条汇总 warning 点名该波——改造前它会变成一个「波次出现了但刷 5+2×波数只原版僵尸」的波次且控制台零输出
- **warning 文案诚实化**：`waves.*.compositions` 的未知键文案改为「既不是 monsters 段键、也不是可生成的原版实体类型」，不再断言「合法键就是 monsters 段那些键」
- **测试**：新增 `WavesKeySetAlignmentTest`（6 条）——出厂模板五层断言（键集 / 逐怪 count·weight / 两合计 / `total-mobs`）、手写波次按磁盘建、加怪种往返、原版实体键接受与非 Mob 键拒绝、`weight` 缺键默认 1、W2 warning 存活证明
- **本阶段范围**：只修 F2 与症状 2。症状 1（删除波次后 reload 复活）留给后续阶段；`wavesBeforeReload` 本阶段保留（现为死变量），保存侧差量删除与 `waves.default.compositions` 对齐同样未在本阶段实施

### Fixed（round5 P2 — waves 键集对齐（症状 1）+ F3 default 配比对齐）

> 归因说明：本节 P2 只负责「键集归磁盘」（`getWaves().clear()` + 按磁盘重建 + 删 `wavesBeforeReload` 死变量）与 F3（`waves.default.compositions` 对齐）。下面列的第一条**行为变更**（未保存的删除被 reload 撤销）的**主体在 P1 就已经发生**——P1 把数字波次改成「无条件按磁盘新建」之后，自建波次那一支的删除复活就已成立；P2 只是让出厂波次 1/5/10 也跟上同一语义，并把键集补齐。

- **【P2】症状 1 键集对齐**：`loadFromFile()` 的 `waves` 段在按磁盘重建前先 `getWaves().clear()`，于是内存波次表严格等于磁盘上的数字键集——磁盘删掉的波次（含构造器预置的 1/5/10）在 reload 后不再复活，也不再被下一次 `saveToFile()` 原样写回磁盘。清空只落在 `if (waveSection != null)` 之内：文件不可用（只读降级）与整段 `waves` 缺失两条路径仍保留内存值 / 出厂三波
- **重建形状改为「先建后换」**：数字波次先在局部 map 里全部解析完，再用一次 `clear()` + `putAll()` 换入活 Map——解析期抛异常时内存保持进场时的状态，不会留下半张波次表（半张表 + 快照未推进会让保存按钮亮着，一按就把半张表固化进磁盘）
- **删除 `wavesBeforeReload` 死变量**：P1 之后它已无读者，连同解释 carry-over 的 7 行注释一并删除；`activeConfig = new GameConfig()` 与 F-002 的「用磁盘重建内存」注释保留
- **新增 G4 告警**：`waves` 段存在但没有任何数字键（只剩 `default`，或 `waves: {}`）时打一条 warning 说明「所有波次将回落 `waves.default` 配比」——`clear()` 让这条路径第一次可达，此前它零输出
- **【F3】`waves.default.compositions` 键集对齐**：模板波次（`defaultWave`，`victory-waves: 20` 而模板只定义 1/5/10 ⇒ 16/20 波实际使用它）的配比改为严格按磁盘段重建——磁盘新增的怪种现在真的生效（此前被判「未知」丢键，还打一条说反话的拼写错误 warning），磁盘删掉的怪种不再被构造器预置复活
- **F3 三条守卫**：① `waves.default.compositions` 子段不存在（含裸头 / 列表 / 标量）时**不清空**，保留出厂 3 条配比；② 子段存在但解析后为空（`compositions: {}` 或全部键不可用）时**同样不清空**，并告警指向正道 `waves.default.total-mobs: 0`；③ `defaultWave` 为 null 时在 `loadFromFile()` 最前面归一化（`snapshot()` 对它无 null 保护，降级载入路径会直接 NPE）
- **F3 修 `waves.default.total-mobs` 的裸 `getInt`**：`total-mobs: '12'`（带引号）此前静默读成 0、`-3` 读成 -3，两者都会让所有缺 `total-mobs` 的数字波次拿到非法上限（整局零刷怪且零日志）；现走与数字波次同一个 `readWaveInt`（非数值回落当前值 + 告警，负数钳 0 + 告警）
- **测试**：新增 `DefaultWaveAlignmentTest`（8 条 / T1 ~ T7 + G4）——删除不复活、新增生效且不误报拼写错误、段缺失保留出厂配比（守卫 A）、显式空保留 + 告警（守卫 B）、`total-mobs` 字符串/负数不静默变 0、单条坏键不连坐、null `defaultWave` 降级载入不 NPE（守卫 C）、空波次表 G4 告警；`ConfigManagerTest` 的 carry-over 用例改名并更正描述（断言未变）

### Fixed（round5 N1 + N2 — 配置 GUI 保存回执与波次墓碑寿命）

- **【N1】配置 GUI 的「保存并应用配置」不再谎报成功**：`MainConfigMenu` 的保存按钮此前直接丢弃 `ConfigManager#saveToFile()` 的返回值，随后无条件播「所有配置已保存」+ 生效时机提示 + 成功音效。该返回值涵盖三条静默失败路径（`game-settings.yml` 闸门只读、settings 的 IO 失败、settings 写成功但 `monsters-config.yml` 写失败），丢弃后管理员在磁盘完全没变的情况下被告知保存成功，重启后设置消失且无任何线索。现改为消费返回值：成功走原文案与原音效；失败播新增的 `config.menu.main.save-failed`（说明「改动仅在内存、重启丢失、请查控制台日志，含磁盘空间/权限/只读降级三条线索」）并改播铁砧落地音 `BLOCK_ANVIL_LAND`。与 `HilltopCommandHandler` 的 `repairitem`、`SetupWizard#autoSave` 既有纪律对齐
- **【N2】波次删除记账（`removedWaveKeys`）的寿命收窄到「它那一次 `game-settings.yml` 写盘」**：`saveToFile()` 原先把 `removedWaveKeys.clear()` 放在「monsters 也写成功」分支（F-001 门控），于是「settings 已落盘、墓碑仍存活」的中间态能跨过一次完整保存。墓碑判据读的是**当前内存**（`!getWaves().containsKey(n)`），一旦中间发生 `loadFromFile()` / `revertAll()` 这类整份替换内存且不维护墓碑的操作，判据可能反向翻转（跳过清除 + 常规全量写回把该波次写回磁盘）。现改为 settings 写成功后立即清空；`lastSavedSnapshot` 与 `loadTimeNormalizationPending` 的 monsters 门控原样保留（F-001 要保住的「快照不推进 ⇒ 保存按钮继续亮 ⇒ 重试」只依赖快照，与墓碑无关）
- **失败分支日志诚实化**：`monsters-config.yml` 写失败时的 warning 由「keeping the unsaved-changes state **and pending wave deletions** so the next save retries them」改为只承诺「未保存状态」会被重试——墓碑已提前兑现，原先那句承诺的是波次删除的重试，在改动后必为假
- **测试**：新增 `MainConfigMenuSaveResultTest`（2 条，GUI 点击链双向：失败播 `save-failed` + `BLOCK_ANVIL_LAND` 且保存按钮仍可见，成功保留原文案与原音效）与 `WaveTombstoneLifetimeTest`（3 条：P2 之后 reload 不复活出厂波次的回归守卫、墓碑不得越界删掉管理员事后手写补回的波次、部分失败分支的 warning 不得再承诺波次重试）；变体验证 4 个（N1 回退为无条件成功 / 墓碑清空挪回 monsters 分支 / warning 文案回退 / 移除 P2 的 `getWaves().clear()`）全部按预期变红

### Fixed（round5 F4 + F5 — 节点增益空表语义 + 自定义怪种静默忽略）

- **【F4】`nodes.buff-effects` 键集归磁盘**：`loadFromFile()` 与 `mergeExternalChanges()` 的判据由「双重 `isEmpty()`」改为 `isSet()` + `isList()`——键缺失 = 出厂默认，键存在 = 磁盘权威（含显式空表）。此前 `buff-effects: []`（想关掉全部节点增益）与「全部条目都写错」两种输入都被当成「没配」，内存保留构造器预置的 `RESISTANCE:0 / REGENERATION:0`，紧接着的 `saveToFile()` 又把预置写回磁盘（删除无效 + 值静默回退两症齐发）。两份守卫必须同改：只改加载侧时「载入后手改成空表再点保存」仍会被内存旧值覆盖
- **【F4】消费侧同改（`NodeSystem#loadConfig`）**：删除「空表时补 `RESISTANCE:1` + `REGENERATION:0`」的分支。只改配置侧会让「磁盘空表 + 游戏里仍有增益」成为更难查的分歧，且补进来的 `RESISTANCE:1` 与预置的 `:0` 还不一致，玩家拿到的等级无从解释；空表现在等于「不施加任何增益」
- **【F4】新增两条告警**：`buff-effects` 写成标量/段（`getStringList` 会静默返回空表，若按显式清空处理，一次手滑的引号就会关掉全部增益）→ 保留内存值并告警；全部条目都被拒（每条已各有一条 warning）→ 补一条「结果为空表」的结论，否则「写错了」与「本来就想全关」在日志里不可区分
- **【F5】`monsters-config.yml` 的自定义怪种段不再静默**：`loadMonstersConfig()` 末尾新增 `warnUnsupportedMonsterSections()`——对「磁盘上有段、内存没有对应怪物」的键打一条汇总 warning 并点名。此前这条路径**零日志**：`YamlFiles#monstersConfigValidator` 只要求「每段含任一已知子键」，照抄一段、写全 20 个字段的文件完全合法，而加载循环只遍历内存键集（构造器闭集 3 键）根本不看它——同一错误在 `game-settings.yml` 的 `monsters` 侧是会报的，两份文件口径不一致
- **【F5】行为不变**：自定义怪种仍**不生效**（每个怪种需要代码里绑定的专用 AI，`WaveManager#spawnCustomMob` 的 `switch` 是闭集），本修复只补可观测性。warning 同时给出正道：要刷原版怪就在 `waves.<n>.compositions` 用 `EntityType` 名（运行时本来就支持）；判据对大小写不敏感、且只认「顶层是段」（与 validator 同口径，顶层标量与大写内置键都不报警）
- **测试**：新增 `NodeBuffEffectsKeyAuthorityTest`（7 条：显式空表清空内存 / 经保存后磁盘仍空且键保留 / 全部非法清空 + 结论告警 / 形态错保留内存 + 告警 / 外部修改合并尊重空表 / 反向对照「键缺失保留出厂预置」与「部分非法只留有效条目」）、`NodeBuffEffectsConsumerTest`（3 条：空表不施加任何增益 / 反向对照非空照常施加且 amplifier 取配置值 / 出厂配置仍给两条）、`MonstersConfigUnsupportedSectionWarningTest`（6 条：新段产生点名 warning / 行为不变 / 反向对照「出厂模板安静」与「大写内置键不报」/ 与 dc-002 告警互不吞没 / 顶层标量不报）
- **变异验证 5 次**（含 1 次反向）：撤销加载侧修复 → 红 4 条；只撤销合并侧 → 红 1 条（两份守卫各自独立承重）；撤销消费侧补默认值 → 红 1 条且两条反向对照仍绿；删掉 F5 告警调用 → 红 2 条正向、反向对照仍绿；把加载侧改成「无条件清空」（过度收紧）→ 反向对照红 2 条

### Fixed（round5 F6 — 外部修改白名单补 `spawning.spawn-radius-spread`）

- **【F6】`mergeExternalChanges` 白名单补 `spawning.spawn-radius-spread`**：该键「写有」（`saveToFile`）、「读有」（`loadFromFile`，缺键保留代码默认 3.0）、「有运行期消费者」（`WaveManager` 在 `spawn-radius-max` 未配置时用它做生成点随机扩散），但全库没有任何 GUI/命令编辑入口（`GameRulesMenu` 只暴露 min/max 与两个 cap）——却漏在外部修改合并白名单外。症状：载入配置后手改该键，再回 GUI 点保存，合并阶段跳过它，紧接着的全量回写用内存旧值把磁盘覆盖回去，手改静默消失且日志不区分是哪几个键被吞
- **补法沿用同段既有模式**：`hasNumber(disk, ...)` + 基线守卫（只有「内存值仍等于上次落盘快照」时才让磁盘值胜出），与相邻的 `spawning.spawn-y-offset` / `spawning.airdrop-max-per-dropper` 完全同款；键缺失或类型损坏仍是跳过并保留内存值
- **全量差集核对（写集合 72 项 vs 白名单 24 键 + 4 个 monsters 模式键 = 差集 48 项）**：除本键外，其余 47 项的判定为——**40 项有 GUI/命令入口**（`fireball.*` 全部数值与材质、`hammer.material`/`itemsadder-id`/`custom-model-data`/tier 三档 9 键/`aftershock-ticks`/`smash-timeout-ticks`/`cooldown-seconds`/`max-uses`、`min/max-players`、`victory-waves`、`wave-interval-seconds`、`nodes.base-health`/`buff-radius`/`self-destruct-multiplier`/`repair-amount-per-cost`/`repair-cost-item`、`spawning.spawn-radius-min`/`max`/`global-mob-cap`/`mob-cap-per-player`、`waves.*` 共 3 项）刻意排除，游戏内为权威；**7 项是死键**（`world-name`、`core.x/y/z`、`hammer.base-damage`、`hammer.effect-radius`、`hammer.visual-display-ticks`）——写有、读有、**零消费者**，真正的世界/核心来源是 `arenas.yml` 的 per-arena 定义，三个 hammer 键的模板注释自认「已废弃，不再读取」。死键不满足不变式的「有运行期消费者」合取条件，**不进白名单**（加进去只会强化「改它能生效」的错误暗示，与 F7 删 `nodeBlockTypes` 同源判据），属独立清理项而非本缺陷
- **F10 评估与落地**：两份手工镜像的「已知顶层键」清单（`ConfigManager.KNOWN_TOP_LEVEL_KEYS` 与 `YamlFiles.GAME_SETTINGS_KNOWN_TOP_LEVEL_KEYS`）可以反射比对——两字段虽为 `private static final`，测试与生产同在 unnamed module、classpath 运行，`getDeclaredField + setAccessible` 不受模块层限制（本仓库已有读取私有字段的先例）。已加哨兵用例，任一侧增删顶层键而另一侧未同步即红
- **测试**：新增 `SpawnSpreadMergeWhitelistTest`（5 条）——① 判别性：载入后手改 spread → 保存后内存与磁盘都必须是磁盘值（修前红在内存 3.0）；② 反向对照：有 GUI 入口的 `spawn-radius-min` 手改必须被内存压回（挡「把整段都合并」的过度修复）；③ 基线守卫：本会话内改过的值必须胜过磁盘；④ 对照：磁盘缺该键时合并跳过、保留代码默认而非读成 0；⑤ F10 清单反射比对
- **变异验证 3 次**：移除刚加的白名单条目 → 红 1 条（判别性用例，内存仍 3.0）；去掉基线守卫改成「磁盘无条件胜出」 → 红 1 条（会话内改动被吞）；在 `YamlFiles` 镜像里删掉 `waves` 一项 → 红 1 条（F10 哨兵）；三次均以 `cp` + `md5sum -c` 还原并校验一致

### Fixed（round5-37 — 只读写测试的守卫方向修正）

- **【缺陷】`YamlFilesTest#writeToReadOnlyFileThrows` 的 `Assumptions.assumeFalse(isWindows(), ...)` 方向装反**：它跳过的 Windows 恰恰是唯一会抛 `IOException` 的平台，而它真跑的 Linux 上 `YamlFiles.write` 会静默成功 —— `assertThrows` 必红。已在真实 Linux JVM（非 root）复现：`setWritable(false)` 后 `tmp + ATOMIC_MOVE` 覆盖**成功**（`threw=false`），目标内容被改掉；只有直接 open-for-write 会被拒。因此该用例从未处于「跑且通过」的状态：Windows 一直被 skip，Linux 上必然失败，也是首次 CI 唯一预期会红的用例
- **根因是平台语义而非写错一行判断**：`write()` 走「同目录 tmp + `ATOMIC_MOVE`」，而 POSIX 的 `rename(2)` 只校验**目录**写权限、完全不看被覆盖文件的权限位；Windows 的 `MoveFileEx` 则在目标带 `FILE_ATTRIBUTE_READONLY` 时返回 `ERROR_ACCESS_DENIED`。两套语义相反，任何单一平台守卫都必然在另一半平台上错，所以不能靠「一条用例 + 一条假设」覆盖
- **改法：按平台各测各的真实失败形态，两条互斥执行**——① 新增 `writeIntoUnwritableDirectoryThrows`（POSIX，`assumeTrue(isPosix())`）：父目录收到 `r-x`（0555）后同目录 tmp 建不出来 → 必然抛 `IOException`，不经 rename 语义、POSIX 上恒成立；② `writeToReadOnlyFileThrows` 改为 `assumeTrue(isWindows())`：目标只读 → `ATOMIC_MOVE` 被拒 → 抛 `IOException`。两条都补了「目标文件内容必须保持原样」与「失败后不留 .tmp」两条现场断言，覆盖原子写的核心承诺
- **平台判定改用能力探测而非内核名**：`isPosix()` 走 `FileStore.supportsFileAttributeView(PosixFileAttributeView.class)`，而不是 `os.name` —— 决定 `setPosixFilePermissions` 能否用的是**路径所在文件系统**，不是内核名字（Windows 盘挂载、网络/FUSE 挂载都无权限位）
- **无法构造真实失败时显式跳过而非假失败**：两条用例在设完权限后都补了前置校验（`assumeFalse(Files.isWritable(dir))` / `assumeFalse(file.canWrite())`）。root 用户、ACL 挂载、FAT 类文件系统会无视权限位，那种环境里断言 `IOException` 只会得到与被测代码无关的假红，因此如实跳过并在 skip 原因里写明
- **本地验证（不是"看代码像对的"）**：① Windows 真跑 → `24 tests / 0 failures / 1 skipped`，skip 的是 POSIX 那条；② 改造后的源码在 WSL Debian 的 ext4 上、以非 root 用户 `tester` 编译并运行 → POSIX 用例通过，skip 的反而是 Windows 那条（与旧行为完全互补）；③ 同一 Linux 环境下把旧写法与新写法并排跑 → **旧红新绿**，证明改造确实翻转了结果；④ 变异验证：把 `write()` 的 `finally` 改成 `catch (IOException)` 吞掉写失败 → Windows 侧红 2 条、Linux 侧红 2 条（含本用例），说明新用例不是永远绿的空转测试；变异后以备份 + `md5sum` 校验还原（`11725d7c…`）
- **生产代码零改动**：`src/main/` 一字未改，仅测试与探针脚本；全量 `846 / 0 failures / 0 errors / 1 skipped`，skip 叶子由「Windows 上被跳的只读用例」换成「Windows 上被跳的 POSIX 目录用例」，总数恒为 1

### Changed（round5 F7 — 删除 `nodeBlockTypes` 死字段）

- **【F7】删除 `GameConfig.nodeBlockTypes`（含 `getNodeBlockTypes` / `setNodeBlockTypes` / `snapshot()` 拷贝 / `equals` 比较 / `hashCode` 分量）**：该字段预置了 4 种材质（`BEACON` / `ENCHANTING_TABLE` / `ENDER_CHEST` / `RESPAWN_ANCHOR`），但 `NodeSystem#loadConfig` 自 1.0.9 起硬编码 `nodeBlockTypes.clear(); add(RESPAWN_ANCHOR)`，全库零消费者、YAML 无此键、GUI 只有一行「固定为重生锚」的说明性展示、`saveToFile` 也从不写它 —— 是一个「永不生效的假配置」，未来若有人接上消费者会立刻拿到 4 种错误材质
- **保留原位注释而不是静默消失**：字段位置留下说明（节点方块固定为 `RESPAWN_ANCHOR`；要支持多材质必须先实现 `NodeSystem` 的消费者再重新引入），避免下一位维护者把删字段误读成「漏实现」
- **`NodeSystem` 侧零改动**：`nodeBlockTypes` 字段 / `loadConfig` 的 `clear()+add()` / `isNodeBlock` 全部保持原样（那是真正在用的实现，与 `GameConfig` 的同名字段无关）
- **测试替换而非删除**：`GameConfigTest#snapshotIsolatesNodeBlockTypes`（原本只测「snapshot 拷贝了新 ArrayList」）换成 `nodeBlockTypesIsNotReintroducedAsFakeConfig`——反射断言该字段与两个访问器都不得重新出现，把「这是有意删除」这个决定钉在测试里，而不是让一条永远为真的断言留在那里
- **无行为变更**：节点方块识别链路（`NodeSystem.isNodeBlock` → `RESPAWN_ANCHOR`）一字未改，玩家可见行为与配置往返均无差异

### Docs（round5 — `waves` 段文档补全与 README 同步）

- **`docs/guides/config-reference.md` / `docs/guides/en/config-reference.md` 新增「`waves` 段」一节**：此前这两份逐项解释**完全没有 `waves` 的文档**（只有 `arenas.yml` 的 `victory-waves`），而 `waves` 是决定每波刷什么、刷多少的核心段。新节说明 `count`（每人只数）/ `total-mobs`（每人精确总数上限，三层裁剪取最小）/ `weight`（仅全波 `count` 全 0 时回退权重路径）三个字段，并写明 `total-mobs: 0` 是「跳过某一波」的唯一受支持写法
- **README / README-EN 各加一节「波次配置」/「Wave configuration」**：摘要 `waves` 三字段语义，并同步 1.1.0 的三条行为变更——① `total-mobs: 0` 停用波次；② 删除波次后必须点 [保存并应用配置]，否则 reload 会按磁盘内容复活；③ 手写的新波次编号在 reload / 重启后**真的生效**（旧版本静默丢弃）
- **不涉及版本号变更**；纯文档，无需服主做任何操作

### ⚠️ 升级提示（升级到本版必读）

- **【行为变更】reload 以磁盘为权威**：GUI 里删过但**未保存**的波次，会在 `/hilltop reloadconfig`（或 GUI 的「加载配置文件」按钮）后按磁盘内容**重新出现**；要真正删掉波次，请删除后点「保存并应用配置」。同理，`waves.default.compositions` 的增删也只在保存后落盘
- **【行为变更】删掉整段 `waves` / `waves.default` 不等于「不刷怪」**：段缺失按「恢复出厂」处理（重建出 1/5/10 与出厂 default 配比），下一次保存会把它们写回磁盘。真要停掉未配置波次，请写 `waves.default.total-mobs: 0`
- **【修复】手写进 `game-settings.yml` 的新波次编号现在真的会生效**：旧版本里非 1/5/10 的波次（含经 GUI「新建波次」建的）在 reload / 重启后会被静默丢弃；本版起按磁盘内容重建，`waves: {"7": ...}` 这类写法 reload 后立即生效。`waves.<n>.compositions` 里用原版 `EntityType` 名（如 `zombie`）也已被接受
- **【行为变更】`nodes.buff-effects: []` 现在真的是「关闭全部节点增益」**：旧版本里显式空表被当成「没配」，节点仍给出厂的 `RESISTANCE` / `REGENERATION`；现在空表即无增益。键被整段删掉（不是写成 `[]`）时语义不变，仍是「回出厂默认」
- **【提示非变更】`monsters-config.yml` 里自建的怪物段现在会报警**：每个怪种都需要代码里绑定的专用 AI，所以新增段不会生效（本版只补告警，行为不变）；新告警会点名那些段，并提示「要刷原版怪请在 `waves.<n>.compositions` 用 `EntityType` 名」
- **删除命令编号 1 基化（行为变更）**：`/hilltop deploy delspawn|delmobspawn|delvillager <编号>` 的编号从 1 开始（与列表显示一致）——旧习惯的 `delspawn 0` 将报「编号不存在」，请按列表显示编号操作
- **`/hilltop help` 开放给所有玩家**（内容按权限分档显示）；此前仅管理员可用
- **`/hilltop stop` 语义修复**：强停不再播「对局失败」假战报、不记负场（修复前双人局强停会把部分玩家错误记负）
- **节点世界名大小写**：此前手改 arenas.yml 世界名大小写会导致 nodes.yml 节点静默全丢且删除不落盘——本版修复；若你的服务器已处于分裂态，升级后节点会自动恢复加载，此前「删不掉的幽灵节点」也可正常删除
- 服务端无需其它动作；`commands.md` 重启后自动重生成（编号说明已同步）

## [1.1.0-SNAPSHOT-fix1] - 2026-10-20

> 1.1.0 的第一个修复版：fix5 审查轮（10 专家通道 + 2 验证角色，合并清单 48 条：P0=0/P1=1/P2=19/P3=28）的六路并行修复 + 结转清理 + 六路锐评轮（与执行同数量的对抗审查）的裁定修正。
> 审查产物：`docs/01-reviews/fix5/`（fix5-MASTER-BACKLOG.md 总报告）；修复规格书：`fix5-REPAIR-PLAN.md`；锐评报告：`docs/01-reviews/fix5-critique/`（fix5-CRIT-MASTER.md 汇总）。
> 原 `1.0.9-SNAPSHOT` / `-fix1`~`-fix4` / `1.1.0-SNAPSHOT` 的包与 GitHub Release 保留不动。

### Fixed（2026-10-20 — X2 战斗与安全（含唯一 P1））

- **【安全·P1】铁砧伪造链封堵**：商店道具（锤/烈焰蛋/汁液）的识别器删除「显示名回退」迁移分支，PDC 成为唯一口径——修复「服主将物品名去色码自定义后，玩家可用铁砧逐字符复现名称伪造锤/蛋/汁液」的漏洞；伪造锤曾可绕过 max-uses=30 变成无限锤、整条消耗品商店线（5/3/2 币）被绕过。字节码级验证依据见 fix5-VERIFY-P1b.md
- **零友伤补齐（弓/弩/近战）**：新增 `FriendlyFireListener`——对局内同场成员之间的原版伤害（箭矢/弩箭/三叉戟/挥砍）一律取消，与既有技能零友伤（火焰蛋/重锤只弹飞）口径一致，兑现 README「零友伤」承诺
- **抛投者俯冲击退收敛**：`DropperAttackGoal` 俯冲命中后取消水平击退位移（保留垂直受击反馈），修复「站在岛边缘被撞下虚空」
- **节点充能爆炸实体伤害过滤**：床/锚爆炸（BLOCK_EXPLOSION）对本场成员的伤害取消（方块面保节点逻辑不变；怪物受伤照常）

### Fixed（2026-10-20 — X1 状态机与判空）

- **reset 补录加固**：结算重置的离线玩家补录循环加判空 + try/catch 兜底——任何单玩家记录异常不再打断 reset（原可能导致竞技场卡死在 ENDING 需管理员 disable→enable）
- **登录链判空**：重连失败恢复物品路径加 PlayerGoodsManager 空值守卫（同族契约裂缝）
- **空置结束 vs 重连超时竞态收敛**：空置自动结束任务在「本场仍有玩家处于重连窗口」时计时清零/让位——全员离线场景的收尾出口唯一化（消除双任务对撞导致的战绩口径翻转）；`empty-arena-end-seconds` ≤ `rejoin-timeout-seconds` 时控制台启动告警（仅提示不改值）
- **重连落点分档**：WAITING 阶段重连放行（不再被移出对局，与 auto-leave-on-quit=false 的承诺对齐）；STARTING/WAITING 重连落等待区 + 冒险模式（与入场态一致），对局进行中落核心 + 生存模式

### Fixed（2026-10-20 — X3 商店与经济）

- **弓掉落归零**：持弓怪（钩爪猎手）主手装备不再按 5% 掉落——修复「20 波内约 95% 玩家免费掉到弓、商店 15 水晶定价被架空」
- **shop.yml slot 重复检测**：同槽位两条商品从「静默覆盖」改为控制台 warning（含双方 id）；`/hilltop shop reload` 支持重复检测
- **slot 超 gui.size warning 补升级指引**：提示文本补「1.1.0 完整商品布局需要 gui.size: 54」
- **kit-received 文案**：开局套装提示补「皮革四件套」
- **`.corrupt` 坏文件隔离修复**：`renameTo` → `Files.move(REPLACE_EXISTING)`（StatsManager + PlayerGoodsManager 两处同构）——修复「Windows 上目标已存在时隔离必败、坏文件永不替换、每次开服重复告警、日志自相矛盾」
- **enable 指引三触点修正**：`arena-not-deployed-hint` / `arena-enable-failed` / README 双语——「enabled: false 需改 true 并重启服务器（启动时自动入队）」替代原「直接用 /hilltop enable」（该命令对 enabled:false 的场必定拒绝）

### Fixed（2026-10-20 — X4 数据/权限/性能）

- **`/hilltop lang` 权限分离**：管理员/控制台改全服默认语言并落盘；普通玩家只改自己的会话语言偏好（不再改写全服默认）
- **SafeSpawnUtil 区块加载防御**：落点扫描入口加 isChunkLoaded 短路——未加载区块不再触发同步加载（纯防御，杜绝最坏 20-180ms 主线程停顿）
- **重生准入兼容层**：非成员的重生点若指向竞技场世界（床 spawn 绕过准入的残余路径），重生时改指大厅 + 清床 spawn

### Fixed（2026-10-20 — X5 文档与生成器）

- **commands.md 生成器五处失实修正**：leave 注意事项（「丢失击杀」→「只累计增量不计胜负」）、start 注意事项（指向两套生成点）、join/start/stop 参数补 `[竞技场]`；生成器类头加维护义务注释
- **README-EN 补齐六块中文独有章节**：竞技场维护（启用队列/隔离窗口）/世界唯一性/per-arena min-players/商店 NPC 管理/生命周期状态表/四必填提示
- **join 同场重入预检**：等待/倒计时阶段对同场重复 join 直接提示已在场内（不再 leave→join 重置倒计时，堵住「反复重入无限拖延开局」）；addPlayer 加幂等早退双保险
- **CHANGELOG 1.1.0 升级提示补 gui.size 54 提醒**（已有段落内补写）

### Fixed（2026-10-20 — X6 结转与死代码清理）

- **预览村民脱管（fix3 漏修结转项）**：`VillagerPreviewManager` 增加 EntitiesLoadEvent 兜底——区块卸载后重新加载时自动清除脱管的预览村民（原 removeIf 清理只覆盖显式调用路径）
- **写盘失败播报**：deploy 的 addspawn/addmobspawn/addvillager 等路径在 arenas.yml 写入失败时聊天栏明确提示「改动仅存在内存、重启丢失」（新增 `deploy.save-failed` 键）；修复失败标记残留导致的后续假警报
- **Tab/部署菜单补全**：`/hilltop setup` Tab 补 6 子命令（addnode/nodesgui/show-particles/addvillager/villagersgui/saveexit）；`/hilltop deploy help` 菜单补 5 条（delvillager/clearmobspawns/confirmclearmob/clearvillagers/confirmclearvillagers；10 个新 i18n 键 en/zh 对称）
- **死代码清理**：删除零调用方法族（isGameActive / MAM.isPendingRejoin / startArena / startAllArenas / ArenaManager.save 等，删除前全库 grep 零调用复核）；`deliverItem` 未用参数签名收窄
- **stopGame 重复赋值清理**（GameManager settlementPhase 冗余行）

### Fixed（2026-10-20 — 锐评轮快修（6 路对抗审查的裁定修正））

- **commands.md 生成器文案全面校准**（存疑口号「不填时按所在世界解析」修正）：start/stop 改为实际解析链（所在场 → 默认场）；wave clear/skip 同步修正；status 参数行补 `[竞技场]`；lang 注意事项改为 M-14 后的双语义（玩家会话偏好 / 管理员落盘）；enable/disable 两行核实属实保留
- **`arena-issue-flag-disabled` 提示修正**：`/hilltop list` 的故障行不再指引用 `/hilltop enable`（enabled: false 未重启时该命令仍被拒），改为「改为 true 并重启服务器（启动时自动入队）」
- **删除类部署命令补写盘失败播报**：delspawn/delmobspawn/delvillager/confirmclear/confirmclearmob/confirmclearvillagers 六条路径补齐「写入失败」提示（与 set 类五路径口径一致）
- **重锤换手绕限封堵（锐评 P2）**：蓄力中换手/丢弃落地不再跳过 max-uses 计数——`executeSmash` 入口校验主手仍持锤，非锤即静默放弃本次蓄力（修复「30 次上限形同虚设」的无限锤残余通道）
- **开局刷怪点区块预加载（锐评 P3→根治）**：`onGameStart` 对全部刷怪点做一次性 getChunkAt——修复「重启后首局、刷怪点距出生点≥视距且区块未加载时首波少怪/空波」的条件性回归（区块加载完成后自愈的闪烁）
- **重生判定双库查询（锐评 P3→根治）**：非成员重生覆写改查「活动实例 or 定义」两库——封闭「已被 disable 的场（世界仍加载）床 spawn 落回」残余路径
- **shop.yml slot 越界 warning 条件化**：仅 gui.size < 54 时才附加升级指引（54 格下 slot 越界是配置错误而非旧盘升级）
- **陈旧 javadoc 三处清理**（已删方法的引用：MAM/ArenaInstance/ArenaAdminService）；预览村民兜底注释与实际重建路径对齐
- **README 双语**：`/hilltop lang` 命令行与特性行同步 M-14 双语义

### ⚠️ 升级提示（升级到本版必读）

- **fix3 之前发放的旧道具失效**：无 PDC 标记的旧锤/烈焰蛋/世界树汁液不再被识别（显示名回退已删除，属反伪造安全取舍；旧道具已逾期多个版本）。商店/命令新发的道具不受影响
- **重连语义变化**：WAITING 等待期掉线重连现在回到等待区（此前被移出对局）——与 `auto-leave-on-quit: false` 的承诺对齐
- **空置与重连阈值**：建议 `empty-arena-end-seconds` > `rejoin-timeout-seconds`（如前者 60、后者可保持默认），启动时若有交叉会看到提示
- **持弓怪不掉弓**：弓的获取恢复为「商店购买」单渠道（服主如需掉落可自行在 monsters-config 调整怪种武器或等后续配置面）
- **重锤蓄力中换手（含按 F/滚轮/Q）会放弃本次猛击**：修复换手绕限的语义收紧；正常流程不受影响
- 服务端无需其它动作；命令文档 commands.md 重启后自动重生成

## [1.1.0-SNAPSHOT] - 2026-10-17

> 首个 Minor 版本：装备成长线（作者拍板"弓+箭做、附魔下版本再加"）+ 怪种掉落表 + 商店 GUI 扩容。
> 设计依据：`docs/03-analysis/equipment-economy-design-2026-10-15.md`（11.0 节十项决策终表）。
> 原 `1.0.9-SNAPSHOT` / `-fix1` / `-fix2` / `-fix3` / `-fix4` 的包与 GitHub Release 保留不动。

### Added（2026-10-17 — 装备成长线（E1））

- **新增 `EQUIPMENT` 商店商品 type（服主自定义装备的载体）**：字段设计——`icon.material`（**必填**，即发货材质，缺失/非物品则跳过并 warning）、`name`（可选，`&` 颜色码，写到发货物上）、`lore`（可选，写道具实体上；GUI 价格行不写入发货物）、`unbreakable`（可选 boolean，默认 false = 普通耐久）。发货固定 1 件（读到 `new ItemStack(icon, 1)` + displayName + lore + unbreakable）。**本版不含附魔字段**（作者决策：附魔下一版本再加，届时旧配置零迁移）
- **商店商品扩充至 16 件**（`shop.yml`，slot 唯一性已校验；原有 3 件商品内容保留、slot 重排至首行居中）：
  - 工具区：神圣重锤 5 / 烈焰蛋×8 3 / 世界树汁液×4 2 / **弓 15 / 箭×8 2**（弓+箭为作者拍板新增——弥补"除烈焰蛋外无手段够到 +8 高度飞行抛投者"的缺口）
  - 武器区（白板，无附魔）：**铁剑 8 / 钻石剑 25 / 下界合金剑 48**（铁剑定价 8 = 打完第 1 波的保底收入，第一件升级即买即得；4 档设计依据：作者明确"以后会不止 20 波"）
  - 护甲区（白板散件）：**铁套 4 件各 6 / 钻石套 4 件各 14**
- **开局赠送皮革四件套**：`starter-kit.items` 在原 石剑/面包/火把 基础上加入 LEATHER_HELMET/CHESTPLATE/LEGGINGS/BOOTS 各 1（作者拍板：皮革套默认发放不入商店；**未来职业系统将以皮革套颜色区分**——本版留待职业版本，无相关代码）
- **商店 GUI 扩至 54 格**（`shop.yml` 的 `gui.size: 27 → 54`），单页分区（工具/武器/护甲），不做二级菜单
- **升级归一化（starter-kit）**：磁盘列表逐项等于旧默认（石剑/面包/火把）时自动升级为含皮革套的新默认；服主改过则原样保留（模式同 fix4 的 `wave-timeout` 归一化）
- **全部商品可服主自定义**：条目/价格/名称/lore/耐久均在 `shop.yml`，`/hilltop shop reload` 生效（E1 硬要求"服主可自定义售卖装备"）

### Added（2026-10-17 — 怪种掉落表（E2））

- **能量水晶掉落按怪种区分**（作者拍板"打难打的怪更值钱"）：甲虫 1 / 钩爪猎手 1~2 / 飞行抛投者 2~3 / 空投僵尸 0（反挂机规则原样保留）/ 未配置的怪回退全局 `shop.currency-drop-min/max`（1~2）
- **配置**：`game-settings.yml` 的 `monsters.<id>` 段新增可选键 `drop-min`/`drop-max`（`-1` = 该端未配置、回退全局对应端；写 `0/0` = 该怪不掉）；GameConfig 构造为三特殊怪播种默认值（升级老存档缺键同样生效）
- **怪种识别**：新增 PDC 标记 `hilltopvillage:monster-id`（在三个专属 spawn 方法与 spawnCustomMob 的 default 分支打标）——**不用实体类型反查**（管理员可自定义 entity-type、原版同类怪会混入，实体类型不可靠）；`dropCurrency` 经 PDC → monsters 配置查表
- **五处同步**：`MonsterConfig` 的字段/getter+setter/`copy()`/`equals()`/`hashCode()` 全部同步（fix3 P2-31 纪律）；`ConfigManager` 的 load（contains 守卫）/ saveToFile（读有写有）/ `mergeExternalChanges`（逐怪种白名单——防手改掉落被 GUI 保存吞掉，模式同 `shop.currency-drop-*`）
- 零新 i18n 键（掉落无播报）

### ⚠️ 升级提示（升级到 1.1.0 的服主必读）

- **`shop.yml` 需手动更新**：商店配置是"文件存在即不释放模板"——**新商品（装备/弓/箭）不会自动出现**。用新版模板替换服务器的 `plugins/HilltopVillage/shop.yml`（注意备份你改过的商品）或手动加条目，然后 `/hilltop shop reload`。**注意**：本版商品按 54 格布局（护甲区槽位 27-53），旧文件的 `shop.gui.size: 27` 必须同步改为 `54`——否则超出 27 格的商品会被跳过并在控制台看到 `out of GUI size` 警告
- **皮革套自动生效**：starter-kit 的升级归一化会在你未改过旧默认时自动加入皮革四件套；改过则保留你的列表
- 掉落表新键（`monsters.<id>.drop-min/drop-max`）会自动补写；三特殊怪默认值（1/1、1/2、2/3）在缺省时由 GameConfig 播种
- 服务端无需其它动作；`/hilltop reloadconfig` 即可让掉落表与 starter-kit 变更生效


## [1.0.9-SNAPSHOT-fix4] - 2026-10-17

> 1.0.9 的第四个修复版：作者第二轮实测反馈（12 条）的五路并行修复 + 装备与经济数值设计文档（纯设计）。
> 原 `1.0.9-SNAPSHOT` / `-fix1` / `-fix2` / `-fix3` 的包与 GitHub Release 保留不动。
> 反馈出处：作者 2026-10-15 实测（含服务器日志）；设计文档：`docs/03-analysis/equipment-economy-design-2026-10-15.md`。

### Fixed（2026-10-17 — G1 刷怪安全落点（反馈 1/3/5，含 fix3 回归修复））

- **反馈 1/3/5 的根因（fix3 自己引入的回归）**：fix3 把 arenas.yml 的 `spawn-radius-min/max`（作者服务器 = 20/40）接线生效后，`WaveManager.getRandomSpawnLocation()` 变成了"每个刷怪点周围 20~40 格随机偏移"——天空岛地图一偏移就飞出岛外，怪物刷在虚空随即下落出世界（日志成片 `fell out of the world`），第一波刷出的怪全部掉光 = "第一波一个怪也没有"
- **新增 `util/SafeSpawnUtil.findSafeGround()`（安全落点工具）**：从参考点向下扫描找第一个实心非液体方块取顶面、x/z +0.5 居中、要求落点上方 2 格无实心（防卡墙/洞穴顶下）、贴近世界底部（`y < minHeight+2`）判不安全返 null——杜绝"落点在虚空 / 方块内部 / 头顶堵死"三类不安全情形
- **`WaveManager.getRandomSpawnLocation()` 全面接入安全化**：固定刷怪点分支改为"偏移候选 ×8 次安全重试 → 失败回退基准点原地安全化 → 再失败返 null（调用方已有守卫）"；环形回退分支（无固定点时以核心为中心）同样接入；多点随机语义未动
- **出厂模板半径改小**：`arenas.yml` 三个竞技场的 `spawn-radius-min/max` 由 20/40、25/45、30/50 统一改为 **1/3**（注：不能配 0——`GameManager.getSpawnRadiusMin()` 的 per-arena 判定是 `>0 才生效`，配 0 会静默回退全局值）。**作者服务器上的实际 arenas.yml 需手动改小，或依赖安全落点检测兜底**
- **玩家出生点传送安全（反馈 5 "遁地掉虚空"）**：`ArenaInstance.onGameStart()` 传送改为经 `safeTeleportTarget()`——`world.getChunkAt()` 强制加载区块 + 安全化修正 y（保留 yaw/pitch）；安全化失败保持原坐标原样传送
- **反馈 3（第一波无怪）额外排查结论**：核实 `spawnWave` 三条路径入口无独立问题，"第一波无怪"完全由掉虚空解释

### Fixed（2026-10-17 — G2 抛投者 AI + 超时保险 + 波次命令（反馈 2/12））

- **反馈 2（飞行抛投者不会攻击玩家）**：根因是 `spawnFlyingDropper` 的 `removeAllGoals` 把幻翼原版俯冲攻击一并清掉、只留空投 AI，导致它只会空投且长时间滞空（日志实证白天被日光烧死）。新增 `ai/DropperAttackGoal`（优先级 2，低于空投的 1——空投抢占优先、间隙才俯冲）：锁定 30 格内最近的本场玩家、向头顶 +2 俯冲、命中造成一次 `ATTACK_DAMAGE`（= monsters-config 的 `flying-dropper.base-damage`，可调）伤害后 50 tick 冷却压低 DPS；不触碰空投状态机
- **顺带修**：抛投者 `setShouldBurnInDay(false)`（白天对局不再自焚烧死）
- **反馈 12 前半（去掉自动超时保险）**：`wave-timeout-seconds` 默认值改为 **0（禁用）**，代码路径与配置钩子全保留（真遇到卡死时可调回）；`ConfigManager` 新增**升级归一化**——存量服务器磁盘上仍是旧默认 120 的值会被自动归一化为 0（显式设过其它值的不受影响）
- **反馈 12 后半（管理员强制清怪/跳波命令）**：新增 `/hilltop wave clear [竞技场]`（RUNNING 时强制清除本波所有怪物并结束本波进入休整期；不掉落、不计击杀；休整期执行会被拒绝并引导用 skip）与 `/hilltop wave skip [竞技场]`（任何相位可用——RUNNING 时等效清波+跳休整、休整期立即开下一波；人数不足拒绝不判负）；权限 `hilltopvillage.admin`、控制台可用；Tab 补全/部署菜单/命令文档/plugin.yml usage 全同步；9 个新 i18n 键

### Fixed（2026-10-17 — G3c 返回大厅入口（反馈 8））

- **技术核查结论**：旁观（SPECTATOR）模式下快捷栏不渲染、客户端不发送使用物品数据包——"观战者右键物品按钮"方案技术上不可行，改用旁观模式唯一可靠的通道：聊天可点击按钮
- **阵亡观战**：置旁观时发送带 `[返回大厅]` 可点击按钮的消息（`ClickEvent.runCommand("/hilltop leave")` + hover 说明），接入 `PlayerLifecycleListener` 的 2 处置旁观位置（含幽灵态兜底分支）
- **开局提示**：`ArenaInstance.onGameStart()` 传送后广播"想中途离开：/hilltop leave"提示
- 新 i18n 键 3 个：`hint.leave-command` / `hint.spectate-leave`（`{0}` 为按钮文字）/ `btn.return-lobby`

### Fixed（2026-10-17 — G3d 空局自动结束（反馈 11））

- **现状**：最后一名玩家 quit 时（RUNNING/WAVE_INTERMISSION 且重连窗口开启）走重连保留逻辑，`players` 集合保留离线 UUID、对局空转无收尾
- **新增"空置超时"出口**：`ArenaInstance` 新增每秒（20 tick）值守任务 —— 状态 ∈ STARTING/RUNNING/WAVE_INTERMISSION 且在线成员为空持续 `empty-arena-end-seconds` 秒 → info 日志 + 沿用现有 `stopGame()` 收尾路径（与"全员退光"同一语义）；有人加入/重连自动清零；`<=0` 禁用
- **新配置键 `empty-arena-end-seconds: 60`**（game-settings.yml 顶层；0 = 禁用）：四件套全接线（GameConfig 字段/getter/setter/snapshot/equals/hashCode、ConfigManager load/save/mergeExternalChanges 白名单/KNOWN_TOP_LEVEL_KEYS）；任务句柄取消点：`stopGame()`/`resetNow()`/`completeResetAfterRestore()` + 状态离开进行中自停

### Changed（2026-10-17 — G4 能量水晶与经济（反馈 6/9/10））

- **反馈 6 前半（掉率太低）**：掉币机制由"概率制"改为"**每杀必掉、数量浮动**"——新增 `shop.currency-drop-min/max`（默认 1/2，闭区间随机）；`currency-drop-chance` 保留为概率门、默认改 **1.0（必掉）**；旧键 `currency-drop-amount` 由 ConfigManager 自动迁移（服主改过则保形为新 min=max=旧值）并删键落盘（仿 nodes.repair-item 迁移先例）；空投怪不掉币的反挂机规则原样保留
- **性能说明**：每杀恰 1 堆 ItemStack（数量在 amount 上、不增加实体数），原版相邻同类掉落物自动合并，竞技场重置时既有清理机制兜底——与旧机制同量级，配大数量也不会膨胀实体
- **反馈 6 后半（排行榜展示水晶）**：`PlayerData.currencyCollected` 计数器（击杀掉落口径）+ `StatsManager` 新增 `crystals-collected` 持久化字段（旧文件缺省 0 兼容）；展示于 `/hilltop stats` 新行、侧边栏、结算战报（`defeat-stats-line` 占位符 3→4）、新增 `/hilltop top crystals` 榜单
- **反馈 9（如何设置大厅出生点）**：新增 `/hilltop lobby setspawn`——须站在大厅世界内执行，把当前位置（含朝向）写为大厅出生点；写盘遵守"重读盘+只改 lobby.spawn 五键"纪律；**立即生效**（新登录/大厅复活/`/hilltop lobby` 即时使用新点，无需重启）；Tab 补全同步
- **反馈 10（大厅可否发币）**：核实 `shop give` 本就无世界限制（在大厅一样可用）；已把"任何世界均可用（含大厅）"落到 `shop.give-usage`/`shop.give-success`/`usage-shop-give` 文案与命令文档
- **额外修复（G4 自发现）**：`MultiArenaManager.isLobbyWorld` 大小写敏感比较改为 `equalsIgnoreCase`（lobby.world 大小写与实际文件夹不一致时大厅保护/复活曾全部静默失效）

### Docs（2026-10-17 — 装备与经济数值设计（反馈 4/7，纯设计未动代码））

- 新增 `docs/03-analysis/equipment-economy-design-2026-10-15.md`（416 行/11 节）：武器线 4 档（铁剑+锋利I=10 → 铁剑+锋利III=22 → 钻石剑+锋利IV=38 → 下界合金+锋利IV+火焰附加=60，每档附清怪刀数对照）；护甲散件 3 档（皮革 2/件 → 铁+保护I 6/件 → 钻石+保护III 14/件）；掉落按怪种建议（甲虫 1 / 猎手 1~2 / 抛投者 2 / 空投僵尸 0）；收入账本（单人 20 波期望 305 水晶）与毕业支出（158~250）校验；弓+箭强烈建议（补"唯一远程打不了 +8 高度抛投者"的缺口）；实现成本：带附魔装备需新 `EQUIPMENT` shop type（现 `MATERIAL` type 发货 `new ItemStack` 不支持附魔）；9 项待作者拍板事项列于第十一节
- 该设计**尚未实现**；实现与否与档位取舍待作者拍板后进行

### ⚠️ 升级提示（升级到 fix4 的服主必读）

- `messages.yml` 只补缺键：fix4 **修改**了 5 个旧键——`usage-top` / `usage-shop-give` / `shop.give-usage` / `shop.give-success` / `defeat-stats-line`（占位符 3→4，旧盘文案不显示水晶数但不报错）；需要看到新文案的话删掉这些键后 `/hilltop reloadconfig`
- **`arenas.yml` 需手动动作**：你服务器上的 `spawn-radius-min/max`（若仍是 20/40 等大值）建议改为 1/3；不改也会被安全落点检测兜住，但小值更符合"围绕刷怪点小范围随机"的设计（**改后需重启**，reloadconfig 不重读 arenas.yml）
- **`wave-timeout-seconds` 升级归一化**：磁盘上仍是旧默认 `120` 的会被自动改写为 `0`（禁用）；如果你手工设过其它值则保持
- **`game-settings.yml` 新键**：`empty-arena-end-seconds: 60`（空局自动结束秒数，0 禁用）会自动补写；`shop.currency-drop-*` 三键变更如上述


## [1.0.9-SNAPSHOT-fix3] - 2026-10-14

> 1.0.9 的第三个修复版：13 路全维度审查（P0=0 / P1=20 / P2=40）后的七路并行修复 + 修复后命令逻辑五通道专项审查与修补。
> 原 `1.0.9-SNAPSHOT` / `-fix1` / `-fix2` 的包与 GitHub Release 保留不动。
> 审查报告：`docs/01-reviews/fix3/`（13 通道）+ `docs/01-reviews/fix3-cmd/`（5 通道）；合并总清单：`fix3-MASTER-BACKLOG.md`。

### Fixed（2026-10-14 — F1 向导核心与会话，M-01/02/03 + P2-1~10）

- **M-01 `/hilltop setup goto <N>` 修复**：分发层此前只传 `args[1]`，goto 分支的 `split(" ",2)` 永远拿不到数字、功能完全不可用（README/Tab 均承诺可用）。分发层改为 `String.join` 拼接余下全部参数；Tab 补全补 goto 后的 1-8 数字补全
- **M-02 向导坐标采集加世界守卫**：此前 `addspawn/addmobspawn/addnode/addvillager/confirm-core/confirm-shop` 全部直接 `player.getLocation()` 写入——saveexit 回大厅后点聊天历史残留按钮会把**大厅坐标静默写进 arenas.yml**（不重启时开局把玩家跨世界传送、村民生成到大厅；重启后坐标按竞技场世界固化）。新增 `blockedByWorldGuard()` 覆盖 7 个命令分支 + 4 个 GUI 点击分支（复用 `deploy.must-be-in-arena-world` 键，含传送指引）；`AdminDeployManager` 三个 add 方法兜底拒收异世界坐标 + 日志
- **M-03 双管理员互踢修复（监听器常驻化）**：`SetupWizard` 的 Listener 原为"打开时注册、关闭时 unregisterAll"——A 关闭 GUI/saveexit/掉线会把 B 正开界面的监听一并注销（B 点击失效 + 界面物品可被拖走取走）。改为 onEnable 注册一次永不注销（事件处理器自带标题判断 + 会话 null 检查），删除全部 17 处注销/重注册调用与 `onSetupInventoryClose`；`onQuit` 补会话判断防误清他人预览
- **P2-1 完成收尾加"最后屏障"**：`confirm-world`/`confirm-core` 补 step 守卫（此前任意步骤可执行——清核心/推进/多推几次可绕过 preview 四项必填直达完成）；`showStepDone` 内置 `validateCompletion()` 四项必填复核（goto 8 / step=8 恢复两条旁路不再能绕过）
- **P2-2 `wizard.step` 增加竞技场绑定**：config.yml 增存 `wizard.arena.<uuid>`；恢复时竞技场不匹配则从第 1 步开始并提示 `setup.resumed-arena-mismatch`（此前换场 resume 会把步骤指针接到另一竞技场）；完成收尾一并清 arena 键
- **P2-3 第 4 步节点 ≥1 校验**：`confirm-nodes` 与 GUI 收口双入口加"至少 1 个节点"拦截（新键 `setup.nodes-required`，含"0 节点的场任意玩家阵亡会立即全队判负且无法复活"说明）；`setup.step4-nodes` 文案重写（删"自动识别"暗示、明写手动注册）
- **P2-4 第 1 步 [确认] 不再物理删除 core**：`confirm-world` 改为仅当 core 已为空时才置 null（此前重设向导（如只改商店）时第 1 步 [确认] 会把已部署场的 core 三键从 arenas.yml 物理删除、无任何提醒）；`setup.step1` 补"原值在第 2 步确认前保留"说明
- **P2-5 世界名指引**：`setup.saved-exit`/`setup.enable-hint` 补 `{0}` 世界名；`setup.resuming` 补场名+世界名
- **P2-6 第 7 步预览显示真值**：min/max/波数改 live 实例 → ArenaDefinition → 全局三级取数（此前读全局值，arena2/3 与实际 per-arena 不符）
- **P2-7 goto 8 与 step=8 恢复**：`goto 8` 改调 `showStepDone`（此前只发提示、跳过 enable 收尾）；恢复条件放宽为 1..8（此前 step=8 落盘后重进从 1/8 重启）；收尾顺手关残留 GUI
- **P2-8 节点 GUI 硬编码清零**：节点管理 GUI 10 处 + 村民 GUI 2 处硬编码中文全改 i18n（EN 管理员界面不再中英混杂）
- **P2-9 村民删除体验**：村民 GUI 删除改为"仅当当前页超界才回退"（此前无条件回第 1 页）+ 补 `setup.villager-removed` 聊天反馈
- **P2-10 arenas.yml 写失败播报**：`saveSpawnPoints`/`saveShopNpcLocation` 改返回 boolean；autoSave 路径失败时播报 `setup.save-failed`（此前 IOException 全程静默照报成功、第 8 步还会把写失败误诊为 enable 问题）

### Fixed（2026-10-14 — F2 对局状态机与玩家生命周期，M-06/07/08/12 + P2-11~13）

- **M-06 重连后记分板恢复**：`ArenaInstance.rejoin()` 补 `scoreboard.show(player)`（fix1「退出清理补齐」摘除了内存条目但 rejoin 未恢复，导致重连玩家整局无侧边栏）
- **M-07 幽灵存活态修复**：`respawnDeadPlayers()` 跳过仍在死亡画面的玩家（`player.isDead()`）；`PlayerLifecycleListener.onRespawn` 进行中分支先查 `data.isAlive()`——已被波次刷新复活者走正常复活流程（此前"死亡画面跨波次刷新"会让玩家进入 `alive=true + 旁观` 的幽灵状态：**永久无法自主复活 + 抑制全灭判负**）；两处延迟任务补 `getPlayerData` 二次校验
- **M-08 结算丢统计修复**：`reset()` 清集合前对离线残留玩家逐个 `recordOffline` + `forgetPlayerArena`（此前重连窗口内掉线的玩家，若对局在其窗口内结束并重置，本局统计全部不入库）；波次口径用 `sessionWave` 快照（stopGame 归零波次后不会记 0 波）
- **M-12 休整期刷怪修复**：`WaveManager` 三条刷怪路径守卫改「state != RUNNING 即自停」（此前 `isGameRunning()` 含休整期——快速清波时刷怪延续到休整期污染下一波）
- **P2-11 物品延迟归还修复**：`tryRejoin` 失败分支补 `restore` + `goods-restored`（此前重连窗口内登录且局已结束，物品快照要等下一次登录才归还、期间对局货币可在大厅消费）
- **P2-12 崩溃残怪清扫**：全部波次怪统一 PDC 标记 `hilltopvillage:wave-mob`；三点清扫——onEnable 静态全服 + ArenaInstance 构造期按世界 + onGameStart 开局前兜底（此前崩溃/强杀重启后 persistent 波次怪无启动清扫、永久驻场可攻击新局村民）
- **P2-13 结算期门控统一 API**：`GameManager` 新增 `settlementPhase` 标记 + `isSettlementPhase()` / `isGameActive()`（haltGame 置位、endGame/stopGame/startGame 复位）——供技能/修节点门控统一口径

### Fixed（2026-10-14 — F3 锤与烈焰蛋，M-17/18/20 + P2-14~18）

- **M-17 技能击杀计统计**：烈焰蛋/重锤 AOE 怪物分支改 `damage(amount, attacker)` 带伤害来源（此前裸 `damage()` 无来源 → `getKiller()==null` → 主武器清怪 Kills 全记 0）
- **M-18 幽灵猛击修复**：新增幂等 `clearSmashState(Player)`；`onPlayerMove` 补 `!data.isAlive() || SPECTATOR` 守卫（命中即兜底清理）；结算期在飞蓄力不落地结算（此前高处蓄力→被杀/掉线→旁观重生或重连落回核心，走一步即触发满档 AOE 且旁观状态可放炮）
- **M-20 重锤弹飞队友收敛（作者决策 D-2）**：玩家分支竖向初速由 2.76~3.6 收敛为 `1.8*falloff+0.4`（≤2.2，对齐烈焰蛋调优锚点 CHANGELOG:1178）、水平由乘 2.0 改为归一化向量 ×0.7（此前推算可把队友弹飞 26~34 格、落地摔伤秒人，「弹飞不伤」名不副实）；「对玩家只弹不伤」语义保留
- **P2-14 旁观者投弹修复**：烈焰蛋右键入口补 SPECTATOR + `isAlive` 双重静默拒绝（锤已有限制、蛋此前没有）
- **P2-15 三道具 PDC 防伪升级（作者决策 D-3）**：新增 `hilltopvillage:item-kind` PDC（fireball/hammer/repair），三个工厂产物全带标记；识别器改「PDC 命中 → true，否则精确显示名兼容」（旧道具平滑过渡，兼容分支下个大版本可删）；与「货币只认 PDC」规范对齐
- **P2-16 击退公式注释修正**：`applyBwKnockback` 的 1.5 补偿（归一化域语义、分支由几何比例主导）注释写清，**数值零改动**（火箭跳为官方 feature）
- **P2-17 道具改名即时生效**：`cachedFireballName`/`hammerName` 构造期缓存删除、改实时读取（此前 reloadconfig/GUI 改名必须重启才生效）
- **P2-18 AOE 击退免疫无敌实体**：烈焰蛋爆炸波及/近炸、重锤 AOE/余震四处过滤加 `!entity.isInvulnerable()`（商店 NPC 与预览村民不再被 setVelocity 推离原位/吹入虚空）

### Fixed（2026-10-14 — F4 节点系统，P2-19~22 + M-16 侧）

- **P2-19 节点方块保护**：新增 `BlockBreakEvent`（对局窗口 STARTING/RUNNING/WH 中拦截，部署期放行；提示 `node.block-protected`）+ `BlockExplodeEvent`（重生锚原版爆炸的 blockList 过滤，与既有 EntityExplode 过滤对齐）——此前玩家挖掉/炸掉节点方块后逻辑节点仍在（Buff/计数照跑）但永久不可修复
- **P2-20 满血不吞材料 + 非成员判空**：`repairNode` 满血早退返回 false + `NodeListener` 满血提示 `node.repair-full` 不扣材料；`NodeListener` 入口 `getPlayerData==null` 静默 return + `NodeSystem` 判空（非成员右键 NPE 双防线）
- **M-16 重置后节点复活修复（含 F1 侧）**：新增 `SetupWizard.invalidateDeploymentContext(arenaId)`（幂等，`invalidateAllDeploymentContexts` 兜底），`ArenaResetService` 重置收尾调用；`deploymentGameManager` cached 分支同步重载 NodeSystem（带世界加载守卫）——此前重置清空 nodes.yml 后同会话重走第 4 步，增删任一节点会把已清空的节点整份写回
- **P2-21 热路径键缓存**：`VillagerSystem.isVillageVillager` 的 NamespacedKey 改惰性静态缓存（AOE 过滤半热路径，此前每次调用 new）
- **P2-22 大厅防火**：`LobbyProtectionListener` 补 `BlockIgniteEvent`/`BlockBurnEvent` 拦截（打火石点燃木质装饰 → 火蔓延烧毁且不在地图回滚域、永久损坏）
- **P2-13 节点门控**：`NodeListener` 修复门控切换 `isGameActive()` 口径（结算期拒绝修复）

### Fixed（2026-10-14 — F5 商店全链，M-05 + P2-23~30）

- **M-05 商店 STARTING 档保护死代码修复**：向导清除/设置守卫改读 live 实例状态（此前读 `GameManager.getState()`——STARTING 是其内部同步瞬时帧、外部永不可观测）；拒绝口径经 `NpcManager.isGameInProgress()` 统一并补结算期 VICTORY/DEFEAT（三入口同源）
- **P2-23 商店迁移保护**：`spawnNpc` 改返回 boolean——跨世界迁移前检查旧实体所在世界的对局状态，对局中拒绝并保留旧实体（此前全局 despawn 会**跨世界抽走他场正在对局的商店 NPC**）；set 三路径（向导 confirm-shop/setshop/showStepDone 兜底）加目标场对局保护（新键 `setup.shop-set-in-game`/`deploy.setshop-in-game`）且改"先生成后存盘"（失败不写配置）；`deploy setshop` 补 pitch 归零
- **P2-25 孤儿清理次序**：主类启动改为先 `respawnFromConfig`（强制加载商店区块）再 `cleanupOrphans`（此前硬崩后未加载区块的孤儿清不到、重启后与新 NPC 并存）
- **P2-26 货币工厂防御**：`createCurrencyItem` 入参 `Math.max(1, amount)` 钳制；退款分支补 `price > 0` 判定 + 溢出改掉落（此前 `currency-drop-amount≤0` 时每次掉币抛 IAE 吞击杀统计刷日志）
- **P2-27 换材质不再作废存量货币**：`isCurrency` 删材质短路、PDC-only（此前 `shop reload` 换 currency 材质后带 PDC 的真币全部不被识别）
- **P2-28 副手货币可用**：`countCurrency`/`removeCurrency` 同步纳入副手（主背包先扣、不够再扣副手）
- **P2-29 世界名大小写**：`NpcManager.despawnIfInWorld` 与 `ArenaRegistry.getByWorldName` 改 `equalsIgnoreCase`（配置大小写与实际文件夹不符时清除删 YAML 但实体残留/向导误报 no-arena-here）
- **P2-30 旁观者开商店修复**：`ShopListener.onPlayerInteractEntity` 补 SPECTATOR 判定

### Fixed（2026-10-14 — F6 数据完整性与配置，M-10/11/14/15 + P2-31~36）

- **M-10 StatsManager 写盘竞态修复**：照 `PlayerGoodsManager` 模式加 `AtomicLong writeSeq`（主线程取号、锁内比对弃旧写）——此前在途旧快照可在 saveNow 之后落盘、覆盖关服新统计
- **M-11 聊天输入交错修复**：超时任务改两参 `remove(uuid, entry)` 身份精确匹配；非法输入重挂改"新条目 + 新超时任务"——此前 30 秒临界输非法数字会使该玩家**此后每条聊天都被吞**、连发两次请求会误杀后一次
- **M-14 死配置接线（作者决策 D-1，per-arena 优先/全局兜底）**：`GameManager` 新增 `getNodeSearchRadius()/getSpawnRadiusMin()/getSpawnRadiusMax()/getSpawnYOffset()` 透传（liveArena 实时读）；`NodeAttackGoal`/`ExplodeBeetleGoal` 改读 per-arena 索敌半径；`WaveManager.getRandomSpawnLocation` 接入 min/max 随机距离与 y-offset、两套点全空且 max>0 时新增"以核心为中心环形刷怪"回退（每波一次 warning 防刷屏）——此前 `arenas.yml` 的这四个键读存校验俱全但**运行时零消费**，服主按 50 理解索敌半径实际是全局 128
- **M-15 手改配置防覆盖（真合并）**：`ConfigManager` 记录 `settingsLastModified`，`saveToFile` 检测 mtime 变化时 `mergeExternalChanges()`——19 个无 GUI 入口键以磁盘为准（会话基线守卫），手写未知键保留；语义：手改保留、GUI 改动仍胜出（此前未 reload 就触发 GUI 保存会把内存旧快照全量回写、静默覆盖手改值）
- **P2-31 `hasUnsavedChanges` 修复**：`GameConfig` 实现全量 `equals/hashCode`（75 字段 + 嵌套 MonsterConfig/WaveConfig/MobComposition/StarterKitItem 字段级；脚本验证三件套对齐）；顺带补 `snapshot()` 漏拷的 spawnRadiusSpread——此前引用比较恒 true、主菜单永远"待保存"
- **P2-32 聊天输入防护**：`NumericInputHandler.onChat` 加 `!isFinite || |v|>1e9` 拦截 + `requestInteger` clamp（此前 `(int)NaN=0`、`(int)1e300=MAX_INT` 可达 setter）
- **P2-33 波次删除记账**：`ConfigManager.removeWave(int)` 内存删 + 记账，saveToFile 清记账键——此前 GUI 删波次只删内存、重启后波次复活
- **P2-34 损坏存档容错升级**：实测发现 paper-api 的 `loadConfiguration(File)` **内部自带 catch 吞异常**，`PlayerGoodsManager`/`StatsManager` 改用 `Files.readString + loadFromString` 使 catch 真正生效 + 坏文件改名 `.corrupt` 保留现场；onEnable 两 new 加 Throwable 兜底——此前半截损坏文件会让整个插件加载失败
- **P2-35 死代码标注与 schema 统一**：`ArenaManager.save()`/`MultiArenaManager.startArena()/startAllArenas()` 加 `@Deprecated(forRemoval=true)` + 危险 javadoc（零外部调用方；save() 会用固化旧值整份覆写 core/spawn-points）；`SCHEMA_VERSION` 1→2 与出厂模板一致 + load 读到更高版本 warning
- **P2-36 未知配置键警告**：顶层未知键与 monsters 段未知键汇总 warning（此前拼写错误静默保留默认、排障困难）

### Fixed（2026-10-14 — F7 命令层与入口，M-09/19 + P2-37~42）

- **M-09 打错场 ID 静默改靶修复**：`resolveArena`/`resolveArenaId` 对显式非空 ID 未命中直接返回 null（不再回落默认场——此前 `/hilltop stop arean1`（typo）会把另一场进行中的对局停掉）；调用方全核对，新增三态提示：显式未命中 → `arena-not-found {0}`、定义存在无实例 → `arena-not-deployed {0}` + hint、无配置场 → `no-active-arenas`
- **M-19 非参赛者击杀 NPE 修复**：`onMobDeath` 的 `getPlayerData(killer)` 加判空（被 /tp 带进竞技场世界的非成员击杀波次怪不再在 EntityDeathEvent 内 NPE）
- **P2-41 竞技场世界准入防护（作者决策 D-4）**：新增 `mechanics/ArenaAccessListener`（PlayerTeleportEvent）——目标世界属竞技场定义且玩家非成员时取消传送 + 提示 `arena.access-denied`；**放行条件含实例成员集合命中**（join 时序 `players.add → teleport → playerArenaMap.put` 的窗口内映射尚未写入，只查映射会误拦正常入场）；管理员豁免（部署需要）；onEnable 注册
- **P2-37 未部署场指引**：`deploy/status/start/stop` 解析失败先查 definitionManager → `arena-not-deployed {0}` + hint（含"运行 /hilltop setup 或用 /hilltop enable 启用"指引）；无参 `enable/disable` 优先按管理员所在世界反查（`resolveArenaIdForAdmin`，防静默操作默认场）；manage GUI 未部署场启用按钮 lore 补"先运行 /hilltop setup"（新键 `manage.detail.enable-not-deployed/-setup-hint`）
- **P2-38 manage GUI 开关消费返回值**：禁用按钮按命令路径同款 switch 映射结果（此前对局中点击静默失败）；启用按钮补预检 + `arena-enable-failed`
- **P2-39 deploy 命令按世界解析**：deploy 入口改为世界反查优先（与向导同源；此前站 arena2 世界被指向 arena1）；未启用场 `clearshop` 直接走 `ArenaAdminService.deleteShopNpc` 的 YAML 兜底（无实例也可清除）；`deploy help` 补实现分支
- **P2-40 命令细节**：`start` 失败统一走 `arena-not-deployed` 含 enable 指引；`clearvillagers` 加 15 秒二次确认（新键 `deploy.clearvillagers-warning/-confirm/-already/-no-pending`）+ 修"恒报已清除 0 个"（先取数再清）
- **P2-42 选图 GUI 点击映射**：holder 记录 slot→arenaId，点击按映射取（此前点击期按下标重取列表，GUI 常开时列表变动会点错场）

### Fixed（2026-10-14 — 命令逻辑专项审查修补轮，C1~C5 五通道）

> 修复后针对全部命令逻辑做了 5 通道专项审查（`docs/01-reviews/fix3-cmd/`，P0=0），以下为审查发现的即时修补：

- **C5-01 选图 GUI 背包点击误触发**：`ArenaSelectionGUI.onInventoryClick` 补 `getClickedInventory` 守卫（点击玩家背包时 `getSlot()` 是 convertSlot 后槽位、与条目槽位 0-44 重叠，可经映射命中误触发 join）
- **C1-01 节点复核缝**：`validateCompletion` 补节点 ≥1 分支（bounceTo=4）——goto 8 / step=8 恢复两条旁路不再能 0 节点完成部署
- **C2-01 世界名校验大小写**：`handleDeployCommand` 的世界校验改 `equalsIgnoreCase`（此前反查命中后仍精确比较，配置大小写不符时站对世界也被误拒）
- **C2-02 确认命令指错**：`clearmobspawns` 的 already 提示改用专用键 `deploy.clearmobspawns-already`（此前复用 clearspawns 键、指引的 confirmclear 不在本 pending 集）
- **C3-01 join 归一化**：`joinArena` 的显式 ID 先经定义侧归一化（`/hilltop join Arena1` 不再误报 arena-not-found，与 stop/enable/disable 口径一致）
- **C3-02 双消息叠报**：`ArenaCommand` join 失败分支不再补发泛化 `join-failed`（joinArena/addPlayer 全失败路径均已发精确原因）
- **C5-02 min-players clamp**：`GameRulesMenu` 的 min-players setter 补 `Math.min(20, Math.max(1, v))`（Shift 聊天输入 NaN/0/大数不再直达 setter）

### Docs（2026-10-14 — F8 文案与文档收尾）

- README.md / README-EN.md 与 fix3 实际行为同步（第 4/5 步按钮实名、[重新扫描]/reloadnodes"扫描"废弃语义整段重写、节点"自动识别"改"手动注册且至少 1 个"、补 goto 回跳说明、补竞技场准入保护说明、部署收尾启用指引核对）
- `CommandsDocumentGenerator` 对齐（权限表两处不存在节点改 `config.items`/`config.rules`、hammer 权限修正、控制台黑名单描述、补齐缺失命令条目——以 README 命令表为源）
- 死键清理 14 个（`joined-game`/`left-game`/`join-queue`/`shop.usage`/`shop.reloaded`/`nodes.scan-*`(3)/`setup.not-in-progress`/`setup.btn-rescan`/`setup.btn-list-villagers`/`setup.btn-skip-shop`/`setup.must-be-in-arena-world`/`deploy.reloadnodes-scanning`）
- deploy 系列"怪物生成点"旧语义改"玩家出生点"口径（`cmd/desc/menu-hint/addspawn-added/listspawns-empty/confirmclear-done` 六键，F-2 同源漏网键）
- 删除键 1 个：`setup.already-done`（P2-7 后成为死键）

### ⚠️ 升级提示（升级到 fix3 的服主必读）

`LanguageManager` 加载规则是「JAR 为底、磁盘覆盖，**只补缺键**」。fix3 中**修改**的旧键在已有旧 `plugins/HilltopVillage/messages.yml` 的服务器上**不会自动更新**，需删掉这些键后 `/hilltop reloadconfig` 或重启才能看到新文案（新增键会自动补写）：

- `setup.step1` · `setup.step4-nodes` · `setup.saved-exit` · `setup.enable-hint` · `setup.resuming`（F1 修改）
- `arena-not-deployed` · `arena-not-deployed-hint`（F7 修改：加 `{0}` 占位符——**务必删除，否则占位符不显示且文案过时**）
- `deploy.cmd-addspawn` · `deploy.desc-addspawn` · `deploy.menu-hint-addspawn` · `deploy.addspawn-added` · `deploy.listspawns-empty` · `deploy.confirmclear-done`（F8 修改：出生点语义）

**删掉的 14 个死键**（在服务器 messages.yml 里也会残留但不影响运行，可不管）。竞技场世界准入保护为 fix3 新增行为：非成员无法用 /tp 等外部手段传送进入（管理员除外）。


## [1.0.9-SNAPSHOT-fix2] - 2026-10-12

> 1.0.9 的第二个修复版：修复作者实测反馈的 F-1~F-7，并实现作者新提的 F-8（村民预览实体）与 F-9（第 3 步合并总览界面）。
> 原 `1.0.9-SNAPSHOT` 与 `1.0.9-SNAPSHOT-fix1` 的包与 GitHub Release 保留不动。

### Fixed（2026-10-12 — F-1 刷怪点管理「显示粒子」调错方法）

- 刷怪点管理 GUI 的 [显示粒子] 此前误调玩家出生点方法 `showSpawnPointParticles()`（粒子发到出生点坐标；出生点为空时静默无粒子但外层仍报「粒子动画已开始」）。新增 `AdminDeployManager.showMobSpawnPointParticles()`（数据源 `savedMobSpawnPoints`，空列表走新键 `particles.no-mobspawnpoints`），改调新方法；预览方法内部统一负责发提示，删去向导三处外层重复的 `setup.particles-started` 补发（该键随之成为死键，已删；`showSpawnPointParticles(Player)` / `showMobSpawnPointParticles(Player)` 两个无调用方的单参重载一并清理 —— 代码测试复测项）

### Fixed（2026-10-12 — F-2 向导第 3 步文案与按钮对不上）

- `setup.step3` 重写为总览句（明确「① 玩家出生点（开局传送位置，阵亡复活用第 2 步核心）② 怪物刷怪点（怪物出生位置）」，两套各需 ≥1）；`setup.step3-mobspawns` 重写为做法句，逐一点名五个聊天按钮（含原先只字未提的 [保存并返回大厅]）；顺带修正 `setup.spawns-required`（原文误称"怪物生成点"，实为玩家出生点）、`setup.btn-add-lore`（原文"添加为怪物生成点"，同一键被两套坐标共用）两处历史语义错误

### Fixed（2026-10-12 — F-3 向导收尾缺启用指引）

- `SetupWizard.finishDeployment()` 改为返回 boolean（按实际结果二选一）：新增 `setup.enable-done-auto`（已就绪/已入队）与 `setup.enable-done-manual`（未自动启用，附可点击 [启用竞技场 {id}] 按钮）两套收尾文案；`setup.saved-exit`（保存并返回大厅）补 `setup.enable-hint` 说明部署完成后竞技场如何生效（自动入队 / enabled:false 需改配置重启 / 手动 `/hilltop enable`）

### Fixed（2026-10-12 — F-4 商店村民低头朝向）

- `SetupWizard` confirm-shop 侧：`player.getLocation()` 克隆后 `setPitch(0)` 再存盘 + 再生成（低头不再写进 `arenas.yml`）
- `NpcManager.spawnNpc()` 侧：全插件唯一生成入口统一 `clone().setPitch(0)` 兜底 —— 旧存档已写坏的 `pitch` 也在每次重启生成时被自动纠正（自愈，无需重设位置）
- 附 yaw 增强：NPC 每 10 tick 面向 8 格内最近玩家（只改 yaw、pitch 恒 0，角度差 <2° 不发包）；转向任务随 `despawnNpc()` 取消，无泄漏

### Fixed（2026-10-12 — F-5 商店位置无删除/清空入口）

- 向导第 6 步：按钮行改为 `[设置商店位置][显示粒子][清除商店位置][继续][保存并返回大厅]`，新增商店位置状态行（`setup.shop-status`）；`confirm-shop` 设置后**不再自动跳步**（可原地重设/清除），进入第 7 步改由 [继续] → preview 子命令（校验口径不变）
- 新增 `/hilltop deploy setshop` / `/hilltop deploy clearshop` 子命令 + Tab 补全 + 部署菜单行 + 命令文档
- `/hilltop manage` 详情页商店条目新增**右键清除**（位置条目 + 实体；清除后立即显示"未设置"）
- 对局中保护：三处清除入口在 STARTING/RUNNING/休整期统一拒绝（商店 NPC 是插件级单例，对局中清除会当场撤掉场内玩家的商店）
- 顺带修两处数据一致性隐患：部署期 `AdminDeployManager` 取用时重读 `arenas.yml`（防向导旧内存值把外部清除整份写回）；`/hilltop manage` 商店读数改走 `ArenaAdminService.shopNpc()`（实例优先 + YAML 兜底）

### Fixed（2026-10-12 — F-6 村民管理按钮命名统一）

- `setup.btn-villagers-gui` 由 `[村民管理]/[Manage Villagers]` 改为 `[打开村民管理]/[Open Villager Manager]`，与第 3/4 步 `[打开生成点管理]/[打开刷怪点管理]/[打开节点管理]` 风格统一；顺带合并等值重复键 `setup.btn-villagers-done` → 复用 `setup.btn-done`

### Fixed（2026-10-12 — F-7 五类点位粒子无配色区分）

- 新增 `admin/PointKind`（枚举：五类点位 → DUST 颜色 + 文案键）与 `admin/PointParticleRenderer`（统一"垂直光柱 + 环绕螺旋"绘制，两套预览系统共用，视觉强制一致）
- 配色：玩家出生点绿 `#3CFF5A` / 怪物刷怪点红 `#FF5555` / 能量节点蓝 `#4A8CFF` / 待保护村民黄 `#FFDE59` / 商店金 `#FFAA00`（`DustOptions` 在枚举常量构造时建好复用，避免热路径重复分配）
- `LocationParticlePreview` 增加 `PointKind` 参数、`ArenaManageGUI` 的 `ListKind` 透传类型色；`AdminDeployManager` 五处复制粘贴的粒子循环合并为一个私有方法；新增村民 GUI [显示粒子]（黄色）与向导第 6 步 [显示粒子]（金色，子命令 `showshopparticles`）两处预览入口

### Added（2026-10-12 — F-8 村民预览实体）

- 新增 `mechanics/VillagerPreviewManager`（插件级单例 Listener）：向导第 5 步登记村民后，于该位置生成一只纯装饰预览村民（`setInvulnerable(true)` + 取消一切 `EntityDamageEvent`、`setCollidable(false)`、`setAI(false)`、`setSilent(true)`，头顶显示序号"村民 #N"）
- 生命周期：进入/重入第 5 步整体重同步（天然处理删除后序号前移）；离开第 5 步 / 完成部署 / saveexit / 玩家退出 / 会话结束即清除；启动 `cleanupOrphans()` 按 PDC 标记清崩溃残留；`onDisable` `clearAll()`
- 与开局真村民三重隔离：PDC 标记不同（`villager-preview` vs `village-villager`）、生命周期只存在于第 5 步窗口、**开局硬屏障**（`ArenaInstance.onGameStart()` 在 `villagerSystem.spawnAll()` 前 `clearInWorld()`）

### Changed（2026-10-12 — F-9 第 3 步合并为总览界面 + 单一完成按钮）

- 新增「生成点总览」GUI（54 格）：slot 11 玩家出生点计数/状态、slot 15 怪物刷怪点计数/状态、slot 20 [打开生成点管理]、slot 24 [打开刷怪点管理]、slot 49 唯一 [完成]（两套各 ≥1 时绿色可点，否则灰色锁定）
- 两个子管理 GUI 的 slot 51 由 `[完成]` 改为 `[返回总览]`（红色玻璃板），消除"两个界面各有一个完成按钮"的困惑
- 聊天栏按钮行由 5 个精简为 4 个：`[添加当前位置][添加刷怪点][打开生成点总览][保存并返回大厅]`，新增 `/hilltop setup overview` 命令
- 旧命令 `spawns-done` 与旧 PDC tag（`setup-spawns-done`/`setup-mobspawns-done`）保留处理分支，与总览 [完成] 汇总到同一收口方法 `tryFinishSpawnsStep`
- 顺带修一个隐藏坑：`openWizardGui()` 改为「先 `openInventory`、后 `unregister+register`」——总览↔子界面互切时会触发旧界面 `InventoryCloseEvent` 的注销逻辑，旧顺序会连带注销新界面导致点击无反应

### ⚠️ 升级提示（升级到 fix2 的服主必读）

- `LanguageManager` 加载规则是「JAR 为底、磁盘覆盖，**只补缺键**」。fix2 中**修改**的旧键（`setup.step3` / `setup.step3-mobspawns` / `setup.spawns-required` / `setup.btn-add-lore` / `setup.btn-villagers-gui` / `particles.hint` / `setup.btn-show-particles-lore`）在已有旧 `plugins/HilltopVillage/messages.yml` 的服务器上**不会自动更新** —— 需删掉这些键（或删整份重建）后 `/hilltop reloadconfig` 或重启，才能看到新文案；新增键会自动补写


## [1.0.9-SNAPSHOT-fix1] - 2026-10-11

> 1.0.9 的首个修复版（命名参考 v0.1.6-SNAPSHOT-fix1 模式）：修复部署死锁等实测问题；原 1.0.9-SNAPSHOT 包与 Release 保留不动。

### Fixed（2026-10-11 — 用户实测反馈修复：部署死锁，setup 不再依赖活动实例）

> 来源：服主实地测试。站在未部署的竞技场世界里执行 `/hilltop setup`，被提示“当前没有活动的竞技场！”，
> 部署流程完全卡死（blocking）。根因是一个闭环死锁：没部署 → `definition.getCore()==null` →
> 启用队列拒绝创建 `ArenaInstance` → 向导的 `gameManager()` 只从 `activeArenas` 取实例 → 取不到就报
> `no-active-arenas` 退出 → 永远无法部署。

- **向导改为“按玩家所在世界解析竞技场”**（方案 A）：`SetupWizard.start()` 先用玩家当前世界名反查
  `ArenaDefinition`（`getDefinitionManager().getArenaByWorld(...)`），找不到才报新键 `setup.no-arena-here`
  （“你不在任何已配置的竞技场世界里”，含如何传送进去的指引）。不再写死默认竞技场 —— 这顺带修复了
  “向导只能部署默认场”的已知问题（站在哪个竞技场世界就部署哪个场）
- **部署期 GameManager（打破死锁的关键）**：新增 `deploymentGameManager(arenaId)` —— 已启用竞技场用其
  正式实例的 GameManager；**未部署竞技场**惰性创建一个“只依赖 `ArenaDefinition` + 已加载世界”的部署期
  GameManager 并按 arenaId 缓存。该 GameManager 的 `core` 允许为 null（构造函数不解引用 core；向导读 core
  走对 null 安全的 `AdminDeployManager.getCoreLocation()`），且不启动任何 Bukkit 任务，部署完成后丢弃
- **会话绑定目标竞技场**：`SetupSession` 新增 `arenaId` 字段（`start()` 按世界解析后写入）；`deploy()` /
  `gameManager()` 全部改为 `deploy(Player)` / `gameManager(Player)`，按会话里的 arenaId（缺失时按所在世界）
  解析，不再取默认/第一个活动竞技场。旧会话 resume（内存 session 与 `config.yml` 的 `wizard.step`）兼容：
  重连重建时按当前世界重新解析 arenaId
- **部署完成即可启用**：第 8 步“完成”收尾时调用 `finishDeployment(arenaId)` —— 若该场还没有正式实例，先
  `reloadDefinitions()` 让 registry 读到刚写入 arenas.yml 的 core，再 `enableArena(arenaId)` 把它放进启用队列
  获得正式实例，使其能被 `/hilltop start`、`/hilltop join` 正常使用（`enabled: false` 的场会被如实拒绝）
- **多竞技场隔离**：部署期上下文按 arenaId 缓存，两个服主同时部署不同场互不干扰；始终优先复用正式实例的
  GameManager（部署改动须由它写入，运行中的对局才能立即生效，并避免两份 AdminDeployManager 竞态写 arenas.yml）
- **新增 i18n 键** `setup.no-arena-here`（en/zh 双语对称）；**不复用** `no-active-arenas`（语义已变）。
  `setup.must-be-in-arena-world` 保留（仍由 `/hilltop deploy` 的世界校验使用）【更正 2026-10-14】实际引用的是 `deploy.` 前缀版（`deploy.must-be-in-arena-world`，HCH 与 SetupWizard 均引用它）；`setup.` 版为死键，已在 fix3 删除


### Docs（2026-10-11 — README 补"阶段 2.5：进入竞技场世界"）

- **补部署指南断层**：作者指出 README 只写"进入竞技场世界"却没教怎么进。新增阶段 2.5，讲清本插件无传送命令、第一次部署需用原版 `/execute in <世界> run tp` / Multiverse `/mvtp` / 地标插件自己传过去，并提醒传过去后确认世界名 + 切创造模式（中英双语同步）

### Docs（2026-10-11 — 版本更名 1.0.9-SNAPSHOT-fix1）

- **pom 版本 `1.0.9-SNAPSHOT` → `1.0.9-SNAPSHOT-fix1`**：保留 1.0.9-SNAPSHOT 原包与 GitHub Release 不动，修复版单独以 fix1 命名发布（作者定稿：参考 v0.1.6-SNAPSHOT-fix1 命名法）
- **README-EN 命令表补齐**：补 lobby/enable/disable 三行，与中文版命令名集合完全对齐
- **CHANGELOG 1.0.9 完整开发日志恢复**：1031 行开发日志从"精简归档"改回完整保留在主 CHANGELOG（作者定稿：CHANGELOG 宁多不少，是给开发看的，不做精简归档）

### Known issues（已核实待修，本轮只记录未修）

> 出处：`docs/04-dev-reports/tests/USER-FEEDBACK_2026-10-11.md`（作者 7 条实测反馈，4 路只读核实全部属实）
> **【状态更新 2026-10-12】以下 F-1~F-7 已全部在 `1.0.9-SNAPSHOT-fix2` 修复**（本段仅作留档，不删）。

- F-1 刷怪点管理 [显示粒子] 调错方法（粒子发到玩家出生点；无 mob 专用方法）
- F-2 向导第 3 步两条文案与五个按钮对不上（"生成点"未定义、[保存并返回大厅] 未提）
- F-3 [保存并返回大厅] 与向导收尾都缺 `/hilltop enable` 指引
- F-4 商店村民低头（pitch 被复制并写进 arenas.yml）
- F-5 商店位置无删除/清空入口（向导/命令/manage GUI 三处皆缺）
- F-6 村民管理按钮命名漏"打开"前缀（其余三步已统一）
- F-7 五类点位粒子无配色区分（村民/商店无预览入口）

## [1.0.9] - 2026-06-19——2026-09-13

### Fixed（2026-10-10 — P0 修复：波次配置 GUI 翻页按钮被返回按钮覆盖）

> 来源：P0-F 实地问题。波次配置菜单（`/hilltop config` → 波次配置）翻到第 2 页后无法翻回上一页。

- **翻页/返回槽位冲突修复**：`WaveSettingsMenu` 主菜单中 `page>0` 时"上一页"按钮占 slot 45，而共享基类 `setBackButton()` 硬编码 slot 45（`size-9`），构建顺序上返回按钮在后，覆盖掉了"上一页"按钮及其点击动作 —— 导致多页波次配置翻到第 2 页后点 slot 45 只会返回上级菜单，无法回到上一页。改为主菜单不再调用 `setBackButton()`，而用 `setClickableItem` 把返回按钮放到空闲的 slot 48（复用 `config.menu.common.back` / `back-lore` 既有键与红色玻璃板外观，无新增 i18n 键）
- **最终底栏布局**：slot 45 =上一页（`page>0` 时）、47 =新增波次、48 =返回、49 =默认模板信息、53 =下一页（有后续页时）；五者互不冲突，点击动作各自对应正确
- **影响面隔离**：仅改主 `WaveSettingsMenu`。基类 `setBackButton()` 不动（其余无分页的菜单 `GameRulesMenu`/`NodeSettingsMenu`/`ItemSettingsMenu`/`MonsterSettingsMenu` 及波次详情/新增怪物/怪物属性三个子菜单的 slot 45 返回按钮均无冲突，保持原样）

### Security（2026-10-10 — P0 修复：商店假币付款漏洞 P0-A）

> 来源：安全审计 P0-A。玩家可用铁砧把普通绿宝石改名成货币显示名，被商店当真币无限购买。

- **假币付款漏洞修复**：`ShopManager.isCurrency` 删除「材质 + 显示名回退」分支，货币识别**只认 PDC 标记** `hilltopvillage:shop-currency`（材质检查仅作快速短路）。真币一律由 `createCurrencyItem` 发放并带该标记，玩家用铁砧 / 命名牌伪造的同材质改名道具一律被拒
- **全链路复核**：发币路径（`WaveManager.dropCurrency` 掉落、`ShopListener` 购买找零 / 退款）均走 `createCurrencyItem`，删除回退后真币仍被正确识别，不误伤
- **清理**：移除随回退一同失效的 `cachedCurrencyName` 字段与 `refreshCurrencyName()` 方法（原为显示名比对的热路径缓存，已无用）
- **新增管理员命令** `/hilltop shop give <玩家> [数量]`：服主手工发放真币的唯一正道（原版 `/give` 发的绿宝石无 PDC 标记、不被商店认），默认 1 个、上限 6400，背包放不下掉落脚下，控制台可用
- **新增 messages.yml 键**：`usage-shop-give`、`shop.give-usage` / `shop.give-player-not-found` / `shop.give-invalid-amount` / `shop.give-too-many` / `shop.give-unavailable` / `shop.give-success` / `shop.give-received`（en/zh 双语对称，共 8 键）
- **文档同步**：README / README-EN 命令表与 `CommandsDocumentGenerator` 补充 `shop give` 条目及货币识别说明

### Fixed（2026-10-10 — P0 修复：波次卡死软锁簇 + 空投失控）

#### P0-C 波次卡死软锁簇

- **波次超时保险（根因兜底，最终安全网）**：新增可配置 `wave-timeout-seconds`（默认 120 秒）。`GameManager.startNextWave` 记录开波时间戳，`checkWaveCompletion`（每 3 秒）在清波判定前跑 `handleWaveTimeout`：某波持续超时仍未清波时，第一阶段把全部残怪传送到最近存活玩家身边并高亮（`WaveManager.relocateStuckMobsToPlayers`，无存活玩家可传送时直接出列），再宽限 15 秒仍未清则第二阶段强制清除（`WaveManager.purgeStuckMobs`）。这一层独立于下方 AI 修复，保证任何形态的卡死最终都会被打破，不再出现"玩家存活却死等、只能 /stop"的软锁
- **自爆甲虫不再罚站**：`ExplodeBeetleGoal` 由"有节点目标才激活"改为常驻激活，`tick` 内统一处理——无节点目标（节点全毁 / 全部超出搜索半径）累计 `NO_TARGET_SELF_DESTRUCT_TICKS`(100tick) 后自毁出列；卡地形连续卡死到 `MAX_STUCK_GIVEUPS`(3) 次后同样自毁出列，不再 persistent 占着 activeMobs
- **钩爪猎手不再木桩**：`WaveManager.spawnHookClawHunter` 为猎手追加低优先级（3）`NodeAttackGoal` 作为退化行为——平地无高差目标（钩爪需玩家在上方≥4格）无法钩拉时，推进并攻击最近活跃节点；一旦出现可钩拉目标仍由优先级 1 的钩爪 AI 抢占。节点全毁的极端情形由波次超时保险兜底
- **普通怪 NodeAttackGoal 卡死放弃**：原实现卡死后只对同一节点无限重发 `moveTo`；改为卡死累计放弃次数，记下卡点后优先改选"次近节点"（新增 `NodeSystem.getNearestNodeExcluding`，retarget 排除刚放弃的节点），连续放弃到 `MAX_STUCK_GIVEUPS`(3) 次或无其它可达节点时自毁出列；成功够到节点则重置放弃预算
- **清波判定剔除扩展**：卡死 / 无目标的怪物现在会主动 `WaveManager.discardMob`（移出世界 + 从 activeMobs 剔除，不掉货币不记击杀），`isWaveCleared` 不再因最后一只 valid 但静止的怪恒为 false

#### P0-D 空投失控

- **空投受容量上限约束**：空投小怪统一改由 `WaveManager.spawnAirdropMobs` 生成，开投前按 `min(global-mob-cap, mob-cap-per-player × 人数)` 裁剪本轮只数、批次内每只再复查，满了即停；`AirdropSpawnGoal.shouldActivate` 额外在上限已满时退避重试，不再无视上限持续空投导致实体数指数膨胀
- **抛投者生涯空投上限**：新增可配置 `spawning.airdrop-max-per-dropper`（默认 3）。`AirdropSpawnGoal` 记录已空投轮数，达上限后永不再空投；`<=0` 等效于禁用空投
- **批次任务一致性与取消**：空投批次任务记录发起时的波次号，运行期带"dropper 存活 / 波次号一致 / 游戏进行中 / 容量未满"四重守卫，波次切换即停；任务登记进 `WaveManager.airdropTasks`，波次结束 / `haltGame` 结算 / `stopGame` / 停服时随 `clearAllMobs` 统一取消（修复 haltGame 不改状态导致空投任务拦不住、结算期间仍刷怪的问题）
- **空投怪纳管 + 不掉货币**：空投怪现在走 `prepareWaveMob`（获得 persistent + `NodeAttackGoal`），不再是"远离即消失、不触发死亡事件、永久残留 activeMobs 污染下一波清波"的游离实体；并打 PDC 标记 `hilltopvillage:airdrop-mob`，`dropCurrency` 据此跳过，杜绝挂机刷钱与经济污染（击杀统计仍保留）

#### 新增配置项（load/save/snapshot/YAML 四处齐全）

- `wave-timeout-seconds`（顶层，默认 120）：波次超时保险阈值，`<=0` 禁用
- `spawning.airdrop-max-per-dropper`（默认 3）：单个抛投者整局空投轮数上限，`<=0` 禁用空投
- 新增 i18n 键 `wave-stuck-relocate` / `wave-stuck-purge`（en/zh 双语对称）

### Fixed（2026-10-10 — P0 修复：村民环境伤害强制失败漏洞 P0-B）

> 来源：P0 破坏漏洞排查。普通玩家可用泥土活埋 / 挖井摔死 / 引岩浆点燃等零门槛手段杀光村民，
> 触发 `triggerDefeat` 强制全场失败。本轮只收敛村民伤害策略，不改游戏规则 / 配置格式 / 权限节点。

- **村民伤害改为白名单放行**：`VillagerSystem.handleVillagerDamage` 由"只拦玩家直击/横扫/投射物"的黑名单改为白名单——唯一放行条件 = 战斗类 cause（`ENTITY_ATTACK` / `ENTITY_SWEEP_ATTACK` / `PROJECTILE` / `ENTITY_EXPLOSION`）且经 `getDamageSource()` 的 `getCausingEntity()`/`getDirectEntity()` 溯源命中敌对怪物（原版 `Monster` 或 `WaveManager.activeMobs` 登记的波次怪）；其余一切来源（玩家溯源的直击/投射物/药水，及窒息/摔落/岩浆/溺水/爆炸/雷击/火/点燃/仙人掌/虚空等全部环境伤害）一律 `setCancelled(true)`
- **村民锁定部署位置**：生成时追加 `setAI(false)`，村民不再游走、也不会被玩家推入岩浆或坑洞；`setInvulnerable(false)` 保持不变，怪物伤害照常生效（村民可被怪物击杀，非全无敌）
- **回归保留**：甲虫自爆（STORY-1.6，溯源为甲虫，`ENTITY_EXPLOSION` 在白名单内）、普通怪 `NodeAttackGoal`、钩爪猎手仍能正常伤害/击杀村民；烈焰蛋/重锤 AOE 的村民豁免（STORY-1.6 目标过滤）不受影响（玩家溯源本就被白名单取消）
- **无新增 i18n/配置/权限**：拦截为静默取消，不引入面向玩家的新文本

### Fixed（2026-10-10 — P0 修复：自动开局永久失效）

- **P0-E 自动开局永久失效**：`ArenaInstance` 开局倒计时 Runnable 的四个终止分支（state 非 STARTING / 人数不足 / 不可开局 / 倒计时归零自然开局）此前只调 `BukkitRunnable.cancel()`，任务停了但 `startCountdownTask` 字段残留非空；而 `checkAutoStart()` 以 `startCountdownTask == null` 为「可开新倒计时」守卫，且自然结算路径（`finishSession→ENDING→reset→completeResetAfterRestore`）全程不碰该句柄 → 首局自动结束后守卫被永久挡住，等待区再不自动倒计时，只剩管理员手动 `/hilltop start`（每局必现）。四个分支统一改走 `cancelCountdown()`（取消 + 置空），`completeResetAfterRestore()` 回到 WAITING 前再幂等兜底一次 `cancelCountdown()`，确保连续多局都能自动开局；手动开局、人数跌破取消等路径行为不变（无玩家可见文案改动）
- **结算倒计时残留句柄一并收尾**：`endSequenceTask` 的「过期回调静默丢弃」分支补 `endSequenceTask = null`，与 `finishSession` 收尾一致（该字段无 `== null` 守卫、`beginEndSequence` 本就先 `cancelEndSequence` 自愈，属低风险加固）

### Fixed（2026-10-09 — 三维测试 FAIL 修正：code 维度 12 项全修）

> 来源：三维测试 code 维度 FAIL（build/arch 同轮已 PASS）。本轮只动 code 维度指出的问题，
> 不改任何游戏规则、配置格式与权限节点。

- **i18n 硬编码清零（8 处）**：`ArenaCommand` 的 sendHelp/handleArenas/describeIssue/getArenaStatus 改走 LanguageManager（复用 `arena-list-entry` / `arena-status-*` / `state.*` / `usage-*` 既有键，新增 `arena-list-help-*` / `arena-detail-*` / `arena-issue-*` / `arena-status-statistics` 共 15 键，en/zh 双语对称）；`AdminDeployManager.showNodeParticles` 与 `SetupWizard` 两处"没有已注册的节点"/"该命令已废弃"硬编码改走新键 `particles.no-nodes` / `particles.showing-nodes` / `setup.rescan-deprecated`（en/zh）；list/arenas/help 输出现在按发送者语言解析（控制台用全局默认语言）
- **`/hilltop arenas` 口径补齐**：`getArenaStatus` 与 `ArenaSelectionGUI.getStatusText` 判定对齐（补地图缺失分支、补休整期/胜负分支、禁用行改走 `arena-disabled` 键）；`MultiArenaManager.getStatistics()`（硬编码中文整串）改为返回裸计数的 `countByState(...)`，拼串移到 ArenaCommand 的 i18n 键里
- **退出清理补齐（5 处）**：主类 `onPlayerQuit` 追加 `lobbyProtectionListener.cleanupPlayer`（建造模式标记退出即失效）、全竞技场 `scoreboard.cleanupPlayer`（离线玩家摘记分板条目并按需停刷新任务）、`languageManager.clearPlayerLocale`（偏好退出即失效，下次取默认语言）；`ArenaInstance.removeOfflinePlayer` 补 `scoreboard.hide`（重连超时路径的残留条目）；`NumericInputHandler` 新增 `@EventHandler onQuit`（待处理聊天输入静默丢弃，不回调 onCancel，避免对失效连接重开 GUI）
- **LanguageManager 并发安全**：`playerLocales` / `messageCache` 由 `HashMap` 换 `ConcurrentHashMap`；`reload()` 改为"局部拼好 → `Map.copyOf` 不可变快照 → 原子替换"，异步聊天线程读到的永远是某个版本的完整快照，不再有"清掉一半"的中间态
- **控制台可执行管理命令**：`HilltopCommandHandler` 顶层"非玩家一刀切拦截"改为黑名单（仅 join/leave/lobby/gui/hammer/fireball/setup/deploy/config/manage/build 拦控制台）；`start/stop/arenas/list/enable/disable/tps/shop reload`（需 admin 权限）与 `stats <玩家名>` / `top <类型>` / `help` 对控制台放行，玩家行为不变；README 双语命令表新增"控制台"列
- **键对称校验**：en/zh 各新增 18 键（15 竞技场 + 2 粒子 + 1 向导），总数 834/834 对齐

### Fixed（2026-10-09 — 第二波 STORY-1.6 C组部分：烈焰蛋/甲虫 AOE 豁免村民 + 甲虫自爆可配置化）

- **烈焰蛋爆炸豁免村民**：`FireballListener.triggerExplosion` 跳过 `VillagerSystem.isVillageVillager` 标记实体（伤害/点燃/击退全豁免）；`isValidTarget` 探针过滤同步剔除村民，蛋飞过村民头顶不再空爆
- **甲虫自爆可配置化**：新增 `monsters.explode-beetle.entity-explosion`（默认 true 保持行为）；设为 false 时自爆只结算节点伤害 + 纯视觉效果（爆炸粒子/烟雾/音效），不再调用原版 `createExplosion` 炸伤周围村民与玩家；伤害数值不动（P1-2 归 STORY-2.4）
- **怪物配置 GUI**：自爆甲虫页新增实体爆炸开关（绿色/红色羊毛点击切换，en/zh 双语键 `config.menu.monsters.entity-explosion*`）

### Fixed（2026-10-09 — 第二波 STORY-1.8 C组部分：烈焰蛋友伤统一为零）

- **烈焰蛋对玩家伤害统一为 0**：投掷者自伤 2.0 与队友 4.0 全部归零，移除玩家点燃；击退保留（含投掷者延迟 1 tick 击退，火箭跳手感不变）；炸怪伤害/点燃不变；全技能友伤规则统一为零（与重锤只弹飞不伤一致）

### Fixed（2026-10-09 — 第二波 D 组：STORY-1.4 波次 count 精确配比）

- **刷怪配比按 `count` 精确**：每种怪刷 `count × 玩家数` 只（不再纯权重随机），待刷队列打散顺序后按既有节奏（3 tick / 2 只）分批；`total-mobs` 语义修正为"精确总数上限"（`sum(count)×人数` 超过它时按比例裁剪，向后兼容保留字段）
- **兼容回退**：某波次所有 count 全 0/缺失时回退旧权重路径（`weightSelect` 保留）；权重合计 0 时仍走僵尸兜底路径，行为不变
- **GUI/文档同步**：波次列表与详情菜单标注新语义（count="每人只数"、total-mobs="上限"）；game-settings.yml waves 节注释重写（含 2 人局配比示例）；README 双语新增配比说明
- **新增 messages.yml 键**：`config.menu.waves.*` 语义标注行（en/zh 双语）

### Fixed（2026-10-09 — 第二波 D 组：STORY-1.5 全灭软锁修复）

- **tick 复检判负**：`checkWaveCompletion` 每 3 秒复检"无存活玩家 + 无活跃节点 → 失败"，覆盖休整期与只剩猎手/抛投者不清波的原软锁组合；触发后取消自身任务
- **休整期空波拦截**：休整期排队回调加人数守卫，人数不足时走失败结算（新键 `defeat-not-enough-players`，en/zh）而不再开空波
- **判负守卫扩展**：`removeOfflinePlayer` 的人数不足判负从 `state == RUNNING` 扩展为 `isGameRunning()`（覆盖休整期）
- **消灭双份逻辑**：复用 `GameManager.hasAlivePlayer()`，`PlayerLifecycleListener.checkTeamWipe` 改调它

### Added（2026-10-09 — 第一波 STORY-1.2 出生点分离 + starter-kit）

- **出生点分离**：玩家出生点（`spawn-points`）与怪物刷怪点（`mob-spawn-points`）分离为两套独立坐标；旧存档仅有 `spawn-points` 时视为双用点（记 warning 提示补部署），仍可开局；两套点都为空时开局被拦截并提示
- **starter-kit**：开局向每名在场玩家发放 `game-settings.yml` 的 `starter-kit` 套装（默认石剑 + 面包×16 + 火把×8，可配置开关与物品列表）
- **deploy 命令新增** `addmobspawn` / `listmobspawns` / `delmobspawn` / `clearmobspawns` 管理怪物刷怪点；SetupWizard 第 3 步扩展为两套点部署（新增怪物刷怪点管理 GUI + 强制校验 + 预览统计）
- **新增 messages.yml 键**：`arena-no-mob-spawns`、`deploy.mobspawns.*`（16 个）、`setup.step3-mobspawns`、`setup.mobspawn-added`、`setup.mobspawn-removed`、`setup.mobspawns-required`、`setup.gui-mobspawns-title`、`setup.preview-mobspawns`、`setup.btn-open-mobspawn-gui`、`setup.mobspawn-entry`、`setup.kit-received`（en/zh 双语齐全）
- **GameManager 新增接口**：`getMobSpawnPointsView()` / `hasMobSpawnPoints()` / `hasEffectiveMobSpawnPoints()` / `isLegacyDualUseSpawns()` / `setMobSpawnPoints()`（D 组 STORY-1.4/1.5 接口先行）

### Fixed（2026-10-09 — 第一波 STORY-1.3 重锤冷却 + 耐久 + 低档下调）

- **新增 `hammer.cooldown-seconds`**（默认 8 秒，时间戳方案无需 ticker，冷却中激活被拒 + ActionBar 提示剩余秒数）
- **新增 `hammer.max-uses`**（默认 30 次，每次猛击耗 1 次，剩余次数显示在 lore 第 4 行，耗尽碎裂需商店回购）
- **`tier.low.damage` 30.0→20.0**（甲虫 40 血需两下低档，中心命中）；删除未使用的 `tier.low.charge` 开关（GameConfig/ConfigManager/YAML/GUI 同步删，对应 GUI 槽位改放冷却配置）

### Fixed（2026-10-09 — 第一波 STORY-1.6 B组部分 + STORY-1.8 B组部分）

- **STORY-1.6（B 组部分）重锤 AOE 豁免村民**：`VillagerSystem` 新增公共方法 `isVillageVillager(Entity)` 供三处 AOE 共用（C 组 FireballListener/甲虫直接调用）；`HammerListener.applyAreaDamage`/`applyAftershock` 跳过标记村民（伤害/击退/减速全豁免）
- **STORY-1.8（B 组部分）重锤/修节点对局状态检查**：`HammerListener.onPlayerInteract` 与 `NodeListener.onPlayerInteract` 加 `isGameRunning()` 检查（休整期允许，RUNNING/休整期均正常），等待区/结算期触发被拒并提示

### Fixed（2026-10-09 — 第一波 STORY-1.1 命令权限漏洞修复）

- **`hammer` / `fireball` / `setup` 追加 `hilltopvillage.admin` 权限检查**：无权限回 `no-permission`（复用既有键），OP/管理员行为不变
- **Tab 补全隐藏 `hammer` / `fireball`**：无权限玩家一级补全不再看到这两条（`setup` 已在 admin 列表，无需改）
- **删除 `hilltopvillage.player` 权限节点**：代码零引用，同步删 plugin.yml 节点 + README 双语权限表相关行
- **README 双语命令表**：hammer/fireball 标注（管理员），权限表删除 player 行

### Fixed（2026-10-09 — 第一波 STORY-1.7 热重载补齐）

- **新增 `/hilltop shop reload` 分支**：原为 TabCompleter/README/usage 提及但 Handler 无分支的死命令，现真实调用 `ShopManager.reload()` 并回显商品数；非 reload 子参数回 usage 行；非 admin 拒绝，控制台拒绝（players-only）
- **`reloadconfig` 顺带重读 messages.yml**：追加 `LanguageManager.reload()`，回显新增 `messages-reloaded` 键（en/zh）
- **README 双语重载口径**：reloadconfig = game-settings.yml + monsters-config.yml + messages.yml；arenas.yml 仍需重启（保留既有说明）

### Added（2026-10-09 — 第一波 P0：STORY-0.2 + STORY-0.3）

- **STORY-0.2 文档小债务批处理**：plugin.yml usage 补全 24 个子命令；messages.yml zh 区 13 个重复键去重（删旧版"喵"体、保留守卫战新版，en/zh 各 816 键对齐）；help 输出与 TabCompleter 的 admin 判定已一致（已分组、无需改代码）；deploy 菜单补村民两行 + 4 个新 i18n 键（en/zh）；goto 上限 1-7→1-8、deploy.cmd-setup 6步→8步、商店步骤"跳过"改为"必填"（en/zh 文案 + README 双语同步）；README 双语依赖统一 Paper 1.21.4+/Java 21、删不存在的 `deploy help`（实为 `confirmclear`）、补全 setup 子命令表（preview/goto/villagers-done，删不存在的 skip-shop）、help 标注管理员、README-EN 补齐商店必填/村民必填说明；game-settings.yml 废弃字段注释明确"已废弃：不再读取"
- **STORY-0.3 工程地基**：新增 `docs/07-guides/JDK21-BUILD-GUIDE.md`（JDK21 构建指引）与 `docs/07-guides/TEST-FRAMEWORK-SPIKE.md`（JUnit5 go + MockBukkit no-go 结论）；新增示例测试 `src/test/java/.../SpikeSanityTest.java`（纯逻辑、零 Bukkit 依赖，`mvn test` 可跑，3/3 通过）；pom 仅加 JUnit5 test-scope 依赖（无 MockBukkit），`mvn clean package` 保持 BUILD SUCCESS 零警告

> 本版本为持续开发与服务器实测版本，包含部署向导、Multi-Arena Phase 1、i18n 修复、性能优化和村民商店系统。

### Changed（2026-10-07 — 性能审计批次 C：热路径）

> 来源：`docs/01-reviews/PERF-AUDIT-2026-10-06.md` 第 8–20 条。本批全部是热路径上的
> 分配与发包优化，**不改任何配置格式、命令、权限与游戏规则**。逐项可独立回滚（按文件粒度）。

- **`HammerEffectRenderer` 三档猛击特效改用 double 坐标重载**：原实现每次
  `spawnParticle` 前都 `center.clone().add(...)` 造一个 `Location`，高档一次猛击要造 467 个。
  改用 `(double x, double y, double z)` 重载后归零。三份恒定的
  `Material#createBlockData()` 同时提为静态常量（每次猛击少建 5 个对象）
- **猛击粒子调用次数 375 → 210（高档 257 → 140，降 45%）**：环上点位减半、单点 `count` 翻倍，
  **粒子总数不减少**（1348 → 1367）。代价是高档第 2/3 环的点位间距由 0.8/0.9 格拉到
  1.6/1.7 格，环上点位会变稀 —— 已列入实测单由服主目视确认。火焰旋涡（间距 0.25→0.50 格）
  与中档内环视觉上看不出差别
- **`ArenaScoreboard` 加两级短路**：① 整帧输入签名（波次/存活/旁观/节点/村民/击杀/阵亡 +
  玩家 locale）命中即整帧跳过；② 未命中时逐行比对文本，只重发真正变化的行。
  `PlayerTeam#setPlayerPrefix` 在服务端**没有**相等性检查（已反编译核实），文本没变也会
  重发整个 Team 包 —— 静止时从「每秒 7 次发包」降到 0
- **`ArenaScoreboard` 标题随 locale 刷新**：locale 纳入签名后，玩家用 `/hilltop lang` 切语言时
  侧边栏标题一并跟进（原实现每秒重拼字符串，1 秒内必然跟上；只加签名会让标题停在旧语言）
- **`LanguageManager` 颜色码预编译 + 单遍占位符替换**：`translateAlternateColorCodes` 从
  「每次取词」提前到「`reload()` 构建缓存时」，且无 `&` 的文本走捷径直接返回原串；
  `{0}` 填充改为单遍 `StringBuilder`，替代原来的 `String.replace` 链（每次分配 int[16] 索引数组）。
  等价性已用对照程序穷举验证（模板字符集 {A,{,},0,1} 长度 ≤6 全部组合 + 40 万次随机 fuzz，
  共 51.7 万组输入）：两者只在下述 **4 类**输入上不同，且 4 类在本插件中**均不可达** ——
  ① 模板含嵌套/交叠大括号（`messages.yml` 匹配数为 0）；② 数字索引带前导零如 `{01}`
  （全部占位符只有 `{0}`–`{3}` 与 `{world}`，`\{[0-9]{2,}\}` 匹配数为 0）；
  ③ 替换值自身含大括号（55 处多参数调用点的参数来源全部核对过：玩家名、世界名、竞技场 ID、
  数字、`String.format` 结果、`shop.yml` 商品名，无一能含大括号）；
  ④ 模板含落单大括号（去掉成对占位符后匹配数为 0）。另有一处纯健壮性差异：
  `args == null` 时原实现 NPE、新实现返回模板原串，本插件无 `null` 调用点
- **`ShopManager` 缓存货币显示名 + `ShopListener` 复用余额**：`isCurrency` 原来每扫一格背包就跑一次
  `translateAlternateColorCodes` + `lang.get`，现改为 reload 时算好；一次购买的 3 次全背包扫描降到 1 次
- **`FireballListener` 快照收窄为 5 个标量**：原来每发烈焰蛋都 `cfg.snapshot()` 深拷贝整份
  `GameConfig`（含 monsters + waves，20+ 对象），实际只读 5 个数值。改用 `record` 后每发只分配 1 个对象
- **`WaveManager` 兜底刷怪改分批**：`compositions` 被清空 / 权重为 0 时，原来单 tick 同步生成
  `5+2×波数` 只（第 20 波 = 45 只），现改为与正常路径相同的「3 tick 一批、一批 2 只」
- **4 个自定义 AI Goal 复用常量**：`GoalKey.of(...)` 每次调用都新建对象（已反编译核实无缓存），
  且顺带 `new NamespacedKey(...)`；`EnumSet.of(...)` 同样每次新建。全部提为静态常量
- **`NodeSystem` 半径扫描直读 `node.location`**：`getLocation()` 每次返回 `location.clone()`，
  而两处扫描只读坐标。改为直读不可变字段 + 手算 3D 距离平方，省掉每节点一次 `Location` 分配
- **`GameManager` 新增 `getSpawnPointsView()`**：`getSpawnPoints()` 每次 `new ArrayList<>(...)`，
  刷一波 80 只怪就要拷 80 次小数组。热路径改用只读视图（元素仍 `clone()` 后再偏移，不改内部状态）
- **`ArenaInstance.clearDroppedItems` 改用 `getEntitiesByClass(Item.class)`**：不再把世界里
  全部实体物化进 List 再过滤
- **`MapRestoreManager` 日志加容量上限（100 万条/世界）**：`OriginalBlock` 单条约 140–170 B，
  500 万方块 ≈ 700–850 MB，而堆只有 3 GB。超限后停止记账并 SEVERE 告警一次
  （每世界一次，日志销毁时重置标记），让服主知道本局回滚将不完整，而不是整个服务器 OOM
- **`MapRestoreManager.BlockPos` 由 record 改为手写并缓存哈希**：record 的默认 `hashCode`
  走 `ObjectMethods` + `StringConcatFactory`（拼字符串再哈希），而本类型是爆炸/水流记账路径上的
  HashMap key，每方块都要查表。**收益来自「构造时缓存」而非公式选择**（手写不缓存的实测值与
  record 基本相同），哈希公式 `(x*31+y)*31+z` 与 record 默认实现**不等价**（差常数项 `31³`），
  但 HashMap 只要求 `hashCode` 与 `equals` 自洽，任何一致映射都合法

#### Fixed（2026-10-07 — 批次 C 三维测试发现的 2 项，均非本批引入）

> 来源：`docs/05-test-reports/batchC-code-rest.md`。两项都是**既有**问题（HEAD 上即可复现），
> 属"改动极小、零风险、顺手清掉"，与本批优化无功能耦合。

- **`ShopConfigLoader` 负数价格未钳制**：`price` 直接取 `yaml.getInt(path + ".price", 1)`，无下限。
  而 `ShopManager#removeCurrency` 在 `amount <= 0` 时直接 `return true` 且不扣任何货币 ——
  服主误配 `price: -1` 会变成**免费发货**。现钳到 `0` 并打 WARNING，使「0（有意免费）」与
  「负数（误配）」在日志里分得开，且不会像非法 type/slot 那样整条 skip 掉
- **`ShopListener.purchase` 忽略 `removeCurrency` 返回值**：当前同 tick 同线程内不会失败
  （调用前刚数过余额，且 `removeCurrency` 内部会复核），属**安全但脆弱** —— 将来若有人把购买
  流程挪进异步或改成多线程，就会退化成「发货但不扣款」的静默错扣。补一行防御性
  `if (!shopManager.removeCurrency(...)) { 提示余额不足并 return; }`，走与前置检查相同的反馈

#### 批次 C 中**刻意未做**的项（附理由）

- **光柱粒子不做「一次调用 + 大 offset」合并**：性能审计原建议把整根柱子合并成 1 次
  `spawnParticle`（count=51、offset=(0,10,0)），但那要求 offset 是「均匀覆盖范围」。
  已用 `javap` 在 paper-1.21.4 上核实 `ServerLevel.sendParticlesSource` 与
  `ClientboundLevelParticlesPacket`（字段 `xDist`/`yDist`/`zDist`）只是把 offset 原样下发，
  不做范围变换；但 offset 在**客户端**的语义（`count > 0` 时为高斯标准差 σ、
  `count == 0` 时才当速度）来自既有知识 —— 该 jar 是纯服务端包，
  不含 `net/minecraft/client/*`，本机无法核实这一点。
  好在两种口径下结论一致：σ=10 或 ±10 均匀范围都会让约 32% 粒子溢出柱外、
  密度中浓端淡、地面起点消失。收益（51→1 次调用）远小于视觉回归，故保留逐点循环
- **猛击环不做「按弧长动态取点」**：曾考虑按周长算点位以恒定间距，但那样半径 7.0 的外环
  仍要 29 个点（省不下多少），而本次的「点位减半」方案省得更多、代价只是环变稀。
  两者不能兼得，取了「省包优先」并如实记录视觉代价

### Changed（2026-10-07 — 性能审计批次 B：主线程 I/O 异步化）

> 来源：`docs/01-reviews/PERF-AUDIT-2026-10-06.md`。本批把两个持久化组件的主线程全量同步写盘
> 改为「主线程序列化 + 异步/定时写文件」，并补齐一处掉线玩家的统计丢失。

- **`StatsManager` 统计落盘改为定时异步刷盘**：原来玩家每次离开竞技场都触发一次
  **主线程全量重建 + 同步写盘**（历史玩家越多越慢）。现拆为 `buildSnapshot()`（主线程序列化）
  与 `writeAtomically()`（纯文件写），并新增 60 秒定时异步刷盘 —— 一局结束的 N 次写盘合并为 1 次，
  且完全移出主线程。关服走 `saveNow()` 仍同步落盘
- **`PlayerGoodsManager` 物品快照改为防抖 + 异步写**：原来任意一名玩家进出竞技场都会把
  **所有历史玩家**的背包快照重写一遍（主线程同步 I/O）。现拆为 `buildYaml()` / `writeYaml()`，
  1 tick 防抖窗口内合并成一次，序列化留主线程、文件写丢异步线程。
  （窗口取 1 tick 而非更长：合并收益来自「同一 tick 内多次调用合并」，一局结束时 6 名玩家
  在同一个 tick 里依次离场，1 tick 已足以合并；窗口越长，「内存已删快照、磁盘仍是旧内容」
  的不一致窗口就越长，而非优雅关闭时那条窗口会导致物品重复归还）
- **修复掉线超时玩家的本局数据全丢**：`ArenaInstance#removeOfflinePlayer` 从不调用
  `statsManager.record`（它需要在线 `Player`），而 `GameManager#removeOfflinePlayer` 会
  `playerDataMap.remove(uuid)` —— 该玩家本局的击杀 / 阵亡 / 节点修复全部丢失。新增
  `StatsManager#recordOffline(UUID, String, PlayerData, Boolean, int)` 重载，并把累加逻辑抽成
  共用的 `accumulate(...)`，使「在线离开」与「掉线超时」走同一套语义
- **`onDisable` 补物品快照落盘**：原来只落统计，防抖窗口内未落盘的物品快照在关服时会丢
- **玩家退出时同步落盘物品快照**：防抖引入了一条玩家可主动制造的刷物品路径 ——
  `restore()` 只登记防抖任务，玩家随即退出（背包已被 Bukkit 写进 `.dat`），若这 2 秒内服务器
  非优雅关闭，磁盘上仍是归还前的旧快照 → 下次登录会**再归还一次（物品翻倍）**。
  `PlayerQuitEvent` 现在当场同步写盘，堵死这条时序

#### 同批修复的并发缺陷（三维测试与编排者复核发现）

- **临时文件撞车导致 YAML 损坏**：两个组件的异步写与关服同步写会并发，共用固定
  `*.yml.tmp` 会让两个线程写同一文件 → 内容交错 → **损坏的 YAML 让下次启动解析失败、
  静默丢掉全部数据**。改为唯一临时文件名（`nanoTime` 后缀）+ `writeLock` 串行化 +
  `finally` 清理残留临时文件
- **脏标记竞态导致数据永不落盘**：`StatsManager` 的清脏标记原在异步的写入方法内，
  会吞掉「快照构建完成 → 写入完成」之间主线程新产生的改动，下一轮刷盘又因 `dirty == false`
  跳过。改为「主线程构建快照**之前**清脏」，写入方法只在失败时置回
- **陈旧异步写覆盖关服内容**：`PlayerGoodsManager` 的在途异步写持有 2 秒前的快照，
  若比 `saveNow()` 后拿到锁会覆盖成陈旧内容 —— 这是**关服屏障被破坏**（不是「丢 2 秒增量」）。
  新增单调递增写序号 `writeSeq`，锁内比对后放弃过期的写入
- **刷盘任务被自动取消后不再重建**：Bukkit 重复任务在 `run()` 抛异常时会自动取消自己而句柄
  仍非 null，只判空会让刷盘永久停摆。`ensureFlushTaskRunning()` 补 `isCancelled()` 检查
- **`bestWave` 未夹负值**：与 `wavesSurvived` 的 `Math.max(0, wave)` 保持对称

### Fixed（2026-10-06 — 性能审计批次 A：3 处正确性缺陷 + 4 项低风险热路径）

> 来源：`docs/01-reviews/PERF-AUDIT-2026-10-06.md`。本批只做「最小改动、最高收益」的 7 项，
> 全部为低风险改动，不涉及配置格式、命令与权限变更。

- **修复玩家统计 `kills` / `deaths` / `nodes-repaired` 恒为 0**：`ArenaInstance` 曾自建一份
  `playerDataMap`，但所有增量写（击杀 / 阵亡 / 节点修复）都打在 `GameManager` 的那份上，
  而结算读的是自己那份从未更新的空副本。删除该影子映射，`getPlayerData()` /
  `removePlayer()` / `rejoin()` / `broadcastSessionStats()` 全部改为委托 `GameManager`
- **修复「玩家已回大厅却仍被判为在场内」的幽灵映射**：`playerArenaMap` 只在 `MultiArenaManager#leaveArena`
  清理，而批量踢人（`stopGame` / `finishSession` / `resetNow`）直接调 `ArenaInstance#removePlayer`，
  映射因此残留，导致登录落地、大厅复活点、退服重连窗口、烈焰蛋竞技场判定全部走错分支，
  且映射表按对局数无界增长。`ArenaInstance` 的两条移除路径现统一回调新增的
  `MultiArenaManager#forgetPlayerArena`；`disableArena` / `stopArena` 退役时按 arenaId 兜底清理
- **修复波次可被「非死亡消失的怪物」永久卡死**：波次怪只在 `EntityDeathEvent` 里从 `activeMobs` 移除，
  而区块卸载 / 其它插件清理 / 掉出世界都不触发死亡事件，导致 `isWaveCleared()` 恒为 false、
  波次判定任务每 3 秒空转到本局结束，同时集合永久持有无效实体的强引用。
  新增 `markWaveMobPersistent()`（`setPersistent(true)` + `setRemoveWhenFarAway(false)`）
  在 4 条刷怪路径全部调用；`isWaveCleared()` 增加失效实体清理兜底
- **`WaveManager.activeMobs` 由 `CopyOnWriteArrayList` 改为 `ConcurrentHashMap.newKeySet()`**：
  刷怪与怪物死亡都是密集写，COW 每次写入复制整个底层数组，单波 80 只产生 O(n²) 引用拷贝。
  新增 `addActiveMob()` 作为唯一写入入口，`getActiveMobs()` 改返回只读视图
- **删除 `NodeListener` 的空事件处理器**：`onNodeDamage(EntityDamageByEntityEvent)` 方法体
  在类型判断后直接结束、无任何逻辑，却已先做了竞技场线性查找 —— 而该事件是全服最高频事件之一
- **`LobbyProtectionListener#onMove` 判断顺序调整**：原实现先做昂贵的 `inLobby()`
  （YAML 路径解析 + 带 `toLowerCase` 的世界查找）再判断 Y 轴；虚空保护只在 `Y <= 0` 时才需要，
  改为先做免费的 Y 轴判断，语义完全等价
- **`HookClawHunterGoal` 用 `setRotation` 替代 `teleport` 做纯视角调整**：`teleport()` 会构造
  `EntityTeleportEvent` 并走完整传送流程（位置同步 + 速度重置），而这里只需要改朝向；
  同时删除 `findValidTarget` 中的冗余 `contains` 判断
- **`AirdropSpawnGoal` 扫描失败退避**：`airdropCooldown` 只在 `stop()` 里赋值，而 Goal 未 `start()`
  就不会 `stop()`，因此一旦找不到落点，`findDropLocation()` 会被**每 tick** 重放
  （每次 = 玩家数 × 5 + 10 次同步高度图 / 方块读取）。新增 20 tick 退避；
  并补上 `world == null` / `core == null` 守卫（后者原本会 NPE）
- **顺带修复同型的第三处影子状态：`ArenaInstance.currentWave` 恒为 0**（架构测试发现，与上一条同源同型）：
  它只在构造与开局时被赋 0、之后永不更新，导致传给 `StatsManager` 的 wave 恒为 0
  （`wavesSurvived` 对所有人永久 +0、`bestWave` 永远为 0），以及 `/hilltop arenas` 永久显示 `波次: 0/N`。
  已删除该字段，`getCurrentWave()` 与结算改为读 `GameManager`
- **顺带清理**：删除 `ArenaInstance` 中从未被读取的死字段 `displayEntityManager` 及其构造

### Added（2026-09-15 — 竞技场管理 GUI `/hilltop manage`）

> 面向管理员的一站式竞技场管理入口：任意竞技场（含未部署 / 未启用 / 世界缺失）的查看、单条删除、
> 游戏参数编辑与「恢复初始状态」。**不影响**既有 `/hilltop arenas` 文本输出与玩家侧 `/hilltop gui` 选图界面。

- **新增命令 `/hilltop manage`**（管理员，权限 `hilltopvillage.admin`）：打开 54 格管理菜单，7 个视图用后退栈串联
  - `LIST` 列出全部已配置竞技场，包括 `enabled: false`、世界文件夹缺失、从未部署的场次（一个不漏）
  - `DETAIL` 显示状态、世界、人数、波次、部署计数、商店位置与参数摘要，并提供启用 / 禁用、传送核心、「恢复初始状态」入口
  - `SPAWN_LIST` / `NODE_LIST` / `VILLAGER_LIST` 分页展示坐标（45 条/页），**左键传送**、**右键删除**、**Shift+左键**粒子预览
  - `PARAMS` 编辑 4 个 per-arena 游戏参数（人数上下限 / 通关波数 / 波次间隔），左键在聊天栏输入新值
  - `RESET_CONFIRM` 二次确认页，附完整清除清单与**实际在场人数**，并提供二次取消按钮
- **新增「恢复初始状态」**（`ArenaResetService`）：清空该竞技场的部署数据（core / spawn-points / villagers / shop-npc / game.* / 半径偏移）、重置游戏参数、**强制回滚地图方块**，之后可重新走一次 `/hilltop setup` 部署
  - 12 步序列，每步幂等；任一步失败即中止后续并逐项上报结果，绝不在 arenas.yml 未清干净时去重新启用
  - 7 种准入判定（`Gate`）：竞技场不存在 / 启用队列加载中 / 缺核心坐标 / 对局进行中 / 上次回滚未完成 / 已有重置在跑；对局中会提示先执行 `/hilltop stop <id>`
  - 每步写 `manage-reset` 前缀日志（INFO 步骤 / WARN 中止 / SEVERE 回滚失败），含 arenaId、world、耗时
- **新增 `ArenaAdminService`**：管理 GUI 的只读数据层 + 单条删除，对**任意**竞技场生效——
  有实例时走 `AdminDeployManager` / `NodeSystem`（内存），无实例时直接读 `arenas.yml` 与 `nodes.yml`（磁盘）
- **新增 `LocationParticlePreview`**：按玩家隔离的坐标粒子预览（`UUID → task`），文案走 i18n，连续预览两个列表不会互相打断
- **新增 `AbstractAdminMenu`**：在 `AbstractConfigMenu` 之上补两个守卫——
  - `guardDrag` 拦 `InventoryDragEvent`（父类未处理，物品可被拖出）
  - `guardClick` 在玩家背包格上取消事件（父类在点击自身背包时不取消，shift-click 可塞物品）
  - ⚠️ 两者都以**不同方法名 + `EventPriority.LOW`** 叠加，不覆写父类 handler：Bukkit 按 `getMethods()` 枚举 `@EventHandler`，覆写会替换而非叠加
- **竞技场核心坐标（core）改为可为空**：`core == null` 表示「尚未部署」，此类竞技场仍留在 registry 里并显示为未部署（而非从列表消失），但不能 `enable`（提示先走部署向导）
- **商店 NPC 位置改为按竞技场隔离存储**：`arenas.<id>.shop-npc`（原为全局 `spawnpoints.yml`）；启动时自动迁移旧数据（世界匹配才迁移，旧键保留不删）
- **新增游戏参数写入口**：`ArenaManager.updateGameParams` 与 `ArenaInstance.applyGameParams`
  - 写盘基于磁盘最新内容操作，不调用 `ArenaManager.save()`（后者会用固化旧值覆写 core / spawn-points）
  - 写盘成功后把新值推送到活动实例，**无需重启**：人数下一局生效，波次间隔与波数从下一个休整期起生效
- **新增 `NodeSystem.clearAllNodes()` 与三个静态工具**：`readNodesForWorld` / `clearNodesForWorld` / `removeNodeEntryForWorld`（未启用竞技场没有 `GameManager`，拿不到实例方法）
- 新增 i18n 键 `manage.*` 共 106 个（en + zh 各 106，一一对应）

### Fixed（2026-09-15 — 竞技场管理 GUI 的三维测试缺陷修复）

- **修复 `victory-waves` / `wave-interval-seconds` 只显示不生效**：`ArenaInstance.getTotalWaves()` 把 per-arena 波数显示在记分板上，而 `GameManager` 实际读的是全局 `game-settings.yml` 的值 —— 出现「记分板写 20 波、第 10 波就结算」的显示与逻辑不一致。现 `GameManager` 改为 per-arena 优先、拿不到实例时回落全局值（`getArenaVictoryWaves` / `getArenaWaveIntervalSeconds`）
- **修复单条删除后可能被"复活"**：`ArenaAdminService` 直接改 `arenas.yml`，而已启用竞技场的 `AdminDeployManager` 构造时缓存了整份副本，下次部署会用旧副本回写、把刚删掉的条目整条恢复。现删除成功后调用新增的 `MultiArenaManager.reloadAllDeploymentData()`
- **修复重置后被旧数据复活的商店 NPC**：新增 `arenas.<id>.shop-npc-migrated` 标记键，区分「旧版本还没迁移」与「管理员重置/主动清除过」。重置会删 `shop-npc` 但**重新写上**该标记，因此下次加载不会从 `spawnpoints.yml` 把刚清掉的位置再迁移回来（标记键刻意不在 `DEPLOYMENT_KEYS` 里）
- **补齐缺失的 i18n 键 `manage.reset.done-missing-core`**：此前复用确认页的清单项 `manage.reset.warn-core`（「· 核心坐标（竞技场将变为未部署）」），挪到结果播报里读起来像还没执行
- **拆开 `manage.reset.done-not-reinstated` 的三种语义**：该键断言「配置中 enabled: false」，却被 `NOT_READY_BEFORE`（重置前本来就没启用）与 `ENABLE_REJECTED`（被启用队列拒绝）复用，会让管理员去 arenas.yml 里找一个并不存在的 `enabled: false`。现拆为 `done-not-ready-before` / `done-not-reinstated` / `done-enable-rejected` 三键
- **`ArenaManager.validGameParams` 改为公开静态**：GUI 写入口与加载校验共用同一份判定，避免「GUI 允许写入的值却过不了加载校验」，那会让竞技场下次加载时被判无效、直接从列表消失
- **「恢复初始状态」补上商店 NPC 实体清理**：此前只删掉 `arenas.<id>.shop-npc` 这个**位置**（= 下次不再生成），已经站在世界里的村民实体不会消失，且它带 PDC 标记、右键仍能打开商店。现按「实体所在世界 == 本竞技场世界」精确清除（刻意不用 `NpcManager.cleanupOrphans()`，那会连大厅与其它竞技场的商店一起删掉）
- **`ArenaScoreboard` 的波数改取竞技场定义**：原来读 `gameManager.getTotalWaves()`，而该字段要等 `startGame()` 才同步成 per-arena 值，等待期会先显示全局值，出现「20 波突然变 10 波」的跳变

### Fixed（2026-09-17 — 竞技场管理 GUI 修复轮复核后的缺陷修复）

> 由三维测试（arch 维度）复核发现并经人工逐一核实。

- **修复 `AdminDeployManager.saveSpawnPoints()` 用陈旧的整份副本覆写 arenas.yml**（本轮引入，高危）：构造函数把整份 arenas.yml 读进内存，`saveSpawnPoints()` 只在 `arenasConfig == null` 时才重读、随后整份 `save()` —— 期间任何其它写入方（管理 GUI 的参数编辑、`clearDeploymentData`、**其它竞技场**的任何部署命令）落盘的内容都会被整份回滚。现改为与 `saveShopNpcLocation()` 相同的写法：**每次写入前无条件重新读盘**。
- **修复 `loadSpawnPoints()` 在世界未加载时先清空内存再返回**：清空在前、判空在后，导致「世界未加载 → 内存被清空 → 之后任意一次部署命令把空的生成点列表写盘 → 竞技场变成未部署」。现已把判空移到清空之前。
- **修复参数写入后未刷新其它竞技场的部署缓存**：`ArenaAdminService.updateGameParams` 现与删除路径对称，写盘成功后调用 `MultiArenaManager.reloadAllDeploymentData()`，顺带刷新各 `AdminDeployManager` 的生成点/核心内存副本。

### Fixed（2026-09-13 — A 类缺陷修复：配置"改了不生效"系列）

> 本轮针对"管理员按 GUI / README 操作、拿到成功提示、游戏内却毫无变化"这一类缺陷做系统性修复。

- **修复节点修复物品/修复量存在新旧两套配置字段导致"改了没效果"**：
  - 现象：`/hilltop config nodes repairitem DIAMOND` 与节点 GUI 的"单次修复量"给出设置成功提示，游戏内却无变化
  - 根因：`GameConfig` 存在两套并行字段 —— GUI/命令写的是旧的 `nodeRepairItem`/`nodeRepairAmount`，而 `NodeListener` 读的是新的 `repairCostItem`/`repairAmountPerCost`；两者 Java 默认值恰好相同（`GHAST_TEAR`/`50.0`），因此"开箱可用但改不动"
  - 修复：删除旧字段，写入方统一指向新键；配置键更名为 `nodes.repair-cost-item` / `repair-cost-amount` / `repair-amount-per-cost` / `repair-cost-name`；新增旧键自动迁移（`nodes.repair-item` / `nodes.repair-amount` 迁移后从文件删除，仅首次启动提示一次）
- **修复节点修复参数与节点增益需重启才生效**：
  - `NodeListener` 原将修复参数缓存在 `final` 字段、`NodeSystem.loadConfig()` 原只在构造时调用一次，导致 GUI / `reloadconfig` 的修改不生效
  - 改为实时读取 `activeConfig`，并在增益任务中周期性重载配置（最迟约 3 秒生效）
- **修复 `GameManager` 自建 `ConfigManager` 导致配置改动读不到**（根因缺陷）：
  - 现象：`NodeSystem` / `WaveManager` / 各 AI Goal 通过 `gameManager.getConfigManager()` 读取的是一份**独立快照**，永远看不到 GUI 与 `reloadconfig` 的改动
  - 修复：`GameManager` 改用插件级共享单例（`plugin.getConfigManager()`），消除重复解析 YAML 的开销，同时修复 `SetupWizard.saveToFile()` 与 GUI 写入不同实例、互相覆盖的隐患
- **`config.yml` 瘦身为纯内部状态文件**：移除全部死配置（`game`/`hammer`/`nodes`/`monsters`/`waves`/`spawning`/`messages` 七段从未被读取，且数值与 `game-settings.yml` 互相矛盾），仅保留 `language` 与部署向导进度，并加醒目指引
- **节点方块固定为重生锚（RESPAWN_ANCHOR）**：移除误导性的 `config nodes add` / `config nodes remove` 命令与 GUI 入口及对应文案（该限制为有意设计，此前命令会提示成功但无效）；删除 `nodes.block-types` 配置项
- **`monsters-config.yml` 新增生效字段 `entity-type`**：可自定义三种特殊怪物的实体类型；文件头注释明确区分"生效字段"与"请改 game-settings.yml"的字段（`health`/`speed` 等 10 项此前被静默忽略）；新增可生成性校验（非 `Mob` 类型回退默认值并提示）
- **节点增益效果改为可配置**：新增 `nodes.buff-effects`（格式 `"效果名:等级"`）、`buff-refresh-ticks`、`buff-stack-cap`、`buff-duration-ticks`，此前 BUFF 类型与等级完全写死
- **`/hilltop reloadconfig` 增加竞技场参数提示**：明确 `arenas.yml` 的修改需重启服务器生效（新增 i18n 键 `config.reload-arena-notice`）
- **配置保存不再整段清空 `monsters` / `waves` 段**：保留管理员手写的键与注释（代价是已删除的怪物/波次可能残留旧键，已在源码注释说明）
- **修复 `VillagerSystem` 监听器泄漏**：原每个 `ArenaInstance` 注册一个监听器，而 `disableArena`/`stopArena` 从不注销，反复开关竞技场会累积监听器（内存泄漏 + 村民受伤被重复处理）；改为全局单例 `GlobalListener` 注册一次、按世界路由回对应竞技场，并加了 `instanceof Villager` 类型短路
- **`arenas.yml` 的 `settings` 段接线生效**：`start-countdown` / `arena-reset-delay` / `auto-start-arenas` / `auto-leave-on-quit` 四键此前从未被读取（行为硬编码），现按配置生效，缺键时保持原行为
- **修复权限节点拼写错误**：`ArenaCommand` 中 `hilltop.admin` → `hilltopvillage.admin`（全库 20 处权限检查中唯一的漏网之鱼，导致仅有正确权限的管理员看不到帮助里的管理员命令段）
- **修复英文服显示中文的默认值**：`ShopConfigLoader` 的 NPC 名默认值改为 `null` 交由 i18n 处理；`shop.yml` 的 `npc.name` / `gui.title` 改为空值
- **修复 `shop.gui.size` 白名单缺 `36`**：配置 36 会被静默钳到 27，现补入白名单并对非法值打印 warning
- **清理重复配置读写**：`shop.currency-drop-chance` / `-amount` 由重复 3 次去重为 1 次；补 `spawning.spawn-y-offset` 进配置模板
- **`CommandsDocumentGenerator` 同步更新**：移除已删除的 `nodes add/remove` 文档条目，修正 `reloadconfig` 说明与实际不符之处
- 新增 i18n 键 `config.unknown-sub`、`config.menu.nodes.block-types-fixed`、`config.reload-arena-notice`（en + zh）

### Fixed（2026-09-12 — 实测反馈第十轮：等待中死亡后无法再加入竞技场）
- **放宽 `/hilltop join` 的「已在竞技场中」判定**：
  - 现象：在未部署竞技场死亡 → 重生到大厅 → 再执行 `/hilltop join` 被 `already-in-arena` 拦住，玩家卡死无法加入
  - 根因：上一轮的自愈逻辑只在「玩家不在该竞技场世界」时触发，若重生落点仍在竞技场世界内（或玩家实际并未离开）就绕过了自愈
  - 修复：改为按**对局是否进行中**判定 ——
    - 对局进行中且人在场内 → 保持拒绝（不能半路换场）
    - 其余情况（等待中的成员 / 死亡后重生到别处 / 被外部插件传送）→ **自动离开原竞技场**（归还物品、移出名单）后继续加入，并提示 `arena-auto-left`
  - 新增 i18n 键 `arena-auto-left`（en + zh）
- **非对局期间的死亡重生到等待区**：
  - `PlayerLifecycleListener.onRespawn` 在「非对局进行中」时，若玩家仍是某竞技场成员，则把重生点设为该竞技场的**等待区**（而非原版世界出生点），保持「在场内」的状态一致，避免玩家被丢到世界出生点或大厅造成困惑

### Fixed（2026-09-12 — 实测反馈第九轮：未开局误判失败 / 战报占位符错位）
- **修复未开局（等待区）时玩家死亡导致误判「全员阵亡 → 失败」**：
  - 现象：管理员加入未部署竞技场后在等待区摔死，控制台/聊天立即输出「失败！全员阵亡且已无活跃能量节点」并进入 20 秒结算倒计时
  - 根因：`PlayerLifecycleListener.onDeath` **没有对局状态守卫** —— 任何死亡都会累计阵亡数并触发 `checkTeamWipe()`；而未部署竞技场没有任何活跃节点，单人死亡即满足「无存活玩家 + 无活跃节点」的判负条件
  - 修复：
    - `onDeath` / `onRespawn` 增加 `gameManager.isGameRunning()` 守卫，等待区与开局前的死亡完全交还原版处理
    - `checkTeamWipe()` 增加二次守卫
    - `ArenaInstance.triggerDefeat()` 增加守卫：只有对局进行中才允许判定失败
  - ⚠️ 注意守卫放在 `triggerDefeat()` 而非 `beginEndSequence()`：`GameManager.endGame()` 会**先改 state 再回调**，若在 `beginEndSequence()` 里判断 `isGameRunning()` 会导致正常胜利/失败也走不完结算
- **修复本局战报占位符错位显示 `{3}`**：
  - 现象：`0-击杀: 0|阵亡: 0|修复节点: {3}`
  - 根因：`defeat-stats-line` 模板是 4 个占位符（`{0}` 玩家名 / `{1}` 击杀 / `{2}` 阵亡 / `{3}` 修复节点），但 `broadcastSessionStats()` 只传了 3 个参数（击杀/阵亡/修复节点），导致整体错位且 `{3}` 未被替换
  - 修复：补齐 `player.getName()` 作为 `{0}`，与模板一一对应

### Fixed（2026-09-12 — 实测反馈第八轮：人在大厅却被判定为「已在竞技场中」）
- **修复 `/hilltop setup saveexit` 只传送、不走「离开竞技场」流程**（上一轮引入的缺陷）：
  - 现象：用「保存并返回大厅」回到大厅后，执行 `/hilltop join` 被 `already-in-arena` 拦住，玩家被卡在「已加入」状态
  - 根因：`saveexit` 只调用了 `sendToLobby()`（纯传送），没有调用 `leaveArena()`，导致 `playerArenaMap` 中仍留着该玩家
  - 修复：`saveexit` 改为「在竞技场中 → `leaveArena()`（归还物品 + 移出名单 + 送回大厅）；否则 → `sendToLobby()`」
- **`/hilltop join` 新增陈旧状态自愈**：
  - 若玩家被标记为「已在竞技场中」，但其当前所在世界**不是**该竞技场的世界（例如被外部插件传送、或历史遗留的陈旧标记），则先自动补一次 `leaveArena()`（归还物品、移出名单）再正常加入
  - 只有当玩家确实还在竞技场世界内时才提示 `already-in-arena`

### Fixed（2026-09-12 — 实测反馈第七轮：/hilltop arenas NPE / 部署中返回大厅）
- **修复 `/hilltop arenas`（及 `/hilltop list`）NullPointerException**：
  - 根因：**初始化顺序造成的循环依赖** —— `HilltopCommandHandler` 构造时读取 `plugin.getArenaCommand()`，但主类里 `arenaCommand` 是在 `commandHandler` **之后**才创建的，导致注入的是 null，调用时 NPE
  - 修复：调整主类初始化顺序，`arenaCommand` 先于 `commandHandler` 创建，并加注释说明顺序不可调换
- **新增 `/hilltop lobby` 命令**：返回大厅
  - 玩家在竞技场中 → 走正常离开流程（归还物品、结算统计）后送回大厅
  - 不在竞技场中 → 直接传送回大厅（部署期间从竞技场世界返回大厅尤其有用）
- **部署向导新增「保存并返回大厅」通道**：
  - 新增向导子命令 `saveexit`：保存配置与当前步骤 → 关闭 GUI → 传送回大厅 → 提示如何继续
  - 步骤 3（生成点）/ 4（节点）/ 5（村民）/ 6（商店）的聊天按钮行均加入 `[保存并返回大厅]` 按钮
  - 向导进度本就在每步自动持久化到 `config.yml` 的 `wizard.step.<uuid>`，回到竞技场世界执行 `/hilltop setup` 即可**从上次步骤继续**
  - 新增 i18n 键 `setup.saved-exit`、`setup.btn-save-exit`、`usage-lobby`、`lobby-teleported`（en + zh）

### Fixed（2026-09-12 — 实测反馈第六轮：商店步骤失效 / 村民与商店强制化 / 村民管理 GUI / stats 补全）
- **修复第 6 步（商店）点击 [设置商店位置] 提示「未知的部署命令」**：
  - 根因：阶段 3 把商店步骤从第 5 步改成第 6 步（第 5 步插入村民），但 `confirm-shop` / `skip-shop` 处理器内部的步骤守卫仍是 `s.step != 5`，导致在第 6 步点击时被判定为非法命令
  - 修复：守卫改为 `s.step != 6`
- **商店改为强制项**：移除 [跳过] 按钮与 `skip-shop` 分支；未设置商店位置无法完成部署
- **村民改为强制项**：村民列表为空时 [完成] 会被拒绝并提示
- **完成部署时统一校验三个强制项**：预览 GUI 的 [确认保存] 与 `preview` 命令都会校验「生成点 / 村民 / 商店」，缺失时拒绝并**自动跳回对应步骤**，新增 i18n 键 `setup.spawns-required`、`setup.shop-required`
- **修复跨步骤操作漏洞**：第 5 步的村民按钮原先直接调用 `/hilltop deploy addvillager` 等命令，不带步骤守卫，导致走到第 6 步后仍能添加村民
  - 修复：改用导向命令 `/hilltop setup addvillager` / `villagersgui` / `villagers-done`，全部受 `s.step != 5` 守卫保护
- **新增村民管理 GUI**（对应你参考的「打开节点管理」）：
  - 54 格分页 GUI（每页 45 个），条目显示世界/坐标，**点击删除**
  - 底部操作行：[添加当前位置] / [完成] / 上一页 / 下一页
  - 空列表时显示屏障图标提示「至少需要 1 个村民」
  - `SetupSession` 新增 `villagerPage` 字段保存页码
  - 新增 i18n 键 `setup.gui-villagers-title`、`setup.villager-entry`、`setup.villager-entry-lore`、`setup.villagers-empty`、`setup.villager-added`、`setup.btn-villagers-gui`
- **`/hilltop stats` 新增玩家名 Tab 补全**（补全在线玩家名）

### Added（2026-09-12 — 竞技场启用队列 + 维护通道，完整复刻 BedWars1058）
- **新增 `ArenaStatus` 竞技场生命周期枚举**（`arena/ArenaStatus.java`）：`DISABLED` / `QUEUED` / `LOADING` / `READY`
  - 与 `GameState` 的区别：`GameState` 描述**一局游戏**的阶段，`ArenaStatus` 描述**竞技场本身是否可用**
  - 状态流转：`DISABLED ──enable──▶ QUEUED ──▶ LOADING ──▶ READY ──disable──▶ DISABLED`
- **新增启用队列**（对应 BedWars1058 的 `Arena.getEnableQueue` / `addToEnableQueue` / `removeFromEnableQueue`）：
  - `MultiArenaManager.enableArena(id)`：校验定义存在且 `enabled: true` → 入队 → 状态 `QUEUED`
  - `processEnableQueue()`：**每 tick 处理一个**，队首加载世界 → `new ArenaInstance()` 初始化 → 状态 `READY` → 自动处理下一个
    - 串行加载，避免一次性加载多个世界卡住服务器（与 BedWars1058 一次只加载队首一致）
  - 世界加载失败 / 初始化异常 → 状态 `DISABLED` 并给出明确日志（不再静默失败）
  - `enableAllConfiguredArenas()`：插件启动时把所有 `enabled: true` 的竞技场入队（替代原先的 `startAllArenas()` 同步创建）
- **新增维护通道命令**（对应 BedWars1058 的 `/bw enable` / `/bw disable`）：
  - `/hilltop disable [竞技场]`：踢出玩家 → 从可加入列表移除 → 状态 `DISABLED`；**对局进行中拒绝**（对应 BedWars 的 "There is a game running on this Arena, please disable after the game!"）
  - `/hilltop enable [竞技场]`：重新加入启用队列；已就绪/已排队时给出对应提示
  - 两者均支持 Tab 补全竞技场 ID（含已被禁用的竞技场）
- **新增 `MultiArenaManager.resolveArenaId()`**：按 ID 解析竞技场（可解析出**已被禁用、尚无运行时实例**的竞技场），`resolveArena()` 只能解析出已就绪的实例，无法用于 enable 场景
- **`/hilltop arenas` 全面重写**：改为遍历**所有已配置**的竞技场（含已禁用/排队中的），显示生命周期状态 + 可操作的排查提示
  - 例如：`(arenas.yml 中 enabled: false → 改为 true 后 /hilltop enable arena2)`、`(被 /hilltop disable 关闭 → 用 /hilltop enable arena1 恢复)`
- **`/hilltop list`、`/hilltop arenas`、GUI 的状态显示接入 `ArenaStatus`**
- **新增 i18n 键**（en + zh）：`arena-status-disabled`、`arena-status-queued`、`arena-status-loading`、`arena-enable-*`、`arena-disabled-ok`、`arena-already-disabled`、`arena-disable-in-progress`、`usage-enable`、`usage-disable`
- **README 新增「竞技场维护（启用 / 禁用）」章节**：说明启用队列、串行加载与六种生命周期状态

### Fixed（2026-09-12 — 实测反馈第五轮：颜色码泄漏 / 未就绪自动开局）
- **修复竞技场显示名颜色码泄漏**（`&6&l天空堡垒` 原样显示）：
  - 根因：`ArenaInstance.getDisplayName()` 直接返回 arenas.yml 的原始值，调用方（GUI / 命令）未做 `&` → `§` 翻译
  - 修复：在 `ArenaInstance.getDisplayName()` 统一翻译颜色码，所有调用点（GUI 图标名、`/hilltop list`、`/hilltop arenas`、提示消息）一次性修复
  - **注意**：`ArenaDefinition.getDisplayName()` 保持**原始未翻译值**，确保写回 arenas.yml 时存入的仍是 `&` 代码而非 `§`
- **修复竞技场未部署时加入就自动开局**（参考 BedWars1058 的就绪门控）：
  - BedWars1058 做法：竞技场必须经 `/bw setup` 配置完成并**显式 `/bw enable`** 进入启用队列，世界加载 + `init()` 成功后才 `changeStatus(waiting)`；而自动开局只在 `status == waiting` 时触发 —— 即**未就绪的竞技场永远不会开局**
  - 我们的等价门控是「已部署」（`isPlayable()` = 世界已加载 + 已配置生成点）：
    - `addPlayer()` 的自动倒计时改为额外要求 `isPlayable()`
    - `startCountdown()` 增加防御性守卫：未就绪则回退到 WAITING 不进入倒计时
    - 倒计时途中若竞技场变为不可用（如管理员清空生成点）也会取消倒计时
    - 管理员加入未部署竞技场时给出明确提示：`[管理员] 尚未部署 —— 游戏不会开始。请在本世界运行 /hilltop setup。`
  - 新增 i18n 键 `arena-not-deployed-hint`（en + zh）

### Fixed（2026-09-12 — 实测反馈第四轮：命令路由 / Tab 补全 / 竞技场状态区分）
- **修复 `/hilltop arenas` 与 `/hilltop list` 完全不可用**：
  - 根因：`ArenaCommand` 从未被注册为命令执行器（`getCommand("hilltop").setExecutor(...)` 只设置了 Tab 补全），而 `HilltopCommandHandler` 的 switch 里**没有 `list` / `arenas` 分支**，两个命令都落到 `default` 只回一句帮助。`ArenaCommand` 实际是死代码
  - 修复：`HilltopCommandHandler` 新增 `list` / `arenas` 分支并委托给 `ArenaCommand`；主类新增 `getArenaCommand()`
  - 同时修复 `ArenaCommand.handleArenas()` 的权限字符串错误（`hilltop.admin` → `hilltopvillage.admin`，此前用正确权限也不会显示）
- **新增 `/hilltop arenas` 的故障排查提示**：每行状态后追加具体原因
  - `enabled: false` / `世界未加载 → 检查世界文件夹是否存在` / `尚未配置生成点 → 运行 /hilltop setup`
  - 世界列在未加载时追加红色 `(未加载)`
- **新增竞技场 ID 的 Tab 补全**：`/hilltop start|stop|status|join` 第 2 个参数补全已加载的竞技场 ID
  - `start` / `stop` 仅对有 `hilltopvillage.admin` 权限者补全
  - 第 1 参数补全列表补上 `list` 与 `arenas`
  - `HilltopTabCompleter` 改为接收插件实例以读取竞技场列表
- **区分「未部署」与「地图缺失」两种不可用状态**：
  - `IArena` 新增 `isWorldAvailable()`（世界是否已加载），`ArenaInstance` 实现并新增 `getWorld()` / `isPlayable()`
  - **地图缺失**（配置里写了但存档不存在）→ 灰色屏障图标 + 状态「地图缺失」，**任何人都不能进**
  - **未部署**（世界在但没配生成点）→ 灰色木剑 + 状态「未部署」，**管理员可进**（便于进图核对位置），普通玩家不可进
  - 不可用竞技场的状态行统一切换为灰色，不再出现「未部署」却显示绿色「等待中」的误导
  - 管理员在未部署竞技场的图标上会看到 `[管理员] 进入该竞技场世界后运行 /hilltop setup` 提示
  - `ArenaInstance.addPlayer()` 同步放宽：管理员可加入未部署竞技场；地图缺失仍一律拒绝
  - 新增 i18n 键 `arena-map-missing`、`arena-status-map-missing`、`gui-arena-admin-hint`（en + zh）
- **修复模板 arenas.yml 携带假生成点导致新装即显示「已部署」**：
  - 模板原带示例坐标（如 `95.5, 64.0, 95.5`），使 `hasSpawnPoints()` 返回 true，竞技场显示为「等待中」可加入，但坐标指向的是不存在的地形
  - 模板改为 `spawn-points: []` / `villagers: []`（由部署向导写入），并补充「地图缺失 / 未部署 / 等待中」三种状态说明与完整部署流程注释
  - ⚠️ 已有服务器需手动清空 arenas.yml 中的示例生成点，或删除该文件让插件重新生成模板

### Fixed（2026-09-12 — 实测反馈第三轮：加入竞技场 NPE / 倒计时 / 未部署可加入 / 大厅可部署）
- **修复加入竞技场时 NPE（阻塞性）**：
  - 根因：`PlayerGoodsManager.saveToDisk()` 用 `List.of(snapshot.contents)` 序列化背包，但**背包数组含 null（空格子）而 `List.of` 不允许 null 元素**，直接抛 NPE
  - 影响链：NPE 发生在 `addPlayer()` 注册玩家**之前**，导致玩家没被登记 → `/hilltop start` 报「当前 0/2 名玩家」、倒计时异常
  - 修复：改为**按槽位索引**存储物品（`contents.<slot>` 节点），天然规避 null；读取端按索引还原，未知键安全跳过
  - 新增常量 `INVENTORY_SIZE`(36) / `ARMOR_SIZE`(4)
- **修复倒计时播报显示原始键名 `countdown-message`**：该 i18n 键此前**未定义**，直接输出了键名
  - 补齐 `countdown-message`、`countdown-cancelled`、`game-started`、`game-in-progress`、`arena-not-deployed`、`game-ended`、`game-ended-no-players`（en + zh）
  - 同时降低刷屏：播报改为「第 10 秒 + 最后 5 秒内」，不再每秒发一条
- **修复未部署的竞技场显示为「等待中 / 点击加入」**（Issue 4）：
  - `IArena` 新增 `isDeployed()`，`ArenaInstance` 实现为 `gameManager.hasSpawnPoints()`
  - `ArenaSelectionGUI.canJoin()` 与 `getStatusText()` 增加未部署判定：未部署显示「未部署」+ 灰色羊毛 + 原因提示
  - `ArenaInstance.isAvailable()` 增加未部署过滤，`/hilltop join`（自动匹配）不会再选中未部署竞技场
  - `/hilltop list` 与 `/hilltop arenas` 的状态列显示「未部署 / 已禁用」
- **修复可在大厅执行部署命令导致坐标写错**（Issue 5，数据破坏性）：
  - `/hilltop deploy` 现在要求管理员**位于目标竞技场世界内**，否则拒绝并提示应使用的世界名
  - `/hilltop setup` 同样增加世界校验，防止在大厅运行向导把大厅坐标登记为竞技场核心
  - 新增 i18n 键 `deploy.must-be-in-arena-world`、`setup.must-be-in-arena-world`（en + zh）

### Fixed（2026-09-12 — 实测反馈第二轮：插件启用失败 NPE / 大厅世界缺失）
- **修复插件启用时 NullPointerException 导致整个插件加载失败**：
  - 根因：阶段 3 重构时把 `spawnPointsConfig`（spawnpoints.yml）的初始化移到了 `saveShopNpcLocation()`，但 `loadSpawnPoints()` 末尾仍调用 `loadShopNpcLocation()` 读取它，导致 `spawnPointsConfig` 为 null 时抛 NPE，插件直接 enable 失败并自动 disable
  - 修复：抽出 `ensureSpawnPointsConfigLoaded()`，读取与写入路径共用，保证幂等初始化
  - 影响：这是**阻塞性 Bug**，会让插件完全无法启动（不只是竞技场加载失败）
- **新增大厅世界兜底**：`lobby.world` 留空、或指向的世界当前未加载时，自动回退到服务器主世界（`server.properties` 的 `level-name` 世界），避免因大厅配置缺失导致保护规则/落地传送失效
  - 新增 `ArenaManager.getConfiguredLobbyWorldName()` 供世界加载阶段使用（不回溯，确保配置里写的世界仍会被尝试加载）
- **清理启动日志**：世界加载失败提示改为「跳过未加载的世界（服务器根目录下不存在该世界文件夹）: xxx」，不再在控制台输出 `level-name` 相关提示
- **README 澄清 `level-name` 语义**（zh + en）：`level-name` 指定服务器根目录下哪个文件夹被加载为主世界（默认 `world`），名字不匹配时服务器会新建世界而非加载目标

### Fixed（2026-09-12 — 实测反馈：竞技场无法加载 / GUI 标题 / 命令帮助不全）
- **修复竞技场定义被误判为"损坏"导致加载 0 个竞技场**：
  - 根因：`ArenaManager.readDefinition()` 用 `section.getConfigurationSection("spawn-points." + i)` 读取列表项，但 **Bukkit 不支持用点号路径索引 YAML 列表**，该方法对列表项一律返回 null，进而抛出 `Missing coordinates at arenaX.spawn-points.0`，整个竞技场定义被丢弃
  - 修复：改为直接从 `getList()` 返回的 `Map` 读取坐标（新增 `readLocationFromMap()` / `mapDouble()` / `mapDoubleOrDefault()` 辅助方法），并跳过无效条目时给出明确警告而非丢弃整个竞技场
- **新增世界自动加载**：Paper 启动时只加载 `server.properties` 的 `level-name` 世界，导致竞技场世界（文件夹存在）未加载、坐标读取全部失败
  - 新增 `ArenaManager.ensureWorldsLoaded()`：解析 arenas.yml 中 `lobby.world` 与所有 `arenas.*.world`，对未加载但磁盘上存在 `level.dat` 的世界执行 `Bukkit.createWorld(new WorldCreator(name))`
  - 世界确实不存在时给出明确提示（`世界未加载且磁盘上不存在，跳过: xxx`），而非静默失败
  - 与 BedWars1058 的 `RestoreAdapter.onEnable` 行为一致
- **修复 `/hilltop gui` 的箱子标题显示为"帮助"**：
  - 根因：`ArenaSelectionGUI.openGUI()` 误用 `gui-help-title`（帮助图标标题）作为 GUI 标题
  - 修复：新增专用键 `gui-arena-select-title`（竞技场选择 / Arena Selection）
  - 同时把点击识别从「标题文本包含 Help/帮助」改为 **InventoryHolder 模式**（新增 `ArenaSelectorHolder`），标题可随语言与配色自由变化而不会导致 GUI 点不动
  - 新增拖拽拦截（`InventoryDragEvent`），与商店 GUI 的防刷一致
  - 无可用竞技场时显示屏障图标 + 明确提示（`gui-no-arenas`），不再是一个空箱子
- **修复命令帮助不全**：`/hilltop` 与 `/hilltop help` 的帮助列表补齐全部命令，并按「玩家命令 / 管理员命令」分组
  - 补齐：`gui`、`list`、`stats`、`top`、`fireball`、`arenas`、`shop reload`、`build`、`tps`
  - 修正 `start`/`stop`/`join`/`status` 的可选竞技场参数说明
  - 修正 setup 描述为「8 步」（此前仍写 6 步）
- **README 补充 `server.properties` 的 `level-name` 说明**（zh + en）：解释 Paper 只加载一个世界、插件会自动加载其余世界、推荐把 `level-name` 设为大厅世界

### Fixed（2026-09-12 — 胜利结算流程断链：玩家被留在竞技场）
- **修复胜利时玩家不会被移出竞技场的问题**：此前 `onNaturalGameEnd()` 只设置 ENDING 并调度重置，**没有把玩家移出**，导致胜利后玩家被留在竞技场世界中、游戏物品不归还、统计不结算
- **统一结算流程**：新增 `beginEndSequence(messageKey, victory)`，胜利与失败共用同一条链路
  - 记录本局结果 → 播报本局战报 → 倒计时（默认 20 秒）→ `finishSession()` 逐个 `removePlayer`（归还物品、结算统计、传送回大厅）→ 转入 ENDING → 竞技场重置
  - 自然结束（胜利/失败）时 `messageKey` 传 null，避免与 `GameManager` 已广播的 game-victory/game-defeat 重复
  - 新增 `isEnding()` 守卫，防止结算流程被重复触发
- **新增 `player-stats.yml` 与 `player-goods.yml` 说明**：README 补充生成文件一览

### Added（2026-09-12 — 阶段 3：记分板 / 统计持久化 / 地图恢复 / 竞技场清理）
- **记分板系统（缺口 9）**：新增 `mechanics/ArenaScoreboard`
  - 每个玩家独立计分板（需显示个人击杀），由一条共享的每秒刷新任务统一驱动，避免每玩家各开任务
  - 显示：波次进度、存活玩家、等待复活、能量方块、存活村民、个人击杀/阵亡
  - 行文本放入 Team prefix（无长度限制且支持颜色），行 entry 使用唯一短码避免冲突
  - 加入竞技场时显示、离开时还原主计分板、竞技场重置时统一清理
  - 可在配置中关闭（`scoreboard.enabled`）
- **玩家统计持久化（缺口 10）**：新增 `stats/StatsManager`（对应 BedWars1058 的 `PlayerStats` + 数据库层）
  - 按用户决策使用 **YAML 文件存储**（`player-stats.yml`），后续可平滑替换为数据库实现
  - 统计项：参与对局、胜/负、胜率、击杀、阵亡、K/D、修复节点数、累计波次、最高波次、首次/最近游戏时间
  - 结算时机参照 BedWars1058 的「内存累加 → 离开竞技场时落盘」：中途离开只累计击杀/阵亡/修复，**不计胜负**
  - 新增 `ArenaInstance.sessionVictory` 标记本局结果，`removePlayer` 据此结算
  - 新增命令 `/hilltop stats [玩家名]`（查看自己或他人统计）与 `/hilltop top <类型>`（排行榜）
  - 排行榜类型：`kills` / `wins` / `games` / `best-wave` / `nodes` / `deaths`，含 Tab 补全
  - 插件关闭时强制落盘；只有存在改动时才写文件
- **地图恢复（缺口 11）**：新增 `mechanics/MapRestoreManager`
  - **方案说明**：BedWars1058 使用「整世界 zip 快照 + 卸载重建世界」；本实现改用**逐方块日志回滚**，原因是无需服主预置地图快照、不涉及世界卸载重载（避免卸载失败/卡顿影响对局），且足以覆盖塔防场景的常见破坏来源
  - 记录来源：方块破坏、方块放置、实体爆炸、方块爆炸、方块自然变化（冰/雪融化）
  - 只记录**竞技场世界**且**对局进行中**的改动，避免把管理员部署时的建造也回滚掉
  - 单个方块只记录**首次**改变前的类型，保证回滚到开局状态
  - 回滚分批执行（每 tick 最多 800 方块），避免大批量改动造成卡顿；采用 `setType(..., false)` 不触发连锁物理更新
  - 可在配置中关闭（`map-restore.enabled`）
- **竞技场清理补全（缺口 12）**：
  - 新增残留掉落物清理（每局重置时清除竞技场世界内的 `Item` 实体）
  - 确认节点血量重置已由现有设计覆盖：`startGame()` 调用 `loadNodesFromConfig()` 会以 `baseHealth` 重建全部节点
- **新增配置项**（`GameConfig`）：`mapRestoreEnabled`（默认 true）、`scoreboardEnabled`（默认 true）
- **新增 i18n 消息键**（en + zh）：`scoreboard.*`（title/wave/alive/spectators/nodes/villagers/kills/deaths）、`stats.*`（header/games/win-loss/kills-deaths/nodes/waves/self-hint/player-not-found/usage/disabled）、`top.*`（header/entry/empty/usage/types）

### Added（2026-09-11 — 阶段 2：大厅落地 / 大厅保护 / 旁观者 / 掉线重连）
- **大厅落地（缺口 5）**：`onPlayerJoin` 从空实现改为完整的大厅流程
  - 新增 `MultiArenaManager.applyLobbyState()`：传送到大厅出生点并重置模式/血量/饱食/饱和/火焰/摔落距离/飞行/药水效果（已在大厅时不重复传送，避免拉扯视角）
  - 新增 `PlayerRespawnEvent` 处理：在大厅世界死亡（掉虚空等）时复活在大厅出生点
  - 进服流程顺序：先归还遗留物品快照（崩溃恢复）→ 再尝试重连 → 最后大厅落地
- **大厅保护（缺口 6）**：新增 `LobbyProtectionListener`（对应 BedWars1058 的 MULTIARENA 大厅保护）
  - 阻止：方块破坏/放置、水桶倾倒/装填、实体爆炸/方块爆炸、玩家受伤、饥饿消耗、天气变化（禁止下雨）、物品丢弃、容器交互（工作台/箱子/熔炉等）、盔甲架与展示框操作与伤害
  - 无害方块豁免：按钮/拉杆等大厅装饰可正常交互
  - 虚空保护：大厅内掉落至 Y < 0 时传送回大厅出生点
  - **建造模式豁免**：新增 `/hilltop build` 命令（管理员），切换后可在大厅自由建造（用于整修大厅）
  - 只作用于大厅世界，竞技场世界不受影响
- **旁观者系统（缺口 7）**：完善死亡等待期的旁观状态
  - `ArenaInstance` 新增 `setSpectator(UUID, boolean)` / `getSpectatorCount()` / `isSpectator(UUID)`
  - 玩家阵亡进入旁观时登记；波次刷新复活时自动退出旁观
  - `/hilltop status` 新增「等待复活」人数显示
- **掉线重连（缺口 8）**：新增重连窗口机制（对应 BedWars1058 的 `ReJoin` + `ReJoinTask`）
  - 对局进行中掉线 → 保留重连窗口（**不归还物品、不移出竞技场**），聊天栏广播剩余秒数
  - 玩家在窗口期内重连 → 恢复到核心：存活状态以生存模式继续，阵亡等待状态以旁观模式继续
  - 窗口超时 → 在线玩家走完整离开流程；**离线玩家只清理竞技场状态，物品快照保留在磁盘**，下次登录自动归还
  - 每秒扫描一次的过期任务（插件关闭时自动停止）
  - 窗口时长可在 `game-settings.yml` 的 `game.rejoin-timeout-seconds` 配置（默认 60，0 = 关闭重连）
- **新增 `/hilltop build` 命令**（管理员）：切换大厅建造模式
- **新增配置项**（`GameConfig`）：`rejoinTimeoutSeconds`（重连窗口，默认 60）
- **新增 i18n 消息键**（en + zh）：`player-reconnecting`、`player-rejoined`、`rejoin-welcome`、`lobby-protected`、`build-mode-on`、`build-mode-off`、`status-spectators`

### Added（2026-09-11 — 玩法闭环：物品保存 / 死亡重生 / 节点攻击 / 村民系统）
- **新增 `PlayerGoodsManager`（玩家物品保存与归还）**：对应 BedWars1058 的 `PlayerGoods`，但改为**落盘 YAML**（`player-goods.yml`），服务器崩溃后重启仍可归还
  - 保存内容：背包物品+槽位、盔甲、副手、等级、经验、血量、饱食度、游戏模式、飞行权限、药水效果
  - 进入竞技场时保存并清空；离开竞技场时归还（含模式/血量/飞行恢复）
  - 玩家离线期间存在未归还快照时，下次登录自动归还（崩溃恢复）
  - **修复数据丢失 Bug**：此前 `addPlayer`/`removePlayer` 直接 `inventory.clear()` 且无保存归还，玩家携带的生存物品会永久消失
- **新增 `PlayerLifecycleListener`（死亡与重生）**：实现「节点 = 血条」设计
  - 玩家死亡 → 掉落物清空、无经验掉落、记录阵亡数、聊天栏广播
  - 死亡后进入旁观，等待**波次刷新**
  - 波次刷新时：有**活跃节点** → 直接在**核心**重生（无倒计时、无无敌时间）；无活跃节点 → 继续旁观并提示
  - **节点全毁 + 全员阵亡** → 触发失败结算
- **新增 `NodeAttackGoal`（普通怪物攻击节点）**：此前只有自爆甲虫会攻击节点，普通波次怪物对节点零威胁
  - 怪物在无玩家可攻击时推进至最近的活跃节点并攻击
  - **伤害值 = 怪物的 `ATTACK_DAMAGE` 属性**（即「打到玩家的伤害」），与节点伤害规则一致
  - 以优先级 3 注册（低于原版近战 AI 的优先级 2），有玩家时仍优先攻击玩家
- **新增 `VillagerSystem`（待保护村民）**：实现「村民全灭 = 游戏失败」
  - 管理员在**部署向导 Step 5** 手动放置村民，数量取决于放置个数
  - 村民血量在 `/hilltop config` 配置（`game.villager-health`，默认 20）
  - 村民可被怪物攻击（原版僵尸天然以村民为目标），玩家无法伤害村民
  - 村民全部阵亡 → 触发失败结算
  - 竞技场重置/游戏结束时自动清理村民实体，并提供孤儿实体清理
- **新增失败结算流程 `ArenaInstance.triggerDefeat()`**：保持玩家在游戏内 → 聊天栏弹出本局战报（击杀/阵亡/修复节点数）→ 倒计时（默认 20 秒，可配）→ 传送回大厅 → 竞技场重置
- **新增村民部署命令**：`/hilltop deploy addvillager` / `listvillagers` / `delvillager <id>` / `clearvillagers`
- **部署向导扩展为 8 步**：1 世界 → 2 核心 → 3 生成点 → 4 节点 → **5 村民（新）** → 6 商店 → 7 预览 → 8 完成
  - 预览 GUI 新增村民数量显示
  - 新增向导子命令 `villagers-done`
- **新增大厅支持**：`MultiArenaManager.getLobbySpawn()` / `getLobbyWorld()` / `sendToLobby()`，从 `arenas.yml` 的 `lobby.world` + `lobby.spawn` 读取
- **新增 i18n 消息键**（en + zh）：`goods-restored`、`player-died`、`respawn-waiting`、`respawn-blocked-no-node`、`respawned`、`defeat-all-down`、`defeat-villagers-dead`、`defeat-stats-header`、`defeat-stats-line`、`defeat-countdown`、`villager.display-name`、`villager-died`、`deploy.addvillager-*`、`deploy.listvillagers-*`、`deploy.delvillager-*`、`deploy.clearvillagers-done`、`deploy.menu-villagers`、`setup.step5-villagers`、`setup.btn-add-villager`、`setup.btn-list-villagers`、`setup.btn-villagers-done`、`setup.preview-villagers`
- **新增配置项**（`GameConfig`）：`defeatCountdownSeconds`（失败结算倒计时，默认 20）、`villagerHealth`（村民血量，默认 20）

### Fixed（2026-09-11 — 默认波次怪物未登记导致波次秒过）
- **修复默认波次僵尸未加入 `activeMobs`**：`spawnDefaultWave()` 生成僵尸后未登记，导致 `isWaveCleared()` 立即返回 true（波次瞬间完成）且 `clearAllMobs()` 无法清理残留僵尸。现统一走 `prepareWaveMob()` 登记
- **修复 `base-damage` 配置从未生效**：`MonsterConfig.baseDamage` 此前只存在于配置与 GUI 中，从未应用到怪物的 `ATTACK_DAMAGE` 属性。现于 `applyMonsterEquipment()` 中统一应用
- **修复离开竞技场未回大厅**：`removePlayer()` 此前传送到服务器默认出生点，现改为传送到 `arenas.yml` 配置的大厅出生点（未配置时回退默认出生点）

### Removed（2026-09-10 — 彻底移除 Legacy 单竞技场模式）
- **删除 `GameManager` 的 Legacy 构造函数**：`GameManager(HilltopVillagePlugin)` 及配套的 `loadWorldReference()`、`isMultiArenaMode()` 全部移除，GameManager 仅保留多竞技场构造函数，`arenaId` 恒为非空
- **删除 Legacy 回退逻辑**：`getSpawnPoints()` / `hasSpawnPoints()` 不再从 `AdminDeployManager` 回退读取，`arenaSpawnPoints` 直接来自竞技场定义；新增 `setSpawnPoints()` / `setCoreLocation()` 供部署工具刷新内存
- **删除 `ArenaManager.migrateLegacyConfiguration()`**：不再从 spawnpoints.yml + game-settings.yml 迁移旧单竞技场配置，arenas.yml 成为唯一权威配置
- **删除 `HilltopVillagePlugin` 的 `gameManager` 字段**：主类不再持有全局 GameManager，`getGameManager()` 方法移除
- **删除 arenas.yml 的 `settings.mode: MULTI/LEGACY` 选项**：多竞技场成为唯一模式
- **删除玩家进服自动加入逻辑**：`onPlayerJoin` 不再把玩家拉入游戏队列，玩家在大厅通过 `/hilltop join` 或 `/hilltop gui` 主动加入

### Changed（2026-09-10 — 多竞技场全量路由改造）
- **监听器改造为竞技场路由**：
  - `HammerListener` / `FireballListener` / `NodeListener` 构造函数改为接收 `HilltopVillagePlugin`，事件触发时通过 `MultiArenaManager.getPlayerArena()` / `getArenaByWorld()` 解析对应竞技场的 GameManager，不在竞技场内的玩家/方块事件直接忽略
  - 物品配置（锤子/烈焰蛋/修复物品名称与参数）从全局 `plugin.getConfigManager()` 读取
- **命令处理器全面路由到竞技场**：
  - `/hilltop start [竞技场]`、`/hilltop stop [竞技场]`、`/hilltop status [竞技场]` 支持可选竞技场 ID，缺省时解析玩家所在 → 默认 → 第一个活动竞技场
  - `/hilltop join [竞技场]`、`/hilltop leave` 委托给 MultiArenaManager
  - `/hilltop deploy`、`/hilltop reloadconfig` 针对解析出的目标竞技场执行
- **新增 `MultiArenaManager.resolveArena()`**：命令统一解析入口（显式 ID → 玩家所在 → 默认 → 第一个活动）
- **修复 ArenaInstance 玩家同步缺失 Bug**：`addPlayer()` / `removePlayer()` 现在同步调用 `gameManager.addPlayer/removePlayer()`，修复多竞技场模式下 GameManager 玩家数为 0 导致游戏永远无法启动的问题
- **修复 ArenaInstance 商店配置未加载 Bug**：构造函数中新增 `shopManager.reload()`
- **AdminDeployManager 写入 arenas.yml**：生成点/核心位置按竞技场 ID 持久化到 arenas.yml 并即时刷新 GameManager 内存；商店村民位置仍存 spawnpoints.yml（全局单 NPC）
- **TpsDiagnostic 竞技场感知**：`/hilltop tps` 按玩家所在/默认竞技场显示游戏数据，无竞技场时仅显示服务器数据
- **NodeSystem 多竞技场隔离**：加载节点时按竞技场世界过滤；保存时保留其他世界的节点条目，只更新本竞技场世界的节点
- **NpcManager / SetupWizard 适配**：商店 NPC 恢复与部署向导通过默认/第一个活动竞技场的 GameManager 解析
- **修复自然结束状态机断链**：GameManager 新增 `setOnGameEndCallback()`，胜利/失败时通知 ArenaInstance 转入 ENDING 并调度重置（幂等的 `scheduleReset()`），修复竞技场在游戏自然结束后永久卡在 RUNNING 状态无法再开局的问题

### Fixed (2026-09-10 — 修复多竞技场大厅与游戏世界混用问题)
- **修复大厅与游戏世界混用问题（参考 BedWars1058 架构）**：
  - 问题根源：`arenas.yml` 中 arena1 的 `world: world` 与 lobby 的 `world: world` 相同，导致游戏可以在大厅部署
  - 解决方案：实现 BedWars1058 的三层传送架构（大厅世界 → 竞技场等待区 → 游戏区域）
- **新增 `waiting-location` 配置**：
  - `ArenaDefinition` 新增 `waitingLocation` 字段（竞技场内的等待区位置）
  - `ArenaManager.readDefinition()` 从 `arenas.yml` 读取 `waiting-location` 配置（如未配置则默认使用 core 位置）
  - `ArenaManager.writeDefinition()` 保存 `waiting-location` 到配置文件
- **修改传送逻辑（完全参照 BedWars1058）**：
  - `ArenaInstance.getLobbySpawn()` 返回 `definition.getWaitingLocation()`（竞技场内的等待区）
  - `ArenaInstance.addPlayer()` 传送玩家到等待区（在竞技场世界内）
  - `ArenaInstance.onGameStart()` 游戏开始时传送玩家到游戏生成点
- **新增世界管理工具方法**（`MultiArenaManager`）：
  - `isLobbyWorld(String worldName)` - 判断是否是大厅世界（用于监听器保护）
  - `getArenaByWorld(String worldName)` - 根据世界名查找竞技场实例
  - `isArenaWorld(String worldName)` - 判断是否是竞技场世界
- **更新配置文件示例**：
  - `arenas.yml` 中 `arena1.world` 改为 `hilltop_arena1`（独立世界）
  - `arena2.world` 改为 `hilltop_arena2`（独立世界）
  - 新增 `arena1.waiting-location` 和 `arena2.waiting-location` 配置示例
- **升级到 Paper 1.21.4 API**：
  - `pom.xml` Paper API 版本从 1.19.4 升级到 1.21.4-R0.1-SNAPSHOT
  - `plugin.yml` api-version 从 1.19 升级到 '1.21'

### Fixed (2026-09-09 — Bug 修复：国际化、生成点检查、GUI)
- **修复未配置生成点可以加入游戏的问题**：`ArenaInstance.addPlayer()` 新增 `hasSpawnPoints()` 检查，未配置生成点的竞技场拒绝玩家加入
- **移除所有源代码中的硬编码"喵"**：
  - `ArenaInstance`：所有玩家消息使用 LanguageManager（player-joined、arena-no-spawn-points、game-start、wave-start、wave-complete、game-victory、game-defeat）
  - `MultiArenaManager`：所有消息使用 LanguageManager（arena-not-found、no-arenas-available）
  - `ArenaCommand`：所有消息使用 LanguageManager（player-only-command、already-in-arena、not-in-arena、no-arenas-available、join-failed、arena-left、arena-list-header、arena-list-footer、no-active-arenas、no-permission）
  - `ArenaSelectionGUI`：所有 GUI 文本使用 LanguageManager（gui-help-title、gui-arena-lore-status、gui-arena-lore-players、gui-arena-lore-map、gui-arena-lore-click-join、gui-arena-lore-cannot-join、gui-help-lore-green/yellow/red/gray）
- **修复 GUI 国际化问题**：`ArenaSelectionGUI` 所有界面文本使用 LanguageManager，支持中英文切换
- **修复重复加入消息问题**：移除 `ArenaCommand.handleJoin()` 中的硬编码成功消息（由 `ArenaInstance.addPlayer()` 统一发送）
- **新增 messages.yml 国际化键**：
  - `arena-no-spawn-points` - 竞技场未配置生成点提示
  - `arena-left` - 离开竞技场成功消息
  - `player-only-command` - 只有玩家可执行命令提示
  - `join-failed` - 加入失败提示
  - `no-active-arenas` - 无活动竞技场提示
  - `arena-list-footer` - 竞技场列表底部提示
  - `arena-status-header` - 竞技场状态标题
  - GUI 相关键：`gui-arena-lore-status/players/map/click-join/cannot-join`、`gui-help-title/lore-green/yellow/red/gray`

### Added（2026-09-08 — 多竞技场系统改造 Phase 1：核心架构 + 大厅房间分离）
- **新增多竞技场核心架构（完全参照 BedWars1058 设计）**：
  - `IArena` 接口：定义竞技场生命周期（getArenaId、getDisplayName、getPlayers、getState、addPlayer、removePlayer、start、stop、cleanup）
  - `ArenaInstance` 实现类：每个竞技场独立运行，拥有独立的 GameManager、WaveManager、NodeSystem、ShopManager、玩家列表和状态机
  - `MultiArenaManager` 管理器：负责加载竞技场定义、启动/停止竞技场实例、玩家路由（UUID → ArenaInstance）、自动匹配玩家到可用竞技场
  - `ArenaCommand` 命令处理器：处理玩家加入/离开/列表/管理员操作等命令
- **新增 `arenas.yml` 配置文件**：支持定义多个竞技场（display-name、enabled、world、core、spawn-points、game 参数），大厅设置（lobby.world、lobby.spawn），模式切换（settings.mode: MULTI/LEGACY）
- **新增竞技场状态枚举 `GameState.ENDING`**：用于多竞技场模式下游戏结束后的清理阶段（传送玩家到大厅、清理实体、重置方块等）
- **新增竞技场命令系统**：
  - `/hilltop join [arena]` - 加入指定竞技场（不指定则自动匹配）
  - `/hilltop leave` - 离开当前竞技场
  - `/hilltop list` - 列出所有可用竞技场
  - `/hilltop arenas` - 查看详细竞技场状态（管理员）
  - `/hilltop start <arena>` - 启动竞技场实例（管理员）
  - `/hilltop stop <arena>` - 停止竞技场实例（管理员）
- **多语言支持**：`messages.yml` 新增多竞技场相关消息键（player-joined、player-left、arena-full、arena-disabled、arena-started、arena-not-found、already-in-arena、not-in-arena、no-arenas-available、arena-list-header、arena-list-entry、arena-status-waiting/starting/running/ending）

### Changed（2026-09-08 — 多竞技场系统改造 Phase 2：GameManager 适配竞技场绑定）
- **`GameManager` 构造函数重载**：
  - 保留原有 `GameManager(HilltopVillagePlugin)` 构造函数（Legacy 模式，从 `plugin.getConfig()` 读取配置）
  - 新增 `GameManager(plugin, arenaId, world, coreLocation, spawnPoints)` 构造函数（多竞技场模式，从 ArenaInstance 传入参数）
  - 新增字段 `arenaId`（竞技场 ID，null = Legacy 模式）
  - 新增字段 `arenaSpawnPoints`（多竞技场模式的生成点列表，null = Legacy 模式从 AdminDeployManager 读取）
  - **修复 Legacy 模式初始化顺序**：Legacy 构造函数中先调用 `loadWorldReference()` 设置 `gameWorld`，再初始化 `AdminDeployManager`，避免 NullPointerException

### Added（2026-09-09 — Phase 4：GUI 竞技场选择菜单）
- **新增 ArenaSelectionGUI 类**：
  - 54 格箱子界面，最多显示 45 个竞技场（5 行）
  - 竞技场图标根据状态显示不同材质：
    - WAITING：钻石剑 + 绿色羊毛边框（可加入）
    - STARTING：金剑 + 黄色羊毛边框（可加入）
    - RUNNING：铁剑 + 红色羊毛边框（无法加入）
    - ENDING：石剑 + 灰色羊毛边框（无法加入）
    - DISABLED：木剑 + 灰色羊毛边框（无法加入）
  - Lore 信息显示：状态、玩家数、地图名称
  - 可加入的竞技场显示 "§e§lClick to join!" 提示
  - 无法加入的竞技场显示原因（游戏进行中、竞技场已满、竞技场禁用）
  - 右下角（第 53 格）显示帮助信息和颜色说明
  - 点击竞技场图标自动加入对应竞技场
  - 已在其他竞技场的玩家无法重复加入
- **新增 `/hilltop gui` 命令**：
  - 打开竞技场选择 GUI
  - 所有玩家都可使用（无需权限）
  - Tab 补全支持
- **主插件类集成**：
  - 在 `HilltopVillagePlugin.onEnable()` 中初始化 `ArenaSelectionGUI`
  - 添加 `getArenaSelectionGUI()` getter 方法
  - GUI 自动注册为事件监听器

### Fixed（2026-09-09 — 优先级 2：命令逻辑修复）
- **修复 `/hilltop join` 和 `/hilltop leave` 命令无实际作用问题**：
  - `/hilltop join`：添加游戏状态检查（只能在 WAITING 或 STARTING 状态下加入）、重复加入检查（已在游戏中的玩家无法再次加入）
  - `/hilltop leave`：添加游戏成员检查（只有在游戏中的玩家才能离开）、提供明确的反馈消息
- **修复 `/hilltop start` 未检查游戏部署状态问题**：
  - 添加 `gameManager.hasSpawnPoints()` 检查，确保游戏已配置生成点才能启动
  - 未部署时提示 "Game not deployed! Please use /hilltop setup or /hilltop deploy first"
  - 添加游戏状态检查（只能在 WAITING 状态下启动，避免重复启动）
  - 添加临时加入逻辑回滚（人数不足时，如果玩家是因 start 命令临时加入的，则自动退出）
- **修复编译错误**：
  - 添加 `GameState` 导入
  - 修复 `Particle.VILLAGER_HAPPY` 为 `Particle.HAPPY_VILLAGER`（1.21.4 API 变更）
  - 修复 `NodeSystem.loadNodesFromWorld()` 为 `loadNodesFromConfig()`
- **修复日志刷屏问题**：
  - `GameManager.startGame()` 添加生成点检查，游戏启动前验证配置完整性
  - 避免游戏在未配置生成点时启动，导致 `WaveManager.getRandomSpawnLocation()` 不断输出错误日志
- **修复插件关闭时的错误**：
  - `ArenaInstance.stopGame()` 添加插件状态检查，只在插件启用时注册延迟任务
  - 插件关闭时立即执行 `reset()` 而不是尝试注册任务，避免 `IllegalPluginAccessException`
- **`GameManager` 新增方法**：
  - `getSpawnPoints()`：多竞技场模式返回 `arenaSpawnPoints`，Legacy 模式调用 `adminDeployManager.getSpawnPoints()`
  - `hasSpawnPoints()`：多竞技场模式检查 `arenaSpawnPoints` 是否为空，Legacy 模式调用 `adminDeployManager.hasFixedSpawnPoints()`
  - `getArenaId()`：返回竞技场 ID（null = Legacy 模式）
- **`WaveManager` 生成点逻辑适配**：
  - `getRandomSpawnLocation()` 方法改为调用 `gameManager.getSpawnPoints()` 和 `gameManager.hasSpawnPoints()`
  - 统一支持多竞技场模式和 Legacy 模式的生成点获取
- **`ArenaInstance` 构造函数完善**：
  - 从 `ArenaDefinition` 获取核心位置（`definition.getCore()`）和生成点列表（`definition.getSpawnPoints()`）
  - 将核心位置和生成点列表传递给 `GameManager` 构造函数
  - 确保每个竞技场实例拥有独立的游戏配置和生成点

### Changed（2026-09-08 — 主插件类改造：支持多竞技场模式 + 向后兼容）
- **`HilltopVillagePlugin` 架构升级**：
  - 新增 `MultiArenaManager` 字段和 getter（`getMultiArenaManager()` 和别名 `getArenaManager()`）
  - 新增 `ConfigManager` 字段和 getter（统一配置管理）
  - 保留原有 `GameManager` 字段（向后兼容 Legacy 单竞技场模式）
  - `onEnable()` 中根据 `arenas.yml` 的 `settings.mode` 决定启动模式（MULTI 启动所有竞技场实例，LEGACY 保持原逻辑）
  - `onDisable()` 中停止所有竞技场实例，清理资源
  - 新增玩家退出事件处理（PlayerQuitEvent）：玩家离线时自动从竞技场中移除
- **`ShopManager` 构造函数重载**：新增接受 `LanguageManager` 参数的构造函数，支持竞技场实例传入自定义语言管理器
- **`ShopManager` 新增 `cleanup()` 方法**：清理商店相关资源（清除所有 NPC 实体），供竞技场结束时调用
- **`LanguageManager` 新增 `send()` 方法**：`send(Player player, String key, Object... args)` 发送消息给玩家（使用玩家语言），简化竞技场代码中的消息发送逻辑

### Changed（2026-09-07 — 构建环境升级：Paper 1.21.4 + Java 21）
- **paper-api 升级到 1.21.4**：`pom.xml` 的 `paper-api` 由 `1.19.4-R0.1-SNAPSHOT` 升级到 `1.21.4-R0.1-SNAPSHOT`，对齐实际测试服务器版本
- **Java 编译目标升级到 21**：`pom.xml` 的 `java.version` 由 `17` 升级到 `21`，启用现代 Java 特性（如 `SequencedCollection`、`String#formatted` 等）
- **plugin.yml `api-version` 更新为 `'1.21'`**：与 Paper 1.21.4 服务端 API 兼容声明同步
- **适配 Particle API 变更**：1.21.4 中移除的粒子常量已替换为新版本：
  - `EXPLOSION_NORMAL` → `EXPLOSION`
  - `EXPLOSION_LARGE` → `EXPLOSION_EMITTER`
  - `BLOCK_CRACK` → `BLOCK`（需要 BlockData 参数）
  - `SPELL_WITCH` → `WITCH`
  - `REDSTONE` → `DUST`（需要 DustOptions 参数）
  - `ENCHANTMENT_TABLE` → `ENCHANT`
  - `VILLAGER_HAPPY` → `HAPPY_VILLAGER`
- **适配 PotionEffectType 变更**：
  - `SLOW` → `SLOWNESS`
  - `CONFUSION` → `NAUSEA`
- **适配 Attribute 变更**：`GENERIC_FOLLOW_RANGE` → `FOLLOW_RANGE`
- **影响文件**：`HammerEffectRenderer.java`、`DisplayEntityManager.java`、`HookClawHunterGoal.java`、`HammerListener.java`、`AirdropSpawnGoal.java`

### Changed（2026-09-06 — 游戏逻辑优化：强制生成点设置 + 节点简化为单一方块类型）
- **生成点设置改为强制**：删除"核心周围随机生成"的fallback逻辑，管理员必须在 Step 3 设置至少1个生成点才能完成部署。`WaveManager.getRandomSpawnLocation()` 不再有随机生成逻辑，强制从 `savedSpawnPoints` 列表中选择。原因：随机生成导致怪物出生位置不可控，影响游戏平衡性和地图设计
- **节点方块类型简化为仅重生锚**：`NodeSystem` 的 `nodeBlockTypes` 从4种方块（信标/附魔台/末影箱/重生锚）简化为仅 `Material.RESPAWN_ANCHOR`。原因：信标/附魔台/末影箱在塔防游戏中只提供原版功能（发光/附魔/存储），对游戏玩法没有实质性帮助；只有重生锚兼具原版重生功能和插件节点功能。管理员在地图上放置4-5个重生锚即可满足节点系统需求
- **更新节点相关文档和i18n消息**：README/README-EN中节点说明改为"使用重生锚方块"，删除信标/附魔台/末影箱的说明；`messages.yml` 的 `setup.step4-nodes`、`setup.node-not-valid-block`、`nodes.scan-empty-hint` 等消息全部更新为只提及重生锚

### Changed（2026-09-06 — 节点系统架构重构：从自动扫描改为手动注册）
- **节点系统从"自动扫描方块"改为"手动注册坐标+方块保护"**：原系统在游戏启动时异步扫描核心周围128格内的信标/附魔台/末影箱/重生锚方块，容易漏检且性能开销大。新系统改为管理员在部署向导 Step 4 手动添加节点（站在节点方块旁边点击[添加当前节点]按钮），节点坐标保存到 `nodes.yml` 配置文件，重启后从配置文件加载而非重新扫描。节点添加后自动保护对应方块不被破坏（BlockBreakEvent 拦截）
- **Step 4 从"扫描节点"改为"节点管理 GUI"**：新增类似 Step 3 生成点管理的 GUI（54格），支持添加、删除、粒子预览节点。添加节点时自动检测脚下方块类型，仅信标/附魔台/末影箱/重生锚可注册。GUI 底部提供 [上一页]、[添加当前节点]、[显示粒子]、[完成]、[下一页] 按钮（slot 45/49/47/51/53）
- **新增节点配置文件 `nodes.yml`**：结构为 `nodes: [{world, x, y, z, type, health}]`，记录节点坐标、方块类型和当前生命值。插件启动时从此文件加载节点，不再执行世界扫描
- **新增节点粒子显示方法 `AdminDeployManager.showNodeParticles()`**：30秒动画展示所有已注册节点位置（紫色魂焰粒子 SOUL_FIRE_FLAME 螺旋上升），帮助管理员可视化确认节点分布
- **新增节点管理子命令**：`/hilltop setup addnode`（添加当前位置为节点）、`/hilltop setup nodegui`（打开节点管理GUI）、`/hilltop setup nodes-done`（完成节点设置）、`/hilltop setup show-node-particles`（显示节点粒子）
- **新增方块破坏保护**：已注册的节点方块被玩家破坏时自动取消事件并提示"这是能量节点方块，不能破坏！如需移除请在部署向导中删除节点"（i18n key `nodes.block-protected`）
- **移除异步扫描代码**：删除 `NodeSystem.loadNodesFromWorld()` 的区块扫描逻辑（BukkitRunnable 分批扫描）、`loadNodesTask` 字段、`cancelLoadNodesTask()` 方法，全部改为同步从配置文件加载（`loadNodesFromConfig()`）
- **移除 `/hilltop deploy reloadnodes` 的扫描功能**：改为从配置文件重新加载节点，不再执行世界扫描
- **i18n 新增节点管理相关消息**：`setup.step4`（节点管理说明）、`setup.btn-add-node`（添加节点按钮）、`setup.btn-show-node-particles`（显示节点粒子按钮）、`setup.node-added`（节点添加成功）、`setup.node-invalid-block`（脚下方块类型无效）、`setup.node-deleted`（节点删除成功）、`setup.node-particles-started`（节点粒子已启动）、`setup.no-nodes-for-particles`（没有节点无法显示粒子）、`nodes.block-protected`（方块保护提示）

### Fixed（2026-09-06 — 节点管理GUI事件处理Bug + GUI删除操作后按钮重发）
- **修复节点管理GUI按钮点击无反应、物品可随意拖拽的严重Bug**：`SetupWizard.onInventoryClick()` 事件处理器仅检查了生成点GUI和预览GUI的标题，遗漏了节点管理GUI标题检查，导致节点GUI的点击事件被跳过、`event.setCancelled(true)` 未执行、物品可被随意拖拽。修复：(1) 第611行新增 `String nodesTitle = lang.getFor(player, "setup.gui-nodes-title");` 声明节点GUI标题；(2) 第615行条件检查增加 `&& !title.equals(nodesTitle)` 确保节点GUI事件被处理；(3) 第625行新增 `else if (title.equals(nodesTitle)) { handleNodesGuiClick(player, s, event); }` 路由到节点处理方法；(4) 新增独立的 `handleNodesGuiClick()` 方法处理所有节点相关按钮（setup-add-node-item / node-show-particles / setup-nodes-done / node-prev-page / node-next-page / node-{index} 删除），从 `handleSpawnGuiClick()` 中分离出来避免逻辑混乱
- **修复GUI内删除生成点/节点后按钮行丢失的用户体验问题**：在生成点管理GUI和节点管理GUI中删除条目时，虽然发送了删除成功的聊天消息，但没有重新发送操作按钮行（`[添加当前位置]` / `[打开管理GUI]`），导致用户关闭GUI后失去快速添加的入口。修复：(1) `handleSpawnGuiClick()` 的生成点删除分支（第692行后）增加 `sendSpawnButtons(player);`；(2) `handleNodesGuiClick()` 的节点删除分支（第782行后）增加 `sendNodeButtons(player);`。现在删除操作后，用户关闭GUI依然能看到聊天栏底部的操作按钮，可以立即走到新位置继续添加，体验与添加操作一致

### Fixed（2026-09-05 — 节点修复失效 / 生成点扩散 / GUI粒子预览 / 坐标整数化 / GUI反馈优化）
- **修复节点右键修复失效的关键 Bug（HashMap Location 键比较陷阱）**：`NodeSystem` 使用 `ConcurrentHashMap<Location, NodeData>` 存储节点，但 Bukkit 的 `Location.equals()` 会比较 world、x/y/z、**pitch/yaw（视角）**所有字段。存储时 `block.getLocation()` 返回的 Location 通常 pitch=0/yaw=0，查询时 `location.getBlock().getLocation()` 可能带有不同的 pitch/yaw 或 World 对象引用，导致即使坐标相同也无法匹配，`getNodeAt()` 返回 null，修复失败。修复：新增 `normalizeLocation()` 辅助方法创建只包含 world 和整数坐标的规范化 Location（忽略 pitch/yaw），存储（第 169-176 行）和查询（第 210-219 行）统一使用规范化 Location 作为 HashMap 键，确保坐标相同的节点必然匹配
- **新增生成点扩散半径（解决怪物堆叠问题）**：引入 `GameConfig.spawnRadiusSpread`（默认 3.0 格，可在配置 GUI "游戏规则" slot 23 调整 0.0-10.0），`WaveManager.getRandomSpawnLocation()` 在固定生成点周围 XZ 平面内随机偏移（角度随机、距离在 0-spread 内均匀分布），Y 坐标保持原逻辑（`getHighestBlockYAt() + yOffset + 1`），避免多个怪物在同一点堆叠；`game-settings.yml` 新增 `spawning.radius-spread: 3.0` 配置项（中英文注释）
- **新增生成点管理 GUI 内粒子预览按钮（slot 47）**：Step 3 生成点管理 GUI 新增"显示粒子效果"按钮（ENDER_EYE 末影之眼，紫色发光，中英文 i18n `setup.btn-show-particles`），点击后关闭 GUI 并触发 30 秒粒子动画展示所有已设生成点位置（复用 `AdminDeployManager.showSpawnPointParticles()`），方便管理员可视化确认生成点分布；新增 `/hilltop setup show-particles` 子命令（步骤 3 守卫）和点击事件处理（`setup-show-particles` PDC tag）；粒子展示成功时发送 `setup.particles-started` 提示（en/zh）
- **修复生成点坐标保存为方块整数坐标（粒子居中 + 数据规范化）**：`AdminDeployManager.addSpawnPoint()` 从直接保存玩家精确坐标 `location.clone()`（例如 `-98.529, 72.0, 133.515`）改为保存方块整数坐标 `new Location(world, getBlockX(), getBlockY(), getBlockZ())`（例如 `-98.0, 72.0, 133.0`）。修复前粒子显示在 `loc.getX() + 0.5 = -98.529 + 0.5 = -98.029`（偏离方块中心），修复后粒子显示在 `-98.0 + 0.5 = -97.5`（方块正中心）；配置文件 `spawnpoints.yml` 中坐标全部为整数，易读且与 Minecraft 方块坐标系统一致
- **优化 GUI 内添加生成点的反馈方式（避免无效聊天消息）**：GUI 内点击"添加当前位置"按钮时，移除聊天栏反馈消息（GUI 开启时用户看不到聊天记录，消息无意义），改为纯 GUI 反馈：音效（PLING）+ 粒子（ELECTRIC_SPARK）+ GUI 刷新显示新条目。聊天栏按钮入口（`/hilltop setup addspawn`）保持不变，依然在反馈后重新发送按钮行保证入口永远在聊天底部
- **删除 AdminDeployManager 旧粒子系统残留代码（架构清理）**：移除已被新系统替代的 `spawnPointParticles` Map、`PARTICLE_POOL`、`assignParticle()`、`reassignAllParticles()` 等旧粒子映射逻辑，保留 `showSpawnPointParticles()` 方法（新系统复用），移除构造函数中的 `reassignAllParticles()` 调用，全项目搜索确认无残留引用

### Fixed（2026-09-04 后半 — 节点修复失效 / 部署向导交互增强）
- **修复节点右键修复失效的关键 Bug（HashMap Location 键比较陷阱）**：`NodeSystem` 使用 `ConcurrentHashMap<Location, NodeData>` 存储节点，但 Bukkit 的 `Location.equals()` 会比较 world、x/y/z、**pitch/yaw（视角）**所有字段。存储时 `block.getLocation()` 返回的 Location 通常 pitch=0/yaw=0，查询时 `location.getBlock().getLocation()` 可能带有不同的 pitch/yaw 或 World 对象引用，导致即使坐标相同也无法匹配，`getNodeAt()` 返回 null，修复失败。修复：新增 `normalizeLocation()` 辅助方法创建只包含 world 和整数坐标的规范化 Location（忽略 pitch/yaw），存储（第 177 行）和查询（第 217 行）统一使用规范化 Location 作为 HashMap 键，确保坐标相同的节点必然匹配
- **新增部署向导交互增强命令**：`/hilltop setup preview` 随时重新打开 Step 6 配置预览 GUI（自动跳转到 step=6）；`/hilltop setup goto <1-7>` 跳转到指定步骤并发送对应消息/GUI（支持向前/向后跳转，便于管理员修改已完成的步骤）；新增 `sendStepWorldMessage`、`sendStepCoreMessage`、`sendStepShopMessage` 三个辅助方法供 goto 命令复用，确保跳转后显示完整步骤说明和按钮；两个命令均已添加 tab completion 和中英文 i18n key（`setup.goto-usage` / `setup.goto-invalid-step` / `setup.goto-invalid-number` / `setup.already-done`）
- **min-players 提示改进（引导使用 GUI 配置）**：`game-start-need-players` 消息从"or lower min-players in game-settings.yml"改为"or use `/hilltop config` to adjust min-players"（中文同步），引导管理员使用图形化配置中心而非手动编辑配置文件，降低配置出错风险
- **新增生成点管理 GUI 内粒子预览按钮（slot 47）**：Step 3 生成点管理 GUI 新增"显示粒子"按钮（FIREWORK_ROCKET，中英文 i18n `setup.btn-show-particles` / `setup.btn-show-particles-lore`），点击后触发 30 秒粒子动画展示所有已设生成点位置（复用 `AdminDeployManager.showSpawnPointParticles()`），方便管理员在 GUI 内可视化确认生成点分布而无需关闭 GUI 执行 `/hilltop deploy showparticles` 命令；粒子展示需至少有 1 个生成点，否则提示"至少需要 1 个生成点才能显示粒子"（新 key `setup.no-spawns-for-particles`）
- **优化生成点粒子效果为统一附魔台风格（方案C：符文+垂直光柱）**：将原先每个生成点使用不同粒子类型（21种颜色轮换）+ 复杂螺旋和正方形边框的效果，简化为统一风格：中心垂直光柱（END_ROD 白色粒子从地面延伸至 y+20，每隔 0.4 格一个粒子，超远距离可见）+ 周围装饰性螺旋符文（ENCHANT 紫色附魔台粒子螺旋上升 0-3 格高度），移除了 4 角正方形边框和连线；新效果更简洁、易辨认、性能更优，符合"附魔台美学"，适合大地图远距离生成点分布场景

### Fixed（2026-09-04 前半 — 节点扫描计数 Bug / 商店余额优化）
- **修复 Setup Step 4 节点扫描显示"找到 3 个"实际放了 9 个的关键 Bug**：`NodeSystem.loadNodesFromWorld()` 在扫描开始时立即 `nodes.clear()` + `activeNodes.clear()`，但扫描是异步分批进行（每 2 tick 处理 2000 个方块），扫描未完成期间 `getTotalCount()` 和 `getActiveCount()` 返回的是中间值而非最终值。修复：改为扫描期间使用临时集合 `tempNodes` / `tempActiveNodes` 存储，扫描完成后原子替换到 `nodes` / `activeNodes`，确保外部读取到的始终是完整结果
- **新增 Step 4 扫描进度提示（聊天栏）**：扫描开始时显示"正在扫描能量节点方块..."（新 i18n key `nodes.scan-start`），完成后显示"节点扫描完成！找到 X 个节点。"（新 key `nodes.scan-complete`，占位符 `{0}` 为节点数），扫描通常 <1 秒完成无需进度条
- **商店余额显示优化为 ActionBar（不占聊天栏）**：右键商店村民开启 GUI 时，余额提示从聊天栏 `sendMessage()` 改为 ActionBar `sendActionBar()`（屏幕下方物品栏上方，3 秒后自动消失）；购买成功后同步更新 ActionBar 余额，玩家实时看到剩余水晶数，无需关闭 GUI 查看聊天记录

### Added

#### 部署与向导
- 新增服主快速部署向导 `/hilltop setup`，从 6 步扩展为 7 步聊天栏 + GUI 混合引导：确认世界、设置核心、管理生成点、扫描能量节点、设置商店村民、配置预览、完成；每步自动保存并支持断点恢复。
- 新增部署向导 Step 3 生成点管理 GUI，支持添加、删除、粒子预览、完成、分页管理；管理 GUI 从 27 格扩展为 54 格，每页从 18 个扩展为 45 个，底部提供上一页、添加当前位置、完成和下一页入口。
- 新增聊天栏优先的生成点设置流程，以及 `/hilltop setup addspawn`、`spawngui`、`spawns-done` 子命令；管理员可先移动到目标位置再添加生成点，GUI 和聊天栏入口保持一致。
- 新增 Step 4 能量节点重新扫描入口和 `/hilltop setup rescan` 子命令；节点自动识别信标、附魔台、末影箱和重生锚。
- 新增 Step 5 商店位置设置，提供 `[设置商店位置]`、`[跳过]` 按钮和 `confirm-shop`、`skip-shop` 子命令；商店位置设置后立即生成 NPC。
- 新增配置预览 GUI，展示核心坐标、生成点数量、节点数量和波次设置总览。

#### Multi-Arena
- 新增 Multi-Arena Phase 1：`ArenaDefinition` 不可变数据模型、`ArenaRegistry` 内存注册表和 `ArenaManager` 生命周期管理。
- 新增 `arenas.yml` canonical 持久化结构，支持 schema-version、校验、稳定读写、默认 Arena 和世界查询。
- 现有单 `GameManager`、旧命令和游戏流程保持兼容。

#### 商店系统
- 新增村民商店基础系统：`ShopItem`、`ShopConfig`、`ShopConfigLoader` 和 `ShopManager`，支持 `shop.yml` 中的商品、价格、图标、槽位、名称和 lore 配置。
- 新增能量水晶货币物品，使用 PDC 标记并结合材质、显示名进行识别；波次怪物在运行和波次间歇期间按概率掉落货币。
- 新增商品发货路由，可购买重锤、烈焰蛋和世界树汁液；背包空间不足时将溢出物品掉落到玩家脚下。
- 新增 `/hilltop shop reload` 管理员命令及 Tab 补全，支持运行时重载商品配置。
- 新增商店 GUI：使用独立 `InventoryHolder`，支持自定义槽位、icon、名称、lore、价格显示、空位占位和按槽位识别商品。
- 新增商店 NPC 管理：村民带有 PDC 标记，支持重启恢复、无敌保护、孤儿实体清理和 `shop-npc` 位置持久化；商店 NPC 默认使用铁匠职业并显示可配置名称。
- 新增购买冷却、余额提示、购买成功、余额不足和背包已满处理，防止快速连点重复扣费。

#### 诊断与其他
- 新增 `/hilltop tps` 诊断命令，显示 TPS、MSPT、游戏状态、内存、每世界实体数、加载区块数和方块实体数，并在 MSPT 超过 50ms 时给出警告和建议。
- `commands.md` 支持服务器启动时自动生成完整命令文档。

### Changed

#### 配置与兼容
- 配置读取统一经由 `ConfigManager` / `GameConfig` / `MonsterConfig`，新增或补充 `spawnYOffset`、`nodeSearchRadius`、`repairCostName`、`repairCostItem`、`repairCostAmount`、`repairAmountPerCost`、`currencyDropChance`、`currencyDropAmount` 等字段。
- 世界树汁液默认材料从 `SLIME_BALL` 改为 `GHAST_TEAR`；旧配置文件中的 `SLIME_BALL` 不会被覆盖，需手动设置新材料或通过 `/hilltop config` 修改。
- GameConfig 的锤子名、烈焰蛋名、修复物品名和怪物显示名默认值改为空字符串，运行时回退到 i18n；删除无引用的节点消息配置字段和已验证的死 key。
- 统一 `messages.yml` 的扁平 key 结构，补充配置、部署、战斗、商店和向导相关注释；配置文件继续提供逐行中文注释。
- `LanguageManager` 改为以 JAR 内嵌文件为默认值、磁盘文件为覆盖层，并将合并后的缺失 key 写回磁盘，避免旧版语言文件导致原始 key 显示。
- 新增 `monsters-config.yml` 独立怪物配置文件支持，`ConfigManager` 分离保存游戏规则、波次、基础怪物属性和完整怪物属性。
- 版本号由 Maven 从 `pom.xml` 的 `${project.version}` 过滤注入 `plugin.yml`，避免运行时版本与构建版本不一致。
- 配置中心继续使用 `GameConfig.snapshot()` 深拷贝和变更追踪，支持保存、撤销、重载以及分级权限。

#### 国际化
- 配置菜单、部署向导、部署菜单、战斗消息、物品 lore、BossBar、ActionBar、怪物名称和商店界面全部改为通过 `LanguageManager` 动态翻译。
- `messages.yml` 中英双语补齐缺失 key，包括火球 lore 和冷却、配置菜单、物品名称、节点扫描提示、游戏启动提示、向导按钮、商店界面和购买流程等。
- 补充烈焰蛋材质翻译及 `config.menu.*` 配置中心 GUI 文案，玩家切换 `/hilltop lang en|zh` 后界面和命令输出保持一致。

#### 玩法与界面
- 部署菜单从箱子 GUI 改为可点击文本命令列表，并新增 `/hilltop setup` 入口；管理员命令补全增加 `setup` 和 `tps`。
- 新增管理员子命令 `/hilltop deploy help`，复用 `/hilltop help` 的命令帮助输出。
- 生成点按钮改为聊天栏优先，添加后显示序号反馈并重新发送操作入口；可操作按钮与完成反馈使用不同颜色，移除易误触的聊天栏 `[完成]` 按钮，`spawns-done` 保留向后兼容。
- 重锤改用 `NETHERITE_AXE`，取消铁砧 BlockDisplay 特效并改为多层原版粒子冲击波；烈焰蛋击退采用 BedWars1058 风格公式，击退倍率和冷却步长支持配置。
- 波次详情菜单升级为 54 格，增加添加怪物按钮和怪物属性编辑器；神圣重锤、节点、波次和怪物参数均支持配置中心编辑。
- `GameManager` 增加 `ConfigManager` 引用和玩家数量 getter，初始化顺序调整以保证节点和配置加载安全。
- `HammerEffectRenderer` 提取公共环形粒子方法；`GameManager.endGame` 等位置移除重复逻辑并提取命名常量。

### Fixed

#### 国际化
- 修复 `/hilltop lang en` 后配置 GUI 仍显示中文的问题，覆盖所有配置菜单标题、物品名、Lore 和操作提示。
- 修复 `/hilltop deploy`、命令帮助和悬停文字显示原始 i18n key 的问题，修复 `messages.yml` 嵌套区块覆盖扁平 key 导致翻译丢失的问题。
- 修复烈焰蛋获得消息、部署菜单描述、SetupWizard 按钮以及怪物和节点相关文本的硬编码中文。
- 修复中文界面显示英文材质名的问题，并删除英文语言区块中的重复 key。

#### 部署与 GUI
- 修复插件启动初始化顺序导致的 NPE 和插件被禁用问题，包括 Step 1 确认世界、`GameManager`、`NodeSystem` 和 `loadWorldReference()` 的加载顺序。
- 修复 SetupWizard GUI 显示 `TextComponentImpl` debug 文本、内部 tag、按钮无法点击和物品可移动问题；GUI 物品改用 PDC 保存 tag，并统一取消向导 GUI 内的物品移动。
- 修复 Step 5 预览 GUI 未重新注册监听器导致按钮无法点击的问题；修复预览使用错误核心坐标、断点恢复步骤错位和监听器重复注册问题。
- 修复中英文配置菜单 Back 按钮位置不一致，统一放在最后一行左端；修复 Config UI 重开后物品可拖拽、按钮重复触发和界面僵死问题。
- 修复生成点 GUI 原有 5 个上限和分页越界问题；修复 `addspawn` 反馈刷屏、按钮入口丢失和无向导会话时误报未知命令的问题。

#### 节点与游戏流程
- 修复能量节点扫描漏检：扫描范围改为默认水平半径 128 格和世界全高度，并按批次扫描；扫描为空时向在线管理员发送放置节点或检查半径的排障提示。
- 修复 `reloadnodes` 在扫描完成前显示 0 节点的问题，修复 `/hilltop start` 条件不满足时静默失败的问题，并避免失败时污染等待玩家集合。
- 修复 ConfigManager 遇到未知材质时 NPE，保留旧值并记录 warning；修复 GameConfig snapshot 浅拷贝导致 revert 无效的问题。

#### 战斗与资源清理
- 修复所有道具、节点修复、爆炸保护和重锤在游戏未运行时错误失效的问题。
- 修复 BossBar 无标题、蓄力后立即消失、落地不触发攻击、进度负值崩溃、重复创建和状态重叠问题。
- 修复烈焰蛋副手无法使用、`applyBwKnockback` 缺失、零向量导致 NaN、自身击退失效、垂直击退过强和水平击退过大的问题。
- 修复 `NumericInputHandler` 插件引用错误、`CAVE_SPIDER_MONSTER_EGG` 材质名错误、事件注销语法错误和 `GameState` 编译问题。
- 修复玩家退出时 HammerListener、FireballListener 和玩家状态未清理导致的 BossBar、冷却和内存泄漏；修复玩家数据并发访问问题。

### Performance

#### 批次 2（2026-09-13）— 主线程尖峰与 AI 热路径优化

- **钩爪猎手寻敌节流**：失败扫描增加明确的 8 tick 冷却，只遍历本竞技场玩家；每轮最多执行 3 条方块射线并用持久游标轮换候选，避免第 4 名以后玩家永久饿死；修复瞄准结束后无法实际发射钩爪的问题。
- **钩爪投射物生命周期补齐**：游戏停止、目标退出/跨世界、射手失效或超时时会主动移除投射物并取消任务，命中前重新验证竞技场归属，避免结算后残留或跨场命中。
- **空投寻点防同步区块加载**：两处 `getHighestBlockYAt` 前均检查 `isChunkLoaded`，未加载区块直接换点；候选玩家限制为本竞技场同世界玩家；空投技能冷却改为读取实际配置/GUI 使用的 `cooldown-ticks`。
- **节点最近查询降本**：`getNearestNode` 从遍历全部节点改为只遍历 `activeNodes`，同时修正所有节点注册、移除、摧毁、修复和 reload 路径的活跃索引一致性。
- **节点 AI 缓存与失效**：普通怪物和自爆甲虫使用 30 tick 重选周期，目标被摧毁、删除或 reload 替换时立即失效；活动期间也会周期重新评估更近节点；普通怪物搜索半径改用 `nodes.search-radius` 配置。
- **节点状态封装**：移除内部可变 Map 暴露，节点坐标返回 clone，外部无法再修改作为 HashMap key 的 `Location`；加载 nodes.yml 时验证实际方块必须为 `RESPAWN_ANCHOR`，运行中 reload 保留同坐标节点的当前血量/失活状态。
- **自爆甲虫节点伤害接线**：节点伤害改为怪物 `ATTACK_DAMAGE × explode-damage-multiplier × nodes.self-destruct-multiplier`，不再使用硬编码 40。
- **地图回滚节流**：每 tick 写入上限从 800 降为 200，预计单 tick 方块写入尖峰下降约 75%；爆炸批量记录只解析一次配置与竞技场。
- **地图回滚保真度与生命周期**：放置事件改存 `getBlockReplacedState()`；日志保存完整 `BlockData` 与 TileState 快照；单世界恢复任务唯一且支持合并完成回调，竞技场在回滚完整成功前保持 ENDING 屏障，插件关闭时同步完成剩余回滚而不再创建调度任务。
- **烈焰蛋任务合并**：每投射物一条 2 tick 任务改为全局唯一按需 ticker；碰撞仍每 2 tick 检测以保持高速近炸语义，火焰/烟雾和熔岩粒子分别节流到 4/6 tick。
- **烈焰蛋健壮性**：命中与近炸统一使用发射时配置快照；Dropper 不再被强转为 Dispenser；玩家、发射器、命中和近炸均验证所属运行竞技场；新增 `shutdown()` 清理任务、投射物、metadata 和冷却状态，并在 `onDisable()` 首先调用。

### Fixed（2026-09-13 — 既有缺陷修复：对局卡死 / 商店 NPC 被删 / 刷怪任务失控）

> 这三项是三维复验在推演对局生命周期时发现的**长期存在的既有缺陷**（非性能优化引入），均会影响正常对局。

- **修复 `min-players` 双源冲突导致对局卡死**：
  - 现象：单人进入 `min-players: 1` 的竞技场，会被传送进图且村民已生成，但**不刷怪、不走波次**，整局既不能胜也不能败
  - 根因：人数下限有两个来源且默认值不一致 —— `ArenaInstance` 读 `arenas.yml` 的 `game.min-players`（arena1 = 1），`GameManager` 读 `game-settings.yml` 的 `min-players`（= 2）。前者判定"可以开局"并进入 RUNNING，后者的 `startGame()` 因人数不足直接 return，于是 `isGameRunning()` 恒为 false，怪物 AI 也随之失效
  - 修复：**以每个竞技场自己的 `arenas.yml` 值为准**（保留 per-arena 配置能力，单人竞技场仍然可用），`GameManager` 新增 `getArenaMinPlayers()` 实时向所属竞技场取值（不缓存字段 —— 构造期竞技场尚未注册），`removeOfflinePlayer()` 的人数不足判负改用同一来源；`ArenaManager` 未显式配置时的默认值改为继承 `game-settings.yml` 的全局值；`/hilltop start` 命令的人数前置校验同步改为 `startArena.getMinPlayers()`（否则逻辑修好了、命令入口仍会被拦）
  - ⚠️ 注意：`game-settings.yml` 的 `min-players` 现在只作为「未显式配置的竞技场的默认值」，配置 GUI 里该项对**已在 `arenas.yml` 显式写明的竞技场**不生效
- **修复每局结束删光全服商店 NPC**：
  - 现象：每一局结束（胜利 / 失败 / 管理员 stop）后，大厅与所有竞技场的商店 NPC 都会消失，且只有重新走部署向导或重启服务器才会重建
  - 根因：`ArenaInstance` 自建了一个 `ShopManager`，其内部 `NpcManager.npc` 恒为 `null`；reset 时调用 `shopManager.cleanup()` → `NpcManager.cleanupOrphans()` 会遍历**所有世界**删除带商店 PDC 标记的实体，而用于"跳过自己"的 `if (entity == npc) continue` 因 `npc` 为 null 永不成立
  - 修复：竞技场**不再持有商店/NPC 生命周期**。删除 `ArenaInstance` 的 `shopManager` 字段、`new ShopManager(...)`、`reload()`、reset 中的 `cleanup()` 调用与 `getShopManager()`（全仓确认无调用者，调用方一律走 `plugin.getShopManager()` 插件级单例）。商店 NPC 的生成与清理现在只由部署向导与服务器启停负责
- **修复波次刷怪任务无法取消**：
  - 现象：对局结束、失败结算倒计时期间、甚至插件关闭时，刷怪任务仍会以 3 tick / 2 只继续 `world.spawnEntity`；关服时写进世界的怪物会在下次启动成为残留（`VillagerSystem.cleanupOrphans` 只清村民、不清怪物）
  - 根因：`WaveManager` 的刷怪 `BukkitRunnable` 没有保存句柄、不检查游戏状态，只在"刷完"时自取消，因此 `haltGame()` / `clearAllMobs()` / `stopGame()` / `onDisable` 都拦不住它
  - 修复：保存 `spawnTask` 句柄；`spawnWave()` 入口先取消旧任务（保证任意时刻至多一个刷怪任务）；任务体内增加 `isGameRunning()` 守卫；`clearAllMobs()` 首行取消任务（该方法已被 `endGame` / `stopGame` / `haltGame` 三处调用，一处覆盖全部停止路径）；被取消时**不**触发 `onComplete` 回调（保持原语义：只有自然刷完才回调）

### Fixed（2026-09-13 — 复验残留修正：波次调度器 / 配置文案 / i18n / 死代码）

> 上一条的修复经复验判定 PASS，但复验同时指出 4 项残留，一并处理。

- **修复波次调度器任务无法取消（与"刷怪任务失控"同类，上一轮只修了刷怪任务）**：
  - 现象：失败判定若发生在**两波之间的休整阶段**，结算倒计时期间会重新刷出怪物并再次播报 `wave-start`
  - 根因：`GameManager.onWaveComplete()` 用一个**没有保存句柄**的 `runTaskLater` 排队下一波，回调只检查 `state == WAVE_INTERMISSION`；而 `haltGame()` 刻意不改变状态（为了让玩家留在场上看战报），因此该任务既拦不住也取消不了
  - 修复：新增 `waveSchedulerTask` 句柄，在 `haltGame()` / `stopGame()` / `endGame()` 与 `startNextWave()` 入口统一取消（`startNextWave` 入口取消可防上次排队残留造成双开波次）；`haltGame()` 不改变状态的设计保持不变
- **修正配置 GUI 中 `min-players` 的说明文案**：该项自「以竞技场配置为准」的修复后只作为**未显式配置竞技场的兜底默认值**，但原文案（`少于该人数时将自动判负`）仍暗示它是生效值，容易重演"改了不生效"的困惑。更新 en/zh 双语说明（键名与数值范围未动）
- **两处硬编码英文改走 i18n**：`GameManager` 的"未配置生成点"提示改用既有键 `arena-no-spawn-points`（并把 `broadcastMessage` 换成按玩家语言解析的 `broadcastI18n`）；`HilltopCommandHandler` 的"未部署"提示改用 `arena-not-deployed` + `arena-not-deployed-hint`（保留原有的部署指引，该分支已校验管理员权限）
- **删除死代码 `ShopManager.cleanup()`**：上个修复移除了它在竞技场重置中的唯一调用后已无任何调用者，且其 javadoc 仍写着"竞技场结束时调用/清除所有商店 NPC 实体"，是一个随时可能被再次误用的「全局删 NPC」入口。全仓 grep 确认无调用者后删除；全局 NPC 清理现在只剩插件启停时机一个入口
- **删除死代码 `GameManager.broadcastMessage()`**：唯一调用点改走 i18n 后已无调用者（该方法发的是同一条已翻译文本、不按玩家语言解析，新代码应使用 `broadcastI18n`）
- **同步修正 `game-start-need-players` 提示文案**（en/zh）：原文指引"使用 `/hilltop config` 调整 min-players"，与上述同源 —— 改为指引 `arenas.yml` 的 `game.min-players`，并说明全局值仅对未显式配置的竞技场生效

### Fixed（2026-09-13 — 批次 2 修正轮：三维复验发现的 10 项回归）

> 批次 2 首轮改动经 build/code/arch 三维复验后判定 FAIL，以下为复验发现并修复的问题。部分问题由性能优化引入，部分为长期存在的既有缺陷。

- **修复节点血量跨局保留（破坏玩法）**：`loadNodesFromConfig()` 对同坐标节点复用旧对象并保留血量与失活状态，而它在每局开始都会被调用，导致第一局被打残/摧毁的节点在第二局仍是残血或不活跃。新增 `NodeSystem.resetAllNodes()` / `NodeData.resetToFull()`，仅在 `GameManager.startGame()`（唯一真正的开局点）调用；管理员 `reloadnodes` 路径保持原有"保留当前状态"语义不受影响。
- **修复结算倒计时任务丢失句柄导致旧局干扰新局**：结算倒计时改用 `endSequenceTask` 字段保存并在所有中断路径取消，同时引入 `sessionId` 局代号 + 状态双重守卫，过期回调静默丢弃（不单纯依赖 cancel 时机）。场景：结算倒计时期间 `/hilltop stop`，旧倒计时到点会把新一局玩家踢出并再次重置。
- **修复插件禁用阶段仍调度 Bukkit 任务**：`beginEndSequence()` 增加 `if (!plugin.isEnabled()) return;`，避免 `onDisable` 的同步清理路径抛 `IllegalPluginAccessException`；`onDisable()` 各阶段改为独立 try/catch，确保地图回滚这一最后屏障不会被前面的异常跳过。
- **修复跨局幽灵玩家**：`reset()` 只清 `ArenaInstance` 集合而未清 `GameManager` 的玩家集合，处于掉线重连窗口的离线玩家会跨局残留并污染开局人数、刷怪数量与存活判定。新增 `GameManager.clearAllPlayers()`（不做统计结算、不触发人数不足判负），在竞技场重置路径调用。
- **修复地图回滚失败方块丢失、无法重试**：失败方块改为保留完整原始快照并在恢复结束时重新写回日志；只有零失败才判为成功，竞技场保持 ENDING 屏障不开放半恢复地图。
- **修复禁用后立即启用的陈旧 reset 竞态**：`/hilltop disable` 后该竞技场上一局的延迟重置仍会执行，若此时已启用同世界新实例会被清掉新局。新增退役竞技场隔离窗口（`arena-reset-delay` + 2 秒，结算阶段再叠加 `defeat-countdown-seconds`），窗口内 `/hilltop enable` 自动排队重试而不是失败。
- **修复同一世界可被多个竞技场占用**：`ArenaManager.load()` 校验世界唯一性（不区分大小写），重复世界的定义拒绝加载并打印明确警告。这是地图回滚正确性的前提（回滚日志与恢复任务都以世界名为键）。
- **补齐流体记录**：新增 `PlayerBucketEmptyEvent` 与 `BlockFromToEvent` 监听，水/熔岩的倾倒与扩散现在会被记录并回滚；为避免流体扩散的高频事件逐条解析配置与竞技场，新增按 tick 的日志解析缓存（同一 tick 内每个世界只解析一次，日志移走/重建时立即失效）。
- **修复烈焰蛋飞行中读取热重载后的新配置**：`TrailState` 改存 `cfg.snapshot()` 深拷贝，命中路径与近炸路径使用同一个不可变快照，发射瞬间的伤害/半径/击退/近炸半径不再被中途的重载影响。
- **修复钩爪猎手冷却硬编码**：射击冷却改为读取 `hook-claw-hunter.cooldown-ticks`（GUI 与配置同一字段），配置缺失或非法时回退 60 tick。

#### 该轮次补充说明

- 复验还纠正了一处任务书 API 错误：1.21.4 的 `getBlockReplacedState()` **只存在于 `BlockPlaceEvent`**，`PlayerBucketEmptyEvent` 上不存在，桶事件改用 `event.getBlock().getState(true)`。
- 已知残留（未在本轮处理，留待后续评估）：`NodeData.maxHealth` 为 `final`，通过 GUI 调大 `nodes.baseHealth` 后已存在的节点仍沿用旧上限；`ArenaInstance` 与 `GameManager` 对 `loadNodesFromConfig()` 存在一次冗余调用；`ArenaRegistry.register` 未下沉世界唯一性校验（当前唯一调用点已覆盖）。

#### 性能
- `GameManager` 波次检查改为单一 repeating task，结束或停止游戏时取消任务。
- NodeSystem 使用 `activeNodes` 缓存降低 Buff 检查复杂度；区块扫描采用分批异步处理、列检查缓存和更长批次间隔。
- WaveManager 分批生成怪物；Airdrop 使用 velocity 引导代替 teleport；CommandsDocumentGenerator 改为异步写入。
- FireballListener 冷却计时器按需运行并使用共享计时器；飞行粒子由每 tick 降为每 2 tick；DisplayEntityManager 清理任务按需运行。
- HammerListener 对纯视角旋转进行过滤；HammerEffectRenderer 复用公共粒子环逻辑；生成点粒子降低刷新频率、缩短连线步数并增加距离剔除。

### Compatibility

#### 配置迁移
- 旧 `spawnpoints.yml` 和 `game-settings.yml` 保留、不删除；首次加载时支持幂等迁移到 `arenas.yml` 的 `default` Arena，并记录来源及冲突 warning。
- 已部署服务器的旧 `game-settings.yml` 不会被 JAR 默认值覆盖；世界树汁液如需使用新默认值，请手动设置 `nodes.repair-item: GHAST_TEAR` 或通过 `/hilltop config` 修改。
- 旧版 SetupWizard 断点步骤仍可恢复：原 step=5/6 的语义变化会进入对应的新商店或预览步骤，管理员可跳过并继续。
- 旧版玩家手中的 `SLIME_BALL` 修复物品在默认配置切换后会失效；如需兼容旧物品，可执行 `/hilltop config nodes repairitem SLIME_BALL` 并同步修改配置。

### Verification status

- `mvn clean compile`: BUILD SUCCESS
- `mvn clean package -DskipTests`: BUILD SUCCESS
- 当前构建产物：`target/HilltopVillage-1.0.9-SNAPSHOT.jar`
- 当前仍处于 `1.0.9-SNAPSHOT` 开发和服务器实测周期，JAR 仅在 `target/`，尚未归档到 `versions/`，等待用户实测和正式发布确认。

## [1.0.8] - 2026-06-07

### Added
- 新增 README.md 和 README-EN.md（中英文项目文档）
- 新增烈焰蛋系统 (Fireball)，仿起床战争火球：右键投掷 / 飞行焰火粒子 / 命中范围爆炸 / 怪物伤害+击退 / 冷却可配置
- 玩法物品设置统一入口 (ItemSettingsMenu)，集成重锤与烈焰蛋全部参数
- 部署命令可点击菜单 (`/hilltop deploy`)，点击自动填入聊天栏
- 烈焰蛋对自己无伤害仅击退
- 新增神圣重锤每档可独立配置：蓄力距离、伤害值、AOE 范围（替代旧的 基础伤害×倍率 模型），GUI 3×3 网格布局
- 新增 `/hilltop tps` 诊断命令：显示服务器 TPS/MSPT、游戏状态、内存占用等实时性能数据

### Fixed
- 修复所有道具（重锤、烈焰蛋、钩爪）在游戏未运行时无法使用的问题：移除了所有物品使用相关事件处理器中的 isGameRunning() 守卫
- 修复重锤蓄力条（BossBar）在游戏未运行时无法更新的问题：onPlayerMove 移除顶部守卫，改为仅在落地执行猛击时检查游戏状态
- 修复重锤蓄力条激活后立即消失的问题：isPlayerOnGround 中 player.getFallDistance()==0 在跳跃上升时误判为落地，导致蓄力状态被瞬间清除
- 修复重锤蓄力条显示为无标题灰条的问题：BossBar 创建时标题为空字符串，且依赖 onPlayerMove 才设置初始标题，若玩家未移动则一直显示空灰条
- 修复重锤蓄力条显示后落地无攻击效果的问题：onPlayerMove 中 isGameRunning() 守卫在玩家落地时拦截了 executeSmash() 调用，导致蓄力完成但猛击无法触发
- 修复节点修复和爆炸保护在游戏未运行时失效的问题：NodeListener 中 isGameRunning() 守卫导致游戏外节点可被炸毁、修复物品无法使用
- 修复神圣重锤蓄力 BossBar 抽搐：title/color 改为仅在 tier 变化时更新（0→1→2），避免每 move-event 都发包导致客户端闪屏
- 修复玩家副手无法使用烈焰蛋：移除 main-hand-only 限制
- 修复烈焰蛋 applyBwKnockback 方法定义缺失导致编译失败
- 修复烈焰蛋击退改为风弹风格：方向分量 + 固定向上分量，站地面贴脸发射也能弹起
- 修复烈焰蛋爆炸时投掷者恰好在爆炸中心导致 NaN 崩溃（零向量 normalize 保护 + 距离 < 0.01 跳过）
- 修复烈焰蛋对自己击退彻底修复：改用 projectile.getShooter() 替代 metadata 获取投掷者 + 延迟 1 tick 应用 velocity 防止被覆盖 + 加大击退倍率至 5.0
- 修复烈焰蛋对自己击退不生效（metadata 在读取前被 removeMetadata 删除导致 throwerUuid 恒为 null）
- 减少烈焰蛋垂直击退倍率：applyBwKnockback 中 3 处垂直分量 1.5x 乘数降至 0.45x，脚下贴脸发射从 y=71→130 降至约 y=71→92
- 修复烈焰蛋稍微偏离垂直方向时水平击退过大（横飞 25 格）的问题：水平分量改用独立倍率 0.25x，与垂直倍率 0.45x 分离
- 修复 NumericInputHandler 中 3 处插件名忘改导致 Plugin null 崩溃（输入数值后无法修改 + AsyncPlayerChat 异常）
- 修复 AbstractConfigMenu.reopen() 每次注册事件前先 unregisterAll，修复监听器堆积导致按钮点击触发多次（重载/保存消息重复发送）
- 修复 Config UI reopen 后物品可拖拽问题（延迟 1 tick 重建 Inventory）
- 修复 BossBar 蓄力重叠问题（复用同一 BossBar 实例）
- 修复 BossBar 进度负值崩溃（clamp 到 0.0-1.0）
- 修复 PlayerQuit 时未清理 HammerListener（BossBar/recentlyLanded）与 FireballListener（cooldowns）状态，修复内存泄漏

### Changed
- 插件统一命名：Hilltop Village（artifactId: HilltopVillage，原名 HilltopDefense/峰上村庄守卫战）
- 开源协议从 MIT License 更换为 PolyForm Noncommercial License 1.0.0
- 重锤材质从铁斧改为下界合金斧 (NETHERITE_AXE)
- 重锤取消铁砧 BlockDisplay 特效，改为多层原版粒子冲击波 (爆炸/碎石/烟柱/闪电)
- 烈焰蛋击退公式改为 BedWars1058 开源代码风格：水平推离爆炸中心 + 动态 Y 轴处理（if y<0 → y+=1.5 补偿脚下爆炸 / y<=0.5 固定向上 / 否则按比例）
- 烈焰蛋冷却时间 GUI 步长从 5 改为 1 tick，支持设为 0（无冷却），显示精度提升至 0.1 秒
- 烈焰蛋击退改为可配置参数 (fireballKnockback)，GUI 可调范围 0.5~20，默认 5.0
- 烈焰蛋默认冷却 60 tick 改为 3 tick
- 冷却时间 GUI 图标从 CLOCK 改为 REDSTONE（设置值后无需再给玩家时钟物品）
- 波次详情菜单 WaveDetailMenu 升级为 54 格，新增添加怪物按钮 + 怪物属性编辑器
- 部署菜单从箱子 GUI 改为可点击文本命令列表

### Optimized
- 烈焰蛋冷却从 N 个独立每 tick BukkitRunnable 改为单个共享 2-tick 计时器，大幅降低 CPU 占用
- FireballListener 冷却计时器改为按需运行：无人冷却时自动停止，有人冷却时自动启动
- DisplayEntityManager 清理任务改为按需运行：队列为空时自动停止，有实体时自动启动
- 烈焰蛋飞行轨迹粒子频率从每 tick 降为每 2 tick，粒子开销减半
- 神圣重锤 onPlayerMove 增加 hasChangedPosition() 过滤，纯视角旋转直接跳过，减少无效事件处理
- NodeSystem 区块扫描从同步全量改为分批异步：每 tick 处理 2000 方块，仅扫描已加载区块，搜索半径从 80 缩小到 40，Y 轴范围从 60 缩小到 40，防止主线程阻塞导致 TPS 暴跌
- NodeSystem isChunkLoaded 缓存优化：同一 X,Z 列复用检查结果，避免 40 次冗余调用，批次间隔从 1 tick 增至 2 tick
- WaveManager 怪物生成从同步一次性全量改为分批异步：每 3 tick 生成 2 只，防止大量生成冻结主线程
- /hilltop tps 命令增强：新增服务器级诊断（每世界实体数、加载区块数、方块实体数），MSPT > 50ms 时输出警告和建议

### Refactored
- 删除旧 HammerSettingsMenu，由 ItemSettingsMenu 替代
- ConfigManager 新增独立怪物配置文件 monsters-config.yml 读写支持

## [1.0.7] - 2026-05-22

### Fixed
- **修复 config GUI 点击 +/- 后 UI 僵死、物品可随意移动**：`AbstractConfigMenu.reopen()` 在 `InventoryClickEvent` 处理器内直接调用 `openInventory()` 导致视图状态不一致，改为延迟 1 tick 执行 (`Bukkit.getScheduler().runTask()`)

## [1.0.6] - 2026-05-21

### Added
- **独立怪物配置文件系统**：新增 `monsters-config.yml`，支持定义怪物完整属性（模型外观、基础伤害、手持武器/工具、盔甲穿戴），首次启动自动从 jar 复制到 `plugins/HilltopVillage/`
- **MonsterConfig 扩展**：新增 `baseDamage`/`itemsAdderId`/`customModelData`/`mainHandItem`/`offHandItem`/`helmet`/`chestplate`/`leggings`/`boots` 共 9 个字段
- **怪物装备系统**：`WaveManager.applyMonsterEquipment()` 在怪物生成时自动装备武器、副手、头盔、胸甲、护腿、靴子，掉落率 5%
- **MonsterSettingsMenu 升级为 54 格完整菜单**：新增基础伤害、ItemsAdder 模型 ID、自定义模型数据、主手武器（13 种循环）、副手物品（盾牌/图腾/空）、头盔/胸甲/护腿/靴子（7 种材质循环）编辑项
- **材质循环选择系统**：`WEAPON_CYCLE`(13 种)/`OFFHAND_CYCLE`(3 种)/`ARMOR_CYCLE`(7 种) — 点击循环切换，Shift+点击自定义 Material 名，所有材质名自动汉化
- **NumericInputHandler 字符串输入**：新增 `requestString()` 方法，支持聊天栏输入任意文本（ItemsAdder ID、Material 名），30 秒超时
- **多语言 (i18n) 系统**：`LanguageManager` 支持 `en`/`zh` 双语，玩家可独立设置语言偏好，占位符 `{0}` `{1}` 支持，颜色使用 `&` 代码
- **`messages.yml` 语言文件**：约 70+ 条消息全部中英双语，首次启动从 jar 复制，管理员可直接编辑后重启生效
- **`/hilltop lang <en|zh>` 命令**：任意玩家可用，无权限限制，切换后所有命令输出立即以所选语言显示，偏好持久化到 `config.yml`
- **`/hilltop deploy showparticles` 命令**：以每个生成点为中心、半径 3 格螺旋动画展示粒子效果，默认持续 30 秒，重复执行重置计时
- **每个生成点的独特粒子效果**：21 种粒子池按索引自动分配
- **粒子连线效果**：每个生成点 4 个角粒子之间用同种粒子绘制连线（8 个插值点/边），形成方形轮廓
- **`commands.md` 自动生成**：服务器每次启动时自动在 `plugins/HilltopVillage/commands.md` 生成完整命令文档
- **配置文件中文化**：`config.yml`/`game-settings.yml` 每个节点逐行中文注释
- **命令帮助可点击复制**：`sendUsage()` 使用 Paper Adventure API 的 `ClickEvent.suggestCommand()`
- **BossBar 蓄力进度条**：右键激活锤子后，屏幕顶部出现蓄力 BossBar，随下落距离动态更新进度和颜色（黄→红）
- **物品模型自定义功能**：重锤支持配置 `use-itemsadder`/`itemsadder-id`/`custom-model-data`，兼容 ItemsAdder 和原版资源包
- **群体攻击 (AOE)**：猛击落地时对范围内玩家击飞、怪物造成伤害+击退
- **玩家弹起机制**：`executeSmash()` 中为玩家添加向上速度
- **管理员部署菜单系统** (`/hilltop deploy`)：GUI 菜单设置核心位置、管理怪物生成点（添加/列表/删除/清除/粒子展示）
- **生成点精确管理**：`addspawn`/`listspawns`/`delspawn`/`clearspawns`/`confirmclear` 子命令，生成点坐标持久化到 `spawnpoints.yml`
- **BedWars1058 风格配置中心** (`/hilltop config`)：多级 GUI 菜单系统，实时编辑游戏参数（MainConfigMenu/GameRulesMenu/WaveSettingsMenu/MonsterSettingsMenu/HammerSettingsMenu/NodeSettingsMenu）
- **`AbstractConfigMenu` 抽象基类**：统一 Inventory GUI 管理（点击路由、返回/关闭按钮、边框填充）
- **`NumericInputHandler`**：聊天栏数值输入（30 秒超时，支持整数和浮点数）
- **`ConfigPermission` 权限分级**：`config.admin`/`config.waves`/`config.monsters`/`config.items`/`config.nodes`/`config.rules`
- **`game-settings.yml` 配置文件**：所有游戏参数可持久化编辑，支持保存/撤销/重新加载
- **变更追踪机制**：`GameConfig.snapshot()` 深拷贝 + `ConfigManager.hasUnsavedChanges()` 比较，未保存时主菜单保存按钮高亮
- **`/hilltop reloadconfig`**：从 `game-settings.yml` 重新加载游戏设置
- **自定义 AI 怪物系统**：三种特殊怪物各自拥有独立 AI Goal（ExplodeBeetleGoal/HookClawHunterGoal/AirdropSpawnGoal）
- **怪物配置文件**：`config.yml` 中 `monsters` 节点定义各怪物生命/速度/伤害/冷却等属性
- **波次组合配置**：`config.yml` 中 `waves` 节点定义每波怪物类型/数量/权重，支持自定义波次覆盖
- **`/hilltop help` 命令**：列出所有可用命令及简要说明
- **TAB 命令补全**：`/hilltop` 子命令完整补全
- **神圣重锤战斗系统**：右键空中激活蓄力（100 tick 超时），落地时根据下落高度造成三档伤害（5m/10m 阈值），AOE 半径可配置
- **铁砧砸地特效**：`BlockDisplay` 铁砧模型配合粒子效果（`BLOCK_CRACK` + `CLOUD`）
- **余震减速**：落地后地面持续减速效果（60 tick，2 级迟缓）
- **能量节点系统**：信标/附魔台/末影箱/重生锚作为节点，可被怪物攻击摧毁，玩家使用"世界树汁液"修复
- **节点增益 BUFF**：节点范围内玩家获得抗性提升 + 生命恢复

### Fixed
- **修复波次添加后 GUI 交互异常**：`AbstractConfigMenu.reopen()` 未重新注册事件监听器导致点击无响应、物品可拖拽
- **修复 BossBar 蓄力进度负值崩溃**：`setProgress()` 要求值在 `[0.0, 1.0]` 闭区间，玩家先跳后落时 `fallDist` 为负，增加 `Math.max(0.0, ...)` 钳制
- **修复 BossBar 蓄力重叠**：每次右键创建全新 `BossBar` 实例而不复用，改为 `ConcurrentHashMap` 缓存，每个玩家终生复用同一个实例
- **修复 `/hilltop` 悬停文字显示原生 key**：`LanguageManager` 缓存仅从磁盘文件加载 keys，jar 默认值中新增的 key 未合并
- **修复 AbstractConfigMenu 构造器缺 plugin 参数**：所有 6 个子菜单类 `super()` 调用同步增加第 4 参数
- **修复 BossBar 实例泄漏**：改为 `ConcurrentHashMap` 缓存终生复用
- **修复首次启动 NPE**：`GameManager` 构造函数中 `loadSpawnPoints()` 在 `loadWorldReference()` 之前执行导致空指针，调整初始化顺序
- **修复 `NumericInputHandler` 未实现 `Listener`**：添加 `implements Listener`
- **修复 `CAVE_SPIDER_MONSTER_EGG` 不存在**：Paper 1.21.4 使用 `CAVE_SPIDER_SPAWN_EGG`
- **修复 `InventoryClickEvent.getHandlerList().unregister()` 语法错误**：改为 `HandlerList.unregisterAll(this)`
- **修复重锤右键无法蓄力**：移除了 `isGameRunning()` 守卫条件，允许非游戏状态下激活锤子
- **修复 `GameState` 枚举编译问题**
- **修复 `PlayerData` 并发访问线程安全**（`ConcurrentHashMap`）

### Changed
- **铁砧特效简化**：取消放大动画和逐帧 `BukkitRunnable`，固定 0.5x 尺寸，直接生成在玩家脚部无 Y 偏移；取消旋转动画，仅保留向下的仿真物理下落轨迹
- **配置文件全部添加中文注释**：`config.yml`/`game-settings.yml`/`messages.yml` 每个字段逐行中文注释
- **`messages.yml` 分层注释**：语言区块内按类别分组注释
- **波次分页阈值文档化**：`SLOTS_PER_PAGE = 21` 添加 JavaDoc 注释
- **ConfigManager 双文件架构**：`game-settings.yml` 存储游戏规则/波次/基础怪物属性，`monsters-config.yml` 存储怪物完整属性
- **GameConfig 扩展**：新增 `hammerItemsAdderId`/`hammerCustomModelData`/`hammerUseItemsAdder` 字段，支持 ItemsAdder 模型配置
- **HammerListener** 使用反射调用 ItemsAdder API 无需编译期依赖
- `AdminDeployManager` 粒子类型持久化到 `spawnpoints.yml`（`particle` 字段）
- 部署菜单新增 `showparticles` 操作提示
- `LanguageManager` 初始化时自动合并 jar 默认值与磁盘文件，确保新版本新增 key 可正常翻译
- `config.yml` 新增 `language` 配置项
- **伤害汉化**：伤害等级从 `LOW/MEDIUM/HIGH` 改为 `低档/中档/高档`，伤害提示从聊天栏刷屏改为 ActionBar
- **移除聊天栏"天降正义"刷屏**：蓄力阶段不再发送聊天栏消息
- 编译命令固化到 `.trae/rules/project_rules.md`
- `WaveManager` 和 `HammerListener` 从 `GameConfig` 读取参数替代硬编码 `config.yml` 读取
- `GameManager` 新增 `ConfigManager` 引用并调整初始化顺序

### Optimized
- 烈焰蛋冷却从 N 个独立每 tick BukkitRunnable 改为单个共享 2-tick 计时器，大幅降低 CPU 占用
- FireballListener 冷却计时器改为按需运行：无人冷却时自动停止，有人冷却时自动启动
- DisplayEntityManager 清理任务改为按需运行：队列为空时自动停止，有实体时自动启动
- 烈焰蛋飞行轨迹粒子频率从每 tick 降为每 2 tick，粒子开销减半
- 神圣重锤 onPlayerMove 增加 hasChangedPosition() 过滤，纯视角旋转直接跳过，减少无效事件处理
- NodeSystem 区块扫描从同步全量改为分批异步：每 tick 处理 2000 方块，仅扫描已加载区块，搜索半径从 80 缩小到 40，Y 轴范围从 60 缩小到 40，防止主线程阻塞导致 TPS 暴跌
- NodeSystem isChunkLoaded 缓存优化：同一 X,Z 列复用检查结果，避免 40 次冗余调用，批次间隔从 1 tick 增至 2 tick
- WaveManager 怪物生成从同步一次性全量改为分批异步：每 3 tick 生成 2 只，防止大量生成冻结主线程
- /hilltop tps 命令增强：新增服务器级诊断（每世界实体数、加载区块数、方块实体数），MSPT > 50ms 时输出警告和建议

### Refactored
- 删除旧 HammerSettingsMenu，由 ItemSettingsMenu 替代
- ConfigManager 新增独立怪物配置文件 monsters-config.yml 读写支持

## [1.0.0] - 2026-05-15

### Added
- 初始版本发布
- 村民守卫战PLUS 合作 PvE 塔防游戏框架
- 游戏状态机：`WAITING` → `STARTING` → `RUNNING` → `WAVE_INTERMISSION` → `VICTORY` / `DEFEAT`
- **基础命令系统**：`/hilltop start|stop|join|leave|status|hammer`
- 玩家数据管理（`PlayerData`）与游戏管理（`GameManager`）
- 波次生成系统（`WaveManager`）：加权随机选怪、固定点生成/环形随机生成
- `spawnpoints.yml` 生成点持久化
- `plugin.yml` 命令注册与权限节点定义
- Maven 构建系统（Paper 1.21.4，Java 17）
