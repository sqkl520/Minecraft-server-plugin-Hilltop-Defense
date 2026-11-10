package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.ConfigManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * round5 F4 的<b>消费侧</b>安全网：{@code NodeSystem#loadConfig} 不得为空表补默认增益。
 *
 * <p><b>为什么这条必须单独守</b>：{@code ConfigManager} 侧把
 * {@code nodes.buff-effects: []} 正确读成空表之后，旧实现的 {@code NodeSystem} 会在
 * {@code buffEffects.isEmpty()} 时无条件补上 {@code RESISTANCE:1} + {@code REGENERATION:0}——
 * 于是「关掉全部节点增益」从另一条路径被撤销，而且补进来的 {@code RESISTANCE:1}
 * 与构造器预置的 {@code :0} 还不一致，玩家实际拿到的等级无从解释。
 * 只改 {@code ConfigManager} 的话，配置看起来是对的、游戏里却仍有增益，比原缺陷更难查。</p>
 *
 * <p><b>断言落在玩家身上</b>：{@code applyAllBuffs()} 是唯一的施加入口，用
 * {@code performTicks} 真实驱动 {@code BukkitRunnable}（与
 * {@code LocationParticlePreviewTest} 同款纪律），再读 {@code PlayerMock} 的
 * 效果集合——不断言私有字段，也不用反射。</p>
 */
class NodeBuffEffectsConsumerTest extends MockBukkitTestBase {

    private static final String WORLD = "hilltop_arena1";
    private static final int NODE_X = 10;
    private static final int NODE_Y = 64;
    private static final int NODE_Z = 10;

    private Logger logger;
    private WorldMock world;
    private PlayerMock player;
    private GameManager gameManager;
    private GameConfig config;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("node-buff-test-" + System.nanoTime());
        config = new GameConfig();
        config.setNodeBuffRadius(20.0);
        config.setNodeBuffRefreshTicks(20);

        HilltopVillagePlugin plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), config);
        world = server.addSimpleWorld(WORLD);

        player = server.addPlayer("alice");
        player.teleport(new Location(world, NODE_X, NODE_Y + 1, NODE_Z));

        // 先赋局部变量再 thenReturn（同 MechanicsFixtures 注释里的 Mockito 重入坑）
        ConfigManager configManager = plugin.getConfigManager();
        gameManager = mock(GameManager.class);
        when(gameManager.getConfigManager()).thenReturn(configManager);
        when(gameManager.getPlugin()).thenReturn(plugin);
        when(gameManager.getGameWorld()).thenReturn(world);
        when(gameManager.getArenaId()).thenReturn("arena1");
        when(gameManager.isGameRunning()).thenReturn(true);
        Set<UUID> players = Set.of(player.getUniqueId());
        when(gameManager.getPlayers()).thenReturn(players);
    }

    /** 放一个重生锚并注册节点（{@code addNode} 向下查一格，返回 0 = 成功）。 */
    private NodeSystem nodeSystemWithOneNode() {
        world.getBlockAt(NODE_X, NODE_Y, NODE_Z).setType(Material.RESPAWN_ANCHOR);
        NodeSystem system = new NodeSystem(gameManager);
        assertEquals(0, system.addNode(new Location(world, NODE_X, NODE_Y + 1, NODE_Z)),
                "前置条件：节点应被成功添加");
        return system;
    }

    private static boolean hasEffect(PlayerMock target, String effectName) {
        PotionEffectType type = PotionEffectType.getByName(effectName);
        return type != null && target.getPotionEffect(type) != null;
    }

    // ============================================================ ① 空表 = 不给任何增益

    @Test
    @DisplayName("buff-effects 为空时玩家站在活跃节点旁不获得任何增益（旧实现会补回 RESISTANCE）")
    void emptyBuffTableAppliesNothing() {
        config.getNodeBuffEffects().clear();
        for (String name : new String[] {"RESISTANCE", "REGENERATION"}) {
            assertFalse(hasEffect(player, name), "前置条件：" + name + " 应尚未生效");
        }

        NodeSystem system = nodeSystemWithOneNode();
        system.applyAllBuffs();
        server.getScheduler().performTicks(25);
        system.stopBuffTask();

        assertFalse(hasEffect(player, "RESISTANCE"),
                "★ 空表 = 管理员显式关掉了全部节点增益，玩家不得获得 RESISTANCE。"
                        + " 旧实现此处补 RESISTANCE:1 —— 「删了增益但游戏里还有」，"
                        + " 比原缺陷更难排查：配置看起来完全正确");
        assertFalse(hasEffect(player, "REGENERATION"),
                "REGENERATION 同样不得被凭空补出");
        assertEquals(0, player.getActivePotionEffects().size(),
                "空表时不应有任何效果；实际：" + player.getActivePotionEffects());
    }

    // ============================================================ ② 反向对照：非空照常生效

    /**
     * <b>反向对照</b>：把「补默认值」整段删掉之后，正常配置必须照旧生效。
     *
     * <p>守的是「别顺手把施加路径也改坏」——一个把 {@code applyBuffsToPlayer}
     * 改成早退的实现会在本条红，而①仍然绿喵。</p>
     */
    @Test
    @DisplayName("反向对照: 非空 buff-effects 仍照常施加，amplifier 取配置值")
    void nonEmptyBuffTableStillApplies() {
        config.getNodeBuffEffects().clear();
        config.getNodeBuffEffects().put(PotionEffectType.getByName("SPEED"), 2);

        NodeSystem system = nodeSystemWithOneNode();
        system.applyAllBuffs();
        server.getScheduler().performTicks(25);
        system.stopBuffTask();

        var effect = player.getPotionEffect(PotionEffectType.getByName("SPEED"));
        assertTrue(effect != null, "配置里有 SPEED 就必须真的施加，否则修复被做成了「一律不施加」");
        // 单个活跃节点 ⇒ effectiveAmplifier = min(1-1, cap) = 0 ⇒ 最终等级 = 配置值 + 0
        assertEquals(2, effect.getAmplifier(),
                "amplifier 应等于配置值（0 个叠加）—— 不能顺手改动叠加语义");
    }

    // ============================================================ ③ 反向对照：出厂配置

    @Test
    @DisplayName("反向对照: 出厂配置（RESISTANCE:0 / REGENERATION:0）仍给出两条增益")
    void factoryBuffTableAppliesBoth() {
        NodeSystem system = nodeSystemWithOneNode();
        system.applyAllBuffs();
        server.getScheduler().performTicks(25);
        system.stopBuffTask();

        assertTrue(hasEffect(player, "RESISTANCE"), "出厂配置必须继续生效 —— 这是绝大多数服务器的现状");
        assertTrue(hasEffect(player, "REGENERATION"), "出厂配置必须继续生效");
    }
}
