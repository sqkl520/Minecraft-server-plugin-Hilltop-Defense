package com.hilltopvillage.util;

import com.hilltopvillage.HilltopVillagePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DisplayEntityManager {

    private final HilltopVillagePlugin plugin;
    private final Queue<ScheduledDisplay> cleanupQueue;

    public DisplayEntityManager(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.cleanupQueue = new ConcurrentLinkedQueue<>();
        startCleanupTask();
    }

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTick = plugin.getServer().getCurrentTick();
                while (!cleanupQueue.isEmpty()) {
                    ScheduledDisplay scheduled = cleanupQueue.peek();
                    if (scheduled == null) break;

                    if (currentTick >= scheduled.removeAtTick) {
                        cleanupQueue.poll();
                        if (scheduled.display != null && scheduled.display.isValid()) {
                            scheduled.display.remove();
                        }
                    } else {
                        break;
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 5L);
    }

    public void spawnHammerImpact(Location location, int displayDurationTicks) {
        World world = location.getWorld();
        Location center = location.clone();

        BlockData anvilData = Material.ANVIL.createBlockData();
        BlockDisplay hammerDisplay = world.spawn(center, BlockDisplay.class, display -> {
            display.setBlock(anvilData);
            display.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            display.setInterpolationDuration(0);
        });

        // 固定大小，脚部位置，无动画
        Transformation transform = hammerDisplay.getTransformation();
        transform.getScale().set(0.5f);
        transform.getTranslation().set(0.0f, 0.0f, 0.0f);
        hammerDisplay.setTransformation(transform);

        scheduleCleanup(hammerDisplay, displayDurationTicks);

        Location particleLoc = center.clone().add(0, 0.1, 0);
        world.spawnParticle(org.bukkit.Particle.BLOCK_CRACK, particleLoc, 30, 0.5, 0.1, 0.5, 0.1,
                Material.IRON_BLOCK.createBlockData());
        world.spawnParticle(org.bukkit.Particle.CLOUD, particleLoc, 15, 0.8, 0.1, 0.8, 0.02);
    }

    public void spawnNodeRepairEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0.5, 1.5, 0.5);

        for (int i = 0; i < 8; i++) {
            double angle = (2 * Math.PI / 8) * i;
            double x = Math.cos(angle) * 0.8;
            double z = Math.sin(angle) * 0.8;

            world.spawnParticle(org.bukkit.Particle.VILLAGER_HAPPY,
                    center.clone().add(x, 0, z), 1, 0, 0, 0, 0);
        }

        world.spawnParticle(org.bukkit.Particle.ENCHANTMENT_TABLE,
                center, 20, 0.5, 0.5, 0.5, 0);
    }

    public void spawnNodeDestroyEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0.5, 0.5, 0.5);

        world.spawnParticle(org.bukkit.Particle.EXPLOSION_LARGE, center, 1, 0, 0, 0, 0);
        world.spawnParticle(org.bukkit.Particle.FLAME, center, 20, 0.5, 0.5, 0.5, 0.02);
    }

    private void scheduleCleanup(org.bukkit.entity.Display display, int durationTicks) {
        long removeAt = plugin.getServer().getCurrentTick() + durationTicks;
        cleanupQueue.add(new ScheduledDisplay(display, removeAt));
    }

    public void cleanupAll() {
        ScheduledDisplay scheduled;
        while ((scheduled = cleanupQueue.poll()) != null) {
            if (scheduled.display != null && scheduled.display.isValid()) {
                scheduled.display.remove();
            }
        }
    }

    public int getPendingCleanupCount() {
        return cleanupQueue.size();
    }

    private static class ScheduledDisplay {
        final org.bukkit.entity.Display display;
        final long removeAtTick;

        ScheduledDisplay(org.bukkit.entity.Display display, long removeAtTick) {
            this.display = display;
            this.removeAtTick = removeAtTick;
        }
    }
}
