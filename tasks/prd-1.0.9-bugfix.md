# PRD: Hilltop Village 1.0.9 — Bug修复 + 服主快速部署

## Introduction

修复 1.0.8 中配置读取不一致、硬编码中文消息、文档旧名称引用三个 Bug，新增 `/hilltop setup` 服主部署向导。  
本版本为 P0 紧急修复，不涉及新功能，纯质量改进。  
部署向导参考 BedWars1058 `/bw setup` 的分步引导体验 + 每步自动保存策略。

## Goals

- 统一 NodeSystem / ExplodeBeetleGoal 的配置读取入口为 `GameConfig`，确保配置 GUI 修改全部生效
- 审查 HookClawHunterGoal / AirdropSpawnGoal 是否存在同类问题
- 清除所有硬编码中文消息，100% 走 i18n
- 修复 commands.md 中 `HilltopDefense` → `HilltopVillage`
- 新增 `/hilltop setup` 聊天栏 + GUI 混合引导部署向导，每步自动保存

---

## User Stories

### US-001: NodeSystem 配置读取统一走 GameConfig

**Description:** As a server admin, I want node parameters changed via `/hilltop config` GUI to take effect immediately without restarting the server.

**Acceptance Criteria:**
- [ ] `NodeSystem.loadConfig()` 中所有配置字段改为从 `gameManager.getConfigManager().getActiveConfig()` 读取
- [ ] `nodeBlockTypes` → `GameConfig.getNodeBlockTypes()`
- [ ] `baseHealth` → `GameConfig.getNodeBaseHealth()`
- [ ] `buffRadius` → `GameConfig.getNodeBuffRadius()`
- [ ] `buffEffects` → `GameConfig.getNodeBuffEffects()`
- [ ] 编译通过（`mvn clean compile`）

---

### US-002: ExplodeBeetleGoal 配置读取统一走 MonsterConfig

**Description:** As a server admin, I want explode beetle explosion damage changed via GUI to take effect immediately.

**Acceptance Criteria:**
- [ ] `ExplodeBeetleGoal.explode()` 中爆炸伤害改为从 `GameConfig.MonsterConfig` 读取
- [ ] `plugin.getConfig().getDouble("monsters.explode-beetle.explosion-power")` → `cfg.getMonsters().get("explode-beetle").getExplodeDamageMultiplier()`
- [ ] 编译通过（`mvn clean compile`）

---

### US-003: 审查 HookClawHunterGoal + AirdropSpawnGoal 配置读取路径

**Description:** As a developer, I need to verify whether HookClawHunterGoal and AirdropSpawnGoal also read directly from `config.yml`, so we know if they need fixing in a future version.

**Acceptance Criteria:**
- [ ] 逐行审查 `HookClawHunterGoal.java` 中所有 `plugin.getConfig()` 调用
- [ ] 逐行审查 `AirdropSpawnGoal.java` 中所有 `plugin.getConfig()` 调用
- [ ] 记录发现（如有）到代码注释或本 PRD 的 Open Questions
- [ ] 不做修改，纯审查
- [ ] 审查结论写入 US-003 完成报告

---

### US-004: 修复硬编码中文消息，全部走 i18n

**Description:** As an international player, I want all plugin messages to display in my chosen language.

**Acceptance Criteria:**
- [ ] `HilltopVillagePlugin.java:225` 烈焰蛋消息改为 `languageManager.getFor(player, "received-fireball")`
- [ ] `HilltopVillagePlugin.java` 部署菜单方法（`sendDeployMenu`）中 8 个命令描述改为 `languageManager.getFor(admin, "deploy.cmd-xxx")`
- [ ] 在 `messages.yml` 的 `en:` 和 `zh:` 区块中添加以下新 key：
  - `received-fireball`
  - `deploy.cmd-setcore`, `deploy.cmd-addspawn`, `deploy.cmd-listspawns`
  - `deploy.cmd-delspawn`, `deploy.cmd-showparticles`, `deploy.cmd-clearspawns`
  - `deploy.cmd-confirmclear`, `deploy.cmd-reloadnodes`
- [ ] 编译通过（`mvn clean compile`）

---

### US-005: 修复 commands.md 文档中旧名引用

**Description:** As a server admin, I want the auto-generated command documentation to reference the correct plugin name `HilltopVillage` instead of the old name `HilltopDefense`.

**Acceptance Criteria:**
- [ ] `HilltopVillagePlugin.java:796` 行：`plugins/HilltopDefense/messages.yml` → `plugins/HilltopVillage/messages.yml`
- [ ] 全文搜索 `generateCommandsDocument()` 方法，确认没有其他 `HilltopDefense` 引用
- [ ] 编译通过（`mvn clean compile`）

---

### US-006: 新增服主快速部署向导 `/hilltop setup`

**Description:** As a server admin, I want a single guided command `/hilltop setup` that walks me through 6 steps to fully deploy the game, instead of memorizing 8+ scattered subcommands. Each step auto-saves progress so I can resume if interrupted.

**Inspiration:** BedWars1058 `/bw setup` — step-by-step guidance with visual feedback, auto-save after each step.

**Acceptance Criteria:**
- [ ] 新建 `command/SetupWizard.java`（或 `admin/SetupWizard.java`），实现 6 步引导
- [ ] 向导步骤（混合模式：聊天栏主导 + 关键步骤弹出 GUI）：
  - **Step 1/6 确认世界** — 聊天栏显示当前世界名，点击确认或输入世界名切换
  - **Step 2/6 设置核心** — 聊天栏提示站在核心位置 → 执行后粒子特效反馈（`VILLAGER_HAPPY` + 音效）
  - **Step 3/6 添加生成点** — 聊天栏提示站在生成点，点击"添加"按钮重复添加 → 弹出简易 GUI 显示已添加的生成点列表，可删除/预览粒子/完成
  - **Step 4/6 扫描节点** — 聊天栏显示扫描进度，完成后显示找到的节点数量
  - **Step 5/6 配置预览** — **弹出 GUI** 显示：核心坐标、生成点数量、节点数量、当前波次设置总览
  - **Step 6/6 完成** — 提示 `/hilltop start` 即可开始，聊天栏发送可点击的快捷命令
- [ ] 每步完成后**自动保存**当前进度（复用 `AdminDeployManager` 方法）
- [ ] 进度保存在内存中（`ConcurrentHashMap<UUID, SetupSession>`），掉线/退出自动清理
- [ ] 玩家可随时输入 `/hilltop setup` 查看当前进度或继续
- [ ] 复用 `DeployMenuSender` 的可点击消息方法（如 US-004 先完成后复用，否则直接调用）
- [ ] 在 `HilltopVillagePlugin.onCommand()` 中添加 `"setup"` case
- [ ] 在 Tab 补全中添加 `"setup"` 选项，无需权限（所有管理员可执行）
- [ ] 在 `messages.yml` 的 `en:` 和 `zh:` 区块中添加所有向导消息（`setup.*` 命名空间，约 15-20 个 key）
- [ ] 编译通过（`mvn clean compile`）

**Step 3 GUI 设计（生成点管理）：**
```
┌──────────────────────────┐
│   生成点管理 — 已添加: 3   │
├──────────────────────────┤
│  #1  [世界, X:123 Y:64 Z:456] [🗑删除] [✨粒子] │
│  #2  [世界, X:130 Y:64 Z:460] [🗑删除] [✨粒子] │
│  #3  [世界, X:140 Y:64 Z:450] [🗑删除] [✨粒子] │
├──────────────────────────┤
│ [➕ 添加当前位置]  [✅ 完成]  │
└──────────────────────────┘
```

**Step 5 GUI 设计（配置预览）：**
```
┌──────────────────────────┐
│    部署配置预览            │
├──────────────────────────┤
│  游戏世界: world           │
│  核心坐标: 0.0, 100.0, 0.0│
│  生成点数量: 5             │
│  能量节点: 12 个           │
│  最少玩家: 2  最多玩家: 6  │
│  波次总数: 20             │
│  大厅等待: 60 秒           │
├──────────────────────────┤
│  [✅ 确认保存]  [🔄 重新设置] │
└──────────────────────────┘
```

---

## Functional Requirements

- FR-1: NodeSystem 不再直接读取 `config.yml`，全部通过 `GameConfig` 获取
- FR-2: ExplodeBeetleGoal 不再直接读取 `config.yml`，全部通过 `MonsterConfig` 获取
- FR-3: HookClawHunterGoal 和 AirdropSpawnGoal 完成配置读取路径审查，不做修改
- FR-4: 所有用户可见的消息文本必须通过 `LanguageManager` 解析，支持中英双语
- FR-5: 自动生成的 `commands.md` 中不再出现旧名称 `HilltopDefense`
- FR-6: `/hilltop setup` 提供 6 步引导部署流程，每步自动保存
- FR-7: Step 3（生成点管理）和 Step 5（配置预览）使用 GUI 界面

## Non-Goals

- 不新增怪物类型或玩法机制
- 不重构现有代码结构（留给 1.1.0）
- 不添加单元测试（留给 1.2.0）
- 不修改 GUI 配置菜单的代码逻辑
- 不修改 HookClawHunterGoal / AirdropSpawnGoal（纯审查）

## Technical Considerations

- `SetupWizard` 使用聊天栏可点击消息 + Bukkit Inventory GUI 混合交互
- `SetupSession` 记录：当前步骤、核心坐标、生成点列表、节点数量
- 自动保存复用 `AdminDeployManager` 和 `ConfigManager.saveToFile()`
- Step 3 的 GUI 需要注册临时监听器（参考 `AbstractConfigMenu` 模式，关闭时 `HandlerList.unregisterAll(this)`）
- US-004 与 US-006 有消息 key 交叉依赖：部署向导新增的 i18n key 需与部署菜单修复同步考虑
- 所有新消息文本遵循 `messages.yml` 的 `&` 颜色代码和 `{0}` 占位符格式

## Success Metrics

- 服主从安装插件到游戏就绪，输入命令数从 5+ 个降至 1 个（`/hilltop setup`）
- 配置 GUI 修改节点/怪物参数后，`/hilltop reloadconfig` 即可生效
- 所有玩家可见消息支持 `/hilltop lang en/zh` 切换
- `commands.md` 中零处 `HilltopDefense` 引用

## Open Questions

- HookClawHunterGoal / AirdropSpawnGoal 审查结果（US-003 完成后填入）
- 部署向导是否需要支持"重新设置"已有配置（覆盖核心/生成点）？
- Step 3 生成点管理 GUI 是否需要显示每个生成点的当前粒子类型？
