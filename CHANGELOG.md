# Changelog

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
