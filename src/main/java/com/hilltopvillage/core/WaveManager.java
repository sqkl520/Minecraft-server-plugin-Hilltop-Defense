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

        int playerCount = gameManager.getPlayers().size();
        int remaining = waveConfig.getTotalMobs() * playerCount;
        int mobsPerPlayerCap = cfg.getMobCapPerPlayer();
        remaining = Math.min(remaining, mobsPerPlayerCap * playerCount);
        remaining = Math.min(remaining, globalCap - activeMobs.size());

        // STORY-1.4 精确配比：每种怪刷 count × 人数 只，上限为 remaining
        // （三层裁剪：total-mobs 上限 → 人均上限 → 全局剩余容量）。
        // 全 0 回退：某波次所有 count 全 0/缺失时回退旧权重路径，避免已部署服主升级后刷怪归零。
        if (waveConfig.getTotalCount() > 0) {
            spawnExactMixWave(waveConfig, playerCount, remaining, onComplete);
            return;
        }

        int totalWeight = waveConfig.getTotalWeight();
        if (totalWeight <= 0) {
            // 兜底路径同样走分批：原来这里是单 tick 同步生成 5+2×波数 只（第 20 波 = 45 只），
            // 每只都要跑一次 getRandomSpawnLocation() + spawnEntity + addGoal，
            // 是整局里最尖的单 tick 分配峰值。改用与正常路径相同的 3 tick / 2 只节奏。
            spawnDefaultWave(waveNumber, onComplete);
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

    /**
     * 精确配比刷怪（STORY-1.4）：每种怪刷 {@code count × 人数} 只。
     *
     * <p>执行步骤：① 按 count×人数展开待刷队列；② 若超出 {@code maxTotal} 上限则按比例裁剪
     * （total-mobs 即"精确总数上限"语义）；③ 打散顺序避免同类扎堆；
     * ④ 按正常路径相同节奏（3 tick / 2 只）分批刷出。</p>
     *
     * <p>任务句柄同样写入 {@link #spawnTask}，{@link #cancelSpawnTask()}、
     * {@link #clearAllMobs()} 与 {@code isGameRunning()} 守卫全覆盖。</p>
     *
     * @param waveConfig 本波配置
     * @param playerCount 开波时的在场人数（快照，刷怪期间不再重读）
     * @param maxTotal 上限（total-mobs → 人均 → 全局剩余三层裁剪后的结果）
     * @param onComplete 全部生成完毕后回调（可为 null）
     */
    private void spawnExactMixWave(GameConfig.WaveConfig waveConfig, int playerCount,
                                   int maxTotal, Runnable onComplete) {
        // ① 展开：每种怪 count × 人数
        Map<String, Integer> exactCounts = new LinkedHashMap<>();
        int exactTotal = 0;
        for (Map.Entry<String, GameConfig.MobComposition> entry : waveConfig.getCompositions().entrySet()) {
            int n = Math.max(0, entry.getValue().getCount()) * playerCount;
            if (n <= 0) continue;
            exactCounts.put(entry.getKey(), n);
            exactTotal += n;
        }

        // ② 配比溢出上限时按比例裁剪（total-mobs 即"精确总数上限"语义）。
        // 最大余数法：先按 floor 配额分配，再把剩余名额按小数部分从大到小补齐，
        // 保证裁剪后的总数恰好等于上限（而非接近），且各类型份额误差 < 1。
        if (exactTotal > maxTotal && maxTotal > 0) {
            exactCounts = trimProportionally(exactCounts, exactTotal, maxTotal);
            exactTotal = maxTotal;
        } else if (maxTotal <= 0) {
            // 全局容量已满（remaining 层已裁到 0）：直接回调，不开刷怪任务
            if (onComplete != null) onComplete.run();
            return;
        }

        // ③ 打散：类型序列 shuffle，避免同类扎堆连续刷出
        List<String> spawnQueue = new ArrayList<>(exactTotal);
        for (Map.Entry<String, Integer> entry : exactCounts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                spawnQueue.add(entry.getKey());
            }
        }
        Collections.shuffle(spawnQueue, random);

        // ④ 分批：与正常路径相同的 3 tick / 2 只节奏
        final int total = spawnQueue.size();
        final int[] spawned = {0};
        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 与正常路径同一守卫：游戏已结束/已停止时立刻自我终止
                if (!gameManager.isGameRunning()) {
                    cancelSpawnTask();
                    return;
                }

                int batchCount = 0;
                while (batchCount < SPAWN_BATCH_SIZE && spawned[0] < total) {
                    String selectedType = spawnQueue.get(spawned[0]);
                    Location spawnLoc = getRandomSpawnLocation();
                    if (spawnLoc != null) {
                        spawnCustomMob(selectedType, spawnLoc);
                    }
                    spawned[0]++;
                    batchCount++;
                }

                if (spawned[0] >= total) {
                    // 先置空句柄，再回调（回调内若再次 spawnWave 会写入新句柄），最后取消自身
                    spawnTask = null;
                    if (onComplete != null) onComplete.run();
                    cancel();
                }
            }
        }.runTaskTimer(gameManager.getPlugin(), 0L, 3L);
    }

    /**
     * 最大余数法按比例裁剪：各类型配额 = {@code count × limit / total} 向下取整，
     * 剩余名额按小数部分从大到小补齐，保证结果总数恰好等于 {@code limit}。
     */
    private Map<String, Integer> trimProportionally(Map<String, Integer> counts, int total, int limit) {
        Map<String, Integer> trimmed = new LinkedHashMap<>();
        Map<String, Double> remainders = new HashMap<>();
        int assigned = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            double exact = (double) entry.getValue() * limit / total;
            int floor = (int) Math.floor(exact);
            trimmed.put(entry.getKey(), floor);
            remainders.put(entry.getKey(), exact - floor);
            assigned += floor;
        }
        // 剩余名额按小数部分从大到小补（并列时按 LinkedHashMap 插入顺序，行为确定）
        List<String> byRemainder = new ArrayList<>(counts.keySet());
        byRemainder.sort((a, b) -> Double.compare(remainders.get(b), remainders.get(a)));
        int rest = limit - assigned;
        for (int i = 0; i < rest && i < byRemainder.size(); i++) {
            String key = byRemainder.get(i);
            trimmed.put(key, trimmed.get(key) + 1);
        }
        return trimmed;
    }

    /**
     * 兜底刷怪：{@code compositions} 被清空 / 权重合计为 0 时使用。
     *
     * <p>与 {@link #spawnWave(int, Runnable)} 采用完全相同的节奏（3 tick 一批、一批 2 只），
     * 单 tick 实体生成量从「5+2×波数」降到 2。任务句柄同样写入 {@link #spawnTask}，
     * 因此 {@link #cancelSpawnTask()}、{@link #clearAllMobs()} 与游戏结束的守卫都能覆盖到它
     * —— 这一点很关键：兜底路径原来是同步的，不存在「任务还活着但游戏已结束」的窗口，
     * 改成异步后必须补上 {@code isGameRunning()} 守卫，否则失败结算后仍会继续刷怪。</p>
     *
     * @param waveNumber 波次号（决定总只数）
     * @param onComplete 全部生成完毕后回调（可为 null）
     */
    private void spawnDefaultWave(int waveNumber, Runnable onComplete) {
        final int count = DEFAULT_WAVE_BASE_COUNT + waveNumber * DEFAULT_WAVE_COUNT_PER_WAVE;
        final int[] spawned = {0};

        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 与正常路径同一守卫：游戏已结束/已停止时立刻自我终止
                if (!gameManager.isGameRunning()) {
                    cancelSpawnTask();
                    return;
                }

                World world = gameManager.getGameWorld();
                int batchCount = 0;
                while (batchCount < SPAWN_BATCH_SIZE && spawned[0] < count) {
                    Location loc = getRandomSpawnLocation();
                    if (loc != null && world != null) {
                        Entity entity = world.spawnEntity(loc, EntityType.ZOMBIE);
                        if (entity instanceof Mob mob) {
                            prepareWaveMob(mob);
                        }
                    }
                    spawned[0]++;
                    batchCount++;
                }

                if (spawned[0] >= count) {
                    // 先置空句柄，再回调（回调内若再次 spawnWave 会写入新句柄），最后取消自身
                    spawnTask = null;
                    if (onComplete != null) onComplete.run();
                    cancel();
                }
            }
        }.runTaskTimer(gameManager.getPlugin(), 0L, 3L);
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
        // STORY-1.2：怪物刷怪走独立的 mob-spawn-points；旧存档（该键为空）回退到玩家出生点，
        // 仅当两套点都为空时才返回 null（调用方刷怪任务会自我终止，见 isGameRunning 守卫）。
        List<Location> fixedPoints = gameManager.getMobSpawnPointsView();
        if (fixedPoints.isEmpty()) {
            gameManager.getPlugin().getLogger().severe("No mob spawn points configured! "
                    + "Deploy them with Step 3 of /hilltop setup or /hilltop deploy addmobspawn.");
            return null;
        }

        // 只读视图而非 getMobSpawnPoints()：后者每次都 new ArrayList<>(...)，
        // 一波 80 只怪就要拷 80 次小数组。取出的元素下面立刻 clone() 再偏移，
        // 不会改到 GameManager 内部的 Location 本体（视图的元素安全约束见该方法 Javadoc）。
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
