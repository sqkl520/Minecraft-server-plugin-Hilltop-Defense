package com.hilltopvillage.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.WaveManager;
import com.hilltopvillage.mechanics.NodeSystem;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link ExplodeBeetleGoal} 的「常驻 + 自毁出列」契约测试。
 *
 * <p><b>本类守护的是什么</b>：P0-C 把 {@code shouldActivate()} 从
 * 「有节点目标才激活」改成「游戏进行中且甲虫存活就常驻」。原实现里，一旦没有节点目标
 * （节点全毁 / 全部超出搜索半径），Goal 根本不运行 → 甲虫静止罚站，<b>既不自毁也不离场</b>，
 * 永久占着 {@code activeMobs} 让波次判定恒为 false，整局卡在「还有怪物没死」。
 *
 * <p>修复后由 {@code tick()} 统一处理：无目标持续 {@code NO_TARGET_SELF_DESTRUCT_TICKS}
 * （100 tick）后 {@code discardMob} 出列。本类第 2 条用例正是这条的回归锚点。
 *
 * <p><b>MockBukkit 下的可行边界</b>（已实测）：{@code Mob#getPathfinder()} /
 * {@code Mob#setGlowing()} 中前者未实现（用 spy 打桩）、后者可用；
 * {@code world.spawnParticle} 未实现且 MockBukkit 会把它转成 <b>skipped</b>，
 * 所以世界也 spy 并打桩。{@code getAttribute} 只在真正引爆时用到，本类不触达。
 */
class ExplodeBeetleGoalTest extends MockBukkitTestBase {

    private WorldMock realWorld;
    private World world;
    private Mob beetle;
    private GameManager gameManager;
    private NodeSystem nodeSystem;
    private WaveManager waveManager;
    private com.destroystokyo.paper.entity.Pathfinder pathfinder;

    @BeforeEach
    void rig() {
        realWorld = server.addSimpleWorld("beetle_world");
        world = spy(realWorld);
        doNothing().when(world).spawnParticle(any(Particle.class), any(Location.class),
                anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());

        Zombie raw = realWorld.spawn(new Location(realWorld, 100, 64, 100), Zombie.class);
        beetle = spy(raw);
        pathfinder = mock(com.destroystokyo.paper.entity.Pathfinder.class);
        doReturn(pathfinder).when(beetle).getPathfinder();
        doReturn(true).when(beetle).isValid();
        doReturn(new Location(world, 100, 64, 100)).when(beetle).getLocation();
        doReturn(world).when(beetle).getWorld();
        doNothing().when(beetle).setGlowing(org.mockito.ArgumentMatchers.anyBoolean());

        nodeSystem = mock(NodeSystem.class);
        waveManager = mock(WaveManager.class);

        gameManager = mock(GameManager.class);
        when(gameManager.isGameRunning()).thenReturn(true);
        when(gameManager.getNodeSystem()).thenReturn(nodeSystem);
        when(gameManager.getWaveManager()).thenReturn(waveManager);

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getActiveConfig()).thenReturn(new GameConfig());
        when(gameManager.getConfigManager()).thenReturn(configManager);
    }

    private NodeSystem.NodeData nodeAt(double x, double y, double z) {
        NodeSystem.NodeData node = mock(NodeSystem.NodeData.class);
        Location loc = new Location(world, x, y, z);
        // 必须 clone：Location#add 是原地修改，共享桩对象会被逐轮累加（见 NodeAttackGoalTest 注释）。
        when(node.getLocation()).thenAnswer(inv -> loc.clone());
        when(nodeSystem.isRegisteredActive(node)).thenReturn(true);
        return node;
    }

    @Test
    @DisplayName("P0-C：无节点目标时仍保持激活（常驻），不再因为没目标就整个 Goal 停摆")
    void staysActiveEvenWithoutAnyNodeTarget() {
        when(gameManager.getNodeSearchRadius()).thenReturn(0); // 无节点寻路
        ExplodeBeetleGoal goal = new ExplodeBeetleGoal(beetle, gameManager);

        assertTrue(goal.shouldActivate(),
                "★ P0-C 回归：无节点目标时也必须常驻运行，否则甲虫静止罚站、"
                        + "既不自毁也不离场，永久占着 activeMobs 让波次判定恒为 false");
        assertTrue(goal.shouldStayActive(),
                "shouldStayActive 同样必须常驻 —— 返回 false 会让 Goal 立刻 stop() 并重新开始循环");
    }

    @Test
    @DisplayName("P0-C：无目标持续 100 tick 后 discardMob 出列（不再罚站卡波）")
    void selfDestructsAfterNoTargetGracePeriod() {
        when(gameManager.getNodeSearchRadius()).thenReturn(0);
        ExplodeBeetleGoal goal = new ExplodeBeetleGoal(beetle, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();

        // NO_TARGET_SELF_DESTRUCT_TICKS = 100
        for (int i = 0; i < 100; i++) {
            goal.tick();
        }

        verify(waveManager, org.mockito.Mockito.atLeastOnce()).discardMob(beetle);
    }

    @Test
    @DisplayName("P0-C 反证：无目标但还没到宽限期时不得自毁（避免刚出生就被误杀）")
    void doesNotSelfDestructBeforeGracePeriodElapses() {
        when(gameManager.getNodeSearchRadius()).thenReturn(0);
        ExplodeBeetleGoal goal = new ExplodeBeetleGoal(beetle, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();

        for (int i = 0; i < 99; i++) {
            goal.tick();
        }

        verify(waveManager, never()).discardMob(beetle);
    }

    @Test
    @DisplayName("有节点目标时不会走自毁分支：朝节点推进而不是出列")
    void doesNotSelfDestructWhenNodeTargetExists() {
        when(gameManager.getNodeSearchRadius()).thenReturn(64);
        NodeSystem.NodeData node = nodeAt(130, 64, 100); // 30 格外，走不到但目标存在
        when(nodeSystem.getNearestNode(any())).thenReturn(node);

        ExplodeBeetleGoal goal = new ExplodeBeetleGoal(beetle, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();

        for (int i = 0; i < 100; i++) {
            goal.tick();
        }

        verify(waveManager, never()).discardMob(beetle);
    }

    @Test
    @DisplayName("游戏未运行时 shouldActivate / shouldStayActive 均为 false")
    void inactiveWhenGameStopped() {
        when(gameManager.isGameRunning()).thenReturn(false);
        ExplodeBeetleGoal goal = new ExplodeBeetleGoal(beetle, gameManager);

        assertFalse(goal.shouldActivate(), "游戏未运行时甲虫 Goal 不得激活");
        assertFalse(goal.shouldStayActive(), "游戏未运行时甲虫 Goal 必须结束");
    }

    @Test
    @DisplayName("甲虫失效（isValid=false）后 shouldStayActive 返回 false")
    void inactiveWhenBeetleInvalid() {
        doReturn(false).when(beetle).isValid();
        ExplodeBeetleGoal goal = new ExplodeBeetleGoal(beetle, gameManager);

        assertFalse(goal.shouldStayActive(), "甲虫已被移除时 Goal 必须结束，否则会对着无效实体继续 tick");
    }

    @Test
    @DisplayName("radius <= 0 时不扫描节点表（0 是「禁用节点寻路」的明确语义）")
    void zeroRadiusSkipsNodeScan() {
        when(gameManager.getNodeSearchRadius()).thenReturn(0);
        ExplodeBeetleGoal goal = new ExplodeBeetleGoal(beetle, gameManager);
        goal.start();
        goal.tick();

        verify(nodeSystem, never()).getNearestNode(any());
    }

    @Test
    @DisplayName("getTypes 返回副本；getKey 稳定")
    void goalIdentityContract() {
        ExplodeBeetleGoal a = new ExplodeBeetleGoal(beetle, gameManager);
        ExplodeBeetleGoal b = new ExplodeBeetleGoal(beetle, gameManager);

        org.junit.jupiter.api.Assertions.assertNotSame(a.getTypes(), a.getTypes());
        assertEquals(2, a.getTypes().size());
        assertEquals("hilltopvillage:explode_beetle", a.getKey().getNamespacedKey().toString());
        assertEquals(a.getKey(), b.getKey());
    }
}
