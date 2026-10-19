package com.hilltopvillage.core;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.ai.ExplodeBeetleGoal;
import com.hilltopvillage.ai.HookClawHunterGoal;
import com.hilltopvillage.ai.AirdropSpawnGoal;
import com.hilltopvillage.ai.DropperAttackGoal;
import com.hilltopvillage.ai.NodeAttackGoal;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.shop.ShopManager;
import com.hilltopvillage.util.SafeSpawnUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

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
    /**
     * 未完成的空投批次任务（P0-D）。
     *
     * <p>抛投者的空投是独立的分批任务，不走 {@link #spawnTask}；若不单独追踪，
     * {@code haltGame()}（结算期间刻意不改游戏状态）之后它们不会被任何守卫停下，
     * 会在倒计时期间继续往世界里写实体。{@link #clearAllMobs()} 统一取消本集合。</p>
     */
    private final Set<BukkitTask> airdropTasks;
    /** PDC 标记：空投小怪（dropCurrency 据此跳过，避免挂机刷钱；见 {@link #markAirdropMob}） */
    private final NamespacedKey airdropKey;
    /**
     * PDC 标记：怪种 id（monsters 配置键，如 {@code explode-beetle}）。
     * 按怪种掉落（E2）据此识别 mob 种类查掉落表——不按 EntityType 反查
     * （entity-type 可被管理员配置覆盖，且原版同类怪会混入）。
     * 见 {@link #markMonsterId} / {@link #resolveMonsterConfig}。
     */
    private final NamespacedKey monsterIdKey;
    /**
     * 「两套刷怪点均空 → 以核心为中心环形刷怪」（M-14 回退路径）的提示是否已记过。
     * 每波重置一次（{@link #spawnWave(int, Runnable)} 入口），使误配置可见但不会
     * 按 mob 数量刷屏（旧实现在该状态按 mob 打 severe，一波 80 只 = 80 行）。
     */
    private boolean ringSpawnWarningLogged;
    /** 波次怪统一 PDC 标记键名（P2-12）：崩溃/强杀重启后启动清扫按此识别残留实体 */
    public static final String WAVE_MOB_KEY_NAME = "wave-mob";
    /** PDC 标记：全部波次怪（普通/三特殊/空投小怪/抛投者，见 {@link #markWaveMobPersistent}） */
    private final NamespacedKey waveMobKey;
    private static final int SPAWN_BATCH_SIZE = 2;
    /** 安全落点重试次数（fix4 G1b）：候选点扫到虚空/无地面时重抽坐标，全部失败则本只不刷或回退固定点 */
    private static final int SPAWN_SAFE_RETRY_COUNT = 8;
    private static final int DEFAULT_WAVE_BASE_COUNT = 5;
    private static final int DEFAULT_WAVE_COUNT_PER_WAVE = 2;
    /** 空投分批节奏：每 10 tick 落 1 只（与历史手感一致） */
    private static final long AIRDROP_SPAWN_PERIOD_TICKS = 10L;

    public WaveManager(GameManager gameManager) {
        this.gameManager = gameManager;
        // 主线程独占写、AI 与死亡回调只读 contains：
        // CopyOnWriteArrayList 每次写入都复制整个底层数组，而刷怪（3 tick 一批、一批 2 只）
        // 与怪物死亡都是密集写，单波 80 只会产生 O(n²) 的引用拷贝。
        // ConcurrentHashMap.newKeySet 的 add/remove 是 O(1) 哈希操作，contains 同样是 O(1)。
        this.activeMobs = ConcurrentHashMap.newKeySet();
        this.airdropTasks = ConcurrentHashMap.newKeySet();
        this.airdropKey = new NamespacedKey(gameManager.getPlugin(), "airdrop-mob");
        this.monsterIdKey = new NamespacedKey(gameManager.getPlugin(), "monster-id");
        this.waveMobKey = new NamespacedKey(gameManager.getPlugin(), WAVE_MOB_KEY_NAME);
        this.random = new Random();
    }

    public void spawnWave(int waveNumber) {
        spawnWave(waveNumber, null);
    }

    public void spawnWave(int waveNumber, Runnable onComplete) {
        // 幂等：上一波的刷怪任务可能尚未结束（波次判定提前完成、上一局未收尾等），
        // 先取消旧任务再启动新任务，避免两批任务并存、用上一波的 waveConfig 继续刷怪。
        cancelSpawnTask();
        // M-14 回退路径提示每波重置一次：误配置可见，但不会按 mob 数量刷屏
        ringSpawnWarningLogged = false;

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
                // M-12：守卫收窄为「只认 RUNNING」——isGameRunning() 含 WAVE_INTERMISSION，
                // 快速清波（重锤 AOE 秒杀时 3 tick 批次间隙即空）会让本波剩余怪延续刷进
                // 休整期，污染下一波清波判定与休整手感。本任务即「本波刷怪窗口」：
                // state 一离开 RUNNING（清波转入休整 / 结算 / 停止）立即自停。
                if (gameManager.getState() != GameState.RUNNING) {
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
     * {@link #clearAllMobs()} 与「state != RUNNING 即停」守卫（M-12）全覆盖。</p>
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
                // 与正常路径同一守卫（M-12：只认 RUNNING，休整期即停）
                if (gameManager.getState() != GameState.RUNNING) {
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
     * 改成异步后必须补上「state != RUNNING 即停」守卫（M-12），否则失败结算后仍会继续刷怪。</p>
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
                // 与正常路径同一守卫（M-12：只认 RUNNING，休整期即停）
                if (gameManager.getState() != GameState.RUNNING) {
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
     *
     * <p>P2-12：同时打上 {@code hilltopvillage:wave-mob} PDC 标记。所有波次怪（含三特殊怪与
     * 空投怪）都经过本方法，崩溃/强杀重启后由 {@link #cleanupOrphans()}（实例级，竞技场
     * 世界加载后）与 {@link #cleanupAllOrphans(HilltopVillagePlugin)}（启动级，全服一次）
     * 按此标记清扫 —— persistent 标记只保证「局内不被移除」，跨进程重启时 {@code activeMobs}
     * 随进程消失，残怪无人认领，必须靠 PDC 标记补清扫。</p>
     */
    private void markWaveMobPersistent(Mob mob) {
        mob.setRemoveWhenFarAway(false);
        mob.setPersistent(true);
        mob.getPersistentDataContainer().set(waveMobKey, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * 清扫本竞技场世界内残留波次怪（P2-12）：崩溃/强杀（非优雅退出）后，上一局已刷出的
     * persistent 怪随世界存盘；重启后新实例直接进入 WAITING，无人清理。残留怪可攻击
     * 新局玩家与新生成的真村民（村民伤害白名单放行 Monster 来源），造成
     * 「被上一局残怪杀掉 → 强制失败」。
     *
     * <p>按 {@code hilltopvillage:wave-mob} PDC 标记识别（不依赖内存引用），模式与
     * {@link com.hilltopvillage.shop.NpcManager#cleanupOrphans()} /
     * {@link com.hilltopvillage.mechanics.VillagerPreviewManager#cleanupOrphans()} 一致。
     * 由 {@code ArenaInstance} 构造期调用（此时该世界刚加载完，且本世界此刻必无进行中
     * 对局——世界唯一性与退役隔离保证），只扫本世界：多竞技场下按全服扫会误删
     * 他场正在对局的活怪（它们带同样标记）。</p>
     *
     * @return 被清除的实体数
     */
    public int cleanupOrphans() {
        World world = gameManager.getGameWorld();
        if (world == null) return 0;
        int removed = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.getPersistentDataContainer().has(waveMobKey, PersistentDataType.BYTE)) continue;
            // 防御：本场当前活跃怪（构造期 activeMobs 必为空，双保险）
            if (activeMobs.contains(living)) continue;
            living.remove();
            removed++;
        }
        if (removed > 0) {
            gameManager.getPlugin().getLogger().info("已清理 " + removed + " 只残留波次怪 (竞技场 "
                    + gameManager.getArenaId() + ")");
        }
        return removed;
    }

    /**
     * 全服清扫残留波次怪（静态版，P2-12）：供插件启动阶段（{@code onEnable}）调用一次。
     *
     * <p>启动时任何对局都未开始（竞技场走启用队列串行加载），全服扫描无「误删活怪」风险；
     * 竞技场世界通常此刻尚未加载（启用队列下一 tick 才 createWorld），因此本扫描主要覆盖
     * 已随服务器启动加载的世界，竞技场世界的清扫由 {@link #cleanupOrphans()}（ArenaInstance
     * 构造期）互补完成。两处都仿 {@code NpcManager/VillagerSystem.cleanupOrphans} 模式。</p>
     *
     * @return 被清除的实体数
     */
    public static int cleanupAllOrphans(HilltopVillagePlugin plugin) {
        NamespacedKey key = new NamespacedKey(plugin, WAVE_MOB_KEY_NAME);
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("已清理 " + removed + " 只残留波次怪（启动清扫）");
        }
        return removed;
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
                        markMonsterId(mob, type);
                        applyMonsterEquipment(mob, gameManager.getConfigManager().getActiveConfig()
                                .getMonsters().get(type.toLowerCase()));
                        prepareWaveMob(mob);
                    } else if (entity instanceof LivingEntity living) {
                        markMonsterId(living, type);
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
            // fix5 M-09：弓是商店付费商品（15 水晶）——猎人主手为 BOW 时若按 5% 掉落，
            // 默认波次单人 20 波累计约 60 只猎人，95% 的玩家免费拿到弓，商店定价被架空。
            // 弓视作怪物「职能装备」：主手为 BOW 一律零掉落（含管理员自配的持弓怪）；
            // 其余物品与其余五部位保持 0.05 掉落面不变。
            equip.setDropChance(EquipmentSlot.HAND, mainHand == Material.BOW ? 0f : 0.05f);
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
            if (spawned instanceof LivingEntity living) {
                markMonsterId(living, "explode-beetle");
                activeMobs.add(living);
            }
            return;
        }
        markWaveMobPersistent(spider);
        markMonsterId(spider, "explode-beetle");
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
            if (spawned instanceof LivingEntity living) {
                markMonsterId(living, "hook-claw-hunter");
                activeMobs.add(living);
            }
            return;
        }
        markWaveMobPersistent(skeleton);
        markMonsterId(skeleton, "hook-claw-hunter");
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
        // P0-C 木桩修复：钩爪需要玩家在上方≥4格才能激活，平地无落差时猎手原本无任何
        // 移动/攻击 AI → 永久静止占着 activeMobs → 波次永不清。补一个低优先级 NodeAttackGoal
        // 作为退化行为：无法钩拉时推进并攻击最近的活跃节点（优先级 3 低于钩爪的 1，
        // 一旦出现可钩拉目标仍优先钩拉）。节点全毁的极端情形由波次超时保险兜底。
        Bukkit.getMobGoals().addGoal(skeleton, 3, new NodeAttackGoal(skeleton, gameManager));

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
            if (spawned instanceof LivingEntity living) {
                markMonsterId(living, "flying-dropper");
                activeMobs.add(living);
            }
            return;
        }
        markWaveMobPersistent(phantom);
        markMonsterId(phantom, "flying-dropper");
        // fix4 反馈2佐证：抛投者是「空投载具 + 骚扰者」的自定义单位，若保留幻翼原版的
        // 白天日光焚烧（实测日志：滞空良久后 burned to death），它在白天对局中会未完成
        // 空投就被烧死、完全失去功能。此处关闭日焚烧（仅对 Phantom 生效，entity-type
        // 被配置改成其它类型时跳过）。
        if (phantom instanceof Phantom vanillaPhantom) {
            vanillaPhantom.setShouldBurnInDay(false);
        }
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
        // fix4 反馈2：removeAllGoals 把幻翼原版俯冲攻击（PHANTOM_ATTACK_PLAYER）也清掉了，
        // 抛投者此前只会空投、从不攻击玩家。补一个低优先级骚扰俯冲 Goal（优先级 2 低于空投的 1，
        // 有空投任务时空投优先，间隙才俯冲），伤害取 ATTACK_DAMAGE（= monsters-config.yml
        // 的 base-damage，管理员可调），并附带冷却压低骚扰 DPS。
        Bukkit.getMobGoals().addGoal(phantom, 2, new DropperAttackGoal(phantom, gameManager));

        activeMobs.add(phantom);
    }

    private Location getRandomSpawnLocation() {
        // M-14 死配置接线（决策 D-1）：距离取 [spawn-radius-min, spawn-radius-max] 内的随机值
        // （per-arena 优先、全局兜底）。两键都 <=0（max<=0）时按各路径的旧行为兜底。
        int radiusMin = gameManager.getSpawnRadiusMin();
        int radiusMax = gameManager.getSpawnRadiusMax();

        // STORY-1.2：怪物刷怪走独立的 mob-spawn-points；旧存档（该键为空）回退到玩家出生点。
        List<Location> fixedPoints = gameManager.getMobSpawnPointsView();
        if (fixedPoints.isEmpty()) {
            // 新回退路径（M-14 原设计意图）：无任何固定刷怪点时，以核心为中心生成随机环形位置。
            // 可达场景：对局中管理员把两套点都清空（正常开局被 hasEffectiveMobSpawnPoints 拦截，
            // 走不到这里）。radius-max 未配置时保持旧行为（终止刷怪 + 日志提示补部署）。
            if (radiusMax <= 0) {
                gameManager.getPlugin().getLogger().severe("No mob spawn points configured! "
                        + "Deploy them with Step 3 of /hilltop setup or /hilltop deploy addmobspawn.");
                return null;
            }
            Location core = gameManager.getCoreLocation();
            if (core == null) return null;
            if (!ringSpawnWarningLogged) {
                ringSpawnWarningLogged = true;
                gameManager.getPlugin().getLogger().warning("竞技场 " + gameManager.getArenaId()
                        + " 的 mob-spawn-points 与 spawn-points 均为空，已回退到「以核心为中心、半径 "
                        + radiusMin + "-" + radiusMax + "」的环形刷怪；建议用 /hilltop deploy addmobspawn 补部署刷怪点");
            }
            // fix4 G1b：环形候选同样必须过安全落点检测——候选落在虚空/水面上时怪会直接
            // 掉出世界（作者实测：日志成片 fell out of the world）。8 次重试全部不安全则
            // 本只不刷（调用方 null 守卫），比刷进虚空白送强。
            int yOffset = gameManager.getSpawnYOffset();
            for (int attempt = 0; attempt < SPAWN_SAFE_RETRY_COUNT; attempt++) {
                Location candidate = core.clone();
                double distance = randomRingDistance(radiusMin, radiusMax);
                double angle = random.nextDouble() * 2 * Math.PI;
                candidate.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                if (yOffset != 0) candidate.add(0, yOffset, 0);
                Location safe = SafeSpawnUtil.findSafeGround(candidate);
                if (safe != null) return safe;
            }
            return null;
        }

        // 只读视图而非 getMobSpawnPoints()：后者每次都 new ArrayList<>(...)，
        // 一波 80 只怪就要拷 80 次小数组。取出的元素下面立刻 clone() 再偏移，
        // 不会改到 GameManager 内部的 Location 本体（视图的元素安全约束见该方法 Javadoc）。
        Location basePoint = fixedPoints.get(random.nextInt(fixedPoints.size())).clone();

        // M-14：生成高度偏移（per-arena spawn-y-offset 优先、全局 spawning.spawn-y-offset 兜底）。
        // fix4 G1b：yOffset 先叠加到基准点，之后它只影响安全扫描的起始高度（扫描会归位到地表）。
        int yOffset = gameManager.getSpawnYOffset();
        if (yOffset != 0) {
            basePoint.add(0, yOffset, 0);
        }

        // 以固定刷怪点为中心的随机水平偏移（距离语义不变：radius-max 配置了取 [min,max]，
        // 否则退回无 IO 的历史扩散常量 spawnRadiusSpread，避免旧存档缺键时行为突变）。
        // fix4 G1b：每个候选点都要过列扫描安全化——向下找实心非液体方块顶面；天空岛地图上
        // 偏移出岛的候选会扫到虚空被丢弃，换下一个候选（每次重抽距离+角度）；全部失败再回退
        // 「固定点本体的原地安全化」，仍有落脚点就刷，彻底无安全点才放弃本只。
        for (int attempt = 0; attempt < SPAWN_SAFE_RETRY_COUNT; attempt++) {
            Location candidate = basePoint.clone();
            double distance = radiusMax > 0
                    ? randomRingDistance(radiusMin, radiusMax)
                    : random.nextDouble() * gameManager.getConfigManager().getActiveConfig().getSpawnRadiusSpread();
            if (distance > 0) {
                double angle = random.nextDouble() * 2 * Math.PI;
                candidate.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            }
            Location safe = SafeSpawnUtil.findSafeGround(candidate);
            if (safe != null) return safe;
        }

        // 偏移候选全部不安全（如半径过大、点群贴在岛边缘）：回退到固定点本体原地安全化。
        // 这保证「怪至少刷在刷怪点附近的安全表面」，而不是直接放弃。
        return SafeSpawnUtil.findSafeGround(basePoint);
    }

    /** 在 [min,max] 中取随机距离（min 夹到非负；max 夹到不小于 min），半径为 0 时返回 0。 */
    private double randomRingDistance(int radiusMin, int radiusMax) {
        double min = Math.max(0, radiusMin);
        double max = Math.max(min, radiusMax);
        return min + random.nextDouble() * (max - min);
    }

    public int onMobDeath(LivingEntity mob) {
        activeMobs.remove(mob);
        return dropCurrency(mob);
    }

    /**
     * 波次怪物死亡时掉落商店货币（能量水晶）。fix4 起由「概率制 + 固定数量」改为
     * <b>每杀必掉、数量浮动</b>：默认每次击杀掉落 {@code [shop.currency-drop-min, shop.currency-drop-max]}
     * 个随机数（区间含两端）；<b>E2 起按怪种区分</b>——怪物带怪种 id PDC 标记且有
     * {@code monsters.<id>.drop-min/drop-max} 时优先用该怪的区间（端点缺省回退全局），
     * 空投怪 0（反挂机规则）。{@code shop.currency-drop-chance} 保留为概率门
     * （默认 1.0 = 必掉；旧格式文件在加载时已迁移为必掉）。仅游戏进行中
     * （RUNNING / WAVE_INTERMISSION）掉落；ShopManager 未初始化时静默跳过。
     *
     * <p>性能口径：掉落物实体数与「怪物数 × 平均掉落堆数」成正比。默认区间 1~2 时每只怪
     * 恰掉 1 堆（1 个 ItemStack），单波 80 只 ≈ 80 个实体，且原版会把同位置相邻的同类
     * 掉落物自动合并 —— 与旧掉落机制同量级、无额外放大。若服主把区间调大（如 10~20），
     * 仍然是每只怪 1 堆实体（数量体现在 ItemStack 的 amount 上，不增加实体数），
     * 真正的量级风险来自「堆数 × 存活时间」：竞技场重置时
     * {@code ArenaInstance.clearDroppedItems()} 会统一清理残留掉落物兜底。</p>
     *
     * @return 本次实际掉落的货币数量（0 = 未掉落：不满足条件 / 概率未命中 / 区间为 0）
     */
    private int dropCurrency(LivingEntity mob) {
        if (!gameManager.isGameRunning()) return 0;
        if (mob.getLocation().getWorld() == null) return 0;
        // P0-D：空投小怪不掉货币 —— 抛投者可无限空投，若空投怪也掉币，玩家挂机即可刷钱，
        // 且会污染经济。空投怪在生成时打了 PDC 标记（见 markAirdropMob），此处据此跳过。
        if (isAirdropMob(mob)) return 0;

        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        ShopManager shopManager = gameManager.getPlugin().getShopManager();
        if (shopManager == null || shopManager.getShopConfig() == null) return 0;

        // 概率门：默认 1.0（nextDouble ∈ [0,1)，恒 < 1.0 → 必掉）；
        // 0 或负数 → nextDouble() >= 0 恒真 → 关闭掉落。越界值就地钳制，不依赖配置侧约束。
        double chance = Math.min(1.0, Math.max(0.0, cfg.getCurrencyDropChance()));
        if (random.nextDouble() >= chance) return 0;

        int amount = rollCurrencyDropAmount(cfg, mob);
        if (amount <= 0) return 0;
        mob.getLocation().getWorld().dropItemNaturally(
                mob.getLocation(),
                shopManager.createCurrencyItem(amount));
        return amount;
    }

    /**
     * 滚动本次掉落的货币数量（E2 按怪种区分）：
     * <b>优先用该怪的 {@code drop-min}/{@code drop-max}</b>（端点级回退——某端未配置时
     * 用全局 {@code shop.currency-drop-min/max} 对应端），无怪种标记（兜底路径的原版僵尸、
     * 老存档残留怪等）或该怪配置不存在时整体回退全局区间。
     *
     * <p>两端各自钳到 {@code [0, 64]}（64 = 单堆上限：{@code ItemStack} 虽然只校验
     * amount > 0，但超上限堆会在拾取/背包操作时被拆分，钳制让掉落物始终是合法单堆）；
     * min > max 时按 {@code [max, min]} 修正顺序，配置写反不会让掉落任务抛
     * {@link IllegalArgumentException}（{@code nextInt} 要求参数为正）。</p>
     */
    private int rollCurrencyDropAmount(GameConfig cfg, LivingEntity mob) {
        int min = cfg.getCurrencyDropMin();
        int max = cfg.getCurrencyDropMax();
        // 怪种级覆盖：<0 = 未配置（GameConfig.MonsterConfig.dropMin/dropMax 的缺省哨兵）
        GameConfig.MonsterConfig mc = resolveMonsterConfig(mob);
        if (mc != null) {
            if (mc.getDropMin() >= 0) min = mc.getDropMin();
            if (mc.getDropMax() >= 0) max = mc.getDropMax();
        }
        min = Math.max(0, Math.min(64, min));
        max = Math.max(0, Math.min(64, max));
        if (min > max) {
            int swap = min;
            min = max;
            max = swap;
        }
        if (max <= 0) return 0;
        return min + random.nextInt(max - min + 1);
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
        // P0-D：同步取消未完成的空投批次任务。haltGame() 结算期间刻意不改游戏状态，
        // 空投任务的 isGameRunning() 守卫因此拦不住它，必须在这里显式取消。
        cancelAirdropTasks();

        for (LivingEntity mob : activeMobs) {
            if (mob != null && mob.isValid()) {
                mob.remove();
            }
        }
        activeMobs.clear();
    }

    /** 取消并清空所有未完成的空投批次任务（幂等）。 */
    private void cancelAirdropTasks() {
        for (BukkitTask task : airdropTasks) {
            if (task != null) task.cancel();
        }
        airdropTasks.clear();
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

    /** 给空投小怪打上 PDC 标记（dropCurrency / 调试据此识别空投来源的怪物）。 */
    private void markAirdropMob(LivingEntity mob) {
        mob.getPersistentDataContainer().set(airdropKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** 是否为空投小怪（带 {@link #airdropKey} PDC 标记）。 */
    private boolean isAirdropMob(LivingEntity mob) {
        return mob != null && mob.getPersistentDataContainer().has(airdropKey, PersistentDataType.BYTE);
    }

    /**
     * 给波次怪打上怪种 id PDC 标记（E2 按怪种掉落；值为 monsters 配置键如
     * {@code explode-beetle}）。空投小怪不打此标记——它们由 {@link #isAirdropMob}
     * 先行跳过掉落，且其种类可被 {@code airdrop-mob-type} 任意配置。
     */
    private void markMonsterId(LivingEntity mob, String monsterId) {
        if (mob == null || monsterId == null) return;
        mob.getPersistentDataContainer().set(monsterIdKey, PersistentDataType.STRING, monsterId.toLowerCase());
    }

    /**
     * 读取 mob 的怪种配置（E2）：优先按怪种 id PDC 标记查 {@code monsters.<id>}；
     * 无标记（兜底路径的原版僵尸 / 老存档残留怪等）返回 null，调用方回退全局值。
     */
    private GameConfig.MonsterConfig resolveMonsterConfig(LivingEntity mob) {
        if (mob == null) return null;
        String monsterId = mob.getPersistentDataContainer().get(monsterIdKey, PersistentDataType.STRING);
        if (monsterId == null) return null;
        return gameManager.getConfigManager().getActiveConfig().getMonsters().get(monsterId);
    }

    /**
     * 当前存活怪物数是否已达容量上限（全局上限与人均上限取最小）。
     *
     * <p>P0-D：空投前的快速门控 —— 已满则本轮不再空投（抛投者转入冷却重试），
     * 避免抛投者无视上限持续空投导致实体数指数膨胀。</p>
     */
    public boolean isMobCapReached() {
        return activeMobs.size() >= resolveMobCap();
    }

    /** 全局上限与「人均上限 × 当前人数」取最小；人数为 0 时退化为全局上限。 */
    private int resolveMobCap() {
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        int playerCount = Math.max(1, gameManager.getPlayers().size());
        return Math.min(cfg.getGlobalMobCap(), cfg.getMobCapPerPlayer() * playerCount);
    }

    /**
     * 执行一轮空投（P0-D 统一纳管入口，由 {@link com.hilltopvillage.ai.AirdropSpawnGoal} 调用）。
     *
     * <p>与旧实现（AirdropSpawnGoal 内直接 {@code spawnEntity + addActiveMob}）的区别：</p>
     * <ul>
     *   <li><b>受容量上限约束</b>：开投前按 {@link #resolveMobCap()} 裁剪本轮只数，批次内每只再复查，满了即停；</li>
     *   <li><b>纳入波次管理</b>：每只走 {@link #prepareWaveMob}（persistent + NodeAttackGoal），
     *       不再是「远离即消失、不触发死亡事件、永久残留 activeMobs 污染清波」的游离实体；</li>
     *   <li><b>不掉货币</b>：打 {@link #markAirdropMob} 标记，{@link #dropCurrency} 跳过；</li>
     *   <li><b>任务可被统一取消</b>：批次任务登记进 {@link #airdropTasks}，波次结束 / 结算 / 停服时随
     *       {@link #clearAllMobs()} 一并取消；运行期自带 dropper 存活、波次号一致、游戏进行中三重守卫。</li>
     * </ul>
     *
     * @param dropper    发起本轮空投的抛投者（死亡即终止本轮，不再继续刷）
     * @param dropLoc    空投落点
     * @param count      本轮期望空投数量
     * @param mobType    空投小怪的实体类型
     * @param originWave 发起本轮空投时的波次号（波次切换即终止，避免跨波补刷）
     */
    public void spawnAirdropMobs(Mob dropper, Location dropLoc, int count, EntityType mobType, int originWave) {
        if (dropLoc == null || dropLoc.getWorld() == null || mobType == null) return;
        if (!gameManager.isGameRunning()) return;

        final World world = dropLoc.getWorld();
        // 开投前按剩余容量裁剪本轮只数（批次内每只还会再复查一次，双保险）
        int room = resolveMobCap() - activeMobs.size();
        final int toSpawn = Math.min(Math.max(0, count), Math.max(0, room));
        if (toSpawn <= 0) return;

        final int[] spawned = {0};
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = new BukkitRunnable() {
            @Override
            public void run() {
                // dropper 死亡 / 波次切换 / 游戏结束 / 容量已满 → 立即终止本轮空投
                if (dropper == null || !dropper.isValid()
                        || !gameManager.isGameRunning()
                        || gameManager.getCurrentWave() != originWave
                        || spawned[0] >= toSpawn
                        || activeMobs.size() >= resolveMobCap()) {
                    airdropTasks.remove(handle[0]);
                    cancel();
                    return;
                }

                double offsetX = (random.nextDouble() - 0.5) * 4;
                double offsetZ = (random.nextDouble() - 0.5) * 4;
                Location spawnLoc = dropLoc.clone().add(offsetX, 3, offsetZ);

                Entity entity = world.spawnEntity(spawnLoc, mobType);
                if (entity instanceof Mob mob) {
                    var followAttr = mob.getAttribute(Attribute.FOLLOW_RANGE);
                    if (followAttr != null) followAttr.setBaseValue(40.0);
                    markAirdropMob(mob);
                    prepareWaveMob(mob); // persistent + NodeAttackGoal + 登记 activeMobs
                } else if (entity instanceof LivingEntity living) {
                    markAirdropMob(living);
                    activeMobs.add(living);
                }

                world.playSound(spawnLoc, Sound.ENTITY_ZOMBIE_INFECT, 0.3f, 0.5f);
                world.spawnParticle(Particle.DRIPPING_OBSIDIAN_TEAR, spawnLoc, 5, 0.3, 0.3, 0.3, 0);

                spawned[0]++;
            }
        }.runTaskTimer(gameManager.getPlugin(), 0L, AIRDROP_SPAWN_PERIOD_TICKS);
        airdropTasks.add(handle[0]);
    }

    /**
     * 把一只怪物移出波次（P0-C/D）：从世界移除并从 {@link #activeMobs} 剔除，<b>不</b>掉货币、不记击杀。
     *
     * <p>用于「自毁出列」（甲虫无节点、普通怪卡死放弃）与波次超时保险的强制清除——这些都不是
     * 玩家击杀，不应产生经济或击杀统计。{@code entity.remove()} 不触发 {@code EntityDeathEvent}，
     * 因此必须在这里主动从集合剔除，否则会留下无效实体永久卡住清波判定。</p>
     */
    public void discardMob(LivingEntity mob) {
        if (mob == null) return;
        if (mob.isValid()) mob.remove();
        activeMobs.remove(mob);
    }

    /** 只读视图：调用方只能查询，写入请用 {@link #addActiveMob(LivingEntity)} */
    public Set<LivingEntity> getActiveMobs() {
        return Collections.unmodifiableSet(activeMobs);
    }

    public int getRemainingMobCount() {
        return activeMobs.size();
    }

    /**
     * 波次超时保险 · 第一阶段（P0-C 根因兜底）：把全部存活怪物传送到最近存活玩家身边并高亮，
     * 让任何形态的卡死怪（木桩猎手 / 卡地形甲虫 / 节点不可达的普通怪）都能被玩家找到并清掉。
     *
     * <p>没有任何存活玩家可传送时（例如人全死但节点尚存、未判负），这些怪物无人能战，
     * 直接 {@link #discardMob} 出列，避免永久卡住清波。</p>
     *
     * @return 被处理（传送高亮或出列）的怪物数
     */
    public int relocateStuckMobsToPlayers() {
        List<Player> alive = getAlivePlayersInWorld();
        int handled = 0;
        for (LivingEntity mob : new ArrayList<>(activeMobs)) {
            if (mob == null || !mob.isValid()) {
                activeMobs.remove(mob);
                continue;
            }
            if (alive.isEmpty()) {
                // 无人可战：留着只会永久卡波，直接出列
                discardMob(mob);
                handled++;
                continue;
            }
            Player nearest = nearestPlayer(alive, mob.getLocation());
            Location dest = nearest.getLocation().clone().add(
                    (random.nextDouble() - 0.5) * 2, 0, (random.nextDouble() - 0.5) * 2);
            mob.teleport(dest);
            mob.setGlowing(true);
            if (mob instanceof Mob aggressive) {
                aggressive.setTarget(nearest); // 尽力让具备目标 AI 的怪物转向玩家
            }
            handled++;
        }
        return handled;
    }

    /**
     * 波次超时保险 · 第二阶段（最终安全网）：第一阶段宽限后仍未清波时，强制清除全部残存怪物。
     * 这是「保证波次一定能清」的硬兜底，{@link #discardMob} 出列后 {@link #isWaveCleared()} 即为 true。
     *
     * @return 被强制清除的怪物数
     */
    public int purgeStuckMobs() {
        int purged = 0;
        for (LivingEntity mob : new ArrayList<>(activeMobs)) {
            if (mob != null && mob.isValid()) {
                mob.getWorld().spawnParticle(Particle.LARGE_SMOKE, mob.getLocation().add(0, 1, 0),
                        10, 0.3, 0.5, 0.3, 0.02);
            }
            discardMob(mob);
            purged++;
        }
        activeMobs.clear();
        return purged;
    }

    /** 本竞技场世界内、仍存活且在线的玩家（供波次超时保险定位传送目标）。 */
    private List<Player> getAlivePlayersInWorld() {
        List<Player> result = new ArrayList<>();
        World world = gameManager.getGameWorld();
        if (world == null) return result;
        for (UUID uuid : gameManager.getPlayers()) {
            PlayerData data = gameManager.getPlayerData(uuid);
            if (data == null || !data.isAlive()) continue;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || player.getWorld() != world) continue;
            result.add(player);
        }
        return result;
    }

    /** 从给定玩家列表中取离 {@code from} 最近的一个（列表非空时保证返回非 null）。 */
    private Player nearestPlayer(List<Player> players, Location from) {
        Player best = players.get(0);
        double bestDistSq = Double.MAX_VALUE;
        for (Player player : players) {
            Location loc = player.getLocation();
            if (loc.getWorld() != from.getWorld()) continue;
            double distSq = loc.distanceSquared(from);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = player;
            }
        }
        return best;
    }
}
