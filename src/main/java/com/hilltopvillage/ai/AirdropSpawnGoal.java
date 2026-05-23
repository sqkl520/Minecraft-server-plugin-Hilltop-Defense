package com.hilltopvillage.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
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

public class AirdropSpawnGoal implements Goal<Mob> {

    private final Mob dropper;
    private final GameManager gameManager;
    private final Random random;
    private int airdropCooldown;
    private Location targetDropLocation;

    public AirdropSpawnGoal(Mob dropper, GameManager gameManager) {
        this.dropper = dropper;
        this.gameManager = gameManager;
        this.random = new Random();
        this.airdropCooldown = 0;
    }

    @Override
    public boolean shouldActivate() {
        if (!gameManager.isGameRunning()) return false;

        if (airdropCooldown > 0) {
            airdropCooldown--;
            return false;
        }

        targetDropLocation = findDropLocation();
        return targetDropLocation != null;
    }

    @Override
    public boolean shouldStayActive() {
        return targetDropLocation != null
                && dropper.getLocation().distanceSquared(targetDropLocation) > 4.0
                && gameManager.isGameRunning() && dropper.isValid();
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        airdropCooldown = gameManager.getPlugin().getConfig()
                .getInt("monsters.flying-dropper.airdrop-interval-ticks", 160);
        targetDropLocation = null;
    }

    @Override
    public void tick() {
        if (targetDropLocation == null) return;

        Location flyTarget = targetDropLocation.clone().add(0, 8, 0);
        org.bukkit.util.Vector dir = flyTarget.toVector().subtract(dropper.getLocation().toVector()).normalize();
        Location newLoc = dropper.getLocation().clone().add(dir.multiply(0.6));
        newLoc.setDirection(dir);
        dropper.teleport(newLoc);

        if (dropper.getLocation().distanceSquared(flyTarget) <= 16.0) {
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
        Location core = gameManager.getCoreLocation();
        World world = gameManager.getGameWorld();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!gameManager.getPlayers().contains(player.getUniqueId())) continue;
            if (!player.isOnline() || player.isDead()) continue;

            Location playerLoc = player.getLocation();

            for (int i = 0; i < 5; i++) {
                double offsetX = (random.nextDouble() - 0.5) * 10;
                double offsetZ = (random.nextDouble() - 0.5) * 10;

                Location candidate = new Location(world,
                        playerLoc.getX() + offsetX, 0, playerLoc.getZ() + offsetZ);
                candidate.setY(world.getHighestBlockYAt(candidate) + 1);

                if (candidate.getBlock().getType().isAir()) {
                    return candidate;
                }
            }
        }

        for (int i = 0; i < 10; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 40;
            double offsetZ = (random.nextDouble() - 0.5) * 40;

            Location candidate = new Location(world,
                    core.getX() + offsetX, 0, core.getZ() + offsetZ);
            candidate.setY(world.getHighestBlockYAt(candidate) + 1);

            if (candidate.getBlock().getType().isAir()) {
                return candidate;
            }
        }

        return null;
    }

    private void executeAirdrop() {
        Location dropLoc = targetDropLocation;
        World world = dropLoc.getWorld();
        int count = gameManager.getPlugin().getConfig()
                .getInt("monsters.flying-dropper.airdrop-count", 3);
        String mobTypeName = gameManager.getPlugin().getConfig()
                .getString("monsters.flying-dropper.airdrop-mob-type", "ZOMBIE");
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
                mob.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(40.0);
                gameManager.getWaveManager().getActiveMobs().add(mob);

                world.playSound(spawnLoc, Sound.ENTITY_ZOMBIE_INFECT, 0.3f, 0.5f);
                world.spawnParticle(Particle.DRIPPING_OBSIDIAN_TEAR, spawnLoc, 5, 0.3, 0.3, 0.3, 0);

                spawned++;
            }
        }.runTaskTimer(gameManager.getPlugin(), 0L, 10L);
    }
}
