package com.hilltopvillage.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.arena.MultiArenaManager;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * {@link FireballListener} 冷却记账的回归测试。
 *
 * <p><b>本轮瞄准的修复（round1）</b>：冷却从「每 2 tick 递减 2 的剩余计数」改成
 * 「到期 tick 绝对时间戳」。旧实现在周期任务里 {@code remaining -= 2}，而任务的步长是
 * 2 tick —— 于是<b>奇数配置必然被量化成偶数</b>：出厂默认 {@code cooldown-ticks: 3}
 * 实际生效成 4 tick。修复后 {@code cooldowns} 存的是 {@code nowTick + configuredTicks}
 * 原值，任意整数（含奇数）都精确生效，周期任务只做过期清理、不再参与精度。</p>
 *
 * <p><b>为什么断言私有 map 而不是只看「能不能再开一枪」</b>：只看行为的话，
 * 「量化成偶数」与「精确生效」在多数采样点上无法区分 —— 你必须恰好卡在
 * {@code now+3} 这个 tick 才能看出差别。直接断言
 * {@code cooldowns.get(uuid) == nowTick + configuredTicks} <b>精确相等</b>
 * 才是这条修复的契约本身，而且对 5 个奇数一次覆盖。</p>
 *
 * <p><b>MockBukkit 阻塞点（已实测）</b>：{@code WorldMock#spawnParticle} 的每个重载都是
 * 裸 {@code UnimplementedOperationException}，而 {@code tickTrails()} 在投射物存活
 * ≥4 tick 后无条件调用它。因此本组测试把 {@code fireball-max-travel-ticks} 设为
 * <b>0</b>：{@code TrailState.expireTicks} 随之变成 0，全局 ticker 的第一趟就在
 * {@code removeProjectile} 分支出队，永远走不到 {@code spawnParticle}。
 * 该值只影响飞行寿命，与冷却记账无关，是纯脚手架。</p>
 *
 * <p><b>顺带更正勘察报告的一条配方</b>：报告 §3.4 建议「每 tick 用
 * {@code world.getEntitiesByClass(Snowball.class)} 把投射物删掉」来规避粒子崩溃。
 * 实测<b>无效</b> —— {@code LivingEntityMock#launchProjectile} 产的 {@code SnowballMock}
 * 并<b>不在</b> {@code WorldMock} 的实体表里（{@code world.getEntitiesByClass(Snowball.class)}
 * 返回 0 个），所以那个循环是空转的。可用的做法只有两条：
 * 把 {@code max-travel-ticks} 设为 0（本类采用），或反射 {@code activeTrails} 拿
 * {@code TrailState.projectile} 手动 {@code remove()}。</p>
 */
@DisplayName("FireballListener 烈焰蛋冷却")
class FireballListenerTest extends MockBukkitTestBase {

    private static final String WORLD = "hilltop_arena1";

    private Logger logger;
    private WorldMock world;
    private GameConfig config;
    private PlayerMock player;
    private FireballListener listener;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("fireball-test-" + System.nanoTime());
        config = new GameConfig();
        // 屏障 1：投射物只活 0 tick —— 让全局 trail ticker 在第一次 run() 就出队，
        // 从而永远不触碰 WorldMock 未实现的 spawnParticle（见类注释）。
        config.setFireballMaxTravelTicks(0);
        // 近炸检测也关掉：即使将来 ticker 路径通了，也不会走到 triggerExplosion 的粒子。
        config.setFireballProximityFuse(0.0);

        HilltopVillagePlugin plugin = MechanicsFixtures.mockPlugin(logger, dataFolder.toFile(), config);

        world = server.addSimpleWorld(WORLD);

        ArenaInstance arena = mock(ArenaInstance.class);
        GameManager gameManager = mock(GameManager.class);
        when(arena.getGameManager()).thenReturn(gameManager);
        when(arena.getState()).thenReturn(GameState.RUNNING);
        when(arena.getArenaId()).thenReturn("arena1");
        PlayerData data = new PlayerData(UUID.randomUUID());
        // 存活双保险读的是 Player 重载；两个重载都桩，避免桩错版本后测试因错误的原因通过。
        when(gameManager.getPlayerData(any(Player.class))).thenReturn(data);
        when(gameManager.getPlayerData(any(UUID.class))).thenReturn(data);

        MultiArenaManager arenaManager = mock(MultiArenaManager.class);
        when(arenaManager.getPlayerArena(any(Player.class))).thenReturn(arena);
        when(arenaManager.getArenaByWorld(WORLD)).thenReturn(arena);
        when(plugin.getMultiArenaManager()).thenReturn(arenaManager);

        player = server.addPlayer("Fireballer");
        player.teleport(new Location(world, 8, 100, 8));
        player.setGameMode(GameMode.SURVIVAL);

        listener = new FireballListener(plugin);

        ItemStack stack = listener.createFireball();
        assertTrue(listener.isFireball(stack),
                "前置条件：工厂产物必须被 isFireball 识别，否则 onRightClick 首行就 return");
        stack.setAmount(64);
        player.getInventory().setItemInMainHand(stack);
    }

    /**
     * 停掉两条全局 ticker 并清空状态。
     *
     * <p>{@code shutdown()} 是公开且幂等的；不调的话 {@code @AfterEach} 的 unmock 之后
     * 仍可能有定时任务持有本用例的 mock 插件，跨用例互相干扰。</p>
     */
    @AfterEach
    void shutDownListener() {
        if (listener != null) {
            listener.shutdown();
        }
    }

    // ---------------------------------------------------------------- 辅助

    /** 触发一次右键投掷，返回投掷后主手物品的数量（物品被消耗 → 说明真的投出去了）。 */
    private int rightClick() {
        ItemStack held = player.getInventory().getItemInMainHand();
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_AIR, held, null, null, EquipmentSlot.HAND);
        listener.onRightClick(event);
        ItemStack after = player.getInventory().getItemInMainHand();
        return after == null ? 0 : after.getAmount();
    }

    /** 反射读 {@code cooldowns}（到期 tick 表）。字段改名/移除时这里给出可读的失败原因。 */
    @SuppressWarnings("unchecked")
    private Map<UUID, Long> cooldowns() {
        try {
            Field field = FireballListener.class.getDeclaredField("cooldowns");
            field.setAccessible(true);
            return (Map<UUID, Long>) field.get(listener);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "cooldowns 字段不存在或不可访问；若实现已改名/公开化，请同步本测试", e);
        }
    }

    /** 当前服务器 tick。 */
    private long now() {
        return server.getScheduler().getCurrentTick();
    }

    // ---------------------------------------------------------------- 1. 核心修复

    @Nested
    @DisplayName("冷却按到期 tick 精确记账（本轮修复）")
    class ExactExpiryBookkeeping {

        /**
         * 配置值必须被<b>原样</b>写成到期 tick —— 不取整、不量化、不加减。
         *
         * <p>取值集合故意混入奇数与偶数：</p>
         * <ul>
         *   <li>奇数（1/3/5/7）正是旧实现「每次 -2」会向上量化到偶数的点，
         *       其中 3 就是出厂默认值，也是作者最初报告的那一例；</li>
         *   <li>偶数（2/4）是对照组：一个「老是 +1」或「老是 +N」的错误实现
         *       即使在奇数上碰巧对上，也会在这里露馅。</li>
         * </ul>
         *
         * <p>本用例在每次采样之间调 {@code cleanupPlayer} 清掉条目，使每一轮都从「无冷却」
         * 起步（它是公开的幂等接口，不碰玩家实体，正好用作测试复位）。</p>
         */
        @Test
        @DisplayName("任意配置值（含奇数）都存成 nowTick + 配置原值")
        void storesExactExpiryTickForEveryConfiguredValue() {
            // 先把时钟推离 0：在 tick 0 上「无冷却」与「冷却为 0 tick」在算术上重合
            // （getOrDefault 的 0 减去 nowTick 的 0），会把比较运算符的错误混进来。
            server.getScheduler().performTicks(10);

            int[] values = {1, 2, 3, 4, 5, 7};
            for (int configured : values) {
                config.setFireballCooldownTicks(configured);
                listener.cleanupPlayer(player);

                long fireTick = now();
                rightClick();

                Long stored = cooldowns().get(player.getUniqueId());
                assertNotNull(stored,
                        "冷却配置为 " + configured + " 时必须写入到期 tick 条目"
                                + "（当前 tick " + fireTick + "）");
                assertEquals(fireTick + configured, stored.longValue(),
                        "冷却 " + configured + " tick 必须精确存成 " + (fireTick + configured)
                                + "（开火 tick " + fireTick + " + " + configured + "）。"
                                + "若这里读到偶数而配置是奇数，就是 round1 修掉的"
                                + "「每 2 tick 减 2 把奇数配置量化成偶数」bug —— "
                                + "出厂默认值 3 会实际生效成 4 tick");
            }
        }

        /** 配置 0 = 无冷却：既不写条目，也不该起清理任务。 */
        @Test
        @DisplayName("冷却配置为 0 时不写任何条目")
        void zeroCooldownWritesNoEntry() {
            config.setFireballCooldownTicks(0);
            listener.cleanupPlayer(player);

            rightClick();

            assertNull(cooldowns().get(player.getUniqueId()),
                    "cooldown-ticks: 0 的语义是「无冷却」，不得留下到期 tick 条目 —— "
                            + "否则 0 被当成一个真实冷却，玩家反而被挡住");
            assertTrue(cooldowns().isEmpty(), "无冷却时整张表应为空");
        }
    }

    // ---------------------------------------------------------------- 2. 边界行为

    @Nested
    @DisplayName("冷却边界：到期前挡住、到期当刻放行")
    class CooldownBoundary {

        /**
         * 成对断言，缺一不可：
         * <ul>
         *   <li>{@code now+1}（未到期）→ 必须<b>挡住</b>，物品数量不变；</li>
         *   <li>{@code now+3}（恰好到期）→ 必须<b>放行</b>，物品数量 -1。</li>
         * </ul>
         *
         * <p>只测「挡住」的话，一个「永远不让投」的实现也能通过；只测「放行」的话，
         * 一个「从不冷却」的实现也能通过。两半合起来才锁住
         * {@code remainingTicks > 0} 这个<b>严格大于</b>的比较 ——
         * 改成 {@code >= 0} 会在恰好到期的那个 tick 上多挡一帧，本测试即红。</p>
         *
         * <p>时间线（配置 3，开火于 tick 10 —— 故意不从 0 起步）：</p>
         * <pre>
         *   tick 10 投出，expiry = 13，物品 64 → 63
         *   tick 11 未到期（剩 2）→ 挡住，仍 63
         *   tick 13 恰好到期（剩 0）→ 放行，63 → 62
         * </pre>
         *
         * <p><b>为什么从 tick 10 起步而不是 tick 0</b>：「无冷却」在实现里表现为
         * {@code getOrDefault(uuid, 0) - nowTick}，从 tick 0 起步时它恰好也是 0 ——
         * 与「恰好到期」无法区分，于是把 {@code > 0} 误写成 {@code >= 0} 的实现在
         * <b>第一发</b>就被挡住，测试红了但报的是别的原因。从非零 tick 起步，
         * {@code >= 0} 只在真正的到期当刻咬人，失败信息才指向被测的那条边界。</p>
         */
        @Test
        @DisplayName("未到期挡住、恰好到期放行")
        void blocksBeforeExpiryAndAllowsExactlyAtExpiry() {
            config.setFireballCooldownTicks(3);
            listener.cleanupPlayer(player);
            // 先把时钟推离 0，理由见 javadoc
            server.getScheduler().performTicks(10);
            assertEquals(10L, now(), "前置条件：时间线应从 tick 10 起步");

            int startAmount = rightClick();
            assertEquals(63, startAmount, "第一发必须真的投出去（物品 64 → 63）");
            assertEquals(13L, cooldowns().get(player.getUniqueId()).longValue(),
                    "配置 3 tick、开火于 tick 10，到期 tick 必须精确存成 13。读到 14 就是"
                            + "round1 修掉的量化 bug；下面的时间线（挡住于 11、放行于 13）也以 13 为前提");

            // ---- 未到期：tick 11 ----
            server.getScheduler().performTicks(1);
            assertEquals(11L, now(), "前置条件：时间线推进到 tick 11");

            int atOne = rightClick();
            assertEquals(63, atOne,
                    "tick 11 时冷却还剩 2 tick，第二发必须被挡住。若物品变成 62，"
                            + "说明冷却判定没有生效（或者被量化/抵消）");

            // ---- 恰好到期：tick 13 ----
            server.getScheduler().performTicks(2);
            assertEquals(13L, now(), "前置条件：时间线推进到 tick 13");

            int atThree = rightClick();
            assertEquals(62, atThree,
                    "tick 13 恰好到期（remaining == 0），第三发必须放行把物品扣到 62。"
                            + "若仍是 63，说明比较被写成了 >= 0 —— 到期当刻还多挡一帧，"
                            + "奇数冷却会被白白吃掉一个 tick");
            assertEquals(16L, cooldowns().get(player.getUniqueId()).longValue(),
                    "放行后必须按新一次开火重写到 tick 13 + 3 = 16");
        }

        /** 冷却条目在玩家退出时被清掉（公开契约，也是上面各用例复位的依赖）。 */
        @Test
        @DisplayName("cleanupPlayer 清掉该玩家的冷却条目且不影响他人")
        void cleanupPlayerRemovesOnlyThatPlayersEntry() {
            config.setFireballCooldownTicks(40);
            player.setGameMode(GameMode.CREATIVE);   // 创造模式不消耗物品，便于连发采样
            rightClick();
            UUID first = player.getUniqueId();
            assertFalse(cooldowns().isEmpty(), "前置条件：应已写入冷却条目");

            PlayerMock other = server.addPlayer("Other");
            other.setGameMode(GameMode.CREATIVE);
            other.getInventory().setItemInMainHand(listener.createFireball());
            PlayerInteractEvent event = new PlayerInteractEvent(
                    other, Action.RIGHT_CLICK_AIR,
                    other.getInventory().getItemInMainHand(), null, null, EquipmentSlot.HAND);
            listener.onRightClick(event);
            assertEquals(2, cooldowns().size(), "前置条件：两名玩家各有一条冷却");

            listener.cleanupPlayer(player);

            assertNull(cooldowns().get(first), "cleanupPlayer 必须删掉该玩家的条目");
            assertNotNull(cooldowns().get(other.getUniqueId()),
                    "cleanupPlayer 只能删自己那条 —— 删到别人身上等于给全场送冷却清零");
        }
    }
}
