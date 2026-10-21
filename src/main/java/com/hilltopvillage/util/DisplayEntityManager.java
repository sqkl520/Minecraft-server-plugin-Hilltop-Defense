package com.hilltopvillage.util;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * 节点视觉特效工具（节点修复 / 节点摧毁粒子）。
 *
 * <p>fix6 Y4-A2：原类中的锤击巨物展示与 DisplayEntity 清理队列整链为零调用死代码，
 * 已随本轮删除；类语义收窄为纯粒子工具，不再持有插件引用。</p>
 */
public class DisplayEntityManager {

    public void spawnNodeRepairEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0.5, 1.5, 0.5);

        for (int i = 0; i < 8; i++) {
            double angle = (2 * Math.PI / 8) * i;
            double x = Math.cos(angle) * 0.8;
            double z = Math.sin(angle) * 0.8;

            world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER,
                    center.clone().add(x, 0, z), 1, 0, 0, 0, 0);
        }

        world.spawnParticle(org.bukkit.Particle.ENCHANT,
                center, 20, 0.5, 0.5, 0.5, 0);
    }

    public void spawnNodeDestroyEffect(Location location) {
        World world = location.getWorld();
        Location center = location.clone().add(0.5, 0.5, 0.5);

        world.spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER, center, 1, 0, 0, 0, 0);
        world.spawnParticle(org.bukkit.Particle.FLAME, center, 20, 0.5, 0.5, 0.5, 0.02);
    }
}
