package com.hilltopvillage.mechanics;

import org.bukkit.*;
import org.bukkit.util.Vector;
import java.util.*;

public class HammerEffectRenderer {

    /**
     * 低档猛击：轻量尘土 + 小范围烟雾环
     */
    public static void spawnLowTierEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0, 0.1, 0);

        world.spawnParticle(Particle.EXPLOSION_NORMAL, center, 3, 0.8, 0.2, 0.8, 0.1);
        world.spawnParticle(Particle.CLOUD, center, 15, 1.2, 0.05, 1.2, 0.02);

        for (int i = 0; i < 12; i++) {
            double angle = (2 * Math.PI / 12) * i;
            Location ringLoc = center.clone().add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, ringLoc, 1, 0.1, 0.05, 0.1, 0.01);
        }

        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.15, 0), 20,
                1.2, 0.3, 1.2, 0.2, Material.STONE.createBlockData());
    }

    /**
     * 中档猛击：爆炸 + 双环冲击波 + 碎石 + 烟雾柱 + 火花
     */
    public static void spawnMediumTierEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0, 0.15, 0);

        world.spawnParticle(Particle.EXPLOSION_LARGE, center, 2, 0.3, 0.3, 0.3, 0);
        world.spawnParticle(Particle.EXPLOSION_NORMAL, center, 10, 1.5, 0.3, 1.5, 0.3);

        for (int i = 0; i < 35; i++) {
            double angle = (2 * Math.PI / 35) * i;
            double dist = 3.5;
            Location ringLoc = center.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            world.spawnParticle(Particle.CLOUD, ringLoc, 2, 0.2, 0.05, 0.2, 0.02);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, ringLoc, 1, 0.1, 0.1, 0.1, 0.01);
        }

        for (int i = 0; i < 20; i++) {
            double angle = (2 * Math.PI / 20) * i;
            Location ringLoc = center.clone().add(Math.cos(angle) * 1.8, 0.05, Math.sin(angle) * 1.8);
            world.spawnParticle(Particle.FLAME, ringLoc, 1, 0.1, 0.1, 0.1, 0.02);
        }

        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.2, 0), 60,
                2.5, 0.5, 2.5, 0.3, Material.STONE.createBlockData());
        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.2, 0), 30,
                2.0, 0.5, 2.0, 0.2, Material.COBBLESTONE.createBlockData());

        for (int y = 0; y < 5; y++) {
            double yOff = y * 0.4;
            double spread = 0.3 + y * 0.25;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center.clone().add(0, yOff, 0),
                    8, spread, 0.1, spread, 0.01);
        }
        world.spawnParticle(Particle.EXPLOSION_LARGE, center.clone().add(0, 1.5, 0),
                10, 0.8, 0.5, 0.8, 0.05);

        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 25, 1.0, 0.3, 1.0, 0.1);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 0.8, 0), 15, 0.6, 0.4, 0.6, 0.08);

        world.spawnParticle(Particle.REDSTONE, center, 40, 2.0, 0.05, 2.0, 0,
                new Particle.DustOptions(Color.fromRGB(120, 100, 70), 1.5f));
    }

    /**
     * 高档猛击：巨型爆炸 + 多环扩散 + 闪电 + 龙息 + 火焰旋涡
     */
    public static void spawnHighTierEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0, 0.15, 0);

        world.spawnParticle(Particle.EXPLOSION_LARGE, center, 5, 0.5, 0.5, 0.5, 0);
        world.spawnParticle(Particle.EXPLOSION_NORMAL, center, 20, 2.5, 0.5, 2.5, 0.4);

        // 三层环形冲击波
        for (int ring = 0; ring < 3; ring++) {
            double dist = 2.0 + ring * 2.5;
            int count = 20 + ring * 15;
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI / count) * i;
                Location ringLoc = center.clone().add(Math.cos(angle) * dist, ring * 0.15, Math.sin(angle) * dist);
                world.spawnParticle(Particle.CLOUD, ringLoc, 2, 0.3, 0.1, 0.3, 0.03);
                world.spawnParticle(ring == 0 ? Particle.FLAME : Particle.CAMPFIRE_COSY_SMOKE,
                        ringLoc, 2, 0.2, 0.1, 0.2, 0.03);
            }
        }

        // 大量碎石
        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.3, 0), 100,
                4.0, 0.8, 4.0, 0.4, Material.STONE.createBlockData());
        world.spawnParticle(Particle.BLOCK_CRACK, center.clone().add(0, 0.3, 0), 60,
                3.5, 0.8, 3.5, 0.3, Material.DEEPSLATE.createBlockData());

        // 上扬烟雾柱 (更高更密)
        for (int y = 0; y < 8; y++) {
            double yOff = y * 0.5;
            double spread = 0.4 + y * 0.3;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center.clone().add(0, yOff, 0),
                    12, spread, 0.1, spread, 0.01);
        }
        world.spawnParticle(Particle.EXPLOSION_LARGE, center.clone().add(0, 2.5, 0),
                15, 1.2, 0.8, 1.2, 0.05);

        // 闪电
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 50, 2.0, 0.5, 2.0, 0.15);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 1.5, 0), 30, 1.5, 0.8, 1.5, 0.1);

        // 地面尘土
        world.spawnParticle(Particle.REDSTONE, center, 80, 3.0, 0.05, 3.0, 0,
                new Particle.DustOptions(Color.fromRGB(120, 100, 70), 2.0f));

        // 火焰旋涡
        for (int i = 0; i < 30; i++) {
            double angle = (2 * Math.PI / 30) * i;
            double dist = 1.2;
            Location flameLoc = center.clone().add(Math.cos(angle) * dist, 0.3, Math.sin(angle) * dist);
            world.spawnParticle(Particle.FLAME, flameLoc, 2, 0.3, 0.2, 0.3, 0.04);
        }

        // 龙息残留
        world.spawnParticle(Particle.DRAGON_BREATH, center.clone().add(0, 0.1, 0),
                5, 1.0, 0.05, 1.0, 0.01);
    }
}
