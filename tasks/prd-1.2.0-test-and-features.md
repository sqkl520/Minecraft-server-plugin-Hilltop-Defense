# PRD: Hilltop Village 1.2.0 — 测试体系 + 新功能

## Introduction

建立单元测试体系（覆盖核心逻辑 30%+），新增 2 种怪物类型（治疗甲虫、冰霜射手），实现波次事件系统和玩家积分排行榜。  
本版本为 P2 — 在 Bug 修复和重构完成后，开始补充测试和扩展玩法。

## Goals

- 添加 6 个测试类，约 33 个用例，核心逻辑覆盖率 30%+
- 新增 2 种怪物 AI：治疗甲虫（跟随治疗）、冰霜射手（远程减速）
- 新增波次事件系统：Boss 波次、双倍怪物波次、精英波次、奖励波次
- 新增玩家积分系统：击杀分 + 节点修复分 + 伤害分，游戏结束显示排行榜

## User Stories

---

### US-001: 添加 Maven 测试依赖 + 测试目录结构

**Description:** 作为开发者，我需要 JUnit 5 + Mockito 测试框架搭建好，为后续测试类提供基础。

**Acceptance Criteria:**
- [ ] 在 `pom.xml` 中添加 `junit-jupiter` (5.10.0) 和 `mockito-core` (5.5.0) 依赖，scope=test
- [ ] 创建 `src/test/java/com/hilltopvillage/` 目录结构
- [ ] 编译通过，依赖下载成功（`mvn clean compile`）

---

### US-002: GameConfigTest + ConfigRoundTripTest — 配置层测试

**Description:** 作为开发者，我需要验证 GameConfig 的 snapshot 深拷贝正确性和配置读写往返一致性。

**Acceptance Criteria:**
- [ ] `GameConfigTest.java` — 8 个用例：
  - snapshot() 修改返回对象不影响原对象
  - snapshot() 包含所有 MonsterConfig 字段
  - MonsterConfig 默认值验证（至少 5 个字段）
  - MonsterConfig setter/getter 成对验证
  - getNodeBlockTypes() 返回副本（修改不影响内部状态）
  - getHammerTiers() 返回 3 档配置
  - 测试通过（`mvn test -Dtest=GameConfigTest`）
- [ ] `ConfigRoundTripTest.java` — 4 个用例：
  - saveToFile → loadFromFile 往返后配置一致
  - 修改单字段后 hasUnsavedChanges() 返回 true
  - save 后 hasUnsavedChanges() 返回 false
  - 测试通过（`mvn test -Dtest=ConfigRoundTripTest`）

---

### US-003: WaveManagerTest + HammerTierTest + FireballKnockbackTest — 核心逻辑测试

**Description:** 作为开发者，我需要验证波次加权随机、重锤档位判定、烈焰蛋击退算法的正确性。

**Acceptance Criteria:**
- [ ] `WaveManagerTest.java` — 5 个用例：
  - weightSelect 单选（只有 1 个选项时必定选中它）
  - weightSelect 等权（2 个等权选项，1000 次采样，比例偏差 < 10%）
  - weightSelect 不等权（高权重选项选中概率显著高于低权重）
  - weightSelect 空列表返回 null
  - 测试通过（`mvn test -Dtest=WaveManagerTest`）
- [ ] `HammerTierTest.java` — 6 个用例：
  - fallDistance=0 → tier 0
  - fallDistance=3（刚好到 tier1 阈值）→ tier 1
  - fallDistance=5（中间值）→ tier 1
  - fallDistance=7（刚好到 tier2 阈值）→ tier 2
  - fallDistance=12 → tier 2
  - 边界值：负数 fallDistance → tier 0
  - 测试通过（`mvn test -Dtest=HammerTierTest`）
- [ ] `FireballKnockbackTest.java` — 5 个用例：
  - 零向量（贴脸爆炸 center=entity）→ 安全处理不崩溃
  - 水平爆炸（爆炸在实体正东 3 格）→ 向西击退
  - 脚底爆炸（explosion.y < entity.y）→ 向上击退
  - 头顶爆炸（explosion.y > entity.y）→ 向下+水平击退
  - 距离 < 0.01 → 默认向上击退
  - 测试通过（`mvn test -Dtest=FireballKnockbackTest`）

---

### US-004: NodeSystemTest — 节点系统测试

**Description:** 作为开发者，我需要验证节点修复和损坏逻辑的正确性。

**Acceptance Criteria:**
- [ ] `NodeSystemTest.java` — 5 个用例：
  - repairNode：满血节点不再回复
  - repairNode：残血节点修复后血量增加
  - damageNode：节点受到伤害血量减少
  - damageNode：血量 ≤ 0 时节点被摧毁
  - 物品消耗：修复节点消耗对应修复物品
  - 测试通过（`mvn test -Dtest=NodeSystemTest`）

---

### US-005: 新增治疗甲虫 (Healer Beetle) 怪物 AI

**Description:** 作为玩家，我希望遇到一种会治疗同伴的新怪物，增加战斗的策略深度。

**Acceptance Criteria:**
- [ ] 新建 `ai/HealerBeetleGoal.java`
- [ ] AI 行为：跟随最近的怪物（非自身），每 5 秒对 3 格范围内所有怪物治疗（恢复 20% 血量）
- [ ] 治疗时播放心形粒子效果（`Particle.HEART`）
- [ ] 本体不攻击玩家，优先跟随受伤的怪物
- [ ] 不治疗其他治疗甲虫（避免无限互奶）
- [ ] 在 `monsters-config.yml` 中添加 `healer-beetle` 配置段（血量/速度/治疗量/治疗间隔）
- [ ] 在 `WaveManager` 生成逻辑中注册 `HealerBeetleGoal`
- [ ] 在 `messages.yml` 中添加怪物名称的 i18n key
- [ ] 编译通过（`mvn clean compile`）

---

### US-006: 新增冰霜射手 (Frost Archer) 怪物 AI

**Description:** 作为玩家，我希望遇到远程攻击并减速的怪物，增加走位的重要性。

**Acceptance Criteria:**
- [ ] 新建 `ai/FrostArcherGoal.java`
- [ ] AI 行为：与玩家保持 8-15 格距离，每 2 秒射箭，命中附加 3 秒缓慢 III
- [ ] 使用 `Arrow` 实体，箭矢飞行速度略慢于骷髅（0.8x），命中时播放雪粒子（`Particle.SNOWFLAKE`）
- [ ] 如果有近战目标靠近（< 4 格），切换为近战模式（伤害减半）
- [ ] 在 `monsters-config.yml` 中添加 `frost-archer` 配置段（血量/速度/箭伤害/减速时长/减速等级/最小射击距离）
- [ ] 在 `WaveManager` 生成逻辑中注册 `FrostArcherGoal`
- [ ] 在 `messages.yml` 中添加怪物名称的 i18n key
- [ ] 编译通过（`mvn clean compile`）

---

### US-007: 新增波次事件系统

**Description:** 作为玩家，我希望不同波次有不同的特殊事件，让每局游戏都有变化和惊喜。

**Acceptance Criteria:**
- [ ] 新建 `core/WaveEventManager.java` 或扩展现有 `WaveManager`
- [ ] 实现 4 种事件类型：
  - **Boss 波次**（每 5 波触发）— 随机选一只怪物，血量 x5、体型 x2，名字带 `&c&l[Boss]`
  - **双倍怪物波次**（每 7 波触发）— 该波怪物数量翻倍
  - **精英波次**（每 10 波触发）— 所有怪物获得速度 II + 力量 I BUFF
  - **奖励波次**（每 3 波触发）— 清除所有剩余怪物，全体玩家回满血
- [ ] 每种事件触发时全服广播消息（`messages.yml` → `event.*` i18n key）
- [ ] 波次事件开关和参数在 `game-settings.yml` 中可配置
- [ ] 事件触发在 `WaveManager` 每波开始前检查并应用
- [ ] 编译通过（`mvn clean compile`）

---

### US-008: 新增玩家积分排行榜

**Description:** 作为玩家，我希望看到自己和其他玩家的击杀/修复/得分数据，游戏结束时有排行榜展示。

**Acceptance Criteria:**
- [ ] 扩展 `PlayerData`，新增字段：`score`, `nodesRepaired`, `damageDealt`
- [ ] 积分规则：
  - 击杀普通怪物 = 10 分
  - 击杀 Boss = 50 分
  - 修复 1 点节点血量 = 5 分
  - 每 10 点伤害 = 1 分
- [ ] 击杀/修复时通过 `GameManager.getPlayerData(p).addScore(n)` 累加
- [ ] 新建 `core/RankingDisplay.java`，游戏结束（VICTORY/DEFEAT）时发送排行榜
- [ ] 排行榜格式：Top 5 玩家，显示击杀数、修复数、总得分（降序）
- [ ] 在 `messages.yml` 中添加排行榜相关 i18n key（`ranking.*`）
- [ ] `PlayerData` 的 `addKill()` 等方法同步计算积分
- [ ] 编译通过（`mvn clean compile`）

---

## Functional Requirements

- FR-1: 项目具有 6 个测试类、约 33 个测试用例，覆盖配置/波次/重锤/烈焰蛋/节点核心逻辑
- FR-2: 测试可通过 `mvn test` 一键运行
- FR-3: 新增治疗甲虫 — 跟随并治疗附近怪物
- FR-4: 新增冰霜射手 — 远程射箭命中减速
- FR-5: 波次事件系统 — 4 种事件类型，可配置触发间隔
- FR-6: 玩家积分系统 — 击杀/修复/伤害得分，游戏结束显示 Top 5

## Non-Goals

- 不增加更多怪物类型（Split Slime / Shield Warrior / Summoner 留给后续版本）
- 不实现数据库持久化（积分仅在当次游戏中有效，留给 1.3.0）
- 不集成 PlaceholderAPI（留给 1.3.0）
- 不实现道具商店（留给 1.4.0）

## Technical Considerations

- 测试类使用 Mockito 模拟 Bukkit 对象（`MockBukkit` 或纯 mock）
- Mock 对象不实际初始化 Paper 服务器，测试可在 CI 中运行
- 波次事件系统建议用事件监听模式（EventBus），避免在 WaveManager 中硬编码
- 积分计算逻辑集中在 `PlayerData` 中，便于测试
- 新怪物 AI 遵循现有 `ai/` 包的 Goal API 模式

## Success Metrics

- `mvn test` 通过率 100%
- 测试覆盖核心逻辑 30%+ 代码行
- 新增 2 种怪物各具独立的 AI 行为
- 波次事件让每局游戏节奏有变化
