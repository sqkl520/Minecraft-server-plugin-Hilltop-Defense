# 开发计划 — 提高烈焰蛋的碰撞箱大小

## 项目信息
- PRD：内联需求（提高烈焰蛋碰撞箱大小，使其更容易命中附近实体）
- 主类：`com.hilltopvillage.HilltopVillagePlugin`
- 总 Story 数：1
- 创建时间：2026-06-19

## 技术方案分析

### 问题现状
烈焰蛋使用 `Snowball` 作为投射物实体。Snowball 的原版碰撞箱约为 0.25×0.25 格，飞行速度快（speed=1.5），在 PvE 场景中玩家很难精确命中移动中的怪物，体感"打不中"。

### 方案对比

| | 方案 A：更换投射物类型 | 方案 B：近炸检测（推荐） | 方案 C：调整 BoundingBox |
|---|---|---|---|
| **原理** | 将 Snowball 换成 LargeFireball / SmallFireball | trail task 中每 tick 扫描周围实体，进入半径即触发爆炸 | 通过 Paper API 调整实体碰撞箱 |
| **碰撞箱** | LargeFireball ≈ 1.0×1.0 | 等效任意半径（可配置） | 取决于 API 支持 |
| **副作用** | Fireball 自带爆炸、点火、重力，需大量屏蔽 | 无副作用，纯逻辑层改动 | Paper 版本绑定，兼容性差 |
| **可控性** | 受原版行为限制 | 完全可控 | 有限 |
| **实现复杂度** | 中等（需处理各种原版行为屏蔽） | 低（复用现有结构） | 高（API 不稳定） |
| **结论** | 不推荐 | **采用** | 不推荐 |

### 采用方案：B — 近炸检测 (Proximity Fuse)

在现有的飞行轨迹任务 (`startTrailTask`) 中增加近炸检测逻辑：每 tick 以投射物位置为中心，扫描周围配置半径内的实体，若发现合法目标则立即触发爆炸（复用现有爆炸逻辑）。

**优势：**
- 不影响现有 `ProjectileHitEvent` 处理器（方块命中仍然有效，作为保底）
- 碰撞半径可通过 `game-settings.yml` 配置，GUI 可调节
- 不更换投射物类型，无原版行为副作用
- 实现简洁，改动集中在 `FireballListener.java` 和配置层

---

## 任务清单

| # | Story ID | 标题 | 涉及文件 | 依赖 | 状态 |
|---|---------|------|---------|------|------|
| 1 | US-001 | 烈焰蛋近炸检测（Proximity Fuse） | GameConfig.java, ConfigManager.java, game-settings.yml, FireballListener.java, messages.yml, ItemSettingsMenu.java | 无 | ⏳ |

状态：⏳ 待办 | 🔄 进行中 | ✅ 完成 | ⚠️ 低质量通过
