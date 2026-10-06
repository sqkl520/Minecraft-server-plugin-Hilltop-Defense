package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import org.bukkit.*;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class HookClawHunterGoal implements Goal<Mob> {

    private final Mob hunter;
    private final GameManager gameManager;
    private Player currentTarget;
    private int cooldownTicks;
    private int scanCooldown;
    private int scanCursor;
    private int aimTicks;
    private boolean active;

    /** 配置缺失或非法时的钩爪射击冷却兜底（tick） */
    private static final int DEFAULT_COOLDOWN_TICKS = 60;
    /** 寻敌扫描间隔：当前次扫描计为第 0 tick，下一次扫描发生在第 8 tick */
    private static final int SCAN_INTERVAL_TICKS = 8;
    /** 每次扫描最多做多少次方块射线检测，跨扫描通过游标公平轮换候选者 */
    private static final int MAX_LINE_OF_SIGHT_CHECKS = 3;
    private static final int AIM_DURATION = 20;
    private static final double MIN_SHOOT_DIST_SQ = 64.0;
    private static final double MAX_SHOOT_DIST_SQ = 900.0;
    private static final double REQUIRED_Y_DIFF = 4.0;

    public HookClawHunterGoal(Mob hunter, GameManager gameManager) {
        this.hunter = hunter;
        this.gameManager = gameManager;
        this.cooldownTicks = 0;
        this.scanCooldown = 0;
        this.scanCursor = 0;
        this.aimTicks = 0;
        this.active = false;
    }

    @Override
    public boolean shouldActivate() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        // 扫描节流：找不到目标时也进入冷却，避免每 tick 重复全量扫描
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        if (!gameManager.isGameRunning()) return false;
        if (hunter.getTarget() != null) return false;

        // 当前评估已完成扫描，只跳过之后 7 次评估，使扫描间隔明确为 8 tick
        scanCooldown = SCAN_INTERVAL_TICKS - 1;
        currentTarget = findValidTarget();
        return currentTarget != null;
    }

    @Override
    public boolean shouldStayActive() {
        return active && isValidArenaTarget(currentTarget, gameManager);
    }

    @Override
    public void start() {
        active = true;
        aimTicks = AIM_DURATION;
    }

    @Override
    public void stop() {
        active = false;
        currentTarget = null;
        cooldownTicks = resolveCooldownTicks();
        // 本次已成功锁定目标，清掉扫描节流，避免与射击冷却叠加（保持原有开火节奏）
        scanCooldown = 0;
    }

    @Override
    public void tick() {
        if (!isValidArenaTarget(currentTarget, gameManager)) {
            active = false;
            return;
        }

        Location hunterLoc = hunter.getLocation();
        Location targetLoc = currentTarget.getLocation();

        Vector direction = targetLoc.toVector().subtract(hunterLoc.toVector());
        direction.setY(0);
        if (direction.lengthSquared() > 0.01) {
            // 只更新视角：teleport() 会触发 EntityTeleportEvent 并走完整传送流程
            // （位置同步 + 速度重置），每 tick 对每只钩爪怪调用既昂贵又会打断位置同步。
            // 复用已克隆的 hunterLoc 计算 yaw/pitch，与原逻辑逐位相同。
            hunterLoc.setDirection(direction.normalize());
            hunter.setRotation(hunterLoc.getYaw(), hunterLoc.getPitch());
        }

        if (aimTicks > 0) {
            aimTicks--;
            if (aimTicks % 4 == 0) {
                hunter.getWorld().playSound(hunterLoc, Sound.ENTITY_SKELETON_SHOOT, 0.5f, 0.6f);
                hunter.getWorld().spawnParticle(Particle.CRIT,
                        hunterLoc.clone().add(0, 1.4, 0), 5, 0.1, 0.1, 0.1, 0);
            }
            if (aimTicks > 0) return;
        }

        shootHook();
        active = false;
    }

    @Override
    public GoalKey<Mob> getKey() {
        return GoalKey.of(Mob.class, new NamespacedKey("hilltopvillage", "hook_claw_hunter"));
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }

    /**
     * 读取配置中心 GUI 的 {@code hook-claw-hunter.cooldown-ticks}，使管理员调整即时生效。
     * 配置缺失或非法（<=0）时回退到历史默认值。
     */
    private int resolveCooldownTicks() {
        GameConfig.MonsterConfig cfg = gameManager.getConfigManager()
                .getActiveConfig().getMonsters().get("hook-claw-hunter");
        int configured = cfg == null ? 0 : cfg.getCooldownTicks();
        return configured > 0 ? configured : DEFAULT_COOLDOWN_TICKS;
    }

    private Player findValidTarget() {
        Location hunterLoc = hunter.getLocation();

        // 第一轮：只做便宜的过滤（不含方块射线），收集本竞技场的合法候选者
        List<Player> candidates = new ArrayList<>();
        Set<UUID> arenaPlayers = gameManager.getPlayers();
        // 循环外缓存游戏世界：getGameWorld() 在每次迭代都被调用，而候选者数量可达数十
        World gameWorld = gameManager.getGameWorld();
        for (UUID uuid : arenaPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            // 注：uuid 本就取自 arenaPlayers，无需再判 contains
            if (player.getWorld() != gameWorld) continue;
            if (player.getGameMode() == GameMode.SPECTATOR
                    || player.getGameMode() == GameMode.CREATIVE) continue;

            Location playerLoc = player.getLocation();
            double yDiff = playerLoc.getY() - hunterLoc.getY();
            if (yDiff < REQUIRED_Y_DIFF) continue;

            double horizontalDistSq = horizontalDistanceSq(hunterLoc, playerLoc);
            if (horizontalDistSq < MIN_SHOOT_DIST_SQ) continue;
            if (horizontalDistSq > MAX_SHOOT_DIST_SQ) continue;

            candidates.add(player);
        }

        if (candidates.isEmpty()) return null;

        // 第二轮：按最终选目标使用的 score 排序，再从游标位置轮换检查，避免后排永久饿死
        candidates.sort(Comparator
                .comparingDouble((Player player) -> targetScore(hunterLoc, player.getLocation()))
                .thenComparing(Player::getUniqueId));

        int candidateCount = candidates.size();
        int checks = Math.min(MAX_LINE_OF_SIGHT_CHECKS, candidateCount);
        int startIndex = Math.floorMod(scanCursor, candidateCount);
        double bestScore = Double.MAX_VALUE;
        Player bestTarget = null;

        for (int offset = 0; offset < checks; offset++) {
            Player player = candidates.get((startIndex + offset) % candidateCount);
            Location playerLoc = player.getLocation();

            if (!hasLineOfSight(hunterLoc, playerLoc)) continue;

            double score = targetScore(hunterLoc, playerLoc);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = player;
            }
        }
        scanCursor = (startIndex + checks) % candidateCount;

        return bestTarget;
    }

    private double targetScore(Location hunterLoc, Location playerLoc) {
        double yDiff = playerLoc.getY() - hunterLoc.getY();
        return horizontalDistanceSq(hunterLoc, playerLoc) - (yDiff * 2.0);
    }

    private double horizontalDistanceSq(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private boolean hasLineOfSight(Location from, Location to) {
        if (from.getWorld() != to.getWorld()) return false;

        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance <= 0) return false;

        RayTraceResult result = from.getWorld().rayTraceBlocks(
                from, direction.normalize(), distance,
                FluidCollisionMode.NEVER, true);

        return result == null || result.getHitBlock() == null;
    }

    private void shootHook() {
        if (!isValidArenaTarget(currentTarget, gameManager)) return;

        Player hookTarget = currentTarget;
        Location hunterLoc = hunter.getLocation().clone().add(0, 1.4, 0);
        Location targetLoc = hookTarget.getLocation().clone().add(0, 1.0, 0);
        if (hunterLoc.getWorld() != gameManager.getGameWorld()) return;

        Vector velocity = targetLoc.toVector().subtract(hunterLoc.toVector()).normalize();
        GameConfig cfg = gameManager.getConfigManager().getActiveConfig();
        GameConfig.MonsterConfig monsterCfg = cfg.getMonsters().get("hook-claw-hunter");
        double speed = monsterCfg != null ? monsterCfg.getHookVelocity() : 1.8;
        velocity.multiply(speed);

        Snowball projectile = hunter.getWorld().spawn(hunterLoc, Snowball.class);
        projectile.setVelocity(velocity);
        projectile.setShooter(hunter);
        projectile.setGlowing(true);
        projectile.setCustomName("hook_claw");
        projectile.setCustomNameVisible(false);

        hunter.getWorld().playSound(hunterLoc, Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.5f);
        hunter.getWorld().spawnParticle(Particle.SWEEP_ATTACK, hunterLoc, 1, 0, 0, 0, 0);

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks++;
                // 寿命上限按「tick」而非「执行次数」计：周期已是 2 tick，100 次 × 2 = 200 tick（10 秒），
                // 与原实现（1 tick 周期 × 200 次）的实际存活时长一致
                if (ticks > 100 || !projectile.isValid() || projectile.isDead()
                        || !gameManager.isGameRunning()
                        || !hunter.isValid()
                        || !gameManager.getWaveManager().getActiveMobs().contains(hunter)
                        || !isValidArenaTarget(hookTarget, gameManager)
                        || projectile.getWorld() != gameManager.getGameWorld()) {
                    projectile.remove();
                    cancel();
                    return;
                }
                // 粒子每 2 tick 输出一次（单次 3 个），任务频率减半，视觉密度不变
                projectile.getWorld().spawnParticle(
                        Particle.WITCH,
                        projectile.getLocation(),
                        3, 0.1, 0.1, 0.1, 0);
            }
        }.runTaskTimer(gameManager.getPlugin(), 1L, 2L);
    }

    public static void onHookHit(Player hitPlayer, Snowball projectile, GameManager gameManager) {
        if (!"hook_claw".equals(projectile.getCustomName())) return;
        if (!isValidArenaTarget(hitPlayer, gameManager)
                || projectile.getWorld() != gameManager.getGameWorld()
                || !(projectile.getShooter() instanceof Mob shooter)
                || !shooter.isValid()
                || shooter.getWorld() != gameManager.getGameWorld()
                || !gameManager.getWaveManager().getActiveMobs().contains(shooter)) {
            projectile.remove();
            return;
        }

        Location teleportDest = shooter.getLocation().clone();
        Location lookDir = hitPlayer.getLocation().getDirection()
                .multiply(-1).toLocation(hitPlayer.getWorld());
        teleportDest.setDirection(lookDir.getDirection());
        hitPlayer.teleport(teleportDest);

        GameConfig cfg2 = gameManager.getConfigManager().getActiveConfig();
        GameConfig.MonsterConfig monsterCfg2 = cfg2.getMonsters().get("hook-claw-hunter");
        int stunDuration = monsterCfg2 != null ? monsterCfg2.getStunDurationTicks() : 40;

        hitPlayer.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS, stunDuration, 0, false, true, true));
        hitPlayer.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS, stunDuration, 3, false, true, true));
        hitPlayer.addPotionEffect(new PotionEffect(
                PotionEffectType.NAUSEA, stunDuration, 1, false, true, true));

        hitPlayer.getWorld().playSound(hitPlayer.getLocation(),
                Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        hitPlayer.getWorld().spawnParticle(Particle.PORTAL,
                hitPlayer.getLocation(), 30, 0.5, 0.5, 0.5, 0.1);
        projectile.remove();
    }

    private static boolean isValidArenaTarget(Player player, GameManager gameManager) {
        World gameWorld = gameManager.getGameWorld();
        return player != null && player.isOnline() && !player.isDead()
                && gameManager.isGameRunning()
                && gameManager.getPlayers().contains(player.getUniqueId())
                && gameWorld != null && player.getWorld() == gameWorld
                && player.getGameMode() != GameMode.SPECTATOR
                && player.getGameMode() != GameMode.CREATIVE;
    }
}
