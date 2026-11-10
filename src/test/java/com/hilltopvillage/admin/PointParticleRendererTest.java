package com.hilltopvillage.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link PointParticleRenderer} 的几何与调用形态测试。
 *
 * <p>该类的输出全是「往世界里撒粒子」，没有返回值，很容易被当成不可测。但它的<b>调用形态</b>
 * 与<b>坐标</b>都是纯函数式可断言的：把 {@code World} 换成 Mockito mock 就能逐次捕获
 * (x, y, z, count, dust)。这也正好是类注释里那段「为什么保留逐点循环、不做大 offset 合并」
 * 的技术结论唯一可被回归守护的地方 —— 若有人按旧审计建议把它合并成一次调用，
 * 粒子数会从 51 掉到 1，本类立刻变红。</p>
 *
 * <p><b>不需要 MockBukkit</b>：{@code World} 与 {@code Location} 都可以直接 mock/构造，
 * 该类不触碰 {@code Bukkit} 单例。</p>
 */
class PointParticleRendererTest {

    /** 捕获一次 spawnParticle 调用的全部坐标与粒子数据。 */
    private record Spawn(double x, double y, double z, int count, Particle.DustOptions dust) { }

    private final List<Spawn> spawns = new ArrayList<>();

    private World capturingWorld() {
        World world = mock(World.class);
        doAnswer(invocation -> {
            spawns.add(new Spawn(
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(9)));
            return null;
        }).when(world).spawnParticle(eq(Particle.DUST), anyDouble(), anyDouble(), anyDouble(),
                eq(1), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(Particle.DustOptions.class));
        return world;
    }

    private World plainWorld() {
        return mock(World.class);
    }

    @Nested
    @DisplayName("垂直光柱")
    class Beam {

        @Test
        @DisplayName("从点位向上撒满 20 格、间距 0.4，共 51 颗")
        void beamCoversTwentyBlocksAtFixedSpacing() {
            World world = capturingWorld();
            PointParticleRenderer.drawMarker(world, new Location(world, 10, 64, 10), PointKind.NODE, 0.0, 0);

            List<Spawn> beam = beamOnly();
            assertEquals(51, beam.size(),
                    "光柱必须是逐点循环的 51 颗（y=0..20 步长 0.4）。"
                            + "旧审计建议「合并成一次 count=51 的调用」，那会让这里变成 1 —— "
                            + "但 offset 在高斯模式下是标准差 σ，σ=10 会让约 32% 粒子溢出柱外、"
                            + "地面起点消失，与逐点柱视觉不一致");
            assertEquals(64.0, beam.get(0).y(), 1e-9, "第一颗必须落在点位方块高度（地面起点不能丢）");
            assertEquals(84.0, beam.get(beam.size() - 1).y(), 1e-9, "最后一颗在 y+20");
            for (int i = 1; i < beam.size(); i++) {
                assertEquals(0.4, beam.get(i).y() - beam.get(i - 1).y(), 1e-9,
                        "第 " + i + " 颗与上一颗的间距必须是 0.4");
            }
        }

        @Test
        @DisplayName("水平坐标 +0.5 居中（方块中心而不是方块角）")
        void beamIsCenteredOnTheBlock() {
            World world = capturingWorld();
            PointParticleRenderer.drawMarker(world, new Location(world, 10, 64, -7), PointKind.SPAWN, 0.0, 0);

            for (Spawn spawn : beamOnly()) {
                assertEquals(10.5, spawn.x(), 1e-9, "x 必须 +0.5 居中");
                assertEquals(-6.5, spawn.z(), 1e-9, "z 必须 +0.5 居中（负坐标同样适用）");
            }
        }

        @Test
        @DisplayName("每颗用 count=1 且不带 offset（逐点模式，不是散布）")
        void beamUsesSingleParticleWithoutOffset() {
            World world = capturingWorld();
            PointParticleRenderer.drawMarker(world, new Location(world, 0, 0, 0), PointKind.SHOP, 0.0, 0);

            assertTrue(spawns.stream().allMatch(spawn -> spawn.count() == 1),
                    "每颗必须是 count=1；count>1 意味着服务端按高斯散布展开，柱子会变模糊");
            org.mockito.Mockito.verify(world, atLeastOnce()).spawnParticle(
                    eq(Particle.DUST), anyDouble(), anyDouble(), anyDouble(), eq(1),
                    eq(0.0), eq(0.0), eq(0.0), eq(0.0), any(Particle.DustOptions.class));
        }

        @Test
        @DisplayName("光柱用 beamDust，螺旋用 spiralDust（各取各的尺寸）")
        void beamAndSpiralUseTheirOwnDustOptions() {
            World world = capturingWorld();
            PointParticleRenderer.drawMarker(world, new Location(world, 0, 0, 0), PointKind.VILLAGER, 0.0, 30);

            List<Spawn> beam = beamOnly();
            List<Spawn> spiral = spawns.subList(beam.size(), spawns.size());

            assertTrue(beam.stream().allMatch(s -> s.dust() == PointKind.VILLAGER.beamDust()),
                    "光柱必须用 beamDust（尺寸 1.0）");
            assertTrue(spiral.stream().allMatch(s -> s.dust() == PointKind.VILLAGER.spiralDust()),
                    "螺旋必须用 spiralDust（尺寸 1.3），否则两层装饰的尺寸差消失");
        }
    }

    @Nested
    @DisplayName("环绕螺旋")
    class Spiral {

        @Test
        @DisplayName("每帧 3 颗，半径恒为 1.2 格")
        void spiralHasThreePointsAtFixedRadius() {
            World world = capturingWorld();
            PointParticleRenderer.drawMarker(world, new Location(world, 100, 64, 100), PointKind.MOB_SPAWN, 0.0, 0);

            List<Spawn> spiral = spawns.subList(beamOnly().size(), spawns.size());
            assertEquals(3, spiral.size(), "螺旋每帧固定 3 颗装饰粒子");

            for (Spawn spawn : spiral) {
                double dx = spawn.x() - 100.5;
                double dz = spawn.z() - 100.5;
                assertEquals(1.2, Math.hypot(dx, dz), 1e-9,
                        "每颗螺旋粒子到点位中心的水平距离必须正好是 1.2 格");
            }
        }

        @Test
        @DisplayName("3 颗两两相隔 120°，不是重叠在一点")
        void spiralPointsAreEquallySpacedOnTheCircle() {
            World world = capturingWorld();
            PointParticleRenderer.drawMarker(world, new Location(world, 0, 64, 0), PointKind.NODE, 0.0, 0);

            List<Spawn> spiral = spawns.subList(beamOnly().size(), spawns.size());
            List<Double> angles = spiral.stream()
                    .map(spawn -> Math.toDegrees(Math.atan2(spawn.z() - 0.5, spawn.x() - 0.5)))
                    .map(angle -> (angle + 360.0) % 360.0)
                    .sorted()
                    .toList();

            assertEquals(0.0, angles.get(0), 1e-6, "angle=0 时第一颗在 +x 方向");
            assertEquals(120.0, angles.get(1), 1e-6);
            assertEquals(240.0, angles.get(2), 1e-6);
        }

        @Test
        @DisplayName("螺旋按 3 秒一轮爬升：每 tick 上升 0.05 格，到 60 tick 归零")
        void spiralClimbsOncePerThreeSeconds() {
            World world = capturingWorld();
            Location origin = new Location(world, 0, 64, 0);
            int beamSize = 51;

            for (int ticks : new int[]{0, 15, 30, 59}) {
                spawns.clear();
                PointParticleRenderer.drawMarker(world, origin, PointKind.NODE, 0.0, ticks);
                double expectedY = 64.0 + ticks * (3.0 / 60.0);
                List<Spawn> spiral = spawns.subList(beamSize, spawns.size());
                assertTrue(spiral.stream().allMatch(s -> Math.abs(s.y() - expectedY) < 1e-9),
                        "ticksElapsed=" + ticks + " 时螺旋高度应为 " + expectedY
                                + "（SPIRAL_HEIGHT 3.0 / 60 tick 一轮）");
            }

            // 60 tick 后归零 —— 证明取模，而不是无限爬升
            spawns.clear();
            PointParticleRenderer.drawMarker(world, origin, PointKind.NODE, 0.0, 60);
            assertTrue(spawns.subList(beamSize, spawns.size()).stream()
                            .allMatch(s -> Math.abs(s.y() - 64.0) < 1e-9),
                    "ticksElapsed % 60 必须让第 60 tick 回到 y+0（3 秒一轮），"
                            + "否则螺旋会一路爬到天上");
        }

        @Test
        @DisplayName("angle 参数真的转动螺旋（绕点位中心 +0.5 镜像）")
        void angleRotatesTheSpiral() {
            World world = capturingWorld();
            Location origin = new Location(world, 0, 64, 0);

            spawns.clear();
            PointParticleRenderer.drawMarker(world, origin, PointKind.NODE, 0.0, 0);
            Spawn atZero = spawns.get(51);

            spawns.clear();
            PointParticleRenderer.drawMarker(world, origin, PointKind.NODE, Math.PI, 0);
            Spawn atPi = spawns.get(51);

            assertEquals(0.5 + 1.2, atZero.x(), 1e-9,
                    "angle=0 时第一颗在中心 +x 方向 1.2 格处（中心 = 方块中心 0.5）");
            assertNotEquals(atZero.x(), atPi.x(), 1e-6, "angle 转 180° 后 x 必须变号");
            // 以方块中心 +0.5 为镜像轴，而不是以原点
            assertEquals(1.0, atZero.x() + atPi.x(), 1e-9,
                    "转 180° 后两颗必须关于中心 0.5 对称（x0 + xPI == 1.0）");
            assertEquals(1.0, atZero.z() + atPi.z(), 1e-9);
            // 高度不受 angle 影响（角度只转水平面）
            assertEquals(atZero.y() + 0.0, atPi.y(), 1e-9);
        }
    }

    @Nested
    @DisplayName("整体形态")
    class Whole {

        @Test
        @DisplayName("每次渲染固定 51 + 3 = 54 次调用，与类别无关")
        void callCountIsIndependentOfKind() {
            for (PointKind kind : PointKind.values()) {
                World world = plainWorld();
                PointParticleRenderer.drawMarker(world, new Location(world, 0, 0, 0), kind, 0, 0);
                verify(world, times(54)).spawnParticle(
                        eq(Particle.DUST), anyDouble(), anyDouble(), anyDouble(), eq(1),
                        anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                        any(Particle.DustOptions.class));
            }
        }

        @Test
        @DisplayName("只画 DUST，不混入其它粒子类型")
        void onlyDustIsSpawned() {
            World world = capturingWorld();
            PointParticleRenderer.drawMarker(world, new Location(world, 0, 0, 0), PointKind.NODE, 0.0, 0);

            assertTrue(spawns.stream().allMatch(spawn -> spawn.dust() != null),
                    "每一次调用都必须带 DustOptions —— 不带数据时 DUST 会以默认白色渲染，"
                            + "五类配色全部失效");
            assertEquals(54, spawns.size());
        }

        @Test
        @DisplayName("世界未加载时不触碰任何方块或实体 API（只用 spawnParticle）")
        void onlyTouchesSpawnParticle() {
            World world = plainWorld();
            PointParticleRenderer.drawMarker(world, new Location(world, 0, 0, 0), PointKind.NODE, 0.0, 0);

            // 反向证明：本类不读世界状态，因此世界对象只需能接 spawnParticle
            verify(world, never()).getBlockAt(any(Location.class));
            verify(world, never()).getEntities();
        }
    }

    // ------------------------------------------------------------------ 夹具

    /** 前 51 颗是光柱（y 递增步长 0.4），其后是螺旋。 */
    private List<Spawn> beamOnly() {
        assertTrue(spawns.size() >= 51, "至少要有 51 颗光柱粒子，实际 " + spawns.size());
        return spawns.subList(0, 51);
    }
}
