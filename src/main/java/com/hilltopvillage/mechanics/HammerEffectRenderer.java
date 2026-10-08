package com.hilltopvillage.mechanics;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;

/**
 * 猛击（锤子）三档特效的粒子渲染。
 *
 * <h2>本类的性能改动与取舍</h2>
 *
 * <p><b>零视觉代价的部分（本类的主要收益）：</b>全部改用带 {@code double x, y, z} 的
 * {@code World#spawnParticle} 重载。原实现每次调用都先 {@code center.clone().add(...)}
 * 造一个 {@link Location}（含一次 {@code Vector} 运算与一次 {@code World} 引用写入），
 * 高档一次猛击要造 467 个；改用 double 重载后归零。同时把三份恒定的
 * {@link Material#createBlockData()} 提为静态常量，每次猛击少建 5 个对象。</p>
 *
 * <p><b>有视觉代价的部分（环形点位削减）：</b>环上点位数量与相邻点位的弧长间距成反比，
 * 而间距决定环看起来是连续的还是一串珠子。原实现与现在的等效间距如下：</p>
 *
 * <pre>
 *   环位置                     半径   原点数  原间距   现点数  现间距
 *   低档烟雾环                 1.5     12     0.79     8      1.18
 *   中档内环（FLAME）          1.8     20     0.57    10      1.13
 *   中档外环（CLOUD+烟）       3.5     35     0.63    18      1.22
 *   高档第 1 环                2.0     20     0.63    10      1.26
 *   高档第 2 环                4.5     35     0.81    18      1.57
 *   高档第 3 环                7.0     50     0.88    26      1.69
 *   高档火焰旋涡               1.2     30     0.25    15      0.50
 * </pre>
 *
 * <p>结论：高档第 2/3 环与低档环的间距被拉到 1.2–1.7 格，肉眼会看出点位变稀；
 * 火焰旋涡（0.50 格）与中档内环（1.13 格）间距仍远小于点位自身的扩散范围，看不出差别。
 * 这是「少发包」与「环更密」之间的取舍，已列入实测单由服主目视确认。</p>
 *
 * <p><b>粒子总数一律不减少</b>：点位减半的同时单点 {@code count} 翻倍，三档合计粒子数
 * 从 1348 增到 1367（低 50→54、中 357→360、高 941→953），视觉总密度不降反升；
 * 调用次数则从 375 降到 210（低 15→11、中 103→59、高 257→140，高档降 45%）。</p>
 */
public class HammerEffectRenderer {

    /**
     * 复用 BlockData：{@link Material#createBlockData()} 每次调用都会经
     * {@code Bukkit.createBlockData()} 新建一个 {@code CraftBlockData} 对象，
     * 而这三份材质是恒定的。砸地时高/中/低三档合计每次要新建 5 个对象，
     * 提到静态常量后整个服务器生命周期只建一次。
     *
     * <p>安全性：{@code CraftParticle} 的 BlockData 分支只读 {@code CraftBlockData#getState()}
     * 并构造不可变的 {@code BlockParticleOption}，不会写回；常量又是 {@code private} 的，
     * 外部拿不到引用去改。</p>
     */
    private static final BlockData STONE_DATA = Material.STONE.createBlockData();
    private static final BlockData COBBLESTONE_DATA = Material.COBBLESTONE.createBlockData();
    private static final BlockData DEEPSLATE_DATA = Material.DEEPSLATE.createBlockData();

    /**
     * 低档猛击：轻量尘土 + 小范围烟雾环
     */
    public static void spawnLowTierEffect(Location location) {
        World world = location.getWorld();
        // 中心点拆成三个 double：改用 double 重载后不再需要任何 Location 分配
        final double cx = location.getX();
        final double cy = location.getY() + 0.1;
        final double cz = location.getZ();

        world.spawnParticle(Particle.EXPLOSION, cx, cy, cz, 3, 0.8, 0.2, 0.8, 0.1);
        world.spawnParticle(Particle.CLOUD, cx, cy, cz, 15, 1.2, 0.05, 1.2, 0.02);

        // 烟雾环：点位 12→8、单点 count 1→2
        // 粒子总数 12→16（略增，环反而更实），调用次数 12→8
        for (int i = 0; i < 8; i++) {
            double angle = (2 * Math.PI / 8) * i;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                    cx + Math.cos(angle) * 1.5, cy, cz + Math.sin(angle) * 1.5,
                    2, 0.1, 0.05, 0.1, 0.01);
        }

        world.spawnParticle(Particle.BLOCK, cx, cy + 0.15, cz, 20, 1.2, 0.3, 1.2, 0.2, STONE_DATA);
    }

    /**
     * 中档猛击：爆炸 + 双环冲击波 + 碎石 + 烟雾柱 + 火花
     */
    public static void spawnMediumTierEffect(Location location) {
        World world = location.getWorld();
        final double cx = location.getX();
        final double cy = location.getY() + 0.15;
        final double cz = location.getZ();

        world.spawnParticle(Particle.EXPLOSION_EMITTER, cx, cy, cz, 2, 0.3, 0.3, 0.3, 0);
        world.spawnParticle(Particle.EXPLOSION, cx, cy, cz, 10, 1.5, 0.3, 1.5, 0.3);

        // 外环冲击波：点位 35→18、单点 count 翻倍（CLOUD 2→4、CAMPFIRE 1→2）
        // 粒子总数 105→108（略增），调用次数 70→36
        for (int i = 0; i < 18; i++) {
            double angle = (2 * Math.PI / 18) * i;
            double px = cx + Math.cos(angle) * 3.5;
            double pz = cz + Math.sin(angle) * 3.5;
            world.spawnParticle(Particle.CLOUD, px, cy, pz, 4, 0.2, 0.05, 0.2, 0.02);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, px, cy, pz, 2, 0.1, 0.1, 0.1, 0.01);
        }

        // 内环火花：点位 20→10、单点 count 1→2，粒子总数 20 不变，调用次数 20→10
        for (int i = 0; i < 10; i++) {
            double angle = (2 * Math.PI / 10) * i;
            world.spawnParticle(Particle.FLAME,
                    cx + Math.cos(angle) * 1.8, cy + 0.05, cz + Math.sin(angle) * 1.8,
                    2, 0.1, 0.1, 0.1, 0.02);
        }

        world.spawnParticle(Particle.BLOCK, cx, cy + 0.2, cz, 60, 2.5, 0.5, 2.5, 0.3, STONE_DATA);
        world.spawnParticle(Particle.BLOCK, cx, cy + 0.2, cz, 30, 2.0, 0.5, 2.0, 0.2, COBBLESTONE_DATA);

        // 上扬烟雾柱：每层 count 8 不变，只消除 5 次 Location 分配
        for (int y = 0; y < 5; y++) {
            double spread = 0.3 + y * 0.25;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, cx, cy + y * 0.4, cz,
                    8, spread, 0.1, spread, 0.01);
        }
        world.spawnParticle(Particle.EXPLOSION_EMITTER, cx, cy + 1.5, cz, 10, 0.8, 0.5, 0.8, 0.05);

        world.spawnParticle(Particle.ELECTRIC_SPARK, cx, cy, cz, 25, 1.0, 0.3, 1.0, 0.1);
        world.spawnParticle(Particle.ELECTRIC_SPARK, cx, cy + 0.8, cz, 15, 0.6, 0.4, 0.6, 0.08);

        // DUST 需要 DustOptions 作为 data，本来就只有一次调用，保持原样
        world.spawnParticle(Particle.DUST, cx, cy, cz, 40, 2.0, 0.05, 2.0, 0,
                new Particle.DustOptions(Color.fromRGB(120, 100, 70), 1.5f));
    }

    /**
     * 高档猛击：巨型爆炸 + 多环扩散 + 闪电 + 龙息 + 火焰旋涡
     */
    public static void spawnHighTierEffect(Location location) {
        World world = location.getWorld();
        final double cx = location.getX();
        final double cy = location.getY() + 0.15;
        final double cz = location.getZ();

        world.spawnParticle(Particle.EXPLOSION_EMITTER, cx, cy, cz, 5, 0.5, 0.5, 0.5, 0);
        world.spawnParticle(Particle.EXPLOSION, cx, cy, cz, 20, 2.5, 0.5, 2.5, 0.4);

        // 三层环形冲击波：点位减半、单点 count 2→4 → 调用次数减半，粒子总数不变
        // （原来每层 20/35/50 个点、每点 2 次调用 = 210 次；现在 10/18/26 个点 = 108 次）
        // offset 参数一律保持原值，扩散范围不变，只是把「多点少粒子」换成「少点多粒子」
        for (int ring = 0; ring < 3; ring++) {
            double dist = 2.0 + ring * 2.5;
            int points = 10 + ring * 8;
            double yOff = cy + ring * 0.15;
            Particle outer = ring == 0 ? Particle.FLAME : Particle.CAMPFIRE_COSY_SMOKE;
            for (int i = 0; i < points; i++) {
                double angle = (2 * Math.PI / points) * i;
                double px = cx + Math.cos(angle) * dist;
                double pz = cz + Math.sin(angle) * dist;
                world.spawnParticle(Particle.CLOUD, px, yOff, pz, 4, 0.3, 0.1, 0.3, 0.03);
                world.spawnParticle(outer, px, yOff, pz, 4, 0.2, 0.1, 0.2, 0.03);
            }
        }

        // 大量碎石
        world.spawnParticle(Particle.BLOCK, cx, cy + 0.3, cz, 100, 4.0, 0.8, 4.0, 0.4, STONE_DATA);
        world.spawnParticle(Particle.BLOCK, cx, cy + 0.3, cz, 60, 3.5, 0.8, 3.5, 0.3, DEEPSLATE_DATA);

        // 上扬烟雾柱 (更高更密)：每层 count 12 不变，只消除 8 次 Location 分配
        for (int y = 0; y < 8; y++) {
            double spread = 0.4 + y * 0.3;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, cx, cy + y * 0.5, cz,
                    12, spread, 0.1, spread, 0.01);
        }
        world.spawnParticle(Particle.EXPLOSION_EMITTER, cx, cy + 2.5, cz, 15, 1.2, 0.8, 1.2, 0.05);

        // 闪电
        world.spawnParticle(Particle.ELECTRIC_SPARK, cx, cy, cz, 50, 2.0, 0.5, 2.0, 0.15);
        world.spawnParticle(Particle.ELECTRIC_SPARK, cx, cy + 1.5, cz, 30, 1.5, 0.8, 1.5, 0.1);

        // 地面尘土
        world.spawnParticle(Particle.DUST, cx, cy, cz, 80, 3.0, 0.05, 3.0, 0,
                new Particle.DustOptions(Color.fromRGB(120, 100, 70), 2.0f));

        // 火焰旋涡：点位 30→15、单点 count 2→4
        // 粒子总数 60 不变，调用次数 30→15。此处间距仅 0.50 格（原 0.25），
        // 远小于单点扩散范围，视觉上看不出差别
        for (int i = 0; i < 15; i++) {
            double angle = (2 * Math.PI / 15) * i;
            world.spawnParticle(Particle.FLAME,
                    cx + Math.cos(angle) * 1.2, cy + 0.3, cz + Math.sin(angle) * 1.2,
                    4, 0.3, 0.2, 0.3, 0.04);
        }

        // 龙息残留
        world.spawnParticle(Particle.DRAGON_BREATH, cx, cy + 0.1, cz, 5, 1.0, 0.05, 1.0, 0.01);
    }
}
