package com.hilltopvillage.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
 * {@link NodeAttackGoal} 的目标选择、搜索半径与卡死放弃（P0-C）契约测试。
 *
 * <p><b>本类测什么、为什么值得测</b>：这个 Goal 是「节点才是真正的血条」这条设计的执行端。
 * 三处行为一旦回退，症状都不是异常而是<b>波次永远清不掉</b>：
 * <ol>
 *   <li>{@code node-search-radius <= 0} 必须<b>禁用</b>节点寻路，而不是退回某个隐藏默认值——
 *       否则管理员把半径设 0 想关掉寻路，怪物仍会横穿地图去打节点；</li>
 *   <li>半径外的节点不得被锁定——否则等于半径配置形同虚设；</li>
 *   <li>P0-C：连续卡死 {@code MAX_STUCK_GIVEUPS} 次必须 {@code discardMob} 出列——
 *       原实现对同一节点无限重发 {@code moveTo}，节点不可达时怪物永久卡住、activeMobs 永不清空，
 *       波次判定恒为 false，整局卡死。</li>
 * </ol>
 *
 * <p><b>MockBukkit 下的可行边界</b>（已实测）：{@code Mob#getPathfinder()}、
 * {@code Mob#lookAt()}、{@code Mob#getAttribute(ATTACK_DAMAGE)} 在裸 {@code ZombieMock} 上
 * 全部抛 {@code UnimplementedOperationException}。前者可以用 {@code spy} + 打桩绕过；
 * 本类不触达攻击路径（只测「够不到节点」的分支），因此后两者不是阻塞。
 * {@code NodeData} 是 public static class，可以直接 {@code mock}。
 */
class NodeAttackGoalTest extends MockBukkitTestBase {

    private WorldMock realWorld;
    private org.bukkit.World world;
    private Mob mob;
    private GameManager gameManager;
    private NodeSystem nodeSystem;
    private WaveManager waveManager;
    private com.destroystokyo.paper.entity.Pathfinder pathfinder;
    private org.bukkit.attribute.AttributeInstance attackDamage;

    @BeforeEach
    void rig() {
        realWorld = server.addSimpleWorld("node_attack_world");
        // 世界必须 spy：attackNode() 里的 spawnParticle 在 MockBukkit 下未实现，
        // 而 MockBukkit 的 JUnit 扩展会把它转成 **skipped**（不是 failure）——
        // 不桩就会静默变成假绿。见类注释与 M4 变异记录。
        world = spy(realWorld);
        doNothing().when(world).spawnParticle(any(Particle.class), any(Location.class),
                anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());

        Zombie raw = realWorld.spawn(new Location(realWorld, 100, 64, 100), Zombie.class);
        mob = spy(raw);
        // 裸 MobMock 的 getPathfinder() 未实现；用 mock 顶替后 moveTo/stopPathfinding 都是可验证调用。
        pathfinder = mock(com.destroystokyo.paper.entity.Pathfinder.class);
        doReturn(pathfinder).when(mob).getPathfinder();
        // ⚠️ lookAt() 在裸 MobMock 上抛 UnimplementedOperationException，而 MockBukkit 的
        // JUnit 扩展会把这类异常转成 **test skipped**（不是 failure）。不桩它的话，
        // 走 tick() 的用例会静默变成 Skipped —— 2026-11-03 实测：未桩时 9 条里 2 条被 skip，
        // 报告仍显示 BUILD SUCCESS，是标准的假绿。必须显式打桩。
        doNothing().when(mob).lookAt(any(Location.class));
        // getAttribute(ATTACK_DAMAGE) 同样未实现，且被 attackNode() 调用。
        // 桩成真实值，使「伤害 = 怪物对玩家的攻击伤害」这条设计可断言。
        attackDamage = mock(org.bukkit.attribute.AttributeInstance.class);
        when(attackDamage.getValue()).thenReturn(7.5);
        doReturn(attackDamage).when(mob).getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
        doReturn(true).when(mob).isValid();
        doReturn(new Location(world, 100, 64, 100)).when(mob).getLocation();
        doReturn(world).when(mob).getWorld();

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

    /**
     * 造一个位于给定坐标、且被 NodeSystem 视为「已注册且活跃」的节点桩。
     *
     * <p><b>必须每次返回新 clone</b>：真实的 {@code NodeData#getLocation()} 就是
     * {@code return location.clone()}。而 Mockito 对 {@code thenReturn(loc)} 每次返回
     * <b>同一个对象</b>，而 {@code NodeAttackGoal} 会执行
     * {@code nearest.getLocation().add(0.5, 0.5, 0.5)} —— {@code Location#add} 是<b>原地修改</b>，
     * 于是桩对象被每一轮 retarget 累加 0.5，几轮之后节点中心漂到十几格外，
     * 「能走到节点」的用例会莫名其妙走进卡死分支。
     * 2026-11-03 实测踩过：桩写成 {@code thenReturn(loc)} 时
     * {@code doesNotDiscardWhenMobReachesNode} 报 {@code NeverWantedButInvoked}。
     */
    private NodeSystem.NodeData activeNodeAt(double x, double y, double z) {
        NodeSystem.NodeData node = mock(NodeSystem.NodeData.class);
        Location nodeLoc = new Location(world, x, y, z);
        when(node.getLocation()).thenAnswer(invocation -> nodeLoc.clone());
        when(nodeSystem.isRegisteredActive(node)).thenReturn(true);
        return node;
    }

    // ---------------------------------------------------------------- 搜索半径

    @Test
    @DisplayName("node-search-radius <= 0 时不扫描节点、不激活（0 是「禁用」的明确语义）")
    void zeroSearchRadiusDisablesNodePathing() {
        when(gameManager.getNodeSearchRadius()).thenReturn(0);
        // 即便 NodeSystem 有就近节点，半径为 0 也不该去问它。
        // ⚠️ 必须先赋给局部变量：直接在 thenReturn(...) 里调 activeNodeAt() 会在桩尚未闭合时
        // 重入 mock/nodeSystem 的桩，抛 UnfinishedStubbingException（round2 勘察报告 B-2 的同一个坑）。
        NodeSystem.NodeData decoy = activeNodeAt(105, 64, 100);
        when(nodeSystem.getNearestNode(any())).thenReturn(decoy);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);

        assertFalse(goal.shouldActivate(),
                "node-search-radius=0 必须完全禁用节点寻路，不能退回隐藏默认值去锁定节点");
        verify(nodeSystem, never()).getNearestNode(any());
    }

    @Test
    @DisplayName("半径内的节点会被锁定并激活")
    void nodeWithinSearchRadiusActivates() {
        when(gameManager.getNodeSearchRadius()).thenReturn(64); // 8 格
        NodeSystem.NodeData node = activeNodeAt(105, 64, 100); // 5 格外
        when(nodeSystem.getNearestNode(any())).thenReturn(node);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);

        assertTrue(goal.shouldActivate(), "半径 8 格内的节点（5 格）必须被锁定");
    }

    @Test
    @DisplayName("半径外的节点被丢弃：最近节点超出搜索半径时不得锁定")
    void nodeBeyondSearchRadiusIsRejected() {
        when(gameManager.getNodeSearchRadius()).thenReturn(8); // 8 格
        NodeSystem.NodeData farNode = activeNodeAt(140, 64, 100); // 40 格外
        when(nodeSystem.getNearestNode(any())).thenReturn(farNode);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);

        assertFalse(goal.shouldActivate(),
                "最近的节点在 40 格外、超出 8 格半径，必须视为「无目标」而不是照样锁定");
    }

    // ---------------------------------------------------------------- P0-C 卡死放弃

    @Test
    @DisplayName("P0-C：连续卡死达到上限后 discardMob 出列（原实现会无限重寻路、波次永不清）")
    void givesUpAndDiscardsMobAfterMaxStuckGiveUps() {
        when(gameManager.getNodeSearchRadius()).thenReturn(64);
        // 节点在 15 格外：大于 ATTACK_RANGE(3)，所以永远走不到「够到节点」分支
        NodeSystem.NodeData node = activeNodeAt(115, 64, 100);
        when(nodeSystem.getNearestNode(any())).thenReturn(node);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();

        // 位置恒定不动 → stuckTicks 每 tick +1；60 tick 触发一次放弃，3 次放弃后自毁。
        // 3 × 60 = 180 tick，留足余量走 400 tick。
        for (int i = 0; i < 400; i++) {
            goal.tick();
        }

        verify(waveManager, org.mockito.Mockito.atLeastOnce()).discardMob(mob);
        verify(mob, org.mockito.Mockito.atLeast(3)).getPathfinder();
    }

    @Test
    @DisplayName("P0-C 反证：能走到节点时（距离 <= 3 格）不触发卡死放弃")
    void doesNotDiscardWhenMobReachesNode() {
        when(gameManager.getNodeSearchRadius()).thenReturn(64);
        NodeSystem.NodeData node = activeNodeAt(102, 64, 100); // 2 格外，够得到
        when(nodeSystem.getNearestNode(any())).thenReturn(node);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();
        for (int i = 0; i < 400; i++) {
            goal.tick();
        }

        verify(waveManager, never()).discardMob(mob);
    }

    // ---------------------------------------------------------------- 攻击节点

    @Test
    @DisplayName("够到节点后按 ATTACK_DAMAGE 攻击，且伤害等于怪物对玩家的攻击伤害（20 tick 冷却）")
    void damagesNodeWithMobAttackDamageAndRespectsCooldown() {
        when(gameManager.getNodeSearchRadius()).thenReturn(64);
        NodeSystem.NodeData node = activeNodeAt(102, 64, 100); // 够得到
        when(nodeSystem.getNearestNode(any())).thenReturn(node);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();
        goal.tick();
        goal.tick();

        // ATTACK_COOLDOWN_TICKS = 20：连续 2 tick 只应结算 1 次伤害
        verify(nodeSystem, times(1)).damageNode(any(Location.class), org.mockito.ArgumentMatchers.eq(7.5));
    }

    // ---------------------------------------------------------------- 生命周期

    @Test
    @DisplayName("stop() 停止寻路并清空目标")
    void stopStopsPathfindingAndClearsTarget() {
        when(gameManager.getNodeSearchRadius()).thenReturn(64);
        NodeSystem.NodeData node = activeNodeAt(115, 64, 100);
        when(nodeSystem.getNearestNode(any())).thenReturn(node);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();

        goal.stop();

        verify(pathfinder, org.mockito.Mockito.atLeastOnce()).stopPathfinding();
        assertFalse(goal.shouldStayActive(), "stop() 之后目标已清空，shouldStayActive 必须为 false");
    }

    @Test
    @DisplayName("目标节点被摧毁后 shouldStayActive 返回 false（节点才是真正的血条）")
    void deadNodeMakesGoalInactive() {
        when(gameManager.getNodeSearchRadius()).thenReturn(64);
        NodeSystem.NodeData node = activeNodeAt(115, 64, 100);
        when(nodeSystem.getNearestNode(any())).thenReturn(node);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();
        assertTrue(goal.shouldStayActive());

        when(nodeSystem.isRegisteredActive(node)).thenReturn(false);

        assertFalse(goal.shouldStayActive(),
                "节点被摧毁（不再活跃）后 Goal 必须结束，否则怪物会对着空气继续挥砍");
    }

    @Test
    @DisplayName("游戏未运行时 shouldActivate 恒为 false")
    void notActiveWhenGameStopped() {
        when(gameManager.isGameRunning()).thenReturn(false);
        when(gameManager.getNodeSearchRadius()).thenReturn(64);

        NodeAttackGoal goal = new NodeAttackGoal(mob, gameManager);

        assertFalse(goal.shouldActivate(), "游戏未运行时不得扫描或锁定节点");
    }

    // ---------------------------------------------------------------- 共享常量的防御性

    @Test
    @DisplayName("getTypes 返回副本；getKey 稳定")
    void goalIdentityContract() {
        NodeAttackGoal a = new NodeAttackGoal(mob, gameManager);
        NodeAttackGoal b = new NodeAttackGoal(mob, gameManager);

        assertNotSame(a.getTypes(), a.getTypes(), "getTypes 必须每次返回新副本");
        assertEquals(2, a.getTypes().size(), "MOVE + LOOK 两个类型");
        assertEquals("hilltopvillage:node_attack", a.getKey().getNamespacedKey().toString());
        assertEquals(a.getKey(), b.getKey(), "GoalKey 是常量，两个实例必须相等");
    }
}
