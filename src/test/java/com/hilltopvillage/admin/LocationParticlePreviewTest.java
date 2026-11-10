package com.hilltopvillage.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.testsupport.MockBukkitTestBase;
import com.hilltopvillage.util.LanguageManager;

/**
 * {@link LocationParticlePreview} 的行为测试：按玩家隔离的预览任务与逐点过滤。
 *
 * <p>这个类存在的理由本身就写在它的 javadoc 里：「为什么不复用 {@code AdminDeployManager}
 * 的预览方法 —— 那边 {@code showParticlesTaskId} 是单实例字段（两个玩家同时预览会互相打断）」。
 * 因此本类最重要的断言就是<b>两个玩家互不打断</b>：A 起预览、B 起预览后，A 的任务必须还活着。</p>
 *
 * <p>第二组断言是逐点的三个过滤器：世界不匹配、世界未加载、超过 64 格。三者任一失效的表现
 * 都是「粒子撒到别处 / 撒得满世界」，在游戏里都只是"有点怪"，很难被当成 bug 报上来。</p>
 *
 * <p><b>可测性边界</b>：{@code runTaskTimer} 在 MockBukkit 下是可调度的，
 * 所以本类用 {@code server.getScheduler().performTicks()} 真正驱动任务，而不是反射调 {@code run()}。
 * 但 {@code PlayerMock#sendMessage} 的文案无法在 4.45.0 下断言（无 message queue API），
 * 因此文案相关的断言退化为「是否调用过 lang」 —— 已在每处标注。</p>
 */
class LocationParticlePreviewTest extends MockBukkitTestBase {

    private HilltopVillagePlugin plugin;
    private LanguageManager lang;
    private LocationParticlePreview preview;
    private final List<Spawn> spawns = new ArrayList<>();

    private record Spawn(String worldName, double x, double y, double z) { }

    @BeforeEach
    void setUp() {
        spawns.clear();
        plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getName()).thenReturn("HilltopVillage");
        when(plugin.isEnabled()).thenReturn(true);
        lang = mock(LanguageManager.class);
        when(lang.getFor(any(), anyString())).thenReturn("x");
        // 可变参数重载必须用 any(Object[].class) 桩：Mockito 的 varargs 匹配在实参个数 != 1 时
        // 不会命中裸 any()，会静默返回 null —— 而 PlayerMock#sendMessage(null) 抛
        // IllegalArgumentException("message cannot be null")，报错点离真正的漏桩处很远。
        // （MechanicsFixtures 记录了同一个坑。）
        when(lang.getFor(any(), anyString(), any(Object[].class))).thenReturn("x");
        when(lang.getFor(any(), anyString(), any(), any())).thenReturn("x");
        when(plugin.getLanguageManager()).thenReturn(lang);
        preview = new LocationParticlePreview(plugin);
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 一个会记录 {@code spawnParticle} 的世界。
     *
     * <p><b>必须是 {@link WorldMock} 的 spy，不能是纯 Mockito mock。</b>
     * {@code EntityMock#getWorld()} 的实现是 {@code (WorldMock) getWorldMock()} —— 把一个
     * Mockito mock 的 {@code World} 塞进 {@code PlayerMock} 的 Location，第一次读
     * {@code viewer.getWorld()} 就会在 {@code LocationParticlePreview:98} 抛
     * {@code ClassCastException}，而报错点看起来和「世界」毫无关系。</p>
     *
     * <p>而 {@code WorldMock} 自己的 {@code spawnParticle} 全部重载都会抛
     * {@code UnimplementedOperationException}（已实测，与 round2-06 的记录一致），
     * 所以只能用 spy + {@code doAnswer} 把这一层换掉。</p>
     */
    private World capturingWorld(String name) {
        WorldMock real = server.getWorld(name) != null
                ? (WorldMock) server.getWorld(name)
                : server.addSimpleWorld(name);
        WorldMock spied = org.mockito.Mockito.spy(real);
        when(spied.getName()).thenReturn(name);
        org.mockito.Mockito.doAnswer(invocation -> {
            spawns.add(new Spawn(name,
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)));
            return null;
        }).when(spied).spawnParticle(eq(Particle.DUST), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                eq(1), org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                any(Particle.DustOptions.class));
        return spied;
    }

    private PlayerMock playerIn(String name, World world) {
        PlayerMock player = server.addPlayer(name);
        player.teleport(new Location(world, 0, 64, 0));
        return player;
    }

    // ================================================================== 按玩家隔离

    @Nested
    @DisplayName("按 UUID 隔离，互不打断")
    class Isolation {

        @Test
        @DisplayName("A 起预览后 B 再起预览，A 的任务仍然活着")
        void secondViewerDoesNotKillTheFirst() {
            World world = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", world);
            PlayerMock bob = server.addPlayer("bob");
            bob.teleport(new Location(world, 0, 64, 0));

            // 两个玩家各看各的点位，才能从坐标上分辨谁的任务还活着
            preview.show(alice, List.of(new Location(world, 10, 64, 10)), PointKind.NODE, 100);
            preview.show(bob, List.of(new Location(world, 40, 64, 40)), PointKind.SPAWN, 100);

            spawns.clear();
            server.getScheduler().performTicks(8);

            long aliceSpawns = spawns.stream().filter(spawn -> Math.abs(spawn.x() - 10.5) < 1e-9).count();
            long bobSpawns = spawns.stream().filter(spawn -> Math.abs(spawn.x() - 40.5) < 1e-9).count();

            assertTrue(aliceSpawns >= 54,
                    "B 的预览不得打断 A 的 —— 这正是本类存在的理由："
                            + "AdminDeployManager 的 showParticlesTaskId 是单实例字段，"
                            + "两个管理员同时预览时后一个会把前一个停掉。"
                            + "A 的点位应当被绘制，实际只捕到 " + aliceSpawns + " 颗粒子");
            assertTrue(bobSpawns >= 54, "B 自己的预览同样必须工作，实际 " + bobSpawns);
        }

        @Test
        @DisplayName("同一玩家再次 show 会先停掉自己的上一次（不叠加帧率）")
        void sameViewerRestartsItsOwnPreview() {
            World world = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", world);
            List<Location> targets = List.of(new Location(world, 10, 64, 10));

            // 基准：只 show 一次时，12 tick 内产生的粒子数
            preview.show(alice, targets, PointKind.NODE, 10_000);
            spawns.clear();
            server.getScheduler().performTicks(12);
            int singleShowCount = spawns.size();
            assertTrue(singleShowCount > 0, "基准必须非零，否则下面的等值断言是永真的");

            // 同一玩家再 show 一次：任务被替换，帧率必须<b>完全一致</b>
            preview.stopAll();
            spawns.clear();
            preview.show(alice, targets, PointKind.NODE, 10_000);
            preview.show(alice, targets, PointKind.NODE, 10_000);
            server.getScheduler().performTicks(12);

            assertEquals(singleShowCount, spawns.size(),
                    "两次 show 若没有先 stop 掉上一次，两个任务会同时跑，"
                            + "粒子数变成两倍（每 4 tick 两次而不是一次）—— 这在游戏里表现为"
                            + "预览「抖」且密度翻倍，很难被当成 bug 报上来。"
                            + "单次=" + singleShowCount + "，双次=" + spawns.size());
        }

        @Test
        @DisplayName("stop 只取消该玩家，其它玩家不受影响")
        void stopOnlyAffectsThatViewer() {
            World world = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", world);
            PlayerMock bob = playerIn("bob", world);
            List<Location> targets = List.of(new Location(world, 10, 64, 10));

            preview.show(alice, targets, PointKind.NODE, 1000);
            preview.show(bob, targets, PointKind.NODE, 1000);
            preview.stop(alice);

            spawns.clear();
            server.getScheduler().performTicks(8);

            assertFalse(spawns.isEmpty(), "bob 的预览必须继续");
        }

        @Test
        @DisplayName("stopAll 之后没有任何任务继续绘制")
        void stopAllCancelsEverything() {
            World world = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", world);
            PlayerMock bob = playerIn("bob", world);
            List<Location> targets = List.of(new Location(world, 10, 64, 10));

            preview.show(alice, targets, PointKind.NODE, 1000);
            preview.show(bob, targets, PointKind.NODE, 1000);
            preview.stopAll();

            spawns.clear();
            server.getScheduler().performTicks(12);

            assertTrue(spawns.isEmpty(),
                    "stopAll 供 onDisable 调用：关服后不得再有任何任务往世界里撒粒子");
        }

        @Test
        @DisplayName("stop / show(null) 都是安全的 no-op")
        void nullsAreTolerated() {
            preview.stop(null);
            preview.show(null, List.of(), PointKind.NODE, 20);
            assertTrue(spawns.isEmpty());
        }
    }

    // ================================================================== 逐点过滤

    @Nested
    @DisplayName("逐点过滤：世界不匹配 / 世界未加载 / 超过 64 格")
    class Filtering {

        @Test
        @DisplayName("世界不匹配的点被跳过（预览只在观看者所在世界生效）")
        void pointsInOtherWorldsAreSkipped() {
            World arena = capturingWorld("hilltop_arena1");
            World lobby = capturingWorld("lobby");
            PlayerMock alice = playerIn("alice", arena);

            preview.show(alice, List.of(
                    new Location(lobby, 100, 64, 100),
                    new Location(arena, 10, 64, 10)), PointKind.NODE, 100);

            spawns.clear();
            server.getScheduler().performTicks(8);

            assertFalse(spawns.isEmpty(), "同世界的点必须被绘制（对照）");
            assertTrue(spawns.stream().allMatch(spawn -> spawn.worldName().equals("hilltop_arena1")),
                    "大厅里的点不得在竞技场世界里被绘制 —— 否则管理员会看到一堆"
                            + "与当前竞技场无关的坐标");
        }

        @Test
        @DisplayName("世界未加载的点被跳过，不抛异常")
        void pointsWithNullWorldAreSkipped() {
            World arena = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", arena);

            // 无世界的坐标（World 未加载时 ArenaAdminService.nodes 就是这个形态）
            preview.show(alice, List.of(
                    new Location(null, 10, 64, 10),
                    new Location(arena, 20, 64, 20)), PointKind.NODE, 100);

            spawns.clear();
            server.getScheduler().performTicks(8);

            assertFalse(spawns.isEmpty(), "有效点仍须被绘制（证明任务没被 null 世界打断）");
            assertTrue(spawns.stream().noneMatch(spawn -> spawn.x() < 15),
                    "世界为 null 的点（x=10）必须被 continue 掉，而不是 NPE 打断整个 tick。"
                            + "实际最左的粒子在 x=" + spawns.stream().mapToDouble(Spawn::x).min().orElse(-1)
                            + "，说明那个无世界点位也被画了");
            assertTrue(spawns.stream().allMatch(spawn -> spawn.x() >= 19.0 && spawn.x() <= 22.0),
                    "有效点 x=20（光柱居中 20.5、螺旋半径 1.2）必须全部落在它周围");
        }

        @Test
        @DisplayName("超过 64 格的点被剔除，64 格内的保留")
        void pointsBeyondViewDistanceAreSkipped() {
            World arena = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", arena);   // 位于 (0, 64, 0)

            preview.show(alice, List.of(
                    new Location(arena, 100, 64, 0),   // 100 格 → 剔除
                    new Location(arena, 63, 64, 0)),   // 63 格 → 保留
                    PointKind.NODE, 100);

            spawns.clear();
            server.getScheduler().performTicks(8);

            assertFalse(spawns.isEmpty(), "63 格内的点必须被绘制");
            assertTrue(spawns.stream().allMatch(spawn -> spawn.x() < 70),
                    "超过 VIEW_DISTANCE(64) 的点必须被剔除 —— 否则一张 200 格的竞技场会"
                            + "在每帧对几十个看不见的点撒 54 颗粒子");
        }

        @Test
        @DisplayName("距离用 distanceSquared 判断，边界 64 格恰好保留")
        void viewDistanceBoundaryIsInclusive() {
            World arena = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", arena);

            preview.show(alice, List.of(new Location(arena, 64, 64, 0)), PointKind.NODE, 100);
            spawns.clear();
            server.getScheduler().performTicks(4);
            assertFalse(spawns.isEmpty(), "恰好 64 格必须保留（判据是 > 64 才剔除）");

            preview.stopAll();
            spawns.clear();
            preview.show(alice, List.of(new Location(arena, 65, 64, 0)), PointKind.NODE, 100);
            spawns.clear();
            server.getScheduler().performTicks(4);
            assertTrue(spawns.isEmpty(), "65 格必须剔除");
        }
    }

    // ================================================================== 生命周期

    @Nested
    @DisplayName("生命周期")
    class Lifecycle {

        @Test
        @DisplayName("durationTicks 到点后自动停（不多跑一帧）")
        void stopsAfterDurationTicks() {
            World world = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", world);
            preview.show(alice, List.of(new Location(world, 10, 64, 10)), PointKind.NODE, 8);

            server.getScheduler().performTicks(40);   // 远超 8 tick
            spawns.clear();
            server.getScheduler().performTicks(40);

            assertTrue(spawns.isEmpty(),
                    "duration 到点必须 cancel；否则预览永不结束，管理员以为按钮没生效");
        }

        @Test
        @DisplayName("空列表不发任务，但要提示（文案键走 lang）")
        void emptyListProducesNoTask() {
            World world = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", world);

            preview.show(alice, List.of(), PointKind.NODE, 100);
            spawns.clear();
            server.getScheduler().performTicks(12);

            assertTrue(spawns.isEmpty(), "空列表不得起任务");
            // 4.45.0 下 PlayerMock 没有 message queue，故只能断言「文案路径被走到过」
            verify(lang, org.mockito.Mockito.atLeastOnce()).getFor(any(), anyString());
        }

        @Test
        @DisplayName("玩家离线后任务自行停止，不向失效连接发包")
        void offlineViewerStopsTheTask() {
            World world = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", world);
            preview.show(alice, List.of(new Location(world, 10, 64, 10)), PointKind.NODE, 10_000);

            server.getScheduler().performTicks(8);
            assertFalse(spawns.isEmpty());

            alice.disconnect();
            spawns.clear();
            server.getScheduler().performTicks(20);

            assertTrue(spawns.isEmpty(),
                    "观看者离线后必须 cancel —— 否则任务会一直跑到 duration 结束，"
                            + "期间不断往一个已断开的连接发包");
        }

        @Test
        @DisplayName("show 的标题用 targets.size() 与 durationTicks/20（秒）")
        void headerReceivesCountAndSeconds() {
            World world = capturingWorld("hilltop_arena1");
            PlayerMock alice = playerIn("alice", world);

            preview.show(alice, List.of(
                    new Location(world, 1, 64, 1),
                    new Location(world, 2, 64, 2),
                    new Location(world, 3, 64, 3)), PointKind.VILLAGER, 200);

            verify(lang).getFor(any(), eq("manage.preview.header"), eq(3), eq(10L));
        }
    }
}
