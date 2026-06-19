# 实现指南 — 提高烈焰蛋的碰撞箱大小（近炸检测）

---

## US-001 — 烈焰蛋近炸检测 (Proximity Fuse)

### 目标
在烈焰弹飞行过程中，每 tick 扫描投射物周围实体，若进入可配置的近炸半径内则立即触发爆炸，使烈焰弹更容易命中目标。

### 涉及文件

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `src/main/java/com/hilltopvillage/config/GameConfig.java` | 修改 | 新增 `fireballProximityFuse` 字段 + getter/setter + 默认值 + snapshot |
| `src/main/java/com/hilltopvillage/config/ConfigManager.java` | 修改 | 新增 `fireball.proximity-fuse` 的 save/load |
| `src/main/resources/game-settings.yml` | 修改 | 新增 `proximity-fuse` 配置项（默认值） |
| `src/main/java/com/hilltopvillage/mechanics/FireballListener.java` | 修改 | 重构爆炸逻辑为独立方法；trail task 增加近炸检测 |
| `src/main/resources/messages.yml` | 修改 | 新增近炸半径的 GUI i18n key（en + zh） |
| `src/main/java/com/hilltopvillage/config/menu/ItemSettingsMenu.java` | 修改 | Fireball 页新增近炸半径滑块 |

### 实现要点

#### 第 1 步：GameConfig.java — 新增配置字段

在 `GameConfig.java` 的 fireball 配置区块（第 67-74 行附近），在现有 `fireballKnockback` 之后新增：

```java
private double fireballProximityFuse = 1.5;  // 近炸检测半径（格），0=禁用
```

添加 getter/setter（在 `getFireballKnockback`/`setFireballKnockback` 之后）：

```java
public double getFireballProximityFuse() { return fireballProximityFuse; }
public void setFireballProximityFuse(double v) { this.fireballProximityFuse = v; }
```

在 `snapshot()` 方法中（第 301 行附近，`fireballKnockback` 之后）添加：

```java
snap.fireballProximityFuse = this.fireballProximityFuse;
```

#### 第 2 步：ConfigManager.java — 新增 save/load

**save（第 127 行 `fireball.knockback` 之后）：**

```java
settingsYaml.set("fireball.proximity-fuse", activeConfig.getFireballProximityFuse());
```

**load（第 276 行 `fireball.knockback` 之后）：**

```java
if (settingsYaml.contains("fireball.proximity-fuse")) activeConfig.setFireballProximityFuse(settingsYaml.getDouble("fireball.proximity-fuse"));
```

#### 第 3 步：game-settings.yml — 新增默认值

在 `fireball` 区块中，`knockback: 5.0` 之后新增一行：

```yaml
  # 近炸检测半径（格，0=禁用，投射物附近进入此范围的实体会触发爆炸）
  proximity-fuse: 1.5
```

#### 第 4 步：FireballListener.java — 核心逻辑改动

这是最关键的一步，涉及两处改动：

**4a. 提取爆炸逻辑为独立方法**

当前 `onProjectileHit()` 方法（约 218-319 行）混合了"获取命中位置"和"执行爆炸效果"。将爆炸效果部分提取为一个独立方法 `triggerExplosion(Snowball projectile, Location impact, GameConfig cfg)`，接受投射物和命中位置作为参数。方法内容为原 `onProjectileHit` 中从"获取投掷者"开始到方法结束的全部逻辑（第 229-319 行）。

修改 `onProjectileHit` 使其调用 `triggerExplosion`：

```java
@EventHandler(priority = EventPriority.NORMAL)
public void onProjectileHit(ProjectileHitEvent event) {
    if (!(event.getEntity() instanceof Snowball)) return;
    Snowball projectile = (Snowball) event.getEntity();
    if (!projectile.hasMetadata("hilltop_fireball")) return;

    Location impact = projectile.getLocation().clone();
    GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
    triggerExplosion(projectile, impact, cfg);
}
```

**4b. 在 trail task 中增加近炸检测**

在 `startTrailTask()` 方法（第 185-209 行）的 `run()` 中，粒子生成之后、tick 递增之前，加入近炸检测：

```java
// 近炸检测：扫描周围实体，进入近炸半径则触发爆炸
double fuse = cfg.getFireballProximityFuse();
if (fuse > 0) {
    Location loc = projectile.getLocation();
    Collection<LivingEntity> nearby = loc.getWorld().getNearbyLivingEntities(
        loc, fuse, fuse, fuse,
        entity -> entity != null && entity.isValid()
            && !(entity instanceof Player)  // 不对玩家做近炸（保持手感）
            && !entity.isDead()
    );
    if (!nearby.isEmpty()) {
        // 获取投掷者信息（用于后续击退自己）
        if (projectile.getShooter() instanceof Player) {
            projectile.setMetadata("hilltop_fireball",
                new FixedMetadataValue(plugin,
                    ((Player) projectile.getShooter()).getUniqueId().toString()));
        }
        triggerExplosion(projectile, loc, cfg);
        projectile.remove();
        cancel();
        return;
    }
}
```

**注意事项：**
- 近炸检测放在粒子生成之后，确保在爆炸前一 tick 粒子仍然可见
- 需要提前 `cancel()` 并 `projectile.remove()` 停止 trail task
- metadata 可能已被消费（如果是从 `onProjectileHit` 进入的），需要在此处重新设置或改为在 `triggerExplosion` 内部保留 metadata 直到用完
- 建议调整 `triggerExplosion` 使其在消费 metadata 前先读取 shooter，并用局部变量传递

**关键点：metadata 生命周期**

当前 `onProjectileHit` 中第 238 行 `projectile.removeMetadata("hilltop_fireball", plugin)` 在爆炸逻辑中会被调用。在 `triggerExplosion` 方法中，需要确保 metadata 在近炸路径中也能正常读取投掷者信息。最佳做法：在 `triggerExplosion` 开头就读取 shooter/metadata，然后移除 metadata，后续用局部变量。

**4c. triggerExplosion 方法签名**

```java
/**
 * 触发烈焰蛋爆炸效果。由 ProjectileHitEvent 或近炸检测调用。
 */
private void triggerExplosion(Snowball projectile, Location impact, GameConfig cfg) {
    // 只处理一次（防止重复触发）
    if (!projectile.hasMetadata("hilltop_fireball")) return;

    World world = impact.getWorld();

    // 获取投掷者（优先 getShooter，metadata 作备用）
    UUID throwerUuid = null;
    if (projectile.getShooter() instanceof Player) {
        throwerUuid = ((Player) projectile.getShooter()).getUniqueId();
    }
    // 从 metadata 读取（兼容发射器或 shooter 为 null 的情况）
    List<MetadataValue> metas = projectile.getMetadata("hilltop_fireball");
    if (throwerUuid == null && !metas.isEmpty()) {
        try {
            String val = metas.get(0).asString();
            if (!"DISPENSER".equals(val)) {
                throwerUuid = UUID.fromString(val);
            }
        } catch (IllegalArgumentException ignored) {}
    }
    projectile.removeMetadata("hilltop_fireball", plugin);

    // === 以下为原 onProjectileHit 中 241-319 行全部爆炸效果代码 ===
    // （爆炸音效、粒子、范围伤害、击退等，完全不变）
    // ...
}
```

#### 第 5 步：messages.yml — 新增 i18n key

在 `en` 和 `zh` 区块的 `config.menu.items.fireball.` 段落中，`max-travel-desc` 之后新增：

**en:**
```yaml
  config.menu.items.fireball.proximity-fuse: "Proximity Fuse (blocks)"
  config.menu.items.fireball.proximity-fuse-desc: "Detect hit when monster enters this radius, 0=disable proximity"
```

**zh:**
```yaml
  config.menu.items.fireball.proximity-fuse: "近炸半径(格)"
  config.menu.items.fireball.proximity-fuse-desc: "怪物进入此范围即触发爆炸，0=禁用近炸"
```

#### 第 6 步：ItemSettingsMenu.java — GUI 新增滑块

在 `buildFireballPage()` 方法中（约第 122-153 行），在 `max-travel` 之后（slot 30 之后，slot 32 位置）新增近炸半径滑块：

```java
addDblItem(32, Material.STRING, lang.getFor(admin, "config.menu.items.fireball.proximity-fuse"), cfg.getFireballProximityFuse(),
        0, 5, 0.5, cfg::getFireballProximityFuse, cfg::setFireballProximityFuse,
        lang.getFor(admin, "config.menu.items.fireball.proximity-fuse-desc"));
```

注意：需要确认 slot 32 是否已被其他元素占用。当前 fireball 页面布局：
- Row 2 (slots 10-16): tab buttons
- Row 3 (slots 19-25): damage(19), radius(21), speed(23), cooldown(25)
- Row 4 (slots 28-34): knockback(28), max-travel(30), material-section(33), mat(34)

slot 32 可用（在 max-travel 右侧、material 左侧），不会冲突。

### 验收条件
- [ ] `fireball.proximity-fuse` 默认值为 1.5，在 `game-settings.yml` 中可手动修改
- [ ] 烈焰蛋飞行过程中，怪物进入近炸半径（默认 1.5 格）时立即触发爆炸
- [ ] 近炸检测不对玩家触发（玩家必须直接命中才爆炸）
- [ ] 方块直接命中仍然正常爆炸（`ProjectileHitEvent` 作为保底）
- [ ] 爆炸效果（音效、粒子、伤害、击退）与修改前完全一致
- [ ] 近炸半径设为 0 时，行为回退到原始（仅 `ProjectileHitEvent` 触发）
- [ ] GUI 配置中心（`/hilltop config` → 玩法物品 → 烈焰蛋）可调整近炸半径（0~5，步进 0.5）
- [ ] `mvn clean compile` 通过，无编译错误
- [ ] `game-settings.yml` 中 `proximity-fuse` 值的修改在 `/hilltop reloadconfig` 后生效

### 依赖
无（独立 Story）
