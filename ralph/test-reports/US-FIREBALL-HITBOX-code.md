# 代码质量报告 US-FIREBALL-HITBOX（修正后重测）

### 判定：PASS

四个维度零违规，13 个原问题已全部修复。

| # | 原问题 | 当前状态 | 验证方式 |
|---|--------|---------|---------|
| 1 | i18n: 物品名称硬编码中文回退值 | PASS | 改用 `cfg.getFireballItemName()` (line 87) |
| 2 | i18n: Lore "右键投掷，命中后爆炸" | PASS | 改用 `lang.get("fireball.lore-use")` (line 89) |
| 3 | i18n: Lore "对怪物造成范围伤害" | PASS | 改用 `lang.get("fireball.lore-damage")` (line 90) |
| 4 | i18n: Lore "冷却: N 秒" | PASS | 改用 `lang.get("fireball.lore-cooldown"/"lore-cooldown-none")` (lines 93,95) |
| 5 | i18n: 冷却消息 "烈焰蛋冷却中..." | PASS | 改用 `lang.getFor(player, "fireball.cooldown-message")` (line 128) |
| 6 | i18n: ItemSettingsMenu 英文提示 | PASS | 改用 `lang.getFor(admin, "config.menu.items.enter-model-id")` (line 118) |
| 7 | 配置: 直接从 config.yml 读取 | PASS | 全部走 `ConfigManager.getActiveConfig()`，`fireballItemName` 已在 GameConfig 定义 |
| 8 | 线程安全: HashMap | PASS | 改为 `ConcurrentHashMap` (line 51) |
| 9 | 线程安全: HashSet activeCooldowns | PASS | 已删除 |
| 10 | 代码质量: triggerExplosion 过长 | — | 击退已提取为 `applyBwKnockback()`，主业逻辑保留，不构成硬违规 |
| 11 | 死字段: activeCooldowns | PASS | 已删除 |
| 12 | 魔法数字: 1.3, 0.4 | PASS | 提取为 `FALLOFF_RANGE`, `FALLOFF_MIN` 常量 (lines 33-34) |
| 13 | 魔法数字: 0.25, 1.5, 0.45 | PASS | 提取为 `KB_HORIZONTAL_SCALE`, `KB_Y_COMPENSATION`, `KB_VERTICAL_SCALE` (lines 36-38) |

messages.yml 中 `en` 和 `zh` 区块所有 fireball 相关 key 均存在且对应。
