# 架构报告 US-FIREBALL-HITBOX

### 判定：PASS

| 维度 | 结果 | 说明 |
|------|------|------|
| 分层正确性 | PASS | FireballListener.java 位于 `mechanics/` 包，符合事件监听器分层规范。 |
| 单一职责 | PASS（警告） | 类职责集中于烈焰蛋系统，功能内聚。文件 396 行（超 300 行阈值，属警告级别，不 FAIL）。 |
| DI 注入完整性 | PASS | 构造器正确注入 GameManager，通过其链式获取 plugin/lang。import 语句完整，**之前 FAIL 的冗余 `GameState` import 已删除**。 |
| 事件注册 | PASS | `onEnable()` 第 56 行 `registerEvents(fireballListener, this)` 正确注册。三个事件处理器使用不同条件区分，无冲突。无新增命令需改 plugin.yml。 |

### 修正验证

| 前次 FAIL 问题 | 状态 | 说明 |
|----------------|------|------|
| FireballListener.java 冗余 import `com.hilltopvillage.core.GameState` | 已修复 | 文件内已无任何 `GameState` 引用或 import。全文 grepping 确认零匹配。 |

### 各维度详情

**1. 分层正确性 — PASS**

FireballListener 位于 `mechanics/`：事件监听 + 爆炸逻辑正确划分至 mechanics 层。triggerExplosion 作为私有方法留在该类中，无需跨层暴露。

**2. 单一职责 — 警告（不 FAIL）**

FireballListener 包含以下职责集合，但全部围绕烈焰蛋这一功能域：
- 物品创建/识别 (`createFireball`, `isFireball`)
- 发射处理 (`onRightClick`, `onDispense`)
- 飞行轨迹 (`startTrailTask`)
- 爆炸逻辑 (`triggerExplosion`)
- 击退公式 (`applyBwKnockback`)
- 冷却管理 (`ensureCooldownTickerRunning`, `cleanupPlayer`)

396 行超 300 行阈值，但内聚性高，为可接受的设计选择。

**3. DI 注入完整性 — PASS**

- 构造参数 `FireballListener(GameManager gameManager)`，`plugin` 和 `lang` 从 `gameManager` 派生。
- `onEnable()` 第 48 行 `new FireballListener(gameManager)`，第 56 行注册。
- 第 145 行 `fireballListener.cleanupPlayer(player)` 正确管理玩家退出清理。
- 全文 import 检查：13 个 import 全部在代码中使用，零冗余、零遗漏。

**4. 事件注册 — PASS**

| 事件 | 处理器 | 优先级 | 冲突分析 |
|------|--------|--------|----------|
| `PlayerInteractEvent` | `onRightClick` | NORMAL | 独立处理 |
| `BlockDispenseEvent` | `onDispense` | HIGHEST | 前置 checked `isCancelled()` |
| `ProjectileHitEvent` | `onProjectileHit` | NORMAL | 与主类 HIGH 的钩爪 handler 通过 metadata/customName 互斥区分 |

plugin.yml 无需变更。
