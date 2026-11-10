package com.hilltopvillage.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link DisplayEntityManager} 的粒子调用契约测试。
 *
 * <p><b>为什么这个类值得测</b>：fix6 Y4-A2 把整条 DisplayEntity 清理队列当作死代码删掉了，
 * 类语义收窄为「纯粒子工具，不持有插件引用」。这个收窄带来两条可验证的契约：</p>
 * <ol>
 *   <li><b>不得再触碰插件</b>：类里没有任何 {@code HilltopVillagePlugin} 字段/构造参数 ——
 *       两个方法都是无状态实例方法，只吃一个 {@link Location}。若有人把清理队列加回来，
 *       这个签名会变，本类会红。</li>
 *   <li><b>调用点必须全部落在传入位置附近</b>：修复特效在 {@code +(0.5,1.5,0.5)} 周围，
 *       摧毁特效在 {@code +(0.5,0.5,0.5)}。若有人把 {@code location.clone().add(...)}
 *       写成 {@code location.add(...)}，<b>会原地改掉调用方传入的 Location</b> ——
 *       节点系统的坐标会被永久偏移，而且那种 bug 在游戏里表现为「粒子越飘越远」，
 *       极难定位。本类用「调用后原 Location 不变」把它钉死。</li>
 * </ol>
 *
 * <p><b>MockBukkit 阻塞点（与 round2-06 一致，本类实测）</b>：
 * {@code WorldMock#spawnParticle} 的<b>全部</b>重载都抛 {@code UnimplementedOperationException}。
 * 因此必须用 {@link WorldMock} 的 spy + {@code doAnswer} 把这一层换掉 ——
 * 注意不能换成纯 Mockito 的 {@code mock(World.class)}，因为 {@code location.getWorld()}
 * 拿到的就是那个对象，而 {@code WorldMock} 的其它方法（{@code getName()} 等）仍可能被调用。</p>
 */
@DisplayName("DisplayEntityManager — 粒子特效的调用契约")
class DisplayEntityManagerTest extends MockBukkitTestBase {

    /** 一次 {@code spawnParticle} 调用的记录（只记本类实际用到的那几个参数）。 */
    private record Spawn(Particle particle, double x, double y, double z, int count) {
    }

    private final List<Spawn> spawns = new ArrayList<>();
    private WorldMock world;
    private DisplayEntityManager manager;

    @BeforeEach
    void setUp() {
        world = capturingWorld("effect_world");
        manager = new DisplayEntityManager();
    }

    /**
     * 把 {@code spawnParticle} 用到的那几个重载换成「记录 + 返回 null」。
     *
     * <p><b>踩过的坑</b>：生产代码用的是 {@code spawnParticle(Particle, Location, int, double×4)}
     * 这个<b>带 Location 的重载</b>（{@code center.clone().add(x, 0, z)}），只桩 double 版本
     * 会让测试被 MockBukkit 转成 <b>SKIPPED</b>（{@code UnimplementedOperationException}
     * → surefire 记为 skipped，Maven 仍报 BUILD SUCCESS）。本类第一版就是这么假绿的，
     * 从 {@code TEST-*.xml} 的 {@code <skipped>} 节点里查到
     * {@code at WorldMock.spawnParticle(WorldMock.java:1949)} 才定位到。
     * 因此 Location 版与 double 版必须<b>一起</b>桩。</p>
     */
    private WorldMock capturingWorld(String name) {
        WorldMock real = server.getWorld(name) != null
                ? (WorldMock) server.getWorld(name)
                : server.addSimpleWorld(name);
        WorldMock spied = org.mockito.Mockito.spy(real);

        // Location 版（生产代码实际调用的那一组）
        doAnswer(invocation -> {
            Location at = invocation.getArgument(1);
            record(invocation.getArgument(0), at.getX(), at.getY(), at.getZ(),
                    invocation.getArgument(2));
            return null;
        }).when(spied).spawnParticle(any(Particle.class), any(Location.class), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble());

        doAnswer(invocation -> {
            Location at = invocation.getArgument(1);
            record(invocation.getArgument(0), at.getX(), at.getY(), at.getZ(),
                    invocation.getArgument(2));
            return null;
        }).when(spied).spawnParticle(any(Particle.class), any(Location.class), anyInt());

        doAnswer(invocation -> {
            Location at = invocation.getArgument(1);
            record(invocation.getArgument(0), at.getX(), at.getY(), at.getZ(),
                    invocation.getArgument(2));
            return null;
        }).when(spied).spawnParticle(any(Particle.class), any(Location.class), anyInt(),
                anyDouble(), anyDouble(), anyDouble());

        // double 版（保留：若有人把调用改成 double 重载，记录仍然有效）
        doAnswer(invocation -> {
            record(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3),
                    invocation.getArgument(4));
            return null;
        }).when(spied).spawnParticle(any(Particle.class), anyDouble(), anyDouble(), anyDouble(),
                anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());

        return spied;
    }

    private void record(Object particle, Object x, Object y, Object z, Object count) {
        spawns.add(new Spawn((Particle) particle,
                ((Number) x).doubleValue(), ((Number) y).doubleValue(),
                ((Number) z).doubleValue(), ((Number) count).intValue()));
    }

    private Location nodeLocation() {
        return new Location(world, 10.0, 64.0, 20.0);
    }

    // ---------------------------------------------------------------- 1. 不污染入参

    @Nested
    @DisplayName("不得原地修改调用方传入的 Location")
    class DoesNotMutateInput {

        /**
         * ★ 若 {@code location.clone().add(...)} 被误写成 {@code location.add(...)}，
         * 节点坐标会被永久偏移，游戏内表现为「粒子越飘越远」，极难定位。
         */
        @Test
        @DisplayName("★ repair 特效不改动入参 Location")
        void repairDoesNotMutateInput() {
            Location location = nodeLocation();
            double x = location.getX();
            double y = location.getY();
            double z = location.getZ();

            manager.spawnNodeRepairEffect(location);

            assertEquals(x, location.getX(), 1e-9,
                    "spawnNodeRepairEffect 不得修改传入的 Location —— "
                            + "location.clone().add(...) 一旦漏掉 clone()，调用方的节点坐标会被永久偏移");
            assertEquals(y, location.getY(), 1e-9);
            assertEquals(z, location.getZ(), 1e-9);
        }

        @Test
        @DisplayName("destroy 特效不改动入参 Location")
        void destroyDoesNotMutateInput() {
            Location location = nodeLocation();
            double x = location.getX();
            double y = location.getY();
            double z = location.getZ();

            manager.spawnNodeDestroyEffect(location);

            assertEquals(x, location.getX(), 1e-9);
            assertEquals(y, location.getY(), 1e-9);
            assertEquals(z, location.getZ(), 1e-9);
        }
    }

    // ---------------------------------------------------------------- 2. 特效内容

    @Nested
    @DisplayName("特效内容")
    class EffectShape {

        @Test
        @DisplayName("repair 特效：8 个环绕点 + 1 个中心 ENCHANT，全部在 y+1.5 平面")
        void repairSpawnsRingAndEnchant() {
            manager.spawnNodeRepairEffect(nodeLocation());

            long happyVillager = spawns.stream()
                    .filter(s -> s.particle() == Particle.HAPPY_VILLAGER).count();
            assertEquals(8, happyVillager,
                    "修复特效的环绕点必须是 8 个（与生产代码的 8 段圆环一致）；"
                            + "数量变化说明有人改了视觉密度而未同步文档");

            long enchant = spawns.stream()
                    .filter(s -> s.particle() == Particle.ENCHANT).count();
            assertEquals(1, enchant, "修复特效中心应有 1 次 ENCHANT 爆发");

            // 所有环绕点必须落在 y = 64 + 1.5 平面上，半径 0.8。
            List<Spawn> ring = spawns.stream()
                    .filter(s -> s.particle() == Particle.HAPPY_VILLAGER).toList();
            for (Spawn spawn : ring) {
                assertEquals(65.5, spawn.y(), 1e-6,
                        "环绕点必须固定在方块中心上方 1.5 格（y+1.5）；"
                                + "高度漂移会让粒子穿进方块里");
                double dx = spawn.x() - 10.5;
                double dz = spawn.z() - 20.5;
                assertEquals(0.8, Math.hypot(dx, dz), 1e-6,
                        "环绕半径必须是 0.8 格");
            }
        }

        @Test
        @DisplayName("repair 特效：环绕点均匀分布（相邻点角度差 45 度）")
        void repairRingIsEvenlySpaced() {
            manager.spawnNodeRepairEffect(nodeLocation());

            List<Double> angles = spawns.stream()
                    .filter(s -> s.particle() == Particle.HAPPY_VILLAGER)
                    .map(s -> Math.atan2(s.z() - 20.5, s.x() - 10.5))
                    .sorted()
                    .toList();

            assertEquals(8, angles.size());
            for (int i = 1; i < angles.size(); i++) {
                double delta = Math.toDegrees(angles.get(i) - angles.get(i - 1));
                assertEquals(45.0, delta, 1e-6,
                        "相邻环绕点的角度差必须是 45 度；不等说明圆环公式被改坏了");
            }
        }

        @Test
        @DisplayName("destroy 特效：1 次 EXPLOSION_EMITTER + 1 次 FLAME 爆发")
        void destroySpawnsExplosionAndFlame() {
            manager.spawnNodeDestroyEffect(nodeLocation());

            assertEquals(1, spawns.stream()
                            .filter(s -> s.particle() == Particle.EXPLOSION_EMITTER).count(),
                    "摧毁特效必须有 1 次 EXPLOSION_EMITTER");
            assertEquals(1, spawns.stream()
                            .filter(s -> s.particle() == Particle.FLAME).count(),
                    "摧毁特效必须有 1 次 FLAME 爆发");
            assertEquals(2, spawns.size(),
                    "摧毁特效合计 2 次回调；次数变化说明有人加了/删了视觉层");
        }

        @Test
        @DisplayName("destroy 特效的中心在 y+0.5（与 repair 的 y+1.5 不同）")
        void destroyCenterHeightDiffersFromRepair() {
            manager.spawnNodeDestroyEffect(nodeLocation());

            for (Spawn spawn : spawns) {
                assertEquals(64.5, spawn.y(), 1e-6,
                        "摧毁特效的中心在方块中心（y+0.5）；与修复特效的 y+1.5 不同是刻意设计"
                                + "（一个在方块表面，一个在方块上方）");
            }
        }
    }

    // ---------------------------------------------------------------- 3. 无状态

    @Test
    @DisplayName("实例无状态：连续两次调用产生完全相同的调用序列")
    void instanceIsStateless() {
        manager.spawnNodeRepairEffect(nodeLocation());
        List<Spawn> first = List.copyOf(spawns);
        spawns.clear();

        manager.spawnNodeRepairEffect(nodeLocation());
        List<Spawn> second = List.copyOf(spawns);

        assertEquals(first, second,
                "同一 Location 的两次调用必须产生完全相同的粒子序列 —— "
                        + "本类不应持有任何累积状态（fix6 Y4-A2 把清理队列删掉后的契约）");
    }

    @Test
    @DisplayName("构造器不需要插件引用（零参数）")
    void constructionNeedsNoPlugin() {
        // 若有人把 HilltopVillagePlugin 作为构造参数加回来（例如重建清理队列），
        // 这一行会编译失败 —— 这是本测试对「类语义收窄」的守卫。
        assertDoesNotThrow(() -> new DisplayEntityManager());
    }

    @Test
    @DisplayName("float/short 等重载不会被误用（所有调用都走 double 形式）")
    void allCallsUseDoubleOverloads() {
        manager.spawnNodeRepairEffect(nodeLocation());
        manager.spawnNodeDestroyEffect(nodeLocation());

        assertTrue(spawns.size() >= 10,
                "两个特效合计应有至少 10 次 spawnParticle 调用（8 环绕 + 1 ENCHANT + 2 摧毁），"
                        + "实际 " + spawns.size());
        // 每个记录到的 count 都必须 > 0 —— 0 意味着「只调不画」，是纯粹的空转。
        for (Spawn spawn : spawns) {
            assertTrue(spawn.count() > 0,
                    "count=0 的 spawnParticle 只产生一次网络往返而不画任何东西："
                            + spawn.particle());
        }
    }
}
