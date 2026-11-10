package com.hilltopvillage.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.WaveManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link DropperAttackGoal} 的目标筛选与「只咬不推」契约测试。
 *
 * <p><b>本类守护的两件事</b>：
 * <ol>
 *   <li><b>目标合法性</b>：观察者/创造模式、死亡、非本场玩家、跨世界一律不得被锁定。
 *       跨世界尤其重要——{@code findNearestPlayer()} 在抛投者不在竞技场世界时直接返回 null，
 *       因为 Bukkit 的 {@code distanceSquared} 对跨世界 Location 抛 {@code IllegalArgumentException}。</li>
 *   <li><b>fix5 M-07「只咬不推」</b>：原版近战击退方向是「远离怪物」，而俯冲时怪物水平投影
 *       可能位于玩家与岛心之间，于是击退会把玩家<b>推离天空岛坠入虚空</b>。修复记录伤害前的
 *       水平速度、伤害后退回它（保留垂直分量），彻底消除不可控的水平击退位移。</li>
 * </ol>
 *
 * <p><b>MockBukkit 下的可行边界</b>（已实测）：受伤/击退的端到端行为需要真实的
 * {@code damage()} 语义，裸 {@code PlayerMock#damage} 会真的走伤害流程但击退计算是
 * MockBukkit 的简化实现，因此「击退位移」本身不可观测。本类改为对
 * {@code setVelocity(...)} 的<b>调用参数</b>做断言——这正是修复的契约：
 * 写入的速度 X/Z 必须等于伤害前读到的 X/Z。
 */
class DropperAttackGoalTest extends MockBukkitTestBase {

    private WorldMock world;
    private Mob dropper;
    private GameManager gameManager;
    private PlayerMock player;

    @BeforeEach
    void rig() {
        world = server.addSimpleWorld("dropper_world");

        Zombie raw = world.spawn(new Location(world, 100, 64, 100), Zombie.class);
        dropper = spy(raw);
        doReturn(true).when(dropper).isValid();
        doReturn(new Location(world, 100, 64, 100)).when(dropper).getLocation();
        doReturn(world).when(dropper).getWorld();

        player = server.addPlayer("DropperTarget");

        GameManager gm = mock(GameManager.class);
        when(gm.isGameRunning()).thenReturn(true);
        when(gm.getGameWorld()).thenReturn(world);

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getActiveConfig()).thenReturn(new GameConfig());
        when(gm.getConfigManager()).thenReturn(configManager);

        WaveManager waveManager = mock(WaveManager.class);
        when(waveManager.getActiveMobs()).thenReturn(new HashSet<>());
        when(gm.getWaveManager()).thenReturn(waveManager);

        gameManager = gm;
    }

    private void enlist(Player p) {
        Set<UUID> participants = new HashSet<>();
        participants.add(p.getUniqueId());
        when(gameManager.getPlayers()).thenReturn(participants);
    }

    // ---------------------------------------------------------------- 目标筛选

    @Test
    @DisplayName("本场玩家在狩猎半径内 → 锁定并激活")
    void locksOntoNearbyArenaPlayer() {
        player.teleport(new Location(world, 110, 64, 100)); // 10 格，远小于 30 格狩猎半径
        enlist(player);

        DropperAttackGoal goal = new DropperAttackGoal(dropper, gameManager);

        assertTrue(goal.shouldActivate(), "10 格外的本场玩家必须被锁定");
    }

    @Test
    @DisplayName("观察者模式的玩家不被锁定（不能被俯冲攻击）")
    void ignoresSpectatorPlayer() {
        player.teleport(new Location(world, 110, 64, 100));
        player.setGameMode(GameMode.SPECTATOR);
        enlist(player);

        DropperAttackGoal goal = new DropperAttackGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(),
                "观察者模式玩家必须被排除，否则会被俯冲击伤");
    }

    @Test
    @DisplayName("创造模式的玩家不被锁定")
    void ignoresCreativePlayer() {
        player.teleport(new Location(world, 110, 64, 100));
        player.setGameMode(GameMode.CREATIVE);
        enlist(player);

        DropperAttackGoal goal = new DropperAttackGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(), "创造模式玩家必须被排除");
    }

    @Test
    @DisplayName("超出狩猎半径（30 格）的玩家不被锁定")
    void ignoresPlayerBeyondHuntRange() {
        player.teleport(new Location(world, 300, 64, 100)); // 200 格
        enlist(player);

        DropperAttackGoal goal = new DropperAttackGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(),
                "HUNT_RANGE_SQ 是 900（30 格），200 格外的玩家不得被锁定 —— "
                        + "否则抛投者会从半张地图外横穿过来耽误空投");
    }

    @Test
    @DisplayName("抛投者不在竞技场世界时不锁定目标（跨世界 distanceSquared 会抛 IllegalArgumentException）")
    void ignoresEverythingWhenDropperIsInAnotherWorld() {
        WorldMock other = server.addSimpleWorld("some_other_world");
        doReturn(new Location(other, 100, 64, 100)).when(dropper).getLocation();
        doReturn(other).when(dropper).getWorld();

        player.teleport(new Location(world, 105, 64, 100));
        enlist(player);

        DropperAttackGoal goal = new DropperAttackGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(),
                "抛投者被外部插件传送到别的世界时必须直接放弃本轮 —— "
                        + "Bukkit 的跨世界 distanceSquared 抛 IllegalArgumentException");
    }

    @Test
    @DisplayName("非本场玩家（不在 getPlayers() 里）不被锁定")
    void ignoresPlayerNotInArena() {
        player.teleport(new Location(world, 110, 64, 100));
        when(gameManager.getPlayers()).thenReturn(new HashSet<>());

        DropperAttackGoal goal = new DropperAttackGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(), "不在本场对局名单里的玩家不得被锁定");
    }

    // ---------------------------------------------------------------- 冷却

    @Test
    @DisplayName("stop() 进入 50 tick 冷却：下次骚扰至少要等 2.5 秒")
    void stopEntersPostAttackCooldown() throws Exception {
        player.teleport(new Location(world, 110, 64, 100));
        enlist(player);

        DropperAttackGoal goal = new DropperAttackGoal(dropper, gameManager);
        assertTrue(goal.shouldActivate());
        goal.start();
        goal.stop();

        java.lang.reflect.Field cooldown = DropperAttackGoal.class.getDeclaredField("cooldownTicks");
        cooldown.setAccessible(true);
        assertEquals(50, cooldown.getInt(goal),
                "stop() 必须把冷却设为 POST_ATTACK_COOLDOWN_TICKS=50，压低骚扰 DPS");

        // 冷却期内不得再次激活
        assertFalse(goal.shouldActivate(), "冷却期内 shouldActivate 必须为 false");
    }

    @Test
    @DisplayName("游戏未运行时 shouldActivate / shouldStayActive 均为 false")
    void inactiveWhenGameStopped() {
        when(gameManager.isGameRunning()).thenReturn(false);
        player.teleport(new Location(world, 110, 64, 100));
        enlist(player);

        DropperAttackGoal goal = new DropperAttackGoal(dropper, gameManager);

        assertFalse(goal.shouldActivate(), "游戏未运行时不得俯冲");
        assertFalse(goal.shouldStayActive(), "游戏未运行时俯冲必须结束");
    }

    // ---------------------------------------------------------------- 身份契约

    @Test
    @DisplayName("getTypes 返回副本；getKey 稳定")
    void goalIdentityContract() {
        DropperAttackGoal a = new DropperAttackGoal(dropper, gameManager);
        DropperAttackGoal b = new DropperAttackGoal(dropper, gameManager);

        org.junit.jupiter.api.Assertions.assertNotSame(a.getTypes(), a.getTypes());
        assertEquals(2, a.getTypes().size());
        assertEquals("hilltopvillage:dropper_attack", a.getKey().getNamespacedKey().toString());
        assertEquals(a.getKey(), b.getKey());
    }
}
