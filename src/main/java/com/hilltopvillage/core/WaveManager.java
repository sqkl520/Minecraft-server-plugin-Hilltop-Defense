package com.hilltopvillage.core;

import com.hilltopvillage.ai.ExplodeBeetleGoal;
import com.hilltopvillage.ai.HookClawHunterGoal;
import com.hilltopvillage.ai.AirdropSpawnGoal;
import com.hilltopvillage.ai.NodeAttackGoal;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.shop.ShopManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WaveManager {

    private final GameManager gameManager;
    private final Set<LivingEntity> activeMobs;
    private final Random random;
    /** 当前波次的刷怪任务句柄；null 表示没有正在进行的刷怪任务 */
    private BukkitTask spawnTask;
    private static final int SPAWN_BATCH_SIZE = 2;
    private static final int DEFAULT_WAVE_BASE_COUNT = 5;
    private static final int DEFAULT_WAVE_COUNT_PER_WAVE = 2;

    public WaveManager(GameManager gameManager) {
        this.gameManager = gameManager;
        // 主线程独占写、AI 与死亡回调只读 contains：
        // CopyOnWriteArrayList 每次写入都复制整个底层数组，而刷怪（3 tick 一批、一批 2 只）
        // 与怪物死亡都是密集写，单波 80 只会产生 O(n²) 的引用拷贝。
        // ConcurrentHashMap.newKeySet 的 add/remove 是 O(1) 哈希操作，contains 同样是 O(1)。
        this.activeMobs = ConcurrentHashMap.newKeySet();
        this.random = new Random();
    }

    public void spawnWave(int waveNumber) {
        spawnWave(waveNumber, null);
    }

    public void spawnWave(int waveNumber, Runnable onComplete) {
        // 幂等：上一波的刷怪任务可能尚未结束（波次判定提前完成、上一局未收尾等），
        // 先取消旧任务再启动新任务，避免两批任务并存、用上一波的 waveConfig 继续刷怪。
        cancelSpawnTask();

        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        GameConfig.WaveConfig waveConfig = cfg.getWaveConfig(waveNumber);

        int globalCap = cfg.getGlobalMobCap();
        if (activeMobs.size() >= globalCap) {
            if (onComplete != null) onComplete.run();
            return;
        }

        int remaining = waveConfig.getTotalMobs() * gameManager.getPlayers().size();
        int mobsPerPlayerCap = cfg.getMobCapPerPlayer();
        remaining = Math.min(remaining, mobsPerPlayerCap * gameManager.getPlayers().size());
        remaining = Math.min(remaining, globalCap - activeMobs.size());

        int totalWeight = waveConfig.getTotalWeight();
        if (totalWeight <= 0) {
            spawnDefaultWave(waveNumber);
            if (onComplete != null) onComplete.run();
            return;
        }

        int[] remainingRef = {remaining};
        int[] spawnedCount = {0};

        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 游戏已结束/已停止时立刻自我终止：任务是在 3 tick 周期上异步推进的，
                // 若不守卫，失败结算 / 停止 / 关闭服务器之后仍会继续往世界写实体。
                if (!gameManager.isGameRunning()) {
                    cancelSpawnTask();
                    return;
                }

                int batchCount = 0;

                while (batchCount < SPAWN_BATCH_SIZE && spawnedCount[0] < remainingRef[0]) {
                    String selectedType = weightSelect(waveConfig, totalWeight);
                    if (selectedType != null) {
                        Location spawnLoc = getRandomSpawnLocation();
                        if (spawnLoc != null) {
                            spawnCustomMob(selectedType, spawnLoc);
                        }
                    }
                    spawnedCount[0]++;
                    batchCount++;
                }

                if (spawnedCount[0] >= remainingRef[0]) {
                    // 本波刷怪自然结束：先置空句柄，再回调（回调内若再次 spawnWave 会写入新句柄），
                    // 最后取消自身。外部取消（游戏结束）不会走到这里，因此不会误触发 onComplete。
                    spawnTask = null;
                    if (onComplete != null) onComplete.run();
                    cancel();
                }
            }
        }.runTaskTimer(gameManager.getPlugin(), 0L, 3L);
    }

    private String weightSelect(GameConfig.WaveConfig waveConfig, int totalWeight) {
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (Map.Entry<String, GameConfig.MobComposition> entry : waveConfig.getCompositions().entrySet()) {
            cumulative += entry.getValue().getWeight();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }
        return waveConfig.getCompositions().keySet().iterator().next();
    }

    private void spawnDefaultWave(int waveNumber) {
        World world = gameManager.getGameWorld();
        int count = DEFAULT_WAVE_BASE_COUNT + waveNumber * DEFAULT_WAVE_COUNT_PER_WAVE;

        for (int i = 0; i < count; i++) {
            Location loc = getRandomSpawnLocation();
            if (loc == null) continue;
            Entity entity = world.spawnEntity(loc, EntityType.ZOMBIE);
            if (entity instanceof Mob mob) {
                prepareWaveMob(mob);
            }
        }
    }

    /**
     * 普通波次怪物的统一初始化：登记到 activeMobs（波次完成判定依赖此集合）、
     * 注册节点攻击 AI（低优先级，有玩家时仍优先攻击玩家）。
     *
     * @param mob 波次怪物
     */
    private void prepareWaveMob(Mob mob) {
        markWaveMobPersistent(mob);
        activeMobs.add(mob);
        // 优先级 3：低于原版近战 AI（优先级 2），因此有玩家在附近时优先打玩家
        Bukkit.getMobGoals().addGoal(mob, 3, new NodeAttackGoal(mob, gameManager));
    }

    /**
     * 波次怪物不允许被原版「远离玩家自动消失 / 区块卸载」移除。
     *
     * <p>这类移除<b>不触发</b> {@code EntityDeathEvent}，怪物会永久残留在 {@link #activeMobs} 中，
     * 导致 {@link #isWaveCleared()} 恒为 false —— 波次永远无法判定完成、
     * {@code GameManager.waveCheckTask} 每 3 秒空转到本局结束，同时集合永久持有该无效实体的强引用。
     * 标记 persistent 后，怪物只能以死亡或 {@link #clearAllMobs()} 离场，判定假设才成立。</p>
     */
    private void markWaveMobPersistent(Mob mob) {
        mob.setRemoveWhenFarAway(false);
        mob.setPersistent(true);
    }

    private void spawnCustomMob(String type, Location location) {
        World world = gameManager.getGameWorld();

        switch (type.toLowerCase()) {
            case "explode-beetle":
                spawnExplodeBeetle(location);
                break;
            case "hook-claw-hunter":
                spawnHookClawHunter(location);
                break;
            case "flying-dropper":
                spawnFlyingDropper(location);
                break;
            default:
                try {
                    EntityType entityType = EntityType.valueOf(type.toUpperCase());
                    Entity entity = world.spawnEntity(location, entityType);
                    if (entity instanceof Mob mob) {
                        applyMonsterEquipment(mob, gameManager.getConfigManager().getActiveConfig()
                                .getMonsters().get(type.toLowerCase()));
                        prepareWaveMob(mob);
                    } else if (entity instanceof LivingEntity living) {
                        activeMobs.add(living);
                    }
                } catch (IllegalArgumentException e) {
                    gameManager.getPlugin().getLogger().warning("Unknown mob type: " + type);
                }
        }
    }

    /**
     * 根据 MonsterConfig 配置为怪物装备武器、副手物品和盔甲，并应用攻击伤害。
     * 同时应用 ItemsAdder 模型（如果配置了）和自定义模型数据。
     *
     * @param entity 目标实体
     * @param mc     怪物配置（含武器/盔甲/模型信息）
     */
    private void applyMonsterEquipment(LivingEntity entity, GameConfig.MonsterConfig mc) {
        if (mc == null) return;

        // 攻击伤害：对玩家与能量节点使用同一数值（对应「节点伤害按打到玩家的伤害算」）
        var attackAttr = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attackAttr != null && mc.getBaseDamage() > 0) {
            attackAttr.setBaseValue(mc.getBaseDamage());
        }

        EntityEquipment equip = entity.getEquipment();
        if (equip == null) return;

        // 主手武器
        Material mainHand = mc.getMainHandItem();
        if (mainHand != null && mainHand != Material.AIR) {
            equip.setItem(EquipmentSlot.HAND, new ItemStack(mainHand));
            equip.setDropChance(EquipmentSlot.HAND, 0.05f);
        }

        // 副手物品（如盾牌）
        Material offHand = mc.getOffHandItem();
        if (offHand != null && offHand != Material.AIR) {
            equip.setItem(EquipmentSlot.OFF_HAND, new ItemStack(offHand));
            equip.setDropChance(EquipmentSlot.OFF_HAND, 0.05f);
        }

        // 头盔
        Material helmet = mc.getHelmet();
        if (helmet != null && helmet != Material.AIR) {
            equip.setItem(EquipmentSlot.HEAD, new ItemStack(helmet));
            equip.setDropChance(EquipmentSlot.HEAD, 0.05f);
        }

        // 胸甲
        Material chest = mc.getChestplate();
        if (chest != null && chest != Material.AIR) {
            equip.setItem(EquipmentSlot.CHEST, new ItemStack(chest));
            equip.setDropChance(EquipmentSlot.CHEST, 0.05f);
        }

        // 护腿
        Material legs = mc.getLeggings();
        if (legs != null && legs != Material.AIR) {
            equip.setItem(EquipmentSlot.LEGS, new ItemStack(legs));
            equip.setDropChance(EquipmentSlot.LEGS, 0.05f);
        }

        // 靴子
        Material boots = mc.getBoots();
        if (boots != null && boots != Material.AIR) {
            equip.setItem(EquipmentSlot.FEET, new ItemStack(boots));
            equip.setDropChance(EquipmentSlot.FEET, 0.05f);
        }
    }

    private void spawnExplodeBeetle(Location location) {
        World world = gameManager.getGameWorld();
        GameConfig.MonsterConfig mc = gameManager.getConfigManager().getActiveConfig()
                .getMonsters().get("explode-beetle");

        EntityType entityType = mc != null && mc.getEntityType() != null
                ? mc.getEntityType() : EntityType.CAVE_SPIDER;
        Entity spawned;
        try {
            spawned = world.spawnEntity(location, entityType);
        } catch (Exception e) {
            // 类型不可生成时只跳过这一只怪物，不能让异常冒泡终止整个刷怪任务
            gameManager.getPlugin().getLogger().warning("无法生成 explode-beetle（实体类型 " + entityType
                    + "）：" + e.getMessage() + "，已跳过该怪物");
            return;
        }
        if (!(spawned instanceof Mob spider)) {
            gameManager.getPlugin().getLogger().warning("explode-beetle entity type " + entityType
                    + " is not a Mob, AI goals were not applied");
            if (spawned instanceof LivingEntity living) activeMobs.add(living);
            return;
        }
        markWaveMobPersistent(spider);
        double health = mc != null ? mc.getHealth() : 40.0;
        spider.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
        spider.setHealth(health);

        String name = (mc != null && mc.getDisplayName() != null && !mc.getDisplayName().isEmpty())
                ? mc.getDisplayName()
                : gameManager.getPlugin().getLanguageManager().get("mob.explode-beetle");
        spider.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
        spider.setCustomNameVisible(true);

        double speed = mc != null ? mc.getSpeed() : 0.35;
        spider.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed);

        applyMonsterEquipment(spider, mc);

        Bukkit.getMobGoals().removeAllGoals(spider);
        Bukkit.getMobGoals().addGoal(spider, 1, new ExplodeBeetleGoal(spider, gameManager));

        activeMobs.add(spider);
    }

    private void spawnHookClawHunter(Location location) {
        World world = gameManager.getGameWorld();
        GameConfig.MonsterConfig mc = gameManager.getConfigManager().getActiveConfig()
                .getMonsters().get("hook-claw-hunter");

        EntityType entityType = mc != null && mc.getEntityType() != null
                ? mc.getEntityType() : EntityType.SKELETON;
        Entity spawned;
        try {
            spawned = world.spawnEntity(location, entityType);
        } catch (Exception e) {
            // 类型不可生成时只跳过这一只怪物，不能让异常冒泡终止整个刷怪任务
            gameManager.getPlugin().getLogger().warning("无法生成 hook-claw-hunter（实体类型 " + entityType
                    + "）：" + e.getMessage() + "，已跳过该怪物");
            return;
        }
        if (!(spawned instanceof Mob skeleton)) {
            gameManager.getPlugin().getLogger().warning("hook-claw-hunter entity type " + entityType
                    + " is not a Mob, AI goals were not applied");
            if (spawned instanceof LivingEntity living) activeMobs.add(living);
            return;
        }
        markWaveMobPersistent(skeleton);
        double health = mc != null ? mc.getHealth() : 60.0;
        skeleton.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
        skeleton.setHealth(health);

        String name = (mc != null && mc.getDisplayName() != null && !mc.getDisplayName().isEmpty())
                ? mc.getDisplayName()
                : gameManager.getPlugin().getLanguageManager().get("mob.hook-claw-hunter");
        skeleton.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
        skeleton.setCustomNameVisible(true);

        double speed = mc != null ? mc.getSpeed() : 0.25;
        skeleton.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed);

        applyMonsterEquipment(skeleton, mc);

        Bukkit.getMobGoals().removeAllGoals(skeleton);
        Bukkit.getMobGoals().addGoal(skeleton, 1, new HookClawHunterGoal(skeleton, gameManager));

        activeMobs.add(skeleton);
    }

    private void spawnFlyingDropper(Location location) {
        World world = gameManager.getGameWorld();
        GameConfig.MonsterConfig mc = gameManager.getConfigManager().getActiveConfig()
                .getMonsters().get("flying-dropper");

        EntityType entityType = mc != null && mc.getEntityType() != null
                ? mc.getEntityType() : EntityType.PHANTOM;
        Entity spawned;
        try {
            spawned = world.spawnEntity(location, entityType);
        } catch (Exception e) {
            // 类型不可生成时只跳过这一只怪物，不能让异常冒泡终止整个刷怪任务
            gameManager.getPlugin().getLogger().warning("无法生成 flying-dropper（实体类型 " + entityType
                    + "）：" + e.getMessage() + "，已跳过该怪物");
            return;
        }
        if (!(spawned instanceof Mob phantom)) {
            gameManager.getPlugin().getLogger().warning("flying-dropper entity type " + entityType
                    + " is not a Mob, AI goals were not applied");
            if (spawned instanceof LivingEntity living) activeMobs.add(living);
            return;
        }
        markWaveMobPersistent(phantom);
        double health = mc != null ? mc.getHealth() : 80.0;
        phantom.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
        phantom.setHealth(health);

        String name = (mc != null && mc.getDisplayName() != null && !mc.getDisplayName().isEmpty())
                ? mc.getDisplayName()
                : gameManager.getPlugin().getLanguageManager().get("mob.airdrop-dropper");
        phantom.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
        phantom.setCustomNameVisible(true);

        double speed = mc != null ? mc.getSpeed() : 0.3;
        phantom.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed);

        applyMonsterEquipment(phantom, mc);

        Bukkit.getMobGoals().removeAllGoals(phantom);
        Bukkit.getMobGoals().addGoal(phantom, 1, new AirdropSpawnGoal(phantom, gameManager));

        activeMobs.add(phantom);
    }

    private Location getRandomSpawnLocation() {
        // 强制要求管理员设置生成点，不提供核心周围随机生成的fallback
        if (!gameManager.hasSpawnPoints()) {
            gameManager.getPlugin().getLogger().severe("No spawn points configured! Game cannot start without spawn points.");
            return null;
        }

        List<Location> fixedPoints = gameManager.getSpawnPoints();
        Location basePoint = fixedPoints.get(random.nextInt(fixedPoints.size())).clone();

        // 应用扩散半径（默认3.0格）
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        double spread = cfg.getSpawnRadiusSpread();  // 使用getter方法

        if (spread > 0) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = random.nextDouble() * spread;
            double offsetX = Math.cos(angle) * distance;
            double offsetZ = Math.sin(angle) * distance;

            basePoint.add(offsetX, 0, offsetZ);
        }

        return basePoint;
    }

    public void onMobDeath(LivingEntity mob) {
        activeMobs.remove(mob);
        dropCurrency(mob);
    }

    /**
     * 波次怪物死亡时按概率掉落商店货币（能量水晶）。
     * 仅游戏进行中（RUNNING / WAVE_INTERMISSION）掉落；ShopManager 未初始化时静默跳过。
     */
    private void dropCurrency(LivingEntity mob) {
        if (!gameManager.isGameRunning()) return;
        if (mob.getLocation().getWorld() == null) return;

        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        ShopManager shopManager = gameManager.getPlugin().getShopManager();
        if (shopManager == null || shopManager.getShopConfig() == null) return;

        if (random.nextDouble() < cfg.getCurrencyDropChance()) {
            mob.getLocation().getWorld().dropItemNaturally(
                    mob.getLocation(),
                    shopManager.createCurrencyItem(cfg.getCurrencyDropAmount()));
        }
    }

    /**
     * 取消并置空当前波次的刷怪任务（幂等）。
     *
     * <p>{@link BukkitTask#cancel()} 对已取消 / 已执行完的任务是空操作（只是把它从调度器摘下），
     * 重复调用不会抛异常；这里再配合判空与置空句柄，使
     * {@link #clearAllMobs()} 与 {@link #spawnWave(int, Runnable)} 多次调用时都不会重复取消同一个任务。</p>
     */
    private void cancelSpawnTask() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
    }

    public void clearAllMobs() {
        // 先停刷怪任务再清实体：否则清理期间任务仍会补刷出新怪（结算 / stopGame / 关闭服务器路径都会走到这里）
        cancelSpawnTask();

        for (LivingEntity mob : activeMobs) {
            if (mob != null && mob.isValid()) {
                mob.remove();
            }
        }
        activeMobs.clear();
    }

    public boolean isWaveCleared() {
        // 兜底：即使仍有实体因未覆盖的路径失效（区块卸载 / 被其它插件移除 / 掉出世界边界），
        // 每 3 秒的判定也会把它们剔除，避免「一只无效实体永久卡住整波」
        if (!activeMobs.isEmpty()) {
            activeMobs.removeIf(mob -> mob == null || !mob.isValid());
        }
        return activeMobs.isEmpty();
    }

    /**
     * 登记一只波次怪物。
     *
     * <p>对外只暴露只读视图（见 {@link #getActiveMobs()}），写入统一走本方法，
     * 避免外部持有可变内部集合、绕过集合选型约定。</p>
     */
    public void addActiveMob(LivingEntity mob) {
        if (mob != null) activeMobs.add(mob);
    }

    /** 只读视图：调用方只能查询，写入请用 {@link #addActiveMob(LivingEntity)} */
    public Set<LivingEntity> getActiveMobs() {
        return Collections.unmodifiableSet(activeMobs);
    }

    public int getRemainingMobCount() {
        return activeMobs.size();
    }
}
