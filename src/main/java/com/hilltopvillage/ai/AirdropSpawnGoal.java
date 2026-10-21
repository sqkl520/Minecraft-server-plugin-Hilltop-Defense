package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class AirdropSpawnGoal implements Goal<Mob> {

    private static final double FLY_HEIGHT_OFFSET       = 8.0;
    private static final double MOVE_SPEED              = 0.6;
    private static final double ARRIVAL_DISTANCE_SQ     = 16.0;
    private static final double STAY_ACTIVE_DISTANCE_SQ = 4.0;
    /**
     * 找不到落点后的退避间隔（tick）。
     *
     * <p>没有这个退避时，一旦 {@link #findDropLocation()} 返回 null（没有玩家在该世界 /
     * 区块未加载 / 候选点被占用），Goal 不会 start()、也就永远不会调用 stop() 去设置冷却，
     * 于是扫描被每个 tick 重放一次。</p>
     */
    private static final int SCAN_RETRY_INTERVAL_TICKS = 20;

    /**
     * Goal 的身份键。
     *
     * <p>{@code GoalKey.of(...)} 每次调用都新建对象（已反编译 paper-api 1.21.4 的
     * {@code GoalKey.of} 核实：字节码只有 {@code new GoalKey} + 构造，无任何缓存），
     * 且还会顺带 {@code new NamespacedKey(...)}（其构造含键名合法性校验）。
     * GoalKey 不可变并已实现 equals/hashCode，故提为常量复用完全安全。</p>
     */
    private static final GoalKey<Mob> KEY =
            GoalKey.of(Mob.class, new NamespacedKey("hilltopvillage", "airdrop_spawn"));

    /** GoalType 集合：{@code EnumSet.of} 每次都新建，同样提为常量。 */
    private static final EnumSet<GoalType> TYPES = EnumSet.of(GoalType.MOVE, GoalType.LOOK);

    private final Mob dropper;
    private final GameManager gameManager;
    private final Random random;
    private int airdropCooldown;
    /** 距下次允许扫描落点还剩多少 tick；扫描失败时用于退避 */
    private int scanCooldown;
    /** 本抛投者生涯已执行的空投轮数；达到 {@code airdrop-max-per-dropper} 后不再空投（P0-D） */
    private int airdropsDone;
    private Location targetDropLocation;

    public AirdropSpawnGoal(Mob dropper, GameManager gameManager) {
        this.dropper = dropper;
        this.gameManager = gameManager;
        this.random = new Random();
        this.airdropCooldown = 0;
        this.scanCooldown = 0;
        this.airdropsDone = 0;
    }

    @Override
    public boolean shouldActivate() {
        if (!gameManager.isGameRunning()) return false;

        // P0-D：抛投者生涯空投次数达上限后永不再空投（防止无限空投导致实体数指数膨胀卡服）。
        // airdrop-max-per-dropper <= 0 时 airdropsDone(>=0) 恒 >= 上限 → 等效于禁用空投。
        if (airdropsDone >= gameManager.getConfigManager().getActiveConfig().getAirdropMaxPerDropper()) {
            return false;
        }

        if (airdropCooldown > 0) {
            airdropCooldown--;
            return false;
        }
        // 扫描失败也必须退避：Goal 未启动 → 不会调用 stop() → 冷却不会被设置，
        // 若此处不退避，findDropLocation() 会被每个 tick 调用一次
        // （每次 = 玩家数 × 5 + 10 次同步高度图 / 方块读取）
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }

        // P0-D：全局/人均怪物上限已满时本轮不发起空投（否则抛投者无视上限持续空投），
        // 退避 SCAN_RETRY_INTERVAL_TICKS 后再评估。
        if (gameManager.getWaveManager().isMobCapReached()) {
            scanCooldown = SCAN_RETRY_INTERVAL_TICKS;
            return false;
        }

        targetDropLocation = findDropLocation();
        if (targetDropLocation == null) {
            scanCooldown = SCAN_RETRY_INTERVAL_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean shouldStayActive() {
        return targetDropLocation != null
                && dropper.getLocation().distanceSquared(targetDropLocation) > STAY_ACTIVE_DISTANCE_SQ
                && gameManager.isGameRunning() && dropper.isValid();
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        GameConfig.MonsterConfig monsterCfg = cfg.getMonsters().get("flying-dropper");
        airdropCooldown = monsterCfg != null ? monsterCfg.getCooldownTicks() : 160;
        targetDropLocation = null;
    }

    @Override
    public void tick() {
        if (targetDropLocation == null) return;

        Location flyTarget = targetDropLocation.clone().add(0, FLY_HEIGHT_OFFSET, 0);
        Location dropperLoc = dropper.getLocation();
        org.bukkit.util.Vector dir = flyTarget.toVector()
                .subtract(dropperLoc.toVector())
                .normalize();
        dropper.setVelocity(dir.multiply(MOVE_SPEED));

        if (dropperLoc.distanceSquared(flyTarget) <= ARRIVAL_DISTANCE_SQ) {
            executeAirdrop();
            targetDropLocation = null;
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        // 返回副本：已反编译核实服务端只读取该集合（PaperMobGoals#getHandle 只做
        // contains(TARGET)，MobGoalHelper#paperToVanilla 只迭代），但本实例经
        // MobGoals#getGoal/getAllGoals 对外暴露，而 EnumSet 可变，故保留防御性副本。
        // clone() 只是一次位掩码拷贝，比原来的 EnumSet.of（noneOf + 两次 add）更便宜。
        return TYPES.clone();
    }

    private Location findDropLocation() {
        World world = gameManager.getGameWorld();
        // 世界未就绪时直接放弃：下面所有候选点都要 new Location(world, ...) 与 getHighestBlockYAt(world, ...)
        if (world == null) return null;

        Set<UUID> arenaPlayers = gameManager.getPlayers();
        for (UUID uuid : arenaPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            // uuid 本就取自 arenaPlayers，无需再 contains 一次
            if (!player.isOnline() || player.isDead()) continue;
            if (player.getWorld() != world) continue;

            Location playerLoc = player.getLocation();

            for (int i = 0; i < 5; i++) {
                double offsetX = (random.nextDouble() - 0.5) * 10;
                double offsetZ = (random.nextDouble() - 0.5) * 10;

                Location candidate = new Location(world,
                        playerLoc.getX() + offsetX, 0, playerLoc.getZ() + offsetZ);

                // 只接受已加载的区块：getHighestBlockYAt() 会同步加载区块，未加载则直接换点
                if (!isChunkLoadedAt(world, candidate)) continue;

                candidate.setY(world.getHighestBlockYAt(candidate) + 1);

                if (candidate.getBlock().getType().isAir()) {
                    return candidate;
                }
            }
        }

        // 核心兜底分支才需要读取核心坐标：把它放到玩家候选循环之后，
        // 避免「有玩家候选却完全不需要核心」时也去碰 GameManager 的核心字段。
        //
        // ⚠️ null 守卫的实际防护力有限（已核实，与 ArenaInstance#getCoreLocation 的说明一致）：
        // GameManager#getCoreLocation() 的实现是 coreLocation.clone()，字段为 null 时 NPE 在
        // 调用<b>内部</b>就抛出了，走不到下面的判断。四层前置条件保证本 Goal 运行期间核心非空：
        //   ① ArenaInstance 构造函数解引用 core；② MultiArenaManager 两条构建路径都拒绝未部署核心；
        //   ③ 全项目唯一写点 AdminDeployManager.saveSpawnPoints() 仅在非空时才转发；
        //   ④ 本 Goal 只在刷怪时挂到 flying-dropper 上，而刷怪只在开局后发生。
        // 因此这个判断按代码惯例保留（与调用方「null = 未部署」的约定一致），但不要指望它兜住 NPE。
        Location core = gameManager.getCoreLocation();
        if (core == null) return null;

        for (int i = 0; i < 10; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 40;
            double offsetZ = (random.nextDouble() - 0.5) * 40;

            Location candidate = new Location(world,
                    core.getX() + offsetX, 0, core.getZ() + offsetZ);

            // 同上：未加载的区块一律跳过，绝不触发同步区块加载
            if (!isChunkLoadedAt(world, candidate)) continue;

            candidate.setY(world.getHighestBlockYAt(candidate) + 1);

            if (candidate.getBlock().getType().isAir()) {
                return candidate;
            }
        }

        return null;
    }

    /** 判断候选点所在区块是否已加载（纯查询，不会触发同步加载） */
    private boolean isChunkLoadedAt(World world, Location location) {
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    private void executeAirdrop() {
        Location dropLoc = targetDropLocation;
        World world = dropLoc.getWorld();
        if (world == null) return;
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        GameConfig.MonsterConfig monsterCfg = cfg.getMonsters().get("flying-dropper");
        int count = monsterCfg != null ? monsterCfg.getAirdropMobCount() : 3;
        String mobTypeName = monsterCfg != null ? monsterCfg.getAirdropMobType() : "ZOMBIE";
        EntityType mobType;
        try {
            mobType = EntityType.valueOf(mobTypeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mobType = EntityType.ZOMBIE;
        }

        world.playSound(dropLoc, Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 0.3f);
        world.spawnParticle(Particle.CLOUD, dropLoc.clone().add(0, 5, 0), 30, 2, 1, 2, 0.05);

        // P0-D：空投小怪统一交由 WaveManager 生成 —— 容量上限裁剪 + prepareWaveMob 纳管
        // （persistent + NodeAttackGoal，不再是游离实体污染清波）+ 不掉货币 + 批次任务登记进
        // airdropTasks（波次切换 / 结算 / 停服时随 clearAllMobs 统一取消）。本 Goal 不再直接 spawnEntity。
        gameManager.getWaveManager().spawnAirdropMobs(dropper, dropLoc, count, mobType, gameManager.getCurrentWave());
        airdropsDone++;
    }
}
