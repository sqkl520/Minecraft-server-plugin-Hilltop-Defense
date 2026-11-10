package com.hilltopvillage.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.WaveManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link HookClawHunterGoal} 的射程带契约测试。
 *
 * <p><b>为什么这个类是本波次最重要的一条</b>：round1 的 {@code slice-04-logic-001}
 * 修的是「射程带只在选目标那一刻成立」。原实现 {@code findValidTarget()} 里校验了
 * 8–30 格水平距离与至少 4 格的落差，锁到目标后 {@code start()} 进入
 * {@value #AIM_DURATION_REPORTED} tick 的瞄准期，然后 <b>无条件开火</b>。玩家在这 1 秒里
 * 走两步就能让「贴脸命中」或「超程命中」成立——钩爪会把 40 格外的玩家拽到怪物脚下，
 * 或者把站在怪物身上的玩家直接拉走。
 *
 * <p>修复把判据提成 {@code isInShootBand()}，选目标与开火共用，开火前按<b>当前坐标</b>复算。
 * 本类第 2、3、4 条用例正是这个复算的回归锚点：它们先让目标在射程带内完成锁定，
 * 再在瞄准期内把玩家移出射程带，断言<b>一发都不该打出</b>。把 {@code shootHook()} 里的
 * 复算删掉，这三条全部变红。
 *
 * <p><b>MockBukkit 下的可行边界</b>（已实测，见报告 §2）：
 * {@code World#spawnParticle} 与 {@code World#rayTraceBlocks} 抛
 * {@code UnimplementedOperationException}，因此世界必须用 {@code spy} 包一层并对这两个方法
 * 打桩；{@code Mob#getAttribute} 同样抛，但本类不打伤害属性，不受影响。
 * 雪球<b>可以</b>用 {@code world.getEntitiesByClass(Snowball.class).size()} 观测——
 * 注意要查被 spy 的那个世界，{@code spy} 委托给同一个 {@code WorldMock}，
 * 实体表是共用的。
 *
 * <p><b>不要把玩家 spy 掉</b>：{@code spy(player)} 会让 {@code getUniqueId()} 走 Mockito 代理，
 * 与 {@code gameManager.getPlayers()} 里的 UUID 不再相等，{@code isValidArenaTarget} 直接失败、
 * Goal 从不激活。作者实测踩过一次，记录在此。
 */
class HookClawHunterGoalTest extends MockBukkitTestBase {

    /** 与 production 的 {@code AIM_DURATION} 对齐；仅用于文档与循环次数。 */
    private static final int AIM_DURATION_REPORTED = 20;

    private WorldMock realWorld;
    private World spyWorld;
    private Mob hunter;
    private PlayerMock player;
    private GameManager gameManager;

    @BeforeEach
    void rig() {
        realWorld = server.addSimpleWorld("hook_test_world");

        // 世界必须 spy：spawnParticle / rayTraceBlocks 在 MockBukkit 下未实现。
        spyWorld = spy(realWorld);
        doNothing().when(spyWorld).spawnParticle(any(Particle.class), any(Location.class),
                anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        doReturn(null).when(spyWorld).rayTraceBlocks(any(Location.class), any(Vector.class),
                anyDouble(), any(FluidCollisionMode.class),
                org.mockito.ArgumentMatchers.anyBoolean());

        Zombie raw = realWorld.spawn(new Location(realWorld, 100, 64, 100), Zombie.class);
        hunter = spy(raw);
        // 猎人位置与所在世界都钉在 spy 世界上，保证 Location 的 world 身份判等成立。
        doReturn(new Location(spyWorld, 100, 64, 100)).when(hunter).getLocation();
        doReturn(spyWorld).when(hunter).getWorld();
        doReturn(true).when(hunter).isValid();

        player = server.addPlayer("HookTarget");

        gameManager = mock(GameManager.class);
        when(gameManager.isGameRunning()).thenReturn(true);
        when(gameManager.getGameWorld()).thenReturn(spyWorld);
        when(gameManager.getPlayers()).thenReturn(new HashSet<>());

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getActiveConfig()).thenReturn(new GameConfig());
        when(gameManager.getConfigManager()).thenReturn(configManager);

        WaveManager waveManager = mock(WaveManager.class);
        when(waveManager.getActiveMobs()).thenReturn(new HashSet<>());
        when(gameManager.getWaveManager()).thenReturn(waveManager);

        HilltopVillagePlugin plugin = mock(HilltopVillagePlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(gameManager.getPlugin()).thenReturn(plugin);
    }

    /** 把玩家放进本场对局，并停在猎人正东 {@code dx} 格、高出 {@code dy} 格处。 */
    private void placePlayer(double dx, double dy) {
        player.teleport(new Location(spyWorld, 100 + dx, 64 + dy, 100));
        Set<UUID> participants = new HashSet<>();
        participants.add(player.getUniqueId());
        when(gameManager.getPlayers()).thenReturn(participants);
    }

    private int snowballsFired() {
        // 必须查被 spy 的那个世界实例。实测：通过 spy 世界 spawn 出来的实体登记在
        // spy 的实体表里，直接查 realWorld 会恒为 0（探针里 real=0 / spy=N）。
        return spyWorld.getEntitiesByClass(Snowball.class).size();
    }

    /**
     * 锁定目标并走完整个瞄准期。
     *
     * <p>第 {@code AIM_DURATION} 次 {@code tick()} 才会落到 {@code shootHook()}
     * （前 19 次都在 {@code aimTicks > 0} 处提前返回）。
     */
    private void aimThroughFullWindow(HookClawHunterGoal goal) {
        goal.start();
        for (int i = 0; i < AIM_DURATION_REPORTED; i++) {
            goal.tick();
        }
    }

    // ---------------------------------------------------------------- 正控

    @Test
    @DisplayName("目标全程留在射程带内 → 瞄准期满后打出一发钩爪（正控，防止下面的用例被硬编码 false 蒙混）")
    void firesWhenTargetStaysInBandThroughAimWindow() {
        placePlayer(20, 6);
        HookClawHunterGoal goal = new HookClawHunterGoal(hunter, gameManager);

        assertTrue(goal.shouldActivate(), "水平 20 格、高出 6 格必须落在 8–30 格 / >=4 格的射程带内");

        aimThroughFullWindow(goal);

        assertEquals(1, snowballsFired(),
                "目标全程在射程带内时，瞄准期满必须打出一发钩爪 —— 否则下面三条「不该开火」的断言毫无意义");
    }

    // ------------------------------------------------ 本波次守护的 round1 修复

    @Test
    @DisplayName("slice-04-logic-001：瞄准期内玩家跑远 → 开火前复算射程带，一发都不打")
    void doesNotFireWhenTargetLeavesBandByRunningAway() {
        placePlayer(20, 6);
        HookClawHunterGoal goal = new HookClawHunterGoal(hunter, gameManager);
        assertTrue(goal.shouldActivate());

        goal.start();
        // 瞄准期内（前 19 tick）玩家跑出 30 格上限
        for (int i = 0; i < AIM_DURATION_REPORTED - 1; i++) {
            goal.tick();
        }
        player.teleport(new Location(spyWorld, 200, 70, 100)); // 水平 100 格，远超 30 格上限
        goal.tick(); // 这一 tick 会走到 shootHook()

        assertEquals(0, snowballsFired(),
                "★ round1 slice-04-logic-001 回归：钩爪只在【选目标那一刻】校验过射程带，"
                        + "瞄准期 20 tick 里玩家已跑到 100 格外，开火前必须按当前坐标复算并放弃，"
                        + "否则会从 100 格外把玩家拽到怪物脚下");
    }

    @Test
    @DisplayName("slice-04-logic-001：瞄准期内玩家贴近到 8 格以内 → 开火前复算射程带，一发都不打")
    void doesNotFireWhenTargetClosesInsideMinimumRange() {
        placePlayer(20, 6);
        HookClawHunterGoal goal = new HookClawHunterGoal(hunter, gameManager);
        assertTrue(goal.shouldActivate());

        goal.start();
        for (int i = 0; i < AIM_DURATION_REPORTED - 1; i++) {
            goal.tick();
        }
        player.teleport(new Location(spyWorld, 102, 70, 100)); // 水平 2 格，低于 8 格下限
        goal.tick();

        assertEquals(0, snowballsFired(),
                "★ round1 slice-04-logic-001 回归：玩家已贴到 2 格内（低于 8 格下限），"
                        + "开火前复算必须放弃 —— 否则钩爪会把站在怪物身上的玩家直接拽走");
    }

    @Test
    @DisplayName("slice-04-logic-001：瞄准期内玩家落到低于猎人 4 格 → 开火前复算落差，一发都不打")
    void doesNotFireWhenHeightGapDisappearsDuringAimWindow() {
        placePlayer(20, 6);
        HookClawHunterGoal goal = new HookClawHunterGoal(hunter, gameManager);
        assertTrue(goal.shouldActivate());

        goal.start();
        for (int i = 0; i < AIM_DURATION_REPORTED - 1; i++) {
            goal.tick();
        }
        // 水平仍 20 格（带内），但只高出 1 格 → 低于 REQUIRED_Y_DIFF 的 4 格
        player.teleport(new Location(spyWorld, 120, 65, 100));
        goal.tick();

        assertEquals(0, snowballsFired(),
                "★ round1 slice-04-logic-001 回归：水平距离仍合法但落差只剩 1 格，"
                        + "开火前复算必须把落差一并重判 —— 钩爪的设计前提是「打高处目标」");
    }

    // ---------------------------------------------------------------- 选目标侧的判据

    @Test
    @DisplayName("选目标：落差不足 4 格时不锁定（射程带判据在扫描阶段同样生效）")
    void doesNotSelectTargetBelowRequiredHeightGap() {
        placePlayer(20, 3); // 只高出 3 格
        HookClawHunterGoal goal = new HookClawHunterGoal(hunter, gameManager);

        assertFalse(goal.shouldActivate(),
                "落差 3 格小于 REQUIRED_Y_DIFF=4，扫描阶段就不该锁定目标");
    }

    @Test
    @DisplayName("选目标：水平距离超过 30 格上限时不锁定")
    void doesNotSelectTargetBeyondMaximumRange() {
        placePlayer(40, 6);
        HookClawHunterGoal goal = new HookClawHunterGoal(hunter, gameManager);

        assertFalse(goal.shouldActivate(),
                "水平 40 格超过 MAX_SHOOT_DIST_SQ 对应上限（30 格），扫描阶段就不该锁定目标");
    }

    @Test
    @DisplayName("选目标：水平距离不足 8 格下限时不锁定")
    void doesNotSelectTargetInsideMinimumRange() {
        placePlayer(5, 6);
        HookClawHunterGoal goal = new HookClawHunterGoal(hunter, gameManager);

        assertFalse(goal.shouldActivate(),
                "水平 5 格低于 MIN_SHOOT_DIST_SQ 对应下限（8 格），扫描阶段就不该锁定目标");
    }

    // ---------------------------------------------------------------- 共享常量的防御性

    @Test
    @DisplayName("getTypes 返回副本：改动返回值不得污染后续调用（TYPES 是 static 共享的）")
    void getTypesReturnsDefensiveCopy() {
        HookClawHunterGoal goal = new HookClawHunterGoal(hunter, gameManager);

        var first = goal.getTypes();
        var second = goal.getTypes();
        assertNotSame(first, second, "getTypes 必须每次返回新副本 —— 直接返回 static TYPES 会让外部改坏全局");

        first.clear();
        assertEquals(2, goal.getTypes().size(),
                "外部清空 getTypes() 的返回值后，下一次调用仍应是 MOVE + LOOK 两个类型");
    }

    @Test
    @DisplayName("getKey 稳定：每次返回同一个 GoalKey 实例")
    void getKeyIsStable() {
        HookClawHunterGoal a = new HookClawHunterGoal(hunter, gameManager);
        HookClawHunterGoal b = new HookClawHunterGoal(hunter, gameManager);

        assertSame(a.getKey(), b.getKey(), "GoalKey 已提为常量，两个实例必须返回同一对象");
        assertEquals("hilltopvillage:hook_claw_hunter", a.getKey().getNamespacedKey().toString(),
                "GoalKey 的命名空间键是服务端注册与去重的依据，改了会让 Goal 重复挂载");
    }
}
