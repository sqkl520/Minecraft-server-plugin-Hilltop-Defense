package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.EnumSet;
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

    private final Mob dropper;
    private final GameManager gameManager;
    private final Random random;
    private int airdropCooldown;
    /** 距下次允许扫描落点还剩多少 tick；扫描失败时用于退避 */
    private int scanCooldown;
    private Location targetDropLocation;

    public AirdropSpawnGoal(Mob dropper, GameManager gameManager) {
        this.dropper = dropper;
        this.gameManager = gameManager;
        this.random = new Random();
        this.airdropCooldown = 0;
        this.scanCooldown = 0;
    }

    @Override
    public boolean shouldActivate() {
        if (!gameManager.isGameRunning()) return false;

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
        return GoalKey.of(Mob.class, new NamespacedKey("hilltopvillage", "airdrop_spawn"));
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
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
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        GameConfig.MonsterConfig monsterCfg = cfg.getMonsters().get("flying-dropper");
        int count = monsterCfg != null ? monsterCfg.getAirdropMobCount() : 3;
        String mobTypeName = monsterCfg != null ? monsterCfg.getAirdropMobType() : "ZOMBIE";
        EntityType mobType;
        try {
            mobType = EntityType.valueOf(mobTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            mobType = EntityType.ZOMBIE;
        }

        world.playSound(dropLoc, Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 0.3f);
        world.spawnParticle(Particle.CLOUD, dropLoc.clone().add(0, 5, 0), 30, 2, 1, 2, 0.05);

        final EntityType finalMobType = mobType;
        new BukkitRunnable() {
            int spawned = 0;

            @Override
            public void run() {
                if (spawned >= count || !gameManager.isGameRunning()) {
                    cancel();
                    return;
                }

                double offsetX = (random.nextDouble() - 0.5) * 4;
                double offsetZ = (random.nextDouble() - 0.5) * 4;
                Location spawnLoc = dropLoc.clone().add(offsetX, 3, offsetZ);

                LivingEntity mob = (LivingEntity) world.spawnEntity(spawnLoc, finalMobType);
                mob.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(40.0);
                gameManager.getWaveManager().addActiveMob(mob);

                world.playSound(spawnLoc, Sound.ENTITY_ZOMBIE_INFECT, 0.3f, 0.5f);
                world.spawnParticle(Particle.DRIPPING_OBSIDIAN_TEAR, spawnLoc, 5, 0.3, 0.3, 0.3, 0);

                spawned++;
            }
        }.runTaskTimer(gameManager.getPlugin(), 0L, 10L);
    }
}
