package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
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

import java.util.EnumSet;

public class HookClawHunterGoal implements Goal<Mob> {

    private final Mob hunter;
    private final GameManager gameManager;
    private Player currentTarget;
    private int cooldownTicks;
    private int aimTicks;
    private boolean active;

    private static final int COOLDOWN = 60;
    private static final int AIM_DURATION = 20;
    private static final double MIN_SHOOT_DIST_SQ = 64.0;
    private static final double MAX_SHOOT_DIST_SQ = 900.0;
    private static final double REQUIRED_Y_DIFF = 4.0;

    public HookClawHunterGoal(Mob hunter, GameManager gameManager) {
        this.hunter = hunter;
        this.gameManager = gameManager;
        this.cooldownTicks = 0;
        this.aimTicks = 0;
        this.active = false;
    }

    @Override
    public boolean shouldActivate() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        if (!gameManager.isGameRunning()) return false;
        if (hunter.getTarget() != null) return false;

        currentTarget = findValidTarget();
        return currentTarget != null;
    }

    @Override
    public boolean shouldStayActive() {
        return active && currentTarget != null && currentTarget.isOnline()
                && !currentTarget.isDead() && gameManager.isGameRunning()
                && aimTicks > 0;
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
        cooldownTicks = COOLDOWN;
    }

    @Override
    public void tick() {
        if (currentTarget == null || !currentTarget.isOnline()) {
            active = false;
            return;
        }

        Location hunterLoc = hunter.getLocation();
        Location targetLoc = currentTarget.getLocation();

        Vector direction = targetLoc.toVector().subtract(hunterLoc.toVector());
        direction.setY(0);
        if (direction.lengthSquared() > 0.01) {
            Location lookAt = hunterLoc.clone();
            lookAt.setDirection(direction.normalize());
            hunter.teleport(lookAt);
        }

        if (aimTicks > 0) {
            aimTicks--;
            if (aimTicks % 4 == 0) {
                hunter.getWorld().playSound(hunterLoc, Sound.ENTITY_SKELETON_SHOOT, 0.5f, 0.6f);
                hunter.getWorld().spawnParticle(Particle.CRIT,
                        hunterLoc.clone().add(0, 1.4, 0), 5, 0.1, 0.1, 0.1, 0);
            }
            return;
        }

        shootHook();
        active = false;
        cooldownTicks = COOLDOWN;
    }

    @Override
    public GoalKey<Mob> getKey() {
        return GoalKey.of(Mob.class, new NamespacedKey("hilltopvillage", "hook_claw_hunter"));
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }

    private Player findValidTarget() {
        double bestScore = Double.MAX_VALUE;
        Player bestTarget = null;
        Location hunterLoc = hunter.getLocation();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!gameManager.getPlayers().contains(player.getUniqueId())) continue;
            if (player.getGameMode() == GameMode.SPECTATOR
                    || player.getGameMode() == GameMode.CREATIVE) continue;

            Location playerLoc = player.getLocation();
            double yDiff = playerLoc.getY() - hunterLoc.getY();
            if (yDiff < REQUIRED_Y_DIFF) continue;

            double horizontalDistSq = horizontalDistanceSq(hunterLoc, playerLoc);
            if (horizontalDistSq < MIN_SHOOT_DIST_SQ) continue;
            if (horizontalDistSq > MAX_SHOOT_DIST_SQ) continue;

            if (!hasLineOfSight(hunterLoc, playerLoc)) continue;

            double score = horizontalDistSq - (yDiff * 2.0);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = player;
            }
        }

        return bestTarget;
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
        if (currentTarget == null) return;

        Location hunterLoc = hunter.getLocation().clone().add(0, 1.4, 0);
        Location targetLoc = currentTarget.getLocation().clone().add(0, 1.0, 0);

        Vector velocity = targetLoc.toVector().subtract(hunterLoc.toVector()).normalize();
        double speed = gameManager.getPlugin().getConfig()
                .getDouble("monsters.hook-claw-hunter.projectile-speed", 1.8);
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
                if (ticks > 200 || !projectile.isValid() || projectile.isDead()) {
                    cancel();
                    return;
                }
                projectile.getWorld().spawnParticle(
                        Particle.SPELL_WITCH,
                        projectile.getLocation(),
                        2, 0.1, 0.1, 0.1, 0);
            }
        }.runTaskTimer(gameManager.getPlugin(), 1L, 1L);
    }

    public static void onHookHit(Player hitPlayer, Snowball projectile, GameManager gameManager) {
        if (!"hook_claw".equals(projectile.getCustomName())) return;

        Location teleportDest = hitPlayer.getLocation().clone();

        if (projectile.getShooter() instanceof Mob shooter && shooter.isValid()) {
            teleportDest = shooter.getLocation().clone();
            Location lookDir = hitPlayer.getLocation().getDirection()
                    .multiply(-1).toLocation(hitPlayer.getWorld());
            teleportDest.setDirection(lookDir.getDirection());
            hitPlayer.teleport(teleportDest);
        }

        int stunDuration = gameManager.getPlugin().getConfig()
                .getInt("monsters.hook-claw-hunter.stun-duration-ticks", 40);

        hitPlayer.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS, stunDuration, 0, false, true, true));
        hitPlayer.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOW, stunDuration, 3, false, true, true));
        hitPlayer.addPotionEffect(new PotionEffect(
                PotionEffectType.CONFUSION, stunDuration, 1, false, true, true));

        hitPlayer.getWorld().playSound(hitPlayer.getLocation(),
                Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        hitPlayer.getWorld().spawnParticle(Particle.PORTAL,
                hitPlayer.getLocation(), 30, 0.5, 0.5, 0.5, 0.1);
    }
}
