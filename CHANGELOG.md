# Changelog

## [Unreleased] - 2026-05-22

### Added
- 新增 README.md 和 README-EN.md（中英文项目文档）
- 新增烈焰蛋系统 (Fireball)，仿起床战争火球：右键投掷 / 飞行焰火粒子 / 命中范围爆炸 / 怪物伤害+击退 / 冷却可配置
- 玩法物品设置统一入口 (ItemSettingsMenu)，集成重锤与烈焰蛋全部参数
- 部署命令可点击菜单 (`/hilltop deploy`)，点击自动填入聊天栏
- 烈焰蛋对自己无伤害仅击退

### Changed
- 重锤材质从铁斧改为下界合金斧 (NETHERITE_AXE)
- 重锤取消铁砧 BlockDisplay 特效，改为多层原版粒子冲击波 (爆炸/碎石/烟柱/闪电)
- 波次详情菜单 WaveDetailMenu 升级为 54 格，新增添加怪物按钮 + 怪物属性编辑器
- 部署菜单从箱子 GUI 改为可点击文本命令列表

### Fixed
- 烈焰蛋对自己击退不生效（metadata 在读取前被 removeMetadata 删除导致 throwerUuid 恒为 null）
- Config UI reopen 后物品可拖拽问题（延迟 1 tick 重建 Inventory）
- BossBar 蓄力重叠问题（复用同一 BossBar 实例）
- BossBar 进度负值崩溃（clamp 到 0.0-1.0）
- 烈焰蛋默认冷却 60 tick 改为 3 tick

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

### Fixed
- **修复波次添加后 GUI 交互异常**：`AbstractConfigMenu.reopen()` 未重新注册事件监听器导致点击无响应、物品可拖拽。根因是 `InventoryCloseEvent` 中 `HandlerList.unregisterAll(this)` 注销后 `reopen()` 仅重建 Inventory 内容而未重新 `registerEvents`
- **修复 BossBar 蓄力进度负值崩溃**：`setProgress()` 要求值在 `[0.0, 1.0]` 闭区间，玩家先跳后落时 `fallDist` 为负导致 `IllegalArgumentException`，增加 `Math.max(0.0, ...)` 钳制
- **修复 BossBar 蓄力重叠**：每次右键创建全新 `BossBar` 实例而不复用，改为 `ConcurrentHashMap` 缓存，每个玩家终生复用同一个实例
- **修复 `/hilltop` 悬停文字显示原生 key**：`LanguageManager` 缓存仅从磁盘文件加载 keys，jar 默认值中新增的 `usage-click-to-copy` key 未合并。修复：缓存时自动合并 jar 默认值中的缺失 key
- **修复 AbstractConfigMenu 构造器缺 plugin 参数**：所有 6 个子菜单类 `super()` 调用同步增加第 4 参数 `HilltopVillagePlugin.getPlugin(HilltopVillagePlugin.class)`

### Changed
- **铁砧特效简化**：取消放大动画和逐帧 `BukkitRunnable`，固定 0.5x 尺寸，直接生成在玩家脚部无 Y 偏移
- **配置文件全部添加中文注释**：`config.yml`/`game-settings.yml`/`messages.yml` 每个字段逐行中文注释（说明含义、取值范围、使用方式）
- **`messages.yml` 分层注释**：语言区块内按类别分组注释（通用提示/游戏状态/命令帮助/配置管理/部署管理/粒子展示/语言切换）
- **波次分页阈值文档化**：`SLOTS_PER_PAGE = 21` 添加 JavaDoc 注释说明分页逻辑
- **ConfigManager 双文件架构**：`game-settings.yml` 存储游戏规则/波次/基础怪物属性，`monsters-config.yml` 存储怪物完整属性（模型/装备），保存时同步写入两个文件

## [1.0.5] - 2026-05-20

### Added
- **多语言 (i18n) 系统**：`LanguageManager` 支持 `en`/`zh` 双语，玩家可独立设置语言偏好，占位符 `{0}` `{1}` 支持，颜色使用 `&` 代码
- **`messages.yml` 语言文件**：约 70+ 条消息全部中英双语，首次启动从 jar 复制，管理员可直接编辑后重启生效
- **`/hilltop lang <en|zh>` 命令**：任意玩家可用，无权限限制，切换后所有命令输出立即以所选语言显示，偏好持久化到 `config.yml`
- **`/hilltop deploy showparticles` 命令**：以每个生成点为中心、半径 3 格螺旋动画展示粒子效果，默认持续 30 秒，重复执行重置计时
- **每个生成点的独特粒子效果**：21 种粒子池（`FLAME`/`DRIP_LAVA`/`ENCHANTMENT_TABLE`/`END_ROD`/`GLOW`/`HEART`/`NOTE`/`PORTAL`/`SOUL_FIRE_FLAME`/`SPELL_WITCH`/`VILLAGER_HAPPY`/`WAX_OFF`/`ELECTRIC_SPARK`/`SCRAPE`/`WARPED_SPORE`/`CHERRY_LEAVES`/`ASH`/`CRIMSON_SPORE`/`SOUL`/`COMPOSTER` 等），按索引自动分配
- **粒子连线效果**：每个生成点 4 个角粒子之间用同种粒子绘制连线（8 个插值点/边），形成方形轮廓
- **`commands.md` 自动生成**：服务器每次启动时自动在 `plugins/HilltopVillage/commands.md` 生成完整命令文档（21 条命令的说明/格式/权限/示例/注意事项）
- **配置文件中文化**：`config.yml`/`game-settings.yml` 每个节点逐行中文注释
- **命令帮助可点击复制**：`sendUsage()` 使用 Paper Adventure API 的 `ClickEvent.suggestCommand()`，点击命令行自动填入聊天栏，悬停显示"点击复制到聊天栏"（中英双语）

### Changed
- **GameConfig 扩展**：新增 `hammerItemsAdderId`/`hammerCustomModelData`/`hammerUseItemsAdder` 字段，支持 ItemsAdder 模型配置
- **HammerListener** 使用反射调用 ItemsAdder API 无需编译期依赖
- `AdminDeployManager` 粒子类型持久化到 `spawnpoints.yml`（`particle` 字段）
- 部署菜单新增 `showparticles` 操作提示
- `LanguageManager` 初始化时自动合并 jar 默认值与磁盘文件，确保新版本新增 key 可正常翻译
- `config.yml` 新增 `language` 配置项

## [1.0.4] - 2026-05-19

### Added
- **BossBar 蓄力进度条**：右键激活锤子后，屏幕顶部出现蓄力 BossBar，随下落距离动态更新进度和颜色（黄→红），`Math.max(0.0, ...)` 防止负值崩溃
- **物品模型自定义功能**：重锤支持配置 `use-itemsadder`/`itemsadder-id`/`custom-model-data`，兼容 ItemsAdder 和原版资源包
- **群体攻击 (AOE)**：猛击落地时对范围内玩家击飞、怪物造成伤害+击退，适配村民守卫战群架特性
- **玩家弹起机制**：`executeSmash()` 中为玩家添加向上速度 `(0, 0.8 + fallDistance*0.05, 0)`

### Fixed
- **修复 BossBar 实例泄漏**：每次右键创建新 `BossBar` 导致重叠，改为 `ConcurrentHashMap` 缓存终生复用
- **修复首次启动 NPE**：`GameManager` 构造函数中 `loadSpawnPoints()` 在 `loadWorldReference()` 之前执行导致空指针，调整初始化顺序
- **修复 `NumericInputHandler` 未实现 `Listener`**：添加 `implements Listener`
- **修复 `CAVE_SPIDER_MONSTER_EGG` 不存在**：Paper 1.21.4 使用 `CAVE_SPIDER_SPAWN_EGG`
- **修复 `InventoryClickEvent.getHandlerList().unregister()` 语法错误**：改为 `HandlerList.unregisterAll(this)`

### Changed
- **铁砧特效优化**：取消旋转动画，仅保留向下的仿真物理下落轨迹；取消放大效果，固定 0.5x 尺寸生成在玩家脚部
- **伤害汉化**：伤害等级从 `LOW/MEDIUM/HIGH` 改为 `低档/中档/高档`，伤害提示从聊天栏刷屏改为 ActionBar
- **移除聊天栏"天降正义"刷屏**：蓄力阶段不再发送聊天栏消息
- 编译命令固化到 [`.trae/rules/project_rules.md`](file:///d:/Server/MCServers/Server%20plugin/Hilltop%20Village/.trae/rules/project_rules.md)：每次代码变更后自动 `mvn clean package`

## [1.0.3] - 2026-05-18

### Added
- **管理员部署菜单系统** (`/hilltop deploy`)：GUI 菜单设置核心位置、管理怪物生成点（添加/列表/删除/清除/粒子展示）
- **生成点精确管理**：`addspawn`/`listspawns`/`delspawn`/`clearspawns`/`confirmclear` 子命令，生成点坐标持久化到 `spawnpoints.yml`
- **BedWars1058 风格配置中心** (`/hilltop config`)：多级 GUI 菜单系统，实时编辑游戏参数
  - `MainConfigMenu`：导航中心（游戏规则/波次/怪物/重锤/节点）
  - `GameRulesMenu`：最少/最多玩家、胜利波次、波间间隔、生成半径
  - `WaveSettingsMenu`：自定义波次增删改查、每波总怪物数可调、怪物组合权重编辑、`WaveDetailMenu` 子菜单
  - `MonsterSettingsMenu`：三页切换（自爆甲虫/钩爪猎手/飞行抛投者），分别编辑生命/速度/特有技能参数
  - `HammerSettingsMenu`：基础伤害、效果半径、三档伤害分级、余震/超时配置
  - `NodeSettingsMenu`：节点方块类型管理、基础生命、修复物品/数量、增益半径
- **`AbstractConfigMenu` 抽象基类**：统一 Inventory GUI 管理（点击路由、返回/关闭按钮、边框填充）
- **`NumericInputHandler`**：聊天栏数值输入（30 秒超时，支持整数和浮点数）
- **`ConfigPermission` 权限分级**：`config.admin`/`config.waves`/`config.monsters`/`config.items`/`config.nodes`/`config.rules`
- **`game-settings.yml` 配置文件**：所有游戏参数可持久化编辑，支持保存/撤销/重新加载
- **变更追踪机制**：`GameConfig.snapshot()` 深拷贝 + `ConfigManager.hasUnsavedChanges()` 比较，未保存时主菜单保存按钮高亮
- **`/hilltop reloadconfig`**：从 `game-settings.yml` 重新加载游戏设置

### Changed
- `WaveManager` 和 `HammerListener` 从 `GameConfig` 读取参数替代硬编码 `config.yml` 读取
- `GameManager` 新增 `ConfigManager` 引用并调整初始化顺序

## [1.0.2] - 2026-05-17

### Added
- **自定义 AI 怪物系统**：三种特殊怪物各自拥有独立 AI Goal
  - `ExplodeBeetleGoal`：洞穴蜘蛛高速冲向能量节点后自爆
  - `HookClawHunterGoal`：骷髅远程射击拖拽玩家并造成眩晕
  - `AirdropSpawnGoal`：幻翼飞行中向地面空投僵尸群
- **怪物配置文件**：`config.yml` 中 `monsters` 节点定义各怪物生命/速度/伤害/冷却等属性
- **波次组合配置**：`config.yml` 中 `waves` 节点定义每波怪物类型/数量/权重，支持自定义波次覆盖
- **`/hilltop help` 命令**：列出所有可用命令及简要说明
- **TAB 命令补全**：`/hilltop` 子命令完整补全（`start`/`stop`/`join`/`leave`/`status`/`hammer`/`deploy`/`config`/`lang`/`reloadconfig`）

### Fixed
- **修复重锤右键无法蓄力**：移除了 `isGameRunning()` 守卫条件，允许非游戏状态下激活锤子

## [1.0.1] - 2026-05-16

### Added
- **神圣重锤战斗系统**：右键空中激活蓄力（100 tick 超时），落地时根据下落高度造成三档伤害（5m/10m 阈值），AOE 半径可配置
- **铁砧砸地特效**：`BlockDisplay` 铁砧模型配合粒子效果（`BLOCK_CRACK` + `CLOUD`）
- **余震减速**：落地后地面持续减速效果（60 tick，2 级迟缓）
- **能量节点系统**：信标/附魔台/末影箱/重生锚作为节点，可被怪物攻击摧毁，玩家使用"世界树汁液"修复
- **节点增益 BUFF**：节点范围内玩家获得抗性提升 + 生命恢复

### Fixed
- 修复 `GameState` 枚举编译问题
- 修复 `PlayerData` 并发访问线程安全（`ConcurrentHashMap`）

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
